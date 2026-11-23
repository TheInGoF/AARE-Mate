package net.prototux.aare.ui.car.navi

import net.prototux.aare.host.EnergyModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.spatialk.geojson.Position

/**
 * I-003: a short trip the battery does not reach still gets a charging stop - also when leaving below the charger's
 * reserve, and when only a weaker or farther charger is near. A straight 60 km road, 180 Wh/km, 77 kWh.
 */
class ChargePlannerLowTest {
    private val km = 60
    private val pts = (0..km).map { Position(longitude = it / 111.32, latitude = 0.0) }
    private val track = ChargePlanner.Track(pts, DoubleArray(km + 1) { it * 1000.0 }, DoubleArray(km + 1) { it * 36.0 }, DoubleArray(km + 1) { it * 180.0 })
    private val car = EnergyModel.Car(capacityWh = 77_000, socPercent = 12.0)
    private fun charger(atKm: Int, kw: Int = 150, offM: Double = 0.0) =
        Charger(Position(longitude = atKm / 111.32, latitude = offM / 111_320.0), kw, 4, "mate-test")
    private fun look(vararg all: Charger): (Position, Double, Int) -> List<Charger> =
        { p, r, kw -> all.filter { it.kw >= kw && ChargerIndex.distanceM(it.at, p) <= r } }

    @Test fun belowTheReserveAtTheStart() {
        // 8 % at the start: no point is reached with 10 % - before, no stop at all
        val plan = ChargeChain.plan(track, car, 8.0, look(charger(5)), ChargePlanner.Rules()).let { ChargePlanner.Plan(it.stops(), it.arrivePercent) }
        assertEquals(1, plan.stops.size)
        assertTrue("arrives with ${plan.arrivePercent}", plan.arrivePercent >= 10.0 - 0.5)
    }

    @Test fun onlyAWeakerChargerFartherAway() {
        // 12 % at the start, the only charger 50 kW and 4 km off the road
        val plan = ChargeChain.plan(track, car, 12.0, look(charger(10, kw = 50, offM = 4_000.0)), ChargePlanner.Rules()).let { ChargePlanner.Plan(it.stops(), it.arrivePercent) }
        assertEquals(1, plan.stops.size)
    }

    @Test fun enoughNeedsNoStop() {
        val plan = ChargeChain.plan(track, car, 40.0, look(charger(5)), ChargePlanner.Rules()).let { ChargePlanner.Plan(it.stops(), it.arrivePercent) }
        assertTrue(plan.stops.isEmpty())
    }
}
