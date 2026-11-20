package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Test
import org.maplibre.spatialk.geojson.Position

/** I-035: closures at a charger (its service area's access) are not sent with the route over it. */
class AwayFromTest {
    private fun p(lat: Double) = Position(longitude = 12.0, latitude = lat)

    @Test fun closuresNearAStopAreLeftOut() {
        val charger = p(51.0)
        val atTheCharger = p(51.005)        // ~560 m away
        val farAway = p(51.5)
        val ring = listOf(p(51.004), p(51.006), p(51.004))
        val farRing = listOf(p(51.6), p(51.61), p(51.6))
        val (points, areas) = awayFrom(listOf(p(50.0), charger, p(52.0)), listOf(atTheCharger, farAway), listOf(ring, farRing), 1500.0)
        assertEquals(listOf(farAway), points)
        assertEquals(listOf(farRing), areas)
    }
}
