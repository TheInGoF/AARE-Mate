package net.prototux.aare.ui.car.navi

import android.content.Context
import net.prototux.aare.host.EnergyModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Navi I-071 (27.11.2026: "Die Verbrauchsberechnung scheint nicht damit klarzukommen, dass ich auf der A10 nur 120 fahren
 * darf … nicht einfach über die aktuelle Fahrweise angepasst werden, sondern anhand der Straßenverhältnisse"): the speed is
 * the driver's or the road's, not the car's error; the plan counts with the limits, the driver's own speed and the works.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36])
class NaviPaceTest {
    private val w = ChargeTestWorld
    private val car = EnergyModel.Car(capacityWh = 77_000, socPercent = 90.0)
    private val rules = ChargePlanner.Rules(buffer = { 0.0 })
    private fun perKm(kmh: Double) = EnergyModel.tripW(kmh, car) / kmh            // Wh per km by the model

    /** Driven at [realKmh] for [km] km on a track planned at 120: the car takes what the model says at the real speed. */
    private fun drive(realKmh: Double, km: Int, withTime: Boolean): Double {
        val track = w.track(400, whPerKm = perKm(120.0), kmh = 120.0)
        val roll = ChargeRolling(track, car, rules, w.near(emptyList()), 90.0, emptyList())
        val whPerKmReal = perKm(realKmh)
        var soc = 90.0; var t = 1_800_000_000_000L
        for (i in 1..km) {
            soc -= whPerKmReal / car.capacityWh * 100
            t += (3600_000.0 / realKmh).toLong()
            roll.check(i * 1000.0, soc, atMs = if (withTime) t else null)
        }
        return roll.scale(km * 1000.0, soc)
    }

    @Test fun drivingFasterIsNoFaultOfTheCar() {
        val without = drive(150.0, 120, withTime = false)
        val with = drive(150.0, 120, withTime = true)
        assertTrue("before: the 150 km/h were learned as the car's own ($without)", without > 1.1)
        assertEquals("now: the car is as the model says", 1.0, with, 0.04)
    }

    @Test fun atThePlannedSpeedNothingChanges() = assertEquals(1.0, drive(120.0, 120, withTime = true), 0.03)

    @Test fun theRoadsSpeeds() {
        // the map's limit where there is one (the driver's speed no higher), the driver's own where there is none
        assertEquals(120.0, NaviPace.speedAt(130.0, 120, 150.0, false), 1e-9)
        assertEquals(150.0, NaviPace.speedAt(130.0, NaviLimits.UNLIMITED, 150.0, false), 1e-9)
        assertEquals(130.0, NaviPace.speedAt(130.0, NaviLimits.UNLIMITED, null, false), 1e-9)
        assertEquals(110.0, NaviPace.speedAt(130.0, 120, 110.0, false), 1e-9)
        assertEquals(130.0, NaviPace.speedAt(130.0, null, 150.0, false), 1e-9)                   // not known: the route's
        assertEquals(80.0, NaviPace.speedAt(130.0, NaviLimits.UNLIMITED, 150.0, true), 1e-9)      // road works
        assertEquals(50.0, NaviPace.speedAt(50.0, 50, 150.0, false), 1e-9)                         // town as it is
    }

    @Test fun theDriversSpeedIsLearned() {
        val c: Context = RuntimeEnvironment.getApplication()
        c.getSharedPreferences("navi_pace", Context.MODE_PRIVATE).edit().clear().commit()
        assertNull(NaviPace.cruise(c))
        NaviPace.note(c, 150.0, 30.0)
        assertEquals(150.0, NaviPace.cruise(c)!!, 1e-6)
        NaviPace.note(c, 130.0, 150.0)
        assertEquals(140.0, NaviPace.cruise(c)!!, 1e-3)
        NaviPace.note(c, 60.0, 50.0)                                                          // a slow stretch teaches nothing
        assertEquals(140.0, NaviPace.cruise(c)!!, 1e-3)
    }
}
