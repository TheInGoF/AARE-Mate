package net.prototux.aare.dash

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * System L-110, L-111 / IDMate L-013: the way of a trip and what it used, from answers the IDmate server itself made
 * (`resources/idmate/route.json`, `logbook_energy.json`, from its test data with places in Berlin).
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36])
class ServerLogbookRouteTest {
    private fun fixture(name: String) = JSONObject(javaClass.classLoader!!.getResource("idmate/$name")!!.readText())

    @Test fun itemsCarryWhatTheTripUsed() {
        val m = ServerLogbook.parseMonth(fixture("logbook_energy.json"))
        val t = m.items.first { it.energy != null }
        assertEquals(5.12, t.energy!!, 1e-9)
        assertEquals(17.1, t.consumption!!, 1e-9)
        assertEquals(4.4, t.tExt!!, 1e-9)
        // a trip without them: null, not 0
        assertTrue(m.items.any { it.energy == null && it.consumption == null })
    }

    @Test fun theWayInDrivingOrder() {
        val r = ServerLogbook.parseRoute(fixture("route.json"))
        assertEquals(4, r.points.size)
        assertEquals(52.5163, r.points.first()[0], 1e-9)
        assertEquals(13.4132, r.points.last()[1], 1e-9)
        assertEquals("Zuhause", r.start!!.name)
        assertEquals("Firma A", r.end!!.name)
        val box = assertNotNull(r.box).let { r.box!! }
        assertEquals(13.3777, box[0], 1e-9); assertEquals(52.5163, box[1], 1e-9)
        assertEquals(13.4132, box[2], 1e-9); assertEquals(52.5219, box[3], 1e-9)
        // GeoJSON wants longitude first
        val line = JSONObject(r.lineJson()).getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals(13.3777, line.getJSONArray(0).getDouble(0), 1e-9)
        assertEquals(52.5163, line.getJSONArray(0).getDouble(1), 1e-9)
        assertEquals("Point", JSONObject(r.endJson()).getJSONObject("geometry").getString("type"))
    }

    @Test fun noWayNoLine() {
        val r = ServerLogbook.parseRoute(JSONObject("""{"type":"route","data":{"id":7}}"""))
        assertTrue(r.points.isEmpty())
        assertEquals(ServerLogbook.EMPTY_JSON, r.lineJson())
        assertEquals(null, r.box)
    }
}
