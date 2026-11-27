package net.prototux.aare.ui.car.navi

import android.content.Context
import org.maplibre.spatialk.geojson.Position

/**
 * The speed the plan counts with along a route (Navi I-071, 27.11.2026: "Ich kann hier nur 120 fahren. Das müsste er
 * wissen, das ist in den Kartendaten vorhanden … nicht nur Wetter, Wind, Temperaturen, sondern auch Höchstgeschwindigkeiten.
 * Wenn eine 15-km-Baustelle mit 80 km/h ist, wüsste er das und kann den Verbrauch richtig abschätzen"): the limit of the
 * map where there is one (the driver keeps to it), the driver's own speed on open motorway without a limit (learned from
 * the drives), 80 at most through road works, the route's own speed elsewhere.
 */
internal object NaviPace {
    private const val PREFS = "navi_pace"
    /** Road works are driven at this at most (the usual limit there). */
    const val WORKS_KMH = 80.0
    /** Stretches the route takes at this or more are open road; slower ones keep the route's own speed. */
    const val OPEN_KMH = 90.0
    /** The driver's speed on open motorway is learned from stretches without a limit at this or more. */
    private const val LEARN_FROM_KMH = 100.0
    /** How many km of the past count against a new stretch (the weight of what was learned). */
    private const val MEMORY_KM = 150.0

    /** The driver's own speed on motorway without a limit, km/h; null before any was driven. */
    fun cruise(c: Context): Double? = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("cruise", 0f).takeIf { it > 0 }?.toDouble()

    /** A stretch of [km] driven at [kmh] where there is no limit: into the driver's speed (weighted by kilometres). */
    fun note(c: Context, kmh: Double, km: Double) {
        if (kmh < LEARN_FROM_KMH || km <= 0) return
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = cruise(c)
        val now = if (old == null) kmh else old + (kmh - old) * (km / (km + MEMORY_KM))
        p.edit().putFloat("cruise", now.toFloat()).apply()
    }

    /** The speed at a place: [base] the route's, [limit] the map's there (UNLIMITED, null unknown), [cruise] the driver's, [works] inside road works. */
    fun speedAt(base: Double, limit: Int?, cruise: Double?, works: Boolean): Double {
        val v = if (base < OPEN_KMH) base else when (limit) {
            NaviLimits.UNLIMITED -> cruise ?: base
            null, 0 -> base
            else -> if (cruise != null) minOf(limit.toDouble(), cruise) else limit.toDouble()
        }
        return if (works) minOf(v, WORKS_KMH) else v
    }

    /**
     * The speed function for a track (ChargePlanner.track): the limits looked up by place ([limitAt], a NaviLimits walker
     * or none), the works as stretches in metres along the route ([worksM]).
     */
    fun along(limitAt: ((Position) -> Int?)?, cruise: Double?, worksM: List<Pair<Double, Double>>): (Double, Position, Double) -> Double =
        { m, p, base -> speedAt(base, limitAt?.invoke(p), cruise, worksM.any { (a, b) -> m in a..b }) }
}
