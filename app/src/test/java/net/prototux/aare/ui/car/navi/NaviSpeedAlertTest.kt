package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** O194/O198: from how far over; once per overspeed; a new overspeed only after coming back under the limit. */
class NaviSpeedAlertTest {
    @Test fun onlyAboveTheThreshold() {
        val a = NaviSpeedAlert(overKmh = 10, times = 1)
        assertFalse(a.update(59.0, 50))
        assertFalse(a.update(60.0, 50))
        assertTrue(a.update(61.0, 50))
    }

    @Test fun oncePerOverspeedHoweverLong() {
        val a = NaviSpeedAlert(5, 3)
        // 60 instead of 50 for two minutes, once a second: one warning
        assertEquals(1, (0 until 120).count { a.update(60.0, 50) })
    }

    @Test fun newOverspeedOnlyAfterComingBackUnder() {
        val a = NaviSpeedAlert(5, 1)
        assertTrue(a.update(60.0, 50))
        // hovering at the edge (within the slack) is the same overspeed
        assertFalse(a.update(54.0, 50)); assertFalse(a.update(60.0, 50))
        // back under (limit + 5 - 2 = 53), then over again: a new one
        assertFalse(a.update(52.0, 50)); assertTrue(a.update(60.0, 50))
    }

    @Test fun noLimitNoTone() {
        val a = NaviSpeedAlert(5, 3)
        assertFalse(a.update(200.0, null)); assertFalse(a.update(null, 50))
    }
}
