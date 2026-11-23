package net.prototux.aare

import android.app.Application

class AareApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        DiagLog.init(this)
        // Mate's colours are read by screens that do not pass IdmateShell (the Android Auto and CarPlay styles)
        net.prototux.aare.ui.car.Mate.init(this)
        net.prototux.aare.audio.AudioTrials.load(this)
        // which Mate runs, once per start (System L-075: bugs reported against an older version stay recognisable)
        if (android.os.Build.VERSION.SDK_INT < 28 || getProcessName() == packageName) DiagLog.add("Mate started: ${versionLine()}")
        lastExit()
        // what Mate gives back when the tablet's memory is tight (Memory)
        Memory.givers += { if (net.prototux.aare.dash.DashData.dropParkedStage()) "the dashboard's page kept behind another area" else null }
        Memory.givers += { if (net.prototux.aare.speech.Dictation.Model.release()) "the speech recognition" else null }
        // the car's position as the phone's, left behind by an earlier run of Mate: the phone's own GPS again
        net.prototux.aare.car.PhoneLocationFeed.clearLeftovers(this)
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
