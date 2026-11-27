package net.prototux.aare.ui.car.navi

import net.prototux.aare.host.EnergyModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.spatialk.geojson.Position

/**
 * Navi L-056 (27.11.2026: "Das lohnt sich, wenn du mit 120 bis nach Potsdam fährst … als wenn du mit 150 bis nach
 * Beelitz fährst"; "auf der Autobahn nicht unter 120 … nur wenn ich den Ladestopp vielleicht nicht erreiche: 110 oder
 * 100, abgestuft"): the speed per stretch from stop to stop, the quickest plan of those weighed, never below 120 when
 * planning, one step at a time on the way. Straight made-up motorways on the equator, a point every km.
 */
class ChargeSpeedTest {
    private val w = ChargeTestWorld
    private val car = EnergyModel.Car(capacityWh = 77_000, socPercent = 80.0)
    private val rules = ChargePlanner.Rules(atCharger = 10.0, atDestination = 10.0, chargeUpTo = 80.0)

    /** A motorway of [km] km where the route says [base] km/h, without a limit (or [limit] everywhere). */
    private fun trackAt(km: Int, base: Double, fn: ((Double, Position, Double) -> Double)?): ChargePlanner.Track {
        val pts = (0..km).map { w.pos(it.toDouble()) }
        val kmh = DoubleArray(pts.size) { i -> if (i == 0) base else fn?.invoke(i * 1000.0, pts[i], base) ?: base }
        return ChargeEnergy.track(pts, kmh, car)
    }

    private fun advise(km: Int, base: Double, chargers: List<Charger>, limit: Int? = NaviLimits.UNLIMITED) = ChargeSpeed.advise(
        trackAt = { fn -> trackAt(km, base, fn) }, limitFor = { { _ -> limit } },
        plan = { t -> ChargeChain.plan(t, car, car.socPercent, w.near(chargers), rules) },
        solve = { t, ch -> ChargeChain.solve(t, ChargeCorridor.candidates(t, { _, _, _ -> emptyList() }, rules, 0, emptyList(), ch), car, car.socPercent, rules) })

    private val everyFifty = (1..12).map { w.charger(it * 50.0, 300) }

    @Test fun aLongMotorwayGetsTheQuickestOfTheSpeedsWeighed() {
        val a = advise(600, 120.0, everyFifty); assertNotNull(a); a!!
        assertTrue("weighed several", a.uniform.size >= 2)
        assertTrue("never below 120 when planning: ${a.kmh}", a.kmh.all { it in ChargeSpeed.SPEEDS })
        assertTrue("reaches the destination", a.plan.shortPercent <= ChargeChain.SHORT_OK)
        // no speed for the whole trip is quicker by more than the tie
        assertTrue(a.describe(), a.uniform.all { it.totalMinutes >= a.plan.tripMinutes - ChargeSpeed.TIE_MIN - 0.1 })
        assertEquals("a stretch per stop and the last", a.plan.links.size + 1, a.kmh.size)
    }

    @Test fun noOpenRoadNoAdvice() {
        // a country road (80 by the route): no speed changes it
        assertNull(advise(200, 80.0, everyFifty))
        // a limit of 120 everywhere: the speeds above it change nothing
        assertNull(advise(300, 130.0, everyFifty, limit = 120))
    }

    @Test fun theSpeedOnlyWhereTheRoadAllowsIt() {
        assertEquals(150.0, ChargeSpeed.speedAt(150, 130.0, NaviLimits.UNLIMITED), 1e-9)
        assertEquals(130.0, ChargeSpeed.speedAt(150, 130.0, 130), 1e-9)
        assertEquals("unknown limit: the route's own", 130.0, ChargeSpeed.speedAt(150, 130.0, null), 1e-9)
        assertEquals("a town stays as it is", 50.0, ChargeSpeed.speedAt(150, 50.0, NaviLimits.UNLIMITED), 1e-9)
        val f = ChargeSpeed.legs(listOf(150, 120), listOf(100_000.0, 300_000.0)) { NaviLimits.UNLIMITED }
        assertEquals(150.0, f(50_000.0, w.pos(50.0), 130.0), 1e-9)
        assertEquals(120.0, f(200_000.0, w.pos(200.0), 130.0), 1e-9)
    }

    @Test fun stepByStepOnlyWhenTheStopIsInDoubt() {
        // arrival by speed: 1 % more for each 10 km/h slower
        val arrive = { base: Double -> { v: Int -> base + (130 - v) / 10.0 } }
        assertNull("enough: no change", ChargeSpeed.stepDown(130, 5.0, arrive(8.0)))
        assertEquals(110, ChargeSpeed.stepDown(130, 5.0, arrive(3.5)))
        assertEquals(100, ChargeSpeed.stepDown(130, 5.0, arrive(2.5)))
        assertEquals("nothing helps: the slowest", 100, ChargeSpeed.stepDown(130, 5.0, arrive(-5.0)))
        assertNull("at 100 already: no lower", ChargeSpeed.stepDown(100, 5.0, arrive(-5.0)))
        assertTrue("slower takes less per km", ChargeSpeed.energyRatio(130, 110, car) < 1.0)
    }

    @Test fun theSpeedsFollowTheStopsByPlace() {
        val stops = listOf(ChargePlanner.Stop(w.charger(200.0), 200_000.0, 15.0, 70.0, 20.0),
            ChargePlanner.Stop(w.charger(420.0), 420_000.0, 15.0, 60.0, 18.0))
        val legs = ChargeSpeed.Legs(listOf(stops[0].charger.at to 140, stops[1].charger.at to 120), 150)
        assertEquals(140, legs.before(stops[0].charger.at))
        assertEquals(150, legs.before(null))
        assertEquals("a charger the plan did not have: the slowest", 120, legs.before(w.pos(300.0)))
        assertEquals(listOf(140, 120, 150), legs.of(stops))
        // the plan on the way, the first stop behind: an own stop before the second charger lies on its stretch
        val rows = listOf(NaviTripPlan.Row(NaviTripPlan.Kind.OWN, "Halt", 0, 0.0),
            NaviTripPlan.Row(NaviTripPlan.Kind.CHARGE, "B", 0, 0.0, charger = stops[1].charger, at = stops[1].charger.at),
            NaviTripPlan.Row(NaviTripPlan.Kind.DESTINATION, "Ziel", 0, 0.0))
        assertEquals(listOf(120, 120, 150), NaviTripPlan.legSpeeds(rows, legs).map { it.legKmh })
    }

    @Test fun theSpeedsOnTheRouteAsPlanned() {
        val a = ChargeSpeed.Advice(listOf(140, 120, 150), listOf(200_000.0, 420_000.0, 600_000.0),
            ChargeChain.Result(emptyList(), 20.0, 0.0, 0.0, 300.0, 0, 0L), emptyList())
        // the same stops a little farther along (the way to the chargers): the same speeds
        assertEquals(listOf(140, 120, 150), a.along(listOf(201_000.0, 422_000.0), 603_000.0).kmh)
        // one stop fewer: each stretch the speed its end fell in before
        assertEquals(listOf(120, 150), a.along(listOf(400_000.0), 600_000.0).kmh)
        val rows = listOf(NaviTripPlan.Row(NaviTripPlan.Kind.OWN, "Halt", 0, 0.0), NaviTripPlan.Row(NaviTripPlan.Kind.CHARGE, "A", 0, 0.0),
            NaviTripPlan.Row(NaviTripPlan.Kind.CHARGE, "B", 0, 0.0), NaviTripPlan.Row(NaviTripPlan.Kind.DESTINATION, "Ziel", 0, 0.0))
        assertEquals(listOf(140, 140, 120, 150), NaviTripPlan.legSpeeds(rows, a.kmh).map { it.legKmh })
        assertEquals("without advice as they were", listOf<Int?>(null, null, null, null), NaviTripPlan.legSpeeds(rows, null as List<Int>?).map { it.legKmh })
    }
}
