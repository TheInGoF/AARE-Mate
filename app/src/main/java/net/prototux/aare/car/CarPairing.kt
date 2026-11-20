package net.prototux.aare.car

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import net.prototux.aare.DiagLog

/**
 * The Bluetooth pairing with the car, from its code to the bond (18.11.2026: "Er hat zwar den Kopplungscode angezeigt,
 * ich habe den Kopplungscode bestätigt, aber danach war dann vorbei").
 *
 * After Mate has begun the bond (P-072), the car sends its code over Android Auto and then waits for the phone to say
 * whether the code is the one the phone shows - only then does it confirm the pairing on its side. Mate never answered:
 * the car gave up after half a minute and sent its code again (the log of 18.11.: "the car sent its pairing code" twice,
 * no bond). Android Auto answers at once, having compared the code with the one the tablet got; Mate does the same, and
 * the driver still confirms on the tablet (only a system app may confirm for him).
 *
 * The diagnosis log says how it ends: codes alike or not, the bond made or failed (with Android's reason), and whether
 * the car then holds the tablet as its hands-free phone.
 */
class CarPairing(
    private val context: Context,
    private val carAddress: String,
    /** Tells the car the answer to its code: true = the same code on the tablet (or no other code seen). */
    private val answer: (ok: Boolean) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var carCode: String? = null
    private var carCodeAt = 0L
    private var tabletKey: String? = null
    private var tabletKeyAt = 0L
    private val timedOut = Runnable { decide(timedOut = true) }
    private var answered = false
    private var registered = false
    private var bonding = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev = device(i) ?: return
            if (!dev.address.equals(carAddress, ignoreCase = true)) return
            when (i.action) {
                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    val key = i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, Int.MIN_VALUE)
                    val variant = i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                    tabletKey = if (key == Int.MIN_VALUE) null else key.toString()
                    tabletKeyAt = android.os.SystemClock.elapsedRealtime()
                    DiagLog.add("car bluetooth: the tablet asks to confirm the pairing (way $variant${if (tabletKey != null) ", with a code" else ""})")
                    decide(timedOut = false)
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val state = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    val before = i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                    when (state) {
                        BluetoothDevice.BOND_BONDING -> bonding = true
                        BluetoothDevice.BOND_BONDED -> {
                            bonding = false
                            DiagLog.add("car bluetooth: paired with the car")
                            // the car connects its hands-free profile on its own after pairing; see if it did
                            main.postDelayed({ DiagLog.add("car bluetooth: ${OwnBluetooth.phoneState(context)}") }, 8_000)
                        }
                        BluetoothDevice.BOND_NONE -> if (before == BluetoothDevice.BOND_BONDING || bonding) {
                            bonding = false
                            // hidden extra BluetoothDevice.EXTRA_REASON ("android.bluetooth.device.extra.REASON")
                            val reason = i.getIntExtra("android.bluetooth.device.extra.REASON", -1)
                            DiagLog.add("car bluetooth: the pairing with the car failed (${bondFailure(reason)})")
                            carCode = null; tabletKey = null; answered = false
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Synchronized fun start() {
        if (registered) return
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            // before the system's own dialog takes the ordered broadcast
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, f)
            registered = true
        }.onFailure { DiagLog.add("car bluetooth: the pairing cannot be followed (${it.javaClass.simpleName})") }
    }

    @Synchronized fun stop() {
        main.removeCallbacksAndMessages(null)
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    /** The car's code arrived (it may come again when the car tries anew: then it is answered anew). */
    fun onCarCode(code: String) {
        carCode = code
        carCodeAt = android.os.SystemClock.elapsedRealtime()
        answered = false
        main.removeCallbacks(timedOut)
        decide(timedOut = false)
        // the tablet's own request comes at about the same moment; without it, answer all the same after a little while
        main.postDelayed(timedOut, WAIT_FOR_TABLET_MS)
    }

    private fun decide(timedOut: Boolean) {
        val car = carCode ?: return
        if (answered) return
        // the tablet's code of this attempt only: a new try of the car brings a new code, the last one would differ
        val tablet = tabletKey?.takeIf { kotlin.math.abs(tabletKeyAt - carCodeAt) < SAME_ATTEMPT_MS }
        val ok = when (val alike = sameCode(car, tablet)) {
            null -> if (timedOut) true else return
            else -> alike
        }
        answered = true
        answer(ok)
        DiagLog.add("car bluetooth: " + when {
            tablet == null -> "the car's code was answered as confirmed (the tablet showed none to compare) - confirm on the tablet"
            ok -> "the car's code and the tablet's are the same - told the car; confirm on the tablet"
            else -> "the car's code and the tablet's differ - told the car, the pairing is refused"
        })
        if (ok && tablet != null) confirmOnTablet()
    }

    /** Only a system app may confirm for the driver (BLUETOOTH_PRIVILEGED); in the own ROM Mate could (Build/ROM). */
    @SuppressLint("MissingPermission")
    private fun confirmOnTablet() {
        runCatching {
            val dev = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.getRemoteDevice(carAddress)
            if (dev?.setPairingConfirmation(true) == true) DiagLog.add("car bluetooth: Mate confirmed the pairing itself")
        }
    }

    @Suppress("DEPRECATION")
    private fun device(i: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    companion object {
        const val WAIT_FOR_TABLET_MS = 2_500L
        /** The car's code and the tablet's belong to one attempt when this close in time. */
        const val SAME_ATTEMPT_MS = 15_000L

        /**
         * Whether the car's code and the tablet's are the same number: null when either is unknown. Leading zeros and
         * anything but digits do not count (the car may send "012345", Android hands over 12345).
         */
        fun sameCode(car: String?, tablet: String?): Boolean? {
            val a = car?.filter(Char::isDigit)?.trimStart('0')
            val b = tablet?.filter(Char::isDigit)?.trimStart('0')
            if (car.isNullOrBlank() || tablet.isNullOrBlank() || a == null || b == null) return null
            if (car.none(Char::isDigit) || tablet.none(Char::isDigit)) return null
            return a == b
        }

        /** Android's hidden unbond reasons (BluetoothDevice.UNBOND_REASON_*), in words for the log. */
        fun bondFailure(reason: Int): String = when (reason) {
            1 -> "authentication failed - codes not confirmed"
            2 -> "rejected by the car"
            3 -> "cancelled"
            4 -> "the car did not answer"
            5 -> "the car was busy pairing"
            6 -> "authentication timed out - not confirmed in time"
            7 -> "too many attempts"
            8 -> "the car sent no proper answer"
            9 -> "removed"
            else -> "reason $reason"
        }
    }
}
