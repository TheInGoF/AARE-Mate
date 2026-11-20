package net.prototux.aare.ui.car.navi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.spatialk.geojson.Position
import java.net.HttpURLConnection
import java.net.URL

/** A route of the own Valhalla: its line and what the trip takes. */
internal class NaviRoute(val line: List<Position>, val km: Double, val minutes: Double) {
    /** The line as a GeoJSON feature for a MapLibre source. */
    fun geoJson(): String {
        val coords = JSONArray()
        for (p in line) coords.put(JSONArray().put(p.longitude).put(p.latitude))
        return JSONObject().put("type", "Feature").put("properties", JSONObject())
            .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coords)).toString()
    }

    companion object {
        /**
         * The line as drawn: corners rounded (wish "schön glatte Kurven und keine eckigen Kurven wie bei Google Maps",
         * specification UI-04, display only - guidance keeps Valhalla's line). Chaikin's corner cutting, [rounds] times,
         * each cut at most [maxCutM] metres from the corner, so the line stays on the road at crossings; ends kept.
         */
        fun smooth(line: List<Position>, rounds: Int = 3, maxCutM: Double = 6.0): List<Position> {
            // first the points that add nothing (straight stretches) go: else every round doubled them - a 287 km route
            // grew from 2 768 to 22 130 points
            var pts = simplify(line, SIMPLIFY_M)
            repeat(rounds) {
                if (pts.size < 3) return pts
                val out = ArrayList<Position>(pts.size * 2)
                out += pts.first()
                // corners only: where the line bends by less than MIN_BEND_DEG the point stays as it is (no new points)
                val bent = BooleanArray(pts.size) { i -> i in 1 until pts.size - 1 && bend(pts[i - 1], pts[i], pts[i + 1]) >= MIN_BEND_DEG }
                for (i in 0 until pts.size - 1) {
                    val a = pts[i]; val b = pts[i + 1]
                    val len = meters(a, b)
                    if (len < 0.01) continue
                    val f = minOf(0.25, maxCutM / len)
                    if (i > 0 && bent[i]) out += lerp(a, b, f)
                    if (i < pts.size - 2) { if (bent[i + 1]) out += lerp(a, b, 1 - f) else out += b }
                }
                out += pts.last()
                pts = out
            }
            return pts
        }

        private const val SIMPLIFY_M = 0.8
        private const val MIN_BEND_DEG = 2.0

        /** How much the line turns at [b] coming from [a] going to [c], in degrees. */
        private fun bend(a: Position, b: Position, c: Position): Double {
            val k = Math.cos(Math.toRadians(b.latitude))
            val h1 = Math.atan2((b.longitude - a.longitude) * k, b.latitude - a.latitude)
            val h2 = Math.atan2((c.longitude - b.longitude) * k, c.latitude - b.latitude)
            val d = Math.toDegrees(h2 - h1)
            return Math.abs(((d + 540) % 360) - 180)
        }

        /** Douglas-Peucker: keeps the points that lie more than [tolM] off the line through their neighbours. */
        fun simplify(line: List<Position>, tolM: Double): List<Position> {
            if (line.size < 3) return line
            val keep = BooleanArray(line.size); keep[0] = true; keep[line.size - 1] = true
            val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to line.size - 1) }
            val k = 111_320.0; val c = Math.cos(Math.toRadians(line[0].latitude))
            while (stack.isNotEmpty()) {
                val (a, b) = stack.removeLast()
                if (b - a < 2) continue
                val ax = line[a].longitude * k * c; val ay = line[a].latitude * k
                val bx = line[b].longitude * k * c; val by = line[b].latitude * k
                val dx = bx - ax; val dy = by - ay; val len = Math.hypot(dx, dy)
                var far = -1; var dist = tolM
                for (i in a + 1 until b) {
                    val px = line[i].longitude * k * c; val py = line[i].latitude * k
                    val d = if (len < 1e-6) Math.hypot(px - ax, py - ay) else Math.abs(dy * px - dx * py + bx * ay - by * ax) / len
                    if (d > dist) { dist = d; far = i }
                }
                if (far >= 0) { keep[far] = true; stack.add(a to far); stack.add(far to b) }
            }
            return line.filterIndexed { i, _ -> keep[i] }
        }

        private fun lerp(a: Position, b: Position, t: Double) =
            Position(longitude = a.longitude + (b.longitude - a.longitude) * t, latitude = a.latitude + (b.latitude - a.latitude) * t)

        private fun meters(a: Position, b: Position): Double {
            val k = 111_320.0
            return Math.hypot((b.latitude - a.latitude) * k, (b.longitude - a.longitude) * k * Math.cos(Math.toRadians(a.latitude)))
        }

        /** The routing service; until the server is rebuilt the build PC's Valhalla through `adb reverse tcp:8002 tcp:8002`. */
        const val DEV_VALHALLA = "http://127.0.0.1:8002"

        /** A car route from [from] to [to] (Valhalla /route, German), or null when the service cannot be reached. */
        suspend fun fetch(from: Position, to: Position, base: String = DEV_VALHALLA): NaviRoute? = withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("locations", JSONArray().put(loc(from)).put(loc(to)))
                    .put("costing", "auto").put("units", "kilometers").put("language", "de-DE")
                val c = URL("$base/route").openConnection() as HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 20000
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val trip = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONObject("trip")
                val line = ArrayList<Position>()
                val legs = trip.getJSONArray("legs")
                for (i in 0 until legs.length()) line += decodePolyline6(legs.getJSONObject(i).getString("shape"))
                val s = trip.getJSONObject("summary")
                NaviRoute(line, s.getDouble("length"), s.getDouble("time") / 60.0)
            }.onFailure { android.util.Log.w("MateNavi", "route: $it") }.getOrNull()
        }

        private fun loc(p: Position) = JSONObject().put("lat", p.latitude).put("lon", p.longitude)

        /** Valhalla's line format: Google's polyline encoding with six decimals. */
        fun decodePolyline6(s: String): List<Position> {
            val out = ArrayList<Position>(s.length / 4)
            var i = 0; var lat = 0L; var lon = 0L
            fun next(): Long {
                var result = 0L; var shift = 0
                while (true) {
                    val b = s[i++].code - 63
                    result = result or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                    if (b < 0x20) break
                }
                return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            }
            while (i < s.length) {
                lat += next(); lon += next()
                out += Position(longitude = lon / 1e6, latitude = lat / 1e6)
            }
            return out
        }
    }
}
