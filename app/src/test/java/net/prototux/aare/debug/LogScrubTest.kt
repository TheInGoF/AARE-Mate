package net.prototux.aare.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 20.11.2026: the issue log without private data. */
class LogScrubTest {
    @Test fun positionsRoadsAndNames() {
        val s = LogScrub.scrub("turn to the car: maneuver 35, road \"Baumstraße\" at 52.52001, 13.40495")
        assertEquals("turn to the car: maneuver 35, road \"<text>\" at <pos>, <pos>", s)
    }

    @Test fun networkAndSecrets() {
        val s = LogScrub.scrub("map https://karten.example.ts.net:8090/x token=abcdefgh123 ip 192.168.1.5:5277 bt 00:11:22:AA:BB:CC …AB:CD fe80::1:abcd")
        listOf("karten", "abcdefgh123", "192.168", "00:11:22", "AB:CD", "fe80").forEach { assertFalse(it, s.contains(it)) }
    }

    @Test fun phoneVinAndKnown() {
        val s = LogScrub.scrub("call +49 170 1234567, car WVWZZZE1ZMP012345, home Musterweg 5", listOf("Musterweg 5"))
        assertEquals("call <phone>, car <vin>, home <private>", s)
    }

    @Test fun namesWithoutQuotes() {
        assertEquals("driving: auto-replied to <name>", LogScrub.scrub("driving: auto-replied to Anna"))
        assertEquals("wireless: <name> offers Android Auto wireless, connecting",
            LogScrub.scrub("wireless: My Car offers Android Auto wireless, connecting"))
    }

    @Test fun whatTheAppSaysStays() {
        val line = "memory: tight - 366 of 3314 MB free, 3.5 s, com.spotify.music at 18:05:08"
        assertEquals(line, LogScrub.scrub(line))
        assertTrue(LogScrub.scrub("charge rated: IONITY Musterstadt 4 of 5").startsWith("charge rated: <name>"))
    }
}
