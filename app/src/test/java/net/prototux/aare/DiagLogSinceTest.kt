package net.prototux.aare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** System L-109: a save takes only what came after the last one; the whole log on its own button. */
class DiagLogSinceTest {
    private val log = listOf("26/11 07:00:00  INFO  CONN   a", "26/11 07:00:01  INFO  CONN   b", "26/11 07:00:02  WARN  NET    c")

    @Test fun neverSavedTakesAll() = assertEquals(log, DiagLog.since(log, null))

    @Test fun afterTheLastSavedLine() = assertEquals(log.drop(2), DiagLog.since(log, log[1]))

    @Test fun nothingNew() = assertTrue(DiagLog.since(log, log.last()).isEmpty())

    @Test fun savedLineGoneTakesAll() = assertEquals(log, DiagLog.since(log, "25/11 06:00:00  INFO  CONN   old"))

    @Test fun thePartInTheHeader() {
        val text = DiagLog.digest(log.drop(1), "2.5.13", part = "new since the last save (26/11 06:59)")
        assertTrue(text.lines().first(), text.lines().first().endsWith("· new since the last save (26/11 06:59)"))
    }
}
