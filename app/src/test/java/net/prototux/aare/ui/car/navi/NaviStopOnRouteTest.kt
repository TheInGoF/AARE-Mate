package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.spatialk.geojson.Position

/**
 * Navi I-075 (27.11.2026: "Ich wurde an der Ladestation vorbei navigiert … die Route ging einfach die Autobahn weiter
 * geradeaus"): whether the guided route goes to a planned charger - a motorway's line has its points far apart, the
 * distance is to the line between them.
 */
class NaviStopOnRouteTest {
    private fun at(kmEast: Double, kmNorth: Double = 0.0) =
        Position(longitude = 11.0 + kmEast / (111.32 * Math.cos(Math.toRadians(53.5))), latitude = 53.5 + kmNorth / 111.32)

    @Test fun aChargerBesideAStraightMotorwayIsNotOnIt() {
        // a straight motorway with points 5 km apart, the charger 0.4 km off its middle
        val motorway = listOf(at(0.0), at(5.0), at(10.0))
        val charger = at(2.5, 0.4)
        assertEquals(400.0, NaviStops.offLineM(motorway, charger), 5.0)
        assertTrue(NaviStops.offLineM(motorway, charger) > STOP_OFF_ROUTE_M)
    }

    @Test fun aRouteOverTheChargerReachesIt() {
        // off at the exit, to the charger's road, back on
        val over = listOf(at(0.0), at(2.0), at(2.3, 0.3), at(2.5, 0.35), at(2.8, 0.3), at(3.0), at(10.0))
        assertTrue(NaviStops.offLineM(over, at(2.5, 0.4)) < STOP_OFF_ROUTE_M)
        assertEquals(Double.MAX_VALUE, NaviStops.offLineM(emptyList(), at(0.0)), 0.0)
    }
}
