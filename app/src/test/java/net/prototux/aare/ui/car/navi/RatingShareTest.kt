package net.prototux.aare.ui.car.navi

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.spatialk.geojson.Position
import org.robolectric.RobolectricTestRunner
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/** Navi I-060b: a rating set back to what was sent is not sent again; one changed while sending goes out after it. */
@RunWith(RobolectricTestRunner::class)
class RatingShareTest {
    private val c: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = c.getSharedPreferences("rating_share", Context.MODE_PRIVATE)
    private val at = Position(longitude = 12.0, latitude = 50.0)
    private val key = "50.0000,12.0000"
    private lateinit var server: ServerSocket
    private val requests = AtomicInteger()
    /** Called inside the first request, before the server answers it. */
    @Volatile private var duringFirst: (() -> Unit)? = null

    private fun visit(stars: Int) = ChargerVisits.Visit(at, "Lader", "Netz", 150, 1, 1_000L, stars)

    @Before fun setUp() {
        server = ServerSocket(0)
        Thread {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                s.use { sock ->
                    val input = sock.getInputStream().bufferedReader()
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("content-length", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                    }
                    repeat(length) { input.read() }
                    if (requests.incrementAndGet() == 1) duringFirst?.invoke()
                    sock.getOutputStream().write("HTTP/1.1 201 Created\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    sock.getOutputStream().flush()
                }
            }
        }.apply { isDaemon = true }.start()
        net.prototux.aare.Settings(c).apply { shareRatings = true; mapServer = "http://127.0.0.1:${server.localPort}" }
        prefs.edit().clear().commit()
    }

    @After fun tearDown() { server.close() }

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!what() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(what())
    }

    @Test fun setBackToWhatWasSentIsNotSent() {
        prefs.edit().putString("sent:$key", RatingShare.signature(4, 0, 0, 0)).commit()
        RatingShare.changed(c, visit(4))
        waitFor { !prefs.contains("wait:$key") }
        // a later change goes out, so the sender has passed the one before
        RatingShare.changed(c, visit(5))
        waitFor { prefs.getString("sent:$key", null) == RatingShare.signature(5, 0, 0, 0) }
        assertEquals(1, requests.get())
        assertNull(prefs.getString("wait:$key", null))
    }

    @Test fun changedBackWhileSendingGoesOutAfter() {
        prefs.edit().putString("sent:$key", RatingShare.signature(4, 0, 0, 0)).commit()
        duringFirst = { RatingShare.changed(c, visit(4)) }
        RatingShare.changed(c, visit(5))
        waitFor { requests.get() == 2 && !prefs.contains("wait:$key") }
        assertEquals(RatingShare.signature(4, 0, 0, 0), prefs.getString("sent:$key", null))
    }
}
