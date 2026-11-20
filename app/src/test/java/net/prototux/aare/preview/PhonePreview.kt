package net.prototux.aare.preview

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The phone app's pages (18.11.2026 rework: tiles, Audio, Sprache, small settings tiles). Only with -Ppreview. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "de-w600dp-h1000dp-xhdpi")
class PhonePreview {
    @get:Rule val rule = createAndroidComposeRule<net.prototux.aare.ui.MainActivity>()

    private fun shot(name: String) {
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/preview/phone").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun pages() {
        if (System.getProperty("preview") == null) return
        shot("1-start")
        rule.onNodeWithText("Neu einrichten").performClick(); shot("1b-fahrzeug")
        rule.onNodeWithText("Spaceship", substring = true).performClick(); shot("1c-obd")
        rule.onNodeWithText("Verstanden").performClick(); rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Audio"))
        rule.onNodeWithText("Audio").performClick(); shot("2-audio")
        rule.activity.onBackPressedDispatcher.onBackPressed(); rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Sprache"))
        rule.onNodeWithText("Sprache").performClick(); shot("3-sprache")
        rule.activity.onBackPressedDispatcher.onBackPressed(); rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Einstellungen"))
        rule.onNodeWithText("Einstellungen").performClick(); shot("4-einstellungen")
        rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Ansichten"))
        rule.onNodeWithText("Ansichten").performClick(); rule.waitForIdle()
        rule.onNodeWithText("Geteilt").performClick(); shot("5-ansichten-geteilt")
    }

    /** Review 18.11.2026: the bar's settings in one page, the car screen's look with size and animations. */
    @Test fun barAndScreen() {
        if (System.getProperty("preview") == null) return
        rule.onNodeWithText("Neu einrichten").performClick()
        rule.onNodeWithText("Spaceship", substring = true).performClick()
        rule.onNodeWithText("Verstanden").performClick(); rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Einstellungen"))
        rule.onNodeWithText("Einstellungen").performClick(); rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Bedienleiste"))
        rule.onNodeWithText("Bedienleiste").performClick(); shot("8-bedienleiste")
        rule.activity.onBackPressedDispatcher.onBackPressed(); rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Autobildschirm"))
        rule.onNodeWithText("Autobildschirm").performClick(); shot("9-autobildschirm")
    }

    /** Navi L-004, L-005: the badge on "Routenplanung", the visited chargers first with "neu", one opened for its details. */
    @Test fun visitedChargers() {
        if (System.getProperty("preview") == null) return
        val ctx = rule.activity
        val now = System.currentTimeMillis()
        val list = org.json.JSONArray()
            .put(org.json.JSONObject().put("lat", 50.0).put("lon", 12.0).put("name", "Schnelllader Musterstadt").put("network", "ionity")
                .put("kw", 300).put("visits", 2).put("last", now).put("rating", 4).put("power", 2).put("price", 3).put("worked", 1).put("fresh", true))
            .put(org.json.JSONObject().put("lat", 50.1).put("lon", 12.1).put("name", "Autohof Beispielfeld").put("network", "enbw")
                .put("kw", 150).put("visits", 1).put("last", now - 86_400_000L).put("rating", 0).put("fresh", false))
        ctx.getSharedPreferences("charger_visits", android.content.Context.MODE_PRIVATE).edit().putString("list", list.toString()).commit()
        net.prototux.aare.ui.car.navi.ChargerVisits.forget()
        rule.onNodeWithText("Neu einrichten").performClick()
        rule.onNodeWithText("Spaceship", substring = true).performClick()
        rule.onNodeWithText("Verstanden").performClick(); rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Routenplanung"))
        shot("6-start-badge")
        rule.onNodeWithText("Routenplanung").performClick(); shot("7-ladepunkte")
        rule.onNodeWithText("Schnelllader Musterstadt").performClick(); shot("7b-ladepunkt-details")
    }
}
