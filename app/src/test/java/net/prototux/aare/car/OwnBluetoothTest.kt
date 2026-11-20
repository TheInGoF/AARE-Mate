package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 18.11.2026: this device's own Bluetooth address for the car's handshake, from what the system's setting prints. */
class OwnBluetoothTest {
    @Test fun addressFromTheSettingsCommand() {
        assertEquals("A4:5E:60:C1:02:9F", OwnBluetooth.parse("a4:5e:60:c1:02:9f\n"))
        assertEquals("A4:5E:60:C1:02:9F", OwnBluetooth.parse("  A4:5E:60:C1:02:9F  \n"))
    }
    @Test fun nothingUsable() {
        assertNull(OwnBluetooth.parse("null\n"))
        assertNull(OwnBluetooth.parse(""))
        assertNull(OwnBluetooth.parse(null))
        assertNull("Android's placeholder is no address", OwnBluetooth.parse("02:00:00:00:00:00"))
        assertNull(OwnBluetooth.parse("Security exception: Permission denial"))
    }
    @Test fun valid() {
        assertTrue(OwnBluetooth.valid("A4:5E:60:C1:02:9F"))
        assertFalse(OwnBluetooth.valid("A4:5E:60:C1:02"))
        assertFalse(OwnBluetooth.valid("02:00:00:00:00:00"))
    }
}
