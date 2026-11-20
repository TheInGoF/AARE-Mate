package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 18.11.2026: standing near the destination is the arrival - not only the route's last metres. */
class NaviArriveTest {
    @Test fun standingNearTheEnd() {
        assertTrue(NaviKeep.arrivedByStanding(0.0, 10.0, 10.0))
        assertTrue("on the car park beside it", NaviKeep.arrivedByStanding(1.5, 180.0, 90.0))
    }
    @Test fun notWhileDrivingNorFarAway() {
        assertFalse("rolling past", NaviKeep.arrivedByStanding(12.0, 10.0, 10.0))
        assertFalse("a red light two streets before", NaviKeep.arrivedByStanding(0.0, 250.0, 200.0))
        assertFalse("close as the crow flies, a long way round still", NaviKeep.arrivedByStanding(0.0, 1500.0, 80.0))
        assertFalse("no speed known", NaviKeep.arrivedByStanding(null, 10.0, 10.0))
        assertFalse("no place known", NaviKeep.arrivedByStanding(0.0, 10.0, null))
    }
}
