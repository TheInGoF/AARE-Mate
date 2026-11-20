package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** F6 step 2: a mistyped town set right, everything else left as typed. */
class NaviTownsTest {
    private val towns = listOf(NaviTowns.Town("Hamburg", 1_862_565), NaviTowns.Town("München", 1_505_036),
        NaviTowns.Town("Hameln", 57_000), NaviTowns.Town("Frankfurt am Main", 701_350), NaviTowns.Town("Potsdam", 180_000), NaviTowns.Town("Strasburg (Uckermark)", 4_600))

    @Test fun swapped() = assertEquals("Hamburg", NaviTowns.correct(towns, "hambrug"))
    @Test fun withStreet() = assertEquals("Hamburg Reeperbahn", NaviTowns.correct(towns, "hambrug Reeperbahn"))
    @Test fun umlautPlain() = assertNull(NaviTowns.correct(towns, "munchen"))          // a prefix after folding: no change needed
    @Test fun neighbourKey() = assertEquals("Frankfurt", NaviTowns.correct(towns, "frsnkfurt"))
    @Test fun rightAlready() = assertNull(NaviTowns.correct(towns, "Hamburg"))
    @Test fun halfTyped() = assertNull(NaviTowns.correct(towns, "hamb"))
    @Test fun streetsStay() {
        assertNull(NaviTowns.correct(towns, "Potsdamer Straße"))
        assertNull(NaviTowns.correct(towns, "Alexanderplatz"))
        assertNull(NaviTowns.correct(towns, "Hauptstraße"))
        assertNull(NaviTowns.correct(towns, "Bahnhofstr"))
    }
}

/** The same against the shipped list of all towns: typos set right, streets and places left alone. */
class NaviTownsRealListTest {
    private val towns = java.io.File("src/main/assets/navi/orte.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        .map { val f = it.split('\t'); NaviTowns.Town(f[0], f.getOrNull(1)?.toIntOrNull() ?: 0) }

    @Test fun typos() {
        assertEquals("Hamburg", NaviTowns.correct(towns, "hambrug"))
        assertEquals("Leipzig", NaviTowns.correct(towns, "leipizg"))
        assertEquals("Lübeck", NaviTowns.correct(towns, "lübekc"))
    }

    @Test fun leftAlone() {
        listOf("Potsdamer Straße", "Alexanderplatz", "Hauptstraße 5", "Bahnhofstr 12", "Brandenburger Tor", "Kurfürstendamm",
            "Friedrichstraße", "Unter den Linden", "Reeperbahn", "Hamburg", "Berlin Hauptbahnhof", "Tesla Supercharger").forEach {
            assertNull(it, NaviTowns.correct(towns, it))
        }
    }
}
