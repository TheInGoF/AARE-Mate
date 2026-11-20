package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 18.11.2026: films only when the car stands parked for sure - P, no speed, no movement; at once closed again. */
class ParkedGateTest {
    private fun r(now: Long, gear: Int? = 101, kmh: Double? = 0.0, speedAt: Long = now, brake: Boolean? = null, fix: ParkedGate.Fix? = ParkedGate.Fix(50.0, 12.0, 0.0, now)) =
        ParkedGate.Reading(now, gear, now, kmh, speedAt, brake, fix)

    @Test fun parkedOpensAfterSettling() {
        val g = ParkedGate()
        assertFalse(g.check(r(0)).first)
        assertFalse(g.check(r(2_000)).first)
        assertTrue(g.check(r(3_000)).first)
    }

    @Test fun everyConditionCloses() {
        assertEquals("not in P", ParkedGate.reason(r(0, gear = 100), emptyList()))
        assertEquals("no gear from the car", ParkedGate.reason(r(0, gear = null), emptyList()))
        assertEquals("moving", ParkedGate.reason(r(0, kmh = 4.0), emptyList()))
        assertEquals("no speed from the car", ParkedGate.reason(r(0, kmh = null), emptyList()))
        assertEquals("parking brake off", ParkedGate.reason(r(0, brake = false), emptyList()))
        val moved = listOf(ParkedGate.Fix(50.0, 12.0, 0.0, 0), ParkedGate.Fix(50.0005, 12.0, 0.0, 10_000))   // some 55 m
        assertEquals("position moving", ParkedGate.reason(r(12_000), moved))
        assertEquals("position moving", ParkedGate.reason(r(0), listOf(ParkedGate.Fix(50.0, 12.0, 8.0, 0))))
    }

    @Test fun calmReadingsPass() {
        // the brake not told, a wandering fix of some metres, no position at all, an old speed of 0 (the car stays silent)
        assertNull(ParkedGate.reason(r(0), listOf(ParkedGate.Fix(50.0, 12.0, 0.5, 0), ParkedGate.Fix(50.0001, 12.0001, 0.8, 0))))
        assertNull(ParkedGate.reason(r(0, fix = null), emptyList()))
        assertNull(ParkedGate.reason(r(60_000, speedAt = 0), emptyList()))
    }

    @Test fun closesAtOnce() {
        val g = ParkedGate()
        g.check(r(0)); g.check(r(3_000))
        assertTrue(g.check(r(4_000)).first)
        assertFalse(g.check(r(5_000, gear = 100)).first)
        // and settles anew afterwards
        assertFalse(g.check(r(6_000)).first)
        assertTrue(g.check(r(9_000)).first)
    }

    @Test fun describesEveryReading() {
        val g = ParkedGate()
        val now = 10_000L
        g.check(ParkedGate.Reading(now, 100, now - 2_000, 0.0, now - 1_000, null, ParkedGate.Fix(50.0, 12.0, 0.4, now)))
        val d = g.describe(ParkedGate.Reading(now, 100, now - 2_000, 0.0, now - 1_000, null, null))
        assertEquals("gear D (2 s old), speed 0.0 km/h (1 s old), parking brake not told, position 1 fixes, moved -, fix speed 0.4 km/h", d)
    }

    /** 19.11.2026, 08:05: one fix a little over walking pace while the car stands in P - the GPS wandering, not a movement. */
    @Test fun oneSlowFixIsNoMovement() {
        assertEquals(null, ParkedGate.reason(r(10_000), listOf(ParkedGate.Fix(50.0, 12.0, 0.0, 2_000), ParkedGate.Fix(50.0, 12.00002, 3.5, 6_000), ParkedGate.Fix(50.0, 12.0, 0.6, 9_000))))
        assertEquals("position moving", ParkedGate.reason(r(10_000), listOf(ParkedGate.Fix(50.0, 12.0, 3.5, 6_000), ParkedGate.Fix(50.0, 12.0, 4.0, 9_000))))
    }
}
