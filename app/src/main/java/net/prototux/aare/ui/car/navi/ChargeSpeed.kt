package net.prototux.aare.ui.car.navi

import net.prototux.aare.host.EnergyModel
import org.maplibre.spatialk.geojson.Position

/**
 * The speed worth driving, stop to stop (Navi L-056, 27.11.2026: "Das lohnt sich, wenn du mit 120 bis nach Potsdam
 * fährst, dann bist du schneller am Ziel, als wenn du mit 150 bis nach Beelitz fährst … von Stopp zu Stopp … mit
 * Geschwindigkeitsempfehlungen am Verkehrszeichen als freundlicher Hinweis"; "auf der Autobahn nicht unter 120 km/h,
 * nur wenn ich den Ladestopp vielleicht nicht erreiche: dann lieber 110 oder 100, abgestuft, so wie Tesla … nicht immer
 * gleich neu berechnen").
 *
 * The trip is planned once for each of [SPEEDS] on the open stretches (where there is no limit or one above it), each
 * with its own charging stops; the quickest to the destination wins, of nearly equals the slower. Then, the stops kept,
 * each stretch from stop to stop gets its own speed - a short charge after it may call for a slower one before it, a
 * long one or the destination near for a quicker one. Worked out at the start and anew when the car leaves a charging
 * stop; on the way only [stepDown] when the next stop comes into doubt.
 */
internal object ChargeSpeed {
    /** The speeds weighed on open stretches, km/h: never below 120 on the motorway when planning. */
    val SPEEDS = listOf(120, 130, 140, 150, 160)
    /** Down to these, one at a time, only when the next stop is in doubt on the way. */
    val SAFE = listOf(110, 100)
    /** Stretches the route takes at this or more are open ones (motorways, fast roads); slower ones stay as they are. */
    const val OPEN_KMH = 90.0
    /** Of speeds this close in time at the destination, the slower is recommended. */
    const val TIE_MIN = 2.0
    /** On the way, slower than planned only when the next stop's level on arrival falls below this (close to empty). */
    const val STEP_AT_PERCENT = 5.0
    /** A recommendation only where the open stretches a speed changes are this long at least. */
    const val MIN_OPEN_KM = 20.0

    /** The speed at a place: [v] on an open stretch where [limit] allows it, else the route's own ([base]). */
    fun speedAt(v: Int, base: Double, limit: Int?): Double = if (base < OPEN_KMH) base else when (limit) {
        NaviLimits.UNLIMITED -> v.toDouble()
        null, 0 -> minOf(v.toDouble(), base)
        else -> minOf(v.toDouble(), limit.toDouble())
    }

    /**
     * The speed function for a track: stretch k (up to [endsM][k] metres along, the last to the end) at [kmh][k], the
     * limits from [limitAt].
     */
    fun legs(kmh: List<Int>, endsM: List<Double>, limitAt: (Position) -> Int?): (Double, Position, Double) -> Double = { m, p, base ->
        val k = endsM.indexOfFirst { m <= it }.let { if (it < 0) kmh.lastIndex else it }.coerceIn(0, kmh.lastIndex)
        if (base < OPEN_KMH) base else speedAt(kmh[k], base, limitAt(p))
    }

    /** What is recommended: a speed per stretch from stop to stop ([kmh], [endsM]), the plan with it, the speeds weighed. */
    class Advice(val kmh: List<Int>, val endsM: List<Double>, val plan: ChargeChain.Result, val uniform: List<Option>) {
        /** The speed of the stretch [m] metres along. */
        fun at(m: Double): Int = kmh[endsM.indexOfFirst { m <= it }.let { if (it < 0) kmh.lastIndex else it }.coerceIn(0, kmh.lastIndex)]
        /** The quickest plan at one speed above the first stretch's, to show what driving faster would bring. */
        val faster: Option? get() = uniform.filter { it.kmh > kmh.first() }.minByOrNull { it.totalMinutes }
        /**
         * The same advice on the route as finally planned: the stretches ending at its stops [stopsM] (metres along) and
         * at [totalM]. The same number of stops: the speeds as they are; else each stretch takes the speed of the one its
         * end fell in before.
         */
        fun along(stopsM: List<Double>, totalM: Double): Advice {
            val ends = stopsM + totalM
            val k = if (ends.size == kmh.size) kmh else ends.map { at(it - 1.0) }
            return Advice(k, ends, plan, uniform)
        }
        fun describe() = "per stretch " + kmh.joinToString("/") + " km/h, %.0f min with %d stop(s); weighed: ".format(java.util.Locale.ROOT,
            plan.tripMinutes, plan.links.size) + uniform.joinToString { "%d km/h %.0f min (%d stop(s))".format(java.util.Locale.ROOT, it.kmh, it.totalMinutes, it.stops) }
    }

    /**
     * The speeds as kept while driving (and saved for going on after a stop): the stretch ending at each charger, known by
     * its place (the plan on the way may move or drop stops), and the last one to the destination.
     */
    class Legs(val toCharger: List<Pair<Position, Int>>, val toEnd: Int) {
        /** The speed of the stretch ending at [charger] (null: the destination); a charger the plan did not have: the slowest. */
        fun before(charger: Position?): Int = if (charger == null) toEnd
            else toCharger.firstOrNull { ChargerIndex.distanceM(it.first, charger) < SAME_M }?.second ?: (toCharger.map { it.second } + toEnd).min()
        /** The speeds of the stretches ending at [stops] (in order) and at the destination. */
        fun of(stops: List<ChargePlanner.Stop>): List<Int> = stops.map { before(it.charger.at) } + toEnd
        fun describe() = (toCharger.map { it.second } + toEnd).joinToString("/") + " km/h"
        companion object {
            fun of(a: Advice, stops: List<ChargePlanner.Stop>): Legs {
                val k = a.along(stops.map { it.atM }, a.endsM.lastOrNull() ?: 0.0).kmh
                return Legs(stops.mapIndexed { i, s -> s.charger.at to k[i] }, k.last())
            }
        }
    }
    private const val SAME_M = 150.0

    /** One speed weighed for the whole trip: the plan with it. */
    class Option(val kmh: Int, val plan: ChargeChain.Result) {
        val totalMinutes get() = plan.tripMinutes
        val stops get() = plan.links.size
    }

    /**
     * The advice for a route: [trackAt] its track with a speed function (null: its own speeds), [plan] the charging plan
     * on a track, [solve] the plan on a track over the given chargers only (the stops kept). Null where it is not worth a
     * word: too little open road, or no plan reaching the destination.
     */
    fun advise(trackAt: (((Double, Position, Double) -> Double)?) -> ChargePlanner.Track, limitFor: () -> ((Position) -> Int?),
               plan: (ChargePlanner.Track) -> ChargeChain.Result,
               solve: (ChargePlanner.Track, List<Charger>) -> ChargeChain.Result): Advice? {
        // a fresh look-up of the limits for each track made (a walker goes along the line once); local, as routes are
        // weighed side by side
        var current: ((Position) -> Int?)? = null
        val limitAt: (Position) -> Int? = { p -> current?.invoke(p) }
        fun fresh() { current = limitFor() }
        val total = trackAt(null).metres.lastOrNull() ?: return null
        // a faster speed counts only where it changes the trip against the slowest (a limit of 120 everywhere: none does)
        fresh()
        val slowest = trackAt(legs(listOf(SPEEDS.first()), listOf(total), limitAt))
        val uniform = SPEEDS.mapNotNull { v ->
            val t = if (v == SPEEDS.first()) slowest else { fresh(); trackAt(legs(listOf(v), listOf(total), limitAt)) }
            if (v != SPEEDS.first() && changedKm(slowest, t) < MIN_OPEN_KM) null
            else plan(t).takeIf { it.shortPercent <= ChargeChain.SHORT_OK }?.let { Option(v, it) }
        }
        if (uniform.size < 2) return null
        val quickest = uniform.minOf { it.totalMinutes }
        val first = uniform.filter { it.totalMinutes <= quickest + TIE_MIN }.minByOrNull { it.kmh } ?: return null
        // each stretch on its own, the stops kept: one pass over the stretches, each speed tried with the others held
        val chargers = first.plan.links.map { it.cand.charger }
        val ends = first.plan.links.map { it.cand.alongM } + total
        val kmh = MutableList(ends.size) { first.kmh }
        var best = first.plan
        for (k in kmh.indices) for (v in SPEEDS) {
            if (v == kmh[k]) continue
            val trial = kmh.toMutableList().also { it[k] = v }
            fresh()
            val r = solve(trackAt(legs(trial, ends, limitAt)), chargers)
            if (r.shortPercent > ChargeChain.SHORT_OK) continue
            // quicker by more than the tie, or as quick and slower
            if (r.tripMinutes < best.tripMinutes - TIE_MIN || (r.tripMinutes <= best.tripMinutes + 0.1 && v < kmh[k])) { kmh[k] = v; best = r }
        }
        return Advice(kmh, ends, best, uniform)
    }

    /**
     * On the way, Tesla's way: the next stop's level on arrival at the stretch's speed ([arriveAt] for a speed) below
     * [least] - one step slower, 110 and then 100, as long as that brings it back; null: no need, or none that helps.
     */
    fun stepDown(current: Int, least: Double, arriveAt: (Int) -> Double): Int? {
        if (arriveAt(current) >= least) return null
        for (v in SAFE.filter { it < current }) if (arriveAt(v) >= least) return v
        return SAFE.last().takeIf { it < current }
    }

    /** The share of energy per km at [to] against [from] km/h on the open road, by the car's model. */
    fun energyRatio(from: Int, to: Int, car: EnergyModel.Car): Double =
        (EnergyModel.tripW(to.toDouble(), car) / to) / (EnergyModel.tripW(from.toDouble(), car) / from)

    /** Kilometres where [t]'s speed differs from [own]'s (the open road a speed applies to). */
    private fun changedKm(own: ChargePlanner.Track, t: ChargePlanner.Track): Double {
        if (own.size != t.size || own.size < 2) return 0.0
        var km = 0.0
        for (i in 1 until own.size) {
            val dm = own.metres[i] - own.metres[i - 1]
            val a = own.seconds[i] - own.seconds[i - 1]; val b = t.seconds[i] - t.seconds[i - 1]
            if (dm > 0 && Math.abs(a - b) > 0.02 * a) km += dm / 1000
        }
        return km
    }
}
