package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 18.11.2026: the car's pairing code against the one the tablet shows, before Mate answers the car. */
class CarPairingTest {
    @Test fun sameNumber() {
        assertEquals(true, CarPairing.sameCode("123456", "123456"))
        // the car may keep leading zeros, Android hands over a number
        assertEquals(true, CarPairing.sameCode("012345", "12345"))
        assertEquals(true, CarPairing.sameCode(" 123 456 ", "123456"))
    }

    @Test fun otherNumber() {
        assertEquals(false, CarPairing.sameCode("123456", "654321"))
    }

    @Test fun unknownWhenEitherIsMissing() {
        assertNull(CarPairing.sameCode("123456", null))
        assertNull(CarPairing.sameCode(null, "123456"))
        assertNull(CarPairing.sameCode("", "123456"))
        assertNull(CarPairing.sameCode("abc", "123456"))
    }

    @Test fun reasonsInWords() {
        assertTrue(CarPairing.bondFailure(6).contains("timed out"))
        assertFalse(CarPairing.bondFailure(42).isBlank())
    }
}
