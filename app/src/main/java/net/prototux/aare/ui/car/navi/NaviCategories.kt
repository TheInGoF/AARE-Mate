package net.prototux.aare.ui.car.navi

import org.maplibre.spatialk.geojson.Position

/**
 * Categories in the search (F6 step 3, O189 "Kategorien (Lader, Rastplatz)", as Google Maps offers them): the nearest
 * fast chargers or service areas - while guiding the ones ahead on the route (within [ROUTE_M] of it) by the way to
 * them, otherwise the ones around by the straight distance. A typed word can ask for them too.
 */
internal object NaviCategories {
    enum class Kind { CHARGER, REST }

    /** One found: where, its line in the list, how far (metres; along the route while guiding). */
    class Found(val at: Position, val metres: Double, val charger: Charger? = null, val rest: NaviRest.Stop? = null)

    private val WORDS = mapOf(
        Kind.CHARGER to listOf("lader", "laden", "ladesaule", "ladestation", "schnelllader", "supercharger", "ladepunkt"),
        Kind.REST to listOf("rastplatz", "raststatte", "rasthof", "rastanlage", "pause"))

    /** The category a typed text asks for, if it is one of its words (forgiving typos). */
    fun asked(query: String): Kind? {
        val w = NaviFuzzy.words(query).singleOrNull() ?: return null
        if (w.length < 4) return null
        return WORDS.entries.firstOrNull { (_, words) -> words.any { NaviFuzzy.wordCost(w, it) != null } }?.key
    }

    /**
     * The nearest [max] of [kind]: along [route] from [drivenM] when a route is guided, else around [here] within
     * [AROUND_M]. [chargers] and [rest] look up what lies near a point.
     */
    fun find(kind: Kind, here: Position, route: List<Position>?, drivenM: Double, chargers: (Position, Double) -> List<Charger>,
             rest: (Position, Double) -> List<NaviRest.Stop>, max: Int = 8): List<Found> {
        fun lookup(p: Position, r: Double): List<Found> = when (kind) {
            Kind.CHARGER -> chargers(p, r).map { Found(it.at, 0.0, charger = it) }
            Kind.REST -> rest(p, r).map { Found(it.at, 0.0, rest = it) }
        }
        if (route == null || route.size < 2) {
            return lookup(here, AROUND_M).map { Found(it.at, ChargerIndex.distanceM(here, it.at), it.charger, it.rest) }
                .sortedBy { it.metres }.distinctBy { it.at }.take(max)
        }
        // along the route ahead: looked up every STEP_M, each found measured at the point of the route nearest to it -
        // not at the point it was found from, which put all within ROUTE_M of the car at "0 m" (bauplan step 12);
        // what lies behind the car is left out
        val cum = DoubleArray(route.size)
        for (i in 1 until route.size) cum[i] = cum[i - 1] + ChargerIndex.distanceM(route[i - 1], route[i])
        val out = LinkedHashMap<Position, Found>()
        var walked = STEP_M
        for (i in route.indices) {
            if (i > 0) walked += cum[i] - cum[i - 1]
            if (cum[i] < drivenM || walked < STEP_M) continue
            walked = 0.0
            for (f in lookup(route[i], ROUTE_M)) {
                if (f.at in out) continue
                var j = i
                while (j > 0 && cum[i] - cum[j - 1] <= ROUTE_M + STEP_M) j--
                var along = cum[i]; var bestD = Double.MAX_VALUE
                while (j + 1 < route.size && cum[j] - cum[i] <= ROUTE_M + STEP_M) {
                    val (t, d) = onSegment(route[j], route[j + 1], f.at)
                    if (d < bestD) { bestD = d; along = cum[j] + t * (cum[j + 1] - cum[j]) }
                    j++
                }
                if (along < drivenM - BEHIND_M) continue
                out[f.at] = Found(f.at, maxOf(0.0, along - drivenM), f.charger, f.rest)
            }
            if (out.size >= max * 2) break
        }
        return out.values.sortedBy { it.metres }.take(max)
    }

    /** Where [q] meets the segment [a]-[b] (0..1) and how far off it is, in metres (flat near the segment). */
    private fun onSegment(a: Position, b: Position, q: Position): Pair<Double, Double> {
        val k = Math.cos(Math.toRadians(a.latitude)) * 111_320.0
        val bx = (b.longitude - a.longitude) * k; val by = (b.latitude - a.latitude) * 111_320.0
        val qx = (q.longitude - a.longitude) * k; val qy = (q.latitude - a.latitude) * 111_320.0
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else ((qx * bx + qy * by) / len2).coerceIn(0.0, 1.0)
        return t to Math.hypot(qx - t * bx, qy - t * by)
    }

    const val ROUTE_M = 1_000.0
    const val AROUND_M = 25_000.0
    private const val STEP_M = 500.0
    /** Just passed still counts as here: the point of the car on the route lags a little. */
    private const val BEHIND_M = 50.0
}
