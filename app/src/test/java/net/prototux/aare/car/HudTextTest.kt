package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The small head-up display (17.11.2026, "B 86: Zw…"): pieces of at most 9 characters, the road's number first. */
class HudTextTest {
    @Test fun roadAndPlaces() {
        assertEquals(listOf("B 86", "Hamburg", "Sangerha."), HudText.pieces("B 86: Hamburg, Sangerhausen"))
    }
    @Test fun exitNumber() {
        assertEquals(listOf("Ausf. 25", "Eisenach"), HudText.pieces("Ausfahrt 25 · Eisenach"))
    }
    @Test fun shortStaysWhole() {
        assertEquals(listOf("A 4"), HudText.pieces("A 4"))
    }
    @Test fun everyPieceFits() {
        HudText.pieces("Richtung Hohenstein-Ernstthal, Glauchau-Ost, Bad Elster").forEach { assertTrue(it, it.length <= HudText.MAX) }
    }
    @Test fun takesTurns() {
        assertEquals("B 86", HudText.at("B 86: Hamburg", 0))
        assertEquals("Hamburg", HudText.at("B 86: Hamburg", HudText.STEP_MS))
    }

    /** 18.11.2026: far from the turn the line also says it in words ("andere Apps sagen dann halt in 73 Kilometer rechts abbiegen"). */
    @org.junit.Test fun theTurnInWords() {
        val m = androidx.car.app.navigation.model.Maneuver::class.java
        fun type(name: String) = m.getField(name).getInt(null)
        org.junit.Assert.assertEquals("rechts ab", NavStatusBridge.words(type("TYPE_TURN_NORMAL_RIGHT"), 0, german = true))
        org.junit.Assert.assertEquals("links ab", NavStatusBridge.words(type("TYPE_TURN_NORMAL_LEFT"), 0, german = true))
        org.junit.Assert.assertEquals("Kreisel 2", NavStatusBridge.words(type("TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE"), 2, german = true))
        org.junit.Assert.assertEquals("Ausfahrt", NavStatusBridge.words(type("TYPE_OFF_RAMP_NORMAL_RIGHT"), 0, german = true))
        org.junit.Assert.assertEquals("li halten", NavStatusBridge.words(type("TYPE_KEEP_LEFT"), 0, german = true))
        org.junit.Assert.assertNull("the arrow says it all", NavStatusBridge.words(type("TYPE_STRAIGHT"), 0, german = true))
        org.junit.Assert.assertNull(NavStatusBridge.words(type("TYPE_TURN_NORMAL_RIGHT"), 0, german = false))
        listOf("TYPE_TURN_SHARP_LEFT", "TYPE_TURN_SLIGHT_RIGHT", "TYPE_MERGE_LEFT", "TYPE_U_TURN_LEFT", "TYPE_ON_RAMP_NORMAL_LEFT", "TYPE_FORK_RIGHT").forEach {
            val w = NavStatusBridge.words(type(it), 0, german = true)!!
            org.junit.Assert.assertTrue("$it: \"$w\" fits the line", w.length <= HudText.MAX)
            org.junit.Assert.assertEquals("shown whole", listOf(w), HudText.pieces(w))
        }
        org.junit.Assert.assertEquals(listOf("rechts ab", "Dr.-Frie.", "Ring"), HudText.pieces("rechts ab · Dr.-Friedrichs Ring").take(3))
    }

    /** 18.11.2026: no names any more - "rechts abbiegen auf die B75, auffahren auf die A24" (the log: "B 86: Zw…"). */
    @Test fun numbersNotNames() {
        assertEquals(listOf("B 86"), HudText.refs("B 86: Hamburg, Sangerhausen, Eisenach, Nordhausen"))
        assertEquals(listOf("Ausf. 62", "B 86"), HudText.refs("Ausfahrt 62 · B 86: Hamburg, Sangerhausen, Eisenach, Nordhausen"))
        assertEquals(listOf("A 4"), HudText.refs("A 4: Dresden, Chemnitz"))
        assertEquals(listOf("A 24"), HudText.refs("auf die A24 auffahren"))
        assertEquals(emptyList<String>(), HudText.refs("Kolpingstraße"))
        assertEquals(emptyList<String>(), HudText.refs("Walther-Rathenau-Straße"))
    }

    @Test fun theLine() {
        val road = "Ausfahrt 62 · B 86: Hamburg, Sangerhausen, Eisenach, Nordhausen"
        // far: the words and the numbers take turns
        assertEquals(listOf("Ausfahrt", "Ausf. 62", "B 86"), HudText.line("Ausfahrt", road, far = true))
        // near: one, steady - the number
        assertEquals(listOf("Ausf. 62"), HudText.line("Ausfahrt", road, far = false))
        // a town street: the words alone, never its name
        assertEquals(listOf("rechts ab"), HudText.line("rechts ab", "Kolpingstraße", far = true))
        assertEquals(listOf("rechts ab"), HudText.line("rechts ab", "Kolpingstraße", far = false))
        assertEquals(emptyList<String>(), HudText.line(null, "Kolpingstraße", far = true))
        HudText.line("Kreisel 2", "In den Kreisverkehr fahren und die 2. Ausfahrt nehmen.", far = true).forEach { assertTrue(it, it.length <= HudText.MAX) }
    }

    @Test fun wordsOfAPhoneApp() {
        assertEquals("rechts ab", HudText.wordsIn("Rechts abbiegen auf B 75"))
        assertEquals("links ab", HudText.wordsIn("Links abbiegen"))
        assertEquals("Kreisel 2", HudText.wordsIn("Im Kreisverkehr die 2. Ausfahrt nehmen"))
        assertEquals("Auffahrt", HudText.wordsIn("Auf die A 24 auffahren"))
        assertEquals(null, HudText.wordsIn("Weiter auf Hauptstraße"))
    }
}
