package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Test
import org.maplibre.spatialk.geojson.Position

/** F3: 300 km at 180 Wh/km, 77 kWh (100 % = 428 km), a stop planned at 150 km. */
class NaviChargeWatchTest {
    private val km = 300
    private val track = ChargePlanner.Track((0..km).map { Position(longitude = it / 111.32, latitude = 0.0) },
        DoubleArray(km + 1) { it * 1000.0 }, DoubleArray(km + 1) { it * 36.0 }, DoubleArray(km + 1) { it * 180.0 })
    private val rules = ChargePlanner.Rules()
    private fun check(soc: Double, drivenKm: Double = 0.0) =
        NaviChargeWatch.check(track, drivenKm * 1000, soc, 77_000, listOf(150_000.0), rules).verdict

    @Test fun asPlanned() = assertEquals(NaviChargeWatch.Verdict.OK, check(50.0))            // 35 % used: 15 % at the stop
    @Test fun tooLow() = assertEquals(NaviChargeWatch.Verdict.TOO_LOW, check(40.0))          // 5 % at the stop
    @Test fun notNeeded() = assertEquals(NaviChargeWatch.Verdict.STOP_NOT_NEEDED, check(85.0)) // 70 % used to the end: 15 %
    @Test fun underway() = assertEquals(NaviChargeWatch.Verdict.OK, check(30.0, drivenKm = 80.0)) // 70 km left: 16 % used
}
