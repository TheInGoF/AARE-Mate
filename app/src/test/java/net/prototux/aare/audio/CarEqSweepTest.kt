package net.prototux.aare.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** System L-005: the sweep measurement finds what the car does to one band, and nothing where it does nothing. */
class CarEqSweepTest {
    private val rate = 48000

    /** [x] through one band of [CarEq]'s own filters (+[dB] at [freq]): a car that is loud there. */
    private fun through(x: FloatArray, freq: Int, dB: Float): FloatArray {
        val k = CarEq.coefficients(freq, dB, rate)!!
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        return FloatArray(x.size) { i ->
            val v = x[i].toDouble()
            val y = k[0] * v + k[1] * x1 + k[2] * x2 - k[3] * y1 - k[4] * y2
            x2 = x1; x1 = v; y2 = y1; y1 = y
            y.toFloat()
        }
    }

    @Test fun findsALoudBand() {
        val played = CarEqAnalysis.sweeps(rate, 3.0, 0.5, 2) + FloatArray(rate / 2)
        val heard = through(played, 1000, 6f)
        val quiet = FloatArray(rate) { ((it * 7919 % 200) - 100) / 1e6f }       // a faint background
        val r = CarEqAnalysis.correction(CarEqAnalysis.bandLevels(heard, rate), CarEqAnalysis.bandLevels(quiet, rate),
            reference = CarEqAnalysis.bandLevels(played, rate))
        val g = r.gains
        assertNotNull(r.error, g)
        val at1k = CarEq.BANDS.indexOf(1000)
        assertTrue("1 kHz: ${g!![at1k]}", g[at1k] <= -3f)
        // two octaves away nothing to correct
        assertTrue("125 Hz: ${g[CarEq.BANDS.indexOf(125)]}", Math.abs(g[CarEq.BANDS.indexOf(125)]) <= 1f)
        assertTrue("8 kHz: ${g[CarEq.BANDS.indexOf(8000)]}", Math.abs(g[CarEq.BANDS.indexOf(8000)]) <= 1f)
    }

    @Test fun calibrationReadAtTheBands() {
        val cal = CarEqAnalysis.calibration("\"Sens Factor =-1.2dB\"\n20 -3.0\n1000 0.0\n20000 2.0\n")!!
        assertEquals(0.0, cal[CarEq.BANDS.indexOf(1000)], 1e-9)
        assertTrue(cal[0] < -1.5)
        assertTrue(cal[CarEq.BANDS.size - 1] > 1.5)
    }
}
