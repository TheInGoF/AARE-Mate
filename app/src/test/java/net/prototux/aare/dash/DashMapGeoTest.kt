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
}
