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

    /** Navi I-070: "er hat die Baustelle nicht als Baustelle gekennzeichnet" - the A9's works as TomTom gave them on 27.11. */
    @Test fun aNarrowedLaneIsShownAsWorks() {
        fun ahead(kind: Int, text: String, along: Boolean = true) =
            NaviTraffic.Ahead(NaviTraffic.Incident(kind, north(0.0), null, text), 43_300.0, 45_000.0, along)
        assertTrue(NaviTraffic.shownKind(ahead(7, "Fahrbahn auf zwei Fahrstreifen verengt")) == 9)
        assertTrue(NaviTraffic.shownKind(ahead(8, "Gesperrt", along = true)) == 9)
        assertTrue("a closure beside the route stays one", NaviTraffic.shownKind(ahead(8, "Gesperrt", along = false)) == 8)
        assertTrue("an accident stays one", NaviTraffic.shownKind(ahead(7, "Unfall, linker Fahrstreifen gesperrt")) == 7)
        assertTrue(NaviTraffic.shownKind(ahead(6, "Stau")) == 6)
    }

    /** I-070: long works the car is in are drawn from the car to their end, not left out once their start is passed. */
    @Test fun longWorksFromTheCarToTheirEnd() {
        val line = (0..100).map { north(it * 200.0) }                       // 20 km north
        val works = NaviTraffic.Ahead(NaviTraffic.Incident(9, north(2_000.0), null, "Bauarbeiten"), 2_000.0, 14_000.0, along = true)
        val json = org.json.JSONObject(NaviTraffic.worksStretches(line, listOf(works), drivenM = 5_000.0))
        val f = json.getJSONArray("features")
        assertTrue(f.length() == 1)
        val c = f.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
        val first = c.getJSONArray(0).getDouble(1); val last = c.getJSONArray(c.length() - 1).getDouble(1)
        assertTrue("begins at the car", Math.abs(first - north(5_000.0).latitude) < 1e-4)
        assertTrue("ends with the works", Math.abs(last - north(14_000.0).latitude) < 1e-4)
        // passed: nothing
        assertTrue(org.json.JSONObject(NaviTraffic.worksStretches(line, listOf(works), drivenM = 15_000.0)).getJSONArray("features").length() == 0)
    }

    @Test fun worksNearTheClosure() {
        val closure = (0..10).map { north(it * 100.0) }
        val lanes = (0..5).map { north(1_800.0 + it * 100.0) }        // narrowed lanes starting 800 m after it
        assertTrue(NaviTraffic.near(lanes, closure, NaviTraffic.WORKS_NEAR_M))
        assertFalse(NaviTraffic.near((0..5).map { north(5_000.0 + it * 100.0) }, closure, NaviTraffic.WORKS_NEAR_M))
    }
}
