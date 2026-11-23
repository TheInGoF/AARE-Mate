package net.prototux.aare.ui.car.navi

import net.prototux.aare.host.EnergyModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.spatialk.geojson.Position

/**
 * O205: the highest charge level holds unless nothing else reaches on - then the stop charges above it and says so.
 * A straight 520 km road on the equator, 180 Wh/km, 77 kWh, leaving with 60 %.
 */
class ChargePlannerLimitTest {
    private val km = 520
    private val pts = (0..km).map { Position(longitude = it / 111.32, latitude = 0.0) }
    private val track = ChargePlanner.Track(pts, DoubleArray(km + 1) { it * 1000.0 }, DoubleArray(km + 1) { it * 36.0 }, DoubleArray(km + 1) { it * 180.0 })
    private val car = EnergyModel.Car(capacityWh = 77_000, socPercent = 60.0)
    private fun chargersAt(vararg atKm: Int): (Position, Double, Int) -> List<Charger> {
        val all = atKm.map { Charger(Position(longitude = it / 111.32, latitude = 0.0), 150, 4, "mate-test") }
        return { p, r, _ -> all.filter { ChargerIndex.distanceM(it.at, p) <= r } }
    }

    @Test fun overTheLimitWhenNothingElseReaches() {
        val plan = ChargeChain.plan(track, car, 60.0, chargersAt(200), ChargePlanner.Rules(chargeUpTo = 80.0)).let { ChargePlanner.Plan(it.stops(), it.arrivePercent) }
        assertEquals(1, plan.stops.size)
        val st = plan.stops.single()
        assertTrue(st.overLimit)
        assertTrue("leaves with ${st.leavePercent}", st.leavePercent > 80.0)
        assertTrue("arrives with ${plan.arrivePercent}", plan.arrivePercent >= 10.0)
    }

    @Test fun limitHoldsWhenAnotherChargerReaches() {
        val plan = ChargeChain.plan(track, car, 60.0, chargersAt(200, 400), ChargePlanner.Rules(chargeUpTo = 80.0)).let { ChargePlanner.Plan(it.stops(), it.arrivePercent) }
        assertEquals(2, plan.stops.size)
        plan.stops.forEach { assertFalse(it.overLimit); assertTrue(it.leavePercent <= 80.0 + 1e-9) }
    }

    @Test fun replayKeepsTheAllowedStop() {
        val c = chargersAt(200)(Position(longitude = 200 / 111.32, latitude = 0.0), 10.0, 0).single()
        val capped = ChargePlanner.replay(track, car, 60.0, listOf(c), ChargePlanner.Rules(chargeUpTo = 80.0))
        val above = ChargePlanner.replay(track, car, 60.0, listOf(c), ChargePlanner.Rules(chargeUpTo = 80.0), setOf(c))
        assertEquals(80.0, capped.stops.single().leavePercent, 1e-9)
        assertTrue(above.stops.single().overLimit)
    }
}

/** O205: a preferred network wins over a stronger charger of another; without one in reach the strongest. */
class ChargePlannerNetworkTest {
    private val km = 400
    private val pts = (0..km).map { Position(longitude = it / 111.32, latitude = 0.0) }
    private val track = ChargePlanner.Track(pts, DoubleArray(km + 1) { it * 1000.0 }, DoubleArray(km + 1) { it * 36.0 }, DoubleArray(km + 1) { it * 180.0 })
    private val car = EnergyModel.Car(capacityWh = 77_000, socPercent = 60.0)
    private val all = listOf(Charger(Position(longitude = 190 / 111.32, latitude = 0.0), 300, 4, "IONITY GmbH"),
        Charger(Position(longitude = 160 / 111.32, latitude = 0.0), 150, 4, "EnBW mobility+ AG und Co.KG"))
    private val near: (Position, Double, Int) -> List<Charger> = { p, r, _ -> all.filter { ChargerIndex.distanceM(it.at, p) <= r } }

    @Test fun networksAreRecognised() {
        assertEquals("ionity", all[0].network); assertEquals("enbw", all[1].network)
    }

    @Test fun preferredWins() {
        val plan = ChargeChain.plan(track, car, 60.0, near, ChargePlanner.Rules(preferred = setOf("enbw"))).let { ChargePlanner.Plan(it.stops(), it.arrivePercent) }
        assertEquals("enbw", plan.stops.first().charger.network)
    }

    @Test fun strongestWithoutPreference() {
        assertEquals("ionity", ChargeChain.plan(track, car, 60.0, near, ChargePlanner.Rules()).let { ChargePlanner.Plan(it.stops(), it.arrivePercent) }.stops.first().charger.network)
        assertEquals("ionity", ChargeChain.plan(track, car, 60.0, near, ChargePlanner.Rules(preferred = setOf("tesla"))).let { ChargePlanner.Plan(it.stops(), it.arrivePercent) }.stops.first().charger.network)
    }
}

/** F3: other chargers for a stop - near it along the route, not the planned ones, preferred and strong first. */
class ChargePlannerAlternativesTest {
    private val km = 300
    private val track = ChargePlanner.Track((0..km).map { Position(longitude = it / 111.32, latitude = 0.0) },
        DoubleArray(km + 1) { it * 1000.0 }, DoubleArray(km + 1) { it * 36.0 }, DoubleArray(km + 1) { it * 180.0 })
    private fun c(atKm: Int, kw: Int, op: String) = Charger(Position(longitude = atKm / 111.32, latitude = 0.0), kw, 4, op)
    private val planned = c(150, 300, "mate-test planned")
    private val all = listOf(planned, c(140, 150, "EnBW"), c(155, 400, "mate-test strong"), c(100, 350, "mate-test too early"), c(170, 300, "mate-test too late"))
    private val near: (Position, Double, Int) -> List<Charger> = { p, r, _ -> all.filter { ChargerIndex.distanceM(it.at, p) <= r } }
    private val stop = ChargePlanner.Stop(planned, 150_000.0, 15.0, 60.0, 20.0)

    @Test fun nearAndStrongFirst() {
        val a = ChargePlanner.alternatives(track, stop, listOf(planned), near)
        assertEquals(listOf("mate-test strong", "EnBW"), a.map { it.charger.operator })
        assertTrue(a[0].shiftM > 0 && a[1].shiftM < 0)
    }

    @Test fun preferredFirst() {
        val a = ChargePlanner.alternatives(track, stop, listOf(planned), near, ChargePlanner.Rules(preferred = setOf("enbw")))
        assertEquals("EnBW", a.first().charger.operator)
    }
}

/** A stop moved earlier charges enough for the next one with its reserve (16.11.2026: the next one was reached with 8 %). */
class ChargePlannerReplayNextTest {
    private val km = 520
    private val track = ChargePlanner.Track((0..km).map { Position(longitude = it / 111.32, latitude = 0.0) },
        DoubleArray(km + 1) { it * 1000.0 }, DoubleArray(km + 1) { it * 36.0 }, DoubleArray(km + 1) { it * 180.0 })
    private val car = EnergyModel.Car(capacityWh = 77_000, socPercent = 60.0)
    private fun c(atKm: Int) = Charger(Position(longitude = atKm / 111.32, latitude = 0.0), 300, 4, "mate-test")

    @Test fun nextStopKeepsItsReserve() {
        // stops at 100 and 450 km: 80 % at the first reaches only 342 km, the second needs ~85 %
        val plan = ChargePlanner.replay(track, car, 60.0, listOf(c(100), c(450)), ChargePlanner.Rules(chargeUpTo = 80.0))
        assertTrue("arrives with ${plan.stops[1].arrivePercent}", plan.stops[1].arrivePercent >= 10.0)
        assertTrue(plan.stops[0].overLimit)
    }

}

/** O213: "nur tesla und ionity" holds - also early in the reach; others only where the chosen do not reach, then said. */
class ChargePlannerOnlyTest {
    private val km = 400
    private val pts = (0..km).map { Position(longitude = it / 111.32, latitude = 0.0) }
    private val track = ChargePlanner.Track(pts, DoubleArray(km + 1) { it * 1000.0 }, DoubleArray(km + 1) { it * 36.0 }, DoubleArray(km + 1) { it * 180.0 })
    private val car = EnergyModel.Car(capacityWh = 77_000, socPercent = 60.0)
    private fun at(k: Int, op: String) = Charger(Position(longitude = k / 111.32, latitude = 0.0), 300, 4, op)
    private fun near(all: List<Charger>): (Position, Double, Int) -> List<Charger> = { p, r, _ -> all.filter { ChargerIndex.distanceM(it.at, p) <= r } }
    private val only = ChargePlanner.Rules(preferred = setOf("tesla", "ionity"), only = true)

    @Test fun chosenEarlyBeatsOtherLate() {
        // reach ~214 km: IONITY at 80 km (early half), EnBW at 190 km (late half)
        val plan = ChargeChain.plan(track, car, 60.0, near(listOf(at(80, "IONITY GmbH"), at(190, "EnBW"))), only).let { ChargePlanner.Plan(it.stops(), it.arrivePercent) }
        assertTrue(plan.stops.isNotEmpty())
        plan.stops.forEach { assertEquals("ionity", it.charger.network) }
        assertTrue("arrives with ${plan.arrivePercent}", plan.arrivePercent >= 9.5)
    }

    @Test fun othersOnlyWhenTheChosenDoNotReach() {
        val plan = ChargeChain.plan(track, car, 60.0, near(listOf(at(190, "EnBW"))), only).let { ChargePlanner.Plan(it.stops(), it.arrivePercent) }
        assertEquals("enbw", plan.stops.single().charger.network)
        assertTrue(only.outside(plan.stops.single().charger))
    }

    @Test fun alternativesOnlyOfTheChosen() {
        val all = listOf(at(190, "IONITY GmbH"), at(175, "Shell Recharge"), at(200, "Tesla Germany GmbH"))
        val stop = ChargeChain.plan(track, car, 60.0, near(all), only).let { ChargePlanner.Plan(it.stops(), it.arrivePercent) }.stops.first()
        val alts = ChargePlanner.alternatives(track, stop, listOf(stop.charger), near(all), only)
        assertTrue(alts.isNotEmpty())
        alts.forEach { assertTrue(it.charger.network in setOf("tesla", "ionity")) }
    }
}

/** O226: a favourite wins over a stronger charger near it - among the chosen networks only ("der Filter siegt"). */
class ChargePlannerRatingTest {
    private val km = 400
    private val pts = (0..km).map { Position(longitude = it / 111.32, latitude = 0.0) }
    private val track = ChargePlanner.Track(pts, DoubleArray(km + 1) { it * 1000.0 }, DoubleArray(km + 1) { it * 36.0 }, DoubleArray(km + 1) { it * 180.0 })
    private val car = EnergyModel.Car(capacityWh = 77_000, socPercent = 60.0)
    private val tesla = Charger(Position(longitude = 190 / 111.32, latitude = 0.0), 250, 8, "Tesla Germany GmbH")
    private val ionity = Charger(Position(longitude = 191 / 111.32, latitude = 0.0), 350, 4, "IONITY GmbH")
    private val enbw = Charger(Position(longitude = 192 / 111.32, latitude = 0.0), 400, 4, "EnBW")
    private val near: (Position, Double, Int) -> List<Charger> = { p, r, _ -> listOf(tesla, ionity, enbw).filter { ChargerIndex.distanceM(it.at, p) <= r } }

    @Test fun strongestWithoutRating() {
        val plan = ChargeChain.plan(track, car, 60.0, near, ChargePlanner.Rules(preferred = setOf("tesla", "ionity"), only = true)).let { ChargePlanner.Plan(it.stops(), it.arrivePercent) }
        assertEquals("ionity", plan.stops.first().charger.network)
    }

    @Test fun favouriteWins() {
        val r = ChargePlanner.Rules(preferred = setOf("tesla", "ionity"), only = true, rating = { if (it === tesla) 5 else 0 })
        assertEquals("tesla", ChargeChain.plan(track, car, 60.0, near, r).let { ChargePlanner.Plan(it.stops(), it.arrivePercent) }.stops.first().charger.network)
    }

    @Test fun filterBeatsRating() {
        val r = ChargePlanner.Rules(preferred = setOf("tesla", "ionity"), only = true, rating = { if (it === enbw) 5 else 0 })
        assertTrue(ChargeChain.plan(track, car, 60.0, near, r).let { ChargePlanner.Plan(it.stops(), it.arrivePercent) }.stops.first().charger.network != "enbw")
    }
}
