package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Test

/** 19.11.2026: the level at the next charger - the way up to it only, not to the destination. */
class PartsUntilTest {
    @Test fun onlyTheWayToTheCharger() {
        // steps: 10 km in 600 s (60 km/h), 100 km in 3600 s (100 km/h), 50 km in 1800 s; 4 km left of the first
        val steps = listOf(10_000.0 to 600.0, 100_000.0 to 3_600.0, 50_000.0 to 1_800.0)
        val parts = NaviCharge.partsUntil(steps, 4_000.0, 54_000.0)       // the charger 54 km on: 4 km + 50 km of the second
        assertEquals(2, parts.size)
        assertEquals(60.0, parts[0].first, 0.01); assertEquals(240.0, parts[0].second, 0.01)
        assertEquals(100.0, parts[1].first, 0.01); assertEquals(1_800.0, parts[1].second, 0.01)
        // past the route's end: the whole of it
        assertEquals(3, NaviCharge.partsUntil(steps, 4_000.0, 1e9).size)
    }
}
