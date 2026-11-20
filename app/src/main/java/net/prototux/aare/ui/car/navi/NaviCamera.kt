package net.prototux.aare.ui.car.navi

/**
 * When the camera turns into a coming curve early (N6, 13.11.2026: "wie bei Apple das in Kurven rein drehen … die Drehung
 * anhand der nächsten 100m aber nur wenn die Geschwindigkeit zur Abbiegung passt. Also nicht mit 100kmh rechts abbiegen
 * und das Display dreht durch wenn man mal gerade ausfährt"): the curve of the next [AHEAD_M] gives the speed it is
 * driven at ([LATERAL] m/s² sideways); only up to a little above that speed does the camera look into it.
 */
internal object NaviCamera {
    const val AHEAD_M = 100.0

    /**
     * Whether to look into the curve that turns by [turnDeg] over the next [AHEAD_M], at [speedMs] (null: standing).
     * [was] the last answer: it changes only clearly (30 % over the curve's speed to stop, 10 % to look in again) - at
     * the edge it went back and forth at 100 km/h (16.11.2026).
     */
    fun intoCurve(turnDeg: Double, speedMs: Double?, was: Boolean = true): Boolean {
        val turn = Math.toRadians(Math.abs(turnDeg))
        if (turn < Math.toRadians(3.0)) return true                  // straight on: looking ahead costs nothing
        val radius = AHEAD_M / turn
        val curveSpeed = Math.sqrt(LATERAL * radius)
        return (speedMs ?: 0.0) <= curveSpeed * if (was) LEAVE else ENTER
    }

    private const val LATERAL = 3.0
    private const val LEAVE = 1.3
    private const val ENTER = 1.1
}
