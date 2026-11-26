package net.prototux.aare.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.PI
import kotlin.math.sin

/** System L-104: music with little bass gets lifted, gently and at most 10 dB; music with bass stays as it is. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36])
class BassLevelTest {
    private val rate = 48000

    /** [seconds] of stereo 16-bit PCM in 20 ms blocks through [b]; the power below 150 Hz and in all of the last second. */
    private fun run(b: BassLevel, seconds: Int, bassAmp: Double, highAmp: Double): Pair<Double, Double> {
        val frames = rate / 50
        var t = 0L
        var low = 0.0; var all = 0.0
        val lp = BassLevel.lowPass(150.0, rate); val st = DoubleArray(4)
        repeat(seconds * 50) { block ->
            val buf = ByteArray(frames * 4)
            for (f in 0 until frames) {
                val v = (bassAmp * sin(2 * PI * 60 * t / rate) + highAmp * sin(2 * PI * 1000 * t / rate)).toInt().toShort()
                for (ch in 0..1) { buf[4 * f + 2 * ch] = v.toByte(); buf[4 * f + 2 * ch + 1] = (v.toInt() shr 8).toByte() }
                t++
            }
            b.process(buf, 0, buf.size)
            if (block >= (seconds - 1) * 50) for (f in 0 until frames) {
                val x = ((buf[4 * f].toInt() and 0xff) or (buf[4 * f + 1].toInt() shl 8)).toShort().toDouble()
                val y = lp[0] * x + lp[1] * st[0] + lp[2] * st[1] - lp[3] * st[2] - lp[4] * st[3]
                st[1] = st[0]; st[0] = x; st[3] = st[2]; st[2] = y
                low += y * y; all += x * x
            }
        }
        return low to all
    }

    @Test fun musicWithoutBassIsLiftedGentlyToAtMostTenDb() {
        val b = BassLevel(rate, 2)
        run(b, 2, bassAmp = 200.0, highAmp = 8000.0)
        val early = b.liftDb
        assertTrue("gentle: after 2 s at most 4 dB, was $early", early <= 4.01)
        val (low, all) = run(b, 20, bassAmp = 200.0, highAmp = 8000.0)
        assertEquals(10.0, b.liftDb, 0.01)
        // the bass carries more of the power than it did (200 of 8000 = -32 dB)
        assertTrue(10 * kotlin.math.log10(low / all) > -32 + 4)
    }

    @Test fun musicWithBassStaysAsItIs() {
        val b = BassLevel(rate, 2)
        run(b, 20, bassAmp = 8000.0, highAmp = 4000.0)
        assertEquals(0.0, b.liftDb, 0.01)
        assertTrue(b.shareDb > -6)
    }

    @Test fun aLineFromNoneToTheWhole() {
        // bass of its own: none; thin: all of it; in between on a straight line (+6 dB at 60 % of the way)
        assertEquals(0.0, BassLevel.liftFor(-1.0), 0.001)
        assertEquals(0.0, BassLevel.liftFor(BassLevel.ZERO_AT_DB), 0.001)
        assertEquals(6.0, BassLevel.liftFor(BassLevel.ZERO_AT_DB + 0.6 * (BassLevel.FULL_AT_DB - BassLevel.ZERO_AT_DB)), 0.001)
        assertEquals(10.0, BassLevel.liftFor(BassLevel.FULL_AT_DB), 0.001)
        assertEquals(10.0, BassLevel.liftFor(-35.0), 0.001)
    }

    @Test fun whatSongsMeasureIsHeard() {
        // 25.11.2026: the morning's songs measured 3 to 8 dB below the whole - they must get a lift one hears
        assertTrue(BassLevel.liftFor(-5.0) >= 3.5)
        assertTrue(BassLevel.liftFor(-7.0) >= 6.0)
        assertTrue(BassLevel.liftFor(-3.0) <= 1.5)
    }

    @Test fun aSongInBetweenGetsPartOfIt() {
        // 60 Hz at half the 1 kHz tone: the bass about 7 dB below the whole, as most songs measure - neither nothing nor all
        val b = BassLevel(rate, 2)
        run(b, 30, bassAmp = 4000.0, highAmp = 8000.0)
        assertTrue("between: ${b.liftDb} dB at a share of ${b.shareDb} dB", b.liftDb > 2.0 && b.liftDb < 9.0)
        assertEquals(BassLevel.liftFor(b.shareDb), b.liftDb, 0.5)
    }

    @Test fun aSongMeasuredBeforeGetsItsLiftFromTheStart() {
        // L-105: a thin song known from before - its lift from the start, whatever its first seconds sound like
        CarEq.song = "k1" to -12.0
        try {
            val b = BassLevel(rate, 2)
            run(b, 8, bassAmp = 8000.0, highAmp = 4000.0)   // a bass-rich opening
            assertEquals(10.0, b.liftDb, 0.01)
        } finally { CarEq.song = null }
    }

    @Test fun aSongHeardLongEnoughIsToldItsShare() {
        val told = mutableListOf<Pair<String, Double>>()
        CarEq.onSongShare = { k, share -> told += k to share }
        CarEq.song = "k2" to Double.NaN
        try {
            val b = BassLevel(rate, 2)
            run(b, 30, bassAmp = 4000.0, highAmp = 8000.0)
            CarEq.song = "k3" to Double.NaN
            run(b, 1, bassAmp = 4000.0, highAmp = 8000.0)
            assertTrue("not told under a minute: $told", told.isEmpty())
            run(b, 62, bassAmp = 4000.0, highAmp = 8000.0)
            CarEq.song = null
            run(b, 1, bassAmp = 4000.0, highAmp = 8000.0)
            assertEquals(1, told.size)
            assertEquals("k3", told[0].first)
            assertEquals(-7.0, told[0].second, 0.7)
        } finally { CarEq.song = null; CarEq.onSongShare = null }
    }

    @Test fun quietPassagesChangeNothing() {
        val b = BassLevel(rate, 2)
        run(b, 10, bassAmp = 10.0, highAmp = 100.0)
        assertEquals(0.0, b.liftDb, 0.01)
    }

    @Test fun theLiftReachesTheBassNotesNotOnlyTheSub() {
        // 25.11.2026: songs without deep bass were not lifted - the shelf must reach the bass notes around 100-160 Hz
        val k = BassLevel.lowShelf(BassLevel.SHELF_HZ.toDouble(), 10.0, rate)
        fun gainDb(f: Double): Double {
            val w = 2 * PI * f / rate
            val c1 = kotlin.math.cos(w); val c2 = kotlin.math.cos(2 * w); val s1 = sin(w); val s2 = sin(2 * w)
            val nr = k[0] + k[1] * c1 + k[2] * c2; val ni = -(k[1] * s1 + k[2] * s2)
            val dr = 1 + k[3] * c1 + k[4] * c2; val di = -(k[3] * s1 + k[4] * s2)
            return 10 * kotlin.math.log10((nr * nr + ni * ni) / (dr * dr + di * di))
        }
        assertTrue("80 Hz: ${gainDb(80.0)}", gainDb(80.0) > 9.0)
        assertTrue("125 Hz: ${gainDb(125.0)}", gainDb(125.0) > 8.0)
        assertTrue("160 Hz: ${gainDb(160.0)}", gainDb(160.0) > 6.0)
        assertTrue("500 Hz stays: ${gainDb(500.0)}", gainDb(500.0) < 1.0)
    }

    @Test fun shelfLiftsTheBassByItsGain() {
        // the low shelf's gain at 30 Hz is the lift, at 5 kHz about none
        val k = BassLevel.lowShelf(120.0, 6.0, rate)
        fun gainDb(f: Double): Double {
            val w = 2 * PI * f / rate
            val c1 = kotlin.math.cos(w); val c2 = kotlin.math.cos(2 * w); val s1 = sin(w); val s2 = sin(2 * w)
            val nr = k[0] + k[1] * c1 + k[2] * c2; val ni = -(k[1] * s1 + k[2] * s2)
            val dr = 1 + k[3] * c1 + k[4] * c2; val di = -(k[3] * s1 + k[4] * s2)
            return 10 * kotlin.math.log10((nr * nr + ni * ni) / (dr * dr + di * di))
        }
        assertEquals(6.0, gainDb(30.0), 0.5)
        assertEquals(0.0, gainDb(5000.0), 0.3)
    }
}
