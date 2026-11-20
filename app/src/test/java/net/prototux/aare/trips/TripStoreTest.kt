package net.prototux.aare.trips

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** System L-076/L-077: trips with their track and charges kept on the tablet. */
@RunWith(RobolectricTestRunner::class)
class TripStoreTest {
    @Test fun keepsTripsAndChargesNewestFirst() {
        val s = TripStore.get(ApplicationProvider.getApplicationContext())
        val pts = listOf(TripDetector.Point(1, 52.0, 13.0, 30.0, 80.0), TripDetector.Point(2, 52.01, 13.0, 40.0, 79.0))
        val id = s.add(TripDetector.Trip(1, 2, 52.0, 13.0, 52.01, 13.0, 1.1, null, null, 80.0, 79.0, 0.77, 70.0, 40.0, pts))
        s.add(TripDetector.Trip(10, 20, 52.01, 13.0, 52.0, 13.0, 1.1, null, null, 79.0, 78.0, null, null, 30.0, emptyList()))
        assertEquals(listOf(10L, 1L), s.trips().map { it.startMs })
        assertEquals(2, s.points(id).size)
        assertEquals(78.0, s.lastEnd()!!.soc!!, 0.01)
        s.nameTrip(id, "Zuhause", null)
        assertEquals("Zuhause", s.trips().last().startName)

        val cut = TripDetector.Charge(30, 40, 52.0, 13.0, true, 20.0, 30.0, 5.0, 50.0, TripDetector.ChargeSource.STICK, cut = true)
        s.add(cut)
        assertNotNull(s.lastCutCharge())
        s.complete(cut.copy(socEnd = 80.0))
        assertEquals(1, s.charges().size)
        assertEquals(80.0, s.charges().single().socEnd!!, 0.01)
        s.deleteTrip(id)
        assertEquals(1, s.trips().size)
        s.add(TripDetector.Trip(50, 60, 52.0, 13.0, 52.01, 13.0, 1.1, null, null, 80.0, 79.0, null, null, null, emptyList()))
        assertNull(s.lastCutCharge())     // a trip came after the cut charge
    }
}
