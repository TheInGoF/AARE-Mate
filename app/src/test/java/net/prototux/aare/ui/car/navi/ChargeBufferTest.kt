package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Navi L-029 step D, E-030: the hit list of the stretches driven; a plan with a buffer keeps it. */
class ChargeBufferTest {
    @Test fun startValue() {
        assertTrue(ChargeBuffer(emptyList()).describe().contains("0 of 3 stretches"))
    }

    @Test fun learnedFromTheStretches() {
        val b = ChargeBuffer(listOf(ChargeBuffer.Sample(1.0, 100.0), ChargeBuffer.Sample(4.0, 200.0), ChargeBuffer.Sample(1.5, 50.0)))
        assertTrue(b.describe(), b.describe().contains("hit list of 3 stretches"))
    }

    @Test fun planKeepsTheBuffer() {
        // a stop 200 km ahead is reached with the least level plus 1 %
        val w = ChargeTestWorld
        val car = net.prototux.aare.host.EnergyModel.Car(capacityWh = 77_000, socPercent = 60.0)
        val buffer = { km: Double -> minOf(6.0, 0.5 * maxOf(0.0, km) / 100.0) }
        val res = ChargeChain.plan(w.track(400), car, 60.0, w.near((10 until 400 step 20).map { w.charger(it.toDouble(), 300) }),
            ChargePlanner.Rules(atCharger = 5.0, atDestination = 5.0, buffer = buffer))
        res.links.forEach { assertTrue("${it.arrive}", it.arrive >= 5.0 + buffer(it.cand.alongM / 1000) - 1e-6) }
        assertTrue(res.arrivePercent >= 5.0 + buffer(400.0) - 1e-6)
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
