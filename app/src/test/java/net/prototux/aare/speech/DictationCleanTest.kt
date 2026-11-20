package net.prototux.aare.speech

import org.junit.Assert.assertEquals
import org.junit.Test

/** 18.11.2026: "dynamische Musik hat er erkannt, findet die Adresse nicht. Oder er sagt, ich schreie". */
class DictationCleanTest {
    @Test fun noisesAreNotWords() {
        assertEquals("", Dictation.clean("(dynamische Musik)"))
        assertEquals("", Dictation.clean("[Musik]"))
        assertEquals("", Dictation.clean("*schreit*"))
        assertEquals("", Dictation.clean("Dynamische Musik."))
        assertEquals("", Dictation.clean("Untertitel der Amara.org-Community"))
        assertEquals("", Dictation.clean("Vielen Dank fürs Zuschauen!"))
        assertEquals("", Dictation.clean("♪ la la ♪"))
    }

    @Test fun wordsStay() {
        assertEquals("Navigiere zur Hamburger Straße 17 in Berlin", Dictation.clean("Navigiere zur Hamburger Straße 17 in Berlin"))
        assertEquals("Spiele Musik von Wizo", Dictation.clean("[Musik] Spiele Musik von Wizo"))
        assertEquals("Musik", Dictation.clean("Musik"))
    }
}
