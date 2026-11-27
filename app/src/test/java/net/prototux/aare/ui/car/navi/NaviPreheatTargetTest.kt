package net.prototux.aare.ui.car.navi

import net.prototux.aare.host.EnergyModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Navi L-052 (27.11.2026: "Lohnt es sich überhaupt, auf 25 Grad zu heizen, oder reichen 18 oder 20 Grad? Das sollte alles
 * dynamisch und so intelligent wie möglich passieren"): the target weighed for the charge it is for.
 */
class NaviPreheatTargetTest {
    private val car = EnergyModel.Car(capacityWh = 77_000, socPercent = 50.0)

    @Test fun coldBatteryWarmedForABigCharge() {
        // the hint of 27.11., 9:50: battery 8 °C, a 300 kW charger, there with 12 %, 22 min to charge (to about 60 %)
        val w = NaviPreheat.weigh(car, 300, 12.0, 60.0, 8.0, 0.25, 5.0)
        println("8 °C, 12 -> 60 %: " + w.joinToString { "%.0f °C %+.1f min (%.0f min, %.1f kWh)".format(it.targetC, it.saves, it.minutes, it.kwh) })
        val pick = assertNotNull(NaviPreheat.best(w)).let { NaviPreheat.best(w)!! }
        assertTrue("worth it", pick.saves >= NaviPreheat.WORTH_MIN)
        assertTrue("not always the most", pick.targetC <= 25.0)
        // what it takes and saves is said with it
        val a = NaviPreheat.advice(8.0, 8.0, 300, pick.minutes + 2, NaviPreheat.Rates(), NaviPreheat.Stop(car, 12.0, 60.0, 5.0))!!
        assertEquals(pick.targetC, a.targetC, 1e-9)
        assertTrue(a.why.contains("°C"))
    }

    @Test fun aShortTopUpIsNotWorthTheWarming() {
        val w = NaviPreheat.weigh(car, 150, 45.0, 52.0, 14.0, 0.25, 5.0)
        assertNull(NaviPreheat.best(w))
        assertNull(NaviPreheat.advice(14.0, 10.0, 150, 10.0, NaviPreheat.Rates(), NaviPreheat.Stop(car, 45.0, 52.0, 5.0)))
    }

    @Test fun aWarmBatteryIsLeftAlone() =
        assertNull(NaviPreheat.advice(23.0, 10.0, 300, 20.0, NaviPreheat.Rates(), NaviPreheat.Stop(car, 10.0, 80.0, 5.0)))

    @Test fun neverBelowTheLeastLevelAtTheCharger() {
        // there with 6 %, least 5 %: no warming costs more than 1 %
        val w = NaviPreheat.weigh(car, 300, 6.0, 70.0, 5.0, 0.25, 5.0)
        assertTrue(w.all { 6.0 - it.kwh * 1000 / car.capacityWh * 100 >= 5.0 })
    }

    @Test fun notTooEarly() {
        val stop = NaviPreheat.Stop(car, 12.0, 60.0, 5.0)
        val pick = NaviPreheat.best(NaviPreheat.weigh(car, 300, 12.0, 60.0, 8.0, 0.25, 5.0))!!
        assertNull(NaviPreheat.advice(8.0, 8.0, 300, pick.minutes + NaviPreheat.MARGIN_MIN + 5, NaviPreheat.Rates(), stop))
    }

    @Test fun coldChargesSlower() {
        val cold = NaviPreheat.chargeMinutes(car, 300, 10.0, 60.0, 5.0)
        val warm = NaviPreheat.chargeMinutes(car, 300, 10.0, 60.0, 25.0)
        assertTrue("$cold vs $warm", cold > warm + 5)
    }
}
