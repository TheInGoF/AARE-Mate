package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.maplibre.spatialk.geojson.Position

/**
 * The limit by the distance driven, not by the nearest point (16.11.2026, Tiergarten tunnel: "er zeigt 20 im tunnel an").
 * A route out north through a "tunnel" with two points 1 km apart (50), back south beside it with dense points (20):
 * half way through the tunnel the dense part is nearer, the distance driven still says 50.
 */
class NaviLimitsAlongTest {
    private fun p(northM: Double, eastM: Double) = Position(longitude = eastM / 111_320.0, latitude = northM / 111_320.0)
    private val points = listOf(p(0.0, 0.0), p(1000.0, 0.0)) + (0..10).map { p(1000.0 - it * 100.0, 150.0) }
    private val limits = NaviLimits(points, IntArray(points.size) { if (it < 1) 50 else if (it == 1) 50 else 20 })

    @Test fun tunnelKeepsItsLimit() {
        assertEquals(50, limits.along(500.0))
        assertEquals(50, limits.along(999.0))
    }

    @Test fun theDenseStretchAfterIt() {
        assertEquals(20, limits.along(1300.0))
        assertEquals(20, limits.along(9_999.0))
    }

    @Test fun unknownStaysUnknown() {
        assertNull(NaviLimits(points, IntArray(points.size)).along(500.0))
    }
}
