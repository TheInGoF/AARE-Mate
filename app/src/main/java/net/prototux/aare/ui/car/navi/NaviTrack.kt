package net.prototux.aare.ui.car.navi

import org.maplibre.spatialk.geojson.Position
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * The drawn (smoothed) route as a track one can stand on: a place on it is its distance from the start. The car glides
 * along it between two positions, so it stays on the line in curves too; its heading is the line's direction over
 * [HEADING_SPAN_M] behind and ahead (wish of 16.11.2026: "einen mittelwert aus den nächsten und letzten 10m").
 */
internal class NaviTrack(private val line: List<Position>) {
    private val k = 111_320.0
    private val cosLat = cos(Math.toRadians(line.first().latitude))
    private val cum = DoubleArray(line.size).also { c ->
        for (i in 1 until line.size) c[i] = c[i - 1] + metres(line[i - 1], line[i])
    }
    val length get() = cum.last()
    private var hint = 0
    private var lastS = -1.0

    private fun metres(a: Position, b: Position) =
        hypot((b.latitude - a.latitude) * k, (b.longitude - a.longitude) * k * cosLat)

    /** Distance along the line of the point on it nearest to [lat]/[lon]; looks near the last answer first. */
    fun project(lat: Double, lon: Double): Double {
        var best = Double.MAX_VALUE
        var bestCost = Double.MAX_VALUE
        var bestS = 0.0
        var bestI = hint
        val from = (hint - 40).coerceAtLeast(0)
        val to = (hint + 200).coerceAtMost(line.size - 2)
        val prev = lastS
        fun scan(range: IntRange, keepOn: Boolean) {
            for (i in range) {
                val a = line[i]; val b = line[i + 1]
                val ax = 0.0; val ay = 0.0
                val bx = (b.longitude - a.longitude) * k * cosLat; val by = (b.latitude - a.latitude) * k
                val px = (lon - a.longitude) * k * cosLat; val py = (lat - a.latitude) * k
                val len2 = bx * bx + by * by
                val t = if (len2 < 1e-9) 0.0 else ((px - ax) * bx + (py - ay) * by) / len2
                val tt = t.coerceIn(0.0, 1.0)
                val d = hypot(px - bx * tt, py - by * tt)
                val sc = cum[i] + (cum[i + 1] - cum[i]) * tt
                // System I-086 (22.11.2026: "Er fährt die letzten Meter rückwärts zu Säule"): the way into a charger and back
                // out runs on the same road - the way out lay as near as the way in and won by a metre of GPS noise, so the
                // car stood beyond the charger and drove back to it. Far ahead of the last place counts only when it is
                // clearly nearer: the further a place lies from the last one along the line, ahead or back, the more it must
                // beat the others by (a fifth of a metre for each metre beyond [AHEAD_FREE_M]) - on an ordinary road only one
                // stretch is near, so this decides only between stretches lying on top of each other
                val cost = if (!keepOn || prev < 0) d else d + 0.2 * (Math.abs(sc - prev) - AHEAD_FREE_M).coerceAtLeast(0.0)
                if (cost < bestCost) { bestCost = cost; best = d; bestS = sc; bestI = i }
            }
        }
        scan(from..to, true)
        if (best > 50) { bestCost = Double.MAX_VALUE; scan(0..line.size - 2, false) }   // far off the window (start, a jump): the whole line
        hint = bestI
        lastS = bestS
        return bestS
    }

    /** The point at distance [s] along the line. */
    fun at(s: Double): Position {
        val d = s.coerceIn(0.0, length)
        var lo = 0; var hi = cum.size - 1
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (cum[m] <= d) lo = m else hi = m }
        val seg = cum[hi] - cum[lo]
        val t = if (seg < 1e-9) 0.0 else (d - cum[lo]) / seg
        val a = line[lo]; val b = line[hi]
        return Position(longitude = a.longitude + (b.longitude - a.longitude) * t, latitude = a.latitude + (b.latitude - a.latitude) * t)
    }

    /** Compass direction from the point at [s1] to the point at [s2]. */
    fun bearing(s1: Double, s2: Double): Double {
        val a = at(s1); val b = at(s2)
        val deg = Math.toDegrees(atan2((b.longitude - a.longitude) * cosLat, b.latitude - a.latitude))
        return (deg + 360) % 360
    }

    /** The car's heading at [s]: the line's direction from [HEADING_SPAN_M] behind to [HEADING_SPAN_M] ahead. */
    fun heading(s: Double) = bearing(s - HEADING_SPAN_M, s + HEADING_SPAN_M)

    companion object { const val HEADING_SPAN_M = 10.0 }
}

/** How far from the last place along the line a position may move before it must be clearly nearer (System I-086). */
private const val AHEAD_FREE_M = 10.0
