package net.prototux.aare.dash

import org.json.JSONArray
import org.json.JSONObject

/**
 * The dashboard's map drawn by Mate itself (System L-079, 21.11.2026: "Die Dashboard-Seite sollte lokal in der App gebaut
 * werden, mit lokalem Kartenmaterial. Und nur die Wetteranbindung und die Daten holt dann quasi aus dem Server, inklusive
 * Umkreis, Pfeile, Regenradar"): IDmate's answers made into the map's GeoJSON, as IDmate's own dashboard page draws them
 * (templates/index.html: smoothRing, smoothArea, showWindArea, the shade out of reach, windIcon, drawRain).
 */
internal object DashMapGeo {
    /**
     * The lines of the range area and the rings out of which the shade is cut; [outer] GeoJSON of lines, [rings]
     * the reachable rings themselves (the ferries' areas are cut out of the same shade, [shadeOf]).
     */
    class Area(val outer: String, val shade: String, val box: DoubleArray?, val rings: List<List<DoubleArray>> = emptyList())

    const val EMPTY = """{"type":"FeatureCollection","features":[]}"""

    /**
     * IDmate's corner cutting: a light moving average (1-2-3-2-1) against the fine zigzag, then [passes] of Chaikin. A ring
     * comes closed (the first point again at the end) and goes back closed.
     */
    fun smoothRing(ring: List<DoubleArray>, passes: Int): List<DoubleArray> {
        var pts = if (ring.size > 1 && same(ring.first(), ring.last())) ring.dropLast(1) else ring
        if (pts.size > 8) {
            val w = intArrayOf(1, 2, 3, 2, 1); val n = pts.size; val src = pts
            pts = List(n) { i ->
                var x = 0.0; var y = 0.0
                for (j in -2..2) { val q = src[(i + j + n) % n]; x += q[0] * w[j + 2]; y += q[1] * w[j + 2] }
                doubleArrayOf(x / 9, y / 9)
            }
        }
        repeat(passes) {
            if (pts.size <= 2) return@repeat
            val out = ArrayList<DoubleArray>(pts.size * 2)
            for (i in pts.indices) {
                val a = pts[i]; val b = pts[(i + 1) % pts.size]
                out += doubleArrayOf(0.75 * a[0] + 0.25 * b[0], 0.75 * a[1] + 0.25 * b[1])
                out += doubleArrayOf(0.25 * a[0] + 0.75 * b[0], 0.25 * a[1] + 0.75 * b[1])
            }
            pts = out
        }
        return if (pts.isEmpty()) pts else pts + listOf(pts[0])
    }

    /** The same for an open line: its ends stay where they are. */
    fun smoothLine(line: List<DoubleArray>, passes: Int): List<DoubleArray> {
        var pts = line
        if (pts.size > 8) {
            val w = intArrayOf(1, 2, 3, 2, 1); val n = pts.size; val src = pts
            pts = List(n) { i ->
                if (i < 2 || i > n - 3) src[i] else {
                    var x = 0.0; var y = 0.0
                    for (j in -2..2) { val q = src[i + j]; x += q[0] * w[j + 2]; y += q[1] * w[j + 2] }
                    doubleArrayOf(x / 9, y / 9)
                }
            }
        }
        repeat(passes) {
            if (pts.size <= 2) return@repeat
            val out = arrayListOf(pts[0])
            for (i in 0 until pts.size - 1) {
                val a = pts[i]; val b = pts[i + 1]
                out += doubleArrayOf(0.75 * a[0] + 0.25 * b[0], 0.75 * a[1] + 0.25 * b[1])
                out += doubleArrayOf(0.25 * a[0] + 0.75 * b[0], 0.25 * a[1] + 0.75 * b[1])
            }
            out += pts.last()
            pts = out
        }
        return pts
    }

    /**
     * The area of /api/range-area/last ("geojson"): road contours (Polygon/MultiPolygon with "contour" km, the largest
     * the outer line, only the outer ring - holes drew a tangle inside) or, with wind, one outline of several contours
     * (a feature of kind "line"). Null when it holds nothing to draw.
     */
    /** [ring] stretched by [factor] from [center] (lon, lat): IDmate's area brought to the car's own range (System L-087). */
    fun stretch(ring: List<DoubleArray>, center: DoubleArray, factor: Double): List<DoubleArray> =
        ring.map { p -> doubleArrayOf(center[0] + (p[0] - center[0]) * factor, center[1] + (p[1] - center[1]) * factor) }

    fun area(geojson: JSONObject, deform: ((List<DoubleArray>) -> List<DoubleArray>)? = null,
             scale: ((List<DoubleArray>) -> List<DoubleArray>)? = null): Area? = runCatching {
        val features = geojson.optJSONArray("features") ?: return null
        val list = (0 until features.length()).mapNotNull { features.optJSONObject(it) }
        val outerLines = ArrayList<List<DoubleArray>>()
        val shade = ArrayList<List<DoubleArray>>()
        if (list.any { it.optJSONObject("properties")?.has("kind") == true }) {
            // with wind (wind_range.py): the outline, closed loops smoothed as rings
            val line = list.firstOrNull { it.optJSONObject("properties")?.optString("kind") == "line" } ?: return null
            val g = line.optJSONObject("geometry") ?: return null
            val parts = when (g.optString("type")) {
                "MultiLineString" -> lines(g.getJSONArray("coordinates"))
                "LineString" -> listOf(points(g.getJSONArray("coordinates")))
                else -> emptyList()
            }
            for (p in parts) {
                val closed = p.size > 3 && same(p.first(), p.last())
                val s = (if (closed) smoothRing(p, 2) else smoothLine(p, 2)).let { r -> scale?.invoke(r) ?: r }
                outerLines += s
                if (closed) shade += s
            }
        } else {
            val max = list.maxOfOrNull { it.optJSONObject("properties")?.optDouble("contour", 0.0) ?: 0.0 } ?: 0.0
            for (f in list) {
                val g = f.optJSONObject("geometry") ?: continue
                val polys = when (g.optString("type")) {
                    "Polygon" -> listOf(g.getJSONArray("coordinates"))
                    "MultiPolygon" -> g.getJSONArray("coordinates").let { a -> (0 until a.length()).map { a.getJSONArray(it) } }
                    else -> emptyList()
                }
                val outer = (f.optJSONObject("properties")?.optDouble("contour", 0.0) ?: 0.0) >= max
                for (poly in polys) {
                    if (poly.length() == 0) continue
                    // without the wind in it (IDmate's plain contours): the wind put in by Mate (RangeWind)
                    val ring = smoothRing(points(poly.getJSONArray(0)), 2).let { r -> deform?.invoke(r) ?: r }.let { r -> scale?.invoke(r) ?: r }
                    if (ring.size < 4) continue
                    // one ring only (21.11.2026: "er soll nur einen umkreis zeigen. die zu erwartende reichweite bei
                    // normaler geschwindigkeit und dem wetter"): the largest contour, the range IDmate expects
                    if (outer) { outerLines += ring; shade += ring }
                }
            }
        }
        if (outerLines.isEmpty()) return null
        Area(lineCollection(outerLines), shadeOf(shade), box(shade.ifEmpty { outerLines }), shade)
    }.getOrNull()

    /**
     * IDmate's long ferries (/api/ferries with status "ok", ferries.py): what the car reaches beyond a long crossing, as
     * IDmate's page draws it (drawFerries) - the outline of all areas behind the ferries ("union", else each one's), no
     * fill: [rings] are cut out of the shade like the range area (shade.setFerry), the crossings over the sea and the
     * ports (the far one larger). [from]/[to]/[line] come as [lat, lon].
     */
    class Ferries(val outline: String, val rings: List<List<DoubleArray>>, val sea: String, val portsFrom: String, val portsTo: String, val box: DoubleArray?, val count: Int)

    fun ferries(answer: JSONObject?): Ferries? = runCatching {
        if (answer?.optString("status") != "ok") return null
        val list = answer.optJSONArray("ferries") ?: JSONArray()
        fun outlineOf(gj: JSONObject?): List<List<DoubleArray>> {
            val fs = gj?.optJSONArray("features") ?: return emptyList()
            val line = (0 until fs.length()).mapNotNull { fs.optJSONObject(it) }
                .firstOrNull { it.optJSONObject("properties")?.optString("kind") == "line" } ?: return emptyList()
            val g = line.optJSONObject("geometry") ?: return emptyList()
            val parts = when (g.optString("type")) {
                "MultiLineString" -> lines(g.getJSONArray("coordinates"))
                "LineString" -> listOf(points(g.getJSONArray("coordinates")))
                else -> emptyList()
            }
            return parts.filter { it.size > 3 }.map { smoothRing(it, 2) }
        }
        fun latLon(a: JSONArray?) = a?.takeIf { it.length() >= 2 }?.let { doubleArrayOf(it.getDouble(1), it.getDouble(0)) }
        val sea = ArrayList<List<DoubleArray>>(); val from = JSONArray(); val to = JSONArray(); val own = ArrayList<List<DoubleArray>>()
        val kept = ArrayList<Pair<DoubleArray, DoubleArray>>()
        var dropped = false
        for (i in 0 until list.length()) {
            val f = list.optJSONObject(i) ?: continue
            val line = f.optJSONArray("line")?.let { l -> (0 until l.length()).mapNotNull { latLon(l.optJSONArray(it)) } } ?: emptyList()
            val a = latLon(f.optJSONArray("from")) ?: line.firstOrNull(); val b = latLon(f.optJSONArray("to")) ?: line.lastOrNull()
            if (a == null || b == null) continue
            // only real crossings (22.11.2026: "nur reale Verbindungen … keine Hin- und Herrouten"): no round trip that
            // comes back where it left (OSM maps some ferries as one way out and back, Læsøfærgen), no pair twice
            if (roundTrip(a, b, line) || kept.any { (x, y) -> (km(x, a) < SAME_PORT_KM && km(y, b) < SAME_PORT_KM) ||
                    (km(x, b) < SAME_PORT_KM && km(y, a) < SAME_PORT_KM) }) { dropped = true; continue }
            kept += a to b
            if (line.size >= 2) sea += line
            from.put(point(a[0], a[1], JSONObject()))
            to.put(point(b[0], b[1], JSONObject().put("name", f.optString("name"))))
            // the area behind it only where it lies at its port of arrival (an area that lands elsewhere is no way on
            // over this ferry - seen when the routing knew no roads there and the area fell back to the coast left)
            // and no part of it back at the port it left (22.11.2026: "Rostock nach Gedser und … zurück": IDmate's area
            // behind Gedser started on the ferry line itself and reached back to Rostock, IDmate I-073)
            val all = outlineOf(f.optJSONObject("area"))
            val area = all.filterNot { r -> near(a, r) }
            if (area.size < all.size) dropped = true
            if (area.any { r -> near(b, r) }) own += area else if (area.isNotEmpty()) dropped = true
        }
        // IDmate's common outline of all areas - unless one was left out here, then each area's own
        val rings = (if (dropped) emptyList() else outlineOf(answer.optJSONObject("union"))).ifEmpty { own }
        val ports = (0 until to.length()).map { to.getJSONObject(it) } + (0 until from.length()).map { from.getJSONObject(it) }
        val portPts = ports.map { p -> p.getJSONObject("geometry").getJSONArray("coordinates").let { doubleArrayOf(it.getDouble(0), it.getDouble(1)) } }
        Ferries(lineCollection(rings), rings, lineCollection(sea),
            collection(from), collection(to), box(rings + sea + listOf(portPts)), kept.size)
    }.getOrNull()

    /** Out of reach slightly dimmed: the world with the reachable rings cut out (IDmate: a tint, the areas cut out of it). */
    fun shadeOf(rings: List<List<DoubleArray>>): String {
        if (rings.isEmpty()) return EMPTY
        val world = listOf(doubleArrayOf(-180.0, -85.0), doubleArrayOf(180.0, -85.0), doubleArrayOf(180.0, 85.0), doubleArrayOf(-180.0, 85.0), doubleArrayOf(-180.0, -85.0))
        val coords = JSONArray().put(ring(world))
        rings.forEach { coords.put(ring(it)) }
        return JSONObject().put("type", "Feature").put("properties", JSONObject())
            .put("geometry", JSONObject().put("type", "Polygon").put("coordinates", coords)).toString()
    }

    /**
     * The shade with the range's [rings] and the ferries' [more] cut out, as IDmate's canvas cuts every reachable area out
     * of its tint. MapLibre cannot cut holes that overlap, so a ferry ring touching one already cut stays out (where it
     * overlaps it is cut anyway; the rest of it then stays dimmed - rare, the areas lie beyond the sea).
     */
    fun shadeOf(rings: List<List<DoubleArray>>, more: List<List<DoubleArray>>): String {
        val cut = ArrayList(rings)
        for (r in more) if (r.size > 3 && cut.none { overlap(it, r) }) cut += r
        return shadeOf(cut)
    }

    /** Do two rings overlap: their boxes, then points of each (up to [SAMPLES]) inside the other. */
    fun overlap(a: List<DoubleArray>, b: List<DoubleArray>): Boolean {
        val ba = box(listOf(a)) ?: return false; val bb = box(listOf(b)) ?: return false
        if (ba[2] < bb[0] || bb[2] < ba[0] || ba[3] < bb[1] || bb[3] < ba[1]) return false
        fun some(r: List<DoubleArray>) = r.filterIndexed { i, _ -> i % maxOf(1, r.size / SAMPLES) == 0 }
        return some(a).any { inside(it, b) } || some(b).any { inside(it, a) }
    }
    private const val SAMPLES = 200
    /** Ports this close are the same port; an area this close to its port of arrival lies there. */
    private const val SAME_PORT_KM = 10.0
    private const val AREA_NEAR_KM = 15.0

    /** Km between two [lon, lat] points (flat, enough for ports). */
    private fun km(a: DoubleArray, b: DoubleArray): Double {
        val k = Math.cos(Math.toRadians((a[1] + b[1]) / 2))
        return Math.hypot((a[0] - b[0]) * k, a[1] - b[1]) * 111.2
    }

    /** A port inside [ring] or close to its edge. */
    private fun near(p: DoubleArray, ring: List<DoubleArray>) = inside(p, ring) || ring.any { km(it, p) < AREA_NEAR_KM }

    /** A crossing out and back: its ends close together, or its far point much further out than its end. */
    fun roundTrip(a: DoubleArray, b: DoubleArray, line: List<DoubleArray>): Boolean {
        val end = km(a, b)
        if (end < SAME_PORT_KM) return true
        val far = line.maxOfOrNull { km(a, it) } ?: return false
        return far > 0 && end < 0.6 * far
    }

    private fun inside(p: DoubleArray, ring: List<DoubleArray>): Boolean {
        var c = false; var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]; val b = ring[j]
            if ((a[1] > p[1]) != (b[1] > p[1]) && p[0] < a[0] + (p[1] - a[1]) * (b[0] - a[0]) / (b[1] - a[1])) c = !c
            j = i
        }
        return c
    }

    /**
     * The wind of /api/wind ("points": lat, lon, kmh, dir where it comes from) as arrows pointing where it blows to, in
     * IDmate's four classes (under 15, 30, 45 km/h and above): one collection per class, "rot" the arrow's turn.
     */
    fun wind(points: JSONArray?): List<String> {
        val byClass = List(4) { JSONArray() }
        if (points != null) for (i in 0 until points.length()) {
            val p = points.optJSONObject(i) ?: continue
            val kmh = p.optDouble("kmh", Double.NaN); val dir = p.optDouble("dir", Double.NaN)
            if (kmh.isNaN() || dir.isNaN() || !p.has("lat") || !p.has("lon")) continue
            byClass[windClass(kmh)].put(point(p.getDouble("lon"), p.getDouble("lat"), JSONObject().put("rot", (dir + 180) % 360)))
        }
        return byClass.map { JSONObject().put("type", "FeatureCollection").put("features", it).toString() }
    }

    fun windClass(kmh: Double) = when { kmh < 15 -> 0; kmh < 30 -> 1; kmh < 45 -> 2; else -> 3 }

    /**
     * One frame of /api/v1/rain or /api/rain ("cells": [lat, lon, grade 1..3], cell_km) as squares, one collection per
     * grade (IDmate's three blues).
     */
    fun rain(frame: JSONObject?, cellKm: Double): List<String> {
        val byGrade = List(3) { JSONArray() }
        val cells = frame?.optJSONArray("cells")
        if (cells != null && cellKm > 0) {
            val half = cellKm / 111.2 / 2
            for (i in 0 until cells.length()) {
                val c = cells.optJSONArray(i) ?: continue
                if (c.length() < 3) continue
                val lat = c.optDouble(0); val lon = c.optDouble(1); val grade = c.optInt(2)
                if (grade !in 1..3 || lat.isNaN() || lon.isNaN()) continue
                val hl = cellKm / (111.2 * Math.cos(Math.toRadians(lat))) / 2
                val sq = listOf(doubleArrayOf(lon - hl, lat - half), doubleArrayOf(lon + hl, lat - half), doubleArrayOf(lon + hl, lat + half),
                    doubleArrayOf(lon - hl, lat + half), doubleArrayOf(lon - hl, lat - half))
                byGrade[grade - 1].put(JSONObject().put("type", "Feature").put("properties", JSONObject())
                    .put("geometry", JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(ring(sq)))))
            }
        }
        return byGrade.map { JSONObject().put("type", "FeatureCollection").put("features", it).toString() }
    }

    /** The frame of now in a rain answer (kind "now", else the last), null without frames. */
    fun rainNow(rain: JSONObject?): JSONObject? {
        val frames = rain?.optJSONArray("frames") ?: return null
        if (frames.length() == 0) return null
        for (i in 0 until frames.length()) frames.optJSONObject(i)?.takeIf { it.optString("kind") == "now" }?.let { return it }
        return frames.optJSONObject(frames.length() - 1)
    }

    /** The trail of /api/trail ([[lat, lon], …], the last 10 minutes) as a line; empty with fewer than two points. */
    fun trail(points: JSONArray?): String {
        val pts = ArrayList<DoubleArray>()
        if (points != null) for (i in 0 until points.length()) {
            val p = points.optJSONArray(i) ?: continue
            if (p.length() >= 2) pts += doubleArrayOf(p.optDouble(1), p.optDouble(0))
        }
        return if (pts.size < 2) EMPTY else lineCollection(listOf(pts))
    }

    /**
     * The range as a circle of [km] around the car (IDmate's updateRangeCircle): shown while there is no road area yet,
     * and on the test instance, which has none. 96 points.
     */
    fun circle(lat: Double, lon: Double, km: Double): String = if (km <= 0) EMPTY else lineCollection(listOf(circleRing(lat, lon, km)))

    fun circleRing(lat: Double, lon: Double, km: Double): List<DoubleArray> {
        val dLat = km / 111.2; val dLon = km / (111.2 * Math.cos(Math.toRadians(lat)))
        return (0..96).map { i -> val a = 2 * Math.PI * i / 96; doubleArrayOf(lon + dLon * Math.cos(a), lat + dLat * Math.sin(a)) }
    }

    /** The car's place, "head" its heading when known. */
    fun car(lat: Double, lon: Double, heading: Double?): String = JSONObject().put("type", "FeatureCollection")
        .put("features", JSONArray().put(point(lon, lat, JSONObject().put("head", heading ?: 0.0)))).toString()

    private fun same(a: DoubleArray, b: DoubleArray) = a.size >= 2 && b.size >= 2 && a[0] == b[0] && a[1] == b[1]
    private fun points(a: JSONArray): List<DoubleArray> = (0 until a.length()).mapNotNull { i ->
        a.optJSONArray(i)?.takeIf { it.length() >= 2 }?.let { doubleArrayOf(it.getDouble(0), it.getDouble(1)) } }
    private fun lines(a: JSONArray): List<List<DoubleArray>> = (0 until a.length()).mapNotNull { a.optJSONArray(it)?.let(::points) }
    private fun ring(pts: List<DoubleArray>) = JSONArray().apply { pts.forEach { put(JSONArray().put(it[0]).put(it[1])) } }
    private fun point(lon: Double, lat: Double, props: JSONObject) = JSONObject().put("type", "Feature").put("properties", props)
        .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(lon).put(lat)))
    private fun collection(features: JSONArray) = JSONObject().put("type", "FeatureCollection").put("features", features).toString()
    fun lineCollection(lines: List<List<DoubleArray>>): String {
        val f = JSONArray()
        lines.filter { it.size >= 2 }.forEach { l ->
            f.put(JSONObject().put("type", "Feature").put("properties", JSONObject())
                .put("geometry", JSONObject().put("type", "LineString").put("coordinates", ring(l))))
        }
        return JSONObject().put("type", "FeatureCollection").put("features", f).toString()
    }
    /** West, south, east, north of [rings]; null without points. */
    private fun box(rings: List<List<DoubleArray>>): DoubleArray? {
        val all = rings.flatten()
        if (all.isEmpty()) return null
        return doubleArrayOf(all.minOf { it[0] }, all.minOf { it[1] }, all.maxOf { it[0] }, all.maxOf { it[1] })
    }
}
