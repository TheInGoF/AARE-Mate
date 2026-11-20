package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Test

/** 19.11.2026: the way driven over a new route after a detour (the arrival said 0.9 km after 22 minutes). */
class DrivenTotalTest {
    @Test fun aNewRouteAddsOnToTheWayBefore() {
        NaviKeep.startTrip(80.0)
        val first = Any(); val second = Any()
        // 12 km route, driven 10.5 km of it
        generateSequence(12_000.0) { it - 50.0 }.takeWhile { it >= 1_500.0 }.forEach { NaviKeep.noteProgress(first, 12_000.0, it) }   // a step a second
        // a detour: a new route of 2.4 km from where the car is, driven to its end
        (listOf(2_400.0, 1_200.0) + 10.0).forEach { NaviKeep.noteProgress(second, 2_400.0, it) }
        assertEquals(10_500.0 + 2_390.0, NaviKeep.drivenTotalM.doubleValue, 1.0)
    }

    @Test fun jumpsAndBackwardsDoNotCount() {
        NaviKeep.startTrip(null)
        val r = Any()
        NaviKeep.noteProgress(r, 50_000.0, 50_000.0)
        NaviKeep.noteProgress(r, 50_000.0, 49_000.0)      // +1 km
        NaviKeep.noteProgress(r, 50_000.0, 30_000.0)      // a snap 19 km on: not driving
        NaviKeep.noteProgress(r, 50_000.0, 30_500.0)      // back: nothing
        NaviKeep.noteProgress(r, 50_000.0, 29_800.0)      // +0.7 km
        assertEquals(1_700.0, NaviKeep.drivenTotalM.doubleValue, 1.0)
    }
}
