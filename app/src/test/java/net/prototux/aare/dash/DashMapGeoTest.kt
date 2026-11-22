package net.prototux.aare.dash

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** System L-079: IDmate's data made into the dashboard map's GeoJSON, as IDmate's page drew it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DashMapGeoTest {
    private fun square(lon: Double, lat: Double, d: Double) =
        listOf(doubleArrayOf(lon - d, lat - d), doubleArrayOf(lon + d, lat - d), doubleArrayOf(lon + d, lat + d), doubleArrayOf(lon - d, lat + d), doubleArrayOf(lon - d, lat - d))
    private fun polygon(ring: List<DoubleArray>, contour: Double) = JSONObject().put("type", "Feature").put("properties", JSONObject().put("contour", contour))
        .put("geometry", JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(JSONArray().apply { ring.forEach { put(JSONArray().put(it[0]).put(it[1])) } })))

    @Test fun smoothRingStaysClosedAndRound() {
        val r = DashMapGeo.smoothRing(square(13.0, 52.0, 0.5), 2)
        assertEquals(r.first()[0], r.last()[0], 0.0); assertEquals(r.first()[1], r.last()[1], 0.0)
        assertEquals("4 corners, two passes: 16 points and the closing one", 17, r.size)
        r.forEach { assertTrue("inside the square", it[0] in 12.5..13.5 && it[1] in 51.5..52.5) }
    }

    @Test fun smoothLineKeepsItsEnds() {
        val l = listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 1.0), doubleArrayOf(2.0, 0.0))
        val s = DashMapGeo.smoothLine(l, 2)
        assertEquals(0.0, s.first()[0], 0.0); assertEquals(2.0, s.last()[0], 0.0)
    }

    @Test fun twoContoursMakeOuterInnerAndShade() {
        val gj = JSONObject().put("type", "FeatureCollection").put("features", JSONArray()
            .put(polygon(square(13.0, 52.0, 1.0), 300.0)).put(polygon(square(13.0, 52.0, 0.5), 200.0)))
        val a = DashMapGeo.area(gj)!!
        assertEquals(1, JSONObject(a.outer).getJSONArray("features").length())
        assertEquals("one ring only: the expected range", 0, JSONObject(a.inner).getJSONArray("features").length())
        val shade = JSONObject(a.shade).getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals("the world and one hole", 2, shade.length())
        val box = a.box!!
        assertTrue(box[0] >= 12.0 && box[2] <= 14.0 && box[1] >= 51.0 && box[3] <= 53.0)
    }

    @Test fun windAreaTakesTheOutline() {
        val line = JSONObject().put("type", "Feature").put("properties", JSONObject().put("kind", "line"))
            .put("geometry", JSONObject().put("type", "MultiLineString").put("coordinates", JSONArray().put(JSONArray().apply {
                square(10.0, 50.0, 0.3).forEach { put(JSONArray().put(it[0]).put(it[1])) } })))
        val fill = JSONObject().put("type", "Feature").put("properties", JSONObject().put("kind", "fill")).put("geometry", JSONObject().put("type", "Polygon").put("coordinates", JSONArray()))
        val a = DashMapGeo.area(JSONObject().put("features", JSONArray().put(fill).put(line)))!!
        assertEquals(1, JSONObject(a.outer).getJSONArray("features").length())
        assertEquals(2, JSONObject(a.shade).getJSONObject("geometry").getJSONArray("coordinates").length())
    }

    @Test fun nothingToDraw() {
        assertNull(DashMapGeo.area(JSONObject().put("features", JSONArray())))
        assertNull(DashMapGeo.area(JSONObject()))
        assertEquals(DashMapGeo.EMPTY, DashMapGeo.shadeOf(emptyList()))
    }

    @Test fun windArrowsByClassPointingWhereItBlows() {
        val pts = JSONArray().put(JSONObject().put("lat", 52.0).put("lon", 13.0).put("kmh", 10.0).put("dir", 270.0))
            .put(JSONObject().put("lat", 52.1).put("lon", 13.1).put("kmh", 50.0).put("dir", 90.0))
            .put(JSONObject().put("lat", 52.2).put("lon", 13.2))   // no values: left out
        val w = DashMapGeo.wind(pts)
        assertEquals(4, w.size)
        val calm = JSONObject(w[0]).getJSONArray("features")
        assertEquals(1, calm.length())
        assertEquals("from the west, blowing east", 90.0, calm.getJSONObject(0).getJSONObject("properties").getDouble("rot"), 0.0)
        assertEquals(1, JSONObject(w[3]).getJSONArray("features").length())
        assertEquals(0, JSONObject(w[1]).getJSONArray("features").length())
    }

    @Test fun rainCellsByGradeAndTheFrameOfNow() {
        val r = JSONObject().put("cell_km", 5.0).put("frames", JSONArray()
            .put(JSONObject().put("kind", "past").put("cells", JSONArray()))
            .put(JSONObject().put("kind", "now").put("cells", JSONArray().put(JSONArray().put(52.0).put(13.0).put(2)).put(JSONArray().put(52.0).put(13.1).put(9))))
            .put(JSONObject().put("kind", "forecast").put("cells", JSONArray())))
        val now = DashMapGeo.rainNow(r)
        assertEquals("now", now!!.getString("kind"))
        val g = DashMapGeo.rain(now, 5.0)
        assertEquals(1, JSONObject(g[1]).getJSONArray("features").length())
        assertEquals("grade 9 left out", 0, JSONObject(g[0]).getJSONArray("features").length() + JSONObject(g[2]).getJSONArray("features").length())
        assertNull(DashMapGeo.rainNow(JSONObject().put("frames", JSONArray())))
    }

    @Test fun trailNeedsTwoPoints() {
        assertEquals(DashMapGeo.EMPTY, DashMapGeo.trail(JSONArray().put(JSONArray().put(52.0).put(13.0))))
        assertNotNull(JSONObject(DashMapGeo.trail(JSONArray().put(JSONArray().put(52.0).put(13.0)).put(JSONArray().put(52.1).put(13.1)))).optJSONArray("features"))
    }

    private fun lineFeature(vararg parts: List<DoubleArray>) = JSONObject().put("type", "Feature").put("properties", JSONObject().put("kind", "line"))
        .put("geometry", JSONObject().put("type", "MultiLineString").put("coordinates", JSONArray().apply {
            parts.forEach { p -> put(JSONArray().apply { p.forEach { put(JSONArray().put(it[0]).put(it[1])) } }) } }))

    @Test fun ferriesWithTheirUnionSeaAndPorts() {
        // the area behind the port of arrival, away from the port it left
        val area = JSONObject().put("type", "FeatureCollection").put("features", JSONArray().put(lineFeature(square(11.6, 54.8, 0.2))))
        val ferry = JSONObject().put("from", JSONArray().put(54.50).put(11.22)).put("to", JSONArray().put(54.65).put(11.35))
            .put("name", "mate-test-Faehre").put("line", JSONArray().put(JSONArray().put(54.50).put(11.22)).put(JSONArray().put(54.65).put(11.35)))
            .put("area", area)
        val answer = JSONObject().put("status", "ok").put("ferries", JSONArray().put(ferry)).put("union", area)
        val f = DashMapGeo.ferries(answer)!!
        assertEquals(1, f.count)
        assertEquals("the union only, not each area again", 1, JSONObject(f.outline).getJSONArray("features").length())
        assertEquals("the area behind the ferry, for the shade", 1, f.rings.size)
        val sea = JSONObject(f.sea).getJSONArray("features").getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals("[lat, lon] turned into [lon, lat]", 11.22, sea.getJSONArray(0).getDouble(0), 1e-9)
        val to = JSONObject(f.portsTo).getJSONArray("features").getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals(11.35, to.getDouble(0), 1e-9); assertEquals(54.65, to.getDouble(1), 1e-9)
        assertTrue(f.box!![0] <= 11.22 && f.box!![2] >= 11.7)
        assertNull("still searching", DashMapGeo.ferries(JSONObject().put("status", "running")))
        assertEquals("none found", 0, DashMapGeo.ferries(JSONObject().put("status", "ok").put("ferries", JSONArray()))!!.count)
    }

    private fun ferry(from: DoubleArray, to: DoubleArray, line: List<DoubleArray>, area: JSONObject?) = JSONObject()
        .put("from", JSONArray().put(from[1]).put(from[0])).put("to", JSONArray().put(to[1]).put(to[0])).put("name", "mate-test-Faehre")
        .put("line", JSONArray().apply { line.forEach { put(JSONArray().put(it[1]).put(it[0])) } }).put("area", area)

    /** 22.11.2026: "nur reale Verbindungen … keine Hin- und Herrouten" - round trips, a pair twice, an area away from its port. */
    @Test fun onlyRealCrossings() {
        val rostock = doubleArrayOf(12.10, 54.15); val gedser = doubleArrayOf(11.93, 54.57); val mid = doubleArrayOf(12.0, 54.36)
        val atGedser = JSONObject().put("type", "FeatureCollection").put("features", JSONArray().put(lineFeature(square(11.8, 54.75, 0.3))))
        val atRostock = JSONObject().put("type", "FeatureCollection").put("features", JSONArray().put(lineFeature(square(12.3, 53.9, 0.3))))
        val loop = ferry(rostock, doubleArrayOf(12.11, 54.16), listOf(rostock, gedser, doubleArrayOf(12.11, 54.16)), null)
        val there = ferry(rostock, gedser, listOf(rostock, mid, gedser), atGedser)
        val back = ferry(gedser, rostock, listOf(gedser, mid, rostock), null)
        val wrongArea = ferry(rostock, doubleArrayOf(13.15, 55.37), listOf(rostock, doubleArrayOf(13.15, 55.37)), atRostock)
        val f = DashMapGeo.ferries(JSONObject().put("status", "ok").put("ferries", JSONArray().put(loop).put(there).put(back).put(wrongArea)))!!
        assertEquals("Rostock-Gedser once and the one to Trelleborg", 2, f.count)
        assertEquals("only the area at Gedser", 1, f.rings.size)
        assertTrue(f.rings[0].all { it[1] > 54.4 })
        // the area behind Gedser with a part back around Rostock (IDmate I-073): that part goes
        val both = JSONObject().put("type", "FeatureCollection").put("features", JSONArray().put(lineFeature(square(11.8, 54.75, 0.3), square(12.1, 54.0, 0.3))))
        val g = DashMapGeo.ferries(JSONObject().put("status", "ok").put("ferries", JSONArray().put(ferry(rostock, gedser, listOf(rostock, mid, gedser), both))).put("union", both))!!
        assertEquals("only the part in Denmark", 1, g.rings.size)
        assertTrue(g.rings[0].all { it[1] > 54.4 })
        assertTrue(DashMapGeo.roundTrip(rostock, doubleArrayOf(12.3, 54.2), listOf(rostock, gedser, doubleArrayOf(12.3, 54.2))))
    }

    /** IDmate's shade.setFerry: the ferries' areas cut out of the same shade, unless one touches a ring already cut. */
    @Test fun ferryAreasAreCutOutOfTheShade() {
        val range = DashMapGeo.smoothRing(square(10.7, 53.9, 0.5), 2)
        val far = DashMapGeo.smoothRing(square(13.1, 55.6, 0.4), 2)
        val touching = DashMapGeo.smoothRing(square(11.0, 53.9, 0.5), 2)
        fun holes(s: String) = JSONObject(s).getJSONObject("geometry").getJSONArray("coordinates").length()
        assertEquals("the world, the range and the area beyond the sea", 3, holes(DashMapGeo.shadeOf(listOf(range), listOf(far))))
        assertEquals("overlapping holes break the fill: left out", 2, holes(DashMapGeo.shadeOf(listOf(range), listOf(touching))))
        assertEquals("ferries without a range area still cut", 2, holes(DashMapGeo.shadeOf(emptyList(), listOf(far))))
    }

    /** I-070: a river IDmate's outline cuts (the Elbe at Hamburg) comes as an open line - drawn as IDmate draws it, not shaded. */
    @Test fun openLineOfTheOutlineIsDrawnNotShaded() {
        val river = listOf(doubleArrayOf(9.6, 53.6), doubleArrayOf(9.9, 53.55), doubleArrayOf(10.1, 53.5))
        val gj = JSONObject().put("type", "FeatureCollection").put("features", JSONArray().put(lineFeature(square(10.0, 53.5, 1.0), river)))
        val a = DashMapGeo.area(gj)!!
        assertEquals("ring and river", 2, JSONObject(a.outer).getJSONArray("features").length())
        assertEquals("the world and the ring's hole only", 2, JSONObject(a.shade).getJSONObject("geometry").getJSONArray("coordinates").length())
    }
}
