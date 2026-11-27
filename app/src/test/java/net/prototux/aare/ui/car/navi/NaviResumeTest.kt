package net.prototux.aare.ui.car.navi

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.spatialk.geojson.Position
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Navi L-055 (27.11.2026: "wenn die Route einfach fortgeführt werden kann und er nicht eine neue Route und Berechnung
 * berechnet … Problem ist natürlich jetzt unser App-Update"): the trip kept with its charging stops and its line, and the
 * stops still ahead of where the car is when it goes on. A made-up line north from Berlin.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36])
class NaviResumeTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private fun north(km: Double) = Position(longitude = 13.4, latitude = 52.5 + km / 111.32)
    private val line = (0..300).map { north(it.toDouble()) }
    private val place = net.prototux.aare.places.Places.Place("x", "", "Ziel", "", north(300.0).latitude, 13.4)
    private fun stop(km: Double) = ChargePlanner.Stop(Charger(Position(longitude = 13.401, latitude = north(km).latitude), 300, 6, "IONITY"),
        km * 1000, 12.0, 60.0, 22.0)

    @Before fun clean() { NaviResume.clear(ctx) }

    @Test fun keptWithStopsAndLine() {
        NaviResume.save(ctx, place, listOf(north(150.0) to "Halt"), kind = "FAST", stops = listOf(stop(100.0), stop(220.0)), line = line)
        val r = assertNotNull(NaviResume.load(ctx)).let { NaviResume.load(ctx)!! }
        assertEquals("FAST", r.kind)
        assertEquals(2, r.stops.size)
        assertEquals(300, r.stops[0].charger.kw); assertEquals("IONITY", r.stops[0].charger.operator)
        assertEquals(22.0, r.stops[0].chargeMinutes, 1e-9)
        assertTrue("thinned", r.line.size in 2..line.size)
        assertEquals(1, r.own.size)
    }

    @Test fun theStopTheCarStandsAtIsDone() {
        NaviResume.save(ctx, place, emptyList(), kind = "FAST", stops = listOf(stop(100.0), stop(220.0)), line = line)
        val r = NaviResume.load(ctx)!!
        // at the first charger (an update of the app while it charges): only the second is ahead
        assertEquals(listOf(220_000.0), r.stopsAhead(Position(longitude = 13.4012, latitude = north(100.05).latitude)).map { it.atM })
        // further on, past the first one: the same
        assertEquals(listOf(220_000.0), r.stopsAhead(north(140.0)).map { it.atM })
        // before the first: both
        assertEquals(2, r.stopsAhead(north(20.0)).size)
    }

    @Test fun anOldSaveHasNoPlan() {
        NaviResume.save(ctx, place, emptyList())
        val r = NaviResume.load(ctx)!!
        assertTrue(r.stops.isEmpty() && r.line.isEmpty())
        assertEquals(null, r.kind)
    }
}
