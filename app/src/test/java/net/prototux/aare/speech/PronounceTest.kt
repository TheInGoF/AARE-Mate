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

    /** 21.11.2026 ("Verfeinere die Sprachausgabe für deutsch"): ordinals with their ending, the sentence no longer cut at "2.". */
    @Test fun ordinals() {
        assertEquals("Im Kreisverkehr die zweite Ausfahrt nehmen.", Pronounce.fix("Im Kreisverkehr die 2. Ausfahrt nehmen."))
        assertEquals("am einundzwanzigsten November", Pronounce.fix("am 21. November"))
        assertEquals("Montag, der dritte Oktober", Pronounce.fix("Montag, 3. Oktober"))
        assertEquals("zur ersten Ampel", Pronounce.fix("zur 1. Ampel"))
        assertEquals("die siebte Straße", Pronounce.fix("die 7. Straße"))
        assertEquals(listOf("Im Kreisverkehr die zweite Ausfahrt nehmen."), LocalTts.pieces(Pronounce.fix("Im Kreisverkehr die 2. Ausfahrt nehmen.")))
        // a number at the end of a sentence stays a number
        assertEquals("Ausfahrt 25. Danach links", Pronounce.fix("Ausfahrt 25. Danach links"))
        assertEquals("Dann 2.7 Kilometer geradeaus.", Pronounce.fix("Dann 2.7 Kilometer geradeaus."))
    }

    @Test fun numberWords() {
        assertEquals("einundzwanzig", Pronounce.cardinal(21))
        assertEquals("zweihundertdrei", Pronounce.cardinal(203))
        assertEquals("zweit", Pronounce.ordinal(2))
        assertEquals("dritt", Pronounce.ordinal(3))
        assertEquals("zwanzigst", Pronounce.ordinal(20))
        assertEquals("einhundertzweit", Pronounce.ordinal(102))
    }

    @Test fun unitsInFull() {
        assertEquals("In 800 Meter rechts abbiegen", Pronounce.fix("In 800 m rechts abbiegen"))
        assertEquals("Höchstens 120 Kilometer pro Stunde", Pronounce.fix("Höchstens 120 km/h"))
        assertEquals("Noch 1 Kilometer", Pronounce.fix("Noch 1 km"))
        assertEquals("Ladestand 80 Prozent, 15 Minuten laden", Pronounce.fix("Ladestand 80 %, 15 min laden"))
        assertEquals("Verbrauch 16 Komma 5 Kilowattstunden auf 100 Kilometer", Pronounce.fix("Verbrauch 16,5 kWh/100 km"))
        assertEquals("Ladeleistung 150 Kilowatt", Pronounce.fix("Ladeleistung 150 kW"))
        assertEquals("Draußen 9 Komma 5 Grad", Pronounce.fix("Draußen 9,5 °C"))
        // a road's letter is no unit
        assertEquals("Auf die A 4", Pronounce.fix("Auf die A 4"))
    }

    @Test fun timesAndPhrases() {
        assertEquals("Ankunft 14 Uhr 30", Pronounce.fix("Ankunft 14:30 Uhr"))
        assertEquals("Ankunft 9 Uhr 5", Pronounce.fix("Ankunft 09:05"))
        assertEquals("um 18 Uhr", Pronounce.fix("um 18:00"))
        assertEquals("zum Beispiel morgen, das heißt um 8 Uhr", Pronounce.fix("z. B. morgen, d. h. um 08:00"))
        assertEquals("eventuell später und so weiter", Pronounce.fix("evtl. später usw."))
    }

    /** The rules are German: an English voice reads its text as it is. */
    @Test fun onlyForGerman() {
        assertEquals("Take the 2. exit in 800 m", Pronounce.fix("Take the 2. exit in 800 m", "en"))
    }
}
