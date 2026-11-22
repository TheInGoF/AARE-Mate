package net.prototux.aare

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The connection log shown in the app. Kept on disk, so what happened in the car (a wireless
 * attempt, a refused certificate…) can still be read on the phone afterwards. Thread-safe.
 */
object DiagLog {
    private const val TAG = "Aare"
    // several days of driving (18.11.2026: "Wie viele Zeilen bräuchten wir realistisch, wenn ich 8 Stunden auf der Autobahn
    // bin? Oder wenn ich mehrere Tage loggen möchte"): about 170 lines an hour in town, fewer on the motorway - eight hours
    // are some 1000-1400, a few days fit in 5000 (about 500 KB). 600 were about three hours, a third of them pings.
    private const val MAX_LINES = 5000
    /** The file is cut back to the kept lines once it has grown past this (not with every line). */
    private const val FILE_MAX = 1_500_000L

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines
    private var file: File? = null

    /** Loads the saved log. Called by [AareApp]; lines added before go to logcat only. */
    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "connection.log")
        file = f
        _lines.value = runCatching { f.readLines() }.getOrDefault(emptyList()).takeLast(MAX_LINES)
    }

    /** How bad a line is (19.11.2026: "Vielleicht sollten die Tags bekommen, also Warn, Error, Info"). */
    enum class Level(val tag: String) { INFO("INFO "), WARN("WARN "), ERROR("ERROR") }

    /**
     * A line into the log, with its level and area in front (19.11.2026: "... damit man die besser überblicken kann ...
     * zum Filtern nach Problemen ... Audio-Tags, Navi-Tags, damit man danach suchen kann"): "dd/MM HH:mm:ss  WARN  AUDIO  ...".
     * Without a [level] it is read from the words (failed, refused → ERROR; tight, lost, not ready → WARN), without an
     * [area] from how the line begins ([area]).
     */
    @JvmOverloads @JvmStatic @Synchronized
    fun add(line: String, level: Level? = null, area: String? = null) {
        val lv = level ?: levelOf(line)
        val ar = area ?: area(line)
        when (lv) { Level.ERROR -> Log.e(TAG, "[$ar] $line"); Level.WARN -> Log.w(TAG, "[$ar] $line"); Level.INFO -> Log.i(TAG, "[$ar] $line") }
        val stamp = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date())
        val entry = "$stamp  ${lv.tag} ${ar.padEnd(AREA_WIDTH)} $line"
        val all = (_lines.value + entry).takeLast(MAX_LINES)
        _lines.value = all
        val f = file ?: return
        runCatching {
            // Rewrite now and then to drop old lines; append otherwise.
            // (only once the file has grown well past what is kept: at 64 KB the kept 600 lines alone could be larger,
            // and the whole file was then written anew with every single line)
            if (all.size == MAX_LINES && f.length() > FILE_MAX) f.writeText(all.joinToString("\n", postfix = "\n"))
            else f.appendText("$entry\n")
        }
    }
    fun warn(line: String, area: String? = null) = add(line, Level.WARN, area)
    fun error(line: String, area: String? = null) = add(line, Level.ERROR, area)

    /** The level a line without one is given, by its words. */
    fun levelOf(text: String): Level {
        val t = text.lowercase(Locale.ROOT)
        return when {
            ERROR_WORDS.any { it in t } -> Level.ERROR
            WARN_WORDS.any { it in t } -> Level.WARN
            else -> Level.INFO
        }
    }
    private val ERROR_WORDS = listOf("failed", "error", "exception", "refused", "crash", "ended for memory", "last end of mate", "could not")
    private val WARN_WORDS = listOf("tight", "lost", "not ready", "falls short", "still low", "stayed low", "disconnected", "dropped",
        "timed out", "not in ", "no route", "may be slowed", "throttling", "too long", "late by", "missing", "gave up", "without charging", "broken off")

    /** A stored line taken apart: its time, level and area (null for lines from before 19.11.2026) and its words. */
    class Entry(val stamp: String, val level: Level?, val area: String, val text: String)

    private val TAGGED = Regex("""^(INFO|WARN|ERROR) +([A-Z]+) +(.*)$""")

    fun parse(line: String): Entry {
        val stamp = line.take(STAMP)
        val rest = line.drop(STAMP).trim()
        val m = TAGGED.find(rest)
        return if (m != null) Entry(stamp, Level.valueOf(m.groupValues[1]), m.groupValues[2], m.groupValues[3])
        else Entry(stamp, null, area(rest), rest).let { e -> Entry(e.stamp, levelOf(rest), e.area, rest) }
    }

    /**
     * The log made for reading it through (18.11.2026: "Gibt es eigentlich eine Gesamtgröße für den Log ... Oder macht es
     * Sinn, den Log als JSON zu exportieren, wo dann alle Log-Einträge nach Kategorie sortiert sind, damit das
     * Aufschlüsseln der Probleme einfacher ist?"): a summary on top - the time it covers, how many lines of each area, the
     * drops, the fault marks with what was said - then every line in the order it happened, its area in front, without
     * the protocol's minute-by-minute pings. In time order on purpose: what came just before a fault tells most.
     */
    fun digest(lines: List<String>, version: String, clean: ((String) -> String)? = null): String {
        val kept = lines.filterNot { NOISE.containsMatchIn(it) }
        val entries = kept.map(::parse).let { all -> if (clean == null) all else all.map { Entry(it.stamp, it.level, it.area, clean(it.text)) } }
        val out = StringBuilder()
        out.append("Mate ").append(version).append(if (clean == null) " · Diagnose-Log" else " · Issue-Log (anonymisiert)")
        if (kept.isNotEmpty()) out.append(" ").append(kept.first().take(STAMP - 2)).append(" bis ").append(kept.last().take(STAMP - 2))
        out.append(" · ").append(kept.size).append(" Zeilen (").append(lines.size - kept.size).append(" Pings weggelassen)\n")
        out.append("Stufen: ").append(Level.values().joinToString { lv -> "${lv.name} ${entries.count { it.level == lv }}" }).append("\n")
        out.append("Bereiche: ").append(entries.groupingBy { it.area }.eachCount().entries.sortedByDescending { it.value }
            .joinToString { "${it.key} ${it.value}" }).append("\n")
        val drops = kept.count { "session failed" in it }
        val tight = kept.count { "memory: tight" in it }
        val ends = kept.count { "last end of Mate" in it }
        out.append("Abbrüche ").append(drops).append(" · Speicher knapp ").append(tight).append(" · Mate beendet ").append(ends).append("\n")
        entries.filter { it.text.startsWith("fault of ") }.forEach { out.append("Fehlermarke ").append(it.text.removePrefix("fault of ")).append("\n") }
        // the problems first, to find them at a glance - each still in its place below
        val problems = entries.filter { it.level == Level.ERROR || it.level == Level.WARN }
        if (problems.isNotEmpty()) {
            out.append("\nProbleme (").append(problems.size).append(if (problems.size > MAX_PROBLEMS) ", die letzten $MAX_PROBLEMS" else "").append("):\n")
            problems.takeLast(MAX_PROBLEMS).forEach { out.append(line(it)).append("\n") }
        }
        out.append("\nAlle Zeilen:\n")
        entries.forEach { out.append(line(it)).append("\n") }
        return out.toString()
    }

    private fun line(e: Entry) = "${e.stamp}${(e.level ?: Level.INFO).tag} ${e.area.padEnd(AREA_WIDTH)} ${e.text}"
    private const val MAX_PROBLEMS = 150
    private const val AREA_WIDTH = 6

    /** "dd/MM HH:mm:ss  " in front of every line. */
    private const val STAMP = 16
    private val NOISE = Regex("""rx AapMessage\(ch=0, id=0xb\b""")

    /** The area a line belongs to, by what it says: a short tag to filter by. */
    fun area(text: String): String = when {
        text.startsWith("fault") -> "FAULT"
        text.startsWith("memory") || text.startsWith("last end of Mate") -> "MEM"
        text.startsWith("power") -> "POWER"
        text.startsWith("dashboard") -> "DASH"
        text.startsWith("maps") -> "MAPS"
        text.startsWith("car bluetooth") || text.startsWith("car call") || text.startsWith("call") || text.startsWith("ringtone") -> "PHONE"
        text.startsWith("wireless") || text.startsWith("car Wi-Fi") || text.startsWith("car internet") || text.startsWith("internet") -> "NET"
        text.startsWith("charging") || text.startsWith("charge ") -> "CHARGE"
        text.startsWith("turn to the car") || text.startsWith("arrived") || text.startsWith("speed limit") || text.startsWith("nav focus") ||
            text.startsWith("head-up display") || text.startsWith("route") || text.startsWith("closures") || text.startsWith("strip") ||
            text.startsWith("stops ") || text.startsWith("trip") || text.startsWith("map source") || text.startsWith("navi") ||
            text.startsWith("eta") || text.startsWith("guidance ended") -> "NAVI"
        text.startsWith("voice") -> "VOICE"
        text.startsWith("audio") || text.startsWith("guidance focus") || text.startsWith("music") || text.startsWith("navigation sound") ||
            text.startsWith("level") || text.startsWith("car audio") || text.startsWith("resume music") || text.startsWith("sound") -> "AUDIO"
        text.startsWith("apps") -> "APPS"
        text.startsWith("parked") || text.startsWith("driving") || text.startsWith("car location") || text.startsWith("cinema") -> "CAR"
        else -> "CONN"
    }

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
        file?.let { runCatching { it.writeText("") } }
    }
}
