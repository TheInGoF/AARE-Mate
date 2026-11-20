package net.prototux.aare.ui.car.navi

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.maplibre.spatialk.geojson.Position

/**
 * The chargers a plan may stop at (Navi L-029 step B, N-016 step 3): those of Mate's charger index near the route,
 * found on the device; the network filter and the driver's own ratings (L-006) as before. The way to a charger and
 * back is first reckoned from the distance as the crow flies ([ROAD] x there and back); where a charger lies further
 * off, the map server's matrix ([ChargeMatrix], one request for all of them) gives the real way.
 */
internal object ChargeCorridor {
    /**
     * A charger by the route: [index] the nearest point of the track, [offM] how far off the route it lies, [detourM] and
     * [detourS] the way there and back on top of the route ([exact]: from the map server), [mandatory] the driver's own
     * stop at a charger (O225: charged there, never left out).
     */
    class Candidate(val charger: Charger, val index: Int, val alongM: Double, val offM: Double, val detourM: Double,
                    val detourS: Double, val exact: Boolean = false, val mandatory: Boolean = false) {
        fun withDetour(m: Double, s: Double) = Candidate(charger, index, alongM, offM, m, s, true, mandatory)
        override fun toString() = "%s %d kW at %.0f km%s".format(java.util.Locale.ROOT, charger.network ?: "other", charger.kw, alongM / 1000,
            if (offM > 300) " (%.1f km off)".format(java.util.Locale.ROOT, offM / 1000) else "")
    }

    /** The way there and back for a charger [offM] off the route, as the crow flies x [ROAD] each way. */
    fun estimatedDetourM(offM: Double) = 2 * offM * ROAD
    /** ... and its time at [APPROACH_KMH]. */
    fun estimatedDetourS(offM: Double) = estimatedDetourM(offM) / (APPROACH_KMH / 3.6)

    /**
     * The chargers of at least [ChargePlanner.Rules.minKw] within [ChargePlanner.Rules.searchM] of the route from track
     * index [fromIndex] on, each at its nearest point; the strict network filter applied ([ChargePlanner.Rules.strict]);
     * where many stand close together along the route only the best [PER_BIN] of each [BIN_M] (chosen network, rating,
     * power, nearest to the road). Sorted along the route, the same order for the same input.
     */
    fun candidates(track: ChargePlanner.Track, near: (Position, Double, Int) -> List<Charger>, rules: ChargePlanner.Rules,
                   fromIndex: Int = 0, mandatory: List<Charger> = emptyList(), keep: List<Charger> = emptyList()): List<Candidate> {
        val best = HashMap<Charger, Pair<Int, Double>>()
        var lastM = -1e9
        for (i in fromIndex until track.size) {
            if (track.metres[i] - lastM < SAMPLE_M && i != track.size - 1) continue
            lastM = track.metres[i]
            for (c in near(track.points[i], rules.searchM, rules.minKw)) {
                if (rules.strict && rules.outside(c)) continue
                if (c in best) continue
                best[c] = nearest(track, c.at, i, fromIndex, rules.searchM + SAMPLE_M)
            }
        }
        // the driver's own stops at a charger: on the route, whatever their power or network; [keep]: those planned already
        for (c in mandatory + keep) if (c !in best) best[c] = nearest(track, c.at, null, fromIndex, 0.0)
        val all = best.map { (c, at) ->
            Candidate(c, at.first, track.metres[at.first], at.second, estimatedDetourM(at.second), estimatedDetourS(at.second),
                mandatory = c in mandatory)
        }
        // the same place listed twice (two operators' entries of one site): the stronger one
        val sites = all.groupBy { "%.4f,%.4f".format(java.util.Locale.ROOT, it.charger.at.latitude, it.charger.at.longitude) }
            .values.map { same -> same.firstOrNull { it.mandatory || it.charger in keep } ?: same.maxWith(compareBy<Candidate> { it.charger.kw }.thenBy { it.charger.operator }) }
        val kept = sites.groupBy { (it.alongM / BIN_M).toInt() }.values.flatMap { bin ->
            bin.sortedWith(compareByDescending<Candidate> { it.mandatory || it.charger in keep }.thenByDescending { preferred(it.charger, rules) }
                .thenByDescending { ChargerVisits.rank(rules.rating(it.charger)) }.thenByDescending { it.charger.kw }
                .thenBy { it.offM }.thenBy { it.charger.at.latitude }.thenBy { it.charger.at.longitude }).let { s ->
                val fixed = s.filter { it.mandatory || it.charger in keep }
                fixed + (s - fixed.toSet()).take(PER_BIN) }
        }
        return kept.sortedWith(compareBy<Candidate> { it.alongM }.thenBy { it.charger.at.latitude }.thenBy { it.charger.at.longitude })
    }

    /** Whether [c] is of a chosen network. */
    fun preferred(c: Charger, rules: ChargePlanner.Rules) = c.network != null && c.network in rules.preferred

    /** The track point nearest to [at], looked for within [lookM] around [around] (or along the whole track), and the distance to it. */
    private fun nearest(track: ChargePlanner.Track, at: Position, around: Int?, fromIndex: Int, lookM: Double): Pair<Int, Double> {
        var from = fromIndex; var to = track.size - 1
        if (around != null) {
            val m = track.metres[around]
            from = maxOf(fromIndex, firstAtLeast(track.metres, m - lookM) - 1)
            to = minOf(track.size - 1, firstAtLeast(track.metres, m + lookM))
        }
        var bi = from; var bd = Double.MAX_VALUE
        for (i in from..to) { val d = ChargerIndex.distanceM(track.points[i], at); if (d < bd) { bd = d; bi = i } }
        return bi to bd
    }

    /** The first index whose metres reach [m] (the track's metres rise). */
    private fun firstAtLeast(metres: DoubleArray, m: Double): Int {
        var lo = 0; var hi = metres.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (metres[mid] < m) lo = mid + 1 else hi = mid }
        return lo
    }

    /** Off the route by more than this, the way there is asked of the map server when the charger is chosen. */
    const val EXACT_FROM_M = 800.0
    /** Roads are longer than the crow flies. */
    const val ROAD = 1.3
    /** Speed on the way to a charger off the motorway. */
    const val APPROACH_KMH = 50.0
    private const val SAMPLE_M = 1_000.0
    private const val BIN_M = 3_000.0
    private const val PER_BIN = 3
}

/**
 * The real way to chargers off the route (Navi L-029 step B: "wo nötig genau per einem Valhalla-Matrix-Aufruf"): one
 * request to the map server's `sources_to_targets` from the route point next to each charger to the charger; the way
 * back counts as long as the way there.
 */
internal object ChargeMatrix {
    /** The most chargers asked in one request. */
    const val MAX = 10

    fun request(cands: List<ChargeCorridor.Candidate>, track: ChargePlanner.Track): String {
        fun loc(p: Position) = org.json.JSONObject().put("lat", p.latitude).put("lon", p.longitude)
        return org.json.JSONObject().put("costing", "auto").put("units", "kilometers")
            .put("sources", org.json.JSONArray(cands.map { loc(track.points[it.index]) }))
            .put("targets", org.json.JSONArray(cands.map { loc(it.charger.at) })).toString()
    }

    /** The candidates with the ways of the answer (there and back, the time too); those without an answer as they were. */
    fun apply(cands: List<ChargeCorridor.Candidate>, json: String): List<ChargeCorridor.Candidate> {
        val rows = org.json.JSONObject(json).optJSONArray("sources_to_targets") ?: return cands
        return cands.mapIndexed { i, c ->
            val cell = rows.optJSONArray(i)?.optJSONObject(i) ?: return@mapIndexed c
            val km = cell.optDouble("distance", Double.NaN); val s = cell.optDouble("time", Double.NaN)
            if (km.isNaN() || s.isNaN()) c else c.withDetour(2 * km * 1000, 2 * s)
        }
    }

    /** Asks the map server for the ways to [cands] (at most [MAX]); blocking. */
    fun fetch(http: okhttp3.OkHttpClient, valhalla: String, cands: List<ChargeCorridor.Candidate>, track: ChargePlanner.Track): List<ChargeCorridor.Candidate> {
        val ask = cands.take(MAX)
        val body = request(ask, track).toRequestBody("application/json".toMediaType())
        return http.newCall(okhttp3.Request.Builder().url("$valhalla/sources_to_targets").post(body).build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("matrix: HTTP ${r.code}")
            apply(ask, r.body.string())
        }
    }
}
