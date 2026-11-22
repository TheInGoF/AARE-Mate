package net.prototux.aare.ui.car.navi

import net.prototux.aare.ui.car.navi.NaviSpeedSource.From
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** System L-088: the GPS's speed with a good fix, the car's in a tunnel. */
class NaviSpeedSourceTest {
    @Test fun gpsWithEnoughSatellites() =
        assertEquals(27.0 to From.GPS, NaviSpeedSource.pick(27.0, 500, 9, 1_000, 30f, 28.0))

    @Test fun carInATunnel() {
        // too few satellites
        assertEquals(28.0 to From.CAR, NaviSpeedSource.pick(27.0, 500, 2, 1_000, 5f, 28.0))
        // no fix for a while
        assertEquals(28.0 to From.CAR, NaviSpeedSource.pick(27.0, 8_000, 9, 1_000, 5f, 28.0))
    }

    @Test fun accuracyWhenTheCarCountsNoSatellites() {
        assertEquals(27.0 to From.GPS, NaviSpeedSource.pick(27.0, 500, null, Long.MAX_VALUE, 8f, 28.0))
        assertEquals(28.0 to From.CAR, NaviSpeedSource.pick(27.0, 500, null, Long.MAX_VALUE, 80f, 28.0))
    }

    @Test fun nothingKnown() = assertNull(NaviSpeedSource.pick(null, Long.MAX_VALUE, null, Long.MAX_VALUE, null, null))
}
