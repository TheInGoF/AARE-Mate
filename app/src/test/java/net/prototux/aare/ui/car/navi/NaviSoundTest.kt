package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NaviSoundTest {
    @Test fun cologneKnownCodes() {
        assertEquals("65752682", NaviFuzzy.cologne("mullerludenscheidt"))
        assertEquals("3412", NaviFuzzy.cologne("wikipedia"))
        assertEquals("17863", NaviFuzzy.cologne("breschnew"))
        assertEquals(NaviFuzzy.cologne("limehome"), NaviFuzzy.cologne("lunenhohe"))
    }

    @Test fun spokenNameFindsOwnPlace() {
        // System I-008: the place is "Lime Home", the speech heard "Lünen Höhe"
        assertNotNull(NaviFuzzy.cost("hamburg Lünen Höhe", "Lime Home Hamburg Lange Reihe"))
    }

    @Test fun soundIsBehindTypos() {
        val sound = NaviFuzzy.cost("Lünen Höhe", "Lime Home")!!
        val typo = NaviFuzzy.cost("Lime Hmoe", "Lime Home")!!
        assert(typo < sound)
    }

    @Test fun shortOrUnlikeWordsStayApart() {
        assertNull(NaviFuzzy.cost("Bahnhof", "Lime Home"))
        assertNull(NaviFuzzy.cost("hamburg ab", "Lime Home Hamburg"))
    }
}
