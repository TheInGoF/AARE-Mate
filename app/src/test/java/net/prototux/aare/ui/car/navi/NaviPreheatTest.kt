package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Navi N-019 step 1 (E-029): when to warm the battery before a fast charger, and the heating rate learned. */
class NaviPreheatTest {
    private val none = NaviPreheat.Rates()

    @Test fun onlyWhenNearEnoughAndCold() {
        // 15 °C battery, 0.25 K/min (MEB): 40 min to 25 °C - said from 48 min before the charger on
        assertNull(NaviPreheat.advice(15.0, 0.0, 300, 50.0, none))
        val a = NaviPreheat.advice(15.0, 0.0, 300, 47.0, none)
        assertNotNull(a)
        assertEquals(40.0, a!!.minutes, 1e-9)
        assertEquals(40.0 / 60 * NaviPreheat.HEATER_KW, a.kwh, 1e-9)
        // warm enough, a slow charger, no reading: nothing
        assertNull(NaviPreheat.advice(23.0, 0.0, 300, 5.0, none))
        assertNull(NaviPreheat.advice(5.0, 0.0, 50, 5.0, none))
        assertNull(NaviPreheat.advice(null, 0.0, 300, 5.0, none))
    }

    @Test fun ratesLearnedPerOutsideTemperature() {
        var r = none.learn(-3.0, 0.5).learn(-4.0, 0.7).learn(12.0, 1.5)
        assertEquals(0.6, r.at(-2.0), 1e-9)
        assertEquals(1.5, r.at(14.0), 1e-9)
        // a band without any takes the nearest one
        assertEquals(1.5, r.at(25.0), 1e-9)
        // no warming: not kept
        r = r.learn(0.0, 9.0)
        assertEquals(0.6, r.at(-2.0), 1e-9)
        // stored and read back
        assertEquals(r.at(-2.0), NaviPreheat.Rates.decode(r.encode()).at(-2.0), 1e-9)
        assertEquals(NaviPreheat.START_RATE_K_MIN, NaviPreheat.Rates.decode("junk").at(0.0), 1e-9)
    }

    @Test fun watchSeesAWarming() {
        val w = NaviPreheat.Watch()
        val t0 = 1_000_000L
        assertNull(w.reading(5.0, t0))
        assertNull(w.reading(6.0, t0 + 4 * 60_000))
        // 2.5 °C in 10 min: 0.25 K/min, as the MEB heats
        assertEquals(0.25, w.reading(7.5, t0 + 10 * 60_000)!!, 1e-9)
        // falling readings start anew, nothing seen
        assertNull(w.reading(6.5, t0 + 12 * 60_000))
        assertNull(w.reading(7.0, t0 + 16 * 60_000))
    }

    /** Review 21.11.2026: a warming while charging is the charge, not the heater - after reset() nothing of it counts. */
    @Test fun resetWhileCharging() {
        val w = NaviPreheat.Watch()
        val t0 = 1_000_000L
        assertNull(w.reading(5.0, t0))
        w.reset()      // charging
        assertNull(w.reading(9.0, t0 + 5 * 60_000))
        assertNull(w.reading(10.0, t0 + 6 * 60_000))
    }
}
