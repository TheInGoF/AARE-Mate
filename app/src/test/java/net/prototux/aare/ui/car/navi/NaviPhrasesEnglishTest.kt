package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Valhalla's English instructions: "Kurz" keeps the turns, without road names and destinations (Navi, language library). */
class NaviPhrasesEnglishTest {
    private fun b(s: String) = NaviSpeaker.brief(s, "en")

    @Test fun turnWithoutStreet() = assertEquals("In 300 meters, turn right.", b("In 300 meters, turn right onto Main Street."))
    @Test fun exitWithoutTowns() = assertEquals("Take exit 62.", b("Take exit 62 toward Hamburg, Sangerhausen."))
    @Test fun roundabout() = assertEquals("Enter the roundabout and take the 2nd exit.", b("Enter the roundabout and take the 2nd exit onto B 86."))
    @Test fun destinationStays() = assertEquals("Your destination is on the right.", b("Your destination is on the right."))

    @Test fun goingOnIsNoTurn() {
        assertTrue(NaviSpeaker.goOnOnly("Continue on A 4 for 10 kilometers.", "en"))
        assertTrue(NaviSpeaker.goOnOnly("Drive north.", "en"))
        assertFalse(NaviSpeaker.goOnOnly("In 500 meters, turn right.", "en"))
        assertFalse(NaviSpeaker.goOnOnly("Take exit 62.", "en"))
        assertFalse(NaviSpeaker.goOnOnly("Keep left at the fork.", "en"))
        assertFalse(NaviSpeaker.goOnOnly("You have arrived at your destination.", "en"))
        assertFalse(NaviSpeaker.goOnOnly("Make a U-turn.", "en"))
    }

    /** German announcements were treated as no turn under English rules before; each language now has its own. */
    @Test fun germanRulesOnlyForGerman() {
        assertFalse(NaviSpeaker.goOnOnly("In 500 Metern rechts abbiegen.", "de"))
        assertEquals("Turn right.", NaviSpeaker.polish(" Turn right. ", "en"))
    }
}
