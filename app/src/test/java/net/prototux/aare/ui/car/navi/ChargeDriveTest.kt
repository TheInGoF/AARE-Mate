package net.prototux.aare.ui.car.navi

import kotlinx.coroutines.runBlocking
import net.prototux.aare.host.EnergyModel
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Navi L-029: a long trip with charging stops played through as Mate Navi does it - the plan with heights and weather,
 * the route over the stops from the map server (here [MockValhalla]), then driven with a car taking 12 % more than the
 * model and noise on top, the plan kept up to date on the way - with the log lines the navigation writes, for reading
 * them as in the diagnosis log (without the tablet, which the online sessions do not have).
 */
class ChargeDriveTest {
    private val w = ChargeTestWorld

    @Test fun longTripWithStopsAndItsLog() = runBlocking {
        val log = ArrayList<String>()
        val km = 720
        val car = EnergyModel.Car(capacityWh = 77_000, socPercent = 80.0, outsideCelsius = 6f)
        val pts = (0..km * 2).map { w.pos(it / 2.0) }
        // motorway at 130, now and then 100, towns at the ends; hills of 150 m
        val kmh = DoubleArray(pts.size) { i -> when { i < 20 || i > pts.size - 20 -> 50.0; (i / 120) % 5 == 4 -> 100.0; else -> 130.0 } }
        val heights = ChargeEnergy.Heights { p -> 200 + 150 * Math.sin(p.longitude * 111.32 / 40.0) }
        // head wind of 7 m/s on the first half, rain between km 300 and 380, 3 °C
        val weather = ChargeEnergy.Weather { p, _ ->
            val at = p.longitude * 111.32
            ChargeEnergy.Conditions(celsius = 3.0, windFromDeg = if (at < km / 2) 90.0 else 200.0, windMs = 7.0, rainMmH = if (at in 300.0..380.0) 2.0 else 0.0)
        }
        val base = ChargeEnergy.track(pts, kmh, car, heights, weather)
        val r = Random(2026)
        val all = ArrayList<Charger>(); var at = 6.0
        while (at < km) { all += w.charger(at, listOf(150, 300, 300, 400)[r.nextInt(4)], w.NETS[r.nextInt(w.NETS.size)], r.nextDouble() * 300); at += 15.0 + r.nextInt(26) }
        val rules = ChargePlanner.Rules(atCharger = 10.0, atDestination = 10.0, chargeUpTo = 80.0, preferred = setOf("ionity", "tesla"),
            buffer = ChargeBuffer(emptyList())::percent)
        log += "charging: FAST " + ChargeBuffer(emptyList()).describe()
        log += "charging: FAST energy: ${base.parts}"
        val server = MockValhalla(base)
        val out = server.routing().plan(base, car, 80.0, w.near(all), rules)
        log += ChargeRouting.logLine("FAST", km.toDouble(), 80.0, out.plan, out, rules, "weather: test (head wind, rain, 3 °C)")
        out.notes.forEach { log += "charging: FAST $it" }

        // driven: 12 % more than the model, noise on top
        var soc = 80.0; var e = 0.0
        val roll = ChargeRolling(base, car, rules, w.near(all), soc, out.plan.stops.map { it.charger }) { missed, k ->
            log += "charging: stretch of %.0f km driven, the car used %+.1f %% more than the model said (for the buffer)".format(java.util.Locale.ROOT, k, missed)
        }
        val arrivals = ArrayList<Double>(); val done = HashSet<Charger>()
        for (i in 1 until base.size) {
            e = 0.8 * e + r.nextGaussian() * 0.03
            soc -= (base.wh[i] - base.wh[i - 1]) * (1.12 + e) / car.capacityWh * 100
            val driven = base.metres[i]
            val next = roll.chain.firstOrNull { it !in done }
            if (next != null && roll.alongM(next) <= driven) {
                done += next
                arrivals += soc
                val to = roll.leaveAt(next, driven, soc)
                log += "at the charger %s %d kW, km %.0f: arrived with %.1f %%, charging to %.0f %%".format(java.util.Locale.ROOT, next.network, next.kw, driven / 1000, soc, to)
                soc = maxOf(soc, to)
            }
            val d = roll.check(driven, soc) ?: continue
            if (d.kind != ChargeRolling.Kind.KEEP || d.why.contains("waiting")) log += "charging on the way: ${d.kind}, ${d.why}"
            if (d.kind == ChargeRolling.Kind.MOVE) { server.route(d.chain); log += "charging on the way: new route over the new stops, 1 request" }
        }
        roll.arrived(base.metres.last(), soc)
        log += "arrived: %.1f %% (least %.0f %%), %d requests in all (%d for the plan, %d on the way)".format(java.util.Locale.ROOT,
            soc, rules.atDestination, server.calls, out.calls, roll.moves)
        println(log.joinToString("\n"))
        arrivals.forEach { assertTrue("arrived with $it", it >= rules.atCharger) }
        assertTrue("end $soc", soc >= rules.atDestination)
        assertTrue(out.calls <= 3 && server.calls == out.calls + roll.moves)
    }
}
