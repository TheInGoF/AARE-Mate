package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NaviFerriesTest {
    /** Valhalla's answer (OSRM format) marks the ferry steps; Ferrostar's route of the same length gets them. */
    @Test fun ferryStepsFromValhallasAnswer() {
        val body = """{"routes":[{"distance":20123.4,"legs":[{"steps":[{"mode":"driving"},{"mode":"ferry","distance":6461.0,"duration":1246.0,"name":"mate-test Fähre"},{"mode":"ferry"},{"mode":"driving","distance":256.0,"duration":15.0,"name":"Sydmotorvejen"}]}]},
            {"distance":30000.0,"legs":[{"steps":[{"mode":"driving"},{"mode":"driving"}]}]}]}"""
        NaviFerries.note(body)
        val f = NaviFerries.steps(20123.4, 4)!!
        assertTrue(!f[0] && f[1] && f[2] && !f[3])
        assertNull("a route without ferries keeps nothing", NaviFerries.steps(30000.0, 2))
        assertNull("other steps: not this route", NaviFerries.steps(20123.4, 5))
        assertTrue("the ferry step by its length, time and name", NaviFerries.isFerry(6461.0, 1246.0, "mate-test Fähre"))
        assertTrue("a road step is none", !NaviFerries.isFerry(256.0, 15.0, "Sydmotorvejen"))
    }
}
