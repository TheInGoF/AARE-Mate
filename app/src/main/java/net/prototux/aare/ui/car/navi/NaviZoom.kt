package net.prototux.aare.ui.car.navi

/**
 * The driving zoom as Google Maps shows it (F5, O189: "Übersicht/Zoom nach Manöver"): close in town and before a turn,
 * further out the faster the car goes and the further the next turn is, so a motorway shows what comes. Changes glide.
 */
internal object NaviZoom {
    const val NEAR = 16.5
    const val FAR = 14.5

    /** The zoom wanted at [kmh] with the next turn [nextM] ahead. */
    fun target(kmh: Double?, nextM: Double?): Double {
        val v = (kmh ?: 0.0).coerceAtLeast(0.0)
        // by speed: 50 km/h and slower close, 130 km/h 1.5 levels out
        var z = NEAR - ((v - 50.0) / 80.0).coerceIn(0.0, 1.0) * 1.5
        // a long way to the next turn: half a level further out
        if (nextM != null && nextM > LONG_M) z -= 0.5
        // the turn is near: close again, whatever the speed
        if (nextM != null && nextM < NEAR_TURN_M) z = NEAR
        return z.coerceIn(FAR, NEAR)
    }

    /** One step from [now] towards [target] after [dtMs]: about 2 s for most of the way, never a jump. */
    fun glide(now: Double, target: Double, dtMs: Long): Double {
        val k = (dtMs / GLIDE_MS).coerceIn(0.0, 1.0)
        return now + (target - now) * k
    }

    private const val LONG_M = 3_000.0
    private const val NEAR_TURN_M = 400.0
    private const val GLIDE_MS = 2_000.0
}
