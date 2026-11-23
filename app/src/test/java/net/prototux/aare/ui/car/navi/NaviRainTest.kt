package net.prototux.aare.ui.car.navi

import net.prototux.aare.dash.DashData
import org.junit.Assert.assertEquals
import org.junit.Test

/** 18.11.2026, IDmate's rain raster: a place finds its cell as IDmate counts it (triplog/rain_radar.py cell_of). */
class NaviRainTest {
    private val step = 5.0 / 111.2
    /** A cell centre as IDmate writes it: row i, column j. */
    private fun centre(i: Long, j: Long): Pair<Double, Double> {
        val la = i * step
        return la to j * (5.0 / (111.2 * Math.cos(Math.toRadians(la))))
    }

    @Test fun placeInItsCell() {
        val (lat, lon) = centre(Math.round(50.83 / step), 200)      // near Chemnitz
        val g = NaviRain.Grid(DashData.Rain(5.0, longArrayOf(0L), arrayOf(doubleArrayOf(lat, lon, 2.0))))
        assertEquals(2, g.level(lat, lon, 0))
        assertEquals(2, g.level(lat + step * 0.4, lon - 0.02, 0))       // still inside
        assertEquals(0, g.level(lat + step * 1.0, lon, 0))               // the next row
    }

    @Test fun nearestPictureInTime() {
        val (lat, lon) = centre(1100, 200)
        val g = NaviRain.Grid(DashData.Rain(5.0, longArrayOf(0L, 900_000L),
            arrayOf(doubleArrayOf(), doubleArrayOf(lat, lon, 3.0))))
        assertEquals(0, g.level(lat, lon, 100_000))
        assertEquals(3, g.level(lat, lon, 800_000))
        assertEquals(3, g.level(lat, lon, 5_000_000))                    // past the last forecast: the last picture
    }
}
