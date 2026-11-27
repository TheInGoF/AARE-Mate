package net.prototux.aare.preview

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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

    /**
     * System L-113, Navi L-004, L-005, N-039: the badge on "Fahrtenbuch", the page opening on "Ladungen" - charges by month with
     * the charger and its stars, a guess where two stand side by side -, one opened to pick the charger and give details, and
     * "Ladestationen" with only the rated ones. Places at public chargers in Berlin of the shipped list.
     */
    @Test fun visitedChargers() {
        if (System.getProperty("preview") == null) return
        val ctx = rule.activity
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val list = org.json.JSONArray()
            .put(org.json.JSONObject().put("lat", 52.51933).put("lon", 13.41646).put("name", "Tesla · 250 kW").put("network", "tesla")
                .put("kw", 250).put("visits", 2).put("last", now - 3 * day).put("rating", 4).put("power", 1).put("price", 2).put("worked", 1))
            // counted once for a planned stop passed, never rated: goes when the page first opens (System I-143)
            .put(org.json.JSONObject().put("lat", 52.56229).put("lon", 13.45673).put("name", "EnBW · 300 kW").put("network", "enbw")
                .put("kw", 300).put("visits", 1).put("last", now - 9 * day).put("rating", 0))
        // looked at half a day ago: the charge since then is "neu"
        ctx.getSharedPreferences("charger_visits", android.content.Context.MODE_PRIVATE).edit().putString("list", list.toString())
            .putLong("seen_at", now - day / 2).commit()
        net.prototux.aare.ui.car.navi.ChargerVisits.forget()
        val store = net.prototux.aare.trips.TripStore.get(ctx)
        fun charge(ago: Long, min: Int, lat: Double?, lon: Double?, dc: Boolean, from: Double, to: Double, kwh: Double, kw: Double) =
            store.add(net.prototux.aare.trips.TripDetector.Charge(now - ago, now - ago + min * 60_000L, lat, lon, dc, from, to, kwh, kw,
                net.prototux.aare.trips.TripDetector.ChargeSource.STICK))
        charge(40 * day, 31, 52.51938, 13.41640, true, 18.0, 66.0, 35.2, 140.0)
        charge(3 * day, 28, 52.51938, 13.41640, true, 22.0, 71.0, 36.1, 150.0)
        charge(day, 300, null, null, false, 41.0, 80.0, 30.4, 11.0)
        charge(2 * 3600_000L, 36, 52.48061, 13.38555, true, 23.0, 74.0, 39.8, 180.0)
        rule.onNodeWithText("Neu einrichten").performClick()
        rule.onNodeWithText("Spaceship", substring = true).performClick()
        rule.onNodeWithText("Verstanden").performClick(); rule.waitForIdle()
        net.prototux.aare.ui.car.navi.ChargerVisits.noticed(ctx)
        rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Fahrtenbuch"))
        shot("6-start-badge")
        rule.onNodeWithText("Fahrtenbuch").performClick(); rule.waitForIdle()
        repeat(40) { if (rule.onAllNodesWithText("Shell", substring = true).fetchSemanticsNodes().isEmpty()) { Thread.sleep(250); rule.waitForIdle() } }
        shot("7-ladungen")
        rule.onAllNodesWithText("Shell", substring = true).onFirst().performClick(); shot("7b-ladung-details")
        rule.onNodeWithText("Ladestationen").performClick(); shot("7c-ladestationen")
    }
}
