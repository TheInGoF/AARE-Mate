package net.prototux.aare.service

import android.content.Context
import android.os.SystemClock
import net.prototux.aare.DiagLog

/**
 * Where the tablet's battery goes while the car is away (System I-135, L-107, 26.11.2026: "Das Tablet war morgens fast voll
 * und nachmittags leer … Den Stromfresser im Log finden"). When the car leaves, the level is kept; when it is back after a
 * longer while, the log says how much went in how many hours - and, where it went fast, which apps and wake locks Android
 * itself counted (its battery statistics, read as the shell through Shizuku), without anything running meanwhile.
 */
object BatteryWatch {
    private const val PREFS = "battery_watch"
    /** Away shorter than this: a stop, nothing to say. */
    private const val AWAY_MIN_MS = 30 * 60_000L
    /** Lost faster than this while away: Android's list of who used it into the log. */
    private const val FAST_PER_HOUR = 1.5

    /** The car went away (the session ended): the level kept. */
    fun carGone(context: Context) {
        val level = PowerState.level(context)
        if (level < 0) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("level", level).putLong("at", System.currentTimeMillis()).putLong("up", SystemClock.elapsedRealtime()).apply()
    }

    /** The car is back (a session started): what went while it was away, in the log. */
    fun carBack(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val was = p.getInt("level", -1)
        val at = p.getLong("at", 0L)
        val up = p.getLong("up", 0L)
        val now = PowerState.level(context)
        p.edit().clear().apply()
        if (was < 0 || now < 0 || at <= 0) return
        val away = System.currentTimeMillis() - at
        if (away < AWAY_MIN_MS) return
        val hours = away / 3_600_000.0
        // the tablet started anew meanwhile (its time since the start is shorter than the time away): it ran empty or was switched off
        val restarted = SystemClock.elapsedRealtime() < up || SystemClock.elapsedRealtime() < away
        val perHour = (was - now) / hours
        DiagLog.add(String.format(java.util.Locale.ROOT, "battery while the car was away: %d %% -> %d %% in %.1f h (%.1f %%/h)%s", was, now, hours, perHour,
            if (restarted) ", the tablet started anew meanwhile" else ""), area = "POWER")
        if (perHour >= FAST_PER_HOUR || restarted) Thread({ users(context) }, "mate-battery-users").apply { isDaemon = true; start() }
    }

    /**
     * Android's own count of who used the battery since it was last unplugged ("dumpsys batterystats --charged"): the
     * biggest users and the longest wake locks, with app names for the uids. Needs Shizuku; without it a line says so.
     */
    private fun users(context: Context) {
        if (!net.prototux.aare.audio.CaptureMethods.shizukuAvailable()) {
            DiagLog.add("battery users: not readable (Shizuku not running)", area = "POWER")
            return
        }
        val lines = runCatching { shell(arrayOf("dumpsys", "batterystats", "--charged")) }.getOrNull()
        if (lines.isNullOrEmpty()) { DiagLog.add("battery users: Android's statistics not readable", area = "POWER"); return }
        val pm = context.packageManager
        fun named(line: String) = UID.replace(line) { m ->
            val uid = 10_000 + (m.groupValues[2].toIntOrNull() ?: return@replace m.value)
            val pkg = runCatching { pm.getPackagesForUid(uid)?.firstOrNull() }.getOrNull()
            if (pkg != null) "${m.value} ($pkg)" else m.value
        }
        val all = section(lines, "Estimated power use", 150)
        val uid = { l: String -> l.startsWith("UID") || l.startsWith("Uid") }
        // the capacity and drain, the biggest parts of the tablet (screen, mobile, Wi-Fi, …) and the biggest apps
        val power = listOfNotNull(all.firstOrNull { it.startsWith("Capacity") }) +
            all.filter { !uid(it) && !it.startsWith("Capacity") && !it.startsWith("Global") }.take(6) + all.filter(uid).take(6)
        val locks = section(lines, "All partial wake locks", 6)
        val kernel = section(lines, "All kernel wake locks", 4)
        if (power.isEmpty() && locks.isEmpty()) { DiagLog.add("battery users: nothing found in Android's statistics", area = "POWER"); return }
        DiagLog.add("battery users (Android's count since unplugged): " + power.joinToString(" | ") { named(it) }, area = "POWER")
        if (locks.isNotEmpty()) DiagLog.add("battery wake locks: " + locks.joinToString(" | ") { named(it) }, area = "POWER")
        if (kernel.isNotEmpty()) DiagLog.add("battery kernel wake locks: " + kernel.joinToString(" | "), area = "POWER")
    }

    /** Up to [max] lines after the heading that starts with [title], trimmed, the heading itself left out. */
    internal fun section(lines: List<String>, title: String, max: Int): List<String> {
        val i = lines.indexOfFirst { it.trim().startsWith(title) }
        if (i < 0) return emptyList()
        val head = lines[i].length - lines[i].trimStart().length
        // up to the blank line that ends it; the entries stand deeper (power use) or as deep as the heading (wake locks),
        // a new heading at its depth ends it too
        return lines.drop(i + 1).takeWhile { l ->
            val d = l.length - l.trimStart().length
            l.isNotBlank() && (d > head || (d == head && !l.trimEnd().endsWith(":")))
        }.map { it.trim() }.take(max)
    }

    private val UID = Regex("\\bu(\\d+)a(\\d+)\\b")

    private fun shell(cmd: Array<String>): List<String> {
        val m = rikka.shizuku.Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, cmd, null, null) as Process
        // the statistics are long: only their beginning is read, where the sums stand
        val out = ArrayList<String>()
        try {
            p.inputStream.bufferedReader().useLines { seq -> for (l in seq) { out += l; if (out.size >= 6000) break } }
        } finally { runCatching { p.destroy() } }
        return out
    }
}
