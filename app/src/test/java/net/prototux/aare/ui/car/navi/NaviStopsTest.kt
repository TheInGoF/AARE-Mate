package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.maplibre.spatialk.geojson.Position

/** O206: what is left to the next stop on the way. A straight 10 km, a stop at 4 km; 2 km at 60 km/h, then 8 km at 120. */
class NaviStopsTest {
    private fun p(km: Double) = Position(longitude = km * 1000 / 111_320.0, latitude = 0.0)
    private val points = (0..100).map { p(it / 10.0) }
    private val steps = listOf(2000.0 to 120.0, 8000.0 to 240.0)
    private val marks = NaviStops.marks(points, steps, listOf(p(4.0)))

    @Test fun stopPlacedWithItsTime() {
        assertEquals(4000.0, marks.single().metres, 1.0)
        assertEquals(120.0 + 60.0, marks.single().seconds, 0.5)   // 2 km in 120 s, 2 km more at 120 km/h = 60 s
    }

    @Test fun leftFromHalfWay() {
        val a = NaviStops.next(marks, 1000.0, 60.0)!!
        assertEquals(3000.0, a.metresLeft, 1.0)
        assertEquals(120.0, a.secondsLeft, 0.5)
    }

    @Test fun noneAfterTheStop() {
        assertNull(NaviStops.next(marks, 4100.0, 190.0))
    }
}
