package net.prototux.aare.car

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * Feeds the car's GPS to the phone's own location providers while connected, so every app
 * (CoMaps, Google Maps…) sees one position. Otherwise a navigation app mixes the car's position,
 * which AARE gives car apps, with the phone's own fix, and the map jumps between the two.
 *
 * Android only allows it for the app chosen as "mock location app" in Developer options.
 */
class PhoneLocationFeed(context: Context) {
    private val lm = context.getSystemService(LocationManager::class.java)
    private val providers = buildList {
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
    }
    private val active = HashSet<String>()
    @Volatile private var failed = false
    // How evenly the car's fixes come (test drive B2: the map jumped every few seconds - a guess is that Google Maps'
    // own location falls back to the network position when the car's fix pauses). Noted in the kept log at the end.
    private var fixes = 0
    private var lastFixMs = 0L
    private var gaps = 0
    private var longestGapMs = 0L

    /** A new car fix (any thread). */
    @Synchronized
    fun push(car: Location) {
        val now = SystemClock.elapsedRealtime()
        if (lastFixMs != 0L) {
            val gap = now - lastFixMs
            if (gap > 2_000) gaps++
            longestGapMs = maxOf(longestGapMs, gap)
        }
        lastFixMs = now
        fixes++
        if (failed) return
        if (active.isEmpty()) { start(); watch() }
        for (p in active) {
            val l = Location(car).apply {
                provider = p
                if (!hasAccuracy()) accuracy = 5f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            runCatching { lm.setTestProviderLocation(p, l) }.onFailure { Log.w(TAG, "setTestProviderLocation $p", it) }
        }
    }

    // The boolean form works on every Android version; Criteria's POWER_LOW and ACCURACY_FINE have
    // the same values as the ProviderProperties constants lint expects.
    @Suppress("DEPRECATION")
    @SuppressLint("WrongConstant")
    private fun start() {
        for (p in providers) {
            runCatching {
                lm.addTestProvider(p, false, false, false, false, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
                lm.setTestProviderEnabled(p, true)
                active += p
            }.onFailure { Log.w(TAG, "addTestProvider $p", it) }
        }
        // Not the mock location app: don't keep trying for every fix.
        if (active.isEmpty()) failed = true
    }

    /**
     * 17.11.2026 ("wenn ich nicht mit dem Fahrzeug verbunden bin, brauchen die Apps ja auch eine Adresse ... dann muss Mate
     * das GPS hernehmen ... Zirkelbezug?"): Mate's position stands in only while the car sends fixes. When they stop for
     * [STALE_MS] (the car's GPS lost, a hang) the stand-in is taken away and the phone's own GPS counts again; the next
     * car fix puts it back. Disconnected, nothing of Mate's is in the way at all.
     */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val check = object : Runnable {
        override fun run() {
            synchronized(this@PhoneLocationFeed) {
                if (active.isEmpty()) return
                if (SystemClock.elapsedRealtime() - lastFixMs > STALE_MS) {
                    for (p in active) runCatching { lm.removeTestProvider(p) }
                    active.clear()
                    net.prototux.aare.DiagLog.add("car location: no fix for ${STALE_MS / 1000} s, the phone's own GPS counts again")
                    return
                }
            }
            main.postDelayed(this, 2_000)
        }
    }
    private fun watch() { main.removeCallbacks(check); main.postDelayed(check, 2_000) }

    @Synchronized
    fun stop() {
        main.removeCallbacks(check)
        if (fixes > 0) net.prototux.aare.DiagLog.add("car location: $fixes fixes, $gaps pauses over 2 s, longest ${longestGapMs / 1000.0} s" +
            if (failed) " (not passed on: Mate is not the mock location app)" else "")
        fixes = 0; gaps = 0; longestGapMs = 0; lastFixMs = 0
        for (p in active) runCatching { lm.removeTestProvider(p) }
        active.clear()
        failed = false
    }

    companion object {
        private const val TAG = "AareLocation"
        private const val STALE_MS = 10_000L

        /**
         * A stand-in left behind (Mate ended while connected): taken away when Mate starts, so the phone's GPS is the
         * phone's again. Quietly does nothing when Mate is not the mock location app.
         */
        fun clearLeftovers(context: Context) {
            val lm = context.getSystemService(LocationManager::class.java)
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused").forEach { p -> runCatching { lm.removeTestProvider(p) } }
        }

        /** AARE is the phone's mock location app (Developer options > Select mock location app). */
        fun allowed(context: Context): Boolean = runCatching {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
                else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }
}
