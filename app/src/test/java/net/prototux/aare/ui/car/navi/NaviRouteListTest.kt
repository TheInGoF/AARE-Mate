package net.prototux.aare.ui.car.navi

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.spatialk.geojson.Position
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** L-035: the route list in the preview - moving the destination up makes the stop before it the destination; X takes a charger out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "de-w1920dp-h1080dp-mdpi")
class NaviRouteListTest {
    @get:Rule val rule = createComposeRule()

    private fun at(lat: Double) = Position(longitude = 12.0, latitude = lat)
    private val ionity = Charger(at(51.5), 350, 6, "IONITY")
    private fun rows(): List<NaviTripPlan.Row> {
        val now = 0L
        return listOf(
            NaviTripPlan.Row(NaviTripPlan.Kind.OWN, "mate-test Halt A", now, 31_000.0, at = at(50.9)),
            NaviTripPlan.Row(NaviTripPlan.Kind.CHARGE, "IONITY · 350 kW", now, 158_000.0, 14.0, 62.0, 21.0, ionity.network, ionity.at, ionity),
            NaviTripPlan.Row(NaviTripPlan.Kind.OWN, "mate-test Halt B", now, 236_000.0, at = at(51.8)),
            NaviTripPlan.Row(NaviTripPlan.Kind.DESTINATION, "mate-test Ziel", now, 301_000.0, arrivePercent = 24.0, at = at(52.3)))
    }

    @Test fun theDestinationMovedUpSwapsWithTheLastStop() {
        net.prototux.aare.ui.car.Mate.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        var got: List<Pair<Position, String>>? = null
        var removed: Charger? = null
        rule.setContent { Column { NaviRouteList(rows(), emptyList(), true, { emptyList() }, false, {}, { _, _ -> }, { removed = it }, {}, {}, {}, { got = it }) } }
        rule.onNodeWithText("mate-test Ziel").performTouchInput {
            down(center); advanceEventTime(900)
            repeat(12) { moveBy(Offset(0f, -10f)); advanceEventTime(16) }
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf("mate-test Halt A", "mate-test Ziel", "mate-test Halt B"), got?.map { it.second })
        rule.onNodeWithContentDescriptionText("Diesen Ladestopp herausnehmen")
        assertEquals(ionity, removed)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithContentDescriptionText(d: String) =
        onNode(androidx.compose.ui.test.hasContentDescription(d)).performClick()
}
