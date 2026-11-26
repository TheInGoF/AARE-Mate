package net.prototux.aare.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import net.prototux.aare.DiagLog
import net.prototux.aare.R

/**
 * Loading the offline playlists in the foreground (System I-142, 26.11.2026: "warum er beim Music-Download von Offline-Musik
 * immer wieder abbricht oder pausiert oder nicht mehr weitermacht"): before, a plain thread of the settings page or of the
 * six-hourly job loaded the songs - with the screen off the tablet went to sleep and Android cut the net, and the job was
 * stopped after about ten minutes. Now a foreground service with a notification holds the processor (and the Wi-Fi) awake
 * until the lists are through, started by "Jetzt abgleichen", by a list switched on and by the job; what it did and why
 * it stopped goes to the log.
 */
class MusicDownload : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val why = intent?.getStringExtra(EXTRA_WHY) ?: "?"
        // already loading: Android still wants to hear "foreground" for this start, or it ends Mate
        if (running) { goForeground(progress ?: getString(R.string.music_download_starting)); return START_NOT_STICKY }
        running = true
        goForeground(getString(R.string.music_download_starting))
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mate:music-download")
            .apply { setReferenceCounted(false); acquire(MAX_MS) }
        @Suppress("DEPRECATION")
        wifi = runCatching { getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "mate:music-download")
            .apply { setReferenceCounted(false); acquire() } }.getOrNull()
        Thread({ work(why) }, "music-download").start()
        return START_NOT_STICKY
    }

    private fun work(why: String) {
        val t0 = SystemClock.elapsedRealtime()
        val before = missing(this)
        DiagLog.add("music offline: loading started ($why) - ${before.second} of ${before.first} songs still to load" +
            (if (Offline.wifiOnly(this)) ", only over Wi-Fi" else ""), area = "AUDIO")
        var shown = 0L
        val n = try {
            Offline.sync(this) { text ->
                progress = text
                val now = SystemClock.elapsedRealtime()
                if (text != null && now - shown > 1_000) { shown = now; goForeground(text) }
            }
        } catch (e: RuntimeException) {
            DiagLog.add("music offline: loading failed (${e.javaClass.simpleName})", area = "AUDIO"); -1
        }
        val after = missing(this)
        val min = (SystemClock.elapsedRealtime() - t0) / 60_000
        lastResult = when {
            n < 0 -> getString(R.string.music_offline_no_net)
            after.second > 0 -> resources.getQuantityString(R.plurals.music_download_left, after.second, after.second)
            else -> null
        }
        DiagLog.add("music offline: " + (if (n < 0) "not possible - " + Offline.whyNot(this) else "$n songs loaded in $min min") +
            ", ${after.second} of ${after.first} still to load" + (if (after.second > 0 && n >= 0) " - " + Offline.whyNot(this) else ""), area = "AUDIO")
        progress = null
        running = false
        runCatching { wifi?.release() }
        runCatching { wake?.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.music_download_channel), NotificationManager.IMPORTANCE_LOW))
        val note = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.music_download_title)).setContentText(text).setOngoing(true).build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTE_ID, note)
        }.onFailure { DiagLog.add("music offline: not in the foreground (${it.javaClass.simpleName}: ${it.message})", area = "AUDIO") }
    }

    companion object {
        private const val EXTRA_WHY = "why"
        private const val CHANNEL = "music_download"
        private const val NOTE_ID = 0x4d44
        /** Never awake longer than this, whatever happens. */
        private const val MAX_MS = 3 * 60 * 60_000L

        /** The song now being loaded ("list: 12 / 340"), null while nothing loads. */
        @Volatile @JvmStatic var progress: String? = null
            private set
        /** What the last run left to say (songs still missing, no net), null when all are there. */
        @Volatile @JvmStatic var lastResult: String? = null
            private set
        @Volatile @JvmStatic var running = false
            private set

        /** Starts the loading ([why] for the log); where Android refuses the service, the songs load on a thread as before. */
        @JvmStatic fun start(context: Context, why: String) {
            val c = context.applicationContext
            runCatching { c.startForegroundService(Intent(c, MusicDownload::class.java).putExtra(EXTRA_WHY, why)) }
                .onFailure {
                    DiagLog.add("music offline: service refused (${it.javaClass.simpleName}) - loading without it ($why)", area = "AUDIO")
                    Thread({ Offline.sync(c, null) }, "music-sync").start()
                }
        }

        /** (songs in the subscribed lists, of them not yet on the device). */
        @JvmStatic fun missing(c: Context): Pair<Int, Int> {
            var all = 0; var left = 0
            Offline.lists(c).forEach { k -> val s = Offline.state(c, k); all += s[0]; left += s[0] - s[1] }
            return all to left
        }
    }
}
