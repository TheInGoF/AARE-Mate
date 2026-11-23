package net.prototux.aare.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a reboot: starts the idle service (if the developer server is on) and plans the start of Shizuku. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        ProjectionService.startIdleIfEnabled(context)
        // after a restart Shizuku is gone: Mate starts it itself a little after the boot (System L-063)
        ShizukuStartJob.schedule(context)
    }
}
