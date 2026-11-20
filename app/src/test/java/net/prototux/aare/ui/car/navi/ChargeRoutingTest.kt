package net.prototux.aare.ui.car.navi

import kotlinx.coroutines.runBlocking
import net.prototux.aare.host.EnergyModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.spatialk.geojson.Position
import java.util.Random

/**
 * A map server for the tests (Navi L-029 step E): the route over chargers is the plain one with a way out to each charger
 * and back; [factor] makes the route over the stops take more (or less) than the plain one said, [refuse] answers 400
 * for a list of chargers, [down] answers nothing at all. Counts the requests.
 */
internal class MockValhalla(private val base: ChargePlanner.Track, private val factor: Double = 1.0,
                            private val refuse: (List<Charger>) -> Boolean = { false }, private val down: Boolean = false,
                            private val detourWhPerKm: Double = 200.0) {
    var calls = 0; var matrixCalls = 0
    val asked = ArrayList<List<Charger>>()

    suspend fun route(chargers: List<Charger>): ChargePlanner.Track {
        calls++; asked += chargers
        if (down) throw java.io.IOException("timeout")
        if (refuse(chargers)) throw java.io.IOException("Route request failed with status code 400")
        val pts = ArrayList<Position>(); val wh = ArrayList<Double>(); val sec = ArrayList<Double>()
        var e = 0.0; var s = 0.0
        val at = chargers.associateBy { c -> (0 until base.size).minBy { ChargerIndex.distanceM(base.points[it], c.at) } }
        for (i in 0 until base.size) {
            if (i > 0) { e += (base.wh[i] - base.wh[i - 1]) * factor; s += base.seconds[i] - base.seconds[i - 1] }
            pts += base.points[i]; wh += e; sec += s
            at[i]?.let { c ->
                val off = ChargerIndex.distanceM(base.points[i], c.at)
                if (off > 1) {
                    // out to the charger and back on the same road
                    val d = off * ChargeCorridor.ROAD / 1000
                    e += d * detourWhPerKm; s += d * 72; pts += c.at; wh += e; sec += s
                    e += d * detourWhPerKm; s += d * 72; pts += base.points[i]; wh += e; sec += s
                }
            }
        }
        val m = DoubleArray(pts.size)
        for (i in 1 until pts.size) m[i] = m[i - 1] + ChargerIndex.distanceM(pts[i - 1], pts[i])
        return ChargePlanner.Track(pts, m, sec.toDoubleArray(), wh.toDoubleArray())
    }

    suspend fun matrix(cands: List<ChargeCorridor.Candidate>, track: ChargePlanner.Track): List<ChargeCorridor.Candidate> {
        matrixCalls++; calls++
        if (down) throw java.io.IOException("timeout")
        return cands.map { it.withDetour(it.offM * 2 * 1.5, it.offM * 2 * 1.5 / 13.9) }
    }

    fun routing(max: Int = ChargeRouting.MAX_CALLS) = ChargeRouting<ChargePlanner.Track>({ route(it) }, { it }, { c, t -> matrix(c, t) }, max)
}

class ChargeRoutingTest {
    private val w = ChargeTestWorld
    private val car = EnergyModel.Car(capacityWh = 77_000, socPercent = 70.0)
    private val rules = ChargePlanner.Rules(buffer = ChargeBuffer(emptyList())::percent)
    private fun every(km: Int, step: Int = 30, kw: Int = 300) = (step / 2 until km step step).map { w.charger(it.toDouble(), kw, w.NETS[it % w.NETS.size]) }

    private fun check(o: ChargeRouting.Outcome<ChargePlanner.Track>, r: ChargePlanner.Rules = rules) {
        o.plan.stops.forEach { assertTrue("stop ${it.arrivePercent}", it.arrivePercent >= r.atCharger - 1e-6) }
        assertTrue("end ${o.plan.arrivePercent}", o.plan.arrivePercent >= r.atDestination - 1e-6)
    }

    @Test fun noStopNoRequest() = runBlocking {
        val base = w.track(150); val v = MockValhalla(base)
        val o = v.routing().plan(base, car, 70.0, w.near(every(150)), rules)
        assertEquals(0, v.calls); assertFalse(o.overStops); assertTrue(o.plan.stops.isEmpty())
    }

    @Test fun oneRequestForTheRouteOverTheStops() = runBlocking {
        val base = w.track(409); val v = MockValhalla(base)
        val o = v.routing().plan(base, car, 70.0, w.near(every(409)), rules)
        assertEquals(1, v.calls); assertTrue(o.overStops); assertEquals(1, o.plan.stops.size)
        check(o)
    }

    @Test fun refusedThenOtherChargers() = runBlocking {
        val base = w.track(409)
        val chargers = every(409)
        // the server refuses any route over the first chain's charger
        val first = ChargeChain.plan(base, car, 70.0, w.near(chargers), rules).links.single().cand.charger
        val v = MockValhalla(base, refuse = { first in it })
        val o = v.routing().plan(base, car, 70.0, w.near(chargers), rules)
        assertEquals(2, v.calls); assertTrue(o.overStops)
        assertTrue(o.plan.stops.isNotEmpty() && o.plan.stops.none { it.charger === first })
        check(o)
        assertTrue(o.notes.joinToString(), o.notes.any { it.contains("refused") })
    }

    @Test fun serverDownStillStops() = runBlocking {
        val base = w.track(485); val v = MockValhalla(base, down = true)
        val o = v.routing().plan(base, car, 70.0, w.near(every(485)), rules)
        assertTrue(v.calls <= 3)
        assertFalse(o.overStops)
        assertTrue("never a long trip without stops", o.plan.stops.isNotEmpty())
        check(o)
    }

    @Test fun refusedEverywhereStillStops() = runBlocking {
        val base = w.track(485); val v = MockValhalla(base, refuse = { true })
        val o = v.routing().plan(base, car, 70.0, w.near(every(485)), rules)
        assertEquals(3, v.calls)
        assertTrue(o.plan.stops.isNotEmpty())
    }

    @Test fun fallsShortThenCorrectedOnce() = runBlocking {
        // the route over the stops takes 18 % more than the plain one said (another road, hills the plain route had not)
        val base = w.track(470); val v = MockValhalla(base, factor = 1.18)
        val o = v.routing().plan(base, car, 70.0, w.near(every(470, 20)), rules)
        assertTrue("${v.calls}", v.calls in 2..3)
        assertTrue(o.notes.joinToString(), o.notes.any { it.contains("falls short") })
        check(o)
    }

    @Test fun matrixForChargersOffTheRoute() = runBlocking {
        // the only chargers 1.5 km off the route: their way asked once
        val base = w.track(409)
        val v = MockValhalla(base)
        val all = (15 until 409 step 30).map { w.charger(it.toDouble(), 300, offM = 1_500.0) }
        val o = v.routing().plan(base, car, 70.0, w.near(all), rules)
        assertEquals(1, v.matrixCalls); assertTrue(v.calls <= 3)
        assertTrue(o.overStops && o.plan.stops.isNotEmpty())
        check(o)
    }

    @Test fun ownChargerStopNeedsNoRequest() = runBlocking {
        // the driver's own stop at a charger is enough: the route is already over it
        val base = w.track(300); val own = w.charger(150.0, 150)
        val v = MockValhalla(base)
        val o = v.routing().plan(base, car, 70.0, w.near(every(300) + own), rules, own = listOf(own))
        assertEquals(0, v.calls)
        assertEquals(listOf(own), o.plan.stops.map { it.charger })
        check(o)
    }

    /** Many random trips, the server refusing now and then, off by up to 20 %: never more than 3 requests, never without stops. */
    @Test fun randomTripsFewRequests() = runBlocking {
        val r = Random(1811)
        var total = 0; var plans = 0; var routed = 0; val hist = IntArray(4)
        repeat(400) { case ->
            val km = 150 + r.nextInt(650)
            val base = w.track(km, perKm = { 150.0 + 80 * r.nextDouble() })
            val all = ArrayList<Charger>(); var at = 3.0 + r.nextInt(20)
            while (at < km) { all += w.charger(at, listOf(150, 300, 400)[r.nextInt(3)], w.NETS[r.nextInt(w.NETS.size)], if (r.nextInt(6) == 0) 1_200.0 else 50.0); at += 8.0 + r.nextInt(35) }
            val bad = all.filter { r.nextInt(8) == 0 }.toSet()
            val v = MockValhalla(base, factor = 0.9 + 0.3 * r.nextDouble(), refuse = { l -> l.any { it in bad } }, down = r.nextInt(25) == 0)
            val start = 40.0 + r.nextInt(55)
            val o = v.routing().plan(base, car, start, w.near(all), rules)
            assertTrue("case $case: ${v.calls} requests", v.calls <= 3)
            hist[v.calls]++
            total += v.calls; plans++
            if (o.first.links.isNotEmpty()) assertTrue("case $case: stops lost", o.plan.stops.isNotEmpty())
            if (o.overStops) routed++
        }
        println("routing: $plans plans, %.2f requests on average, by number of requests 0..3: ${hist.toList()}, $routed over stops".format(total.toDouble() / plans))
    }

    /**
     * The trips of the log of 18.11.2026 (I-029): 409 km and 485 km from 70 % on the motorway at 130 in November (5 °C),
     * an 86 kWh ID.7 from its physics, a fast charger every 25-40 km - before, the first went without any stop (the route
     * over the charger refused) and the second planned 5 stops and arrived with -12 %.
     */
    @Test fun tripsOfTheLog() = runBlocking {
        val car = EnergyModel.Car(capacityWh = 86_000, socPercent = 70.0, outsideCelsius = 5f)
        for ((km, refuseFirst) in listOf(409 to true, 485 to false, 485 to true)) {
            val pts = (0..km * 10).map { w.pos(it / 10.0) }
            // motorway at 130 with stretches at 100 and town at the ends
            val kmh = DoubleArray(pts.size) { i -> when { i < 80 || i > pts.size - 80 -> 50.0; (i / 300) % 4 == 3 -> 100.0; else -> 130.0 } }
            val base = ChargeEnergy.track(pts, kmh, car)
            val r = Random(km.toLong())
            val all = ArrayList<Charger>(); var at = 12.0
            while (at < km) { all += w.charger(at, listOf(150, 300, 300, 400)[r.nextInt(4)], w.NETS[r.nextInt(w.NETS.size)]); at += 25.0 + r.nextInt(16) }
            var refused: Charger? = null
            val v = MockValhalla(base, refuse = { l -> if (refuseFirst && refused == null) { refused = l.first(); true } else l.any { it === refused } })
            val o = v.routing().plan(base, car, 70.0, w.near(all), rules)
            println("log trip $km km from 70 %${if (refuseFirst) ", first route refused" else ""}: ${v.calls} requests, " +
                o.plan.stops.joinToString { "%.0f km %.0f->%.0f %% %.0f min".format(it.atM / 1000, it.arrivePercent, it.leavePercent, it.chargeMinutes) } +
                ", %.0f %% at the end".format(o.plan.arrivePercent))
            assertTrue(o.plan.stops.size in 1..2)
            assertTrue(o.overStops && v.calls <= 3)
            check(o)
            // on the point: not much more than the least level plus its buffer at the end
            assertTrue("end ${o.plan.arrivePercent}", o.plan.arrivePercent <= rules.atDestination + ChargeBuffer.MAX + 4)
        }
    }
}
