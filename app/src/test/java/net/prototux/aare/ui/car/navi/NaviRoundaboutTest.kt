package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** 18.11.2026: a roundabout driven straight over showed the exit to the right - the exit now comes from the route's way. */
class NaviRoundaboutTest {
    private val lat0 = 50.83; private val lon0 = 12.92
    /** A point [north] and [east] metres from the roundabout's middle. */
    private fun p(north: Double, east: Double) = (lat0 + north / 111_200.0) to (lon0 + east / (111_200.0 * cos(Math.toRadians(lat0))))
    /** A straight way from [fromM] to [toM] metres out along [bearing] (0 north, 90 east), a point every 10 m. */
    private fun way(bearing: Double, fromM: Int, toM: Int): List<Pair<Double, Double>> {
        val step = if (toM >= fromM) 10 else -10
        return generateSequence(fromM) { it + step }.takeWhile { if (step > 0) it <= toM else it >= toM }
            .map { m -> p(m * cos(Math.toRadians(bearing)), m * sin(Math.toRadians(bearing))) }.toList()
    }
    /** Coming from the south (driving north) towards the roundabout, 15 m in radius. */
    private val fromSouth = way(180.0, 120, 15)
    /** Round the island against the clock from the south to where the road at [bearing] leaves. */
    private fun round(toBearing: Double): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var b = 180.0
        val sweep = ((180.0 - toBearing) % 360 + 360) % 360       // against the clock: south -> east -> north -> west
        var done = 0.0
        while (done <= sweep) { out += p(15 * cos(Math.toRadians(b)), 15 * sin(Math.toRadians(b))); b -= 15; done += 15 }
        return out
    }

    @Test fun straightOverIsStraight() {
        assertEquals(180, NaviRoundabout.exitAngle(fromSouth, round(0.0), way(0.0, 15, 120), false))
        // drawn at the angle itself (18.11.2026: "70 Grad ... auch wahrnehmen"), off the way in at both ends
        assertEquals(172, NaviRoundabout.drawn(172))
        assertEquals(70, NaviRoundabout.drawn(70))
        assertEquals(25, NaviRoundabout.drawn(5))
        assertEquals(340, NaviRoundabout.drawn(360))
    }

    @Test fun rightLeftAndBack() {
        assertEquals(90, NaviRoundabout.exitAngle(fromSouth, round(90.0), way(90.0, 15, 120), false))
        assertEquals(270, NaviRoundabout.exitAngle(fromSouth, round(270.0), way(270.0, 15, 120), false))
        assertEquals(360, NaviRoundabout.exitAngle(fromSouth, round(180.0), way(180.0, 15, 120), false))
        // half right: an eighth of the circle more than the right
        assertEquals(135.0, NaviRoundabout.drawn(NaviRoundabout.exitAngle(fromSouth, round(45.0), way(45.0, 15, 120), false)!!).toDouble(), 3.0)
    }

    @Test fun drivingOnTheLeftGoesRoundTheOtherWay() {
        assertEquals(90, NaviRoundabout.exitAngle(fromSouth, emptyList(), way(270.0, 15, 120), true))
        assertEquals(270, NaviRoundabout.exitAngle(fromSouth, emptyList(), way(90.0, 15, 120), true))
        assertEquals(180, NaviRoundabout.exitAngle(fromSouth, emptyList(), way(0.0, 15, 120), true))
    }

    @Test fun theBendAtTheIslandDoesNotCount() {
        // the road swings 10 m to the right just before the island and the exit swings back: still straight over
        val towards = way(180.0, 120, 30) + listOf(p(-22.0, 6.0), p(-15.0, 10.0))
        val away = listOf(p(15.0, 10.0), p(22.0, 6.0)) + way(0.0, 30, 120)
        assertEquals(180.0, NaviRoundabout.exitAngle(towards, emptyList(), away, false)!!.toDouble(), 12.0)
        assertEquals(180.0, NaviRoundabout.drawn(NaviRoundabout.exitAngle(towards, emptyList(), away, false)!!).toDouble(), 12.0)
    }

    @Test fun routeEndingInsideAndNothingKnown() {
        // nothing leads away: the last of the way inside (leaving it to the east)
        assertEquals(90.0, NaviRoundabout.exitAngle(fromSouth, round(90.0) + way(90.0, 15, 100), emptyList(), false)!!.toDouble(), 5.0)
        assertNull(NaviRoundabout.exitAngle(emptyList(), emptyList(), way(0.0, 15, 120), false))
    }
}
