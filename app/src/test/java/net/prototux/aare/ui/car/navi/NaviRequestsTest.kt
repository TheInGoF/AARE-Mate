package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Navigation URIs handed to Mate Navi from outside. */
class NaviRequestsTest {
    @Test fun text() {
        assertEquals("Alexanderplatz", NaviRequests.fromUri("geo:0,0?q=Alexanderplatz")!!.query)
        assertEquals("Hamburg Hbf", NaviRequests.fromUri("google.navigation:q=Hamburg+Hbf")!!.query)
        assertEquals("Lübeck", NaviRequests.fromUri("google.navigation:q=L%C3%BCbeck")!!.query)
    }

    @Test fun coordinates() {
        val a = NaviRequests.fromUri("geo:52.52,13.41")!!
        assertEquals(52.52, a.at!!.latitude, 1e-9); assertEquals(13.41, a.at!!.longitude, 1e-9); assertNull(a.query)
        val b = NaviRequests.fromUri("geo:0,0?q=52.5,13.4(mate-test Bäcker)")!!
        assertEquals("mate-test Bäcker", b.name); assertEquals(13.4, b.at!!.longitude, 1e-9)
        assertEquals(53.87, NaviRequests.fromUri("google.navigation:ll=53.87,10.69")!!.at!!.latitude, 1e-9)
        assertEquals(10.69, NaviRequests.fromUri("google.navigation:q=53.87,10.69")!!.at!!.longitude, 1e-9)
    }

    @Test fun nothing() {
        assertNull(NaviRequests.fromUri("geo:0,0"))
        assertNull(NaviRequests.fromUri(""))
        assertNull(NaviRequests.fromUri("geo:0,0?q="))
    }
}

/** The same name close together once, in another town kept. */
class NaviSearchOneEachTest {
    private fun h(n: String, lat: Double) = NaviSearch.Hit(n, "", org.maplibre.spatialk.geojson.Position(longitude = 10.0, latitude = lat))
    @Test fun sameNameNearOnce() {
        val r = NaviSearch.oneEach(listOf(h("Hamburg Hauptbahnhof", 53.5530), h("Hamburg Hauptbahnhof", 53.5535), h("Hauptbahnhof", 53.55), h("Hamburg Hauptbahnhof", 52.0)))
        assertEquals(3, r.size)
        assertEquals(53.5530, r[0].at.latitude, 1e-9)
    }
}
