package net.prototux.aare.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** System L-117: the way driven drawn as it is driven - a point every 30 m, gaps at jumps, a new line after a night. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36])
class DashTrailTest {
    private fun east(m: Double) = 12.5 + m / (111_320.0 * Math.cos(Math.toRadians(52.5)))

    @Before fun clean() = DashTrail.clear()

    private fun coords(json: String): Int {
        val f = org.json.JSONObject(json).getJSONArray("features")
        return (0 until f.length()).sumOf { f.getJSONObject(it).getJSONObject("geometry").getJSONArray("coordinates").length() }
    }

    @Test fun drawnAsItIsDriven() {
        // 36 m/s for 100 s, a fix every second
        for (s in 0..100) DashTrail.note(52.5, east(36.0 * s), s * 1000L)
        val all = coords(DashTrail.settled.value) + coords(DashTrail.recent.value)
        // about one point per fix (36 m apart, one every 30 m at least), the settled part ends where the recent begins
        assertTrue("$all", all in 95..103)
        assertEquals(east(3600.0), DashTrail.last()!!.longitude, 1e-9)
    }

    @Test fun standingAddsNothingAndAJumpLeavesAGap() {
        DashTrail.note(52.5, east(0.0), 0); DashTrail.note(52.5, east(10.0), 2_000); DashTrail.note(52.5, east(20.0), 4_000)
        DashTrail.note(52.5, east(40.0), 6_000)
        DashTrail.note(52.5, east(50_000.0), 8_000)
        DashTrail.note(52.5, east(50_040.0), 10_000)
        val f = org.json.JSONObject(DashTrail.recent.value).getJSONArray("features")
        assertEquals("two lines, no stroke across", 2, f.length())
    }

    @Test fun aNewDriveAfterTheNight() {
        DashTrail.note(52.5, east(0.0), 0); DashTrail.note(52.5, east(100.0), 1_000)
        DashTrail.note(52.5, east(200.0), 1_000 + DashTrail.NEW_DRIVE_MS + 1)
        assertEquals(DashMapGeo.EMPTY, DashTrail.settled.value)
        assertTrue(coords(DashTrail.recent.value) <= 1)
    }
}
