package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.spatialk.geojson.Position

/** 19.11.2026: closures shown on the strip only when they run along the route, not a service area's beside it. */
class OnOurWayTest {
    private fun a(kind: Int, along: Boolean) = NaviTraffic.Ahead(NaviTraffic.Incident(kind, Position(12.0, 50.0), null, "mate-test", 0), 1000.0, 1200.0, along)

    @Test fun closuresOnlyAlongTheRoute() {
        assertFalse(NaviTraffic.onOurWay(a(8, along = false)))
        assertFalse(NaviTraffic.onOurWay(a(7, along = false)))
        assertTrue(NaviTraffic.onOurWay(a(8, along = true)))
        // an accident is often one point: shown as before
        assertTrue(NaviTraffic.onOurWay(a(1, along = false)))
        assertTrue(NaviTraffic.onOurWay(a(9, along = false)))
    }

    /** 19.11.2026: a lane of an exit closed, the route goes straight on past it - not shown; taking that exit - shown. */
    @Test fun anExitPassedIsNoMatter() {
        val exit = NaviTraffic.Ahead(NaviTraffic.Incident(7, Position(12.0, 50.0), null, "A 4: Ausfahrt Glauchau-Ost, Fahrstreifen gesperrt", 0), 20_000.0, 20_400.0, along = true)
        val passed = NaviTraffic.besideRamps(listOf(exit), rampsAtM = listOf(5_000.0, 60_000.0)).single()
        assertFalse(NaviTraffic.onOurWay(passed))
        val taken = NaviTraffic.besideRamps(listOf(exit), rampsAtM = listOf(20_300.0)).single()
        assertTrue(NaviTraffic.onOurWay(taken))
        // a lane closed on the motorway itself, nothing of a ramp in its words: shown
        val main = NaviTraffic.Ahead(NaviTraffic.Incident(7, Position(12.0, 50.0), null, "A 4: Fahrstreifen gesperrt, Baustelle", 0), 20_000.0, 22_000.0, along = true)
        assertTrue(NaviTraffic.onOurWay(NaviTraffic.besideRamps(listOf(main), emptyList()).single()))
    }
}
