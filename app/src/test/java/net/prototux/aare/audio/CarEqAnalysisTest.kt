package net.prototux.aare.audio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarEqAnalysisTest {
    private val rate = 48000

    @After fun off() = CarEq.set(false, FloatArray(CarEq.BANDS.size))

    private fun pink(seconds: Int, through: CarEqProcessor? = null): FloatArray {
        val p = PinkNoise(7)
        val n = rate * seconds
        val pcm = ByteArray(n * 2)
        for (i in 0 until n) { val v = (p.next() * 32767).toInt().coerceIn(-32768, 32767); pcm[2 * i] = v.toByte(); pcm[2 * i + 1] = (v shr 8).toByte() }
        through?.process(pcm, 0, pcm.size)
        return FloatArray(n) { ((pcm[2 * it].toInt() and 0xff) or (pcm[2 * it + 1].toInt() shl 8)).toShort() / 32768f }
    }

    private fun quiet() = DoubleArray(CarEq.BANDS.size) { -200.0 }

    @Test fun pinkNoiseIsHeardFlat() {
        val l = CarEqAnalysis.bandLevels(pink(8), rate)
        val mid = (1..8).map { l[it] }.average()
        for (b in 1..8) assertEquals("band ${CarEq.BANDS[b]}", mid, l[b], 1.5)
        val r = CarEqAnalysis.correction(l, quiet())
        assertNull(r.error)
        r.gains!!.forEachIndexed { b, g -> assertTrue("band ${CarEq.BANDS[b]} $g", g in -1f..1f) }
    }

    @Test fun aLoudBandInTheCarIsTakenBack() {
        // the "car" raises 1 kHz by 6 dB: the correction lowers it by about three quarters of that
        CarEq.set(true, floatArrayOf(0f, 0f, 0f, 0f, 0f, 6f, 0f, 0f, 0f, 0f))
        val l = CarEqAnalysis.bandLevels(pink(8, CarEqProcessor(rate, 1)), rate)
        val g = CarEqAnalysis.correction(l, quiet()).gains!!
        assertTrue("1 kHz ${g[5]}", g[5] in -6f..-3f)
        for (b in listOf(1, 2, 3, 8)) assertTrue("band ${CarEq.BANDS[b]} ${g[b]}", g[b] in -1f..1f)
    }

    @Test fun theOuterBandsAreNeverRaised() {
        val l = DoubleArray(10) { 0.0 }.also { it[0] = -20.0; it[9] = -20.0 }
        val g = CarEqAnalysis.correction(l, quiet()).gains!!
        assertEquals(0f, g[0]); assertEquals(0f, g[9])
    }

    @Test fun tooQuietAgainstTheBackgroundGivesNoCorrection() {
        val l = DoubleArray(10) { 0.0 }
        val r = CarEqAnalysis.correction(l, DoubleArray(10) { -5.0 })
        assertEquals("quiet", r.error); assertNull(r.gains)
    }
}

class CarEqTargetTest {
    @Test fun aTargetShiftsTheCorrectionByThreeQuarters() {
        val flat = DoubleArray(10) { 0.0 }
        val quiet = DoubleArray(10) { -200.0 }
        val bass = CarEqAnalysis.TARGETS.first { it.first == "bass" }.second
        val g = CarEqAnalysis.correction(flat, quiet, bass).gains!!
        // 62 Hz wants +5: three quarters, rounded; the outer band stays capped at 0; the middle untouched
        assertEquals(4f, g[1]); assertEquals(0f, g[0]); assertEquals(0f, g[5])
        // the middle is measured against the flat bands only: a target in the middle shifts nothing else
        assertEquals(0f, CarEqAnalysis.correction(flat, quiet, CarEqAnalysis.TARGETS.first().second).gains!![3])
    }
}
