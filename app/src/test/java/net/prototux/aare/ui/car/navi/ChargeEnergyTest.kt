package net.prototux.aare.ui.car.navi

import net.prototux.aare.host.EnergyModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.spatialk.geojson.Position

/** Navi L-029 step A: the energy of each piece from speed, climb, temperature, wind and rain. */
class ChargeEnergyTest {
    private val car = EnergyModel.Car(capacityWh = 77_000, socPercent = 60.0, outsideCelsius = 15f)
    /** A straight road going east, a point every 100 m. */
    private val km = 50
    private val pts = (0..km * 10).map { Position(longitude = 12.0 + it * 100.0 / (111_320.0 * Math.cos(Math.toRadians(50.0))), latitude = 50.0) }
    private val kmh = DoubleArray(pts.size) { 120.0 }

    private fun total(heights: ChargeEnergy.Heights? = null, weather: ChargeEnergy.Weather? = null, c: EnergyModel.Car = car,
                      learned: ((Position, Double) -> Double)? = null) =
        ChargeEnergy.track(pts, kmh, c, heights, weather, learned).wh.last()

    @Test fun flatWithoutWeatherIsTheSpeedCurve() {
        val t = ChargeEnergy.track(pts, kmh, car)
        val expected = EnergyModel.tripW(120.0, car) * t.seconds.last() / 3600.0
        assertEquals(expected, t.wh.last(), expected * 1e-6)
        assertEquals(km * 1000.0, t.metres.last(), 5.0)
    }

    @Test fun climbCostsAndFallGivesBackLess() {
        val flat = total()
        val up = total(heights = { p -> (p.longitude - 12.0) * 2_000.0 })      // rising steadily
        val down = total(heights = { p -> -(p.longitude - 12.0) * 2_000.0 })
        val rise = (pts.last().longitude - 12.0) * 2_000.0
        val lift = car.massKg * 9.81 * rise / 3600.0
        assertEquals(lift / car.efficiency, up - flat, 1.0)
        assertEquals(-lift * ChargeEnergy.REGEN, down - flat, 1.0)
        // up and down again costs more than flat: recuperation loses
        val hill = total(heights = { p -> 200.0 - Math.abs(p.longitude - pts[pts.size / 2].longitude) * 2_000.0 })
        assertTrue(hill > flat)
    }

    @Test fun headWindCostsTailWindHelps() {
        val flat = total()
        val head = total(weather = { _, _ -> ChargeEnergy.Conditions(windFromDeg = 90.0, windMs = 8.0) })   // from the east
        val tail = total(weather = { _, _ -> ChargeEnergy.Conditions(windFromDeg = 270.0, windMs = 8.0) })
        val cross = total(weather = { _, _ -> ChargeEnergy.Conditions(windFromDeg = 0.0, windMs = 8.0) })
        assertTrue("head $head flat $flat", head > flat * 1.08)
        assertTrue("tail $tail flat $flat", tail < flat * 0.94)
        assertEquals(flat, cross, flat * 0.01)
    }

    @Test fun coldCostsMilderIsCheaper() {
        val now = total()
        val cold = total(weather = { _, _ -> ChargeEnergy.Conditions(celsius = -5.0) })
        val same = total(weather = { _, _ -> ChargeEnergy.Conditions(celsius = 15.0) })
        assertTrue(cold > now * 1.05)
        assertEquals(now, same, 1e-6)
        // with IDmate's curve (heating of now inside): milder than now is cheaper
        val curved = car.copy(curve = listOf(50.0 to 15.0, 100.0 to 18.0, 130.0 to 22.0), outsideCelsius = 0f)
        assertTrue(total(c = curved, weather = { _, _ -> ChargeEnergy.Conditions(celsius = 15.0) }) < total(c = curved))
    }

    @Test fun rainFromTheForecastOnlyWithoutIdmate() {
        val dry = total()
        val wet = total(weather = { _, _ -> ChargeEnergy.Conditions(rainMmH = 1.0) })
        val heavy = total(weather = { _, _ -> ChargeEnergy.Conditions(rainMmH = 4.0) })
        assertEquals(dry * (1 + ChargeEnergy.RAIN_LIGHT), wet, dry * 0.01)
        assertTrue(heavy > wet)
        // IDmate's learned extra (here none where the car is) takes the forecast's place
        assertEquals(dry, total(weather = { _, _ -> ChargeEnergy.Conditions(rainMmH = 4.0) }, learned = { _, _ -> 0.0 }), 1e-6)
        assertEquals(dry + 2.0 * km * 10, total(learned = { _, _ -> 2.0 }), 1.0)
    }

    @Test fun partsAddUp() {
        val t = ChargeEnergy.track(pts, kmh, car, { p -> (p.longitude - 12.0) * 1_000.0 },
            { _, _ -> ChargeEnergy.Conditions(celsius = 5.0, windFromDeg = 90.0, windMs = 5.0, rainMmH = 1.0) })
        val p = t.parts!!
        assertEquals(t.wh.last(), p.drive + p.climb + p.wind + p.climate + p.rain, 1.0)
        assertTrue(p.weatherPieces > 0 && p.heightPieces > 0)
    }

    @Test fun bearingAndHeadwind() {
        val a = Position(longitude = 12.0, latitude = 50.0)
        assertEquals(0.0, ChargeEnergy.bearing(a, Position(longitude = 12.0, latitude = 50.1)), 0.5)
        assertEquals(90.0, ChargeEnergy.bearing(a, Position(longitude = 12.1, latitude = 50.0)), 0.5)
        assertEquals(10.0 * ChargeEnergy.GROUND, ChargeEnergy.headwindMs(0.0, 0.0, 10.0), 1e-9)
        assertEquals(-10.0 * ChargeEnergy.GROUND, ChargeEnergy.headwindMs(0.0, 180.0, 10.0), 1e-9)
    }
    /** N-019 step 1: a jam at -5 °C costs its minutes of heating - creeping takes at least what standing takes. */
    @Test fun jamCostsHeatingByTheMinute() {
        val cold = car.copy(outsideCelsius = -5f)
        val crawl = DoubleArray(pts.size) { 5.0 }
        val t = ChargeEnergy.track(pts, crawl, cold)
        val idle = EnergyModel.idleW(-5.0, cold) * t.seconds.last() / 3600.0
        assertTrue("jam %.0f Wh against standing %.0f Wh".format(t.wh.last(), idle), t.wh.last() >= idle - 1.0)
        assertTrue(EnergyModel.idleW(-5.0, cold) > 2_500.0)
        assertTrue(t.parts!!.idle > 0.0)
        // on the motorway the drive is far above standing: nothing added
        val fast = ChargeEnergy.track(pts, kmh, cold)
        assertEquals(0.0, fast.parts!!.idle, 1e-9)
    }


    /** L-084, as IDmate: the kilometres on a ferry cost no energy (the car rides along), the time still counts. */
    @Test fun ferryCostsNoEnergy() {
        val ferry = BooleanArray(pts.size) { it in 101..300 }      // 20 km of the 50 on the ferry
        val parts = ChargeEnergy.Parts()
        val with = ChargeEnergy.track(pts, kmh, car, parts = parts, ferry = ferry)
        val without = ChargeEnergy.track(pts, kmh, car)
        assertEquals(without.wh.last() * 30.0 / 50.0, with.wh.last(), without.wh.last() * 0.01)
        assertEquals(20_000.0, parts.ferryM, 150.0)
        assertEquals(without.seconds.last(), with.seconds.last(), 0.01)
        assertTrue(parts.toString().contains("20 km on ferries"))
    }

}

/** Navi L-029 step A: the heights of the route from Valhalla's answer (org.json needs Robolectric). */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36])
class ChargeHeightsTest {
    private val pts = (0..40).map { Position(longitude = 12.0 + it * 100.0 / (111_320.0 * Math.cos(Math.toRadians(50.0))), latitude = 50.0) }

    @Test fun heightsFromTheServer() {
        val line = pts
        val samples = ChargeHeights.resample(line)
        assertEquals(11, samples.size)
        // a server without a height model answers 0 everywhere: no heights
        assertNull(ChargeHeights.parse(samples, """{"height":[${samples.joinToString { "0" }}]}"""))
        assertNull(ChargeHeights.parse(samples, """{"height":[1,2]}"""))
        val h = ChargeHeights.parse(samples, """{"height":[${samples.indices.joinToString { (it * 10).toString() }}]}""")
        assertNotNull(h)
        // the middle of a steady rise stays as it is after smoothing, a point off the road has none
        assertEquals(50.0, h!!.at(samples[5])!!, 1e-6)
        assertNull(h.at(Position(longitude = 13.0, latitude = 51.0)))
        // gaps take the neighbour's height
        assertNotNull(ChargeHeights.parse(samples, """{"height":[5,null,${(2 until samples.size).joinToString { (it * 10).toString() }}]}"""))
        assertTrue(ChargeHeights.request(samples).contains("\"shape\""))
    }
}
