package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** O190: swapped letters, neighbour keys, umlauts, half typed words. */
class NaviFuzzyTest {
    private fun ok(q: String, t: String) = assertNotNull("$q ~ $t", NaviFuzzy.cost(q, t))
    private fun no(q: String, t: String) = assertNull("$q !~ $t", NaviFuzzy.cost(q, t))

    @Test fun swapped() = ok("hambrug", "Hamburg")
    @Test fun neighbourKey() = ok("potsdamrr platz", "Potsdamer Platz")
    @Test fun umlauts() = ok("muenchen", "München").also { ok("munchen", "München") }
    @Test fun halfTyped() = ok("alexan", "Alexanderplatz")
    @Test fun ownNameAndAddress() = ok("buero", "Büro, Friedrichstraße 12")
    @Test fun shortWordsExact() = no("abc", "Arbeit")
    @Test fun tooFar() = no("hamburg", "Delft")
    @Test fun exactBeatsTypo() = assertTrue(NaviFuzzy.cost("hamburg", "Hamburg")!! < NaviFuzzy.cost("hambrug", "Hamburg")!!)
}
