package net.prototux.aare.video

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * System I-153 (27.11.2026: "Ich drücke auf Dashboard 3-4 Mal und es passiert nichts … man sieht, dass er betätigt
 * wurde"): a finger that moves a little while it taps stays a tap; a swipe goes on as it came.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36])
class TapSteadyTest {
    private fun ev(action: Int, x: Float, y: Float) = MotionEvent.obtain(0, 0, action, x, y, 0)

    @Test fun aShakyTapStaysATap() {
        val s = TapSteady(23f)
        assertNotNull(s.filter(ev(MotionEvent.ACTION_DOWN, 100f, 100f)))
        assertNull("small moves held back", s.filter(ev(MotionEvent.ACTION_MOVE, 108f, 103f)))
        assertNull(s.filter(ev(MotionEvent.ACTION_MOVE, 112f, 95f)))
        val up = s.filter(ev(MotionEvent.ACTION_UP, 114f, 96f))!!
        assertEquals("ends where it began", 100f, up.x, 0.01f)
        assertEquals(100f, up.y, 0.01f)
        assertEquals(1, s.steadied)
    }

    @Test fun aSwipeGoesOn() {
        val s = TapSteady(23f)
        s.filter(ev(MotionEvent.ACTION_DOWN, 100f, 100f))
        assertNull(s.filter(ev(MotionEvent.ACTION_MOVE, 110f, 100f)))
        assertNotNull("out of the circle: the swipe", s.filter(ev(MotionEvent.ACTION_MOVE, 140f, 100f)))
        assertNotNull("and on", s.filter(ev(MotionEvent.ACTION_MOVE, 120f, 100f)))
        assertEquals(180f, s.filter(ev(MotionEvent.ACTION_UP, 180f, 100f))!!.x, 0.01f)
        assertEquals(0, s.steadied)
    }
}
