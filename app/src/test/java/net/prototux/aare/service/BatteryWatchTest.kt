package net.prototux.aare.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** System I-135: the parts of Android's battery statistics that go into the log. */
class BatteryWatchTest {
    private val stats = """
Statistics since last charge:
  System starts: 0, currently on battery: true
  Estimated power use (mAh):
    Capacity: 7040, Computed drain: 3120, actual drain: 2900-3000
    Global
      screen: 1210 apps: 1210 duration: 6h 2m
      cpu: 640 apps: 600
      cell: 410 apps: 380 duration: 8h 1m
    UID u0a215: 980 ( screen=900 cpu=60 cell=20 )
    UID u0a140: 310 ( cpu=250 cell=60 )
    UID 1000: 120 ( cpu=120 )

  All kernel wake locks:
  Kernel Wake lock PowerManagerService.WakeLocks: 5h 1m realtime (40 times)
  Kernel Wake lock wlan: 10m realtime (300 times)

  All partial wake locks:
  Wake lock u0a215 aare:projection: 4h 58m realtime (3 times)
  Wake lock 1000 *alarm*: 2m realtime (80 times)
""".trimIndent().lines()

    @Test
    fun powerUseWithItsLines() {
        val power = BatteryWatch.section(stats, "Estimated power use", 150)
        assertEquals("Capacity: 7040, Computed drain: 3120, actual drain: 2900-3000", power.first())
        assertTrue(power.any { it.startsWith("screen: 1210") })
        assertTrue(power.any { it.startsWith("UID u0a215: 980") })
        assertTrue(power.none { it.startsWith("All kernel") })
    }

    @Test
    fun wakeLocksAtTheSameDepthAsTheirHeading() {
        val locks = BatteryWatch.section(stats, "All partial wake locks", 6)
        assertEquals(listOf("Wake lock u0a215 aare:projection: 4h 58m realtime (3 times)", "Wake lock 1000 *alarm*: 2m realtime (80 times)"), locks)
        assertEquals(2, BatteryWatch.section(stats, "All kernel wake locks", 4).size)
    }
}
