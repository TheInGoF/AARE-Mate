package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Test
import org.maplibre.spatialk.geojson.Position

/** O209: the stops ahead in order with their arrival, the charging before one counted in; passed ones left out. */
class NaviTripPlanTest {
    private fun p(km: Double) = Position(longitude = km * 1000 / 111_320.0, latitude = 0.0)
    private val charger = Charger(p(40.0), 300, 4, "mate-test Lader GmbH")
    private val marks = listOf(NaviStops.Mark(p(10.0), 10_000.0, 600.0), NaviStops.Mark(p(40.0), 40_000.0, 2_400.0))
    private val charges = listOf(ChargePlanner.Stop(charger, 40_000.0, 12.0, 80.0, 25.0))
    private val own = listOf(p(10.0) to "mate-test Bäcker")

    @Test fun stopsInOrderWithCharging() {
        val rows = NaviTripPlan.rows(marks, charges, own, 0.0, 0.0, 100_000.0, 6_000.0, 0L, "mate-test Ziel", 21.0)
        assertEquals(listOf(NaviTripPlan.Kind.OWN, NaviTripPlan.Kind.CHARGE, NaviTripPlan.Kind.DESTINATION), rows.map { it.kind })
        assertEquals("mate-test Bäcker", rows[0].name)
        assertEquals("mate-test Lader GmbH · 300 kW", rows[1].name)
        assertEquals(2_400_000L, rows[1].arriveAtMillis)
        // the destination after 6000 s driving and 25 min charging
        assertEquals((6_000 + 25 * 60) * 1000L, rows[2].arriveAtMillis)
        assertEquals(21.0, rows[2].arrivePercent!!, 0.01)
    }

    @Test fun passedStopsLeftOut() {
        val rows = NaviTripPlan.rows(marks, charges, own, 20_000.0, 1_200.0, 100_000.0, 6_000.0, 0L, "mate-test Ziel", null)
        assertEquals(listOf(NaviTripPlan.Kind.CHARGE, NaviTripPlan.Kind.DESTINATION), rows.map { it.kind })
        assertEquals(20_000.0, rows[0].metresLeft, 1.0)
        assertEquals(1_200_000L, rows[0].arriveAtMillis)
    }
}
