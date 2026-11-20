package net.prototux.aare.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 18.11.2026: "Man darf keine Pause einlegen beim Sprechen, sonst ... die Aufnahme zu beenden. Er muss weiter zuhören ...
 * Textstücke, die bereits gesprochen sind, schon erkennen, während er noch zuhört."
 */
class LongDictationCutterTest {
    private val rate = 16_000
    private fun chunk(ms: Int, level: Int) = ShortArray(rate * ms / 1000) { i -> (if (i % 2 == 0) level else -level).toShort() }
    private fun feed(c: LongDictation.Cutter, ms: Int, level: Int) { repeat(ms / 20) { chunk(20, level).let { c.feed(it, it.size) } } }

    @Test fun aPauseEndsAPieceNotTheListening() {
        val pieces = ArrayList<Int>()
        val c = LongDictation.Cutter(rate) { _, n -> pieces += n * 1000 / rate }
        feed(c, 500, 50)        // quiet at first
        feed(c, 2000, 3000)     // a sentence
        feed(c, 1000, 50)       // a pause
        assertEquals(1, pieces.size)
        feed(c, 5000, 50)       // a long pause: still listening, nothing more handed on
        assertEquals(1, pieces.size)
        feed(c, 1500, 3000)     // and on
        feed(c, 1000, 50)
        assertEquals(2, pieces.size)
        assertTrue("the first piece holds its sentence", pieces[0] in 2000..3600)
        assertTrue("the long quiet is not kept with the second", pieces[1] in 1500..3000)
    }

    @Test fun speechWithoutAPauseIsCutIntoShortPieces() {
        val pieces = ArrayList<Int>()
        val c = LongDictation.Cutter(rate) { _, n -> pieces += n * 1000 / rate }
        feed(c, 300, 50)
        feed(c, 30_000, 3000)
        assertEquals(2, pieces.size)
        assertTrue(pieces.all { it <= (LongDictation.PIECE_MAX_S + 1) * 1000 })
        c.rest()
        assertEquals("what was begun is handed on at the end", 3, pieces.size)
    }

    @Test fun quietAloneIsNothing() {
        val pieces = ArrayList<Int>()
        val c = LongDictation.Cutter(rate) { _, n -> pieces += n }
        feed(c, 20_000, 60)
        c.rest()
        assertEquals(0, pieces.size)
    }
}
