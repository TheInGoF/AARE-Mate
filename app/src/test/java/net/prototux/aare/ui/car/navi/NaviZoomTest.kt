package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F5: close in town and before a turn, further out on a fast, long stretch; glides. */
class NaviZoomTest {
    @Test fun town() = assertEquals(NaviZoom.NEAR, NaviZoom.target(45.0, 800.0), 1e-9)
    @Test fun motorwayLongStretch() = assertEquals(NaviZoom.FAR, NaviZoom.target(130.0, 12_000.0), 1e-9)
    @Test fun turnAheadOnTheMotorway() = assertEquals(NaviZoom.NEAR, NaviZoom.target(120.0, 300.0), 1e-9)
    @Test fun unknownSpeed() = assertEquals(NaviZoom.NEAR, NaviZoom.target(null, null), 1e-9)
    @Test fun glidesWithoutJump() {
        val z = NaviZoom.glide(16.5, 14.5, 33)
        assertTrue(z < 16.5 && z > 16.4)
        assertEquals(14.5, NaviZoom.glide(16.5, 14.5, 5_000), 1e-9)
    }
}
