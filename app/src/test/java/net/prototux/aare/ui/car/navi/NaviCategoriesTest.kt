package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.maplibre.spatialk.geojson.Position

/** F6 step 3: chargers ahead on the route by the way to them, around by distance; asked by a typed word. */
class NaviCategoriesTest {
    private fun p(km: Double, northKm: Double = 0.0) = Position(longitude = km * 1000 / 111_320.0, latitude = northKm * 1000 / 111_320.0)
    private val route = (0..100).map { p(it.toDouble()) }
    private val all = listOf(Charger(p(10.0, 0.3), 150, 4, "mate-test A"), Charger(p(40.0, 0.2), 300, 4, "mate-test B"),
        Charger(p(5.0, 5.0), 150, 2, "mate-test far off the route"), Charger(p(2.0), 50, 2, "mate-test behind"))
    private val chargers: (Position, Double) -> List<Charger> = { q, r -> all.filter { ChargerIndex.distanceM(it.at, q) <= r } }
    private val none: (Position, Double) -> List<NaviRest.Stop> = { _, _ -> emptyList() }

    @Test fun aheadOnTheRoute() {
        val f = NaviCategories.find(NaviCategories.Kind.CHARGER, p(5.0), route, 5_000.0, chargers, none)
        assertEquals(listOf("mate-test A", "mate-test B"), f.map { it.charger!!.operator })
        assertEquals(5_000.0, f[0].metres, 50.0)
        assertEquals(35_000.0, f[1].metres, 50.0)
    }

    /** Each found by the way to the route point nearest to it, not "0 m" for all near the car; behind is left out. */
    @Test fun nearTheCarByItsOwnWay() {
        val near = listOf(Charger(p(5.4, 0.1), 150, 2, "mate-test 400 m"), Charger(p(5.9, 0.2), 300, 2, "mate-test 900 m"),
            Charger(p(4.5, 0.1), 150, 2, "mate-test passed"))
        val look: (Position, Double) -> List<Charger> = { q, r -> near.filter { ChargerIndex.distanceM(it.at, q) <= r } }
        val f = NaviCategories.find(NaviCategories.Kind.CHARGER, p(5.0), route, 5_000.0, look, none)
        assertEquals(listOf("mate-test 400 m", "mate-test 900 m"), f.map { it.charger!!.operator })
        assertEquals(400.0, f[0].metres, 50.0)
        assertEquals(900.0, f[1].metres, 50.0)
    }

    @Test fun aroundWithoutRoute() {
        val f = NaviCategories.find(NaviCategories.Kind.CHARGER, p(3.0), null, 0.0, chargers, none)
        assertEquals("mate-test behind", f.first().charger!!.operator)
    }

    @Test fun askedByWords() {
        assertEquals(NaviCategories.Kind.CHARGER, NaviCategories.asked("Ladesäule"))
        assertEquals(NaviCategories.Kind.CHARGER, NaviCategories.asked("lader"))
        assertEquals(NaviCategories.Kind.REST, NaviCategories.asked("rastplaz"))
        assertNull(NaviCategories.asked("Hamburg"))
        assertNull(NaviCategories.asked("Ladenburg Marktplatz"))
    }
}
