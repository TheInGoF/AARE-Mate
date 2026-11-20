package net.prototux.aare.debug

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Fehler markieren" (test drive T1): one long press in the car, and what is needed to find a fault later is kept
 * together - Mate's own log of the last minutes, the connection log, the car screen's state, the network and the
 * car's description of itself - under Android/data/<app>/files/marks/<time>/. No picture of the screen (the map
 * shows where the car is). Runs off the main thread.
 */
object FaultMark {
    private const val TAG = "AareFaultMark"

    fun capture(context: Context, state: String, done: (File?) -> Unit) {
        val app = context.applicationContext
        Thread {
            val dir = runCatching {
                val base = File(app.getExternalFilesDir(null), "marks")
                File(base, SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())).apply { mkdirs() }
            }.getOrNull()
            if (dir == null) { done(null); return@Thread }
            // Mate's own log (an app reads its own lines without any right), the last 30 000 lines at most
            runCatching {
                val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "30000").redirectErrorStream(true).start()
                File(dir, "logcat.txt").outputStream().use { out -> p.inputStream.copyTo(out) }
                p.waitFor()
            }.onFailure { Log.w(TAG, "logcat", it) }
            runCatching { File(app.filesDir, "connection.log").copyTo(File(dir, "connection.log"), overwrite = true) }
            runCatching { File(dir, "state.json").writeText(state) }
            runCatching { File(dir, "network.txt").writeText(network(app)) }
            runCatching {
                File(app.getExternalFilesDir(null), "cars").listFiles()?.forEach { it.copyTo(File(dir, "car-" + it.name), overwrite = true) }
            }
            net.prototux.aare.DiagLog.add("fault marked: ${dir.name}")
            done(dir)
        }.apply { isDaemon = true }.start()
    }

    private fun network(context: Context): String = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val n = cm.activeNetwork ?: return@runCatching "no network"
        val c = cm.getNetworkCapabilities(n) ?: return@runCatching "network without capabilities"
        listOf(
            "wifi" to c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            "mobile" to c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            "internet" to c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            "validated" to c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            "captive portal" to c.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
        ).joinToString("\n") { (k, v) -> "$k: $v" }
    }.getOrDefault("unknown")
}

/**
 * What the marked fault is, in the driver's words (18.11.2026: "wenn ich auf Fehler markieren klicke, dass er nicht nur
 * den Fehler markiert, sondern auch ein Textfeld anzeigt, mit dem ich eingeben kann, was der Fehler ist ... Dann ist der
 * Fehler zu dem Zeitpunkt hinterlegt mit dem Problem, das dazugehört"): kept as note.txt beside the mark's files and as
 * a line of the diagnosis log. The words may come before the mark's files are written - they wait for them then.
 */
object FaultNote {
    private var dir: File? = null
    private var waiting: String? = null
    private var at = ""

    /** A new mark begins: its note belongs to it, not to the one before. */
    @Synchronized fun begin() {
        dir = null; waiting = null
        at = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY).format(Date())
    }

    /** The mark's files are there (or could not be written: the note goes to the log alone). */
    @Synchronized fun marked(d: File?) {
        dir = d
        waiting?.let { w -> waiting = null; write(d, w) }
    }

    @Synchronized fun save(text: String) {
        val words = text.trim()
        if (words.isEmpty()) return
        net.prototux.aare.DiagLog.add("fault of $at: \"$words\"")
        val d = dir
        if (d == null) waiting = words else write(d, words)
    }

    private fun write(d: File?, words: String) {
        if (d == null) return
        runCatching { File(d, "note.txt").writeText(words + "\n") }.onFailure { Log.w("AareFaultMark", "note", it) }
    }
}
