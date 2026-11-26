package net.prototux.aare

import android.app.Application

class AareApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        DiagLog.init(this)
        // Mate's colours are read by screens outside IdmateShell too
        net.prototux.aare.ui.car.Mate.init(this)
        net.prototux.aare.audio.AudioTrials.load(this)
        // which Mate runs, once per start (System L-075: bugs reported against an older version stay recognisable)
        val mainProcess = android.os.Build.VERSION.SDK_INT < 28 || getProcessName() == packageName
        // with the tablet's time since its start: a start of the tablet itself shows (System N-040, I-136)
        if (mainProcess) DiagLog.add("Mate started: ${versionLine()}, tablet up ${android.os.SystemClock.elapsedRealtime() / 60_000} min")
        if (mainProcess) readyAfterRestart()
        lastExit()
        // what Mate gives back when the tablet's memory is tight (Memory)
        Memory.givers += { if (net.prototux.aare.dash.DashData.dropParkedStage()) "the dashboard's page kept behind another area" else null }
        Memory.givers += { if (net.prototux.aare.speech.Dictation.Model.release()) "the speech recognition" else null }
        // the car's position as the phone's, left behind by an earlier run of Mate: the phone's own GPS again
        net.prototux.aare.car.PhoneLocationFeed.clearLeftovers(this)
        // a Wi-Fi network coming later starts Shizuku too (E-045)
        if (mainProcess) net.prototux.aare.adb.SelfAdb.watchWifi(this)
        val once = getSharedPreferences("mate_once", MODE_PRIVATE)
        // the car's Wi-Fi left out (System I-131, 26.11.2026): wireless Android Auto never got through on the tablet, and a
        // car Wi-Fi without internet kept the other apps offline - mobile data only, switched off once per installation
        if (mainProcess && !once.getBoolean("car_wifi_off", false)) {
            Settings(this).wireless = false
            Settings(this).carInternet = false
            runCatching { net.prototux.aare.transport.wireless.CarInternet.withdraw(this) }
            once.edit().putBoolean("car_wifi_off", true).apply()
            DiagLog.add("car Wi-Fi: left out - wireless Android Auto and the car's internet off, mobile data only (can be switched on again)", area = "NET")
        }
        // the wallpaper of the Android Auto style, left behind since the style went (E-044): looked for once per installation
        if (!once.getBoolean("wallpaper_gone", false)) Thread {
            runCatching { java.io.File(filesDir, "wallpaper.jpg").takeIf { it.exists() }?.let { if (it.delete()) DiagLog.add("old wallpaper removed") } }
            once.edit().putBoolean("wallpaper_gone", true).apply()
        }.start()
    }

    /**
     * Whether the tablet comes up by itself after a restart (System L-108, 26.11.2026: "Strom an → startet von selbst →
     * Internet → Mate läuft"): a screen lock keeps every app, Mate too, from running until it is unlocked, and a SIM PIN
     * keeps mobile data off. Said in the log when one of them stands in the way.
     */
    private fun readyAfterRestart() = runCatching {
        val lock = getSystemService(android.app.KeyguardManager::class.java)?.isDeviceSecure == true
        val sim = getSystemService(android.telephony.TelephonyManager::class.java)?.simState
        val pin = sim == android.telephony.TelephonyManager.SIM_STATE_PIN_REQUIRED || sim == android.telephony.TelephonyManager.SIM_STATE_PUK_REQUIRED
        if (lock || pin) DiagLog.add("after a restart: " + listOfNotNull(
            if (lock) "the tablet has a screen lock (PIN, pattern or password) - Mate only runs once it is unlocked" else null,
            if (pin) "the SIM card waits for its PIN - no mobile data until it is typed" else null).joinToString("; "), area = "POWER")
    }

    /**
     * Why Mate ended last time, into the log (18.11.2026: "Testsätze sprechen - auf dem Gerät ist die App abgestürzt";
     * the log had nothing of it). A crash of Kotlin/Java, a native one (speech models), memory, an ANR: with the
     * first lines of the trace where Android keeps one. Each end is written once.
     */
    private fun lastExit() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = getSystemService(android.app.ActivityManager::class.java)
            val prefs = getSharedPreferences("mate_exit", MODE_PRIVATE)
            val seen = prefs.getLong("seen", 0)
            val bad = setOf(android.app.ApplicationExitInfo.REASON_CRASH, android.app.ApplicationExitInfo.REASON_CRASH_NATIVE,
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY, android.app.ApplicationExitInfo.REASON_ANR,
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE)
            val ends = am.getHistoricalProcessExitReasons(packageName, 0, 5).filter { it.timestamp > seen }
            for (e in ends.reversed()) {
                if (e.reason !in bad && !(e.reason == android.app.ApplicationExitInfo.REASON_SIGNALED && e.importance <= 125)) continue
                val why = when (e.reason) {
                    android.app.ApplicationExitInfo.REASON_CRASH -> "crash"
                    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
                    android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "ended for memory"
                    android.app.ApplicationExitInfo.REASON_ANR -> "not responding"
                    android.app.ApplicationExitInfo.REASON_SIGNALED -> "killed by signal ${e.status} in front"
                    else -> "ended (${e.reason})"
                }
                val trace = runCatching { e.traceInputStream?.bufferedReader()?.useLines { l -> l.filter { it.isNotBlank() }.take(12).toList() } }.getOrNull()
                    ?.joinToString(" | ") { it.trim().take(160) }
                // the dashboard's browser part (WebView renderer, its own process): Mate itself ran on, the page was rebuilt
                // (18.11.2026: such lines read as if Mate had ended)
                val renderer = e.processName.contains("webview", ignoreCase = true) || e.processName.contains("sandboxed_process")
                if (renderer) {
                    DiagLog.add("dashboard page: its browser part was $why at ${java.text.SimpleDateFormat("dd.MM. HH:mm:ss", java.util.Locale.GERMANY).format(java.util.Date(e.timestamp))}, " +
                        "%d MB in use - Mate ran on, the page was built anew".format(java.util.Locale.ROOT, maxOf(e.pss, e.rss) / 1024))
                    continue
                }
                DiagLog.add("last end of Mate: $why at ${java.text.SimpleDateFormat("dd.MM. HH:mm:ss", java.util.Locale.GERMANY).format(java.util.Date(e.timestamp))}, " +
                    // which of Mate's processes it was and how much it held (the system often has no number for one it ended itself)
                    "process ${e.processName.removePrefix(packageName).ifEmpty { "main" }}, %d MB in use, importance %d".format(java.util.Locale.ROOT, maxOf(e.pss, e.rss) / 1024, e.importance) + (e.description?.let { " - ${it.take(200)}" } ?: "") + (trace?.let { " - $it" } ?: ""))
            }
            ends.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("seen", it).apply() }
        }
    }

    companion object {
        lateinit var instance: AareApp
            private set

        /** Mate's version for the log: name, code, debug build, Android (System L-075). */
        fun versionLine(): String = "version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}" +
            (if (BuildConfig.DEBUG) ", debug build" else "") + "), Android ${android.os.Build.VERSION.RELEASE}"
    }
}
