package net.prototux.aare.dash

import android.os.SystemClock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * System I-132 (log 26.11.2026, 16:23:53): right after the tablet started, Mate counted "never asked" as "asked at boot"
 * and dropped the stick's pairing when it sent nothing in 20 s. Only the driver's "Stick suchen" opens the window.
 */
@RunWith(RobolectricTestRunner::class)
class StickPairWindowTest {
    @org.junit.Before
    fun neverAsked() {
        StickLink::class.java.getDeclaredField("sPairAskedAt").apply { isAccessible = true }.setLong(null, Long.MIN_VALUE)
    }

    @Test
    fun neverAskedRightAfterTheStart() {
        // the tablet up for a minute only
        assertTrue(SystemClock.elapsedRealtime() < 5 * 60_000L)
        assertFalse(StickLink.pairAllowed())
    }

    @Test
    fun askedOpensFiveMinutes() {
        StickLink.pairNow()
        assertTrue(StickLink.pairAllowed())
        ShadowSystemClock.advanceBy(Duration.ofMinutes(6))
        assertFalse(StickLink.pairAllowed())
    }
}
