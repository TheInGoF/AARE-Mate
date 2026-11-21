package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Test

/** 17.11.2026, "weniger Sprache": turns without road names and destinations, the rest as it was. */
class NaviBriefTest {
    private fun b(s: String) = NaviSpeaker.brief(s, "de")
    @Test fun turnWithoutStreet() = assertEquals("In 300 Metern rechts abbiegen.", b("In 300 Metern rechts auf Baumstraße abbiegen."))
    @Test fun exitWithoutTowns() = assertEquals("In 1 Kilometer Ausfahrt 62 nehmen.", b("In 1 Kilometer Ausfahrt 62 in Richtung Hamburg, Sangerhausen nehmen."))
    @Test fun roundabout() = assertEquals("Im Kreisverkehr die 2. Ausfahrt nehmen.", b("Im Kreisverkehr die 2. Ausfahrt auf B 86 nehmen."))
    @Test fun destinationStays() = assertEquals("Das Ziel befindet sich auf der rechten Seite.", b("Das Ziel befindet sich auf der rechten Seite."))
    @Test fun plainStays() = assertEquals("Dann links abbiegen.", b("Dann links abbiegen."))

    /** 18.11.2026: "Kurz" says the turns only - "1 Kilometer geradeaus fahren und der Route folgen" is not one. */
    @org.junit.Test fun goingOnIsNoTurn() {
        org.junit.Assert.assertTrue(NaviSpeaker.goOnOnly("1 Kilometer geradeaus fahren und der Route folgen.", "de"))
        org.junit.Assert.assertTrue(NaviSpeaker.goOnOnly("Weiter auf der A 4 für 10 Kilometer.", "de"))
        org.junit.Assert.assertFalse(NaviSpeaker.goOnOnly("In 500 Metern rechts abbiegen.", "de"))
        org.junit.Assert.assertFalse(NaviSpeaker.goOnOnly("Ausfahrt 62 nehmen.", "de"))
        org.junit.Assert.assertFalse(NaviSpeaker.goOnOnly("In den Kreisverkehr fahren und die 2. Ausfahrt nehmen.", "de"))
        org.junit.Assert.assertFalse(NaviSpeaker.goOnOnly("Das Ziel befindet sich auf der rechten Seite.", "de"))
        org.junit.Assert.assertFalse(NaviSpeaker.goOnOnly("Links halten.", "de"))
    }
}
