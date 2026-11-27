package net.prototux.aare.ui.car.navi

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.spatialk.geojson.Position
import org.robolectric.RobolectricTestRunner

/**
 * Navi E-035 (27.11.2026: "Autobahnbaustellen, da fährt man grundsätzlich erstmal durch … nur wegen einer Baustelle muss
 * er nicht umleiten, wegen einer Straßensperrung schon"): what makes a motorway closure the works' own.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36])
class NaviTrafficWorksTest {
    private fun props(roads: List<String>, vararg words: String) = JSONObject().put("roadNumbers", JSONArray(roads))
        .put("events", JSONArray(words.map { JSONObject().put("description", it) }))
    private fun north(m: Double) = Position(longitude = 11.9, latitude = 51.0 + m / 111_320.0)

    @Test fun motorwaysByTheirNumber() {
        assertTrue(NaviTraffic.onMotorway(props(listOf("A9"))))
        assertTrue(NaviTraffic.onMotorway(props(listOf("B180", "A 9"))))
        assertTrue(NaviTraffic.onMotorway(props(listOf("E51"))))
        assertFalse(NaviTraffic.onMotorway(props(listOf("B180"))))
        assertFalse(NaviTraffic.onMotorway(props(emptyList())))
        assertFalse(NaviTraffic.onMotorway(null))
    }

    @Test fun theWordsOfWorks() {
        assertTrue(NaviTraffic.worksWords(props(listOf("A9"), "Gesperrt", "Baustelle")))
        assertTrue(NaviTraffic.worksWords(props(listOf("A9"), "Verkehrsführung geändert")))
        assertFalse("a plain closure is one", NaviTraffic.worksWords(props(listOf("A9"), "Gesperrt")))
    }

    @Test fun worksNearTheClosure() {
        val closure = (0..10).map { north(it * 100.0) }
        val lanes = (0..5).map { north(1_800.0 + it * 100.0) }        // narrowed lanes starting 800 m after it
        assertTrue(NaviTraffic.near(lanes, closure, NaviTraffic.WORKS_NEAR_M))
        assertFalse(NaviTraffic.near((0..5).map { north(5_000.0 + it * 100.0) }, closure, NaviTraffic.WORKS_NEAR_M))
    }
}
