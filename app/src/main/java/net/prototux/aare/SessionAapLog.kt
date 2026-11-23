package net.prototux.aare

import android.util.Log
import net.prototux.aare.aap.AapLog

/**
 * The car session's protocol log. Always to logcat (tag "AareAap"); when [verbose] is on, every
 * line — including the per-frame debug trace — also goes to the in-app [DiagLog], so a reporter can
 * capture the protocol trace and export it without adb. Off by default (Settings.verboseProtocolLog).
 */
object SessionAapLog : AapLog {
    @Volatile var verbose = false

    /**
     * 17.11.2026: "wenn ich die detaillierten Logs eingestellt habe, kam im Sekundentakt diese eine Logmeldung ... dann ist
     * der Log unendlich lang mit ganz viel Müll": lines of the same kind (the same words, numbers aside) go to the app's
     * log at most once per [REPEAT_MS]; the next one says how many were left out. Everything new still shows at once.
     */
    private val seen = HashMap<String, LongArray>()      // kind -> [last written (elapsedRealtime), left out since]
    private const val REPEAT_MS = 60_000L
    private val NUMBERS = Regex("""\b(?:0x)?[0-9a-fA-F]*\d[0-9a-fA-F]*\b""")
    private fun diag(msg: String) {
        if (!verbose) return
        // the per-frame trace ("rx AapMessage(...)") stays in logcat: in the app's log it was one line a minute that said
        // nothing and took a third of the room (18.11.2026: "dann sollten wir das Ping-Rauschen definitiv aus den Logs
        // entfernen, dass dort mehr Platz für richtige Informationen sind")
        if (msg.startsWith("rx ") || msg.startsWith("tx ")) return
        val kind = NUMBERS.replace(msg, "#").take(120)
        val now = android.os.SystemClock.elapsedRealtime()
        val line = synchronized(seen) {
            val s = seen.getOrPut(kind) { longArrayOf(0L, 0L) }
            if (s[0] != 0L && now - s[0] < REPEAT_MS) { s[1]++; null }
            else { val out = if (s[1] > 0) "$msg   (${s[1]} similar lines left out in the last minute)" else msg; s[0] = now; s[1] = 0; out }
        } ?: return
        DiagLog.add(line)
    }
    private fun with(msg: String, t: Throwable?) = if (t != null) "$msg: ${t.message ?: t}" else msg
    override fun d(msg: String) { Log.d("AareAap", msg); diag(msg) }
    override fun i(msg: String) { Log.i("AareAap", msg); diag(msg) }
    override fun w(msg: String, t: Throwable?) { Log.w("AareAap", msg, t); diag(with(msg, t)) }
    override fun e(msg: String, t: Throwable?) { Log.e("AareAap", msg, t); diag(with(msg, t)) }
}

/** Also writes to the [DiagLog] the user sees in the app (not the debug lines). */
object DiagAapLog : AapLog {
    override fun d(msg: String) { Log.d("AareAap", msg) }
    override fun i(msg: String) { DiagLog.add(msg) }
    override fun w(msg: String, t: Throwable?) { Log.w("AareAap", msg, t); DiagLog.add(if (t != null) "$msg: ${t.message ?: t}" else msg) }
    override fun e(msg: String, t: Throwable?) { Log.e("AareAap", msg, t); DiagLog.add(if (t != null) "$msg: ${t.message ?: t}" else msg) }
}
