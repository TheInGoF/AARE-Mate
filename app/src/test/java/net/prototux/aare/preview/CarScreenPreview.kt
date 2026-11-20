package net.prototux.aare.preview

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import net.prototux.aare.ui.car.navi.NaviPreview
import androidx.compose.ui.test.swipeUp
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import net.prototux.aare.media.CircleButton
import net.prototux.aare.media.Pill
import net.prototux.aare.ui.theme.CarTheme
import net.prototux.aare.ui.theme.LocalCarDimens
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import net.prototux.aare.ui.car.navi.NaviSearchPanelPreview

/**
 * Pictures of the car screen's controls at the ID.7's real measure (18.11.2026: "Du könntest mir mal ein paar
 * Screenshots reinstellen"): the 1920x1080 frame the car asks for, drawn at the density it names (183 dpi), with the
 * app's own components. Written to app/build/preview/. Not a check - run on purpose:
 *   ./build.sh :app:testDebugUnitTest --tests '*CarScreenPreview*' -Ppreview
 * The bars of the car (top 122, bottom 121 px) are drawn as grey bands, as they lie over the picture in the car.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "de-w1920dp-h1080dp-mdpi")
class CarScreenPreview {
    @get:Rule val rule = createComposeRule()

    /** The car's density (183 dpi) on a 160-dpi canvas of 1920x1080 px; [factor]: Mate's bar 1.3, areas 1.0. */
    private fun car(factor: Float) = Density(183f / 160f * factor, 1f)

    private fun shoot(name: String, content: @Composable () -> Unit) {
        rule.setContent {
            CarTheme(night = true) {
                Box(Modifier.size(1920.dp, 1080.dp).background(Color(0xFF101418))) {
                    content()
                    // the car's own bars over the picture
                    Box(Modifier.fillMaxWidth().size(1920.dp, 122.dp).background(Color(0x99606870)).align(Alignment.TopStart))
                    Box(Modifier.fillMaxWidth().size(1920.dp, 121.dp).background(Color(0x99606870)).align(Alignment.BottomStart))
                }
            }
        }
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/preview").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Mate's whole car screen: the bar at the bottom with music, microphone and areas, drawn as in the car. */
    @Test fun bar() {
        if (System.getProperty("preview") == null) return
        val state = net.prototux.aare.ui.car.CarUiState().apply {
            nowPlaying = net.prototux.aare.ui.car.NowPlaying("music", "Titel des Stücks", "Interpret", null, true, 60_000, 200_000, 0)
            headUnitName = "Volkswagen"
        }
        val actions = object : net.prototux.aare.ui.car.CarActions {
            override fun openApp(app: net.prototux.aare.ui.car.CarAppEntry) {}
            override fun goHome() {}
            override fun openLauncher() {}
            override fun assistant() {}
            override fun mediaPlayPause() {}
            override fun mediaNext() {}
            override fun mediaPrevious() {}
            override fun dismissNotice(notice: net.prototux.aare.ui.car.CarNotice) {}
            override fun answerCall() {}
            override fun endCall() {}
            override fun navigateTo(place: String) {}
        }
        shoot("leiste") {
            // the bar in Mate's measure (1.3 times the car's)
            CompositionLocalProvider(LocalDensity provides car(1.3f)) {
                Box(Modifier.fillMaxSize().padding(top = 122.dp / (183f / 160f * 1.3f), bottom = 121.dp / (183f / 160f * 1.3f))) {
                    net.prototux.aare.ui.car.IdmateShell(state, actions,
                        appContent = { _, m -> Box(m) },
                        navMap = { _, m -> Box(m) },
                        overlay = {})
                }
            }
        }
    }

    private val navi = net.prototux.aare.ui.car.CarAppEntry("navi", "com.google.android.apps.maps", "Maps", null, net.prototux.aare.ui.car.CarAppKind.NAVIGATION)
    private val musicApp = net.prototux.aare.ui.car.CarAppEntry("music", "com.spotify.music", "Spotify", null, net.prototux.aare.ui.car.CarAppKind.MEDIA)
    private val noActions = object : net.prototux.aare.ui.car.CarActions {
        override fun openApp(app: net.prototux.aare.ui.car.CarAppEntry) {}
        override fun goHome() {}
        override fun openLauncher() {}
        override fun assistant() {}
        override fun mediaPlayPause() {}
        override fun mediaNext() {}
        override fun mediaPrevious() {}
        override fun dismissNotice(notice: net.prototux.aare.ui.car.CarNotice) {}
        override fun answerCall() {}
        override fun endCall() {}
        override fun navigateTo(place: String) {}
    }

    /** A map stand-in: pure magenta, so the free map can be counted in the picture. */
    @Composable private fun FakeMap(m: Modifier) = Box(m.background(Color(0xFFFF00FF)))

    /** Mate's car screen with Maps and Spotify, in [setup]'s state; [act] then plays taps on it. */
    private fun scene(name: String, setup: net.prototux.aare.ui.car.CarUiState.() -> Unit, act: () -> Unit = {}) {
        val state = net.prototux.aare.ui.car.CarUiState().apply {
            apps.add(navi); apps.add(musicApp)
            nowPlaying = net.prototux.aare.ui.car.NowPlaying("music", "Titel des Stücks", "Interpret", null, true, 60_000, 200_000, 0)
            activeMediaAppId = "music"; activeNavAppId = "navi"
            setup()
        }
        rule.setContent {
            CarTheme(night = true) {
                Box(Modifier.size(1920.dp, 1080.dp).background(Color(0xFF101418))) {
                    CompositionLocalProvider(LocalDensity provides car(1.3f)) {
                        Box(Modifier.fillMaxSize().padding(top = 122.dp / (183f / 160f * 1.3f), bottom = 121.dp / (183f / 160f * 1.3f))) {
                            net.prototux.aare.ui.car.IdmateShell(state, noActions,
                                appContent = { app, m -> if (app.kind == net.prototux.aare.ui.car.CarAppKind.NAVIGATION) FakeMap(m)
                                    else Box(m.background(Color(0xFF1E2A30)), contentAlignment = Alignment.Center) { Text("Musik-App", color = Color.White) } },
                                navMap = { _, m -> FakeMap(m) },
                                overlay = {})
                        }
                    }
                    Box(Modifier.fillMaxWidth().size(1920.dp, 122.dp).background(Color(0x99606870)).align(Alignment.TopStart))
                    Box(Modifier.fillMaxWidth().size(1920.dp, 121.dp).background(Color(0x99606870)).align(Alignment.BottomStart))
                }
            }
        }
        rule.waitForIdle(); act(); rule.waitForIdle()
        rule.mainClock.advanceTimeBy(2_000)
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/preview").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val guidance = net.prototux.aare.notify.PhoneGuidance("k", "com.google.android.apps.maps", "Maps", "300 m", "Baumstraße", "",
        null, net.prototux.aare.notify.PhoneGuidance.Distance(300, "300", net.prototux.aare.notify.PhoneGuidance.DistanceUnit.METERS), "Baumstraße")

    @Test fun naviFull() { if (System.getProperty("preview") == null) return
        scene("navi-voll", { screen = net.prototux.aare.ui.car.CarScreen.App("navi") }) }

    @Test fun musicWithTurn() { if (System.getProperty("preview") == null) return
        scene("musik-mit-abbiegehinweis", { screen = net.prototux.aare.ui.car.CarScreen.App("music"); phoneGuidance = guidance }) }

    @Test fun split() { if (System.getProperty("preview") == null) return
        scene("geteilt", { screen = net.prototux.aare.ui.car.CarScreen.App("navi") }) {
            rule.onNodeWithContentDescription(splitSign()).performTouchInput { longClick() }
        } }

    @Test fun switcher() { if (System.getProperty("preview") == null) return
        scene("bereiche", { screen = net.prototux.aare.ui.car.CarScreen.App("navi") }) {
            rule.onNodeWithText("Maps").performClick()
        } }

    /**
     * 18.11.2026 ("wenn ich ein Bedienfeld auf Navi hochziehe, dann geht erst die Liste auf und dann klappt sie nach rechts
     * aus"): the list of the navigation area while it opens - each frame must stand where the last one stands.
     */
    @Test fun switcherListOpening() { if (System.getProperty("preview") == null) return
        val second = net.prototux.aare.ui.car.CarAppEntry("abrp", "com.iternio.abrpapp", "A Better Route Planner", null, net.prototux.aare.ui.car.CarAppKind.NAVIGATION)
        scene("bereiche-liste-4-offen", { screen = net.prototux.aare.ui.car.CarScreen.App("navi"); apps.add(second) }) {
            rule.onNodeWithText("Maps").performClick(); rule.waitForIdle()
            rule.mainClock.autoAdvance = false
            rule.onNodeWithText("Navi").performTouchInput { swipeUp() }
            listOf(40L, 80L, 120L).forEachIndexed { i, t -> rule.mainClock.advanceTimeBy(t); capture("bereiche-liste-${i + 1}") }
            rule.mainClock.autoAdvance = true
        } }

    @Test fun callAndNotice() { if (System.getProperty("preview") == null) return
        scene("anruf-und-meldung", { screen = net.prototux.aare.ui.car.CarScreen.App("navi")
            call = net.prototux.aare.ui.car.CarCall(true, "Anrufer", 0)
            notices.add(net.prototux.aare.ui.car.CarNotice("n", "WhatsApp – Anna", "Bin in 10 Minuten da", null)) }) }

    /** Navi L-001: the tile "Wie war die Ladung?" over the controls - one charger, and three networks side by side. */
    @Test fun chargeAsk() { if (System.getProperty("preview") == null) return
        fun at(m: Double) = org.maplibre.spatialk.geojson.Position(longitude = 12.9, latitude = 50.8 + m / 111_320.0)
        val a = net.prototux.aare.ui.car.navi.Charger(at(20.0), 300, 8, "IONITY")
        try {
            net.prototux.aare.ui.car.navi.ChargeAsk.pending.value = net.prototux.aare.ui.car.navi.ChargeAsk.Ask(listOf(a), 20.0, 80.0)
            scene("ladung-bewerten", { screen = net.prototux.aare.ui.car.CarScreen.App("navi") })
        } finally { net.prototux.aare.ui.car.navi.ChargeAsk.pending.value = null }
    }

    @Test fun chargeAskWhich() { if (System.getProperty("preview") == null) return
        fun at(m: Double) = org.maplibre.spatialk.geojson.Position(longitude = 12.9, latitude = 50.8 + m / 111_320.0)
        val more = listOf(net.prototux.aare.ui.car.navi.Charger(at(20.0), 300, 8, "IONITY"), net.prototux.aare.ui.car.navi.Charger(at(60.0), 250, 12, "Tesla"),
            net.prototux.aare.ui.car.navi.Charger(at(120.0), 150, 2, "EnBW"))
        try {
            net.prototux.aare.ui.car.navi.ChargeAsk.pending.value = net.prototux.aare.ui.car.navi.ChargeAsk.Ask(more, 20.0, 80.0)
            scene("ladung-bewerten-anbieter", { screen = net.prototux.aare.ui.car.CarScreen.App("navi") })
        } finally { net.prototux.aare.ui.car.navi.ChargeAsk.pending.value = null }
    }

    /** Navi P-041: the incidents as signs - road works now with the worker and his heap (Zeichen 123), large and small;
     *  a lane closed as Zeichen 120 (narrows), only a closed road with the red ring. */
    @Test fun trafficSigns() { if (System.getProperty("preview") == null) return
        net.prototux.aare.ui.car.Mate.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        shoot("verkehrszeichen") {
            CompositionLocalProvider(LocalDensity provides car(1f)) {
                Column(Modifier.fillMaxSize().padding(top = 160.dp, start = 60.dp), verticalArrangement = Arrangement.spacedBy(40.dp)) {
                    listOf(220.dp, 64.dp, 28.dp).forEach { sz ->
                        Row(horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                            listOf(9, 6, 1, 14, 7, 0, 8).forEach { k -> net.prototux.aare.ui.car.navi.TrafficSign(k, sz) }
                        }
                    }
                    // the map's bitmaps: road works on the route (40 px) beside a closure's X (30 px), at their size and enlarged
                    Row(horizontalArrangement = Arrangement.spacedBy(30.dp), verticalAlignment = Alignment.CenterVertically) {
                        val works = net.prototux.aare.ui.car.navi.NaviTraffic.rememberSignImage(9, 40)
                        val closed = androidx.compose.runtime.remember { net.prototux.aare.ui.car.navi.NaviTraffic.closedImage(30, false) }
                        androidx.compose.foundation.Image(works, null)
                        androidx.compose.foundation.Image(closed, null)
                        androidx.compose.foundation.Image(works, null, Modifier.size(160.dp))
                    }
                }
            }
        }
    }

    /** System L-057: the message area - the people of all apps, no texts; one chosen, its answer offered. */
    @Test fun messagePeople() { if (System.getProperty("preview") == null) return
        val now = System.currentTimeMillis()
        scene("nachrichten-personen", {
            history.add(net.prototux.aare.ui.car.CarNotice("a", "Anna", "geheim", null, timeMs = now - 3 * 60_000, message = true, reply = { true }))
            history.add(net.prototux.aare.ui.car.CarNotice("b", "Familie · Ben", "geheim", null, timeMs = now - 40 * 60_000, message = true))
            history.add(net.prototux.aare.ui.car.CarNotice("c", "Carla", "geheim", null, timeMs = now - 5 * 3600_000L, message = true, reply = { true }))
            unseen = 3
        }) {
            rule.onAllNodesWithContentDescription("Messenger").onFirst().performClick(); rule.waitForIdle()
            rule.onNodeWithText("Anna").performClick()
        }
    }

    /** System L-056: the music's search by keyboard or voice in the bar, also while the player is shown. */
    @Test fun musicSearchPill() { if (System.getProperty("preview") == null) return
        net.prototux.aare.ui.car.Mate.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        try {
            net.prototux.aare.media.MediaControls.search = {}; net.prototux.aare.media.MediaControls.searchVoice = {}
            net.prototux.aare.media.MediaControls.browse = {}
            shoot("musik-suche") {
                CompositionLocalProvider(LocalDensity provides car(1.3f)) {
                    Box(Modifier.fillMaxSize()) {
                        Row(Modifier.align(Alignment.BottomStart).padding(start = 200.dp, bottom = 20.dp).size(700.dp, 64.dp)
                            .background(Color(0xFF1B2128)), verticalAlignment = Alignment.CenterVertically) {
                            net.prototux.aare.media.HostedMediaControls()
                        }
                    }
                }
            }
        } finally { net.prototux.aare.media.MediaControls.clear() }
    }

    /** Navi L-035: the route as a list - left the preview right after the routes came (charging stops still being planned,
     *  startable), right the list once planned: two own stops and the destination (grip), a charging stop (X), one taken out. */
    @Test fun naviRouteList() { if (System.getProperty("preview") == null) return
        net.prototux.aare.ui.car.Mate.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        fun at(lat: Double) = org.maplibre.spatialk.geojson.Position(longitude = 12.0, latitude = lat)
        val ionity = net.prototux.aare.ui.car.navi.Charger(at(51.5), 350, 6, "IONITY")
        val enbw = net.prototux.aare.ui.car.navi.Charger(at(52.0), 300, 8, "EnBW")
        val now = System.currentTimeMillis()
        val rows = listOf(
            net.prototux.aare.ui.car.navi.NaviTripPlan.Row(net.prototux.aare.ui.car.navi.NaviTripPlan.Kind.OWN, "Bäckerei Musterweg", now + 25 * 60_000L, 31_000.0, at = at(50.9)),
            net.prototux.aare.ui.car.navi.NaviTripPlan.Row(net.prototux.aare.ui.car.navi.NaviTripPlan.Kind.CHARGE, net.prototux.aare.ui.car.navi.NaviTripPlan.chargerName(ionity), now + 95 * 60_000L, 158_000.0, 14.0, 62.0, 21.0, ionity.network, ionity.at, ionity),
            net.prototux.aare.ui.car.navi.NaviTripPlan.Row(net.prototux.aare.ui.car.navi.NaviTripPlan.Kind.OWN, "Freunde", now + 150 * 60_000L, 236_000.0, at = at(51.8)),
            net.prototux.aare.ui.car.navi.NaviTripPlan.Row(net.prototux.aare.ui.car.navi.NaviTripPlan.Kind.DESTINATION, "Musterstadt", now + 215 * 60_000L, 301_000.0, arrivePercent = 24.0, at = at(52.3)))
        val p = net.prototux.aare.ui.car.navi.Preview("Musterstadt", 301.0, 215.0, 52.3, 24.0, false,
            plan = rows.filter { it.kind != net.prototux.aare.ui.car.navi.NaviTripPlan.Kind.CHARGE }, pending = true)
        shoot("navi-routenliste") {
            CompositionLocalProvider(LocalDensity provides car(1f)) {
                Row(Modifier.padding(start = 16.dp, top = 130.dp, bottom = 130.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    // the preview as the car shows it (scrolled to the top), and the list on its own as it scrolls into view
                    net.prototux.aare.ui.car.navi.NaviPanel(Modifier.size(560.dp, 830.dp)) {
                        NaviPreview(p, null, "Musterstadt", emptyList(), 0, 0, onPick = {}, onStart = {}, onBack = {}, onClose = {}, canPlan = true)
                    }
                    net.prototux.aare.ui.car.navi.NaviPanel(Modifier.size(560.dp, 830.dp)) {
                        net.prototux.aare.ui.car.navi.NaviRouteList(rows, listOf(enbw), true, { emptyList() }, false, {}, { _, _ -> }, {}, {}, {}, {}, {})
                    }
                }
            }
        }
    }

    /** System L-060: the debug app in the car, its audio tests. */
    @Test fun debugApp() { if (System.getProperty("preview") == null) return
        net.prototux.aare.ui.car.Mate.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        shoot("debug-audio") {
            CompositionLocalProvider(LocalDensity provides car(1f)) {
                Box(Modifier.fillMaxSize().padding(top = 122.dp, bottom = 121.dp)) { net.prototux.aare.ui.car.DebugScreen(Modifier.fillMaxSize()) }
            }
        }
    }

    /** 19.11.2026: every manoeuvre's arrow with its meaning, to check them for their sense. */
    @Test fun maneuverArrows() { if (System.getProperty("preview") == null) return
        val M = androidx.car.app.navigation.model.Maneuver::class.java
        val types = M.fields.filter { it.name.startsWith("TYPE_") }.map { it.name.removePrefix("TYPE_") to it.getInt(null) }.sortedBy { it.second }
        shoot("pfeile") {
            CompositionLocalProvider(LocalDensity provides car(1f)) {
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxSize().padding(start = 20.dp, top = 130.dp, end = 20.dp)) {
                    types.forEach { (name, t) ->
                        Column(Modifier.size(150.dp, 100.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            net.prototux.aare.host.templates.TurnGlyph(t, 0, 48.dp, Color.White)
                            Text(name.lowercase(), color = Color.White, fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp), maxLines = 2)
                        }
                    }
                }
            }
        }
    }

    /** System L-061: a second call coming in during a call - its three answers. */
    @Test fun waitingCall() { if (System.getProperty("preview") == null) return
        net.prototux.aare.ui.car.Mate.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val call = net.prototux.aare.ui.car.CarCall(false, "Anna", System.currentTimeMillis() - 90_000, waiting = "Ben", canEndAndAnswer = true)
        shoot("anklopfen") {
            CompositionLocalProvider(LocalDensity provides car(1f)) {
                Box(Modifier.fillMaxSize().padding(top = 140.dp, start = 16.dp)) { net.prototux.aare.ui.car.WaitingCard(call, noActions) }
            }
        }
    }

    /** Navi L-032: the tile menu with back, keyboard and microphone at the driver's side - left-hand drive, right-hand drive. */
    @Test fun naviHubDriverSide() { if (System.getProperty("preview") == null) return
        net.prototux.aare.ui.car.Mate.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        shoot("navi-menue-fahrerseite") {
            CompositionLocalProvider(LocalDensity provides car(1f)) {
                Column(Modifier.fillMaxSize().padding(top = 130.dp, bottom = 130.dp, start = 16.dp, end = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    listOf(false, true).forEach { right ->
                        val tiles = listOf(net.prototux.aare.ui.car.navi.HubTile(Icons.Rounded.Search, "Zwischenhalt hinzufügen") {},
                            net.prototux.aare.ui.car.navi.HubTile(Icons.Rounded.Search, "Ziel ändern") {})
                        net.prototux.aare.ui.car.navi.NaviHub("Musterstadt", tiles, Modifier.weight(1f), null, {}, {}, {}, right)
                    }
                }
            }
        }
    }

    /** Navi I-033: the search keyboard while driving - typing (the count below), then resting (dimmed, the count). */
    @Test fun naviKeyboardRest() { if (System.getProperty("preview") == null) return
        net.prototux.aare.ui.car.Mate.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CarTheme(night = true) {
                CompositionLocalProvider(LocalDensity provides car(1f)) {
                    Box(Modifier.size(1920.dp, 1080.dp).background(Color(0xFF101418))) {
                        net.prototux.aare.ui.car.navi.NaviPanel(Modifier.padding(16.dp).fillMaxHeight().fillMaxWidth(0.62f)) {
                            NaviSearchPanelPreview()
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Q").performClick()
        fun save(name: String) {
            val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
            File(File("build/preview").apply { mkdirs() }, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        var open = false; var rest = false
        repeat(40) {
            rule.mainClock.advanceTimeBy(500)
            if (!open && rule.onAllNodesWithText("3 s").fetchSemanticsNodes().isNotEmpty()) { save("navi-tastatur-offen"); open = true }
            if (!rest && rule.onAllNodesWithText("Pause", substring = true).fetchSemanticsNodes().isNotEmpty()) {
                // a tap on the resting keyboard does nothing: the text stays "q"
                rule.onNodeWithText("W").performClick(); rule.mainClock.advanceTimeBy(100)
                save("navi-tastatur-pause"); rest = true
            }
        }
        rule.mainClock.autoAdvance = true
    }

    /** Navi L-030: a list in the car by use, A to Z and by distance - side by side, the pinned ones on top. */
    @Test fun placeOrders() { if (System.getProperty("preview") == null) return
        net.prototux.aare.ui.car.Mate.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val book = placeBook()
        val here = org.maplibre.spatialk.geojson.Position(longitude = 12.50, latitude = 50.70)
        shoot("ziel-listen-sortierung") {
            CompositionLocalProvider(LocalDensity provides car(1f)) {
                Row(Modifier.fillMaxSize().padding(top = 130.dp, bottom = 130.dp, start = 16.dp, end = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    listOf(net.prototux.aare.places.Places.Order.USE, net.prototux.aare.places.Places.Order.NAME, net.prototux.aare.places.Places.Order.DISTANCE).forEach { o ->
                        val cat = book.categories.first().copy(order = o)
                        net.prototux.aare.ui.car.navi.NaviPanel(Modifier.weight(1f).fillMaxHeight()) {
                            net.prototux.aare.ui.car.navi.NaviPlaces(book, cat, here, {}, {}, {}, {})
                        }
                    }
                }
            }
        }
    }

    /** Navi L-030: the list's order in the phone app (a holiday list by distance). */
    @Test fun placeOrdersPhone() { if (System.getProperty("preview") == null) return
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        net.prototux.aare.places.Places.save(ctx, placeBook().let { b -> b.copy(categories = listOf(b.categories.first().copy(order = net.prototux.aare.places.Places.Order.DISTANCE))) })
        rule.setContent {
            net.prototux.aare.ui.theme.PhoneTheme(dark = false) {
                androidx.compose.material3.Surface(Modifier.size(420.dp, 900.dp)) {
                    Box(Modifier.padding(16.dp)) { net.prototux.aare.ui.PlacesPage() }
                }
            }
        }
        rule.onNodeWithText("Urlaub").performClick(); rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/preview/phone").apply { mkdirs() }
        File(dir, "ziel-liste-sortierung.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Places for the pictures: made-up names, places far from anyone's own. */
    private fun placeBook(): net.prototux.aare.places.Places.Book {
        val now = System.currentTimeMillis(); val day = 24 * 3600_000L
        fun p(id: String, name: String, addr: String, lat: Double, lon: Double, daysAgo: List<Int>, pinned: Boolean = false) =
            net.prototux.aare.places.Places.Place(id, "u", name, addr, lat, lon, daysAgo.map { now - it * day }, pinned)
        return net.prototux.aare.places.Places.Book(listOf(net.prototux.aare.places.Places.Category("u", "Urlaub", "favorite")), listOf(
            p("1", "Ferienhaus Musterbucht", "Strandweg 1", 54.40, 11.20, listOf(3), pinned = true),
            p("2", "Ostsee Campingplatz", "Dünenweg 7", 54.10, 10.90, listOf(2, 5, 9)),
            p("3", "Alpenhütte", "Bergstraße 12", 47.50, 11.10, listOf(40)),
            p("4", "Bäckerei am Markt", "Markt 3", 50.72, 12.52, listOf(1, 4)),
            p("5", "Baumarkt Nord", "Industriestraße 8", 50.80, 12.40, emptyList()),
            p("6", "Seehotel", "Uferpromenade 2", 52.40, 13.00, listOf(120)),
            p("7", "Weingut Sonnenhang", "Rebenweg 5", 49.90, 8.00, listOf(10)),
        ))
    }

    /** Navi L-029 step F: the hints of the rolling planning top right - a stop moved (no "Neu planen"), the level too low. */
    @Test fun chargeHints() { if (System.getProperty("preview") == null) return
        net.prototux.aare.ui.car.Mate.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        shoot("ladestopp-hinweise") {
            CompositionLocalProvider(LocalDensity provides car(1f)) {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.align(Alignment.TopEnd).padding(top = 120.dp, end = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        val w = Modifier.size(620.dp, 76.dp)
                        net.prototux.aare.ui.car.navi.NaviChargeHint(net.prototux.aare.ui.car.navi.NaviChargeWatch.Result(
                            net.prototux.aare.ui.car.navi.NaviChargeWatch.Verdict.MOVED, 12.0, "Ladestopp verlegt: IONITY · 300 kW, in 84 km"), w, {}, {})
                        net.prototux.aare.ui.car.navi.NaviChargeHint(net.prototux.aare.ui.car.navi.NaviChargeWatch.Result(
                            net.prototux.aare.ui.car.navi.NaviChargeWatch.Verdict.MOVED, 15.0, "Kein weiterer Ladestopp nötig"), w, {}, {})
                        net.prototux.aare.ui.car.navi.NaviChargeHint(net.prototux.aare.ui.car.navi.NaviChargeWatch.Result(
                            net.prototux.aare.ui.car.navi.NaviChargeWatch.Verdict.TOO_LOW, 6.0), w, {}, {})
                    }
                }
            }
        }
    }

    @Test fun music() {
        if (System.getProperty("preview") == null) return
        shoot("musik") {
            CompositionLocalProvider(LocalDensity provides car(1f)) {
                Column(Modifier.fillMaxSize().padding(top = 122.dp / (183f / 160f), start = 24.dp, end = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Pill("Läuft gerade", true, {}); Pill("Bibliothek", false, {}); Pill("Playlists", false, {}); Pill("Podcasts", false, {})
                    }
                    Text("Titel des Stücks", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    Slider(0.4f, {}, Modifier.fillMaxWidth().size(width = 1200.dp, height = LocalCarDimens.current.touchTarget))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircleButton(Icons.Rounded.SkipPrevious, "", {})
                        CircleButton(Icons.Rounded.FastRewind, "", {})
                        CircleButton(Icons.Rounded.Pause, "", {}, filled = true, size = 96.dp)
                        CircleButton(Icons.Rounded.FastForward, "", {})
                        CircleButton(Icons.Rounded.SkipNext, "", {})
                        CircleButton(Icons.Rounded.QueueMusic, "", {})
                        CircleButton(Icons.Rounded.Search, "", {})
                    }
                }
            }
        }
    }

    /** The route strip of a quarter split (18.11.2026): two stops, a jam, the destination. */
    @Test fun routeStrip() {
        if (System.getProperty("preview") == null) return
        net.prototux.aare.ui.car.Mate.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val now = System.currentTimeMillis()

        val rows = listOf(
            net.prototux.aare.ui.car.navi.NaviTripPlan.Row(net.prototux.aare.ui.car.navi.NaviTripPlan.Kind.OWN, "Halt", now + 25 * 60_000L, 38_000.0),
            net.prototux.aare.ui.car.navi.NaviTripPlan.Row(net.prototux.aare.ui.car.navi.NaviTripPlan.Kind.CHARGE, "Ionity · 350 kW", now + 95 * 60_000L, 160_000.0, 14.0, 70.0, 22.0),
            net.prototux.aare.ui.car.navi.NaviTripPlan.Row(net.prototux.aare.ui.car.navi.NaviTripPlan.Kind.DESTINATION, "Ziel", now + 210 * 60_000L, 290_000.0, arrivePercent = 21.0),
        )
        val jams = listOf(Triple(9_000.0, 14_000.0, 0), Triple(60_000.0, 64_000.0, 2), Triple(200_000.0, 203_000.0, 3))
        shoot("route-strip") {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides car(1f)) {
                Box(Modifier.size(480.dp, 1080.dp)) {
                    net.prototux.aare.ui.car.navi.NaviRouteStrip(rows, jams, 290_000.0, 210 * 60.0,
                        Modifier.padding(top = 107.dp, bottom = 106.dp), incidents = listOf(3_100.0 to 9, 120_000.0 to 8, 240_000.0 to 1))
                }
            }
        }
    }

    // ── Split view matrix (18.11.2026: "Teste die geteilten Ansichten mit Mock-Daten, in allen Lagen und Apps") ──

    private val icon = androidx.car.app.model.CarIcon.ALERT

    /** A navigation app with templates (ABRP and the like) while guiding: turn card, trip estimate, both action strips. */
    private fun navTemplate(): androidx.car.app.navigation.model.NavigationTemplate {
        val step = androidx.car.app.navigation.model.Step.Builder("Dr.-Friedrichs-Ring")
            .setManeuver(androidx.car.app.navigation.model.Maneuver.Builder(androidx.car.app.navigation.model.Maneuver.TYPE_TURN_NORMAL_LEFT).setIcon(icon).build())
            .setRoad("Dr.-Friedrichs-Ring").build()
        val info = androidx.car.app.navigation.model.RoutingInfo.Builder()
            .setCurrentStep(step, androidx.car.app.model.Distance.create(60.0, androidx.car.app.model.Distance.UNIT_METERS)).build()
        val est = androidx.car.app.navigation.model.TravelEstimate.Builder(androidx.car.app.model.Distance.create(11.6, androidx.car.app.model.Distance.UNIT_KILOMETERS),
            androidx.car.app.model.DateTimeWithZone.create(System.currentTimeMillis() + 19 * 60_000, java.util.TimeZone.getDefault()))
            .setRemainingTimeSeconds(1140).build()
        val strip = androidx.car.app.model.ActionStrip.Builder()
            .addAction(androidx.car.app.model.Action.Builder().setTitle("Ziel ändern").setOnClickListener {}.build())
            .addAction(androidx.car.app.model.Action.Builder().setIcon(icon).setOnClickListener {}.build()).build()
        val mapStrip = androidx.car.app.model.ActionStrip.Builder().addAction(androidx.car.app.model.Action.PAN)
            .addAction(androidx.car.app.model.Action.Builder().setIcon(icon).setOnClickListener {}.build())
            .addAction(androidx.car.app.model.Action.Builder().setIcon(icon).setOnClickListener {}.build()).build()
        return androidx.car.app.navigation.model.NavigationTemplate.Builder().setNavigationInfo(info)
            .setDestinationTravelEstimate(est).setActionStrip(strip).setMapActionStrip(mapStrip).build()
    }

    private val fakeHost = object : net.prototux.aare.host.templates.TemplateHost {
        override val driving = true
        override val night = true
        override val appLabel = "ABRP"
        override fun contentLimit(type: Int) = 6
        override val done = object : androidx.car.app.OnDoneCallback {}
        override fun back() {}
        override fun loadIcon(icon: androidx.car.app.model.CarIcon, sizePx: Int): androidx.compose.ui.graphics.ImageBitmap? = null
        override fun showParkedOnlyMessage() {}
        @Composable override fun MapSurface(modifier: Modifier) = FakeMap(modifier)
        override fun setVisibleArea(left: Int, top: Int, right: Int, bottom: Int) {}
        override fun setStableArea(left: Int, top: Int, right: Int, bottom: Int) {}
        override fun mapScroll(dx: Float, dy: Float) {}
        override fun mapFling(vx: Float, vy: Float) {}
        override fun mapScale(focusX: Float, focusY: Float, factor: Float) {}
        override fun mapClick(x: Float, y: Float) {}
    }

    /** Something playing, with two buttons of the app's own and a queue - as Spotify or Mate Musik. */
    private class FakeSource : net.prototux.aare.media.PlaybackSource {
        override val key = "fake"
        override val packageName = "com.spotify.music"
        override val isConnection = true
        override val state = kotlinx.coroutines.flow.MutableStateFlow(net.prototux.aare.media.SourceState(
            info = net.prototux.aare.media.PlaybackInfo("com.spotify.music", "Should I Stay or Should I Go (Remastered)", "The Clash", "Combat Rock",
                null, true, 60_000, 190_000, android.os.SystemClock.elapsedRealtime()),
            showPause = true, canPrevious = true, canNext = true, canSeek = true,
            actions = listOf(net.prototux.aare.media.CustomAction("shuffle", "Mischen", null) {}, net.prototux.aare.media.CustomAction("repeat", "Wiederholen", null) {}),
            queue = listOf(net.prototux.aare.media.QueueEntry(1, "London Calling", "The Clash", false)), halfStars = 8))
        override fun playPause() {}
        override fun pause() {}
        override fun next() {}
        override fun previous() {}
        override fun seekTo(positionMs: Long) {}
        override fun skipTo(entry: net.prototux.aare.media.QueueEntry) {}
    }

    /** A guided trip for Mate Navi's strips: a stop, a charging stop, jams, the destination. */
    private fun tripRows(): List<net.prototux.aare.ui.car.navi.NaviTripPlan.Row> {
        val now = System.currentTimeMillis()
        return listOf(
            net.prototux.aare.ui.car.navi.NaviTripPlan.Row(net.prototux.aare.ui.car.navi.NaviTripPlan.Kind.OWN, "Halt", now + 25 * 60_000L, 38_000.0),
            net.prototux.aare.ui.car.navi.NaviTripPlan.Row(net.prototux.aare.ui.car.navi.NaviTripPlan.Kind.CHARGE, "Ionity · 350 kW", now + 95 * 60_000L, 160_000.0, 14.0, 70.0, 22.0),
            net.prototux.aare.ui.car.navi.NaviTripPlan.Row(net.prototux.aare.ui.car.navi.NaviTripPlan.Kind.DESTINATION, "Ziel", now + 210 * 60_000L, 290_000.0, arrivePercent = 21.0))
    }
    private val tripJams = listOf(Triple(9_000.0, 14_000.0, 0), Triple(60_000.0, 64_000.0, 2), Triple(200_000.0, 203_000.0, 3))

    /**
     * Mate Navi as far as it can be drawn here (its map needs real graphics): a magenta map, the turn card at the top left,
     * the trip tile at the bottom left above what the controls cover (P-038), and the route strips as the screen places them.
     */
    @Composable private fun MateNaviStandIn(m: Modifier) {
        val share = net.prototux.aare.ui.car.navi.LocalNaviShare.current
        val inset = net.prototux.aare.host.templates.LocalCarBottomInset.current
        Box(m) {
            FakeMap(Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.TopStart).padding(16.dp).size(width = 480.dp, height = 200.dp).background(Color(0xFF0E3B36)),
                contentAlignment = Alignment.Center) { Text("Abbiegen (Mate Navi)", color = Color.White) }
            Box(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = inset + 12.dp).size(width = 540.dp, height = 96.dp).background(Color(0xFF161B22)),
                contentAlignment = Alignment.Center) { Text("05:13   19 min   11,6 km", color = Color.White) }
            if (share >= 0.6f) net.prototux.aare.ui.car.navi.NaviRouteStrip(tripRows(), tripJams, 290_000.0, 0.0,
                Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 10.dp, bottom = inset + 40.dp).fillMaxHeight(), labels = false,
                totalM = 400_000.0, incidents = listOf(30_000.0 to 9, 150_000.0 to 8))
            if (share < 0.3f) net.prototux.aare.ui.car.navi.NaviRouteStrip(tripRows(), tripJams, 290_000.0, 210 * 60.0)
        }
    }

    private val mateNavi = net.prototux.aare.ui.car.CarAppEntry("matenavi", "net.prototux.aare", "Mate Navi", null, net.prototux.aare.ui.car.CarAppKind.MATE_NAVI)

    /** One split: [share] of the width for the navigation, on the right or left, [mate] Mate Navi or a template app. */
    /** The sign of what plays in the bar, by its name in the language the preview is drawn in (German since 18.11.2026). */
    private fun splitSign(): String = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        .getString(net.prototux.aare.R.string.idmate_split)

    private fun splitCase(share: Float, right: Boolean, mate: Boolean, picture: String? = null, then: (() -> Unit)? = null) {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        net.prototux.aare.ui.car.Mate.init(ctx)
        ctx.getSharedPreferences("mate_split", android.content.Context.MODE_PRIVATE).edit()
            .putFloat("navi_share", share).putBoolean("navi_right", right).commit()
        val naviEntry = if (mate) mateNavi else navi
        val state = net.prototux.aare.ui.car.CarUiState().apply {
            apps.add(naviEntry); apps.add(musicApp)
            nowPlaying = net.prototux.aare.ui.car.NowPlaying("music", "Should I Stay or Should I Go", "The Clash", null, true, 60_000, 190_000, 0)
            activeMediaAppId = "music"; activeNavAppId = naviEntry.id
            screen = net.prototux.aare.ui.car.CarScreen.App(naviEntry.id)
        }
        val source = FakeSource()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        val hub = net.prototux.aare.media.DefaultMediaHub(ctx, scope)
        val template = navTemplate()
        rule.setContent {
            CarTheme(night = true) {
                Box(Modifier.size(1920.dp, 1080.dp).background(Color(0xFF101418))) {
                    CompositionLocalProvider(LocalDensity provides car(1.3f)) {
                        Box(Modifier.fillMaxSize().padding(top = 122.dp / (183f / 160f * 1.3f), bottom = 121.dp / (183f / 160f * 1.3f))) {
                            net.prototux.aare.ui.car.IdmateShell(state, noActions,
                                appContent = { app, m ->
                                    // as CarRuntime: the areas in the car's measure (the shell's taken back by 1.3)
                                    val shell = LocalDensity.current
                                    val inset = net.prototux.aare.host.templates.LocalCarBottomInset.current
                                    Box(m) {
                                        CompositionLocalProvider(LocalDensity provides androidx.compose.ui.unit.Density(shell.density / 1.3f, shell.fontScale),
                                            net.prototux.aare.host.templates.LocalCarBottomInset provides inset * 1.3f) {
                                            when (app.kind) {
                                                net.prototux.aare.ui.car.CarAppKind.NAVIGATION -> net.prototux.aare.host.templates.RenderTemplate(template, fakeHost, Modifier.fillMaxSize())
                                                net.prototux.aare.ui.car.CarAppKind.MATE_NAVI -> MateNaviStandIn(Modifier.fillMaxSize())
                                                else -> Box(Modifier.fillMaxSize().background(Color(0xFF101418))) {
                                                    net.prototux.aare.media.NowPlayingView(hub, source, source.state.value) {}
                                                }
                                            }
                                        }
                                    }
                                },
                                navMap = { _, m -> FakeMap(m) },
                                overlay = {})
                        }
                    }
                    Box(Modifier.fillMaxWidth().size(1920.dp, 122.dp).background(Color(0x99606870)).align(Alignment.TopStart))
                    Box(Modifier.fillMaxWidth().size(1920.dp, 121.dp).background(Color(0x99606870)).align(Alignment.BottomStart))
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription(splitSign()).performTouchInput { longClick() }
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(2_000)
        then?.invoke()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/preview/split").apply { mkdirs() }
        val name = picture ?: "${if (mate) "mate" else "vorlage"}-${listOf("14", "13", "12", "23", "34")[listOf(0.25f, 1f / 3, 0.5f, 2f / 3, 0.75f).indexOf(share)]}-navi-${if (right) "rechts" else "links"}"
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** 18.11.2026: the roundabout's symbol by the exit the route takes - right, half right, straight on, left, back; and driving on the left. */
    @Test fun roundabouts() { if (System.getProperty("preview") == null) return
        fun m(angle: Int, cw: Boolean = false) = androidx.car.app.navigation.model.Maneuver.Builder(
            if (cw) androidx.car.app.navigation.model.Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE
            else androidx.car.app.navigation.model.Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE)
            .setRoundaboutExitNumber(1).setRoundaboutExitAngle(angle).build()
        rule.setContent {
            androidx.compose.foundation.layout.Row(Modifier.background(Color(0xFF101418)).padding(16.dp)) {
                listOf(m(90), m(135), m(180), m(270), m(360), m(90, true)).forEach { mv ->
                    Box(Modifier.padding(12.dp)) { net.prototux.aare.host.templates.RoundaboutSign(mv, 160.dp, Color.White) }
                }
            }
        }
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File("build/preview").mkdirs()
        File("build/preview/kreisverkehr.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * 18.11.2026: the bar held and drawn along shows the split as it would be (picture), let go the split takes that
     * share; and the sides swap every time, not only the first ("kann ich die linke und rechte App nicht tauschen").
     */
    @Test fun split_bar_taken() { if (System.getProperty("preview") == null) return
        val prefs = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("mate_split", android.content.Context.MODE_PRIVATE)
        splitCase(0.5f, true, true, "balken-gegriffen") {
            rule.onNodeWithTag("split-bar").performTouchInput { down(center); advanceEventTime(700); moveBy(androidx.compose.ui.geometry.Offset(-430f, 0f)) }
            rule.waitForIdle(); rule.mainClock.advanceTimeBy(400)
        }
        org.junit.Assert.assertEquals("not taken before the bar is let go", 0.5f, prefs.getFloat("navi_share", 0f), 0.01f)
        rule.onNodeWithTag("split-bar").performTouchInput { up() }
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(1_000)
        org.junit.Assert.assertEquals("three quarters for the navigation on the right", 0.75f, prefs.getFloat("navi_share", 0f), 0.01f)
        for (right in listOf(false, true, false)) {
            rule.onNodeWithTag("split-bar").performTouchInput { doubleClick() }
            rule.waitForIdle(); rule.mainClock.advanceTimeBy(1_000)
            org.junit.Assert.assertEquals("sides swapped", right, prefs.getBoolean("navi_right", true))
        }
        // a quick flick swaps as well
        rule.onNodeWithTag("split-bar").performTouchInput { down(center); advanceEventTime(40); moveBy(androidx.compose.ui.geometry.Offset(160f, 0f)); advanceEventTime(40); up() }
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(1_000)
        org.junit.Assert.assertEquals("flick swaps", true, prefs.getBoolean("navi_right", false))
        org.junit.Assert.assertEquals("a flick leaves the share", 0.75f, prefs.getFloat("navi_share", 0f), 0.01f)
    }

    /**
     * 19.11.2026: "Im Bedienfeld steht Apps, aber er hat immer noch die geteilte Ansicht auf von Navi und Musik" - with the
     * split on, "Apps" in the switcher ends the split, so the overview (the phone and the other apps) comes.
     */
    @Test fun appsEndSplit() { if (System.getProperty("preview") == null) return
        splitCase(0.5f, true, true, "apps-aus-geteilt") {
            rule.onAllNodesWithTag("split-bar").assertCountEquals(1)
            rule.onAllNodes(androidx.compose.ui.test.hasClickAction() and androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasText("Mate Navi")))
                .onFirst().performClick()
            rule.waitForIdle(); rule.mainClock.advanceTimeBy(600)
            rule.onAllNodesWithText("Apps").onFirst().performClick()
            rule.waitForIdle(); rule.mainClock.advanceTimeBy(1_000)
            rule.onAllNodesWithTag("split-bar").assertCountEquals(0)
        }
    }

    @Test fun split_mate_14_r() { if (System.getProperty("preview") != null) splitCase(0.25f, true, true) }
    @Test fun split_mate_14_l() { if (System.getProperty("preview") != null) splitCase(0.25f, false, true) }
    @Test fun split_mate_13_r() { if (System.getProperty("preview") != null) splitCase(1f / 3, true, true) }
    @Test fun split_mate_13_l() { if (System.getProperty("preview") != null) splitCase(1f / 3, false, true) }
    @Test fun split_mate_12_r() { if (System.getProperty("preview") != null) splitCase(0.5f, true, true) }
    @Test fun split_mate_12_l() { if (System.getProperty("preview") != null) splitCase(0.5f, false, true) }
    @Test fun split_mate_23_r() { if (System.getProperty("preview") != null) splitCase(2f / 3, true, true) }
    @Test fun split_mate_23_l() { if (System.getProperty("preview") != null) splitCase(2f / 3, false, true) }
    @Test fun split_mate_34_r() { if (System.getProperty("preview") != null) splitCase(0.75f, true, true) }
    @Test fun split_mate_34_l() { if (System.getProperty("preview") != null) splitCase(0.75f, false, true) }
    @Test fun split_tpl_14_r() { if (System.getProperty("preview") != null) splitCase(0.25f, true, false) }
    @Test fun split_tpl_14_l() { if (System.getProperty("preview") != null) splitCase(0.25f, false, false) }
    @Test fun split_tpl_13_r() { if (System.getProperty("preview") != null) splitCase(1f / 3, true, false) }
    @Test fun split_tpl_13_l() { if (System.getProperty("preview") != null) splitCase(1f / 3, false, false) }
    @Test fun split_tpl_12_r() { if (System.getProperty("preview") != null) splitCase(0.5f, true, false) }
    @Test fun split_tpl_12_l() { if (System.getProperty("preview") != null) splitCase(0.5f, false, false) }
    @Test fun split_tpl_23_r() { if (System.getProperty("preview") != null) splitCase(2f / 3, true, false) }
    @Test fun split_tpl_23_l() { if (System.getProperty("preview") != null) splitCase(2f / 3, false, false) }
    @Test fun split_tpl_34_r() { if (System.getProperty("preview") != null) splitCase(0.75f, true, false) }
    @Test fun split_tpl_34_l() { if (System.getProperty("preview") != null) splitCase(0.75f, false, false) }

    /** The switcher with the saved views' tile opened (18.11.2026). */
    @Test fun switcherViews() { if (System.getProperty("preview") == null) return
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val v = net.prototux.aare.ui.car.MateViews(ctx)
        v.save(net.prototux.aare.ui.car.MateViews.View("Pendeln", "split", "com.google.android.apps.maps", "com.spotify.music", true, 2f / 3))
        v.save(net.prototux.aare.ui.car.MateViews.View("Langstrecke", "navi", "com.google.android.apps.maps", null))
        v.activate(null)   // saving makes a view the start view - its start would close the switcher after 1.2 s
        scene("bereiche-ansichten", { screen = net.prototux.aare.ui.car.CarScreen.App("navi") }) {
            rule.onNodeWithText("Maps").performClick(); rule.waitForIdle()
            rule.onAllNodesWithText("Ansichten").onFirst().performClick()
        } }

    private fun capture(name: String) {
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(File("build/preview").apply { mkdirs() }, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** An app taken from the switcher and laid on the left half (18.11.2026): mid-drag, then the split it makes. */
    @Test fun dragIntoSplit() { if (System.getProperty("preview") == null) return
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.getSharedPreferences("mate_split", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        scene("ziehen-abgelegt", { screen = net.prototux.aare.ui.car.CarScreen.App("navi") }) {
            rule.onNodeWithText("Maps").performClick(); rule.waitForIdle()
            rule.onNodeWithText("Audio").performTouchInput { down(center); advanceEventTime(900); moveBy(androidx.compose.ui.geometry.Offset(-20f, -60f)) }
            rule.onNodeWithText("Audio").performTouchInput { repeat(10) { moveBy(androidx.compose.ui.geometry.Offset(-10f, -30f)) } }
            rule.waitForIdle(); rule.mainClock.advanceTimeBy(600)
            capture("ziehen-in-der-hand")
            rule.onNodeWithText("Audio").performTouchInput { up() }
        } }
}
