package net.prototux.aare.ui.car.navi

import android.content.Context
import org.maplibre.spatialk.geojson.Position
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor

/**
 * Fixed speed cameras along the route (O193 "warnungen sind … blitzer"; decision of 16.11.2026: a switch in the phone
 * app, off by default - in Germany the driver may not use a camera warner while driving, § 23 Abs. 1c StVO). The cameras
 * of Germany and Denmark from OpenStreetMap ship with the app (assets/navi/blitzer.tsv, projekt/werkzeuge/blitzer.py).
 *
 * [along] finds the cameras of a route once, each with how far along the route it stands; one whose direction is known
 * must face along the road or against it, not across (that one watches a crossing road). [due] then names the next one
 * to warn of: once each, about 15 s ahead at the speed driven (300 m at least, 800 m at most). Announced as a danger spot,
 * as other apps do (O202, 16.11.2026: "nciht vor blitzern warnen. sondern vor gefahrenstellen").
 */
internal object NaviCameras {
    class Camera(val at: Position, val kmh: Int, val direction: Int)
    class OnRoute(val camera: Camera, val alongM: Double)

    private const val CELL = 0.05
    private const val NEAR_M = 25.0
    @Volatile private var cells: Map<Long, List<Camera>>? = null

    private fun index(context: Context): Map<Long, List<Camera>> = cells ?: synchronized(this) {
        cells ?: HashMap<Long, MutableList<Camera>>().also { m ->
            context.assets.open("navi/blitzer.tsv").bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
                    val f = line.split('\t')
                    if (f.size < 4) return@forEach
                    val c = Camera(Position(longitude = f[1].toDouble(), latitude = f[0].toDouble()), f[2].toInt(), f[3].toInt())
                    m.getOrPut(key(c.at.latitude, c.at.longitude)) { ArrayList() } += c
                }
            }
            cells = m
        }
    }

    /** The cameras standing on [line] (a route's points), in order, with their distance along it. */
    fun along(context: Context, line: List<Position>): List<OnRoute> {
        val idx = index(context)
        val found = LinkedHashMap<Camera, OnRoute>()
        var along = 0.0
        for (i in 0 until line.size - 1) {
            val a = line[i]; val b = line[i + 1]
            val seg = ChargerIndex.distanceM(a, b)
            val heading = bearing(a, b)
            for (c in candidates(idx, a, b)) {
                if (c in found) continue
                val (d, t) = offset(c.at, a, b)
                if (d > NEAR_M) continue
                if (c.direction >= 0 && !alongTheRoad(c.direction.toDouble(), heading)) continue
                found[c] = OnRoute(c, along + t * seg)
            }
            along += seg
        }
        return found.values.sortedBy { it.alongM }
    }

    /** The next camera to warn of at [nowAlongM] along the route and [kmh], not one of [warned]; null when none is near. */
    fun due(cameras: List<OnRoute>, nowAlongM: Double, kmh: Double?, warned: Set<OnRoute>): OnRoute? {
        val ahead = ((kmh ?: 50.0) / 3.6 * 15).coerceIn(300.0, 800.0)
        return cameras.firstOrNull { it !in warned && it.alongM - nowAlongM in 0.0..ahead }
    }

    /** "Blitzer in 400 Metern": the distance rounded to 50 m. */
    fun metres(c: OnRoute, nowAlongM: Double) = (Math.round((c.alongM - nowAlongM) / 50.0) * 50).coerceAtLeast(50)

    private fun candidates(idx: Map<Long, List<Camera>>, a: Position, b: Position): List<Camera> {
        val out = ArrayList<Camera>()
        val keys = HashSet<Long>()
        for (p in listOf(a, b)) for (dl in -1..1) for (dn in -1..1) keys += key(p.latitude + dl * CELL, p.longitude + dn * CELL)
        keys.forEach { k -> idx[k]?.let { out += it } }
        return out
    }

    /** How far [p] lies from the segment [a]-[b] in metres, and where along it (0..1). */
    private fun offset(p: Position, a: Position, b: Position): Pair<Double, Double> {
        val k = cos(Math.toRadians(a.latitude))
        val ax = a.longitude * k; val ay = a.latitude; val bx = b.longitude * k; val by = b.latitude
        val px = p.longitude * k; val py = p.latitude
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val x = ax + t * dx - px; val y = ay + t * dy - py
        return Math.sqrt(x * x + y * y) * ChargerIndex.M_PER_DEG to t
    }

    private fun bearing(a: Position, b: Position): Double {
        val x = (b.longitude - a.longitude) * cos(Math.toRadians(a.latitude)); val y = b.latitude - a.latitude
        return (Math.toDegrees(atan2(x, y)) + 360) % 360
    }

    /** Along the road (within 60°) or against it - mappers note the camera's facing or the traffic's direction. */
    fun alongTheRoad(direction: Double, heading: Double): Boolean {
        val d = abs(((direction - heading) % 360 + 540) % 360 - 180)
        return d <= 60 || d >= 120
    }

    private fun key(lat: Double, lon: Double) = (floor(lat / CELL).toLong() shl 32) or (floor(lon / CELL).toLong() and 0xffffffffL)
}
