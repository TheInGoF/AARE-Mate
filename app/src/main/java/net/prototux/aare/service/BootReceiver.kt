package net.prototux.aare.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms wireless listening after a reboot, if the user enabled it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        ProjectionService.startIdleIfEnabled(context)
        // after a restart Shizuku is gone: Mate starts it itself once Wi-Fi is there (System L-063)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ net.prototux.aare.adb.SelfAdb.ensureShizuku(context, "tablet started") }, 45_000)
    }
}
