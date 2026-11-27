package net.prototux.aare.ui.car.navi

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.spatialk.geojson.Position

/**
 * The speed limits along a route (bauplan step 6, N2): Valhalla's route in Ferrostar's OSRM form carries none, so the
 * route's line goes once more to Valhalla's trace_attributes (/route/trace_attributes behind the gate) and comes back
 * with the limit of each road piece by its points. While driving the limit is looked up by the distance driven along the
 * route, not by the nearest point: in the Tiergarten tunnel (few points, a long straight tube) the dense end of the same
 * route at the Pariser Platz lay nearer, its 20 showed in the tunnel and stuck (16.11.2026: "er zeigt 20 im tunnel an.
 * das kann nicht stimmen", "die 20 bleiben hängen").
 */
internal class NaviLimits internal constructor(private val points: List<Position>, private val limit: IntArray,
                                               /** The route these are the limits of; read only on it (Navi I-069). */
                                               val route: Any? = null) {
    /**
     * Whether these limits belong to [guided] (Navi I-069, 27.11.2026: warned at 140 where there is no limit): they load in
     * the background, and a new route meanwhile got the old route's limits - read by the distance along the new one, from
     * somewhere else entirely.
     */
    fun of(guided: Any?): Boolean = route == null || route === guided

    /** Metres from the start to each point. */
    private val metres = DoubleArray(points.size).also { m ->
        for (i in 1 until points.size) m[i] = m[i - 1] + ChargerIndex.distanceM(points[i - 1], points[i])
    }

    /**
     * The limit [driven] metres along the route (km/h), UNLIMITED where there is none (German motorway), null where it is
     * not known: the piece that point lies on.
     */
    fun along(driven: Double): Int? {
        if (points.size < 2) return null
        var lo = 0; var hi = points.lastIndex
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (metres[mid] <= driven) lo = mid else hi = mid }
        return limit[lo].takeIf { it != 0 }
    }

    /**
     * The limit near places taken in their order along a similar line (Navi L-056: the plan's own route over its stops
     * runs a little apart from the one the limits were asked for): each looked up from where the last one was found,
     * a little back and on - in one pass over the line, not a search of all of it at each place.
     */
    fun walker(): (Position) -> Int? {
        var at = 0
        return walk@{ p ->
            if (points.size < 2) return@walk null
            var best = at; var bd = Double.MAX_VALUE
            val from = maxOf(0, at - WALK_BACK); val to = minOf(points.lastIndex, at + WALK_ON)
            for (i in from..to) { val d = ChargerIndex.distanceM(points[i], p); if (d < bd) { bd = d; best = i } }
            at = best
            if (bd > WALK_OFF_M) null else limit[best].takeIf { it != 0 }
        }
    }

    companion object {
        const val UNLIMITED = -1
        private const val WALK_BACK = 20
        private const val WALK_ON = 400
        /** Further than this from the line the limits were asked for: not known there. */
        private const val WALK_OFF_M = 300.0

        /**
         * The limits along [points] (the route's line); null when Valhalla cannot be asked. Long routes are asked in pieces
         * of at most [PIECE_M] and [PIECE_POINTS] (the map server refused a 304 km route with HTTP 400, 16.11.2026: "keine
         * tempo daten gerade?"), side by side, and put together again; a piece that fails leaves its stretch unknown. Each is
         * matched exactly where it can and snapped to the map where not ("walk_or_snap": over charging stops the exact
         * walk failed).
         */
        suspend fun load(context: Context, points: List<Position>, route: Any? = null): NaviLimits? = withContext(Dispatchers.IO) {
            val limit = IntArray(points.size)
            val pieces = pieces(points)
            val ok = kotlinx.coroutines.coroutineScope {
                pieces.map { (from, to) -> async { piece(context, points.subList(from, to + 1))?.let { from to it } } }.map { it.await() }
            }.filterNotNull()
            for ((from, l) in ok) for (k in l.indices) if (l[k] != 0) limit[from + k] = l[k]
            android.util.Log.i("MateNavi", "speed limits: ${ok.size} of ${pieces.size} pieces")
            if (ok.isEmpty()) null else NaviLimits(points, limit, route)
        }

        private const val PIECE_M = 100_000.0
        private const val PIECE_POINTS = 1500

        /** Index ranges (inclusive, each sharing its last point with the next) of at most [PIECE_M] and [PIECE_POINTS]. */
        fun pieces(points: List<Position>): List<Pair<Int, Int>> {
            val out = ArrayList<Pair<Int, Int>>()
            var start = 0; var m = 0.0
            for (i in 1 until points.size) {
                m += ChargerIndex.distanceM(points[i - 1], points[i])
                if (m >= PIECE_M || i - start >= PIECE_POINTS) { out += start to i; start = i; m = 0.0 }
            }
            if (start < points.size - 1 || out.isEmpty()) out += start to points.size - 1
            return out
        }

        private fun piece(context: Context, points: List<Position>): IntArray? = runCatching {
            val body = JSONObject().put("encoded_polyline", encode(points)).put("shape_match", "walk_or_snap").put("costing", "auto")
                .put("filters", JSONObject().put("action", "include")
                    .put("attributes", JSONArray(listOf("edge.speed_limit", "edge.begin_shape_index", "edge.end_shape_index"))))
            val req = Request.Builder().url(NaviServer.valhalla(context) + "/trace_attributes")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            NaviServer.client(context).newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    android.util.Log.w("MateNavi", "speed limits: HTTP ${r.code} for ${points.size} points: " + r.body.string().take(160))
                    return@runCatching null
                }
                val edges = JSONObject(r.body.string()).optJSONArray("edges") ?: return@runCatching null
                val limit = IntArray(points.size)
                for (i in 0 until edges.length()) {
                    val e = edges.getJSONObject(i)
                    val v = when (val sl = e.opt("speed_limit")) {
                        is Number -> sl.toInt()
                        "unlimited" -> UNLIMITED
                        else -> 0
                    }
                    val a = e.optInt("begin_shape_index", -1); val b = e.optInt("end_shape_index", -1)
                    if (a < 0 || b < a) continue
                    for (k in a..b.coerceAtMost(limit.lastIndex)) limit[k] = v
                }
                limit
            }
        }.onFailure { android.util.Log.w("MateNavi", "speed limits: $it") }.getOrNull()

        /** Polyline with 6 decimals, as Valhalla reads it. */
        private fun encode(points: List<Position>): String {
            val sb = StringBuilder()
            var lat0 = 0L; var lon0 = 0L
            fun put(v: Long) {
                var x = if (v < 0) (v shl 1).inv() else v shl 1
                while (x >= 0x20) { sb.append(((0x20 or (x and 0x1f).toInt()) + 63).toChar()); x = x shr 5 }
                sb.append((x + 63).toInt().toChar())
            }
            for (p in points) {
                val lat = Math.round(p.latitude * 1e6); val lon = Math.round(p.longitude * 1e6)
                put(lat - lat0); put(lon - lon0); lat0 = lat; lon0 = lon
            }
            return sb.toString()
        }
    }
}
