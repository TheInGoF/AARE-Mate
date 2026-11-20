package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.spatialk.geojson.Position

/** 18.11.2026: the route over a charger refused - nearby chargers are tried one stop at a time, the nearest first. */
class ChargeSubstituteTest {
    private fun at(km: Double) = Position(longitude = 12.0, latitude = 50.0 + km / 111.2)
    private val a = Charger(at(0.0), 300, 8, "IONITY")
    private val b = Charger(at(100.0), 150, 4, "EnBW")
    private val nearA = Charger(at(3.0), 300, 6, "Tesla")
    private val farA = Charger(at(9.0), 150, 4, "Aral")
    private val nearB = Charger(at(102.0), 150, 2, "EWE")
    private val all = listOf(a, b, nearA, farA, nearB)
    private fun near(p: Position, r: Double, minKw: Int) = all.filter { ChargerIndex.distanceM(it.at, p) <= r && it.kw >= minKw }

    @Test fun oneStopAtATime() {
        val alts = ChargePlanner.substitutes(listOf(a, b), ::near, 100)
        assertEquals(listOf(nearA, b), alts[0])
        assertEquals(listOf(farA, b), alts[1])
        assertEquals(listOf(a, nearB), alts[2])
        assertEquals(3, alts.size)
    }

    @Test fun strongEnoughAndCapped() {
        assertTrue(ChargePlanner.substitutes(listOf(a, b), ::near, 200).all { l -> l.all { it.kw >= 200 } || l.contains(b) })
        assertEquals(1, ChargePlanner.substitutes(listOf(a, b), ::near, 100, max = 1).size)
    }
}
