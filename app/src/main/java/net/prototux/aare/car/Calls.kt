package net.prototux.aare.car

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager

/**
 * Phone calls from the car through Android's telephony, so their sound goes through the car's
 * Bluetooth hands-free link as with Android Auto. Needs CALL_PHONE, ANSWER_PHONE_CALLS and
 * READ_PHONE_STATE; every call fails quietly without them.
 */
@SuppressLint("MissingPermission")
object Calls {
    /** The last number dialled from the car, and when (elapsedRealtime): outgoing calls don't tell us their number. */
    @Volatile var lastPlaced: Pair<String, Long>? = null
        private set

    fun place(context: Context, number: String) {
        lastPlaced = number to android.os.SystemClock.elapsedRealtime()
        runCatching { context.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), Bundle()) }
    }

    fun answer(context: Context) {
        runCatching { context.getSystemService(TelecomManager::class.java).acceptRingingCall() }
    }

    /** Hangs up or declines. TelecomManager.endCall is Android 9+: on Android 8 the car's or phone's own buttons do it. */
    @Suppress("DEPRECATION") // still the way to hang up (or decline) without being the dialer
    fun end(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching { context.getSystemService(TelecomManager::class.java).endCall() }
    }

    /** Whether [end] works on this phone. */
    val canEnd: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /** TelephonyManager.CALL_STATE_IDLE, _RINGING or _OFFHOOK. */
    @Suppress("DEPRECATION")
    fun state(context: Context): Int = runCatching { context.getSystemService(TelephonyManager::class.java).callState }
        .getOrDefault(TelephonyManager.CALL_STATE_IDLE)

    /**
     * How loud Mate's own prompts are while somebody is on the phone (18.11.2026: "Die Musik hält an, wenn ich
     * telefoniere, aber die Navi-Ansagen quatschen immer noch in voller Lautstärke dazwischen"): half (-6 dB) during a
     * call or while it rings - also a call of a messenger -, 1 otherwise (18.11.2026, calls over the car now: "dass die
     * Navigationsansagen nur halb so laut sind. Also, dass man das vielleicht noch wahrnimmt, dass er was von einem wollte,
     * aber nicht, dass er die ganze Zeit voll ins Telefonat reinspricht" - it was a quarter).
     */
    fun promptFactor(context: Context): Float {
        val mode = runCatching { context.getSystemService(android.media.AudioManager::class.java).mode }.getOrDefault(android.media.AudioManager.MODE_NORMAL)
        val busy = mode == android.media.AudioManager.MODE_IN_CALL || mode == android.media.AudioManager.MODE_IN_COMMUNICATION ||
            mode == android.media.AudioManager.MODE_RINGTONE || state(context) != TelephonyManager.CALL_STATE_IDLE
        if (busy) net.prototux.aare.DiagLog.add("call: Mate's prompt at half volume during the call")
        return if (busy) DURING_CALL else 1f
    }

    const val DURING_CALL = 0.5f
}
