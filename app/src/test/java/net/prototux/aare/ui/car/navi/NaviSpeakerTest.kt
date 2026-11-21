package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Test

/** Valhalla's German announcements, smoothed as one would say them (F4). Examples from a route Potsdamer Platz - BER. */
class NaviSpeakerTest {
    private fun p(s: String) = NaviSpeaker.polish(s, "de")

    @Test fun lowerCaseAfterTheDistance() {
        assertEquals("In 300 Metern leicht nach links auf Potsdamer Brücke abbiegen.", p("In 300 Metern, Leicht nach links auf Potsdamer Brücke abbiegen."))
        assertEquals("In 700 Metern links halten auf die Ausfahrt 25.", p("In 700 Metern, Links halten auf die Ausfahrt 25."))
        assertEquals("In 900 Metern an der Ausfahrt 8 abfahren.", p("In 900 Metern, An der Ausfahrt 8 abfahren."))
    }

    @Test fun directionsLowerCase() {
        assertEquals("In Richtung A 100, Tempelhof nach rechts abbiegen.", p("In Richtung A 100, Tempelhof nach Rechts abbiegen."))
        assertEquals("Leicht nach links auf Potsdamer Brücke abbiegen. Dann links auf Schöneberger Ufer abbiegen.",
            p("Leicht nach links auf Potsdamer Brücke abbiegen. Dann Links auf Schöneberger Ufer abbiegen."))
    }

    @Test fun namesStay() {
        assertEquals("In 2 Kilometern Potsdamer Straße folgen.", p("In 2 Kilometern, Potsdamer Straße folgen."))
        assertEquals("2 Kilometer weiter der Route folgen.", p("2 Kilometer weiter der Route folgen."))
    }

    @Test fun destinationWithoutComma() {
        assertEquals("In 80 Metern das Ziel befindet sich auf der rechten Seite.",
            NaviSpeaker.polish("In 80 Metern Das Ziel befindet sich auf der rechten Seite.", "de"))
    }

    @Test fun destinationGetsItsArticle() {
        assertEquals("Das Ziel befindet sich auf der rechten Seite.", NaviSpeaker.polish("Ziel befindet sich auf der rechten Seite.", "de"))
    }

    @Test
    fun directionAfterCommaIsLowercase() {
        assertEquals("Rechts auf Rosa-Luxemburg-Straße abbiegen. Dann, in 70 Meter, rechts abbiegen.",
            NaviSpeaker.polish("Rechts auf Rosa-Luxemburg-Straße abbiegen. Dann, in 70 Meter, Rechts abbiegen.", "de"))
    }
}
