package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The small head-up display (17.11.2026, "B 86: Zw…"): pieces of at most 9 characters, the road's number first. */
class HudTextTest {
    private val de = hudWords("values-de")

    @Test fun roadAndPlaces() {
        assertEquals(listOf("B 86", "Hamburg", "Sangerha."), HudText.pieces("B 86: Hamburg, Sangerhausen"))
    }
    @Test fun exitNumber() {
        assertEquals(listOf("Ausf. 25", "Eisenach"), HudText.pieces("Ausfahrt 25 · Eisenach", de))
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
        org.junit.Assert.assertEquals("rechts ab", NavStatusBridge.words(type("TYPE_TURN_NORMAL_RIGHT"), 0, de))
        org.junit.Assert.assertEquals("links ab", NavStatusBridge.words(type("TYPE_TURN_NORMAL_LEFT"), 0, de))
        org.junit.Assert.assertEquals("Kreisel 2", NavStatusBridge.words(type("TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE"), 2, de))
        org.junit.Assert.assertEquals("Ausfahrt", NavStatusBridge.words(type("TYPE_OFF_RAMP_NORMAL_RIGHT"), 0, de))
        org.junit.Assert.assertEquals("li halten", NavStatusBridge.words(type("TYPE_KEEP_LEFT"), 0, de))
        org.junit.Assert.assertNull("the arrow says it all", NavStatusBridge.words(type("TYPE_STRAIGHT"), 0, de))
        org.junit.Assert.assertNull(NavStatusBridge.words(type("TYPE_TURN_NORMAL_RIGHT"), 0, null))
        listOf("TYPE_TURN_SHARP_LEFT", "TYPE_TURN_SLIGHT_RIGHT", "TYPE_MERGE_LEFT", "TYPE_U_TURN_LEFT", "TYPE_ON_RAMP_NORMAL_LEFT", "TYPE_FORK_RIGHT").forEach {
            val w = NavStatusBridge.words(type(it), 0, de)!!
            org.junit.Assert.assertTrue("$it: \"$w\" fits the line", w.length <= HudText.MAX)
            org.junit.Assert.assertEquals("shown whole", listOf(w), HudText.pieces(w))
        }
        org.junit.Assert.assertEquals(listOf("rechts ab", "Dr.-Frie.", "Ring"), HudText.pieces("rechts ab · Dr.-Friedrichs Ring").take(3))
    }

    /** 18.11.2026: no names any more - "rechts abbiegen auf die B75, auffahren auf die A24" (the log: "B 86: Zw…"). */
    @Test fun numbersNotNames() {
        assertEquals(listOf("B 86"), HudText.refs("B 86: Hamburg, Sangerhausen, Eisenach, Nordhausen"))
        assertEquals(listOf("Ausf. 62", "B 86"), HudText.refs("Ausfahrt 62 · B 86: Hamburg, Sangerhausen, Eisenach, Nordhausen", de))
        assertEquals(listOf("A 4"), HudText.refs("A 4: Dresden, Chemnitz"))
        assertEquals(listOf("A 24"), HudText.refs("auf die A24 auffahren"))
        assertEquals(emptyList<String>(), HudText.refs("Kolpingstraße"))
        assertEquals(emptyList<String>(), HudText.refs("Walther-Rathenau-Straße"))
    }

    @Test fun theLine() {
        val road = "Ausfahrt 62 · B 86: Hamburg, Sangerhausen, Eisenach, Nordhausen"
        // far: the words and the numbers take turns
        assertEquals(listOf("Ausfahrt", "Ausf. 62", "B 86"), HudText.line("Ausfahrt", road, far = true, w = de))
        // near: one, steady - the number
        assertEquals(listOf("Ausf. 62"), HudText.line("Ausfahrt", road, far = false, w = de))
        // a town street: the words alone, never its name
        assertEquals(listOf("rechts ab"), HudText.line("rechts ab", "Kolpingstraße", far = true))
        assertEquals(listOf("rechts ab"), HudText.line("rechts ab", "Kolpingstraße", far = false))
        assertEquals(emptyList<String>(), HudText.line(null, "Kolpingstraße", far = true))
        HudText.line("Kreisel 2", "In den Kreisverkehr fahren und die 2. Ausfahrt nehmen.", far = true).forEach { assertTrue(it, it.length <= HudText.MAX) }
    }

    @Test fun wordsOfAPhoneApp() {
        assertEquals("rechts ab", HudText.wordsIn("Rechts abbiegen auf B 75", de))
        assertEquals("links ab", HudText.wordsIn("Links abbiegen", de))
        assertEquals("Kreisel 2", HudText.wordsIn("Im Kreisverkehr die 2. Ausfahrt nehmen", de))
        assertEquals("Auffahrt", HudText.wordsIn("Auf die A 24 auffahren", de))
        assertEquals(null, HudText.wordsIn("Weiter auf Hauptstraße", de))
        assertEquals("Rbt 2", HudText.wordsIn("At the roundabout, take the 2nd exit", hudWords("values")))
        assertEquals("Right", HudText.wordsIn("Turn right onto B 75", hudWords("values")))
        assertEquals("Exit 62", HudText.refs("Exit 62 · B 86", hudWords("values")).first())
    }

    /** The turn words of every language fit the line (English the base, German). */
    @Test fun everyLanguageFits() {
        for (dir in listOf("values", "values-de")) {
            val w = hudWords(dir)
            listOf(w.roundabout, w.roundaboutExit.format(9), w.uTurn, w.offRamp, w.onRamp, w.keepLeft, w.keepRight, w.slightLeft,
                w.slightRight, w.sharpLeft, w.sharpRight, w.left, w.right, w.merge, w.ferry, w.destination)
                .forEach { assertTrue("$dir: \"$it\" fits the line", it.length <= HudText.MAX) }
        }
    }

    /** The hud_* entries of res/<dir>/strings.xml, read as the app reads them. */
    private fun hudWords(dir: String): HudWords {
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(java.io.File("src/main/res/$dir/strings.xml"))
        val n = doc.getElementsByTagName("string")
        val m = (0 until n.length).map { n.item(it) as org.w3c.dom.Element }.associate { it.getAttribute("name") to it.textContent }
        fun g(k: String) = m.getValue("hud_$k")
        return HudWords(g("roundabout"), g("roundabout_exit"), g("u_turn"), g("off_ramp"), g("on_ramp"), g("keep_left"), g("keep_right"),
            g("slight_left"), g("slight_right"), g("sharp_left"), g("sharp_right"), g("left"), g("right"), g("merge"), g("ferry"), g("destination"), g("exit_short"))
    }
}
