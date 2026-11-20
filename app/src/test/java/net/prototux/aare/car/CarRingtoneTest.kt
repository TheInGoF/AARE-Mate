package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 18.11.2026: the rings made for the car - a round each with sound and a pause, never over full scale. */
class CarRingtoneTest {
    @Test fun everyToneRings() {
        for (t in listOf(CarRingtone.Tone.CLASSIC, CarRingtone.Tone.MARIMBA, CarRingtone.Tone.BELL, CarRingtone.Tone.SOFT)) {
            val pcm = CarRingtone.render(t)
            assertTrue("$t has a round of 3 s at least", pcm.size >= 3 * 44_100)
            val peak = pcm.maxOf { kotlin.math.abs(it.toInt()) }
            assertTrue("$t sounds", peak > 10_000)
            assertTrue("$t stays under full scale", peak <= (0.8 * Short.MAX_VALUE).toInt() + 1)
            // the round ends in quiet, so the loop has its pause
            val tail = pcm.takeLast(44_100 / 2).maxOf { kotlin.math.abs(it.toInt()) }
            assertTrue("$t ends quiet", tail < 300)
        }
    }

    @Test fun choices() {
        assertEquals(CarRingtone.Tone.OFF, CarRingtone.tone("off"))
        assertEquals(CarRingtone.Tone.MARIMBA, CarRingtone.tone("unknown"))
        assertEquals(0, CarRingtone.render(CarRingtone.Tone.TABLET).size)
    }
}
