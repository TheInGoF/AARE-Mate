package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 21.11.2026 ("vor berechnen und zum richtigen Zeitpunkt ausgeben"): an announcement said early by the chain's delay. */
class AnnouncementTimingTest {
    private val triggers = listOf(2000.0, 500.0, 50.0)   // as Valhalla gives them: far, near, at the turn

    @Test fun standingSaysItAtItsDistance() {
        assertNull(NaviKeep.dueIndex(2100.0, 0.0, triggers))
        assertEquals(0, NaviKeep.dueIndex(2000.0, 0.0, triggers))
        assertEquals(1, NaviKeep.dueIndex(480.0, 0.0, triggers))
        assertEquals(2, NaviKeep.dueIndex(40.0, null, triggers))
    }

    @Test fun movingSaysItEarlyByTheDelay() {
        // 100 km/h = 27.8 m/s: 1.3 s are 36 m
        val ms = 100 / 3.6
        assertEquals(1, NaviKeep.dueIndex(535.0, ms, triggers))
        assertNull(NaviKeep.dueIndex(540.0, ms, triggers).takeIf { it == 1 })
        assertEquals(2, NaviKeep.dueIndex(85.0, ms, triggers))
        // in town, 50 km/h: 18 m early
        assertEquals(2, NaviKeep.dueIndex(67.0, 50 / 3.6, triggers))
        assertEquals(1, NaviKeep.dueIndex(69.0, 50 / 3.6, triggers))
    }

    @Test fun aWildSpeedIsCapped() {
        // a broken reading of 1000 m/s counts as 70 m/s at most: 91 m early, not 1300 m
        assertEquals(1, NaviKeep.dueIndex(590.0, 1000.0, triggers))
    }
}
