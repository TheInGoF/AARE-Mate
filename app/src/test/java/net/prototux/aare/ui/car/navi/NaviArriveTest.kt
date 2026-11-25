package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 18.11.2026: standing near the destination is the arrival; Navi I-064: but not a stop 100 m before it out of P. */
class NaviArriveTest {
    @Test fun standingNearTheEnd() {
        assertEquals(NaviKeep.ARRIVE_STAND_S, NaviKeep.standNeededS(0.0, 10.0, 10.0, parked = false))
        assertEquals("on the car park beside it, in P", 2, NaviKeep.standNeededS(1.5, 180.0, 90.0, parked = true))
    }
    @Test fun notWhileDrivingNorFarAway() {
        assertNull("rolling past", NaviKeep.standNeededS(12.0, 10.0, 10.0, parked = false))
        assertNull("a red light or a closed road 106 m before (24.11.2026)", NaviKeep.standNeededS(0.0, 106.0, 106.0, parked = false))
        assertNull("a red light two streets before", NaviKeep.standNeededS(0.0, 250.0, 200.0, parked = true))
        assertNull("close as the crow flies, a long way round still", NaviKeep.standNeededS(0.0, 1500.0, 80.0, parked = true))
        assertNull("no speed known", NaviKeep.standNeededS(null, 10.0, 10.0, parked = true))
        assertNull("no place known", NaviKeep.standNeededS(0.0, 10.0, null, parked = true))
    }
}
