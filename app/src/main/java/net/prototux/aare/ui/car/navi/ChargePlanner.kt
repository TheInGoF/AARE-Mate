package net.prototux.aare.ui.car.navi

import net.prototux.aare.host.EnergyModel
import org.maplibre.spatialk.geojson.Position

/**
 * Charging stops for a route (O182, O187: "laderouten planung … von google abgucken, aber clean code"). The rules are
 * what Google Maps visibly does, our own code:
 *  - the battery is followed along the route with the car's energy model; below [Rules.atDestination] on arrival the
 *    route needs stops;
 *  - a stop is a fast charger close to the route, reached with at least [Rules.atCharger]; the chain of stops is the one
 *    of least time from driving, the way to the chargers, stopping and charging ([ChargeChain], Navi L-029);
 *  - it charges only what is needed for the rest of the way (plus the reserve and buffer), at most [Rules.chargeUpTo] -
 *    above it the car charges slowly and the next stop is quicker;
 *  - charging time from the car's charging curve, limited by the charger.
 * Pure arithmetic: the route comes in as a [Track], the chargers from a lookup; replanning the route over the stops is
 * the caller's (Valhalla), which then asks [replay] for the times on the new route.
 */
internal object ChargePlanner {
    data class Rules(val atCharger: Double = 10.0, val atDestination: Double = 10.0, val chargeUpTo: Double = 80.0,
                     val minKw: Int = 100, val searchM: Double = 2_000.0, val preferred: Set<String> = emptySet(),
                     val only: Boolean = false,
                     /** O226: the driver's rating of a charger (0 none, 1-5), for choosing among those allowed. */
                     val rating: (Charger) -> Int = { 0 },
                     /** Navi L-029 step D: percent on top of the least levels for a stop this many km ahead. */
                     val buffer: (Double) -> Double = { 0.0 },
                     /** Navi O-015, Q-050: a stop that charges charges at least this long (0: as short as it needs). */
                     val minMinutes: Double = 0.0) {
        /** Strictly the chosen networks (O213). */
        val strict get() = only && preferred.isNotEmpty()
        /** A charger outside the chosen networks, when there are some. */
        fun outside(c: Charger) = preferred.isNotEmpty() && (c.network == null || c.network !in preferred)
        companion object {
            /** The driver's own values from the phone app (O205). */
            fun of(s: net.prototux.aare.Settings) = Rules(s.chargeMinAtCharger.toDouble(), s.chargeMinAtDestination.toDouble(),
                s.chargeMaxLevel.toDouble(), preferred = s.chargeNetworks, only = s.chargeNetworksOnly,
                rating = { ch -> ChargerVisits.rating(s.appContext, ch) }, minMinutes = s.chargeMinMinutes.toDouble())
                .also { stopExtraMinutes = s.chargeStopExtraMin.toDouble() }
        }
    }

    /** The route as the planner sees it: points with distance (m), time (s) and energy (Wh) from the start. */
    class Track(val points: List<Position>, val metres: DoubleArray, val seconds: DoubleArray, val wh: DoubleArray,
                /** What the energy was made of (climb, wind, temperature, rain), for the log. */
                val parts: ChargeEnergy.Parts? = null) {
        val size get() = points.size
    }

    /** [overLimit]: charged above the driver's highest level, because nothing else reaches on from there (O205). */
    class Stop(val charger: Charger, val atM: Double, val arrivePercent: Double, val leavePercent: Double, val chargeMinutes: Double,
               val overLimit: Boolean = false) {
        /** The time the stop takes: the charging and what goes with it ([stopExtraMinutes]), for arrival times. */
        val stayMinutes get() = chargeMinutes + stopExtraMinutes
    }
    class Plan(val stops: List<Stop>, val arrivePercent: Double) {
        val chargeMinutes get() = stops.sumOf { it.chargeMinutes }
        /** The time all stops take, for the arrival time. */
        val stayMinutes get() = stops.sumOf { it.stayMinutes }
    }

    /**
     * Minutes a charging stop takes beyond the charging itself (Ingo, 22.11.2026: "Bis sich die Ladung aufgebaut hat,
     * dauert es meistens schon eine Minute … Das Einparken, Anhalten, Anstecken kann man wahrscheinlich auch nochmal 2
     * Minuten draufrechnen … Eventuell einstellbar in den Ladeeinstellungen"): parking, plugging in, the power coming up.
     * The driver's value (Settings.chargeStopExtraMin), set whenever the rules are read.
     */
    @Volatile var stopExtraMinutes = 3.0

    /**
     * The times at [stops] once the route goes over them (Valhalla replanned it): each charger at its nearest point of
     * the new track, charged to what the rest needs as in [ChargeChain.plan].
     */
    fun replay(track: Track, car: EnergyModel.Car, startPercent: Double, stops: List<Charger>, rules: Rules = Rules(),
               above: Set<Charger> = emptySet()): Plan {
        val pct = { wh: Double -> wh / car.capacityWh * 100.0 }
        // each stop's place on the new track, in order
        var from = 0
        val at = stops.map { c -> ((from until track.size).minByOrNull { ChargerIndex.distanceM(track.points[it], c.at) } ?: from).also { from = it } }
        var level = startPercent; var fromI = 0
        val out = stops.mapIndexed { k, c ->
            val i = at[k]
            val arrive = level - pct(track.wh[i] - track.wh[fromI])
            // a stop the plan had to charge above the highest level (O205) may do so on the new route too
            val cap = if (c in above) FULL else rules.chargeUpTo
            var leave = minOf(cap, maxOf(arrive, pct(track.wh.last() - track.wh[i]) + rules.atDestination + MARGIN))
            // enough at least for the next stop with its reserve - a stop moved earlier (F3, another charger chosen)
            // left the next one at 8 % (16.11.2026); more than the highest level only then, marked as over it
            at.getOrNull(k + 1)?.let { j ->
                val needNext = pct(track.wh[j] - track.wh[i]) + rules.atCharger + MARGIN
                if (leave < needNext) leave = minOf(FULL, needNext)
            }
            leave = longEnough(leave, rules) { chargeMinutes(arrive, it, c.kw, car) }
            Stop(c, track.metres[i], arrive, leave, chargeMinutes(arrive, leave, c.kw, car), leave > rules.chargeUpTo + 0.5)
                .also { level = leave; fromI = i }
        }
        return Plan(out, level - pct(track.wh.last() - track.wh[fromI]))
    }

    /** Another charger for a stop: which, and how far before (negative) or after it lies along the route. */
    class Alternative(val charger: Charger, val shiftM: Double)

    /**
     * Other chargers for [stop] on [track] (F3, as ABRP offers them): strong ones near the route from [BEFORE_M] before to
     * [AFTER_M] after it, not the stop itself nor another planned one; preferred networks first, then the strongest,
     * then the nearest to the stop; at most [max].
     */
    fun alternatives(track: Track, stop: Stop, planned: List<Charger>, chargers: (Position, Double, Int) -> List<Charger>,
                     rules: Rules = Rules(), max: Int = 3): List<Alternative> {
        val found = LinkedHashMap<Charger, Double>()
        var lastM = -1e9
        for (i in 0 until track.size) {
            val m = track.metres[i]
            if (m < stop.atM - BEFORE_M || m > stop.atM + AFTER_M || m - lastM < SAMPLE_M) continue
            lastM = m
            for (c in chargers(track.points[i], rules.searchM, rules.minKw)) {
                if (planned.any { ChargerIndex.distanceM(it.at, c.at) < 150 } || c in found) continue
                found[c] = m - stop.atM
            }
        }
        // only the chosen networks offered (O213) - none of them near: no other (16.11.2026: Shell and Pfalzwerke were
        // offered with only Tesla and IONITY chosen)
        return (if (rules.strict) found.entries.filter { !rules.outside(it.key) } else found.entries).sortedWith(compareByDescending<Map.Entry<Charger, Double>> { it.key.network != null && it.key.network in rules.preferred }
            .thenByDescending { it.key.kw }.thenBy { Math.abs(it.value) })
            .distinctBy { "%.3f,%.3f".format(java.util.Locale.ROOT, it.key.at.latitude, it.key.at.longitude) }
            .take(max).map { Alternative(it.key, it.value) }
    }

    private const val BEFORE_M = 25_000.0
    private const val AFTER_M = 10_000.0

    /**
     * The level to leave with so that the stop charges at least [Rules.minMinutes] (Navi O-015, Q-050; Ingo, 22.11.2026:
     * "Q50 ja, Option in den Einstellungen"): a stop of 1 min (22 -> 24 %) costs the way there and plugging in all the same,
     * so it charges on, in 1 % steps, up to the highest level - never above it for this, and never less than [leave].
     * [minutes] gives the charging time up to a level.
     */
    fun longEnough(leave: Double, rules: Rules, minutes: (Double) -> Double): Double {
        if (rules.minMinutes <= 0.0 || leave >= rules.chargeUpTo || minutes(leave) >= rules.minMinutes) return leave
        var l = leave
        while (l < rules.chargeUpTo && minutes(l) < rules.minMinutes) l = minOf(rules.chargeUpTo, l + 1.0)
        return l
    }

    /** Minutes from [fromPercent] to [toPercent] in 1 % steps at the lower of the charger and the car's curve. */
    fun chargeMinutes(fromPercent: Double, toPercent: Double, chargerKw: Int, car: EnergyModel.Car): Double {
        var p = fromPercent; var hours = 0.0
        val stepWh = car.capacityWh / 100.0
        while (p < toPercent) {
            val w = minOf(chargerKw * 1000.0, EnergyModel.dcCurveW(p / 100.0 * car.capacityWh, car)).coerceAtLeast(1_000.0)
            hours += stepWh * minOf(1.0, toPercent - p) / w
            p += 1.0
        }
        return hours * 60.0
    }

    /**
     * The track of a Ferrostar route: each piece at the speed of the step it belongs to, its energy from [ChargeEnergy]
     * (Navi L-029: climb from [heights], temperature, wind and rain from [weather] where and when the car gets there).
     * [rainKwh100]: IDmate's learned extra for rain at a place reached so many seconds from now ([NaviRain]); while IDmate
     * has learned none, the forecast's rain counts instead.
     */
    fun track(route: uniffi.ferrostar.Route, car: EnergyModel.Car,
              rainKwh100: ((Position, Double) -> Double)? = if (NaviRain.active) NaviRain::kwh100At else null,
              heights: ChargeEnergy.Heights? = null, weather: ChargeEnergy.Weather? = null,
              /** Navi L-056: the speed driven at a point instead of the route's (metres along, its place, the route's speed there). */
              speed: ((Double, Position, Double) -> Double)? = null): Track {
        val pts = route.geometry.map { Position(longitude = it.lng, latitude = it.lat) }
        val kmh = DoubleArray(pts.size)
        // the steps' ends along the route, for each piece's speed
        val stepEnd = route.steps.runningFold(0.0) { a, st -> a + st.distance }.drop(1)
        // the steps on a ferry (Valhalla's answer, NaviFerries): the car rides along, no energy
        val ferrySteps = NaviFerries.steps(route)
        val ferry = ferrySteps?.let { BooleanArray(pts.size) }
        var k = 0; var m = 0.0
        for (i in 1 until pts.size) {
            m += ChargerIndex.distanceM(pts[i - 1], pts[i])
            while (k < route.steps.lastIndex && m > stepEnd[k]) k++
            val st = route.steps[k]
            kmh[i] = if (st.duration > 0) (st.distance / st.duration * 3.6).coerceIn(5.0, 200.0) else 50.0
            if (speed != null) kmh[i] = speed(m, pts[i], kmh[i])
            if (ferry != null) ferry[i] = ferrySteps[k]
        }
        return ChargeEnergy.track(pts, kmh, car, heights, weather, rainKwh100, ferry = ferry)
    }

    private const val FULL = 100.0
    private const val MARGIN = 3.0          // % on top of the destination's reserve: the model is an estimate
    private const val SAMPLE_M = 1_000.0
}
