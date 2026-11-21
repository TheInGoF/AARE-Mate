package net.prototux.aare.dash

import net.prototux.aare.host.EnergyModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wind put into IDmate's range area by Mate (Navi N-019 step 1, E-029). */
class RangeWindTest {
    private val car = EnergyModel.Car(capacityWh = 77_000, socPercent = 60.0)

    @Test fun meanOfThePoints() {
        val w = RangeWind.mean(listOf(36.0 to 270.0, 36.0 to 270.0))
        assertNotNull(w)
        assertEquals(270.0, w!!.fromDeg, 1e-6)
        assertEquals(10.0, w.ms, 1e-6)
        // from west and from east cancel out
        assertEquals(0.0, RangeWind.mean(listOf(36.0 to 270.0, 36.0 to 90.0))!!.ms, 1e-6)
    }

    @Test fun furtherWithTheWindBehind() {
        val west = RangeWind.Wind(270.0, 10.0)
        val east = RangeWind.factor(90.0, west, car)       // driving east, the wind from behind
        val toWest = RangeWind.factor(270.0, west, car)    // into the wind
        val north = RangeWind.factor(0.0, west, car)       // across
        assertTrue("behind %.3f, against %.3f".format(east, toWest), east > 1.0 && toWest < 1.0)
        assertEquals(1.0, north, 1e-6)
        assertTrue(toWest >= RangeWind.MIN_FACTOR && east <= RangeWind.MAX_FACTOR)
    }

    @Test fun ringStretchedFromTheCentre() {
        val c = doubleArrayOf(10.0, 50.0)
        val ring = listOf(doubleArrayOf(11.0, 50.0), doubleArrayOf(10.0, 51.0), doubleArrayOf(9.0, 50.0), doubleArrayOf(10.0, 49.0))
        val out = RangeWind.deform(ring, c, RangeWind.Wind(270.0, 10.0), car)
        assertTrue("east %.3f".format(out[0][0]), out[0][0] > 11.0)       // downwind further
        assertTrue("west %.3f".format(out[2][0]), out[2][0] > 9.0)        // upwind nearer
        assertEquals(51.0, out[1][1], 1e-6)                              // across unchanged
        // calm: as it was
        val calm = RangeWind.deform(ring, c, RangeWind.Wind(270.0, 1.0), car)
        assertEquals(11.0, calm[0][0], 1e-12)
    }
}
