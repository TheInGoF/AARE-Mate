package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.spatialk.geojson.Position

/** System I-086: a charger at the end of a short road - in and out on the same road - the car stays on the way in. */
class NaviTrackDeadEndTest {
    @Test fun staysOnTheWayIn() {
        // 0.0 -> 0.002 east (about 220 m) to the charger, then back west the same way and on north
        val line = listOf(Position(0.0, 50.0), Position(0.001, 50.0), Position(0.002, 50.0), Position(0.001, 50.00002), Position(0.0, 50.00002), Position(0.0, 50.001))
        val t = NaviTrack(line)
        val inM = t.project(50.0, 0.0002)                 // on the way in, 15 m from the start (the way out lies 2 m north of it)
        assertTrue("start $inM", inM < 30)
        // nearer the charger, a little to the side the way out would be nearest by noise: still on the way in (less than ~143 m)
        val near = t.project(50.00002, 0.0013)
        assertTrue("near the charger $near", near < 143 + 5)
        val after = t.project(50.00001, 0.0019)
        assertTrue("before the charger $after", after < 143 + 5 && after > near)
    }
}
