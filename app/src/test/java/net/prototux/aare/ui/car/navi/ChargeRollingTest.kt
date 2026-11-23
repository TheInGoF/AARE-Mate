package net.prototux.aare.ui.car.navi

import net.prototux.aare.host.EnergyModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Navi L-029 step F: whole trips driven in simulation with a consumption off the model by -20 to +20 % and noise on top;
 * the plan kept up to date on the way. The car reaches every charger and the destination with the least level, the
 * stops do not flap, the requests to the map server are counted (one per change).
 */
class ChargeRollingTest {
    private val w = ChargeTestWorld
    private val car = EnergyModel.Car(capacityWh = 77_000, socPercent = 70.0)

    var debugAll = false
    class Trip(val arrivals: List<Double>, val end: Double, val atCharger: Double, val atEnd: Double, val moves: Int, val flips: Int, val firstShiftKm: Double?, val stretches: Int)

    private fun drive(seed: Long, bias: Double? = null, noise: Double = 0.05): Trip {
        val r = Random(seed)
        val km = 300 + r.nextInt(400)
        val shape = DoubleArray(km + 1) { 150.0 + 80 * r.nextDouble() }
        val track = w.track(km, perKm = { shape[it] })
        val all = ArrayList<Charger>(); var at = 10.0 + r.nextInt(20)
        while (at < km - 5) { all += w.charger(at, listOf(150, 300, 400)[r.nextInt(3)], w.NETS[r.nextInt(w.NETS.size)], r.nextDouble() * 400); at += 15.0 + r.nextInt(31) }
        val near = w.near(all)
        val rules = ChargePlanner.Rules(atCharger = 5.0 + r.nextInt(6), atDestination = 5.0 + r.nextInt(6), buffer = { km -> minOf(6.0, 0.5 * maxOf(0.0, km) / 100.0) })
        val start = 50.0 + r.nextInt(41)
        val chain = ChargeChain.plan(track, car, start, near, rules).links.map { it.cand.charger }
        var stretches = 0
        val roll = ChargeRolling(track, car, rules, near, start, chain) { _, _ -> stretches++ }
        val b = bias ?: (r.nextDouble() * 0.4 - 0.2)
        var e = 0.0; var soc = start
        val arrivals = ArrayList<Double>()
        val abandoned = HashSet<Charger>(); var flips = 0
        var firstShift: Double? = null
        for (i in 1..km) {
            e = 0.8 * e + r.nextGaussian() * noise
            val factor = (1 + b + e).coerceIn(0.6, 1.6)
            soc -= (track.wh[i] - track.wh[i - 1]) * factor / car.capacityWh * 100
            val driven = i * 1000.0
            // at the next stop: charged as the plan from here says
            val next = roll.chain.firstOrNull()
            if (next != null && roll.alongM(next) <= driven) {
                arrivals += soc
                soc = maxOf(soc, roll.leaveAt(next, driven, soc))
            }
            val d = roll.check(driven, soc) ?: continue
            if (d.kind != ChargeRolling.Kind.KEEP && System.getProperty("rollDebug") == seed.toString() || (d.kind == ChargeRolling.Kind.MOVE && debugAll)) println("trip $seed at $i km soc " + "%.1f".format(soc) + ": ${d.kind} ${d.why}")
            if (d.kind == ChargeRolling.Kind.MOVE) {
                val before = next
                if (firstShift == null && before != null && d.chain.isNotEmpty()) firstShift = (roll.alongM(d.chain.first()) - roll.alongM(before)) / 1000
                // back to a stop given up before - for safety (the next stop out of reach) that is right, else it flaps
                if (!d.urgent) d.chain.take(1).forEach { if (it in abandoned) { flips++; if (System.getProperty("rollDebug") != null || debugAll) println("trip $seed FLIP back to ${it.network} at $i km") } }
                if (before != null && before !in d.chain) abandoned += before
            }
        }
        roll.arrived(km * 1000.0, soc)
        return Trip(arrivals, soc, rules.atCharger, rules.atDestination, roll.moves, flips, firstShift, stretches)
    }

    @Test fun wholeTripsWithNoisyConsumption() {
        var below = 100.0; var trips = 0; var moves = 0; var stops = 0; var flips = 0; var lowest = 100.0; var lowestEnd = 100.0
        val t0 = System.nanoTime()
        for (seed in 1L..300L) {
            val t = drive(seed)
            trips++; moves += t.moves; stops += t.arrivals.size; flips += t.flips
            t.arrivals.forEach { lowest = minOf(lowest, it) }
            lowestEnd = minOf(lowestEnd, t.end)
            // the least levels of this trip (5 to 10 %) kept
            t.arrivals.forEach { assertTrue("trip $seed: arrived with $it, least ${t.atCharger}", it >= t.atCharger) }
            assertTrue("trip $seed: end ${t.end}, least ${t.atEnd}", t.end >= t.atEnd)
            below = minOf(below, minOf(t.end - t.atEnd, t.arrivals.minOrNull()?.let { it - t.atCharger } ?: 100.0))
            assertTrue("trip $seed: ${t.moves} changes", t.moves <= 5)
        }
        println("rolling: $trips trips, $stops stops, $moves changes (= requests on the way, %.2f per trip), $flips back to a stop left, lowest arrival %.1f %%, lowest end %.1f %%, closest to the least level +%.1f %%, %.0f ms"
            .format(moves.toDouble() / trips, lowest, lowestEnd, below, (System.nanoTime() - t0) / 1e6))
        assertEquals(0, flips)
    }

    @Test fun asPlannedNothingChanges() {
        for (seed in 1L..40L) {
            val t = drive(seed, bias = 0.0, noise = 0.0)
            assertEquals("trip $seed", 0, t.moves)
        }
    }

    @Test fun moreUseMovesTheStopEarlierLessLater() {
        val up = (1L..80L).mapNotNull { drive(it, bias = 0.2, noise = 0.02).firstShiftKm }
        val down = (1L..80L).mapNotNull { drive(it, bias = -0.2, noise = 0.02).firstShiftKm }
        println("first change: +20 %%: ${up.size} trips, %.0f km on average; -20 %%: ${down.size} trips, %.0f km".format(up.average(), down.average()))
        assertTrue(up.isNotEmpty() && down.isNotEmpty())
        assertTrue(up.average() < 0 && down.average() > 0)
    }

    @Test fun stretchesNotedForTheBuffer() {
        val t = drive(7)
        assertTrue(t.stretches >= 1)
        // a stretch says what the model missed: here the car took 10 % more over 100 km of 180 Wh/km = 2.3 %
        val track = w.track(100)
        var noted = Double.NaN to 0.0
        val roll = ChargeRolling(track, car, ChargePlanner.Rules(), w.near(emptyList()), 60.0, emptyList()) { p, km -> noted = p to km }
        roll.arrived(100_000.0, 60.0 - 100 * 180 * 1.1 / 770)
        assertEquals(100 * 180 * 0.1 / 770, noted.first, 0.01)
        assertEquals(100.0, noted.second, 1e-6)
    }

    @Test fun scaleTrustedQuickerWhenItTakesMore() {
        val track = w.track(200)
        val roll = ChargeRolling(track, car, ChargePlanner.Rules(), w.near(emptyList()), 80.0, emptyList())
        val model10 = 10 * 180.0 / 770
        assertEquals(1.0 + 0.2 * 10 / 30.0, roll.scale(10_000.0, 80.0 - model10 * 1.2), 1e-6)
        assertEquals(1.0 - 0.2 * 10 / 60.0, roll.scale(10_000.0, 80.0 - model10 * 0.8), 1e-6)
    }

    // 19.11.2026: the tile drifted from 20 to 40 % on the way; counted from the level now along the plan's model it holds
    // where the car takes what the model says, and falls where it takes more
    @Test fun levelOnArrivalFollowsTheLevelNow() {
        val track = w.track(200)
        val roll = ChargeRolling(track, car, ChargePlanner.Rules(), w.near(emptyList()), 80.0, emptyList())
        val perKm = 180.0 / 770
        val planned = 80.0 - 200 * perKm
        assertEquals(planned, roll.levelAt(0.0, 80.0, roll.endM), 1e-6)
        for (km in listOf(10, 50, 100, 150)) assertEquals(planned, roll.levelAt(km * 1000.0, 80.0 - km * perKm, roll.endM), 1e-6)
        // 20 % more taken over the first 50 km: lower than planned, and lower than the plain difference
        val soc = 80.0 - 50 * perKm * 1.2
        val now = roll.levelAt(50_000.0, soc, roll.endM)
        assertTrue(now < planned - 50 * perKm * 0.2 + 1e-9)
    }

    /** N-019 step 1: "Ankunft 11 % ± 2" - the ± shrinks as the way ahead gets shorter and the drive has taught more. */
    @Test fun spreadShrinksOnTheWay() {
        val track = w.track(200)
        val roll = ChargeRolling(track, car, ChargePlanner.Rules(), w.near(emptyList()), 80.0, emptyList())
        val perKm = 180.0 / 770
        val atStart = roll.spreadAt(0.0, 80.0, roll.endM)
        // 200 km at the start: about 12 % of the ~47 % ahead
        assertEquals(Math.sqrt(0.36 + Math.pow(200 * perKm * 0.12, 2.0)), atStart, 1e-6)
        val half = roll.spreadAt(100_000.0, 80.0 - 100 * perKm, roll.endM)
        val near = roll.spreadAt(195_000.0, 80.0 - 195 * perKm, roll.endM)
        assertTrue("start %.2f, half %.2f, near %.2f".format(atStart, half, near), atStart > half && half > near)
        // at the end only the reading is left
        assertEquals(ChargeRolling.READING, roll.spreadAt(200_000.0, 80.0 - 200 * perKm, roll.endM), 0.05)
    }

    /** E-030: with a hit list the ± starts from what earlier drives missed per 100 km, not from the 12 % guess. */
    @Test fun spreadStartsFromTheHitList() {
        val track = w.track(200)
        val roll = ChargeRolling(track, car, ChargePlanner.Rules(), w.near(emptyList()), 80.0, emptyList(), learnedMissPer100 = 1.0)
        assertEquals(Math.sqrt(0.36 + 2.0 * 2.0), roll.spreadAt(0.0, 80.0, roll.endM), 1e-6)
    }
}
