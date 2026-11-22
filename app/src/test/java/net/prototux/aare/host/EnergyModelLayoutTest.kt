package net.prototux.aare.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The model as Google's server reads it (vehicle energy model v1.2) must meet Mate's own consumption (System I-079). */
class EnergyModelLayoutTest {
    private val physics = EnergyModel.Car(capacityWh = 86_000, socPercent = 55.0, outsideCelsius = 2f, gtx = true)
    private val curve = physics.copy(
        capacityWh = 84_200,
        curve = listOf(30.0 to 18.0, 50.0 to 17.5, 80.0 to 20.5, 100.0 to 25.6, 120.0 to 27.6, 130.0 to 29.0, 150.0 to 34.0),
    )

    @Test fun googleMeetsTheCurve() {
        for (kmh in listOf(50.0, 80.0, 100.0, 120.0, 130.0, 150.0)) {
            val want = EnergyModel.powerW(kmh, curve)
            val got = EnergyModel.googlePowerW(kmh, curve)
            assertEquals("at $kmh km/h", want, got, want * 0.06)
        }
    }

    @Test fun googleMeetsThePhysics() {
        for (kmh in listOf(50.0, 100.0, 130.0)) {
            val want = EnergyModel.powerW(kmh, physics) + EnergyModel.climateW(physics.outsideCelsius)
            val got = EnergyModel.googlePowerW(kmh, physics)
            assertEquals("at $kmh km/h", want, got, want * 0.06)
        }
    }

    @Test fun roadLoadIsSane() {
        val r = EnergyModel.roadLoad(physics)
        assertTrue("constant ${r[0]}", r[0] in 50.0..400.0)
        assertTrue("quadratic ${r[2]}", r[2] in 0.15..0.6)       // 0.5 x air x cd x A of an ID.7 is about 0.35
    }

    @Test fun layout() {
        val top = parse(EnergyModel.build(curve))
        assertEquals(listOf(1, 2, 3, 4, 5, 7, 8), top.map { it.first }.distinct())
        val info = parse(top.first { it.first == 1 }.second!!)
        val mass = parse(info.first { it.first == 2 }.second!!).first().third
        assertTrue("mass $mass", mass in 2000L..2800L)
        // charging curves ascending by power, points ascending by energy to full
        val curves = parse(top.first { it.first == 8 }.second!!).filter { it.first == 1 }.map { parse(it.second!!) }
        val powers = curves.map { c -> c.first { it.first == 1 }.third }
        assertEquals(powers.sorted(), powers)
        for (c in curves) {
            val energy = c.filter { it.first == 2 }.map { p -> parse(p.second!!).first { it.first == 1 }.third }
            assertEquals(energy.sorted(), energy)
        }
    }

    /** One level of a protobuf message: (field, bytes of a nested message or null, varint value). */
    private fun parse(b: ByteArray): List<Triple<Int, ByteArray?, Long>> {
        val out = ArrayList<Triple<Int, ByteArray?, Long>>()
        var i = 0
        fun varint(): Long { var r = 0L; var s = 0; while (true) { val x = b[i++].toInt() and 0xff; r = r or ((x and 0x7f).toLong() shl s); if (x < 0x80) return r; s += 7 } }
        while (i < b.size) {
            val tag = varint().toInt()
            when (tag and 7) {
                0 -> out += Triple(tag shr 3, null, varint())
                2 -> { val n = varint().toInt(); out += Triple(tag shr 3, b.copyOfRange(i, i + n), 0L); i += n }
                5 -> { out += Triple(tag shr 3, null, 0L); i += 4 }
                1 -> { out += Triple(tag shr 3, null, 0L); i += 8 }
            }
        }
        return out
    }
}
