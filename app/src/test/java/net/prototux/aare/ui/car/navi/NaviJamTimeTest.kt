package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.spatialk.geojson.Position

/** O237: the time a jam costs, from TomTom's delay or the flow's level, and the time left inside it. */
class NaviJamTimeTest {
    private val free = { _: Double -> 25.0 }      // 90 km/h
    private fun incident(from: Double, to: Double, delayS: Int?) =
        NaviTraffic.Ahead(NaviTraffic.Incident(6, Position(0.0, 0.0), delayS, "Stau"), from, to, along = true)

    @Test fun incidentDelayAsGiven() {
        val j = NaviTraffic.jams(listOf(incident(5_000.0, 7_000.0, 240)), emptyList(), 0.0, free).single()
        assertEquals(240.0, j.delayS, 1e-9)
    }

    @Test fun flowEstimatedFromItsLevel() {
        // 2 km at 15 % of 90 km/h: 80 s free, 533 s slow - 453 s more
        val j = NaviTraffic.jams(emptyList(), listOf(Triple(1_000.0, 3_000.0, 2)), 0.0, free).single()
        assertEquals(2_000 / 25.0 * (1 / 0.15 - 1), j.delayS, 1.0)
    }

    @Test fun samePlaceCountsOnce() {
        val jams = NaviTraffic.jams(listOf(incident(1_000.0, 3_000.0, 300)), listOf(Triple(1_100.0, 2_900.0, 1)), 0.0, free)
        assertEquals(1, jams.size)
        assertTrue(jams.single().delayS >= 300.0)
        assertTrue(jams.single().delayS < 600.0)
    }

    @Test fun smallAndPassedOnesLeftOut() {
        assertTrue(NaviTraffic.jams(listOf(incident(1_000.0, 1_200.0, 20)), emptyList(), 0.0, free).isEmpty())
        assertTrue(NaviTraffic.jams(listOf(incident(1_000.0, 3_000.0, 300)), emptyList(), 3_500.0, free).isEmpty())
    }

    @Test fun timeLeftInside() {
        val j = NaviTraffic.Jam(1_000.0, 3_000.0, 240.0)
        assertNull(j.leftS(500.0, 25.0))
        // half way: half of (80 s free + 240 s)
        assertEquals(160.0, j.leftS(2_000.0, 25.0)!!, 1e-9)
    }
}
