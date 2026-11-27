package net.prototux.aare.ui.car.navi

import androidx.compose.ui.unit.dp

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Warning
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import net.prototux.aare.ui.car.Mate

/** F2: the traffic's own signs on the map. */
internal object NaviTraffic {
    /**
     * The road sign of an incident [kind] (as [TrafficSign]: the white triangle with its red edge and the picture) as a
     * bitmap [px] wide, for the map (19.11.2026: road works the route runs through drawn with their sign, not a closure's X).
     */
    @androidx.compose.runtime.Composable
    fun rememberSignImage(kind: Int, px: Int): ImageBitmap {
        val painter = androidx.compose.ui.graphics.vector.rememberVectorPainter(icon(kind))
        val density = androidx.compose.ui.platform.LocalDensity.current
        return androidx.compose.runtime.remember(kind, px, painter) {
            val img = ImageBitmap(px, px)
            val canvas = androidx.compose.ui.graphics.Canvas(img)
            val w = px.toFloat()
            androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(density, androidx.compose.ui.unit.LayoutDirection.Ltr, canvas,
                androidx.compose.ui.geometry.Size(w, w)) {
                val tri = androidx.compose.ui.graphics.Path().apply { moveTo(w / 2, w * 0.04f); lineTo(w * 0.97f, w * 0.90f); lineTo(w * 0.03f, w * 0.90f); close() }
                drawPath(tri, androidx.compose.ui.graphics.Color.White)
                drawPath(tri, androidx.compose.ui.graphics.Color(0xFFD32F2F), style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.11f,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round))
                val inner = w * 0.42f
                val dx = (w - inner) / 2; val dy = w * 0.42f
                drawContext.transform.translate(dx, dy)
                with(painter) { draw(androidx.compose.ui.geometry.Size(inner, inner),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.ui.graphics.Color.Black)) }
                drawContext.transform.translate(-dx, -dy)
            }
            img
        }
    }

    /**
     * A closed road's sign (17.11.2026: "kleine X rot mit roten Rand"): a red cross in a red ring on the map's light
     * ground (dark at night), [px] wide - small, repeated along the closed stretch.
     */
    fun closedImage(px: Int, night: Boolean): ImageBitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = px / 2f
        val red = Mate.trafficClosed.toArgb()
        p.color = (if (night) Mate.bg else Mate.trafficGround).toArgb()
        c.drawCircle(r, r, r - 1, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = px * 0.11f; p.color = red
        c.drawCircle(r, r, r - p.strokeWidth / 2 - 1, p)
        p.strokeWidth = px * 0.12f; p.strokeCap = Paint.Cap.ROUND
        val a = px * 0.32f; val b = px - a
        c.drawLine(a, a, b, b, p); c.drawLine(b, a, a, b, p)
        return bmp.asImageBitmap()
    }

    /**
     * F2 step 3: the closed roads a route [line] runs over, as points on them for Valhalla to keep away from. TomTom's
     * incidents through the gate (/verkehr/traffic/services/5/incidentDetails, closures only, present ones), asked for
     * stretches of the route at most [CHUNK_M] long (TomTom answers areas up to 10,000 km²); a closure counts when it
     * lies within [ON_ROUTE_M] of the line. The points are inside the closed stretch, away from its ends, so a road
     * crossing it is not taken out too.
     */
    suspend fun closuresAlong(context: android.content.Context, line: List<org.maplibre.spatialk.geojson.Position>): List<List<org.maplibre.spatialk.geojson.Position>> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val base = net.prototux.aare.Settings(context).mapServer.trimEnd('/')
            if (base.isBlank() || line.size < 2) return@withContext emptyList()
            val client = NaviServer.client(context)
            val out = ArrayList<List<org.maplibre.spatialk.geojson.Position>>()
            val works = ArrayList<String>()
            // Navi E-035 (27.11.2026: "Autobahnbaustellen, da fährt man grundsätzlich erstmal durch … nur wegen einer Baustelle
            // muss er nicht umleiten, wegen einer Straßensperrung schon"): road works and narrowed lanes on a motorway, and the
            // motorway closures TomTom gives for them (a carriageway closed, the traffic switched over), kept apart
            val motorwayWorks = ArrayList<List<org.maplibre.spatialk.geojson.Position>>()
            val motorwayClosed = ArrayList<Pair<List<org.maplibre.spatialk.geojson.Position>, Boolean>>()
            for (chunk in chunks(line)) {
                val minLon = chunk.minOf { it.longitude } - 0.01; val maxLon = chunk.maxOf { it.longitude } + 0.01
                val minLat = chunk.minOf { it.latitude } - 0.01; val maxLat = chunk.maxOf { it.latitude } + 0.01
                val fields = java.net.URLEncoder.encode("{incidents{geometry{type,coordinates},properties{iconCategory,magnitudeOfDelay,roadNumbers,events{description}}}}", "UTF-8")
                // the box formatted on its own: the encoded fields hold "%7B..." that format() would take for its own
                val box = "%.5f,%.5f,%.5f,%.5f".format(java.util.Locale.ROOT, minLon, minLat, maxLon, maxLat)
                // closures (8), and road works (9) for the log only (24.11.2026, 19:30: led through road works no closure
                // had been reported for - whether TomTom knew them could not be seen)
                val url = "$base/verkehr/traffic/services/5/incidentDetails?bbox=$box&fields=$fields&categoryFilter=7,8,9&timeValidityFilter=present&language=${net.prototux.aare.Language.tag()}"
                val body = client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body.string() else null } ?: continue
                val incidents = org.json.JSONObject(body).optJSONArray("incidents") ?: continue
                for (i in 0 until incidents.length()) {
                    val g = incidents.getJSONObject(i).optJSONObject("geometry") ?: continue
                    val coords = g.optJSONArray("coordinates") ?: continue
                    val pts = if (g.optString("type") == "LineString") (0 until coords.length()).map { k -> coords.getJSONArray(k).let {
                        org.maplibre.spatialk.geojson.Position(longitude = it.getDouble(0), latitude = it.getDouble(1)) } } else continue
                    val props = incidents.getJSONObject(i).optJSONObject("properties")
                    val kind = props?.optInt("iconCategory") ?: 0
                    val motorway = onMotorway(props)
                    // E-035: lanes narrowed or works on a motorway mark the works there; no closure themselves
                    if (motorway && (kind == 7 || kind == 9)) motorwayWorks += pts
                    if (kind == 7) continue
                    if (kind == 9) {
                        val on = onRoute(pts, chunk)
                        if (on.isNotEmpty()) works += "%d m (magnitude %d)".format(java.util.Locale.ROOT,
                            on.sumOf { l -> l.zipWithNext { p, q -> ChargerIndex.distanceM(p, q) }.sum() }.toInt(), props.optInt("magnitudeOfDelay"))
                        continue
                    }
                    // the part of the closed stretch the route runs on (19.11.2026: see onRoute); on a motorway weighed below
                    val on = onRoute(pts, chunk)
                    if (motorway) on.forEach { motorwayClosed += it to worksWords(props) } else out += on
                }
            }
            // E-035: a motorway closure that is the works' (its words say so, or works or narrowed lanes lie within
            // [WORKS_NEAR_M]) is driven through; one without any works is a closure as before
            var throughWorks = 0
            for ((l, said) in motorwayClosed) {
                if (said || motorwayWorks.any { w -> near(w, l, WORKS_NEAR_M) }) throughWorks++ else out += l
            }
            if (throughWorks > 0) net.prototux.aare.DiagLog.add("closures on the motorway taken as road works, driven through: $throughWorks", area = "NAVI")
            if (works.isNotEmpty()) net.prototux.aare.DiagLog.add("road works on the route (not avoided): ${works.size} - ${works.take(6).joinToString()}", area = "NAVI")
            // and the closed roads of the flow tiles - they mark whole closed roads where the incidents name a piece
            val fetch: (String) -> ByteArray? = { u -> runCatching { client.newCall(okhttp3.Request.Builder().url(u).build()).execute()
                .use { if (it.isSuccessful) it.body.bytes() else null } }.getOrNull() }
            val flow = NaviFlowTiles.closedLines(fetch, base, line)
            var fromFlow = 0
            var told = 0
            for (l in flow) {
                val parts = onRoute(l, line)
                // E-035: a closed carriageway of the flow tiles at motorway works is the works' too
                if (parts.isNotEmpty() && motorwayWorks.any { w -> near(w, l, WORKS_NEAR_M) }) { throughWorks++; continue }
                if (parts.isNotEmpty()) { out += parts; fromFlow++; continue }
                // for checking: a closed line near the route that was not taken - how near, which way
                if (told < 8) {
                    val d = l.minOf { p -> line.minOf { ChargerIndex.distanceM(it, p) } }
                    if (d < 100) { told++; android.util.Log.i("MateNavi", "closure not taken: %.5f,%.5f-%.5f,%.5f nearest %.0f m".format(java.util.Locale.ROOT,
                        l.first().latitude, l.first().longitude, l.last().latitude, l.last().longitude, d)) }
                }
            }
            android.util.Log.i("MateNavi", "closures along: ${out.size - fromFlow} from incidents, $fromFlow from ${flow.size} closed flow lines")
            out.distinctBy { it.first().let { p -> "%.5f,%.5f".format(java.util.Locale.ROOT, p.latitude, p.longitude) } }
        }

    /** E-035: works or narrowed lanes this near a motorway closure make it the works'. */
    const val WORKS_NEAR_M = 1_500.0

    /** Whether TomTom's incident lies on a motorway: a road number "A 9", "A9", "E 51" among its roads. */
    fun onMotorway(props: org.json.JSONObject?): Boolean {
        val a = props?.optJSONArray("roadNumbers") ?: return false
        return (0 until a.length()).any { MOTORWAY.matches(a.optString(it).trim()) }
    }
    private val MOTORWAY = Regex("^[AE] ?\\d{1,3}.*")

    /** Whether TomTom's words for an incident speak of road works (the closure is the works' own). */
    fun worksWords(props: org.json.JSONObject?): Boolean {
        val ev = props?.optJSONArray("events") ?: return false
        return (0 until ev.length()).any { i -> WORKS.containsMatchIn(ev.optJSONObject(i)?.optString("description").orEmpty()) }
    }
    private val WORKS = Regex("(?i)baustelle|bauarbeit|arbeitsstelle|verkehrsführung|gegenverkehr|road ?works|construction|contraflow|maintenance")

    /** Whether any point of [a] lies within [m] of any point of [b]. */
    fun near(a: List<org.maplibre.spatialk.geojson.Position>, b: List<org.maplibre.spatialk.geojson.Position>, m: Double): Boolean =
        a.any { p -> b.any { q -> ChargerIndex.distanceM(p, q) <= m } }

    /** Whether the closure [pts] near its point [p] runs along [line] (close by and in its direction, either way). */
    private fun runsAlong(p: org.maplibre.spatialk.geojson.Position, pts: List<org.maplibre.spatialk.geojson.Position>,
                          line: List<org.maplibre.spatialk.geojson.Position>): Boolean {
        val k = line.indices.minByOrNull { ChargerIndex.distanceM(line[it], p) } ?: return false
        if (ChargerIndex.distanceM(line[k], p) >= ON_ROUTE_M) return false
        val j = pts.indices.minByOrNull { ChargerIndex.distanceM(pts[it], p) } ?: return false
        return sameWay(pts[maxOf(0, j - 1)], pts[minOf(pts.size - 1, j + 1)], line, k, either = true)
    }

    /**
     * The parts of the closed stretch [pts] the route [line] runs on, each at least [MIN_ON_ROUTE_M] long (19.11.2026, the
     * log: 40 closed stretches "along" the routes, none of them free - the other carriageway and the closed accesses of
     * service areas counted, since a closure counted where any point of it lay within 25 m in either direction). A point
     * counts when it lies on the same road (within [SAME_ROAD_M], either way: a closed street in town) or close by in the
     * route's own direction (within [ON_ROUTE_M]: not the other carriageway). A ramp touching the route for a few metres
     * gives nothing - its points beside the motorway would close the motorway itself; a long tunnel the route runs on in
     * part gives that part.
     */
    fun onRoute(pts: List<org.maplibre.spatialk.geojson.Position>, line: List<org.maplibre.spatialk.geojson.Position>): List<List<org.maplibre.spatialk.geojson.Position>> {
        val samples = inside(pts, SAMPLE_M, 0.0).ifEmpty { pts }
        val out = ArrayList<List<org.maplibre.spatialk.geojson.Position>>()
        var run = ArrayList<org.maplibre.spatialk.geojson.Position>()
        fun close() { if (run.size * SAMPLE_M >= MIN_ON_ROUTE_M) out += run; run = ArrayList() }
        for ((i, p) in samples.withIndex()) {
            val k = line.indices.minByOrNull { ChargerIndex.distanceM(line[it], p) }
            // to the line itself, not to its nearest point (the route's points lie up to some 50 m apart)
            val d = k?.let { segmentDistM(p, line, it) } ?: Double.MAX_VALUE
            val a = samples[maxOf(0, i - 1)]; val b = samples[minOf(samples.size - 1, i + 1)]
            val on = k != null && (d <= SAME_ROAD_M && sameWay(a, b, line, k, either = true) || d <= ON_ROUTE_M && sameWay(a, b, line, k))
            if (on) run += p else close()
        }
        close()
        return out
    }

    /** Metres from [p] to [line] near its point [k] (the segments before and after it). */
    private fun segmentDistM(p: org.maplibre.spatialk.geojson.Position, line: List<org.maplibre.spatialk.geojson.Position>, k: Int): Double {
        val kx = Math.cos(Math.toRadians(p.latitude)) * 111_320.0
        fun xy(q: org.maplibre.spatialk.geojson.Position) = (q.longitude - p.longitude) * kx to (q.latitude - p.latitude) * 111_320.0
        var best = ChargerIndex.distanceM(line[k], p)
        for (j in maxOf(0, k - 1) until minOf(line.size - 1, k + 1)) {
            val (ax, ay) = xy(line[j]); val (bx, by) = xy(line[j + 1])
            val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
            val t = if (l2 > 0) (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0) else 0.0
            best = minOf(best, Math.hypot(ax + t * dx, ay + t * dy))
        }
        return best
    }

    /**
     * The points Valhalla keeps away from: inside each closed part, every [DENSE_M] - at most [MAX_POINTS] in all, spread
     * over the parts, one at least for each (Valhalla takes 50: 1647 were refused with every request, 19.11.2026).
     */
    fun excludePoints(lines: List<List<org.maplibre.spatialk.geojson.Position>>): List<org.maplibre.spatialk.geojson.Position> {
        val per = lines.map { inside(it, DENSE_M, DENSE_END_M) }.filter { it.isNotEmpty() }
        if (per.sumOf { it.size } <= MAX_POINTS) return per.flatten()
        val each = maxOf(1, MAX_POINTS / maxOf(1, per.size))
        return per.flatMap { ps -> if (ps.size <= each) ps else (0 until each).map { j -> ps[(j * (ps.size - 1)) / maxOf(1, each - 1)] }.distinct() }
            .take(MAX_POINTS)
    }

    /**
     * How many metres of [route] still run on the closed [lines] (checked every ~20 m, close by and along them), leaving
     * out [FREE_ENDS_M] at the start and the end - a trip may start or end on a closed road. 0: the route is free.
     */
    fun onClosures(route: List<org.maplibre.spatialk.geojson.Position>, lines: List<List<org.maplibre.spatialk.geojson.Position>>): Double {
        if (route.size < 2 || lines.isEmpty()) return 0.0
        val cum = DoubleArray(route.size); for (i in 1 until route.size) cum[i] = cum[i - 1] + ChargerIndex.distanceM(route[i - 1], route[i])
        var on = 0.0; var last = -1e9
        for (i in route.indices) {
            if (cum[i] - last < 20 || cum[i] < FREE_ENDS_M || cum.last() - cum[i] < FREE_ENDS_M) continue
            last = cum[i]
            val p = route[i]
            if (lines.any { l -> l.any { ChargerIndex.distanceM(it, p) < 12 } && runsAlong(p, l, route) }) on += 20
        }
        return on
    }

    /**
     * Thin areas along the closed [lines] for Valhalla's exclude_polygons - the whole road, not points (17.11.2026: "das
     * setzt die ganze Straße ... nicht nur punktuell"): a narrow box round each piece of the stretch, [CLEAR_END_M] short
     * of its ends so the roads at its junctions stay open.
     */
    fun excludeAreas(lines: List<List<org.maplibre.spatialk.geojson.Position>>, maxVertices: Int = MAX_VERTICES): List<List<org.maplibre.spatialk.geojson.Position>> {
        // one narrow band per stretch (left side forward, right side back), few corners: Valhalla takes at most 100
        // corners for all its areas (error 176, 17.11.2026 - the boxes went over it and the areas were never used)
        val out = ArrayList<List<org.maplibre.spatialk.geojson.Position>>(); var used = 0; var perimeter = 0.0
        for (l in lines) {
            val len = (1 until l.size).sumOf { ChargerIndex.distanceM(l[it - 1], l[it]) }
            val pts = inside(l, maxOf(25.0, (len - 2 * CLEAR_END_M) / 6), CLEAR_END_M).let { if (it.size == 1) listOf(it[0], it[0]) else it }
            val ring = band(pts, 4.0)
            // and at most [MAX_PERIMETER_M] of edges for all of them (Valhalla's max_exclude_polygons_length, 10 km)
            val edge = (1 until ring.size).sumOf { ChargerIndex.distanceM(ring[it - 1], ring[it]) }
            if (used + ring.size > maxVertices || perimeter + edge > MAX_PERIMETER_M) break
            out += ring; used += ring.size; perimeter += edge
        }
        return out
    }

    private fun band(pts: List<org.maplibre.spatialk.geojson.Position>, halfM: Double): List<org.maplibre.spatialk.geojson.Position> {
        val k = Math.cos(Math.toRadians(pts[0].latitude)) * 111_320.0
        fun side(i: Int, sign: Double): org.maplibre.spatialk.geojson.Position {
            val a = pts[maxOf(0, i - 1)]; val b = pts[minOf(pts.size - 1, i + 1)]
            var dx = (b.longitude - a.longitude) * k; var dy = (b.latitude - a.latitude) * 111_320.0
            val len = Math.hypot(dx, dy).takeIf { it > 0.1 } ?: run { dx = 1.0; dy = 0.0; 1.0 }
            val nx = -dy / len * halfM * sign; val ny = dx / len * halfM * sign
            return org.maplibre.spatialk.geojson.Position(longitude = pts[i].longitude + nx / k, latitude = pts[i].latitude + ny / 111_320.0)
        }
        val left = pts.indices.map { side(it, 1.0) }; val right = pts.indices.reversed().map { side(it, -1.0) }
        return left + right + listOf(left[0])
    }

    /** Points inside a closed stretch: every [STEP_M] from [END_M] after its start to [END_M] before its end, or its middle. */
    fun inside(pts: List<org.maplibre.spatialk.geojson.Position>, step: Double = STEP_M, end: Double = END_M): List<org.maplibre.spatialk.geojson.Position> {
        if (pts.size < 2) return emptyList()
        val cum = DoubleArray(pts.size); for (i in 1 until pts.size) cum[i] = cum[i - 1] + ChargerIndex.distanceM(pts[i - 1], pts[i])
        val len = cum.last()
        val marks = if (len < 2 * end + 1) listOf(len / 2) else generateSequence(end) { it + step }.takeWhile { it <= len - end }.toList()
        return marks.map { m ->
            val j = (1 until pts.size).first { cum[it] >= m }
            val t = if (cum[j] > cum[j - 1]) (m - cum[j - 1]) / (cum[j] - cum[j - 1]) else 0.0
            org.maplibre.spatialk.geojson.Position(longitude = pts[j - 1].longitude + t * (pts[j].longitude - pts[j - 1].longitude),
                latitude = pts[j - 1].latitude + t * (pts[j].latitude - pts[j - 1].latitude))
        }
    }

    /**
     * Whether a-b runs along [line] at its point [k] (within [SAME_WAY_DEG]); [either]: in either direction (a closure
     * blocks both ways for the route, an incident counts in the driving direction only).
     */
    fun sameWay(a: org.maplibre.spatialk.geojson.Position, b: org.maplibre.spatialk.geojson.Position, line: List<org.maplibre.spatialk.geojson.Position>,
                k: Int, either: Boolean = false): Boolean {
        if (line.size < 2) return true
        val p = line[maxOf(0, minOf(k, line.size - 2))]; val q = line[maxOf(1, minOf(k + 1, line.size - 1))]
        var d = Math.abs(bearing(a, b) - bearing(p, q)) % 360.0
        if (d > 180) d = 360 - d
        return d <= SAME_WAY_DEG || (either && d >= 180 - SAME_WAY_DEG)
    }

    private fun bearing(a: org.maplibre.spatialk.geojson.Position, b: org.maplibre.spatialk.geojson.Position): Double {
        val y = (b.longitude - a.longitude) * Math.cos(Math.toRadians(a.latitude))
        val x = b.latitude - a.latitude
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
    }

    /** The line in stretches of at most [CHUNK_M]. */
    private fun chunks(line: List<org.maplibre.spatialk.geojson.Position>): List<List<org.maplibre.spatialk.geojson.Position>> {
        val out = ArrayList<List<org.maplibre.spatialk.geojson.Position>>()
        var cur = ArrayList<org.maplibre.spatialk.geojson.Position>(); var m = 0.0
        for (p in line) {
            if (cur.isNotEmpty()) m += ChargerIndex.distanceM(cur.last(), p)
            cur += p
            if (m >= CHUNK_M) { out += cur; cur = arrayListOf(p); m = 0.0 }
        }
        if (cur.size > 1) out += cur
        return out
    }

    private const val CHUNK_M = 60_000.0
    private const val ON_ROUTE_M = 25.0
    /** A known jam lies on a route this close to one of its stretches (I-080). */
    private const val JAM_ON_ROUTE_M = 40.0
    private const val SAME_WAY_DEG = 40.0
    private const val END_M = 40.0
    private const val STEP_M = 300.0
    private const val DENSE_M = 50.0
    /** Valhalla's limit of excluded places is 50 (its default service limit); a few kept free. */
    const val MAX_POINTS = 48
    private const val MAX_PERIMETER_M = 9_000.0
    private const val SAMPLE_M = 25.0
    private const val SAME_ROAD_M = 10.0
    private const val MIN_ON_ROUTE_M = 200.0
    private const val DENSE_END_M = 20.0
    private const val FREE_ENDS_M = 400.0   // a start or destination in a closed block cannot be helped
    private const val CLEAR_END_M = 15.0
    private const val MAX_VERTICES = 96

    /**
     * A jam met on this trip (System I-080): where it starts, a point a little further on its road (its direction) and
     * what it costs. Kept while the trip lasts, so a new route after leaving the guided one still knows it.
     */
    class KnownJam(val start: org.maplibre.spatialk.geojson.Position, val ahead: org.maplibre.spatialk.geojson.Position,
                   val delayS: Int, val seenAtMs: Long)

    /** The delay of the [jams] that lie on [route] in its driving direction (start within [ON_ROUTE_M], same way). */
    fun knownDelayS(route: List<org.maplibre.spatialk.geojson.Position>, jams: List<KnownJam>): Int = jams.sumOf { j ->
        // the nearest stretch of the line, not point: a motorway's points lie far apart on straight parts
        var best = -1; var bestD = JAM_ON_ROUTE_M
        for (k in 0 until route.size - 1) { val d = segmentDistM(j.start, route, k); if (d < bestD) { bestD = d; best = k } }
        if (best >= 0 && sameWay(j.start, j.ahead, route, best)) j.delayS else 0
    }

    /**
     * Which of the new [routes] (its duration in seconds and line) to guide after leaving the route: the least of
     * duration plus the known jams on it - Valhalla alone knows no traffic and led back into the jam the driver had
     * just left (22.11.2026: "Penetrantes zurück führen auf seine Wunsch Route. Weil er denkt die ist schneller.").
     */
    fun pickRoute(routes: List<Pair<Double, List<org.maplibre.spatialk.geojson.Position>>>, jams: List<KnownJam>): Int {
        if (routes.size < 2 || jams.isEmpty()) return 0
        return routes.indices.minByOrNull { routes[it].first + knownDelayS(routes[it].second, jams) } ?: 0
    }

    /** One of TomTom's incidents: [kind] its icon category, [delayS] what it costs (null: unknown), [text] its words. */
    class Incident(val kind: Int, val start: org.maplibre.spatialk.geojson.Position, val delayS: Int?, val text: String,
                   /** TomTom's magnitudeOfDelay: 0 unknown, 1 minor, 2 moderate, 3 major, 4 undefined (closures) */
                   val magnitude: Int = 0)
    /** An incident on the guided route, [alongM] metres from the route's start, reaching to [endM] along it. */
    class Ahead(val incident: Incident, val alongM: Double, val endM: Double = alongM,
                /** its end lies further on along the route too - only then is it coloured on the line (a jam the other way
                 *  starts at our road as well, 17.11.2026: "ein Ampelstau in die andere Richtung wurde rot angezeigt") */
                val along: Boolean = false,
                /** about an exit, an entry or a service area the route drives past without taking it ([besideRamps]) */
                val passedRamp: Boolean = false) {
        fun shifted(by: Double) = Ahead(incident, alongM + by, endM + by, along, passedRamp)
    }

    /**
     * Whether [a] is on the way the route takes: a closure (lane or road) only when it runs along the route ([Ahead.along]:
     * its end further on along it too) - one that begins beside the route and leaves it is a ramp's or a service area's
     * (19.11.2026: "Es gibt also irgendwo auf der Route Punkte, wo er Durchfahrtsverbote erkennt ... das werden nicht zwei
     * Autobahnsperrungen an den gleichen Stellen gestern Abend und heute Mittag gewesen sein"). Other kinds as before (an
     * accident is often a single point).
     */
    /**
     * The sign an incident ahead is shown with (Navi I-070, 27.11.2026: "er hat die Baustelle nicht als Baustelle
     * gekennzeichnet"): TomTom gave the A9's works as "Fahrbahn auf zwei Fahrstreifen verengt" (lane closed, 7) - a lane
     * closed or narrowed is road works unless its words speak of an accident or a breakdown, and a "closed" stretch the
     * route runs through is too (works with the traffic switched over); the others as they are.
     */
    fun shownKind(a: Ahead): Int = when {
        a.incident.kind == 8 && a.along -> 9
        a.incident.kind == 7 && !MISHAP.containsMatchIn(a.incident.text) -> 9
        else -> a.incident.kind
    }
    private val MISHAP = Regex("(?i)unfall|panne|liegengeblieben|accident|breakdown|broken.?down")

    fun onOurWay(a: Ahead): Boolean = !a.passedRamp && ((a.incident.kind != 7 && a.incident.kind != 8) || a.along)

    /**
     * The incidents about an exit, an entry or a service area (by their words) marked where the route drives straight
     * past - no exit, entry or fork of its own within [RAMP_NEAR_M] (19.11.2026: "es war eine Spursperrung der Abfahrt und
     * deswegen zeigt er mir das an, weil ich daran vorbeifahre. Vielleicht kriegt man das auch noch irgendwie rausgefiltert,
     * wenn das geradeaus gar nicht betrifft"). [rampsAtM]: where along the route it takes a ramp or a fork.
     */
    fun besideRamps(found: List<Ahead>, rampsAtM: List<Double>): List<Ahead> = found.map { a ->
        val words = a.incident.text.lowercase(java.util.Locale.ROOT)
        val aboutRamp = RAMP_WORDS.any { it in words }
        val taken = rampsAtM.any { it >= a.alongM - RAMP_NEAR_M && it <= a.endM + RAMP_NEAR_M }
        if (aboutRamp && !taken) Ahead(a.incident, a.alongM, a.endM, a.along, passedRamp = true) else a
    }
    /** TomTom's words for a ramp or a service area (German, as asked, and English). */
    private val RAMP_WORDS = listOf("ausfahrt", "abfahrt", "auffahrt", "zufahrt", "einfahrt", "anschlussstelle", "rastanlage", "raststätte",
        "rastplatz", "parkplatz", "tankstelle", "exit", "entry", "slip road", "ramp", "service area", "rest area", "parking")
    private const val RAMP_NEAR_M = 1000.0

    /** The kinds said and shown: accident, jam, lane closed, road closed, roadworks, broken-down vehicle. */
    const val KINDS = "1,6,7,8,9,14"

    /**
     * F2 step 2: the incidents a route [line] runs into, placed along it (TomTom through the gate, present ones, in
     * stretches as [closuresAlong]); an incident counts when its start lies within [ON_ROUTE_M] of the line.
     */
    suspend fun incidentsAlong(context: android.content.Context, line: List<org.maplibre.spatialk.geojson.Position>): List<Ahead> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val base = net.prototux.aare.Settings(context).mapServer.trimEnd('/')
            if (base.isBlank() || line.size < 2) return@withContext emptyList()
            val cum = DoubleArray(line.size); for (i in 1 until line.size) cum[i] = cum[i - 1] + ChargerIndex.distanceM(line[i - 1], line[i])
            val client = NaviServer.client(context)
            val out = ArrayList<Ahead>()
            for (chunk in chunks(line)) {
                val box = "%.5f,%.5f,%.5f,%.5f".format(java.util.Locale.ROOT, chunk.minOf { it.longitude } - 0.01, chunk.minOf { it.latitude } - 0.01,
                    chunk.maxOf { it.longitude } + 0.01, chunk.maxOf { it.latitude } + 0.01)
                val fields = java.net.URLEncoder.encode("{incidents{geometry{type,coordinates},properties{iconCategory,magnitudeOfDelay,delay,events{description}}}}", "UTF-8")
                val url = "$base/verkehr/traffic/services/5/incidentDetails?bbox=$box&fields=$fields&categoryFilter=$KINDS&timeValidityFilter=present&language=${net.prototux.aare.Language.tag()}"
                val body = client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body.string() else null } ?: continue
                val list = org.json.JSONObject(body).optJSONArray("incidents") ?: continue
                for (i in 0 until list.length()) {
                    val o = list.getJSONObject(i)
                    val coords = o.optJSONObject("geometry")?.takeIf { it.optString("type") == "LineString" }?.optJSONArray("coordinates") ?: continue
                    if (coords.length() == 0) continue
                    val start = coords.getJSONArray(0).let { org.maplibre.spatialk.geojson.Position(longitude = it.getDouble(0), latitude = it.getDouble(1)) }
                    val next = if (coords.length() > 1) coords.getJSONArray(minOf(coords.length() - 1, 3)).let { org.maplibre.spatialk.geojson.Position(longitude = it.getDouble(0), latitude = it.getDouble(1)) } else null
                    val pr = o.optJSONObject("properties") ?: continue
                    // its place along the route: the nearest point of the line, if close enough
                    var best = -1; var bestD = ON_ROUTE_M
                    for (k in line.indices) { val d = ChargerIndex.distanceM(line[k], start); if (d < bestD) { bestD = d; best = k } }
                    if (best < 0) continue
                    // in the route's direction only: a closed road crossing the route (a tunnel under it) is not on it
                    if (next != null && !sameWay(start, next, line, best)) continue
                    val text = pr.optJSONArray("events")?.optJSONObject(0)?.optString("description").orEmpty()
                    // where it ends along the route (17.11.2026: the jam's stretch coloured on the line, as Google does):
                    // the route's point nearest the incident's last point, further on than its start; else its own length
                    val last = coords.getJSONArray(coords.length() - 1).let { org.maplibre.spatialk.geojson.Position(longitude = it.getDouble(0), latitude = it.getDouble(1)) }
                    var own = 0.0
                    for (k in 1 until coords.length()) own += ChargerIndex.distanceM(
                        coords.getJSONArray(k - 1).let { org.maplibre.spatialk.geojson.Position(longitude = it.getDouble(0), latitude = it.getDouble(1)) },
                        coords.getJSONArray(k).let { org.maplibre.spatialk.geojson.Position(longitude = it.getDouble(0), latitude = it.getDouble(1)) })
                    var endIdx = -1; var endD = ON_ROUTE_M
                    for (k in best until line.size) { val d = ChargerIndex.distanceM(line[k], last); if (d < endD) { endD = d; endIdx = k } }
                    val endM = if (endIdx > best) cum[endIdx] else cum[best] + own.coerceAtLeast(100.0)
                    out += Ahead(Incident(pr.optInt("iconCategory"), start, if (pr.isNull("delay")) null else pr.optInt("delay"), text,
                        pr.optInt("magnitudeOfDelay")), cum[best], endM, along = endIdx > best && cum[endIdx] - cum[best] >= 0.5 * own)
                }
            }
            out.distinctBy { "%.4f,%.4f".format(java.util.Locale.ROOT, it.incident.start.latitude, it.incident.start.longitude) }.sortedBy { it.alongM }
        }

    /**
     * The flow along the first [maxM] metres of [line]: slow stretches as (from, to, level) in metres along it
     * (17.11.2026, NaviFlowTiles.levelsAlong). Through the gate and its TomTom budget, as the closures.
     */
    suspend fun flowAlong(context: android.content.Context, line: List<org.maplibre.spatialk.geojson.Position>, maxM: Double = 30_000.0): List<Triple<Double, Double, Int>> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val base = net.prototux.aare.Settings(context).mapServer.trimEnd('/')
            if (base.isBlank() || line.size < 2 || TomTomBudget.exhausted(context)) return@withContext emptyList()
            val part = ArrayList<org.maplibre.spatialk.geojson.Position>(); var m = 0.0
            for (k in line.indices) { if (k > 0) m += ChargerIndex.distanceM(line[k - 1], line[k]); part += line[k]; if (m > maxM) break }
            val client = NaviServer.client(context)
            val fetch: (String) -> ByteArray? = { u -> runCatching { client.newCall(okhttp3.Request.Builder().url(u).build()).execute()
                .use { if (it.isSuccessful) it.body.bytes() else null } }.getOrNull() }
            val flows = NaviFlowTiles.flowLines(fetch, base, part)
            NaviFlowTiles.levelsAlong(part, flows).also { android.util.Log.i("MateNavi", "flow along: ${flows.size} slow roads near, ${it.size} slow stretches on the route: " +
                it.take(8).joinToString { (f, t, l) -> "%.0f-%.0f m L%d".format(java.util.Locale.ROOT, f, t, l) }) }
        }

    /** A jam on the route: from [fromM] to [toM] along it, costing [delayS] seconds more than driving it freely. */
    class Jam(val fromM: Double, val toM: Double, val delayS: Double) {
        val midM get() = (fromM + toM) / 2
        /** Inside it at [drivenM]: the time to its end (driving the rest at the jam's pace), else null. */
        fun leftS(drivenM: Double, freeMs: Double): Double? {
            if (drivenM < fromM || drivenM >= toM || toM <= fromM) return null
            val whole = (toM - fromM) / freeMs.coerceAtLeast(1.0) + delayS
            return whole * (toM - drivenM) / (toM - fromM)
        }
    }

    /**
     * O237 ("im Stau die Zeit im Stau anzeigen", as Google: "+4 min" at the jam): the jams ahead of [drivenM] with what
     * each costs. An incident's own delay from TomTom where it gives one; a slow stretch of the flow estimated from its
     * level (the speed's share of the free one: [FLOW_SHARE]) and the route's free speed there ([freeMs], m/s);
     * overlapping ones are one jam with the larger delay. Only those of [MIN_JAM_S] or more.
     */
    fun jams(ahead: List<Ahead>, flow: List<Triple<Double, Double, Int>>, drivenM: Double, freeMs: (Double) -> Double): List<Jam> {
        val all = ArrayList<Jam>()
        for (a in ahead) {
            if (!a.along || a.incident.kind == 8) continue
            val d = a.incident.delayS ?: continue
            if (a.endM > a.alongM) all += Jam(a.alongM, a.endM, d.toDouble())
        }
        for ((f, t, lvl) in flow) {
            if (t <= f) continue
            val share = FLOW_SHARE.getOrElse(lvl) { FLOW_SHARE.last() }
            all += Jam(f, t, (t - f) / freeMs((f + t) / 2).coerceAtLeast(1.0) * (1 / share - 1))
        }
        // one jam where they overlap or nearly touch
        val merged = ArrayList<Jam>()
        for (j in all.sortedBy { it.fromM }) {
            val last = merged.lastOrNull()
            if (last != null && j.fromM <= last.toM + JOIN_M) {
                // the same place twice (incident and flow): the larger delay; one after the other: both
                val overlap = (minOf(last.toM, j.toM) - j.fromM).coerceAtLeast(0.0)
                val delay = if (overlap > 0.5 * minOf(j.toM - j.fromM, last.toM - last.fromM)) maxOf(last.delayS, j.delayS) else last.delayS + j.delayS
                merged[merged.lastIndex] = Jam(last.fromM, maxOf(last.toM, j.toM), delay)
            } else merged += j
        }
        return merged.filter { it.toM > drivenM && it.delayS >= MIN_JAM_S }
    }
    /** The speed's share of the free one for the flow's levels (traffic_level below 0.75 / 0.5 / 0.25). */
    private val FLOW_SHARE = doubleArrayOf(0.6, 0.37, 0.15)
    private const val JOIN_M = 200.0
    const val MIN_JAM_S = 60.0

    /**
     * An incident's kind as a sign (18.11.2026: "ein kleines Baustellenlogo ... dass dort ein Baustellenzeichen wäre, statt
     * nur ein Ausrufezeichen. Bei Gefahrenstellen kann man ruhig ein Ausrufezeichen machen").
     */
    fun icon(kind: Int): androidx.compose.ui.graphics.vector.ImageVector = when (kind) {
        9 -> ROAD_WORKS
        6 -> JAM
        // a lane closed is no closure: the road narrows (Zeichen 120), only a closed road gets the red ring (19.11.2026:
        // "Ladestops als Durchfahrtsverbot angezeigt ... die falsche Symbolzuordnung")
        7 -> NARROWS
        8 -> androidx.compose.material.icons.Icons.Rounded.Block
        1 -> ACCIDENT
        14 -> BROKEN_DOWN
        else -> androidx.compose.material.icons.Icons.Rounded.Warning
    }

    /**
     * The picture of the German sign for road works (Zeichen 123 "Arbeitsstelle"; 19.11.2026: "Eine Baustelle wird
     * normalerweise mit Bauarbeiter, mit Schippe und einem kleinen Sandberg dargestellt" - before a spanner): a worker bent
     * forward, digging with a shovel into a heap of sand on his right. Drawn here, black on the sign's white.
     */
    val ROAD_WORKS: androidx.compose.ui.graphics.vector.ImageVector by lazy {
        val black = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black)
        fun line(vararg xy: Float) = androidx.compose.ui.graphics.vector.PathBuilder().apply {
            moveTo(xy[0], xy[1]); var i = 2; while (i < xy.size) { lineTo(xy[i], xy[i + 1]); i += 2 } }.nodes
        androidx.compose.ui.graphics.vector.ImageVector.Builder("RoadWorks", 24.dp, 24.dp, 24f, 24f).apply {
            val round = androidx.compose.ui.graphics.StrokeCap.Round
            val join = androidx.compose.ui.graphics.StrokeJoin.Round
            // the heap of sand, to the right on the ground
            addPath(androidx.compose.ui.graphics.vector.PathBuilder().apply {
                moveTo(13.6f, 21f); curveTo(15.2f, 17.4f, 17.2f, 15.6f, 19.1f, 15.6f); curveTo(21f, 15.6f, 22.4f, 17.6f, 23.4f, 21f); close() }.nodes,
                fill = black)
            // head, at the upper end of the body bent forward (19.11.2026: "Kopf vom Bauarbeiter hängt runter")
            addPath(androidx.compose.ui.graphics.vector.PathBuilder().apply {
                moveTo(12.3f, 5.6f); arcTo(1.9f, 1.9f, 0f, true, true, 16.1f, 5.6f); arcTo(1.9f, 1.9f, 0f, true, true, 12.3f, 5.6f); close() }.nodes,
                fill = black)
            // body bent forward, from the shoulders to the hip
            addPath(line(12.9f, 8.4f, 8.4f, 13.2f), stroke = black, strokeLineWidth = 3.2f, strokeLineCap = round, strokeLineJoin = join)
            // legs: one stepping forward, one back
            addPath(line(8.4f, 13.2f, 10.6f, 16.6f, 10.2f, 20.6f), stroke = black, strokeLineWidth = 2.3f, strokeLineCap = round, strokeLineJoin = join)
            addPath(line(8.4f, 13.2f, 6.6f, 16.8f, 4.4f, 20.4f), stroke = black, strokeLineWidth = 2.3f, strokeLineCap = round, strokeLineJoin = join)
            // arms down to the shovel's handle
            addPath(line(12.6f, 9.0f, 13.6f, 11.8f, 15.3f, 14.0f), stroke = black, strokeLineWidth = 1.9f, strokeLineCap = round, strokeLineJoin = join)
            // the shovel: handle from behind the hands down into the sand, the blade at its end
            addPath(line(11.6f, 9.8f, 17.6f, 16.8f), stroke = black, strokeLineWidth = 1.8f, strokeLineCap = round)
            addPath(line(16.6f, 17.6f, 18.8f, 15.6f, 20.2f, 17.6f, 18.4f, 19.6f), fill = black)
        }.build()
    }

    /**
     * The picture of Zeichen 120 "Verengte Fahrbahn", for a lane closed (TomTom's kind 7): the two edges of the road coming
     * closer from both sides and going on narrower. Black on the sign's white.
     */
    val NARROWS: androidx.compose.ui.graphics.vector.ImageVector by lazy {
        val black = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black)
        fun line(vararg xy: Float) = androidx.compose.ui.graphics.vector.PathBuilder().apply {
            moveTo(xy[0], xy[1]); var i = 2; while (i < xy.size) { lineTo(xy[i], xy[i + 1]); i += 2 } }.nodes
        androidx.compose.ui.graphics.vector.ImageVector.Builder("Narrows", 24.dp, 24.dp, 24f, 24f).apply {
            val round = androidx.compose.ui.graphics.StrokeCap.Round
            val join = androidx.compose.ui.graphics.StrokeJoin.Round
            addPath(line(5f, 22f, 5f, 15f, 9.5f, 10f, 9.5f, 3f), stroke = black, strokeLineWidth = 2.6f, strokeLineCap = round, strokeLineJoin = join)
            addPath(line(19f, 22f, 19f, 15f, 14.5f, 10f, 14.5f, 3f), stroke = black, strokeLineWidth = 2.6f, strokeLineCap = round, strokeLineJoin = join)
        }.build()
    }

    /**
     * A car from the side, 10 x 5.4 units, its nose to the right (left when [mirror]): body, cabin and two wheels, turned
     * by [rotate] degrees about its middle, times [scale], its corner at [x], [y].
     */
    private fun androidx.compose.ui.graphics.vector.ImageVector.Builder.car(x: Float, y: Float, scale: Float = 1f, rotate: Float = 0f, mirror: Boolean = false) {
        val black = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black)
        val a = Math.toRadians(rotate.toDouble()); val c = Math.cos(a).toFloat(); val sn = Math.sin(a).toFloat()
        fun t(px: Float, py: Float): Pair<Float, Float> {
            val mx = (if (mirror) 10f - px else px) - 5f; val my = py - 2.7f
            return (x + (5f + mx * c - my * sn) * scale) to (y + (2.7f + mx * sn + my * c) * scale)
        }
        fun shape(pts: List<Pair<Float, Float>>) = androidx.compose.ui.graphics.vector.PathBuilder().apply {
            pts.forEachIndexed { i, (px, py) -> val (tx, ty) = t(px, py); if (i == 0) moveTo(tx, ty) else lineTo(tx, ty) }; close() }.nodes
        val body = listOf(0.4f to 2.3f, 2.4f to 2.0f, 3.4f to 0.4f, 6.8f to 0.4f, 8.0f to 2.0f, 9.6f to 2.3f, 10f to 2.8f, 10f to 4.2f,
            0f to 4.2f, 0f to 2.7f)
        addPath(shape(body), fill = black)
        listOf(2.4f, 7.6f).forEach { cx -> addPath(shape((0 until 10).map { k ->
            val w = k * Math.PI / 5; (cx + 1.15f * Math.cos(w).toFloat()) to (4.4f + 1.15f * Math.sin(w).toFloat()) }), fill = black) }
    }

    /**
     * A jam as the German sign 124 "Stau" shows it (19.11.2026: before a traffic light, which says nothing of a jam):
     * cars close behind one another, the queue going away up the sign.
     */
    val JAM: androidx.compose.ui.graphics.vector.ImageVector by lazy {
        androidx.compose.ui.graphics.vector.ImageVector.Builder("Jam", 24.dp, 24.dp, 24f, 24f).apply {
            car(11.0f, 3.6f, 1.0f); car(5.6f, 9.0f, 1.15f); car(0.2f, 14.6f, 1.3f)
        }.build()
    }

    /** An accident (19.11.2026: "dann ist das 3. Symbol = dem vierten"): two cars run into each other, a burst above. */
    val ACCIDENT: androidx.compose.ui.graphics.vector.ImageVector by lazy {
        val black = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black)
        androidx.compose.ui.graphics.vector.ImageVector.Builder("Accident", 24.dp, 24.dp, 24f, 24f).apply {
            car(0.4f, 12.6f, 1.15f, rotate = 14f); car(12.1f, 12.6f, 1.15f, rotate = -14f, mirror = true)
            // the crash: short strokes going out from where they met
            val round = androidx.compose.ui.graphics.StrokeCap.Round
            listOf(floatArrayOf(12f, 9.6f, 12f, 6.4f), floatArrayOf(9.6f, 10.2f, 8.2f, 7.6f), floatArrayOf(14.4f, 10.2f, 15.8f, 7.6f))
                .forEach { l -> addPath(androidx.compose.ui.graphics.vector.PathBuilder().apply { moveTo(l[0], l[1]); lineTo(l[2], l[3]) }.nodes,
                    stroke = black, strokeLineWidth = 1.6f, strokeLineCap = round) }
        }.build()
    }

    /** A broken-down car: a car standing, the warning triangle set up behind it. Before a car on a lift. */
    val BROKEN_DOWN: androidx.compose.ui.graphics.vector.ImageVector by lazy {
        val black = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black)
        androidx.compose.ui.graphics.vector.ImageVector.Builder("BrokenDown", 24.dp, 24.dp, 24f, 24f).apply {
            car(9.0f, 12.2f, 1.45f)
            // the warning triangle on its stand
            addPath(androidx.compose.ui.graphics.vector.PathBuilder().apply { moveTo(4.6f, 13.2f); lineTo(8.2f, 19.4f); lineTo(1.0f, 19.4f); close() }.nodes,
                stroke = black, strokeLineWidth = 1.5f, strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round)
            addPath(androidx.compose.ui.graphics.vector.PathBuilder().apply { moveTo(4.6f, 19.4f); lineTo(4.6f, 20.8f) }.nodes,
                stroke = black, strokeLineWidth = 1.2f)
        }.build()
    }

    /** An incident's kind as a short word: Stau, Unfall, Straße gesperrt ... */
    fun word(context: android.content.Context, kind: Int): String = context.getString(when (kind) {
        1 -> net.prototux.aare.R.string.traffic_accident
        6 -> net.prototux.aare.R.string.traffic_jam
        7 -> net.prototux.aare.R.string.traffic_lane_closed
        8 -> net.prototux.aare.R.string.traffic_road_closed
        9 -> net.prototux.aare.R.string.traffic_roadworks
        14 -> net.prototux.aare.R.string.traffic_broken_down
        else -> net.prototux.aare.R.string.traffic_incident
    })

    /** What is said for [i] at [metres] ahead: "Stau in 3 Kilometern, etwa 6 Minuten Verzögerung". */
    fun spoken(context: android.content.Context, i: Incident, metres: Double, kind: Int = i.kind): String {
        val where = if (metres >= 1000) context.getString(net.prototux.aare.R.string.traffic_in_km, Math.round(metres / 1000.0).toInt())
            else context.getString(net.prototux.aare.R.string.traffic_in_m, (Math.round(metres / 100.0) * 100).toInt().coerceAtLeast(100))
        val delay = i.delayS?.takeIf { it >= 60 }?.let { context.getString(net.prototux.aare.R.string.traffic_delay_say, Math.round(it / 60.0).toInt()) }
        return listOfNotNull(word(context, kind) + " " + where, delay).joinToString(", ")
    }

    /**
     * The jams' stretches on the route [line] as three GeoJSON line collections - slow (orange), jam (red), standing
     * (dark red), as Google colours the route (17.11.2026: "Stau-Anteile auf der Navigationslinie werden noch nicht
     * eingefärbt"). Closures stay the red crosses; what is driven already is left out.
     */
    /**
     * Navi I-070: the road works on the route (as shown, [shownKind] 9) as stretches of the drawn [line], from where the car
     * is (or they begin) to their end - a GeoJSON FeatureCollection; [scale] as for [stretches].
     */
    fun worksStretches(line: List<org.maplibre.spatialk.geojson.Position>, ahead: List<Ahead>, drivenM: Double, scale: Double = 1.0): String {
        val ranges = ahead.filter { onOurWay(it) && shownKind(it) == 9 }
            .map { maxOf(it.alongM, drivenM) * scale to maxOf(it.endM, it.alongM + 100) * scale }.filter { (a, b) -> b - a >= 20 }
        return linesAlong(line, ranges)
    }

    /** Stretches of [line] between metres along it, as a GeoJSON FeatureCollection of lines. */
    fun linesAlong(line: List<org.maplibre.spatialk.geojson.Position>, ranges: List<Pair<Double, Double>>): String {
        if (line.size < 2 || ranges.isEmpty()) return "{\"type\":\"FeatureCollection\",\"features\":[]}"
        val cum = DoubleArray(line.size); for (i in 1 until line.size) cum[i] = cum[i - 1] + ChargerIndex.distanceM(line[i - 1], line[i])
        fun at(m: Double): org.maplibre.spatialk.geojson.Position {
            if (m <= 0) return line.first()
            var k = 1; while (k < line.size - 1 && cum[k] < m) k++
            val seg = cum[k] - cum[k - 1]; val f = if (seg <= 0) 0.0 else ((m - cum[k - 1]) / seg).coerceIn(0.0, 1.0)
            return org.maplibre.spatialk.geojson.Position(longitude = line[k - 1].longitude + (line[k].longitude - line[k - 1].longitude) * f,
                latitude = line[k - 1].latitude + (line[k].latitude - line[k - 1].latitude) * f)
        }
        val feats = ranges.map { (a0, b0) ->
            val a = a0.coerceIn(0.0, cum.last()); val b = b0.coerceIn(a, cum.last())
            val pts = ArrayList<org.maplibre.spatialk.geojson.Position>(); pts += at(a)
            for (k in line.indices) if (cum[k] > a && cum[k] < b) pts += line[k]
            pts += at(b)
            "{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"LineString\",\"coordinates\":" +
                pts.joinToString(",", "[", "]") { "[%.6f,%.6f]".format(java.util.Locale.ROOT, it.longitude, it.latitude) } + "}}"
        }
        return feats.joinToString(",", "{\"type\":\"FeatureCollection\",\"features\":[", "]}")
    }

    fun stretches(line: List<org.maplibre.spatialk.geojson.Position>, ahead: List<Ahead>, drivenM: Double,
                  flow: List<Triple<Double, Double, Int>> = emptyList(),
                  /** [line] is the drawn (smoothed) line: metres of the route's own geometry times this */
                  scale: Double = 1.0): Triple<String, String, String> {
        val cum = DoubleArray(line.size); for (i in 1 until line.size) cum[i] = cum[i - 1] + ChargerIndex.distanceM(line[i - 1], line[i])
        fun at(m: Double): org.maplibre.spatialk.geojson.Position {
            if (m <= 0 || line.size < 2) return line.first()
            var k = 1; while (k < line.size - 1 && cum[k] < m) k++
            val seg = cum[k] - cum[k - 1]; val f = if (seg <= 0) 0.0 else ((m - cum[k - 1]) / seg).coerceIn(0.0, 1.0)
            return org.maplibre.spatialk.geojson.Position(longitude = line[k - 1].longitude + (line[k].longitude - line[k - 1].longitude) * f,
                latitude = line[k - 1].latitude + (line[k].latitude - line[k - 1].latitude) * f)
        }
        fun slice(a: Double, b: Double): String {
            val pts = ArrayList<org.maplibre.spatialk.geojson.Position>(); pts += at(a)
            for (k in line.indices) if (cum[k] > a && cum[k] < b) pts += line[k]
            pts += at(b)
            return pts.joinToString(",", "[", "]") { "[%.6f,%.6f]".format(java.util.Locale.ROOT, it.longitude, it.latitude) }
        }
        val levels = arrayOf(ArrayList<String>(), ArrayList(), ArrayList())
        if (line.size >= 2) for (a in ahead) {
            val i = a.incident
            if (i.kind == 8 || !a.along) continue        // road closed: its own red cross; not along our way: not coloured
            val lvl = when (i.magnitude) {
                3 -> 2; 2 -> 1; 1 -> 0
                else -> if ((i.delayS ?: 0) >= 600) 1 else if (i.kind == 6 || (i.delayS ?: 0) > 0) 0 else -1
            }
            if (lvl < 0) continue
            val from = maxOf(a.alongM, drivenM) * scale; val to = minOf(maxOf(a.endM, a.alongM + 50) * scale, cum.last())
            if (to - from < 20) continue
            levels[lvl] += "{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"LineString\",\"coordinates\":${slice(from, to)}}}"
        }
        // the flow's slow stretches (where an incident covers the same place, both are drawn; the darker lies on top)
        if (line.size >= 2) for ((f, t, lvl) in flow) {
            val from = maxOf(f, drivenM) * scale; val to = minOf(t * scale, cum.last())
            if (to - from < 20) continue
            levels[lvl] += "{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"LineString\",\"coordinates\":${slice(from, to)}}}"
        }
        fun fc(l: List<String>) = l.joinToString(",", "{\"type\":\"FeatureCollection\",\"features\":[", "]}")
        return Triple(fc(levels[0]), fc(levels[1]), fc(levels[2]))
    }
}

/**
 * An incident drawn like the German road sign (18.11.2026: "ein kleines Baustellenlogo ... Bei Gefahrenstellen kann man
 * ruhig ein Ausrufezeichen machen"): a white triangle with a red edge and the sign's picture in black (road works,
 * a jam, an accident, a broken-down car, "!" for any other danger), a white disc with a red ring for a closed road only (a lane closed is the triangle of
 * Zeichen 120, "Verengte Fahrbahn").
 */
@androidx.compose.runtime.Composable
internal fun TrafficSign(kind: Int, size: androidx.compose.ui.unit.Dp, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val red = androidx.compose.ui.graphics.Color(0xFFD32F2F)
    val closed = kind == 8
    androidx.compose.foundation.layout.Box(modifier.size(size), contentAlignment = androidx.compose.ui.Alignment.Center) {
        androidx.compose.foundation.Canvas(androidx.compose.ui.Modifier.matchParentSize()) {
            val w = this.size.minDimension
            if (closed) {
                drawCircle(androidx.compose.ui.graphics.Color.White, w / 2)
                drawCircle(red, w / 2 - w * 0.08f, style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.16f))
            } else {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w / 2, w * 0.04f); lineTo(w * 0.97f, w * 0.90f); lineTo(w * 0.03f, w * 0.90f); close()
                }
                drawPath(path, androidx.compose.ui.graphics.Color.White)
                drawPath(path, red, style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.11f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
        }
        if (!closed) {
            val inner = size * 0.42f
            if (NaviTraffic.icon(kind) == androidx.compose.material.icons.Icons.Rounded.Warning)
                androidx.compose.material3.Text("!", color = androidx.compose.ui.graphics.Color.Black, fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                    fontSize = androidx.compose.ui.unit.TextUnit(size.value * 0.42f, androidx.compose.ui.unit.TextUnitType.Sp),
                    modifier = androidx.compose.ui.Modifier.padding(top = size * 0.18f))
            else androidx.compose.material3.Icon(NaviTraffic.icon(kind), null, tint = androidx.compose.ui.graphics.Color.Black,
                modifier = androidx.compose.ui.Modifier.padding(top = size * 0.2f).size(inner))
        }
    }
}
