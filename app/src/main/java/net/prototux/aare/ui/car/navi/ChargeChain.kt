package net.prototux.aare.ui.car.navi

import net.prototux.aare.host.EnergyModel
import org.maplibre.spatialk.geojson.Position
import kotlin.math.floor

/**
 * The chain of charging stops by dynamic programming (Navi L-029 step C, N-016 step 4: "die beste Kette von Stopps statt
 * gierig den weitesten"): of all ways over the [ChargeCorridor] candidates the one of the least time from driving there
 * and back, stopping and charging by the car's curve - on the condition that every charger and the destination is
 * reached with the driver's least level (phone app) and the buffer for the way to it ([ChargePlanner.Rules.buffer]).
 *
 * The levels the car leaves a charger with are steps of [STEP] %; the level it arrives with counts from the step below
 * (never more than it has). A plan that cannot keep the least levels anywhere (no charger in reach, a start below the
 * reserve) still gets the least shortfall: each percent short weighs [SHORT_MIN] minutes, far more than any detour, so
 * the plan is as good as the chargers allow and the shortfall is said. Further costs, in minutes:
 * [STOP_MIN] for each stop (in and out, plugging), a network outside the chosen ones [OUTSIDE_MIN], a charger the driver
 * rated poorly [POOR_MIN] or not yet [UNRATED_MIN] (L-006: the own ratings, never the server's), charging above the
 * driver's highest level [OVER_MIN] (O205: only where nothing else reaches).
 */
internal object ChargeChain {
    /** A stop of the chain: where, with what it arrives and leaves, and how long it charges. */
    class Link(val cand: ChargeCorridor.Candidate, val arrive: Double, val leave: Double, val minutes: Double, val overLimit: Boolean)

    /**
     * The chain found: its stops, the level at the destination, the cost the chain was chosen by (minutes with the
     * weights above), how much it falls short of the least levels (percent, 0 when it keeps them), and the trip's time.
     */
    class Result(val links: List<Link>, val arrivePercent: Double, val cost: Double, val shortPercent: Double, val tripMinutes: Double,
                 val candidates: Int, val micros: Long, val field: String = "") {
        val chargeMinutes get() = links.sumOf { it.minutes }
        fun stops() = links.map { ChargePlanner.Stop(it.cand.charger, it.cand.alongM, it.arrive, it.leave, it.minutes, it.overLimit) }
        fun describe() = if (links.isEmpty()) "no stop" else links.joinToString { l ->
            "%s %.0f->%.0f %% %.0f min".format(java.util.Locale.ROOT, l.cand, l.arrive, l.leave, l.minutes) }
    }

    /**
     * Everything a solve works on, worked out once: the nodes (0 the start at track index [fromIndex], 1..n the
     * candidates, n + 1 the destination), the energy between them ([scale]: what the car really took against the
     * model so far, for the planning on the way), the least levels and costs of each node, the charging times.
     */
    class Model(val track: ChargePlanner.Track, val cands: List<ChargeCorridor.Candidate>, car: EnergyModel.Car, val start: Double,
                val rules: ChargePlanner.Rules, val fromIndex: Int = 0, val scale: Double = 1.0, val step: Double = STEP) {
        val n = cands.size
        val dest = n + 1
        val levels = Math.round(100.0 / step).toInt()
        val capLevel = floor(rules.chargeUpTo / step + 1e-9).toInt().coerceIn(0, levels)
        private val capWh = car.capacityWh.toDouble()
        private val idx = IntArray(n + 2) { when (it) { 0 -> fromIndex; dest -> track.size - 1; else -> cands[it - 1].index } }
        private val fromM = track.metres[fromIndex]
        /** Percent of the way there and back for each candidate, half before and half after the stop. */
        private val halfDetour = DoubleArray(n + 2).also { a ->
            val whPerM = EnergyModel.tripW(ChargeCorridor.APPROACH_KMH, car) / ChargeCorridor.APPROACH_KMH / 1000.0 * scale
            for (k in 1..n) a[k] = cands[k - 1].detourM * whPerM / capWh * 100.0 / 2
        }
        private val required = DoubleArray(n + 2) { k ->
            if (k == 0) 0.0 else (if (k == dest) rules.atDestination else rules.atCharger) + rules.buffer((track.metres[idx[k]] - fromM) / 1000.0)
        }
        private val stopMin = DoubleArray(n + 2) { k -> if (k == 0 || k == dest) 0.0 else stopCost(cands[k - 1]) }
        /** The candidates the chain may not pass (the driver's own stops at a charger): the next one at or after each node. */
        val nextMandatory = IntArray(n + 2).also { a ->
            var next = dest
            for (k in dest downTo 0) { a[k] = next; if (k in 1..n && cands[k - 1].mandatory) next = k }
        }
        private val tables = HashMap<Int, DoubleArray>()
        private val table = Array(n + 2) { k -> if (k in 1..n) tables.getOrPut(cands[k - 1].charger.kw) { chargeTable(cands[k - 1].charger.kw, car, step, levels) } else null }

        fun required(k: Int) = required[k]
        fun stopMinutes(k: Int) = stopMin[k]
        /** Percent used from leaving node [i] to arriving at node [j]. */
        fun used(i: Int, j: Int) = (track.wh[idx[j]] - track.wh[idx[i]]) * scale / capWh * 100.0 + halfDetour[i] + halfDetour[j]
        /** Percent along the route alone from [i] to [j] (no detours), for cutting the search short. */
        fun along(i: Int, j: Int) = (track.wh[idx[j]] - track.wh[idx[i]]) * scale / capWh * 100.0
        /** Minutes charging at node [k] from level step 0 to [level] (cumulative). */
        fun charged(k: Int, level: Int) = table[k]!![level]
        /** Minutes charging at node [k] from [from] to [to] percent (any values, between the steps in a line). */
        fun chargeMinutes(k: Int, from: Double, to: Double): Double {
            if (to <= from) return 0.0
            val t = table[k]!!
            fun at(p: Double): Double { val x = (p / step).coerceIn(0.0, levels.toDouble()); val i = floor(x).toInt().coerceAtMost(levels - 1); return t[i] + (t[i + 1] - t[i]) * (x - i) }
            return at(to) - at(from)
        }
        fun bucket(p: Double) = floor(p / step + 1e-9).toInt().coerceIn(0, levels)
        fun over(level: Int) = if (level > capLevel) OVER_MIN else 0.0
        fun shortCost(k: Int, arrive: Double) = SHORT_MIN * maxOf(0.0, required[k] - arrive)
        /** Arriving at charger [k] with [arrive]: the shortfall, and of equals the lower level (on the point, L-029). */
        fun arriveCost(k: Int, arrive: Double) = shortCost(k, arrive) + TIE_LOW * arrive
        /** The destination's cost of arriving with [arrive]: the shortfall, and of equals the higher level. */
        fun endCost(arrive: Double) = shortCost(dest, arrive) - TIE_END * arrive
        fun driveMinutes() = (track.seconds.last() - track.seconds[fromIndex]) / 60.0

        private fun stopCost(c: ChargeCorridor.Candidate): Double {
            if (c.mandatory) return 0.0
            val rank = ChargerVisits.rank(rules.rating(c.charger))
            return STOP_MIN + c.detourS / 60.0 +
                (if (rules.preferred.isNotEmpty() && !ChargeCorridor.preferred(c.charger, rules)) OUTSIDE_MIN else 0.0) +
                (when (rank) { 0 -> POOR_MIN; 1 -> UNRATED_MIN; else -> 0.0 }) +
                // of equals the stronger charger, then the one further along
                TIE_KW * (MAX_KW - minOf(c.charger.kw, MAX_KW))
        }
    }

    /**
     * The best chain over [cands] (sorted along the route) from [start] % at track index [fromIndex]. Runs in
     * O(candidates in reach x candidates x levels); a few hundred candidates take milliseconds.
     */
    fun solve(track: ChargePlanner.Track, cands: List<ChargeCorridor.Candidate>, car: EnergyModel.Car, start: Double,
              rules: ChargePlanner.Rules, fromIndex: Int = 0, scale: Double = 1.0, step: Double = STEP): Result {
        val t0 = System.nanoTime()
        val m = Model(track, cands, car, start, rules, fromIndex, scale, step)
        val n = m.n; val dest = m.dest; val levels = m.levels
        val inf = Double.POSITIVE_INFINITY
        // arrive[j][b]: least cost to arrive at candidate j with level step b; where from (node, level step left with)
        val arrive = Array(n + 1) { if (it == 0) DoubleArray(0) else DoubleArray(levels + 1) { inf } }
        val fromNode = Array(n + 1) { if (it == 0) IntArray(0) else IntArray(levels + 1) { -1 } }
        val fromLevel = Array(n + 1) { if (it == 0) IntArray(0) else IntArray(levels + 1) { -1 } }
        // depart[i][L]: least cost to leave candidate i with level step L, and the arrival step it charged from
        val departArg = Array(n + 1) { if (it == 0) IntArray(0) else IntArray(levels + 1) { -1 } }
        var bestEnd = inf; var endNode = -1; var endLevel = -1
        val depart = DoubleArray(levels + 1)

        fun relax(i: Int, cost: Double, leave: Double, level: Int) {
            // on to the candidates in reach, not past the next of the driver's own stops
            val last = minOf(m.nextMandatory[i], n)
            for (j in i + 1..last) {
                if (m.along(i, j) > leave + REACH_SLACK) break
                val a = leave - m.used(i, j)
                if (a < 0) continue
                val b = m.bucket(a)
                val c = cost + m.stopMinutes(j) + m.arriveCost(j, a)
                if (c < arrive[j][b]) { arrive[j][b] = c; fromNode[j][b] = i; fromLevel[j][b] = level }
            }
            if (m.nextMandatory[i] == dest) {
                val c = cost + m.endCost(leave - m.used(i, dest))
                if (c < bestEnd) { bestEnd = c; endNode = i; endLevel = level }
            }
        }

        relax(0, 0.0, start, -1)
        for (i in 1..n) {
            // leaving with L: the cheapest arrival at or below L charged up to L
            var best = inf; var arg = -1
            for (l in 0..levels) {
                val v = arrive[i][l] - m.charged(i, l)
                if (v < best) { best = v; arg = l }
                if (arg < 0) { depart[l] = inf; departArg[i][l] = -1; continue }
                depart[l] = best + m.charged(i, l) + m.over(l)
                departArg[i][l] = arg
            }
            for (l in 0..levels) if (depart[l] < inf) relax(i, depart[l], l * step, l)
        }

        // back from the destination: each stop with the step it left with
        val chain = ArrayList<Pair<Int, Int>>()
        var node = endNode; var level = endLevel
        while (node > 0) {
            chain += node to level
            val b = departArg[node][level]
            val pn = fromNode[node][b]; val pl = fromLevel[node][b]
            node = pn; level = pl
        }
        chain.reverse()
        return exact(m, chain, bestEnd, System.nanoTime() - t0)
    }

    /**
     * The chain's levels and times worked out without steps: the arrival as it is, charged up to the step chosen (or
     * not at all where it arrives above it). Also the cost of [chain] by the same rules as [solve] (for the tests).
     */
    fun exact(m: Model, chain: List<Pair<Int, Int>>, cost: Double = cost(m, chain), nanos: Long = 0): Result {
        var level = m.start; var prev = 0
        var short = 0.0; var minutes = 0.0
        val links = chain.map { (k, l) ->
            val a = level - m.used(prev, k)
            short = maxOf(short, m.required(k) - a)
            val leave = maxOf(a, l * m.step)
            val min = m.chargeMinutes(k, a, leave)
            minutes += min + m.stopMinutes(k).coerceAtMost(STOP_MIN) + m.cands[k - 1].detourS / 60.0
            level = leave; prev = k
            Link(m.cands[k - 1], a, leave, min, leave > m.rules.chargeUpTo + 0.5)
        }
        val end = level - m.used(prev, m.dest)
        short = maxOf(short, m.required(m.dest) - end)
        return Result(links, end, cost, maxOf(0.0, short), m.driveMinutes() + minutes, m.n, nanos / 1000, field(m))
    }

    /**
     * The candidates for the log (19.11.2026, a stop at 38 % chosen where one 100 km on seemed to reach): each with the
     * level it is reached with straight from the start, the level it needs (least + buffer) and the minutes its stop
     * weighs (stop, detour, network, rating), so it can be seen why a charger was or was not taken.
     */
    fun field(m: Model): String = (1..m.n).joinToString("; ") { k ->
        "%s: %.0f %% straight, needs %.1f %%, stop %.0f min".format(java.util.Locale.ROOT, m.cands[k - 1], m.start - m.used(0, k), m.required(k), m.stopMinutes(k))
    }

    /** The cost of [chain] (node, level step left with) as [solve] counts it; infinite where it cannot be driven. */
    fun cost(m: Model, chain: List<Pair<Int, Int>>): Double {
        var cost = 0.0; var leave = m.start; var prev = 0
        for ((k, l) in chain) {
            if (k <= prev || m.nextMandatory[prev] < k) return Double.POSITIVE_INFINITY
            val a = leave - m.used(prev, k)
            if (a < 0) return Double.POSITIVE_INFINITY
            val b = m.bucket(a)
            if (l < b) return Double.POSITIVE_INFINITY
            cost += m.stopMinutes(k) + m.arriveCost(k, a) + m.charged(k, l) - m.charged(k, b) + m.over(l)
            leave = l * m.step; prev = k
        }
        if (m.nextMandatory[prev] != m.dest) return Double.POSITIVE_INFINITY
        return cost + m.endCost(leave - m.used(prev, m.dest))
    }

    /**
     * The chain for a route as the planner needs it: only the chosen networks where they reach (O213), else all of them;
     * where the strong chargers near the route do not reach, weaker or farther ones too (I-003: "a stop below the
     * reserve is better than none"). [mandatory]: the driver's own stops at chargers.
     */
    fun plan(track: ChargePlanner.Track, car: EnergyModel.Car, start: Double, near: (Position, Double, Int) -> List<Charger>,
             rules: ChargePlanner.Rules, fromIndex: Int = 0, scale: Double = 1.0, mandatory: List<Charger> = emptyList(),
             exact: Map<Charger, ChargeCorridor.Candidate> = emptyMap(), keep: List<Charger> = emptyList()): Result {
        // [exact]: the ways the map server gave (ChargeMatrix) in place of the estimate
        fun with(r: ChargePlanner.Rules) = solve(track, ChargeCorridor.candidates(track, near, r, fromIndex, mandatory, keep).map { c ->
            exact[c.charger]?.let { e -> c.withDetour(e.detourM, e.detourS) } ?: c }, car, start, r, fromIndex, scale)
        var best = with(rules)
        if (best.shortPercent > SHORT_OK && rules.strict) best = better(best, with(rules.copy(only = false)))
        if (best.shortPercent > SHORT_OK) best = better(best, with(rules.copy(only = false, minKw = minOf(rules.minKw, WIDE_KW),
            searchM = maxOf(rules.searchM, WIDE_M))))
        return best
    }

    private fun better(a: Result, b: Result) = if (b.shortPercent < a.shortPercent - SHORT_OK) b else a

    /** Minutes from empty up to each level step at a charger of [kw], by the car's curve. */
    fun chargeTable(kw: Int, car: EnergyModel.Car, step: Double, levels: Int): DoubleArray {
        val t = DoubleArray(levels + 1)
        val stepWh = car.capacityWh * step / 100.0
        for (k in 0 until levels) {
            val w = minOf(kw * 1000.0, EnergyModel.dcCurveW((k + 0.5) * stepWh, car)).coerceAtLeast(1_000.0)
            t[k + 1] = t[k] + stepWh / w * 60.0
        }
        return t
    }

    const val STEP = 1.0
    const val STOP_MIN = 4.0
    const val OUTSIDE_MIN = 15.0
    const val POOR_MIN = 20.0
    const val UNRATED_MIN = 3.0
    const val OVER_MIN = 60.0
    const val SHORT_MIN = 1000.0
    /** A shortfall below this counts as none (the steps' rounding). */
    const val SHORT_OK = 0.05
    private const val TIE_KW = 0.0005
    private const val MAX_KW = 400
    private const val TIE_END = 0.0001
    /**
     * Of chains equal in time the one arriving lower at its chargers (Navi L-029: "wenn ich mit 5 % am Lader ankommen
     * will, wäre nicht schlecht, wenn er das auch ungefähr erreicht"): the car charges as fast from 5 as from 25 %.
     */
    private const val TIE_LOW = 0.02
    /** The road may give a little back going down: candidates are looked at a little beyond what the level reaches. */
    private const val REACH_SLACK = 5.0
    private const val WIDE_KW = 50
    private const val WIDE_M = 5_000.0
}
