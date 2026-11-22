package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.maplibre.spatialk.geojson.Position
import org.junit.Test

/** I-080: a new route after leaving the guided one does not lead back into a jam known on the way. */
class NaviRerouteJamTest {
    /** A straight line north from [lon] over [km] km, a point every 500 m. */
    private fun north(lon: Double, km: Int) = (0..km * 2).map { Position(longitude = lon, latitude = 51.0 + it * 0.0045) }

    // the jam on the motorway (lon 12.0), 3 km on, northbound, 15 min
    private val jam = NaviTraffic.KnownJam(Position(longitude = 12.0, latitude = 51.0 + 6 * 0.0045),
        Position(longitude = 12.0, latitude = 51.0 + 6.2 * 0.0045), 900, 0L)
    private val back = north(12.0, 10)          // back onto the motorway, through the jam
    private val detour = north(12.01, 10)       // the parallel road, ~700 m east

    @Test fun jamOnTheWayBack() = assertEquals(900, NaviTraffic.knownDelayS(back, listOf(jam)))
    @Test fun noJamOnTheDetour() = assertEquals(0, NaviTraffic.knownDelayS(detour, listOf(jam)))

    @Test fun detourTakenWhenTheJamCostsMore() =
        // Valhalla's first: back on the motorway, 8 min; the detour 14 min - with the 15 min jam the detour wins
        assertEquals(1, NaviTraffic.pickRoute(listOf(480.0 to back, 840.0 to detour), listOf(jam)))

    @Test fun motorwayKeptWhenTheJamIsSmall() =
        assertEquals(0, NaviTraffic.pickRoute(listOf(480.0 to back, 840.0 to detour),
            listOf(NaviTraffic.KnownJam(jam.start, jam.ahead, 120, 0L))))

    @Test fun jamTheOtherWayIgnored() {
        val southbound = back.reversed()
        assertEquals(0, NaviTraffic.knownDelayS(southbound, listOf(jam)))
    }

    @Test fun noJamsValhallasFirst() = assertEquals(0, NaviTraffic.pickRoute(listOf(900.0 to back, 840.0 to detour), emptyList()))
}
