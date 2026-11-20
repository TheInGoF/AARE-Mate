package net.prototux.aare.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F3: Mate Navi counts the heating by the outside temperature where IDmate's curve (which holds it) is missing. */
class EnergyTripTest {
    private fun car(c: Float, curve: List<Pair<Double, Double>>? = null) =
        EnergyModel.Car(capacityWh = 86_000, socPercent = 80.0, outsideCelsius = c, curve = curve)

    @Test fun coldCostsMore() = assertTrue(EnergyModel.tripW(100.0, car(-5f)) > EnergyModel.tripW(100.0, car(15f)) + 1_500)
    @Test fun mildAsBefore() = assertEquals(EnergyModel.powerW(100.0, car(15f)), EnergyModel.tripW(100.0, car(15f)), 1e-9)
    @Test fun curveHoldsIt() {
        val curve = listOf(50.0 to 14.0, 100.0 to 18.0, 130.0 to 23.0)
        assertEquals(EnergyModel.powerW(100.0, car(-5f, curve)), EnergyModel.tripW(100.0, car(-5f, curve)), 1e-9)
    }
    @Test fun table() {
        assertEquals(2600.0, EnergyModel.climateW(0f), 1.0)
        assertEquals(4600.0, EnergyModel.climateW(-30f), 1.0)
    }
}
