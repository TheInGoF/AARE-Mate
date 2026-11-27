package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.maplibre.spatialk.geojson.Position

/**
 * Navi L-001: "Die App erkennt das Laden nachträglich an einem SoC-Sprung, und weil man dann schon eingestiegen ist und
 * fährt, erscheint die Kachel sofort, sobald der SoC-Sprung festgestellt wird. Zuordnung der Ladestation: über den
 * letzten Standort vor dem SoC-Sprung, wo die Ladung begonnen hat."
 */
class ChargeAskTest {
    private val charger = Position(longitude = 12.9, latitude = 50.8)
    private fun north(m: Double) = Position(longitude = charger.longitude, latitude = charger.latitude + m / 111_320.0)
    private val min = 60_000L
    /** some moment of the clock: the watch takes 0 for "never" */
    private val t0 = 1_800_000_000_000L

    @Test fun aChargeWhileMateWasOffIsAskedWhenTheCarDrivesOff() {
        // driven to the charger with 20 %, the tablet goes out with the car
        val w = ChargeAsk.Watch()
        assertNull(w.step(21.0, north(-800.0), 14.0, t0))
        assertNull(w.step(20.0, charger, 0.0, t0 + 1 * min))
        // 25 minutes later Mate starts again at the same place with 80 %: not asked while the car stands
        assertNull(w.step(80.0, charger, 0.0, t0 + 26 * min))
        val found = w.step(80.0, north(40.0), 5.0, t0 + 26 * min + 10_000)
        assertNotNull(found)
        assertEquals(20.0, found!!.from, 0.01)
        assertEquals(80.0, found.to, 0.01)
        assertEquals(0.0, ChargerIndex.distanceM(found.at!!, charger), 1.0)
        // asked once
        assertNull(w.step(80.0, north(200.0), 12.0, t0 + 27 * min))
    }

    @Test fun aLevelThatComesLateBelongsToWhereTheCarStood() {
        // kept over the restart: 20 % at the charger; IDmate still says 20 % when the car drives off
        val w = ChargeAsk.Watch(20.0, charger, t0)
        assertNull(w.step(20.0, charger, 0.0, t0 + 30 * min))
        assertNull(w.step(20.0, north(300.0), 12.0, t0 + 31 * min))
        // a red light on the way must not become the place
        assertNull(w.step(20.0, north(900.0), 0.0, t0 + 32 * min))
        val found = w.step(78.0, north(900.0), 0.0, t0 + 32 * min + 10_000) ?: w.step(78.0, north(950.0), 6.0, t0 + 32 * min + 20_000)
        assertNotNull(found)
        assertEquals(0.0, ChargerIndex.distanceM(found!!.at!!, charger), 1.0)
    }

    @Test fun drivingNeverLooksLikeCharging() {
        val w = ChargeAsk.Watch()
        var t = t0
        // down a long hill the level comes back bit by bit, a coffee stop in between
        for (soc in listOf(50.0, 49.0, 50.0, 51.0, 52.0, 53.0, 54.0, 55.0, 56.0)) { assertNull(w.step(soc, north((t - t0) / 100.0), 20.0, t)); t += 10_000 }
        for (k in 0 until 40) { assertNull(w.step(56.0, north((t - t0) / 100.0), 0.0, t + k * 10_000L)) }
        assertNull(w.step(56.0, north((t - t0) / 100.0 + 50), 8.0, t + 41 * 10_000L))
    }

    @Test fun aChargeSeenWhileStandingWaitsForTheDriveOff() {
        val w = ChargeAsk.Watch()
        assertNull(w.step(30.0, charger, 0.0, t0))
        // the level rises while Mate looks on
        assertNull(w.step(36.0, charger, 0.0, t0 + 3 * min))
        assertNull(w.step(60.0, charger, 0.0, t0 + 15 * min))
        val found = w.step(60.0, north(30.0), 4.0, t0 + 16 * min)
        assertNotNull(found)
        assertEquals(30.0, found!!.from, 0.01)
        assertEquals(60.0, found.to, 0.01)
        assertEquals(0.0, ChargerIndex.distanceM(found.at!!, charger), 1.0)
    }

    @Test fun aCarMovedWithoutMateIsNotPlaced() {
        // last seen far away; Mate was off while the car was driven and charged somewhere
        val w = ChargeAsk.Watch(20.0, north(50_000.0), t0)
        val found = w.step(80.0, charger, 6.0, t0 + 120 * min)
        assertNotNull(found)
        assertNull(found!!.at)
    }

    @Test fun besideEachOtherTheNearestAndOneOfEachOtherNetwork() {
        val a = Charger(north(20.0), 300, 8, "IONITY")
        val b = Charger(north(60.0), 250, 12, "Tesla")
        val c = Charger(north(90.0), 250, 4, "Tesla")
        val d = Charger(north(120.0), 150, 2, "EnBW")
        val e = Charger(north(150.0), 150, 2, "Aral pulse")
        val got = ChargeAsk.candidates(listOf(e, d, c, b, a), charger)
        assertEquals(listOf(a, b, d), got)
    }

    /** System I-143: the planned stop reached is not a visit, but its charger comes first when a charge is noticed there. */
    @Test fun thePlannedChargerComesFirst() {
        val a = Charger(north(20.0), 300, 8, "IONITY")
        val b = Charger(north(60.0), 250, 12, "Tesla")
        assertEquals(listOf(b, a), ChargeAsk.candidates(listOf(a, b), charger, planned = b.at))
        // an answer given there weighs more than the plan
        assertEquals(listOf(a, b), ChargeAsk.candidates(listOf(a, b), charger, listOf(ChargeAsk.Learned(north(5.0), a.at)), planned = b.at))
        // planned elsewhere: as before
        assertEquals(listOf(a, b), ChargeAsk.candidates(listOf(a, b), charger, planned = north(900.0)))
    }

    @Test fun anAnswerPutsItsChargerFirst() {
        val a = Charger(north(20.0), 300, 8, "IONITY")
        val b = Charger(north(60.0), 250, 12, "Tesla")
        val learned = listOf(ChargeAsk.Learned(north(5.0), b.at))
        assertEquals(listOf(b, a), ChargeAsk.candidates(listOf(a, b), charger, learned))
    }

    @Test fun twoAnswersAlikeAskNoMore() {
        val a = Charger(north(20.0), 300, 8, "IONITY")
        val b = Charger(north(60.0), 250, 12, "Tesla")
        val learned = listOf(ChargeAsk.Learned(north(5.0), b.at), ChargeAsk.Learned(north(-10.0), b.at))
        assertEquals(listOf(b), ChargeAsk.candidates(listOf(a, b), charger, learned))
    }

    @Test fun answersElsewhereOrEvenlySplitChangeNothing() {
        val a = Charger(north(20.0), 300, 8, "IONITY")
        val b = Charger(north(60.0), 250, 12, "Tesla")
        val far = listOf(ChargeAsk.Learned(north(200.0), b.at), ChargeAsk.Learned(north(210.0), b.at))
        assertEquals(listOf(a, b), ChargeAsk.candidates(listOf(a, b), charger, far))
        val split = listOf(ChargeAsk.Learned(north(5.0), b.at), ChargeAsk.Learned(north(-5.0), a.at))
        assertEquals(listOf(a, b), ChargeAsk.candidates(listOf(a, b), charger, split))
    }

    @Test fun aGoneChargerIsNotForced() {
        val a = Charger(north(20.0), 300, 8, "IONITY")
        val learned = listOf(ChargeAsk.Learned(north(5.0), north(500.0)), ChargeAsk.Learned(north(6.0), north(500.0)))
        assertEquals(listOf(a), ChargeAsk.candidates(listOf(a), charger, learned))
    }
}
