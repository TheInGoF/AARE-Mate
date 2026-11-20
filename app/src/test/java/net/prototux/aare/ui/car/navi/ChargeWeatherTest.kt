package net.prototux.aare.ui.car.navi

import net.prototux.aare.host.EnergyModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.spatialk.geojson.Position

/** Navi L-029 step G: Open-Meteo's answer read (org.json: Robolectric). */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36])
class ChargeWeatherTest {
    private val line = (0..300).map { Position(longitude = 10.0 + it / 70.0, latitude = 50.0) }

    @Test fun pointsAlongTheRouteRounded() {
        val p = ChargeWeather.points(line)
        assertTrue(p.size in 6..9)
        p.forEach { assertEquals(it.latitude, Math.round(it.latitude * 10) / 10.0, 1e-9) }
        assertTrue(ChargeWeather.url(p).contains("hourly=temperature_2m,wind_speed_10m,wind_direction_10m,precipitation"))
        assertTrue(ChargeWeather.points((0..10000).map { Position(longitude = 5.0 + it / 500.0, latitude = 48.0 + it / 2000.0) }).size <= 20)
    }

    @Test fun readsTheHoursOfEachPoint() {
        val pts = listOf(Position(longitude = 10.0, latitude = 50.0), Position(longitude = 11.0, latitude = 50.0))
        val t0 = 1_800_000_000L
        fun one(temp: Double, wind: Double, from: Double, rain: Double) = """{"hourly":{"time":[$t0,${t0 + 3600},${t0 + 7200}],
            "temperature_2m":[$temp,${temp + 1},${temp + 2}],"wind_speed_10m":[$wind,$wind,$wind],"wind_direction_10m":[$from,$from,$from],
            "precipitation":[$rain,0,null]}}"""
        val w = ChargeWeather.parse(pts, "[${one(3.0, 6.0, 270.0, 1.2)},${one(-2.0, 2.0, 90.0, 0.0)}]") { t0 * 1000 + 600_000 }
        assertNotNull(w)
        val c = w!!.at(Position(longitude = 10.1, latitude = 50.0), 0.0)!!
        assertEquals(3.0, c.celsius!!, 1e-9); assertEquals(6.0, c.windMs, 1e-9); assertEquals(270.0, c.windFromDeg!!, 1e-9); assertEquals(1.2, c.rainMmH, 1e-9)
        // an hour later at the other point
        val d = w.at(Position(longitude = 10.9, latitude = 50.0), 3600.0)!!
        assertEquals(-1.0, d.celsius!!, 1e-9); assertEquals(90.0, d.windFromDeg!!, 1e-9)
        // missing values are none, far off nothing
        assertEquals(0.0, w.at(pts[0], 7200.0)!!.rainMmH, 1e-9)
        assertNull(w.at(Position(longitude = 20.0, latitude = 50.0), 0.0))
        assertTrue(w.describe(), w.describe().contains("2 points"))
        // one point: an object, not a list; an answer without hours: none
        assertNotNull(ChargeWeather.parse(pts.take(1), one(3.0, 6.0, 270.0, 0.0)))
        assertNull(ChargeWeather.parse(pts.take(1), """{"error":true}"""))
    }
}

/** Navi L-029 step G: head wind, rain and cold move the stops forward, wind from behind back; no weather as before. */
class ChargeWeatherPlanTest {
    private val w = ChargeTestWorld
    private val car = EnergyModel.Car(capacityWh = 77_000, socPercent = 80.0, outsideCelsius = 12f)
    private val km = 450
    // going east, a point every 500 m, at 120
    private val pts = (0..km * 2).map { w.pos(it / 2.0) }
    private val kmh = DoubleArray(pts.size) { 120.0 }
    private val chargers = (8 until km step 12).map { w.charger(it.toDouble(), 300) }
    private val rules = ChargePlanner.Rules(atCharger = 5.0, atDestination = 5.0)

    private fun firstStopKm(weather: ChargeEnergy.Weather?): Double {
        val track = ChargeEnergy.track(pts, kmh, car, weather = weather)
        return ChargeChain.plan(track, car, 80.0, w.near(chargers), rules).links.first().cand.alongM / 1000
    }

    @Test fun weatherMovesTheStop() {
        val calm = firstStopKm(null)
        val head = firstStopKm { _, _ -> ChargeEnergy.Conditions(windFromDeg = 90.0, windMs = 8.0) }
        val tail = firstStopKm { _, _ -> ChargeEnergy.Conditions(windFromDeg = 270.0, windMs = 8.0) }
        val rain = firstStopKm { _, _ -> ChargeEnergy.Conditions(rainMmH = 3.0) }
        val cold = firstStopKm { _, _ -> ChargeEnergy.Conditions(celsius = -8.0) }
        val same = firstStopKm { _, _ -> ChargeEnergy.Conditions(celsius = 12.0) }
        println("first stop: calm $calm km, head wind $head, wind from behind $tail, rain $rain, cold $cold")
        assertTrue(head < calm && rain < calm && cold < calm)
        assertTrue(tail > calm)
        assertEquals(calm, same, 1e-9)
        // no answer: as before
        assertEquals(calm, firstStopKm { _, _ -> null }, 1e-9)
    }
}
