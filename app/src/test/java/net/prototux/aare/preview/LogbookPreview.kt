package net.prototux.aare.preview

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** System L-102: the IDmate server's logbook month in Mate's look, from the server's own test answer. Only with -Ppreview. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "de-w600dp-h1400dp-xhdpi")
class LogbookPreview {
    @get:Rule val rule = createComposeRule()

    @Test fun serverMonth() {
        if (System.getProperty("preview") == null) return
        val m = net.prototux.aare.dash.ServerLogbook.parseMonth(JSONObject(javaClass.classLoader!!.getResource("idmate/logbook.json")!!.readText()))
        rule.setContent {
            net.prototux.aare.ui.theme.PhoneTheme(dark = false) {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(20.dp)) {
                    net.prototux.aare.ui.ServerLogbookMonth(m) {}
                }
            }
        }
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/preview/phone").apply { mkdirs() }
        File(dir, "10-fahrtenbuch-server.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** System L-111: a trip's values as IDmate's trip page lists them (the map beside them needs the device). */
    @Test fun tripValues() {
        if (System.getProperty("preview") == null) return
        val m = net.prototux.aare.dash.ServerLogbook.parseMonth(JSONObject(javaClass.classLoader!!.getResource("idmate/logbook_energy.json")!!.readText()))
        val t = m.items.first { it.energy != null }
        rule.setContent {
            net.prototux.aare.ui.theme.PhoneTheme(dark = false) {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(20.dp)) {
                    net.prototux.aare.ui.TripValues(t)
                }
            }
        }
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/preview/phone").apply { mkdirs() }
        File(dir, "10b-fahrt-werte.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
