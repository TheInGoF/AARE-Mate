package net.prototux.aare.dash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * System I-133 (26.11.2026: "sitzt zu tief, die Zeile ‚vor … Sekunden …' ist halb verdeckt"): on the car screen the values
 * start at the top edge and nothing is drawn below the bottom edge, however many lines there are.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashFitTest {
    private val dp = 237 / 160f

    private fun painter(): DashPainter {
        val ctx = RuntimeEnvironment.getApplication()
        val hub = Hub.get(ctx)
        hub.telemetry.accept("""{"v":1,"src":"stick","ts":"${Instant.now()}","seq":1,"type":"status","data":
            {"soc":74,"range":312,"power":182,"speed":0,"t_ext":-1,"t_batt":21,"odometer":48211,"charging":true,"dc":true}}""")
        return DashPainter(ctx, hub)
    }

    /** Rows of the picture with something drawn on them (not the background). */
    private fun inkRows(b: Bitmap): List<Int> = (0 until b.height).filter { y ->
        (0 until b.width step 2).any { x -> b.getPixel(x, y) != DashPainter.BG }
    }

    private fun draw(w: Int, h: Int): List<Int> {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(DashPainter.BG)
        painter().paint(c, Rect(0, 0, w, h), dp, false)
        return inkRows(b)
    }

    @Test
    fun aLowCarScreenGetsEverythingAboveTheBottom() {
        // the right 44 % of a wide car screen, low as on the car
        val rows = draw(560, 560)
        assertTrue("something drawn", rows.isNotEmpty())
        assertTrue("first line near the top, was ${rows.first()}", rows.first() < 34 * dp)
        assertTrue("nothing at the bottom edge, last ink at ${rows.last()}", rows.last() < 560 - 4 * dp)
    }

    @Test
    fun aTallOneDrawsAtFullSize() {
        val rows = draw(560, 1400)
        assertTrue(rows.first() < 34 * dp)
        assertTrue(rows.last() < 1400 - 100 * dp)
    }
}
