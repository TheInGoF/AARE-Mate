package net.prototux.aare.ui.car.navi

import net.prototux.aare.host.EnergyModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.spatialk.geojson.Position
import java.util.Random

/** Routes and chargers for the tests of the charging plan (Navi L-029), all on the equator, a point every km. */
internal object ChargeTestWorld {
    fun pos(km: Double, offM: Double = 0.0) = Position(longitude = km / 111.32, latitude = offM / 111_320.0)

    /** A straight route of [km] km at [kmh], each km taking [whPerKm] (or what [perKm] gives for it). */
    fun track(km: Int, whPerKm: Double = 180.0, kmh: Double = 120.0, perKm: ((Int) -> Double)? = null): ChargePlanner.Track {
        val wh = DoubleArray(km + 1)
        for (i in 1..km) wh[i] = wh[i - 1] + (perKm?.invoke(i) ?: whPerKm)
        return ChargePlanner.Track((0..km).map { pos(it.toDouble()) }, DoubleArray(km + 1) { it * 1000.0 },
            DoubleArray(km + 1) { it * 3600.0 / kmh }, wh)
    }

    fun charger(km: Double, kw: Int = 150, op: String = "mate-test", offM: Double = 0.0) = Charger(pos(km, offM), kw, 4, op)

    fun near(all: List<Charger>): (Position, Double, Int) -> List<Charger> {
        // a grid by km, as the index does it by cells
        val cells = all.groupBy { Math.floor(it.at.longitude * 111.32 / 10).toInt() }
        return { p, r, kw ->
            val k = Math.floor(p.longitude * 111.32 / 10).toInt()
            (k - 1 - (r / 10_000).toInt()..k + 1 + (r / 10_000).toInt()).flatMap { cells[it].orEmpty() }
                .filter { it.kw >= kw && ChargerIndex.distanceM(it.at, p) <= r }
        }
    }

    val NETS = listOf("IONITY GmbH", "EnBW", "Tesla Germany GmbH", "Aral pulse", "mate-test")
}

/**
 * Navi L-029 step C: the chain of stops by dynamic programming - the same least cost as trying every chain, the least
 * levels kept wherever a chain can keep them, fast and small on thousands of random routes, and the edge cases.
 */
class ChargeChainTest {
    private val car = EnergyModel.Car(capacityWh = 77_000, socPercent = 60.0)
    private val w = ChargeTestWorld

    /** Every chain tried (each candidate taken or not, each level step to leave with): the least cost. */
    private fun bruteForce(m: ChargeChain.Model): Double {
        var best = Double.POSITIVE_INFINITY
        fun go(prev: Int, leave: Double, cost: Double) {
            if (m.nextMandatory[prev] == m.dest) best = minOf(best, cost + m.endCost(leave - m.used(prev, m.dest)))
            for (k in prev + 1..minOf(m.nextMandatory[prev], m.n)) {
                val a = leave - m.used(prev, k)
                if (a < 0) continue
                val b = m.bucket(a)
                val c = cost + m.stopMinutes(k) + m.arriveCost(k, a)
                for (l in b..m.levels) go(k, l * m.step, c + m.charged(k, l) - m.charged(k, b) + m.over(l))
            }
        }
        go(0, m.start, 0.0)
        return best
    }

    private fun randomRules(r: Random) = r.nextBoolean().let { buffered -> ChargePlanner.Rules(
        atCharger = 3.0 + r.nextInt(13), atDestination = 3.0 + r.nextInt(18), chargeUpTo = 60.0 + r.nextInt(41),
        preferred = if (r.nextBoolean()) emptySet() else setOf("ionity", "tesla").shuffled(r).take(1 + r.nextInt(2)).toSet(),
        only = r.nextInt(3) == 0, rating = { c -> (c.at.longitude * 1e4).toInt() % 6 },
        buffer = { km -> if (buffered) 0.5 + 1.5 * km / 100 else 0.0 }) }

    @Test fun sameAsTryingEveryChain() {
        val r = Random(20261119)
        var feasible = 0; var withStops = 0
        repeat(1500) { case ->
            val km = 80 + r.nextInt(520)
            val track = w.track(km, perKm = { 150.0 + 100 * r.nextDouble() })
            val n = r.nextInt(6)
            val step = if (n >= 5) 10.0 else 5.0
            val cands = (0 until n).map {
                val at = 1 + r.nextInt(km - 1)
                ChargeCorridor.Candidate(w.charger(at.toDouble(), listOf(50, 150, 300)[r.nextInt(3)], w.NETS[r.nextInt(w.NETS.size)]),
                    at, at * 1000.0, r.nextDouble() * 1500, r.nextDouble() * 4000, r.nextDouble() * 400, mandatory = r.nextInt(12) == 0)
            }.sortedBy { it.alongM }
            val start = 2.0 + r.nextInt(95)
            val rules = randomRules(r)
            val m = ChargeChain.Model(track, cands, car, start, rules, step = step)
            val res = ChargeChain.solve(track, cands, car, start, rules, step = step)
            val brute = bruteForce(m)
            assertEquals("case $case: n $n, $km km from $start %", brute, res.cost, 1e-6)
            if (brute < ChargeChain.SHORT_MIN * ChargeChain.SHORT_OK) {
                feasible++
                // the least levels kept at every stop and at the destination
                assertTrue("case $case short ${res.shortPercent}", res.shortPercent <= ChargeChain.SHORT_OK)
                res.links.forEachIndexed { i, l -> assertTrue("case $case stop $i ${l.arrive}", l.arrive >= rules.atCharger - 1e-6 || l.cand.mandatory) }
                assertTrue("case $case end ${res.arrivePercent}", res.arrivePercent >= rules.atDestination - 1e-6)
            }
            if (res.links.isNotEmpty()) withStops++
            // never above full, never below what the car arrived with
            res.links.forEach { assertTrue(it.leave <= 100.0 + 1e-6 && it.leave >= it.arrive - 1e-9) }
        }
        println("DP = brute force: 1500 cases, $feasible keep the least levels, $withStops with stops")
        assertTrue(feasible > 500 && withStops > 300)
    }

    /** Thousands of long routes with many chargers: the least levels kept, quick and small. */
    @Test fun randomRoutesQuickAndSafe() {
        val r = Random(4711)
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val tid = Thread.currentThread().id
        // the time on the thread's own CPU (a shared build machine pauses the thread now and then - the release of
        // 18.11.2026 failed on one such pause), and the wall clock's 95 % mark beside it
        val walls = ArrayList<Long>()
        var slowest = 0L; var total = 0L; var most = 0L; var stops = 0; var cases = 0; var maxCands = 0
        // warm up the JIT, as the tablet's runtime has been by the time of a plan
        repeat(50) { ChargeChain.plan(w.track(400), car, 50.0, w.near(listOf(w.charger(200.0))), ChargePlanner.Rules()) }
        repeat(3000) { case ->
            val km = 100 + r.nextInt(1100)
            val track = w.track(km, perKm = { 140.0 + 120 * r.nextDouble() })
            // a charger every 5-60 km (gaps up to 120 km now and then), some off the motorway
            val all = ArrayList<Charger>(); var at = 2.0 + r.nextInt(30)
            while (at < km) {
                all += w.charger(at, listOf(50, 75, 150, 150, 300, 300, 400)[r.nextInt(7)], w.NETS[r.nextInt(w.NETS.size)],
                    if (r.nextInt(5) == 0) r.nextDouble() * 1800 else r.nextDouble() * 200)
                at += if (r.nextInt(20) == 0) 120.0 else 5.0 + r.nextInt(55)
            }
            val rules = ChargePlanner.Rules(atCharger = 5.0 + r.nextInt(8), atDestination = 5.0 + r.nextInt(12),
                chargeUpTo = 80.0 + r.nextInt(21), preferred = if (r.nextBoolean()) emptySet() else setOf("ionity", "tesla"),
                only = r.nextInt(4) == 0, buffer = { d -> minOf(6.0, 0.5 + 1.5 * d / 100) })
            // enough to reach the first charger (up to 32 km) with the least level and its buffer
            val start = 30.0 + r.nextInt(70)
            val before = bean.getThreadAllocatedBytes(tid)
            val t0 = System.nanoTime(); val c0 = bean.currentThreadCpuTime
            val res = ChargeChain.plan(track, car, start, w.near(all), rules)
            val ns = bean.currentThreadCpuTime - c0
            walls += System.nanoTime() - t0
            val bytes = bean.getThreadAllocatedBytes(tid) - before
            slowest = maxOf(slowest, ns); total += ns; most = maxOf(most, bytes); stops += res.links.size; cases++
            maxCands = maxOf(maxCands, res.candidates)
            // with chargers at most 120 km apart and 80 % at a stop the least levels are always kept
            assertTrue("case $case: short ${res.shortPercent}, ${res.describe()}", res.shortPercent <= ChargeChain.SHORT_OK)
            res.links.forEach { assertTrue("case $case ${it.arrive}", it.arrive >= rules.atCharger - 1e-6) }
            assertTrue("case $case end ${res.arrivePercent}", res.arrivePercent >= rules.atDestination - 1e-6)
            // stops in order along the route, none twice
            assertEquals(res.links.map { it.cand.alongM }.sorted(), res.links.map { it.cand.alongM })
        }
        val p95 = walls.sorted()[walls.size * 95 / 100]
        println("random routes: $cases plans, $stops stops, up to $maxCands candidates, %.2f ms CPU on average, slowest %.1f ms CPU, 95 %% within %.1f ms, at most %.1f MB allocated"
            .format(total / 1e6 / cases, slowest / 1e6, p95 / 1e6, most / 1e6))
        assertTrue("slowest plan ${slowest / 1e6} ms CPU", slowest < 50_000_000L)
        assertTrue("95 % of the plans within ${p95 / 1e6} ms", p95 < 50_000_000L)
        assertTrue("allocated ${most / 1e6} MB", most < 20_000_000L)
    }

    @Test fun noChargersAtAll() {
        val res = ChargeChain.plan(w.track(500), car, 60.0, w.near(emptyList()), ChargePlanner.Rules())
        assertTrue(res.links.isEmpty())
        assertTrue(res.arrivePercent < 0 && res.shortPercent > 10)
    }

    @Test fun onlyBlockedNetworksThenTheOthersSaid() {
        val r = ChargePlanner.Rules(preferred = setOf("tesla"), only = true)
        val res = ChargeChain.plan(w.track(400), car, 60.0, w.near(listOf(w.charger(180.0, op = "EnBW"))), r)
        assertEquals("enbw", res.links.single().cand.charger.network)
        assertTrue(r.outside(res.links.single().cand.charger) && res.shortPercent <= ChargeChain.SHORT_OK)
    }

    @Test fun destinationJustInReachNeedsNoStop() {
        // 60 % - 213 km x 180 Wh = 49.8 % -> 10.2 % at the end, the least is 10
        val res = ChargeChain.plan(w.track(213), car, 60.0, w.near(listOf(w.charger(100.0))), ChargePlanner.Rules())
        assertTrue(res.links.isEmpty())
        assertTrue(res.arrivePercent >= 10.0)
        // a km further and it stops
        assertEquals(1, ChargeChain.plan(w.track(215), car, 60.0, w.near(listOf(w.charger(100.0))), ChargePlanner.Rules()).links.size)
    }

    @Test fun startBelowTheReserve() {
        // 6 % at the start: the first charger is reached below the reserve, the plan still goes on from there
        val res = ChargeChain.plan(w.track(300), car, 6.0, w.near(listOf(w.charger(8.0), w.charger(150.0))), ChargePlanner.Rules())
        assertTrue(res.links.isNotEmpty())
        assertEquals(8_000.0, res.links.first().cand.alongM, 1.0)
        assertTrue(res.arrivePercent >= 10.0)
    }

    @Test fun veryLongTrip() {
        val all = (1 until 2500 step 25).map { w.charger(it.toDouble(), 300, w.NETS[it % w.NETS.size]) }
        val t0 = System.nanoTime()
        val res = ChargeChain.plan(w.track(2500), car, 90.0, w.near(all), ChargePlanner.Rules(buffer = { d -> minOf(6.0, 0.5 + 1.5 * d / 100) }))
        val ms = (System.nanoTime() - t0) / 1e6
        println("2500 km: ${res.links.size} stops, %.0f min charging, %.1f ms".format(res.chargeMinutes, ms))
        assertTrue(res.links.size in 8..16)
        assertTrue(res.shortPercent <= ChargeChain.SHORT_OK)
        assertTrue("$ms ms", ms < 500)
    }

    @Test fun chargersOnlyOffTheRoute() {
        // the only chargers 3.5 km off: found by the wider search, the way there counted
        val res = ChargeChain.plan(w.track(350), car, 60.0, w.near(listOf(w.charger(170.0, offM = 3_500.0))), ChargePlanner.Rules())
        val l = res.links.single()
        assertTrue(l.cand.detourM > 8_000 && res.shortPercent <= ChargeChain.SHORT_OK)
    }

    @Test fun equalChargersStableChoice() {
        val a = w.charger(170.0, 300, "IONITY GmbH"); val b = w.charger(170.0, 300, "EnBW", offM = 1.0)
        val c = w.charger(171.0, 300, "Aral pulse")
        val picks = (0 until 20).map { k ->
            val order = listOf(a, b, c).shuffled(Random(k.toLong()))
            ChargeChain.plan(w.track(350), car, 60.0, w.near(order), ChargePlanner.Rules()).links.single().cand.charger
        }.toSet()
        assertEquals(1, picks.size)
    }

    @Test fun fewerStopsWhereTheTimeIsTheSame() {
        // a long way with chargers everywhere: no stop that only charges a few minutes before the destination
        val all = (5 until 600 step 10).map { w.charger(it.toDouble(), 300) }
        val res = ChargeChain.plan(w.track(600), car, 80.0, w.near(all), ChargePlanner.Rules())
        res.links.forEach { assertTrue("${it.minutes} min", it.minutes > 3.0) }
        assertTrue(res.links.size <= 3)
    }

    @Test fun ownChargerStopKept() {
        val own = w.charger(40.0, 50)
        val res = ChargeChain.plan(w.track(300), car, 80.0, w.near(listOf(own, w.charger(150.0, 300))), ChargePlanner.Rules(), mandatory = listOf(own))
        assertTrue(res.links.any { it.cand.charger === own })
    }
}
