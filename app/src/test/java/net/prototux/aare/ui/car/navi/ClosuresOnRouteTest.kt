package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.spatialk.geojson.Position

/** 19.11.2026: only closures on the route's own way are kept out, and never more points than Valhalla takes. */
class ClosuresOnRouteTest {
    // a motorway going north along lon 12.0, a point every 50 m over 5 km
    private val route = (0..100).map { Position(longitude = 12.0, latitude = 51.0 + it * 50 / 111_320.0) }
    private fun north(lon: Double, fromM: Double, toM: Double) = listOf(Position(longitude = lon, latitude = 51.0 + fromM / 111_320.0),
        Position(longitude = lon, latitude = 51.0 + toM / 111_320.0))
    private val m = 1 / (111_320.0 * Math.cos(Math.toRadians(51.0)))   // a metre east in degrees

    @Test fun ourCarriagewayCounts() {
        val parts = NaviTraffic.onRoute(north(12.0 + 3 * m, 1000.0, 2000.0), route)
        assertEquals(1, parts.size)
        assertTrue(parts[0].size * 25.0 >= 900)
    }

    @Test fun theOtherCarriagewayDoesNot() {
        // 20 m east, drawn southwards: the carriageway the other way
        assertTrue(NaviTraffic.onRoute(north(12.0 + 20 * m, 2000.0, 1000.0), route).isEmpty())
        // on the road itself the other way round (a closed street): counts
        assertEquals(1, NaviTraffic.onRoute(north(12.0 + 2 * m, 2000.0, 1000.0), route).size)
    }

    @Test fun aRampTouchingDoesNot() {
        // begins beside the motorway and leaves it to the east within 100 m
        val ramp = listOf(Position(longitude = 12.0 + 5 * m, latitude = 51.0 + 1500 / 111_320.0),
            Position(longitude = 12.0 + 15 * m, latitude = 51.0 + 1560 / 111_320.0), Position(longitude = 12.0 + 200 * m, latitude = 51.0 + 1700 / 111_320.0))
        assertTrue(NaviTraffic.onRoute(ramp, route).isEmpty())
    }

    @Test fun neverMoreThanValhallaTakes() {
        val lines = (0 until 40).map { i -> north(12.0 + i * 0.01, 0.0, 2000.0) }   // 40 closures of 2 km: some 1600 points before
        val pts = NaviTraffic.excludePoints(lines)
        assertTrue(pts.size <= NaviTraffic.MAX_POINTS)
        assertTrue(pts.size >= 40)                  // one at least for each
    }
}
