package net.prototux.aare.ui.car.navi

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Where a roundabout is left, as seen from where it is entered (18.11.2026: "Der Kreisverkehr geht eigentlich geradeaus
 * rüber, aber er zeigt an, dass ich rechts abbiegen soll ... Die Symbole müssen nicht zur Anzahl der Ausfahrten passen,
 * aber sie sollten zumindest logisch in die Route passen"). The symbol was one picture for every roundabout, its arrow
 * always leaving to the right. The route itself says where it leaves: the way driven towards the roundabout and the way
 * driven away from it.
 *
 * The angle is the Car App Library's (and the car's): the degrees driven round from the entry - with right-hand traffic
 * (counter-clockwise) 90 leaves to the right, 180 straight on, 270 to the left, 360 back where one came from.
 */
internal object NaviRoundabout {
    /** Metres skipped right at the roundabout (the road bends round its island there) and how far the look goes. */
    private const val SKIP_M = 12.0
    private const val LOOK_M = 60.0

    private fun metres(a: Pair<Double, Double>, b: Pair<Double, Double>): Double =
        hypot((b.first - a.first) * 111_200.0, (b.second - a.second) * 111_200.0 * cos(Math.toRadians((a.first + b.first) / 2)))

    private fun bearing(a: Pair<Double, Double>, b: Pair<Double, Double>): Double =
        (Math.toDegrees(atan2((b.second - a.second) * cos(Math.toRadians((a.first + b.first) / 2)), b.first - a.first)) + 360) % 360

    /** The point [m] metres along [pts] (lat, lon) from its start, the last one when the way is shorter. */
    private fun along(pts: List<Pair<Double, Double>>, m: Double): Pair<Double, Double> {
        var left = m
        for (i in 1 until pts.size) {
            val d = metres(pts[i - 1], pts[i])
            if (d >= left && d > 0) { val f = left / d; return (pts[i - 1].first + (pts[i].first - pts[i - 1].first) * f) to (pts[i - 1].second + (pts[i].second - pts[i - 1].second) * f) }
            left -= d
        }
        return pts.last()
    }

    /** The direction driven over the first metres of [pts], away from its start; null when it has no length. */
    fun headingFromStart(pts: List<Pair<Double, Double>>): Double? {
        if (pts.size < 2) return null
        var a = along(pts, SKIP_M); val b = along(pts, LOOK_M)
        if (metres(a, b) < 3.0) a = pts.first()
        return if (metres(a, b) < 1.0) null else bearing(a, b)
    }

    /** The direction driven over the last metres of [pts], towards its end. */
    fun headingToEnd(pts: List<Pair<Double, Double>>): Double? = headingFromStart(pts.asReversed())?.let { (it + 180) % 360 }

    /**
     * The exit angle (1..360) from the way [towards] the roundabout and the way [away] from it; where nothing leads
     * away (the route ends in it) the last of the way [inside]. Null when the ways tell nothing.
     */
    fun exitAngle(towards: List<Pair<Double, Double>>, inside: List<Pair<Double, Double>>, away: List<Pair<Double, Double>>, clockwise: Boolean): Int? {
        val came = headingToEnd(towards) ?: return null
        val goes = headingFromStart(away) ?: headingToEnd(inside) ?: return null
        val turn = ((goes - came + 540) % 360) - 180          // to the right is positive
        val round = ((if (clockwise) 180 + turn else 180 - turn) % 360 + 360) % 360
        return round.roundToInt().let { if (it < 1) 360 else it }.coerceIn(1, 360)
    }

    /**
     * The angle as drawn: the angle itself (18.11.2026: "der Kreisverkehrwinkel ist 70 Grad ... so anzeigen, dass wir
     * diese 70 Grad Abbiegung auch wahrnehmen" - it was rounded to eighths of the circle), kept off the way in: at least
     * 25 degrees after it, and a turn back all round stops 20 degrees short of it.
     */
    fun drawn(angle: Int): Int = angle.coerceIn(25, 340)
}
