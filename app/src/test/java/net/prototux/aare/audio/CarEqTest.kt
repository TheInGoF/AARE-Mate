package net.prototux.aare.audio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

class CarEqTest {
    private val rate = 48000

    @After fun off() = CarEq.set(false, FloatArray(CarEq.BANDS.size))

    /** Level change in dB of a stereo sine at [freq] through the filter (filter settled, first half skipped). */
    private fun change(freq: Double, amp: Double = 8000.0): Double {
        val frames = rate // one second, in 20 ms chunks like CarAudioOut
        val chunk = rate / 50 * 2 * 2
        val p = CarEqProcessor(rate, 2)
        var inSq = 0.0; var outSq = 0.0
        var f = 0
        while (f < frames) {
            val buf = ByteArray(chunk)
            val start = f
            for (k in 0 until chunk / 4) {
                val v = (amp * sin(2 * PI * freq * (start + k) / rate)).toInt()
                for (c in 0..1) { buf[k * 4 + c * 2] = v.toByte(); buf[k * 4 + c * 2 + 1] = (v shr 8).toByte() }
            }
            p.process(buf, 0, chunk)
            for (k in 0 until chunk / 4) {
                if (start + k < frames / 2) continue
                val x = amp * sin(2 * PI * freq * (start + k) / rate)
                for (c in 0..1) {
                    val y = ((buf[k * 4 + c * 2].toInt() and 0xff) or (buf[k * 4 + c * 2 + 1].toInt() shl 8)).toShort().toDouble()
                    inSq += x * x; outSq += y * y
                }
            }
            f += chunk / 4
        }
        return 10 * log10(outSq / inSq)
    }

    @Test fun offLeavesTheSoundAlone() {
        CarEq.set(false, floatArrayOf(6f, 6f, 6f, 6f, 6f, 6f, 6f, 6f, 6f, 6f))
        assertEquals(0.0, change(1000.0), 0.01)
    }

    @Test fun aBandRaisesItsFrequencyAndTheRestGoesDownByThePreamp() {
        CarEq.set(true, floatArrayOf(0f, 0f, 0f, 0f, 0f, 6f, 0f, 0f, 0f, 0f))
        assertEquals(0.0, change(1000.0), 0.3)   // +6 dB band, -6 dB preamp
        assertEquals(-6.0, change(62.0), 0.3)    // far from the band: only the preamp
        assertEquals(CarEq.responseDb(CarEq.gains, 2000.0), change(2000.0), 0.3) // the phone app's curve tells the truth
    }

    @Test fun aLoweredBandCutsWithoutPreamp() {
        CarEq.set(true, floatArrayOf(0f, 0f, -12f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
        assertEquals(-12.0, change(125.0), 0.5)
        assertEquals(0.0, change(4000.0), 0.3)
    }

    @Test fun loudSoundDoesNotWrapAround() {
        CarEq.set(true, floatArrayOf(-12f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
        // nearly full scale: the cut elsewhere is 0 dB, so nothing may flip sign by overflow
        assertEquals(0.0, change(4000.0, 32000.0), 0.3)
    }

    @Test fun presetsStayInRange() {
        for ((_, g) in CarEq.PRESETS) for (v in g) assert(v >= CarEq.MIN_DB && v <= CarEq.MAX_DB)
    }
}
