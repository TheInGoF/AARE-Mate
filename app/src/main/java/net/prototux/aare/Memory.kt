package net.prototux.aare

import android.app.ActivityManager
import android.content.Context
import android.util.Log

/**
 * The tablet's memory (18.11.2026: "Die App läuft aktuell auch ziemlich unperformant"; the drive's log of that day:
 * "last end of Mate: ended for memory" eight times, five of them within 17 s after "Fehler markieren" had loaded the
 * speech recognition beside the map, the dashboard's page and the voice). The system does not tell a running app any
 * more that memory runs short, so Mate looks itself every 10 s and, when it is tight, gives back what it can do
 * without: the dashboard's page kept behind another area, a speech recognition not in use. What it found is in the
 * diagnosis log.
 */
object Memory {
    private const val TAG = "MateMemory"
    private const val MB = 1024 * 1024L
    private var saidAt = 0L
    private var first = true

    /** What is given back when memory is tight; each says what it freed, or null. Set by the parts that hold it. */
    val givers = java.util.concurrent.CopyOnWriteArrayList<() -> String?>()

    class State(val freeMb: Long, val totalMb: Long, val limitMb: Long, val low: Boolean) {
        /** Tight: the system calls it low, or less than twice its limit (at the limit it begins to end apps) is free. */
        val tight: Boolean get() = low || freeMb < 2 * limitMb
    }

    fun state(context: Context): State? = runCatching {
        val mi = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        State(mi.availMem / MB, mi.totalMem / MB, mi.threshold / MB, mi.lowMemory)
    }.getOrNull()

    private fun own(): String {
        val rt = Runtime.getRuntime()
        return "Mate holds %d MB in Java and %d MB beside it".format((rt.totalMemory() - rt.freeMemory()) / MB, android.os.Debug.getNativeHeapAllocatedSize() / MB) + held()
    }

    /**
     * What large is loaded right now (18.11.2026: after the fault note some 490 MB stayed "beside" Java with the speech
     * recognition given back - which part holds them was not to be told from the log).
     */
    private fun held(): String {
        val parts = buildList {
            if (runCatching { net.prototux.aare.speech.Dictation.Model.inMemory }.getOrDefault(false)) add("the speech recognition")
            runCatching { net.prototux.aare.speech.LocalTts.inMemory }.getOrNull()?.let { add("the voice $it") }
            if (runCatching { net.prototux.aare.ui.car.navi.NaviKeep.guiding.value }.getOrDefault(false)) add("a route being guided")
        }
        return if (parts.isEmpty()) "" else " (loaded: ${parts.joinToString()})"
    }

    /** Looked at every 10 s while Mate runs in the car. */
    fun check(context: Context, now: Long = android.os.SystemClock.elapsedRealtime()) {
        val s = state(context) ?: return
        if (first) { first = false; DiagLog.add("memory: ${s.freeMb} of ${s.totalMb} MB free (the system's limit ${s.limitMb} MB), ${own()}") }
        if (!s.tight) return
        val freed = shed()
        // said once a minute at most, and whenever something was given back
        if (freed.isNotEmpty() || now - saidAt > 60_000L) {
            saidAt = now
            DiagLog.add("memory: tight - ${s.freeMb} of ${s.totalMb} MB free (limit ${s.limitMb} MB), ${own()}" +
                if (freed.isEmpty()) "; nothing left to give back" else "; given back: " + freed.joinToString())
        }
    }

    /** Gives back what Mate can do without right now; what was freed. Also before something large is loaded. */
    fun shed(): List<String> = givers.mapNotNull { g -> runCatching { g() }.onFailure { Log.w(TAG, "giving back failed", it) }.getOrNull() }

    /** Before loading something large ([what]): when memory is tight already, room is made first. */
    fun makeRoom(context: Context, what: String) {
        val s = state(context) ?: return
        if (!s.tight) return
        val freed = shed()
        DiagLog.add("memory: tight before loading $what - ${s.freeMb} of ${s.totalMb} MB free" + if (freed.isEmpty()) "" else "; given back: " + freed.joinToString())
    }
}
