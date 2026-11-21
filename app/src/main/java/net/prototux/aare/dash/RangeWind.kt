package net.prototux.aare.dash

import net.prototux.aare.host.EnergyModel
import kotlin.math.cos
import kotlin.math.sin

/**
 * The wind in the range on the dashboard's map (Navi N-019 step 1, E-029; Ingo, 21.11.2026: "Mate rechnet die Fläche
 * selbst"): IDmate's range area without wind is stretched where the wind blows from behind and shrunk where it comes
 * against the car - each point of the outline by what driving in its direction takes with the wind against without it,
 * at [KMH]. An area IDmate already made with wind (its "line" outline) is left as it is, so the wind never counts twice.
 */
internal object RangeWind {
    /** A typical speed over the area's roads (a mix of road and motorway). */
    const val KMH = 90.0
    /** The range changes at most this much either way, whatever the wind. */
    const val MIN_FACTOR = 0.6
    const val MAX_FACTOR = 1.4
    /** Below this the wind is not worth drawing. */
    const val CALM_MS = 1.5

    /** The wind as one: from [fromDeg] at [ms]. */
    class Wind(val fromDeg: Double, val ms: Double)

    /** The mean of IDmate's wind points ([kmh] and [dirFrom] in degrees each), as vectors; null without any. */
    fun mean(points: List<Pair<Double, Double>>): Wind? {
        if (points.isEmpty()) return null
        var x = 0.0; var y = 0.0
        for ((kmh, from) in points) { val r = Math.toRadians(from); x += kmh / 3.6 * sin(r); y += kmh / 3.6 * cos(r) }
        x /= points.size; y /= points.size
        val ms = Math.hypot(x, y)
        return Wind((Math.toDegrees(Math.atan2(x, y)) + 360.0) % 360.0, ms)
    }

    /** How far the car gets driving towards [bearingDeg] in [wind], against calm air (above 1: further). */
    fun factor(bearingDeg: Double, wind: Wind, car: EnergyModel.Car): Double {
        val head = net.prototux.aare.ui.car.navi.ChargeEnergy.headwindMs(bearingDeg, wind.fromDeg, wind.ms)
        val calm = EnergyModel.powerW(KMH, car)
        val with = calm + EnergyModel.windW(KMH, head, car)
        return if (with <= 0) MAX_FACTOR else (calm / with).coerceIn(MIN_FACTOR, MAX_FACTOR)
    }

    /** [ring] ([lon, lat] points) stretched from [center] ([lon, lat]) by the wind in each direction. */
    fun deform(ring: List<DoubleArray>, center: DoubleArray, wind: Wind, car: EnergyModel.Car): List<DoubleArray> {
        if (wind.ms < CALM_MS) return ring
        val k = cos(Math.toRadians(center[1]))
        return ring.map { p ->
            val dx = (p[0] - center[0]) * k; val dy = p[1] - center[1]
            if (dx == 0.0 && dy == 0.0) return@map p
            val bearing = (Math.toDegrees(Math.atan2(dx, dy)) + 360.0) % 360.0
            val f = factor(bearing, wind, car)
            doubleArrayOf(center[0] + (p[0] - center[0]) * f, center[1] + dy * f)
        }
    }
}
