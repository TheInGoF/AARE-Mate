package net.prototux.aare.audio

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 18.11.2026, one level for every source: the measuring as the standard says, and sources turned down, never up. */
class LoudnessTest {
    @Before fun fresh() { Loudness.known.clear(); Loudness.on = true; Loudness.targetLufs = -18.0; Loudness.own = "mate" }

    /** [seconds] of a 997 Hz tone at [lufs] (a full-scale tone on both channels is 0 LUFS), in 20 ms chunks through [l]. */
    private fun run(l: LoudnessLeveller, lufs: Double, seconds: Double, channels: Int = 2, rate: Int = 48000): Double {
        val amp = 10.0.pow(lufs / 20) * 32767
        val chunkFrames = rate / 50
        val buf = ByteArray(chunkFrames * channels * 2)
        var frame = 0L
        var last = 0.0
        repeat((seconds * 50).toInt()) {
            for (f in 0 until chunkFrames) {
                val v = (amp * sin(2 * PI * 997 * (frame + f) / rate)).toInt()
                for (c in 0 until channels) { val o = 2 * (f * channels + c); buf[o] = v.toByte(); buf[o + 1] = (v shr 8).toByte() }
            }
            frame += chunkFrames
            l.process(buf, 0, buf.size)
            var sq = 0.0
            for (i in 0 until buf.size / 2) { val s = ((buf[2 * i].toInt() and 0xff) or (buf[2 * i + 1].toInt() shl 8)).toShort() / 32767.0; sq += s * s }
            last = 20 * log10(sqrt(sq / (buf.size / 2)) * sqrt(2.0))       // the tone's level in dB below full scale
        }
        return last
    }

    @Test fun weightingAsTheStandard() {
        val k = Loudness.kWeighting(48000)
        assertEquals(1.53512485958697, k[0][0], 1e-6); assertEquals(-2.69169618940638, k[0][1], 1e-6); assertEquals(1.19839281085285, k[0][2], 1e-6)
        assertEquals(-1.69065929318241, k[0][3], 1e-6); assertEquals(0.73248077421585, k[0][4], 1e-6)
        assertEquals(-1.99004745483398, k[1][3], 1e-6); assertEquals(0.99007225036621, k[1][4], 1e-6)
    }

    @Test fun aLoudSourceComesDownToTheTarget() {
        val l = LoudnessLeveller(48000, 2) { "com.audio.book" }
        val out = run(l, -12.0, 20.0)
        assertEquals("6 dB above the target: turned down by 6", -6.0, l.cutDb, 0.3)
        assertEquals(-18.0, out, 0.3)
        // and it is known when it plays again: at its level from the first moment
        val again = LoudnessLeveller(48000, 2) { "com.audio.book" }
        run(again, -12.0, 3.0)
        assertEquals(-6.0, again.cutDb, 0.3)
    }

    @Test fun aQuietSourceStays() {
        val l = LoudnessLeveller(48000, 2) { "com.quiet" }
        assertEquals(-25.0, run(l, -25.0, 15.0), 0.1)
        assertEquals(0.0, l.cutDb, 1e-9)
    }

    @Test fun matesOwnMusicAndTheSwitch() {
        val own = LoudnessLeveller(48000, 2) { "mate" }
        assertEquals("levelled by its own numbers already", -8.0, run(own, -8.0, 10.0), 0.1)
        Loudness.on = false
        val off = LoudnessLeveller(48000, 2) { "com.loud" }
        assertEquals(-8.0, run(off, -8.0, 10.0), 0.1)
    }

    @Test fun nothingJumpsAndNeverMoreThanTheMost() {
        val l = LoudnessLeveller(48000, 2) { "com.very.loud" }
        run(l, -1.0, 2.5)
        assert(abs(l.cutDb) <= 3.0 * 0.6) { "glides at 3 dB a second, was ${l.cutDb} after half a second of knowing" }
        run(l, -1.0, 20.0)
        assertEquals(-Loudness.MAX_CUT_DB, l.cutDb, 0.01)
    }

    @Test fun promptsOnOneChannelCountAsFromBothSides() {
        val l = LoudnessLeveller(48000, 1, "guidance")
        run(l, -12.0, 20.0, channels = 1)
        assertEquals(-6.0, l.cutDb, 0.3)
    }

    /** 18.11.2026 ("die Sprache knistert und knarzt"): the gain only scales - every sample is the one that came, times the gain. */
    @Test fun nothingButTheGain() {
        Loudness.known["com.loud"] = doubleArrayOf(0.25, 60.0)          // known at -6.7 LUFS: turned down from the first moment
        val l = LoudnessLeveller(48000, 1, "com.loud")
        val frames = 960
        val buf = ByteArray(frames * 2); val src = IntArray(frames)
        var before = 0.0
        repeat(300) { c ->
            for (f in 0 until frames) { val v = (20000 * sin(2 * PI * 440 * (c * frames + f) / 48000.0)).toInt(); src[f] = v; buf[2 * f] = v.toByte(); buf[2 * f + 1] = (v shr 8).toByte() }
            l.process(buf, 0, buf.size)
            val after = l.cutDb
            for (f in 0 until frames) {
                val o = ((buf[2 * f].toInt() and 0xff) or (buf[2 * f + 1].toInt() shl 8)).toShort().toInt()
                val g = 10.0.pow((before + (after - before) * f / frames) / 20)
                assert(abs(o - src[f] * g) <= 40) { "chunk $c frame $f: $o for ${src[f]} at ${"%.3f".format(g)}" }
            }
            before = after
        }
        assert(l.cutDb < -8.0) { "turned down by then: ${l.cutDb}" }
    }

    /** Mate's own speech and tones at the music's target: loud ones down, quiet ones up a little, silence left alone. */
    @Test fun matesOwnSoundAtTheTarget() {
        fun tone(amp: Double, seconds: Double, rate: Int = 22050) = FloatArray((seconds * rate).toInt()) { (amp * sin(2 * PI * 997 * it / rate)).toFloat() }
        val loud = tone(0.9, 2.0)
        assertEquals(20 * log10(0.9), Loudness.integrated(loud, 22050), 0.3)
        assertEquals(-18.0, Loudness.integrated(Loudness.own(loud, 22050), 22050), 0.3)
        Loudness.targetLufs = -15.0
        assertEquals(-15.0, Loudness.integrated(Loudness.own(tone(0.9, 0.18), 22050), 22050), 0.5)     // a short beep
        assertEquals("up by 6 dB at most", 6.0, 20 * log10(Loudness.ownGain(tone(0.01, 2.0), 22050).toDouble()), 0.01)
        // words with a pause between them: the pause does not make them louder
        val words = tone(0.5, 1.0) + FloatArray(22050) + tone(0.5, 1.0)
        assertEquals(Loudness.ownGain(tone(0.5, 2.0), 22050).toDouble(), Loudness.ownGain(words, 22050).toDouble(), 0.03)
        Loudness.on = false
        assertEquals(1f, Loudness.ownGain(loud, 22050))
    }

    @Test fun otherRateOtherTargetAndAPause() {
        Loudness.targetLufs = -15.0
        val l = LoudnessLeveller(44100, 2) { "com.podcast" }
        run(l, -10.0, 15.0, rate = 44100)
        assertEquals(-5.0, l.cutDb, 0.3)
        // a pause (nearly silent) teaches nothing
        run(l, -70.0, 10.0, rate = 44100)
        assertEquals(-5.0, l.cutDb, 0.3)
    }
}
