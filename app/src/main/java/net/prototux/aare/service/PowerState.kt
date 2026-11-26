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

    /**
     * The tablet's battery in words (System I-135, 26.11.2026: "morgens fast voll und nachmittags leer" - the log said
     * nothing of the battery): level, and whether and how it is charging.
     */
    fun battery(context: Context): String {
        val i = runCatching { context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
            ?: return "battery unknown"
        val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val plugged = when (i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0)) {
            android.os.BatteryManager.BATTERY_PLUGGED_USB -> "charging over USB"
            android.os.BatteryManager.BATTERY_PLUGGED_AC -> "charging (power supply)"
            android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS -> "charging (wireless)"
            0 -> "on battery"
            else -> "charging"
        }
        val full = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, 0) == android.os.BatteryManager.BATTERY_STATUS_FULL
        return "battery ${if (level >= 0) "${level * 100 / scale} %" else "?"}, " + (if (full) "full" else plugged)
    }

    /** The battery level in percent, -1 unknown. */
    fun level(context: Context): Int = runCatching {
        context.getSystemService(android.os.BatteryManager::class.java).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrDefault(-1)

    fun describe(context: Context): String {
        val pm = context.getSystemService(PowerManager::class.java) ?: return "no power manager"
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "cool"; PowerManager.THERMAL_STATUS_LIGHT -> "warm (light throttling)"
            PowerManager.THERMAL_STATUS_MODERATE -> "hot (throttling)"; PowerManager.THERMAL_STATUS_SEVERE -> "very hot (heavy throttling)"
            else -> "critical (${pm.currentThermalStatus})"
        } else "-"
        return battery(context) + ", battery optimisation " + (if (ignoringOptimisation(context)) "off for Mate" else "ON for Mate (may be slowed)") +
            ", power saving " + (if (pm.isPowerSaveMode) "ON" else "off") +
            ", device idle " + (if (pm.isDeviceIdleMode) "yes" else "no") +
            ", interactive " + (if (pm.isInteractive) "yes" else "no") + ", temperature " + thermal
    }
}
