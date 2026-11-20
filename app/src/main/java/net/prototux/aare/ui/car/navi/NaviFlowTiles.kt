package net.prototux.aare.ui.car.navi

import org.maplibre.spatialk.geojson.Position

/**
 * The closed roads in TomTom's flow tiles (O219, 17.11.2026: the incidents named only the west end of the closed Tunnel
 * Tiergarten, the flow tiles mark the whole road closed - and the route took the rest of it). Reads the tiles' "Traffic
 * flow" layer (Mapbox vector tiles, protobuf) for the lines with road_closure, as coordinates.
 */
internal object NaviFlowTiles {
    /** The closed lines in the tiles at [ZOOM] the [line] runs through (each tile once). */
    fun closedLines(fetch: (String) -> ByteArray?, base: String, line: List<Position>): List<List<Position>> {
        val tiles = LinkedHashSet<Pair<Int, Int>>()
        var last: Position? = null
        for (p in line) {
            if (last != null && ChargerIndex.distanceM(last, p) < 150) continue
            last = p; tiles += tileOf(p)
        }
        line.lastOrNull()?.let { tiles += tileOf(it) }
        // six at a time (one after another took ~10 s for a 300 km trip)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        try {
            return tiles.map { (x, y) -> pool.submit<List<List<Position>>> {
                val bytes = fetch("$base/verkehr/traffic/map/4/tile/flow/relative/$ZOOM/$x/$y.pbf") ?: return@submit emptyList()
                runCatching { decode(bytes, x, y) }.getOrDefault(emptyList())
            } }.flatMap { runCatching { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList()) }
        } finally { pool.shutdown() }
    }

    /** A road of the flow tiles with its traffic_level (speed now / free speed, 0..1). */
    class Flow(val line: List<Position>, val level: Double)

    /**
     * Every road of the flow tiles the [line] runs through, with its level (17.11.2026: "laut Map sind alle Straßen gelb
     * oder rot" - the route is coloured from the flow, as Google does, not only from TomTom's incident reports).
     */
    fun flowLines(fetch: (String) -> ByteArray?, base: String, line: List<Position>): List<Flow> {
        val tiles = LinkedHashSet<Pair<Int, Int>>()
        var last: Position? = null
        for (p in line) { if (last != null && ChargerIndex.distanceM(last, p) < 150) continue; last = p; tiles += tileOf(p) }
        line.lastOrNull()?.let { tiles += tileOf(it) }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        try {
            return tiles.map { (x, y) -> pool.submit<List<Flow>> {
                val bytes = fetch("$base/verkehr/traffic/map/4/tile/flow/relative/$ZOOM/$x/$y.pbf") ?: return@submit emptyList()
                runCatching { decodeFlow(bytes, x, y) }.getOrDefault(emptyList())
            } }.flatMap { runCatching { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList()) }
        } finally { pool.shutdown() }
    }

    fun decodeFlow(bytes: ByteArray, tx: Int, ty: Int): List<Flow> {
        val data = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
            java.util.zip.GZIPInputStream(bytes.inputStream()).readBytes() else bytes
        val out = ArrayList<Flow>()
        Pb(data).fields { f, v -> if (f == 3 && v is ByteArray) out += layerFlow(v, tx, ty) }
        return out
    }

    private fun layerFlow(b: ByteArray, tx: Int, ty: Int): List<Flow> {
        var name = ""; val keys = ArrayList<String>(); val values = ArrayList<Any?>(); val features = ArrayList<ByteArray>(); var extent = 4096
        Pb(b).fields { f, v -> when (f) { 1 -> name = String(v as ByteArray); 2 -> features += v as ByteArray; 3 -> keys += String(v as ByteArray)
            4 -> values += value(v as ByteArray); 5 -> extent = (v as Long).toInt() } }
        if (name != "Traffic flow") return emptyList()
        val levelKey = keys.indexOf("traffic_level").takeIf { it >= 0 } ?: return emptyList()
        val closureKey = keys.indexOf("road_closure")
        val out = ArrayList<Flow>()
        for (feat in features) {
            var tags = LongArray(0); var geom = LongArray(0)
            Pb(feat).fields { f, v -> when (f) { 2 -> tags = packed(v as ByteArray); 4 -> geom = packed(v as ByteArray) } }
            var level = 1.0; var closed = false; var i = 0
            while (i + 1 < tags.size) {
                val k = tags[i].toInt(); val v = values.getOrNull(tags[i + 1].toInt())
                if (k == levelKey) level = (v as? Number)?.toDouble() ?: level
                if (k == closureKey && v == true) closed = true
                i += 2
            }
            // closures are drawn as crosses; free-flowing roads need no colour
            if (closed || level >= 0.75) continue
            lines(geom, extent, tx, ty).forEach { out += Flow(it, level) }
        }
        return out
    }

    /**
     * The [route]'s stretches on slow roads, in metres along it: (from, to, 0 slow / 1 jam / 2 standing) - the map's own
     * steps (< 0.75, < 0.5, < 0.25). Checked every [stepM] m: a flow line within 15 m, running the route's way (each
     * direction of a road is its own line).
     */
    fun levelsAlong(route: List<Position>, flows: List<Flow>, stepM: Double = 25.0): List<Triple<Double, Double, Int>> {
        if (route.size < 2 || flows.isEmpty()) return emptyList()
        // the flow segments in a grid of ~100 m cells
        class Seg(val a: Position, val b: Position, val level: Double)
        val cell = 0.001
        val grid = HashMap<Long, ArrayList<Seg>>()
        fun key(lon: Double, lat: Double) = (Math.floor(lon / cell).toLong() shl 32) xor (Math.floor(lat / cell).toLong() and 0xffffffffL)
        for (f in flows) for (k in 1 until f.line.size) {
            val sg = Seg(f.line[k - 1], f.line[k], f.level)
            val lo0 = minOf(sg.a.longitude, sg.b.longitude); val lo1 = maxOf(sg.a.longitude, sg.b.longitude)
            val la0 = minOf(sg.a.latitude, sg.b.latitude); val la1 = maxOf(sg.a.latitude, sg.b.latitude)
            var x = Math.floor(lo0 / cell); while (x <= Math.floor(lo1 / cell)) { var y = Math.floor(la0 / cell)
                while (y <= Math.floor(la1 / cell)) { grid.getOrPut(key(x * cell + cell / 2, y * cell + cell / 2)) { ArrayList() } += sg; y++ }; x++ }
        }
        fun bearing(a: Position, b: Position) = Math.toDegrees(Math.atan2((b.longitude - a.longitude) * Math.cos(Math.toRadians(a.latitude)), b.latitude - a.latitude))
        fun near(p: Position, s: Seg): Double {
            val kx = 111_320 * Math.cos(Math.toRadians(p.latitude)); val ky = 110_540.0
            val ax = (s.a.longitude - p.longitude) * kx; val ay = (s.a.latitude - p.latitude) * ky
            val bx = (s.b.longitude - p.longitude) * kx; val by = (s.b.latitude - p.latitude) * ky
            val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0)
            return Math.hypot(ax + t * dx, ay + t * dy)
        }
        val out = ArrayList<Triple<Double, Double, Int>>()
        var m = 0.0; var nextAt = 0.0; var curLvl = -1; var curFrom = 0.0
        for (k in 1 until route.size) {
            val a = route[k - 1]; val b = route[k]; val seg = ChargerIndex.distanceM(a, b); val br = bearing(a, b)
            while (nextAt <= m + seg) {
                val f = if (seg <= 0) 0.0 else (nextAt - m) / seg
                val p = Position(longitude = a.longitude + (b.longitude - a.longitude) * f, latitude = a.latitude + (b.latitude - a.latitude) * f)
                var best = 1.0
                for (dx in -1..1) for (dy in -1..1) grid[key(p.longitude + dx * cell, p.latitude + dy * cell)]?.forEach { s ->
                    if (s.level < best && near(p, s) < 15.0) {
                        val d = Math.abs(((bearing(s.a, s.b) - br + 540) % 360) - 180)
                        if (d < 35) best = s.level     // the route's way only (each direction is its own line)
                    }
                }
                val lvl = when { best < 0.25 -> 2; best < 0.5 -> 1; best < 0.75 -> 0; else -> -1 }
                if (lvl != curLvl) { if (curLvl >= 0) out += Triple(curFrom, nextAt, curLvl); curLvl = lvl; curFrom = nextAt }
                nextAt += stepM
            }
            m += seg
        }
        if (curLvl >= 0) out += Triple(curFrom, m, curLvl)
        // single 25 m blips (a crossing road's line) are left out
        return out.filter { it.second - it.first >= stepM * 2 }
    }

    private fun tileOf(p: Position): Pair<Int, Int> {
        val n = 1 shl ZOOM
        val x = ((p.longitude + 180) / 360 * n).toInt()
        val lat = Math.toRadians(p.latitude)
        val y = ((1 - Math.log(Math.tan(lat) + 1 / Math.cos(lat)) / Math.PI) / 2 * n).toInt()
        return x to y
    }

    /** The lines of tile [tx],[ty] whose feature has road_closure true, in lon/lat. */
    fun decode(bytes: ByteArray, tx: Int, ty: Int): List<List<Position>> {
        val data = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
            java.util.zip.GZIPInputStream(bytes.inputStream()).readBytes() else bytes
        val out = ArrayList<List<Position>>()
        Pb(data).fields { f, v -> if (f == 3 && v is ByteArray) out += layer(v, tx, ty) }
        return out
    }

    private fun layer(b: ByteArray, tx: Int, ty: Int): List<List<Position>> {
        var name = ""; val keys = ArrayList<String>(); val values = ArrayList<Any?>(); val features = ArrayList<ByteArray>(); var extent = 4096
        Pb(b).fields { f, v ->
            when (f) {
                1 -> name = String(v as ByteArray)
                2 -> features += v as ByteArray
                3 -> keys += String(v as ByteArray)
                4 -> values += value(v as ByteArray)
                5 -> extent = (v as Long).toInt()
            }
        }
        if (name != "Traffic flow") return emptyList()
        val closureKey = keys.indexOf("road_closure").takeIf { it >= 0 } ?: return emptyList()
        val out = ArrayList<List<Position>>()
        for (feat in features) {
            var tags = LongArray(0); var geom = LongArray(0)
            Pb(feat).fields { f, v -> when (f) { 2 -> tags = packed(v as ByteArray); 4 -> geom = packed(v as ByteArray) } }
            var closed = false
            var i = 0
            while (i + 1 < tags.size) { if (tags[i].toInt() == closureKey && values.getOrNull(tags[i + 1].toInt()) == true) closed = true; i += 2 }
            if (closed) out += lines(geom, extent, tx, ty)
        }
        return out
    }

    /** The line strings of a geometry (MoveTo/LineTo with zigzag deltas) in lon/lat. */
    private fun lines(g: LongArray, extent: Int, tx: Int, ty: Int): List<List<Position>> {
        val out = ArrayList<List<Position>>()
        var cur = ArrayList<Position>()
        var x = 0L; var y = 0L; var i = 0
        val n = (1 shl ZOOM).toDouble()
        fun at(px: Long, py: Long): Position {
            val lon = (tx + px.toDouble() / extent) / n * 360 - 180
            val lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * (ty + py.toDouble() / extent) / n))))
            return Position(longitude = lon, latitude = lat)
        }
        while (i < g.size) {
            val cmd = (g[i] and 7).toInt(); val count = (g[i] shr 3).toInt(); i++
            when (cmd) {
                1, 2 -> repeat(count) {
                    if (i + 1 >= g.size) return@repeat
                    x += zigzag(g[i]); y += zigzag(g[i + 1]); i += 2
                    if (cmd == 1) { if (cur.size > 1) out += cur; cur = ArrayList() }
                    cur += at(x, y)
                }
                7 -> {}
                else -> return out
            }
        }
        if (cur.size > 1) out += cur
        return out
    }

    private fun zigzag(v: Long) = (v ushr 1) xor -(v and 1)

    private fun value(b: ByteArray): Any? {
        var r: Any? = null
        Pb(b).fields { f, v -> r = when (f) { 1 -> String(v as ByteArray); 2 -> (v as Float).toDouble(); 3 -> v as Double; 7 -> (v as Long) != 0L; else -> v } }
        return r
    }

    private fun packed(b: ByteArray): LongArray {
        val out = ArrayList<Long>(); val p = Pb(b)
        while (p.more()) out += p.varint()
        return out.toLongArray()
    }

    /** A minimal protobuf reader: varints and length-delimited fields; fixed 32/64-bit skipped. */
    private class Pb(val b: ByteArray) {
        var i = 0
        fun more() = i < b.size
        fun varint(): Long { var r = 0L; var s = 0; while (true) { val c = b[i++].toInt() and 0xff; r = r or ((c and 0x7f).toLong() shl s); s += 7; if (c < 0x80) return r } }
        fun fields(each: (Int, Any) -> Unit) {
            while (more()) {
                val k = varint(); val f = (k shr 3).toInt()
                when ((k and 7).toInt()) {
                    0 -> each(f, varint())
                    2 -> { val l = varint().toInt(); each(f, b.copyOfRange(i, i + l)); i += l }
                    // 32- and 64-bit fields: floats and doubles of the values (traffic_level, 17.11.2026 - skipped before)
                    5 -> { var v = 0L; for (n in 0 until 4) v = v or ((b[i + n].toLong() and 0xff) shl (8 * n)); i += 4; each(f, java.lang.Float.intBitsToFloat(v.toInt())) }
                    1 -> { var v = 0L; for (n in 0 until 8) v = v or ((b[i + n].toLong() and 0xff) shl (8 * n)); i += 8; each(f, java.lang.Double.longBitsToDouble(v)) }
                    else -> return
                }
            }
        }
    }

    const val ZOOM = 12   // each tile a TomTom call: 12 halves them against 13 (TomTomBudget)
}
