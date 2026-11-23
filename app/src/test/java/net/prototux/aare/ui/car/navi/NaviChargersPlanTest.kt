package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Test
import org.maplibre.spatialk.geojson.Position

/** System L-099: while planning, every charger of the chosen networks in a wide strip, not only the near ones. */
class NaviChargersPlanTest {
    private val route = (0..200).map { Position(10.0 + it * 0.005, 52.0) }
    private val far = Charger(Position(10.5, 52.1), 300, 8, "Ionity")       // about 11 km beside the route
    private val near = Charger(Position(10.3, 52.002), 150, 4, "Unbekannt") // close, but another network
    private val all = listOf(far, near)
    private val index: (Position, Double, Int) -> List<Charger> = { p, r, kw ->
        all.filter { it.kw >= kw && ChargerIndex.distanceM(it.at, p) <= r }
    }

    @Test fun drivingShowsOnlyTheNearOnes() {
        assertEquals(listOf(near), NaviChargers.along(route, emptyList(), index).others)
    }

    @Test fun planningShowsTheChosenNetworksFarOut() {
        val rules = ChargePlanner.Rules(preferred = setOf(far.network!!))
        val found = NaviChargers.along(route, emptyList(), index, NaviChargers.PLAN_M, 100, { !rules.outside(it) }, NaviChargers.PLAN_MAX)
        assertEquals(listOf(far), found.others)
    }

    /** Networks chosen: all of theirs, also far from the route, none of the others, the stop not twice. */
    @Test fun chosenNetworksEverywhere() {
        val faraway = Charger(Position(13.4, 54.0), 250, 6, "Ionity")
        val stop = Charger(Position(11.0, 52.0), 300, 6, "Ionity")
        val rules = ChargePlanner.Rules(preferred = setOf(far.network!!))
        val pool = listOf(far, near, faraway, stop).filter { !rules.outside(it) }
        assertEquals(listOf(far, faraway), NaviChargers.ofNetworks(pool, listOf(stop.at)))
    }
}
