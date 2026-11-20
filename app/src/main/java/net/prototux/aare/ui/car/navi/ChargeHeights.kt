package net.prototux.aare.ui.car.navi

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.maplibre.spatialk.geojson.Position
import kotlin.math.floor

/**
 * The heights along a route (Navi L-029 step A: "Steigung (Höhen der Grundroute)"), asked once per route from the map
 * server's Valhalla (`/height`, a point every [STEP_M]) and kept for the routes planned over the stops of the same trip:
 * those run on the same roads but for the way to and from the chargers, which take the height of the nearest point.
 * Smoothed over [SMOOTH] points: the height model's steps of a few metres would otherwise count as hills.
 */
internal class ChargeHeights private constructor(private val samples: List<Position>, private val heights: DoubleArray) : ChargeEnergy.Heights {
    private val cells = HashMap<Long, MutableList<Int>>().apply {
        samples.forEachIndexed { i, p -> getOrPut(key(cell(p.latitude), cell(p.longitude))) { ArrayList() } += i }
    }

    override fun at(p: Position): Double? {
        val la = cell(p.latitude); val lo = cell(p.longitude)
        var best = -1; var bestM = NEAR_M
        for (a in la - 1..la + 1) for (b in lo - 1..lo + 1) cells[key(a, b)]?.forEach { i ->
            val d = ChargerIndex.distanceM(samples[i], p)
            if (d < bestM) { bestM = d; best = i }
        }
        return if (best >= 0) heights[best] else null
    }

    /** Highest climb and fall in the profile, for the log. */
    fun describe() = "%d points, %.0f-%.0f m".format(heights.size, heights.minOrNull() ?: 0.0, heights.maxOrNull() ?: 0.0)

    companion object {
        /** A point every so many metres. */
        const val STEP_M = 400.0
        private const val SMOOTH = 5
        private const val NEAR_M = 1_000.0
        /** The most points one request carries (Valhalla's limit is far above; 1600 km at [STEP_M]). */
        private const val MAX_POINTS = 4_000
        private const val CELL = 0.01

        private fun cell(d: Double) = floor(d / CELL).toInt()
        private fun key(a: Int, b: Int) = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)

        /** [line] every [STEP_M] (its first and last point kept). */
        fun resample(line: List<Position>, stepM: Double = STEP_M): List<Position> {
            if (line.size < 2) return line
            val out = arrayListOf(line.first()); var since = 0.0
            for (i in 1 until line.size) {
                since += ChargerIndex.distanceM(line[i - 1], line[i])
                if (since >= stepM - 1e-6) { out += line[i]; since = 0.0 }
            }
            if (out.last() !== line.last()) out += line.last()
            return out
        }

        /** The request body for [points]. */
        fun request(points: List<Position>): String = org.json.JSONObject().put("range", false).put("height_precision", 0)
            .put("shape", org.json.JSONArray(points.map { org.json.JSONObject().put("lat", it.latitude).put("lon", it.longitude) })).toString()

        /**
         * The heights of Valhalla's answer, smoothed; null when there are none or all are the same (a server without a
         * height model answers 0 everywhere - the road then counts as flat, said in the log).
         */
        fun parse(points: List<Position>, json: String): ChargeHeights? {
            val a = org.json.JSONObject(json).optJSONArray("height") ?: return null
            if (a.length() != points.size || a.length() < 2) return null
            val raw = DoubleArray(a.length()) { if (a.isNull(it)) Double.NaN else a.optDouble(it, Double.NaN) }
            // gaps (no data there) take their neighbour's height
            for (i in raw.indices) if (raw[i].isNaN()) raw[i] = if (i > 0) raw[i - 1] else raw.firstOrNull { !it.isNaN() } ?: return null
            if (raw.all { it == raw[0] }) return null
            return ChargeHeights(points, smooth(raw))
        }

        /** Moving mean over [SMOOTH] points. */
        fun smooth(h: DoubleArray): DoubleArray {
            val half = SMOOTH / 2
            return DoubleArray(h.size) { i ->
                val from = maxOf(0, i - half); val to = minOf(h.lastIndex, i + half)
                (from..to).sumOf { h[it] } / (to - from + 1)
            }
        }

        /** The heights of the routes planned last (a few base routes and their routes over the stops share them). */
        private val kept = object : LinkedHashMap<String, ChargeHeights?>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ChargeHeights?>?) = size > 6
        }

        /**
         * The heights for [line], asked once per route (a request that failed is asked again next time). [found] says
         * for the log what came: points and range, "none" or the error. Blocking.
         */
        fun forLine(http: OkHttpClient, valhalla: String, line: List<Position>, found: (String) -> Unit = {}): ChargeHeights? {
            if (line.size < 2) return null
            val k = "%.5f,%.5f-%.5f,%.5f-%d".format(java.util.Locale.ROOT, line.first().latitude, line.first().longitude,
                line.last().latitude, line.last().longitude, line.size)
            synchronized(kept) { if (kept.containsKey(k)) return kept[k] }
            return runCatching { fetch(http, valhalla, line) }
                .onSuccess { h -> found(h?.describe() ?: "none (the server has no height model)"); synchronized(kept) { kept[k] = h } }
                .onFailure { found("failed: ${it.message}") }.getOrNull()
        }

        /** Asks the server for [line]'s heights; null without an answer or without a height model. Blocking. */
        fun fetch(http: OkHttpClient, valhalla: String, line: List<Position>): ChargeHeights? {
            val pts = resample(line).let { if (it.size > MAX_POINTS) resample(line, STEP_M * it.size / MAX_POINTS + 1) else it }
            val body = request(pts).toRequestBody("application/json".toMediaType())
            return http.newCall(okhttp3.Request.Builder().url("$valhalla/height").post(body).build()).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("height: HTTP ${r.code}")
                parse(pts, r.body.string())
            }
        }
    }
}
