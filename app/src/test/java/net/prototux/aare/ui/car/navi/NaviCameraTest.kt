package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** N6: into the curve when the speed fits it, not at 100 km/h before a right turn. */
class NaviCameraTest {
    private fun kmh(v: Double) = v / 3.6
    @Test fun turnInTown() = assertTrue(NaviCamera.intoCurve(90.0, kmh(30.0)))
    @Test fun turnTooFast() = assertFalse(NaviCamera.intoCurve(90.0, kmh(100.0)))
    @Test fun motorwayBend() = assertTrue(NaviCamera.intoCurve(10.0, kmh(130.0)))
    @Test fun straight() = assertTrue(NaviCamera.intoCurve(1.0, kmh(200.0)))
    @Test fun standing() = assertTrue(NaviCamera.intoCurve(120.0, null))
    @Test fun noFlippingAtTheEdge() {
        // 32° at 100 km/h lies at the edge: it keeps what it did
        assertTrue(NaviCamera.intoCurve(32.0, kmh(100.0), was = true))
        assertFalse(NaviCamera.intoCurve(32.0, kmh(100.0), was = false))
    }
}
