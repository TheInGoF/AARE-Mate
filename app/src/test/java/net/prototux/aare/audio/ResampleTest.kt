package net.prototux.aare.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** "knackt und knarzt" (17.11.2026): speech brought to the car's rate keeps its tones and loses those it cannot carry. */
class ResampleTest {
    private fun tone(f: Double, rate: Int, n: Int) = FloatArray(n) { (0.5 * sin(2 * PI * f * it / rate)).toFloat() }
    private fun rms(x: FloatArray, from: Int = 200, to: Int = x.size - 200) = sqrt((from until to).sumOf { (x[it] * x[it]).toDouble() } / (to - from))

    @Test fun keepsASpeechTone() {
        val y = Resample.to(tone(1000.0, 22050, 22050), 22050, 16000)
        assertEquals(16000, y.size)
        assertEquals(0.5 / sqrt(2.0), rms(y), 0.02)
    }

    @Test fun dropsWhatTheCarCannotCarry() {
        // 10 kHz is above 16 kHz's half rate: it must not come back as a 6 kHz rasp
        val y = Resample.to(tone(10_000.0, 22050, 22050), 22050, 16000)
        assertTrue("rms ${rms(y)}", rms(y) < 0.02)
    }

    @Test fun neverFullScaleAndSoftEdges() {
        val x = FloatArray(1000) { if (it % 2 == 0) 1.2f else -1.2f }
        val y = Resample.tidy(x, 16000)
        assertTrue(y.all { abs(it) <= 0.9f })
        assertEquals(0f, y.first(), 1e-6f); assertEquals(0f, y.last(), 1e-6f)
    }
}
