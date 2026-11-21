package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Navi L-029 step D: the buffer grows with the way to the stop, learned from the stretches driven. */
class ChargeBufferTest {
    @Test fun startValue() {
        val b = ChargeBuffer(emptyList())
        // Ingo, 19.11.2026: 0.5 % per 100 km on the least level, nothing fixed on top (the least level is the safety)
        assertEquals(0.0, b.percent(0.0), 1e-9)
        assertEquals(0.5, b.percent(100.0), 1e-9)
        assertEquals(2.0, b.percent(400.0), 1e-9)
        assertEquals(ChargeBuffer.MAX, b.percent(5000.0), 1e-9)
        assertTrue(b.describe().contains("0 of 3 stretches"))
    }

    @Test fun learnedFromTheStretches() {
        // the model missed 1, 2 and 3 % per 100 km: mean 2, spread 0.82 -> 2.8 %/100 km
        val b = ChargeBuffer(listOf(ChargeBuffer.Sample(1.0, 100.0), ChargeBuffer.Sample(4.0, 200.0), ChargeBuffer.Sample(1.5, 50.0)))
        assertEquals(2.0 + Math.sqrt(2.0 / 3), b.rate, 1e-9)
        assertTrue(b.describe(), b.describe().contains("hit list of 3 stretches"))
        // fewer than three long enough: the start value
        assertEquals(ChargeBuffer.START_RATE, ChargeBuffer(listOf(ChargeBuffer.Sample(1.0, 100.0), ChargeBuffer.Sample(5.0, 10.0))).rate, 1e-9)
    }

    @Test fun keptWithinBounds() {
        val optimistic = ChargeBuffer(List(5) { ChargeBuffer.Sample(-3.0, 100.0) })
        assertEquals(ChargeBuffer.LOW_RATE, optimistic.rate, 1e-9)
        val bad = ChargeBuffer(List(5) { ChargeBuffer.Sample(20.0, 100.0) })
        assertEquals(ChargeBuffer.HIGH_RATE, bad.rate, 1e-9)
    }

    @Test fun planKeepsTheBuffer() {
        // a stop 200 km ahead is reached with the least level plus 1 %
        val w = ChargeTestWorld
        val car = net.prototux.aare.host.EnergyModel.Car(capacityWh = 77_000, socPercent = 60.0)
        val buffer = ChargeBuffer(emptyList())
        val res = ChargeChain.plan(w.track(400), car, 60.0, w.near((10 until 400 step 20).map { w.charger(it.toDouble(), 300) }),
            ChargePlanner.Rules(atCharger = 5.0, atDestination = 5.0, buffer = buffer::percent))
        res.links.forEach { assertTrue("${it.arrive}", it.arrive >= 5.0 + buffer.percent(it.cand.alongM / 1000) - 1e-6) }
        assertTrue(res.arrivePercent >= 5.0 + buffer.percent(400.0) - 1e-6)
    }

    /** E-030: the hit list's miss per 100 km (root mean square), only from three stretches of at least 30 km. */
    @Test fun hitListMissPer100() {
        org.junit.Assert.assertNull(ChargeBuffer(listOf(ChargeBuffer.Sample(2.0, 100.0), ChargeBuffer.Sample(2.0, 100.0))).missPer100)
        org.junit.Assert.assertNull(ChargeBuffer(List(5) { ChargeBuffer.Sample(2.0, 20.0) }).missPer100)
        assertEquals(2.0, ChargeBuffer(List(3) { ChargeBuffer.Sample(4.0, 200.0) }).missPer100!!, 1e-9)
        // off both ways counts as much as always too low
        assertEquals(2.0, ChargeBuffer(listOf(ChargeBuffer.Sample(2.0, 100.0), ChargeBuffer.Sample(-2.0, 100.0), ChargeBuffer.Sample(2.0, 100.0))).missPer100!!, 1e-9)
        org.junit.Assert.assertTrue(ChargeBuffer(emptyList()).describe().startsWith("hit list"))
    }
}
