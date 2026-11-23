package net.prototux.aare.ui.car.navi

import net.prototux.aare.host.EnergyModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.spatialk.geojson.Position

/**
 * Navi O-015, Q-050: a needed stop charges at least the minutes set, up to the highest level. A straight 400 km road on
 * the equator, 180 Wh/km, 77 kWh, leaving with 95 % - one short stop at km 300 would do.
 */
class ChargeMinMinutesTest {
    private val km = 400
    private val pts = (0..km).map { Position(longitude = it / 111.32, latitude = 0.0) }
    private val track = ChargePlanner.Track(pts, DoubleArray(km + 1) { it * 1000.0 }, DoubleArray(km + 1) { it * 36.0 }, DoubleArray(km + 1) { it * 180.0 })
    private val car = EnergyModel.Car(capacityWh = 77_000, socPercent = 95.0)
    private val charger = Charger(Position(longitude = 300 / 111.32, latitude = 0.0), 150, 4, "mate-test")
    private val near: (Position, Double, Int) -> List<Charger> = { p, r, _ -> listOf(charger).filter { ChargerIndex.distanceM(it.at, p) <= r } }

    @Test fun shortStopChargesTheMinutesSet() {
        val short = ChargePlanner.replay(track, car, 95.0, listOf(charger), ChargePlanner.Rules(chargeUpTo = 80.0))
        assertTrue("without a minimum ${short.stops.single().chargeMinutes} min", short.stops.single().chargeMinutes < 10.0)
        val long = ChargePlanner.replay(track, car, 95.0, listOf(charger), ChargePlanner.Rules(chargeUpTo = 80.0, minMinutes = 10.0))
        assertTrue("with 10 min ${long.stops.single().chargeMinutes}", long.stops.single().chargeMinutes >= 10.0)
        assertTrue(long.arrivePercent > short.arrivePercent)
        val chain = ChargeChain.plan(track, car, 95.0, near, ChargePlanner.Rules(chargeUpTo = 80.0, minMinutes = 10.0)).let { ChargePlanner.Plan(it.stops(), it.arrivePercent) }
        assertTrue("planned ${chain.stops.single().chargeMinutes}", chain.stops.single().chargeMinutes >= 10.0)
    }

    @Test fun neverAboveTheHighestLevelForIt() {
        val p = ChargePlanner.replay(track, car, 95.0, listOf(charger), ChargePlanner.Rules(chargeUpTo = 40.0, minMinutes = 60.0))
        assertEquals(40.0, p.stops.single().leavePercent, 1e-9)
    }
}
