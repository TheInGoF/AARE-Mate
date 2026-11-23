package net.prototux.aare.transport.wireless

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.ParcelUuid
import androidx.core.content.IntentCompat

/**
 * Notices a wireless head unit coming into Bluetooth range, for the manifest-declared receiver of [ACTIONS]: when a bonded
 * device connects (the car's hands-free link usually comes up on its own) or its services arrive, [headUnitIn] tells
 * whether it publishes the Android Auto wireless service. Starting the session is the caller's decision.
 * ACL_CONNECTED and ACTION_UUID are among the implicit broadcasts still delivered to manifest receivers.
 */
object WirelessAutoStarter {
    val ACTIONS = listOf(BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_UUID)

    /**
     * For a manifest-declared receiver: the head unit [intent] (one of [ACTIONS]) is about, or
     * null. Uses the services Android has cached, or those carried by ACTION_UUID.
     */
    @SuppressLint("MissingPermission")
    fun headUnitIn(intent: Intent): BluetoothDevice? {
        val device = deviceOf(intent) ?: return null
        return when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED ->
                device.takeIf { it.bondState == BluetoothDevice.BOND_BONDED && WirelessConnector.isHeadUnit(it) }
            BluetoothDevice.ACTION_UUID -> {
                val uuids = IntentCompat.getParcelableArrayExtra(intent, BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java)
                    ?.filterIsInstance<ParcelUuid>()?.toTypedArray()
                // Any app's SDP query broadcasts this, for any nearby device. BONDING, not only BONDED: on
                // first pairing the UUIDs arrive before Android reports the bond, and nothing else fires then.
                device.takeIf {
                    it.bondState != BluetoothDevice.BOND_NONE &&
                        (WirelessConnector.hasAaUuid(uuids) || WirelessConnector.isHeadUnit(it))
                }
            }
            else -> null
        }
    }

    private fun deviceOf(intent: Intent): BluetoothDevice? =
        IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
}
