package net.prototux.aare.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.spatialk.geojson.Position

/**
 * System I-150 (27.11.2026: "der Mini-Pfeil zeigt die ganze Zeit Richtung Norden … es läuft immer nur ein bisschen mit und
 * dann pausiert das wieder"): the car carried on between places, its direction from its moves.
 */
class DashDriveTest {
    private fun east(m: Double) = Position(longitude = 12.5 + m / (111_320.0 * Math.cos(Math.toRadians(52.5))), latitude = 52.5)

    @Test fun carriedOnBetweenPlacesAndHeadingEast() {
        val d = DashDrive()
        // 36 m/s (130 km/h) to the east, a place every 5 s, no heading from the stick
        d.place(east(0.0), null, 0)
        d.place(east(180.0), null, 5_000)
        val (p, h) = d.at(7_500)!!
        assertEquals(90.0, h!!, 1.0)
        // half way to the next place, not standing at the last one
        assertEquals(270.0, DashDrive.distanceM(east(0.0), p), 15.0)
        // and on after the next one without a stop
        d.place(east(360.0), null, 10_000)
        val later = d.at(12_000)!!.first
        assertTrue(DashDrive.distanceM(east(0.0), later) > 400)
    }

    @Test fun aNewPlaceCorrectsSoftly() {
        val d = DashDrive()
        d.place(east(0.0), null, 0); d.place(east(180.0), null, 5_000)
        val before = d.at(9_999)!!.first
        d.place(east(330.0), null, 10_000)                          // a bit behind what was carried on
        val right = d.at(10_000)!!.first
        assertTrue("no jump back", DashDrive.distanceM(before, right) < 5)
        val settled = d.at(11_100)!!.first
        assertEquals(330.0 + 30.0 * 1.1, DashDrive.distanceM(east(0.0), settled), 25.0)
    }

    @Test fun standingStaysAndTheSticksHeadingCounts() {
        val d = DashDrive()
        d.place(east(0.0), 45.0, 0)
        assertEquals(DashDrive.distanceM(east(0.0), d.at(4_000)!!.first), 0.0, 0.5)
        d.place(east(150.0), 80.0, 5_000)
        assertEquals(80.0, d.at(5_500)!!.second!!, 1e-9)
    }
}
