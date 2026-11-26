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

/** The phone app's pages (N-039: home page for every day, the settings in six themes, small settings tiles). Only with -Ppreview. */
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
        rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Musik"))
        rule.onNodeWithText("Musik").performClick(); shot("2-musik")
        rule.activity.onBackPressedDispatcher.onBackPressed(); rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Einstellungen"))
        rule.onNodeWithText("Einstellungen").performClick(); shot("4-einstellungen")
        // a page opened from the settings goes back to them
        rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Ton im Auto"))
        rule.onNodeWithText("Ton im Auto").performClick(); shot("3-ton-im-auto")
        // System L-106: the music under prompts, per kind
        rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Hörbuch")); shot("3b-musik-bei-ansagen")
        rule.activity.onBackPressedDispatcher.onBackPressed(); rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Ansichten"))
        rule.onNodeWithText("Ansichten").performClick(); rule.waitForIdle()
        rule.onNodeWithText("Geteilt").performClick(); shot("5-ansichten-geteilt")
    }

    /** N-039: the look of the car screen in one page (with the old page Bedienleiste), the test switches under Erweitert. */
    @Test fun lookAndTesting() {
        if (System.getProperty("preview") == null) return
        rule.onNodeWithText("Neu einrichten").performClick()
        rule.onNodeWithText("Spaceship", substring = true).performClick()
        rule.onNodeWithText("Verstanden").performClick(); rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Einstellungen"))
        rule.onNodeWithText("Einstellungen").performClick(); rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Aussehen"))
        rule.onNodeWithText("Aussehen").performClick(); shot("8-aussehen")
        rule.activity.onBackPressedDispatcher.onBackPressed(); rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Erweitert"))
        rule.onNodeWithText("Erweitert").performClick(); rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Testen"))
        shot("9-testen")
    }

    /** Navi L-004, L-005, N-039: the badge on "Fahrtenbuch", the visited chargers on top with "neu", one opened for its details. */
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
        rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Fahrtenbuch"))
        shot("6-start-badge")
        rule.onNodeWithText("Fahrtenbuch").performClick(); shot("7-ladepunkte")
        rule.onNodeWithText("Schnelllader Musterstadt").performClick(); shot("7b-ladepunkt-details")
    }
}
