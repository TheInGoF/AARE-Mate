package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.spatialk.geojson.Position

/** Navi L-029 step B: the chargers by the route, found on the device. */
class ChargeCorridorTest {
    private val km = 200
    private val track = ChargePlanner.Track((0..km).map { Position(longitude = it / 111.32, latitude = 0.0) },
        DoubleArray(km + 1) { it * 1000.0 }, DoubleArray(km + 1) { it * 36.0 }, DoubleArray(km + 1) { it * 180.0 })
    private fun c(atKm: Double, offM: Double = 0.0, kw: Int = 150, op: String = "mate-test") =
        Charger(Position(longitude = atKm / 111.32, latitude = offM / 111_320.0), kw, 4, op)
    private fun near(all: List<Charger>): (Position, Double, Int) -> List<Charger> =
        { p, r, kw -> all.filter { it.kw >= kw && ChargerIndex.distanceM(it.at, p) <= r } }

    @Test fun foundAlongWithTheWayOff() {
        val a = c(50.0, offM = 600.0); val b = c(120.0)
        val got = ChargeCorridor.candidates(track, near(listOf(b, a, c(150.0, offM = 4_000.0))), ChargePlanner.Rules())
        assertEquals(listOf(a, b), got.map { it.charger })
        assertEquals(50_000.0, got[0].alongM, 1_000.0)
        assertEquals(600.0, got[0].offM, 60.0)
        assertEquals(2 * 600.0 * ChargeCorridor.ROAD, got[0].detourM, 200.0)
        assertTrue(!got[0].exact && got[1].offM < 50.0)
    }

    @Test fun strictNetworksOnly() {
        val r = ChargePlanner.Rules(preferred = setOf("ionity"), only = true)
        val got = ChargeCorridor.candidates(track, near(listOf(c(60.0, op = "IONITY GmbH"), c(70.0, op = "EnBW"))), r)
        assertEquals(listOf("ionity"), got.map { it.charger.network })
    }

    @Test fun manyInOnePlaceThinnedToTheBest() {
        val many = (0 until 12).map { c(80.0 + it * 0.1, kw = 100 + it * 20) }
        val got = ChargeCorridor.candidates(track, near(many), ChargePlanner.Rules())
        assertTrue("${got.size}", got.size <= 6)
        assertTrue(got.maxOf { it.charger.kw } == 320)
    }

    @Test fun ownStopAtAWeakChargerKept() {
        val weak = c(90.0, kw = 22)
        val got = ChargeCorridor.candidates(track, near(listOf(weak, c(100.0))), ChargePlanner.Rules(), mandatory = listOf(weak))
        assertTrue(got.first { it.charger === weak }.mandatory)
    }

    @Test fun sameOrderWhateverTheLookupGives() {
        val all = (0 until 30).map { c(10.0 + it * 5.0, offM = (it % 4) * 300.0, kw = 150 + (it % 3) * 50) }
        val a = ChargeCorridor.candidates(track, near(all), ChargePlanner.Rules()).map { it.charger }
        val b = ChargeCorridor.candidates(track, near(all.reversed()), ChargePlanner.Rules()).map { it.charger }
        assertEquals(a, b)
    }
}

/** Navi L-029 step B: the map server's matrix gives the real way to chargers off the route (org.json: Robolectric). */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36])
class ChargeMatrixTest {
    @Test fun waysFromTheAnswer() {
        val track = ChargePlanner.Track((0..10).map { Position(longitude = it / 111.32, latitude = 0.0) },
            DoubleArray(11) { it * 1000.0 }, DoubleArray(11) { it * 36.0 }, DoubleArray(11) { it * 180.0 })
        val ch = Charger(Position(longitude = 5 / 111.32, latitude = 0.02), 150, 4, "x")
        val cand = ChargeCorridor.Candidate(ch, 5, 5_000.0, 2_200.0, 5_720.0, 412.0)
        assertTrue(ChargeMatrix.request(listOf(cand), track).contains("sources_to_targets").not())
        val got = ChargeMatrix.apply(listOf(cand, cand), """{"sources_to_targets":[[{"distance":3.1,"time":240},{}],[{},{"distance":null}]]}""")
        assertEquals(6_200.0, got[0].detourM, 1e-6)
        assertEquals(480.0, got[0].detourS, 1e-6)
        assertTrue(got[0].exact && !got[1].exact)
    }
}
