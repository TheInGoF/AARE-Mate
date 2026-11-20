package net.prototux.aare.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 19.11.2026: the phone app's answers to a second call, found by their words - German and English. */
class WaitingCallTest {
    private fun w(vararg titles: String) = WaitingCall("k", "mate-test Anrufer", titles.map { WaitingCall.Action(it, null) })

    @Test fun germanPhoneApp() {
        val c = w("Ablehnen", "Beenden und annehmen", "Halten und annehmen")
        assertEquals("Halten und annehmen", c.find(WaitingCall.HOLD)?.title)
        assertEquals("Beenden und annehmen", c.find(WaitingCall.END_ANSWER)?.title)
        assertEquals("Ablehnen", c.find(WaitingCall.DECLINE)?.title)
    }

    @Test fun englishAndOnlyTwo() {
        val c = w("Decline", "Answer")
        assertNull(c.find(WaitingCall.HOLD))
        assertNull(c.find(WaitingCall.END_ANSWER))
        assertEquals("Answer", c.find(WaitingCall.ANSWER, WaitingCall.END_ANSWER)?.title)
        assertEquals("Decline", c.find(WaitingCall.DECLINE)?.title)
        assertEquals("Hang up & answer", w("Hang up & answer", "Hold & answer").find(WaitingCall.END_ANSWER)?.title)
    }
}
