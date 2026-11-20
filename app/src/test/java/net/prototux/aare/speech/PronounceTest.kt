package net.prototux.aare.speech

import org.junit.Assert.assertEquals
import org.junit.Test

/** 17.11.2026, "Baumstraße" said as "Baums-traße": the kind of road set apart before the device voice computes it. */
class PronounceTest {
    @Test fun streetSetApart() {
        assertEquals("Rechts abbiegen auf die Baum-Straße", Pronounce.fix("Rechts abbiegen auf die Baumstraße"))
        assertEquals("Bahnhof-Straße", Pronounce.fix("Bahnhofstraße"))
        assertEquals("Schiller-Platz und Linden-Allee", Pronounce.fix("Schillerplatz und Lindenallee"))
    }
    @Test fun abbreviation() {
        assertEquals("Haupt-Straße 5", Pronounce.fix("Hauptstr. 5"))
    }
    /** 18.11.2026: "Dr. Schumannplatz" read as "D-R Schumannplatz". */
    @Test fun abbreviationsInFull() {
        assertEquals("Links abbiegen auf Doktor Schumann-Platz", Pronounce.fix("Links abbiegen auf Dr. Schumannplatz"))
        assertEquals("Doktor-Friedrichs-Ring", Pronounce.fix("Dr.-Friedrichs-Ring"))
        assertEquals("Geschwister-Scholl-Straße", Pronounce.fix("Geschw.-Scholl-Str."))
        assertEquals("Leipziger Straße, dann Sankt Egidien", Pronounce.fix("Leipziger Str., dann St. Egidien"))
        assertEquals("Professor-Virchow-Straße", Pronounce.fix("Prof.-Virchow-Straße"))
        // words that only end like one stay, and so does the end of a sentence
        assertEquals("Fahre zur Post. Dann rechts.", Pronounce.fix("Fahre zur Post. Dann rechts."))
        assertEquals("Ausfahrt Nord. Danach links", Pronounce.fix("Ausfahrt Nord. Danach links"))
    }
    @Test fun sentencesNotCutInsideAName() {
        assertEquals(listOf("Rechts auf die Fr.-Ebert-Straße.", "Dann 2.7 Kilometer geradeaus."), LocalTts.pieces("Rechts auf die Fr.-Ebert-Straße. Dann 2.7 Kilometer geradeaus."))
        assertEquals(listOf("B 86:", "Hamburg, Eisenach"), LocalTts.pieces("B 86: Hamburg, Eisenach"))
        assertEquals(listOf("Ziel erreicht."), LocalTts.pieces("Ziel erreicht. ..."))
    }
    @Test fun othersStay() {
        assertEquals("Weiter auf der A 4 Richtung Dresden", Pronounce.fix("Weiter auf der A 4 Richtung Dresden"))
        assertEquals("Hamburg und Nürnberg", Pronounce.fix("Hamburg und Nürnberg"))
        assertEquals("Straße", Pronounce.fix("Straße"))
    }

    /** 18.11.2026: "Für die Sprachsynthese müssen wir immer ein paar Hilfen parat haben." */
    @org.junit.Test fun moreHelp() {
        org.junit.Assert.assertTrue(Pronounce.fix("Hl. Geist-Kirche").startsWith("Heilige "))
        assertEquals("Ludwigshafen am Rhein", Pronounce.fix("Ludwigshafen am Rh."))
        assertEquals("Gewerbegebiet Nord", Pronounce.fix("Gew. Nord"))
    }
}
