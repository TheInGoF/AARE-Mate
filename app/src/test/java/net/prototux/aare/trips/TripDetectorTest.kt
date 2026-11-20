package net.prototux.aare.trips

import net.prototux.aare.trips.TripDetector.Event
import net.prototux.aare.trips.TripDetector.Gear
import net.prototux.aare.trips.TripDetector.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** System L-076/L-077: trips from the gear (P ends after the grace time), charges from the stick and from the level. */
class TripDetectorTest {
    private val min = 60_000L

    /** Drives north from 52.0/13.0 at ~[kmh] for [minutes], one sample every 2 s, then stands in P for [parkMin]. */
    private fun drive(d: TripDetector, t0: Long, minutes: Int, kmh: Double = 50.0, soc0: Double = 80.0, parkMin: Int = 6,
                      lat0: Double = 52.0): Pair<List<Event>, Long> {
        val out = ArrayList<Event>()
        var t = t0
        var lat = lat0
        var soc = soc0
        val steps = minutes * 30
        repeat(steps) {
            lat += kmh / 3.6 * 2 / 111_000.0
            soc -= 0.01
            out += d.feed(Sample(t, Gear.DRIVE, kmh, lat, 13.0, soc))
            t += 2_000
        }
        repeat(parkMin * 30) { out += d.feed(Sample(t, Gear.PARK, 0.0, lat, 13.0, soc)); t += 2_000 }
        return out to t
    }

    @Test fun tripEndsAfterTheGraceInPAtTheMomentPWasPutIn() {
        val d = TripDetector(5 * min, { 77.0 })
        val (ev, _) = drive(d, 0, 10)
        val trips = ev.filterIsInstance<Event.TripEnded>().map { it.trip }
        assertEquals(1, trips.size)
        val t = trips[0]
        assertEquals(10 * min, t.endMs)
        assertEquals(8.3, t.distanceKm!!, 0.3)
        assertNotNull(t.energyKwh)
        assertTrue(t.points.size in 80..160)
    }

    @Test fun aShortStopAtTheBakerKeepsOneTrip() {
        val d = TripDetector(5 * min)
        val (a, t1) = drive(d, 0, 5, parkMin = 3)
        val (b, _) = drive(d, t1, 5, lat0 = 52.04)
        val all = a + b
        assertEquals(1, all.count { it is Event.TripStarted })
        assertEquals(1, all.count { it is Event.TripEnded })
    }

    @Test fun oneMinuteGraceSplitsAtTheBaker() {
        val d = TripDetector(1 * min)
        val (a, t1) = drive(d, 0, 5, parkMin = 3)
        val (b, _) = drive(d, t1, 5, lat0 = 52.04)
        assertEquals(2, (a + b).count { it is Event.TripEnded })
    }

    @Test fun carGoneEndsTheTripAndShortTripsAreDropped() {
        val d = TripDetector(5 * min)
        repeat(5) { d.feed(Sample(it * 2_000L, Gear.DRIVE, 5.0, 52.0, 13.0)) }
        assertTrue(d.inTrip)
        assertTrue(d.carGone().none { it is Event.TripEnded })     // a few metres only
        assertTrue(!d.inTrip)
    }

    @Test fun chargeFromTheStickWithEnergyAndPower() {
        val d = TripDetector()
        var t = 0L
        var soc = 20.0
        val out = ArrayList<Event>()
        repeat(15 * 30) {       // 15 min at 100 kW
            out += d.feed(Sample(t, Gear.PARK, 0.0, 52.0, 13.0, soc, charging = true, dc = true, powerKw = 100.0))
            soc += 0.1; t += 2_000
        }
        out += d.feed(Sample(t, Gear.PARK, 0.0, 52.0, 13.0, soc, charging = false))
        val c = out.filterIsInstance<Event.ChargeEnded>().single().charge
        assertEquals(true, c.dc)
        assertEquals(25.0, c.energyKwh!!, 0.5)
        assertEquals(100.0, c.maxKw!!, 0.01)
        assertEquals(TripDetector.ChargeSource.STICK, c.source)
    }

    @Test fun chargeFoundFromTheLevelBetweenTwoTrips() {
        val d = TripDetector(1 * min, lastEnd = TripDetector.Spot(0, 52.0, 13.0, 30.0))
        val ev = d.feed(Sample(3_600_000, Gear.DRIVE, 10.0, 52.0, 13.0, 75.0))
        val c = ev.filterIsInstance<Event.ChargeEnded>().single().charge
        assertEquals(TripDetector.ChargeSource.LEVEL, c.source)
        assertEquals(30.0, c.socStart!!, 0.01)
        assertEquals(75.0, c.socEnd!!, 0.01)
        assertNull(c.energyKwh)
    }

    @Test fun aCutChargeIsCompletedByTheNextTrip() {
        val d = TripDetector()
        d.feed(Sample(0, Gear.PARK, 0.0, 52.0, 13.0, 20.0, charging = true, powerKw = 11.0))
        d.feed(Sample(60_000, Gear.PARK, 0.0, 52.0, 13.0, 22.0, charging = true, powerKw = 11.0))
        val cut = d.carGone().filterIsInstance<Event.ChargeEnded>().single().charge
        assertTrue(cut.cut)
        val next = TripDetector(cutCharge = cut).feed(Sample(7_200_000, Gear.DRIVE, 10.0, 52.0, 13.0, 60.0))
        val done = next.filterIsInstance<Event.ChargeCompleted>().single().charge
        assertEquals(60.0, done.socEnd!!, 0.01)
        assertEquals(cut.startMs, done.startMs)
    }

    @Test fun withoutAGearTheSpeedDecides() {
        val d = TripDetector(5 * min)
        assertTrue(d.feed(Sample(0, Gear.UNKNOWN, 1.0, 52.0, 13.0)).isEmpty())
        assertTrue(d.feed(Sample(2_000, Gear.UNKNOWN, 30.0, 52.0, 13.0)).any { it is Event.TripStarted })
    }
}
