package net.prototux.aare.ui.car.navi

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.spatialk.geojson.Position
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The trip plan and the map pick as the driver uses them (System L-089, L-092, L-096, L-097): tapped, what happens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "de-w1920dp-h1080dp-mdpi")
class NaviPlanUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun mate() { net.prototux.aare.ui.car.Mate.init(androidx.test.core.app.ApplicationProvider.getApplicationContext()) }

    private fun at(lat: Double) = Position(longitude = 12.0, latitude = lat)
    private val tesla = Charger(at(51.0), 250, 12, "Tesla")
    private val enbw = Charger(at(52.0), 350, 6, "EnBW")
    private val ionity = Charger(at(50.95), 300, 8, "IONITY")
    private val now = 1_000_000L
    private fun rows() = listOf(
        NaviTripPlan.Row(NaviTripPlan.Kind.CHARGE, NaviTripPlan.chargerName(tesla), now + 95 * 60_000L, 168_000.0, 12.0, 70.0, 22.0, tesla.network, tesla.at, tesla),
        NaviTripPlan.Row(NaviTripPlan.Kind.CHARGE, NaviTripPlan.chargerName(enbw), now + 225 * 60_000L, 352_000.0, 14.0, 65.0, 19.0, enbw.network, enbw.at, enbw),
        NaviTripPlan.Row(NaviTripPlan.Kind.DESTINATION, "mate-test Ziel", now + 330 * 60_000L, 470_000.0, 21.0))

    @Test fun planShowsTheStretchesAndItsButtons() {
        var stop = 0; var change = 0; var menu = 0
        rule.setContent { Column { NaviTripPlanPanel(rows(), onBack = null, onClose = {}, nowPercent = 51.0,
            onAddStop = { stop++ }, onChangeDestination = { change++ }, onMenu = { menu++ }) } }
        rule.onNodeWithText("Hier, jetzt").assertIsDisplayed()
        // the stretch between the two stops: 352 - 168 = 184 km
        rule.onNodeWithText("184 km", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Zwischenziel").performClick()
        rule.onNodeWithText("Ziel ändern").performClick()
        rule.onNodeWithText("Menü").performClick()
        assertEquals(listOf(1, 1, 1), listOf(stop, change, menu))
    }

    @Test fun aStopTappedOffersAlternativesOnlyOnRequestAndSwapsJustThatOne() {
        var picked: NaviTripPlan.Row? = null
        var asked: Charger? = null
        var chose: NaviTripPlan.Option? = null
        var swapped: Pair<Charger, Charger>? = null
        val option = NaviTripPlan.Option(ionity, -18_000.0, 16.0)
        rule.setContent {
            var sel by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Position?>(null) }
            var altFor by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Charger?>(null) }
            var altPicked by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<NaviTripPlan.Option?>(null) }
            Column { NaviTripPlanPanel(rows(), onBack = null, onClose = {}, selected = sel,
                onPick = { r -> picked = r; sel = r.charger?.at },
                altFor = altFor, altCount = if (altFor != null) 1 else 0, altPicked = altPicked, altList = if (altFor != null) listOf(option) else emptyList(),
                onAlternatives = { c -> asked = c; altFor = c }, onAltPick = { o -> chose = o; altPicked = o },
                onSwap = { a, b -> swapped = a to b }) }
        }
        // nothing about alternatives before a stop is tapped
        assertTrue(rule.onAllNodesWithText("Alternativen anzeigen").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText(NaviTripPlan.chargerName(tesla)).performClick()
        assertSame(tesla, picked?.charger)
        // tapped: the button, the list only after it
        assertTrue(rule.onAllNodesWithText("IONITY", substring = true).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("Alternativen anzeigen").performClick()
        assertSame(tesla, asked)
        rule.onNodeWithText("18 km früher", substring = true).performClick()
        assertSame(option, chose)
        rule.onNodeWithText("Nehmen").performClick()
        assertEquals(tesla to ionity, swapped)
    }

    @Test fun aChargerPickedOnTheMapOffersChargingThere() {
        var stop = 0; var go = 0
        rule.setContent { Column { NaviMapPick(NaviTripPlan.chargerName(tesla), guiding = true, onGo = { go++ }, onStop = { stop++ }, onClose = {}, charger = tesla) } }
        rule.onNodeWithText("250 kW · 12 Ladepunkte").assertIsDisplayed()
        rule.onNodeWithText("Hier laden (fester Ladestopp)").performClick()
        rule.onNodeWithText("Dahin navigieren").performClick()
        assertEquals(1 to 1, stop to go)
    }

    @Test fun aPlaceWithoutATripOffersOnlyTheWayThere() {
        rule.setContent { Column { NaviMapPick("mate-test Ort", guiding = false, onGo = {}, onStop = {}, onClose = {}) } }
        assertTrue(rule.onAllNodesWithText("Als Zwischenziel").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("Dahin navigieren").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithText(t: String, substring: Boolean = false) =
        onAllNodes(androidx.compose.ui.test.hasText(t, substring = substring))
}
