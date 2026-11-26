package net.prototux.aare.dash

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * System L-112 / IDMate L-014: what the app offers to assign, ordered as IDmate's trip list offers it - the values used in
 * the last 30 days first, most used on top with their count, then the rest alphabetically. From an answer the IDmate server
 * itself made (`resources/idmate/logbook_options_usage.json`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "de")
class ServerLogbookChoicesTest {
    private val o = ServerLogbook.parseOptions(JSONObject(javaClass.classLoader!!.getResource("idmate/logbook_options_usage.json")!!.readText()))

    @Test fun mainPurposeAndColours() {
        assertEquals("Kunde", o.main!!.name)
        assertEquals(0xFFD29922.toInt(), o.color("Kunde"))
        assertTrue(o.purposes.first { it.name == "Privat" }.private)
    }

    @Test fun mostUsedFirstThenTheRest() {
        val d = o.choices(ServerLogbook.DESTINATION)
        assertEquals(listOf("Firma B" to 2, "Firma A" to 1, "Kunde C" to 1), d.used)   // equal counts by name
        val r = o.choices(ServerLogbook.REASON)
        assertEquals(listOf("Lieferung" to 2, "Besprechung" to 1), r.used)
        assertTrue(r.rest.isEmpty())
        assertEquals(listOf("Kunde", "Privat"), o.choices(ServerLogbook.PURPOSE).used.map { it.first })
    }

    @Test fun unusedAlphabeticallyAndCountedOnesOfferedToo() {
        val c = ServerLogbook.ordered(listOf("Zeta", "alpha", "Beta", "Mitte"), mapOf("Mitte" to 3, "Neu" to 1))
        assertEquals(listOf("Mitte" to 3, "Neu" to 1), c.used)
        assertEquals(listOf("alpha", "Beta", "Zeta"), c.rest)
    }

    @Test fun anOlderServerWithoutCounts() {
        val old = ServerLogbook.parseOptions(JSONObject(javaClass.classLoader!!.getResource("idmate/logbook_options.json")!!.readText()))
        assertTrue(old.choices(ServerLogbook.PURPOSE).used.isEmpty())
        assertEquals(null, old.main?.color)
    }
}
