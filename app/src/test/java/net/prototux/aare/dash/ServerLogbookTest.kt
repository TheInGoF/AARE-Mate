package net.prototux.aare.dash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import net.prototux.aare.R
import net.prototux.aare.ShownFailure
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetSocketAddress

/**
 * System L-102 / IDMate L-012: the server's logbook in Mate. The answers in `resources/idmate/` were made by the IDmate
 * server itself (its test data, `triplog/tests/test_app_read_api_logbook.py`), so this checks the real format.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36])
class ServerLogbookTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: HttpServer
    private val seen = mutableListOf<Triple<String, String?, String>>() // method+path, auth, body
    private var editStatus = 200

    private fun fixture(name: String) = javaClass.classLoader!!.getResource("idmate/$name")!!.readText()

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            seen += Triple(ex.requestMethod + " " + ex.requestURI, ex.requestHeaders.getFirst("Authorization"), body)
            val path = ex.requestURI.path
            val (code, answer) = when {
                path == "/api/v1/logbook" -> 200 to fixture("logbook.json")
                path == "/api/v1/logbook/options" -> 200 to fixture("logbook_options.json")
                path.startsWith("/api/v1/trips/") && ex.requestMethod == "POST" ->
                    editStatus to if (editStatus == 200) fixture("trip_edited.json") else """{"error":"no"}"""
                else -> 404 to "{}"
            }
            val bytes = answer.toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        ctx.getSharedPreferences("dash_link", Context.MODE_PRIVATE).edit()
            .putString("url", "http://127.0.0.1:${server.address.port}/api/v1").putString("token", "t0k3n-for-the-test-only")
            .putString("vehicle", "dev1").putString("name", "Auto1").commit()
    }

    @After fun stop() {
        server.stop(0)
        ctx.getSharedPreferences("dash_link", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun monthAsTheServerBuildsIt() {
        val m = ServerLogbook.parseMonth(JSONObject(fixture("logbook.json")))
        assertEquals(2026, m.year); assertEquals(11, m.month); assertTrue(m.edit)
        assertEquals(listOf("2026-11", "2026-10"), m.months)
        assertEquals(listOf(ServerLogbook.TRIP, ServerLogbook.PRIVATE, ServerLogbook.GAP, ServerLogbook.OPEN), m.rows.map { it.kind })
        val trip = m.rows[0]
        assertEquals("Kunde", trip.purpose); assertEquals("Firma A", trip.destination); assertEquals("Besprechung", trip.visitReason)
        assertEquals("Zuhause", trip.from); assertEquals(30.0, trip.distance!!, 0.0)
        val private = m.rows[1]
        assertEquals(2, private.count); assertEquals(25.0, private.distance!!, 0.0); assertNull(private.from)
        assertEquals(2, private.trips.size)
        assertTrue(private.trips.all { m.item(it)!!.private })
        assertEquals(5.0, m.rows[2].distance!!, 0.0)
        assertEquals(1, m.open)
        assertEquals(4, m.totals.trips); assertEquals(75.0, m.totals.total, 0.0); assertEquals(20.0, m.totals.open, 0.0)
        assertEquals(listOf("Kunde" to 30.0), m.totals.purposes)
        assertEquals(1000.0, m.totals.odoStart!!, 0.0)
        // times come in UTC and are kept as instants
        assertEquals(java.time.Instant.parse("2026-11-03T07:00:00Z").toEpochMilli(), trip.startMs)
    }

    @Test fun loadsOverTheDashboardsAccess() {
        assertTrue(ServerLogbook.available(ctx))
        val m = ServerLogbook.month(ctx, 2026, 11)
        assertEquals(4, m.items.size)
        assertEquals("GET /api/v1/logbook?year=2026&month=11", seen.last().first)
        assertEquals("Bearer t0k3n-for-the-test-only", seen.last().second)
        val o = ServerLogbook.options(ctx)
        assertTrue(o.edit)
        assertEquals(ServerLogbook.Purpose("Kunde", false), o.purposes.first())
        assertTrue(ServerLogbook.Purpose("Privat", true) in o.purposes)
        assertEquals(listOf("Firma A"), o.destinations)
    }

    @Test fun editSendsOnlyTheChangedFields() {
        val item = ServerLogbook.edit(ctx, 4, linkedMapOf("purpose" to "Kunde", "note" to "Termin"))
        assertEquals("POST /api/v1/trips/4", seen.last().first)
        assertEquals(JSONObject("""{"purpose":"Kunde","note":"Termin"}""").toString(), JSONObject(seen.last().third).toString())
        assertEquals(4L, item.id); assertEquals("Kunde", item.purpose); assertEquals("Termin", item.note); assertFalse(item.private)
    }

    @Test fun refusedEditSaysWhy() {
        editStatus = 403
        try {
            ServerLogbook.edit(ctx, 4, mapOf("note" to "x")); fail()
        } catch (e: ShownFailure) {
            assertEquals(ctx.getString(R.string.logbook_server_no_edit), ShownFailure.text(ctx, e))
        }
    }

    @Test fun withoutAccessNothingIsAsked() {
        ctx.getSharedPreferences("dash_link", Context.MODE_PRIVATE).edit().clear().commit()
        assertFalse(ServerLogbook.available(ctx))
        try { ServerLogbook.month(ctx); fail() } catch (e: ShownFailure) {
            assertEquals(ctx.getString(R.string.logbook_server_none), ShownFailure.text(ctx, e))
        }
        assertTrue(seen.isEmpty())
    }
}
