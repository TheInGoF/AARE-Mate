package net.prototux.aare.dash

import org.maplibre.spatialk.geojson.Position
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The dashboard's car between two places (System I-150, 27.11.2026: "Es läuft immer nur ein bisschen mit und dann pausiert
 * das wieder … der Mini-Pfeil zeigt die ganze Zeit Richtung Norden"): each new place gives the speed and the direction
 * (from the move since the place before, where the stick sends no heading), and the car is carried on from it at that
 * speed in that direction until the next one - at most [AHEAD_MS], it may have stopped. Where a new place lies off the
 * carried-on one, the difference is taken out over [BLEND_MS], not jumped. A plain clock, no Android: for the tests.
 */
internal class DashDrive {
    private var fix: Position? = null
    private var fixAt = 0L
    private var speedMs = 0.0
    private var bearing: Double? = null
    /** The carried-on place's offset from the new fix at the time it came (degrees of lat/lon), taken out over [BLEND_MS]. */
    private var offLat = 0.0; private var offLon = 0.0

    /** A new place [p] at [atMs] (uptime); [heading] the stick's, null when it sends none. */
    @Synchronized fun place(p: Position, heading: Double?, atMs: Long) {
        val last = fix
        if (last != null && p.latitude == last.latitude && p.longitude == last.longitude) return
        val shownNow = atLocked(atMs)
        if (last != null && atMs > fixAt) {
            val d = distanceM(last, p)
            val dt = (atMs - fixAt) / 1000.0
            if (d > JUMP_M) { speedMs = 0.0; offLat = 0.0; offLon = 0.0 }
            else {
                speedMs = if (dt > 0) (d / dt).coerceAtMost(MAX_MS) else speedMs
                if (d >= TURN_MIN_M) bearing = bearingDeg(last, p)
                // what was shown against the new place: blended away
                shownNow?.let { (s, _) -> offLat = s.latitude - p.latitude; offLon = s.longitude - p.longitude }
            }
        }
        if (heading != null && speedMs > MOVING_MS) bearing = heading
        fix = p; fixAt = atMs
    }

    /** Where to draw the car at [nowMs], and its direction; null before the first place. */
    @Synchronized fun at(nowMs: Long): Pair<Position, Double?>? = atLocked(nowMs)

    private fun atLocked(nowMs: Long): Pair<Position, Double?>? {
        val p = fix ?: return null
        val t = ((nowMs - fixAt).coerceIn(0, AHEAD_MS)) / 1000.0
        val b = bearing
        var lat = p.latitude; var lon = p.longitude
        if (b != null && speedMs > MOVING_MS) {
            val d = speedMs * t
            lat += d * cos(Math.toRadians(b)) / M_PER_DEG
            lon += d * sin(Math.toRadians(b)) / (M_PER_DEG * cos(Math.toRadians(p.latitude)))
        }
        val keep = (1.0 - (nowMs - fixAt).toDouble() / BLEND_MS).coerceIn(0.0, 1.0)
        return Position(longitude = lon + offLon * keep, latitude = lat + offLat * keep) to b
    }

    companion object {
        /** Carried on no further than this past the last place (the car may have stopped, the stick fallen silent). */
        const val AHEAD_MS = 6_000L
        const val BLEND_MS = 1_000L
        /** Moves shorter than this give no direction (the place's own noise). */
        const val TURN_MIN_M = 8.0
        /** Further than this from the last place: no carrying on, the car is put there. */
        const val JUMP_M = 1_500.0
        const val MOVING_MS = 1.5
        private const val MAX_MS = 70.0
        private const val M_PER_DEG = 111_320.0

        fun distanceM(a: Position, b: Position): Double {
            val dLat = (b.latitude - a.latitude) * M_PER_DEG
            val dLon = (b.longitude - a.longitude) * M_PER_DEG * cos(Math.toRadians((a.latitude + b.latitude) / 2))
            return sqrt(dLat * dLat + dLon * dLon)
        }

        fun bearingDeg(a: Position, b: Position): Double {
            val dLat = b.latitude - a.latitude
            val dLon = (b.longitude - a.longitude) * cos(Math.toRadians((a.latitude + b.latitude) / 2))
            return (Math.toDegrees(atan2(dLon, dLat)) + 360) % 360
        }
    }
}
