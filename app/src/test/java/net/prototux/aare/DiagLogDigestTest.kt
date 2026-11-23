package net.prototux.aare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 18.11.2026: the log for reading through - a summary, the area of each line, no pings, in time order; 19.11.2026: levels and area tags. */
class DiagLogDigestTest {
    private val lines = listOf(
        "18/11 18:05:08  rx AapMessage(ch=0, id=0x18, enc len=2)",
        "18/11 18:05:15  fault marked: 20261118-180515",
        "18/11 18:05:18  memory: tight - 366 of 3314 MB free",
        "18/11 18:05:32  fault of 18.11. 18:05:15: \"Testnachricht\"",
        "18/11 18:05:38  rx AapMessage(ch=0, id=0xb, enc len=6)   (59 similar lines left out in the last minute)",
        "18/11 18:07:11  car call availability: false",
        "18/11 17:58:05  session failed: read failed: read failed: EIO (I/O error)",
        "18/11 18:16:59  turn to the car: maneuver 35, road \"\", cue \"Kreisverkehr\"",
        // a line of the new kind, as stored since 19.11.2026
        "19/11 08:01:02  WARN  AUDIO  music after a prompt: still low after 3 s",
    )

    @Test fun summaryAndAreas() {
        val d = DiagLog.digest(lines, "2.0.55")
        assertTrue(d.startsWith("Mate 2.0.55 · Diagnostic log"))
        assertTrue(d, d.contains("8 lines (1 pings left out)"))
        assertTrue(d.contains("Drops 1 · Memory tight 1"))
        assertTrue(d, d.contains("Levels: INFO 5, WARN 2, ERROR 1"))
        assertTrue(d.contains("Fault mark 18.11. 18:05:15: \"Testnachricht\""))
        assertTrue(d, d.contains("18/11 18:05:18  WARN  MEM    memory: tight"))
        assertTrue(d.contains("INFO  PHONE  car call availability"))
        assertTrue(d.contains("INFO  NAVI   turn to the car"))
        assertTrue(d.contains("ERROR CONN   session failed"))
        assertTrue(d.contains("19/11 08:01:02  WARN  AUDIO  music after a prompt"))
        // the problems on top, before all lines
        assertTrue(d.indexOf("Problems (3)") in 0 until d.indexOf("All lines:"))
        assertFalse("pings are left out", d.contains("id=0xb"))
    }

    @Test fun areas() {
        assertEquals("NET", DiagLog.area("car internet: the tablet's internet goes over Wi-Fi"))
        assertEquals("DASH", DiagLog.area("dashboard: Seite geladen"))
        assertEquals("AUDIO", DiagLog.area("guidance focus: car answered GAIN"))
        assertEquals("CHARGE", DiagLog.area("charging plan FAST: 410 km"))
        assertEquals("NAVI", DiagLog.area("route over the stops: Valhalla answered 400"))
        assertEquals("CONN", DiagLog.area("session running: 10/10 channels open"))
    }

    @Test fun levels() {
        assertEquals(DiagLog.Level.ERROR, DiagLog.levelOf("charging: plan failed for FAST"))
        assertEquals(DiagLog.Level.WARN, DiagLog.levelOf("memory: tight - 366 of 3314 MB free"))
        assertEquals(DiagLog.Level.INFO, DiagLog.levelOf("audio focus: AUDIO_FOCUS_STATE_GAIN"))
        val e = DiagLog.parse("19/11 08:01:02  ERROR NAVI   eta: late")
        assertEquals(DiagLog.Level.ERROR, e.level); assertEquals("NAVI", e.area); assertEquals("eta: late", e.text)
    }
}
