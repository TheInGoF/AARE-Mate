package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Test

class ChargerJudgedTest {
    @Test fun starsWin() {
        assertEquals(5, ChargerVisits.judged(5, power = 3, price = 3, worked = 2))
        assertEquals(1, ChargerVisits.judged(1, power = 1, price = 1, worked = 1))
    }

    @Test fun badDetailsRankLast() {
        assertEquals(0, ChargerVisits.rank(ChargerVisits.judged(0, power = 0, price = 0, worked = 2)))
        assertEquals(0, ChargerVisits.rank(ChargerVisits.judged(0, power = 3, price = 1, worked = 1)))
    }

    @Test fun goodDetailsRankFirst() {
        assertEquals(2, ChargerVisits.rank(ChargerVisits.judged(0, power = 1, price = 1, worked = 1)))
    }

    @Test fun halfToldStaysUnrated() {
        assertEquals(0, ChargerVisits.judged(0, 0, 0, 0))
        assertEquals(0, ChargerVisits.judged(0, power = 1, price = 3, worked = 1))
        assertEquals(1, ChargerVisits.rank(ChargerVisits.judged(0, power = 2, price = 2, worked = 1)))
    }
}
