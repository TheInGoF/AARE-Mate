package net.prototux.aare.audio

import org.junit.Assert.assertTrue
import org.junit.Test

/** System I-055: the music's ramp around a prompt has no step between blocks, also when blocks come in a burst. */
class MediaFadeTest {
    private fun block(v: Short = 10_000, frames: Int = 960) = ByteArray(frames * 4).also { b ->
        for (i in 0 until frames * 2) { b[2 * i] = v.toByte(); b[2 * i + 1] = (v.toInt() shr 8).toByte() }
    }
    private fun first(b: ByteArray) = ((b[0].toInt() and 0xff) or (b[1].toInt() shl 8)).toShort().toInt()
    private fun last(b: ByteArray) = ((b[b.size - 2].toInt() and 0xff) or (b[b.size - 1].toInt() shl 8)).toShort().toInt()

    @Test fun noStepBetweenBlocksOfABurst() {
        MediaFade.duck()                       // the clock reads 0 in a unit test: the ramp starts at 0
        var prevEnd: Int? = null
        // blocks handled in bursts of four at the same moment, as the capture hands them over
        for (now in listOf(100L, 100, 100, 100, 180, 180, 180, 180, 260, 260, 260, 260, 340, 340, 340, 340)) {
            val b = block()
            MediaFade.apply(b, 0, b.size, now)
            prevEnd?.let { assertTrue("step at a block edge: $it -> ${first(b)}", kotlin.math.abs(first(b) - it) <= 60) }
            prevEnd = last(b)
        }
        assertTrue(prevEnd!! < 10_000)         // it went down
    }
}
