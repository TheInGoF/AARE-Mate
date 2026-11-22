package net.prototux.aare.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** System L-087: the car's live values (stick) ahead of the server's, IDmate's range kept apart, the consumption of now. */
@RunWith(RobolectricTestRunner::class)
class TelemetryLiveTest {
    private fun t(src: String, atMs: Long, data: String) =
        """{"v":1,"src":"$src","ts":"${Instant.ofEpochMilli(atMs)}","seq":1,"type":"status","data":$data}"""

    @Test fun stickHoldsAgainstANewerServerValue() {
        val tel = Telemetry()
        val now = System.currentTimeMillis()
        tel.accept(t("stick", now - 5_000, """{"range":186,"soc":51}"""))
        tel.accept(t("backend", now, """{"range":176,"soc":53}"""))
        assertEquals(186.0, tel.number("range")!!, 0.0)
        assertEquals("stick", tel.get("soc").src)
        assertEquals(176.0, (tel.from("backend", "range").v as Number).toDouble(), 0.0)
    }

    @Test fun serverTakesOverWhenTheStickIsSilent() {
        val tel = Telemetry()
        val now = System.currentTimeMillis()
        tel.accept(t("stick", now - 5 * 60_000, """{"range":186}"""))
        tel.accept(t("backend", now, """{"range":176}"""))
        assertEquals(176.0, tel.number("range")!!, 0.0)
    }

    @Test fun consumptionOfNowFromPowerAndSpeed() {
        val tel = Telemetry()
        val now = System.currentTimeMillis()
        tel.accept(t("stick", now - 1_000, """{"power":20,"speed":100}"""))
        assertEquals(20.0, tel.number("consumption_now")!!, 0.01)
        tel.accept(t("stick", now, """{"power":30,"speed":100}"""))
        val c = tel.number("consumption_now")!!
        assert(c > 20.0 && c < 21.0) { "smoothed: $c" }
        tel.accept(t("stick", now + 1_000, """{"power":5,"speed":3}"""))   // standing: not counted
        assertNotNull(tel.number("consumption_now"))
        assertNull(tel.from("backend", "consumption_now"))
    }
}
