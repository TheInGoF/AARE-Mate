package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.spatialk.geojson.Position

/** F2 step 3: points inside a closed stretch, away from its ends (the crossing roads stay). */
class NaviTrafficTest {
    private fun p(m: Double) = Position(longitude = m / 111_320.0, latitude = 0.0)

    @Test fun shortStretchItsMiddle() {
        val r = NaviTraffic.inside(listOf(p(0.0), p(60.0)))
        assertEquals(1, r.size); assertEquals(30.0, r[0].longitude * 111_320.0, 0.5)
    }

    @Test fun longStretchAwayFromTheEnds() {
        val r = NaviTraffic.inside(listOf(p(0.0), p(500.0), p(1000.0)))
        assertEquals(listOf(40.0, 340.0, 640.0, 940.0), r.map { Math.round(it.longitude * 111_320.0).toDouble() })
        assertTrue(r.all { it.longitude * 111_320.0 in 40.0..960.0 })
    }
}

/** An incident counts along the route only: a closed road crossing it (a tunnel under it) does not. */
class NaviTrafficWayTest {
    private fun p(x: Double, y: Double) = Position(longitude = x / 111_320.0, latitude = y / 111_320.0)
    private val northward = (0..10).map { p(0.0, it * 100.0) }

    @Test fun alongCounts() { assertTrue(NaviTraffic.sameWay(p(0.0, 300.0), p(5.0, 400.0), northward, 3)) }
    @Test fun acrossDoesNot() { assertTrue(!NaviTraffic.sameWay(p(-50.0, 300.0), p(50.0, 300.0), northward, 3)) }
    @Test fun againstOnlyForClosures() {
        assertTrue(!NaviTraffic.sameWay(p(0.0, 400.0), p(0.0, 300.0), northward, 3))
        assertTrue(NaviTraffic.sameWay(p(0.0, 400.0), p(0.0, 300.0), northward, 3, either = true))
    }
}
