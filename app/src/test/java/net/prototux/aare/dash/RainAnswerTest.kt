package net.prototux.aare.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 18.11.2026, IDmate's rain raster on a rainy day: a front over a sixth of a 350 km circle is 2500 cells in each of
 * the 13 pictures - more than the 256 KB every other answer of the read API may have. Such an answer was dropped
 * ("Antwort zu gross", taken for an older server), so rain counted on dry days only.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36])
class RainAnswerTest {
    private fun answer(cellsPerFrame: Int): String {
        val step = 5.0 / 111.2
        val sb = StringBuilder("{\"type\":\"rain\",\"device\":\"x\",\"data\":{\"cell_km\":5.0,\"radius\":350,\"complete\":true,\"frames\":[")
        for (f in 0 until 13) {
            if (f > 0) sb.append(',')
            sb.append("{\"t\":\"2026-11-18T%02d:%02d:00Z\",\"kind\":\"%s\",\"src\":[\"dwd\"],\"cells\":[".format(6 + f / 4, f % 4 * 15,
                if (f < 4) "past" else if (f == 4) "now" else "forecast"))
            for (k in 0 until cellsPerFrame) {
                if (k > 0) sb.append(',')
                val lat = (1100 + k / 60) * step
                sb.append("[%.4f,%.4f,%d]".format(java.util.Locale.ROOT, lat, 9.0 + (k % 60) * 0.0712, 1 + k % 3))
            }
            sb.append("]}")
        }
        return sb.append("]}}").toString()
    }

    @Test fun aRainyDayIsRead() {
        val json = answer(2500)
        assertTrue("larger than the other answers may be", json.length > 256 * 1024)
        assertTrue("within what the rain may have", json.length < ReadApi.RAIN_MAX_BYTES)
        val t = Telemetry()
        assertEquals(2, t.acceptRain(json))
        val rain = t.rain()
        assertEquals(13, rain.times.size)
        assertEquals(2500 * 3, rain.cells[4].size)
    }

    @Test fun withoutPicturesAskedAgain() {
        assertEquals(0, Telemetry().acceptRain("{\"type\":\"rain\",\"data\":{\"cell_km\":5.0,\"frames\":[],\"complete\":false}}"))
    }
}
