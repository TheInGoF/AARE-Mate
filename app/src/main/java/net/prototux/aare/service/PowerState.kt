package net.prototux.aare.service

import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * What Android may do to Mate's work with the screen off, in words for the log (19.11.2026, crackle with the tablet in
 * standby): whether Mate is kept out of the battery optimisation, the power saving mode, the device's idle state and how
 * hot it is (it slows the processor down when hot).
 */
object PowerState {
    /** Whether the screen is on, kept by the projection's screen watch (for the audio's log lines). */
    @Volatile @JvmStatic var screenOn: Boolean = true

    fun ignoringOptimisation(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    fun describe(context: Context): String {
        val pm = context.getSystemService(PowerManager::class.java) ?: return "no power manager"
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "cool"; PowerManager.THERMAL_STATUS_LIGHT -> "warm (light throttling)"
            PowerManager.THERMAL_STATUS_MODERATE -> "hot (throttling)"; PowerManager.THERMAL_STATUS_SEVERE -> "very hot (heavy throttling)"
            else -> "critical (${pm.currentThermalStatus})"
        } else "-"
        return "battery optimisation " + (if (ignoringOptimisation(context)) "off for Mate" else "ON for Mate (may be slowed)") +
            ", power saving " + (if (pm.isPowerSaveMode) "ON" else "off") +
            ", device idle " + (if (pm.isDeviceIdleMode) "yes" else "no") +
            ", interactive " + (if (pm.isInteractive) "yes" else "no") + ", temperature " + thermal
    }
}
