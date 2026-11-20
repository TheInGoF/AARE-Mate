package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Test
import org.maplibre.spatialk.geojson.Position

/** F5: service areas always, rest areas only with toilets, each once. */
class NaviRestTest {
    private fun p(km: Double) = Position(longitude = km * 1000 / 111_320.0, latitude = 0.0)
    private val stops = listOf(NaviRest.Stop(p(1.0), true, null, "mate-test Raststätte"), NaviRest.Stop(p(2.0), false, true, ""),
        NaviRest.Stop(p(3.0), false, null, ""), NaviRest.Stop(p(4.0), false, false, ""))
    private val near: (Position, Double) -> List<NaviRest.Stop> = { q, r -> stops.filter { ChargerIndex.distanceM(it.at, q) <= r } }

    @Test fun whatShows() {
        val shown = NaviRest.along((0..50).map { p(it / 10.0) }, near)
        assertEquals(listOf("mate-test Raststätte", ""), shown.map { it.name })
        assertEquals(2, shown.size)
    }
}
