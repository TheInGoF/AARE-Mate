package net.prototux.aare.ui.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** System I-062: the zones when an app is let go follow the split as it is shown. */
class DropLayoutTest {
    private fun lay(split: Boolean, share: Float, naviRight: Boolean, right: Boolean?, isNavi: Boolean, foreign: Boolean = false, cur: Boolean = false) =
        dropLayout(split, share, naviRight, right, isNavi, foreign, cur)

    @Test fun threeQuartersKeepsItsLine() {
        // navi left at 3/4, music right
        assertEquals(0.75f, lay(true, 0.75f, false, null, false).boundary, 1e-4f)
        // a music app on the right takes the quarter, the navi keeps 3/4
        val m = lay(true, 0.75f, false, true, false)
        assertFalse(m.naviRight); assertEquals(0.75f, m.leftShare, 1e-4f)
        // a navi app on the left replaces it in the same width
        val n = lay(true, 0.75f, false, false, true)
        assertFalse(n.naviRight); assertEquals(0.75f, n.leftShare, 1e-4f)
    }

    @Test fun twoThirdsNaviRight() {
        val l = lay(true, 2f / 3, true, null, false)
        assertEquals(1f / 3, l.boundary, 1e-4f)
        assertEquals(1f / 3, lay(true, 2f / 3, true, true, true).leftShare, 1e-4f)
    }

    @Test fun newSplitIsHalfButForeignNaviTakesTwoThirds() {
        assertEquals(0.5f, lay(false, 0.75f, true, null, true).boundary, 1e-4f)
        assertEquals(0.5f, lay(false, 0.75f, true, false, true).leftShare, 1e-4f)
        val maps = lay(false, 0.5f, true, true, true, foreign = true)
        assertTrue(maps.naviRight); assertEquals(1f / 3, maps.leftShare, 1e-4f)
    }

    @Test fun foreignNaviInANarrowSideGetsTwoThirds() {
        // split 3/4 Mate Navi left; Google Maps let go on the narrow right side
        val l = lay(true, 0.75f, false, true, true, foreign = true)
        assertTrue(l.naviRight); assertEquals(2f / 3, l.naviShare, 1e-4f)
    }
}
