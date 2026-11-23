package net.prototux.aare.ui.car.navi

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/**
 * Mate Navi's announcements in the chosen Kokoro voice (O230, 17.11.2026: "richte die Sprachsynthese mit Kokoro ein",
 * answer "Navi-Ansagen ja, vorab berechnen"). Kokoro needs 2-3 s to the first sound on the S6 Lite - too late for a
 * turn - so the next announcements of the route are computed as soon as they are known and kept; at their moment the
 * finished sound is played. One not ready in time is spoken by the phone's voice (NaviSpeaker).
 */
internal object NaviKokoro {
    private const val TAG = "MateNavi"
    /** A city route has some 20 announcements, a long trip a few hundred; each ~0.3 MB. Kept: what is computed ahead ([AHEAD]) and some more (120 before, 36 MB at worst). */
    private const val KEEP = 60
    /**
     * How many announcements are computed ahead of the drive: some ten turns with their three announcements each, far
     * below [KEEP], so that what is computed is never thrown away before it is spoken (warnings come on top).
     */
    const val AHEAD = 36
    private val worker = Executors.newSingleThreadExecutor()
    /** Texts asked for now (said or warned): kept in front when a new list replaces the queue (System I-092). */
    private val urgent = HashSet<String>()
    private val cache = object : LinkedHashMap<String, Pair<FloatArray, Int>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<FloatArray, Int>>?) = size > KEEP
    }
    /** What is still to be computed, in the order it will be needed; [current] is being computed now. */
    private val todo = ArrayDeque<String>()
    private var current: String? = null
    private var running = false

    /**
     * Whether the voice chosen in the phone app is one on the device - Kokoro or Piper (17.11.2026: "Navi-Ansagen sollen
     * auch über Kokoro oder Piper einstellbar sein"; Piper is faster than real time, computing ahead does no harm).
     */
    fun enabled(context: Context): Boolean = net.prototux.aare.speech.LocalTts.chosen(context)?.let {
        net.prototux.aare.speech.Voices.available(context, it)
    } == true

    /**
     * The announcements still ahead, in their order (already polished): they replace what was waiting - a new route or
     * a step passed drops the old ones (17.11.2026: only the next three steps were too few, Kokoro computes at about
     * 2.3 times the speech's length and fell behind in town). [first]: put before the waiting ones, keep them.
     */
    fun prepare(context: Context, texts: List<String>, first: Boolean = false) {
        if (!enabled(context)) return
        val app = context.applicationContext
        synchronized(this) {
            val add = texts.distinct().filter { it !in cache && it != current }
            if (first) { add.asReversed().forEach { t -> todo.remove(t); todo.addFirst(t) }; urgent.addAll(add) }
            else {
                // System I-092 (23.11.2026: "diese Ladestopp-Änderung, die kommt über den Standard TTS, nicht über Piper"): a new
                // route's list replaced the whole queue - a waiting urgent one (a warning, the first words of a new route)
                // went with it, was never computed, and the phone's voice said it. The urgent ones stay in front.
                val keep = todo.filter { it in urgent }
                todo.clear(); todo.addAll(keep); todo.addAll(add.filter { it !in keep })
            }
            if (!running && todo.isNotEmpty()) { running = true; worker.execute { work(app) } }
        }
    }

    /**
     * Navi I-008: an error or a hang in one rendering left [running] set for good - nothing was computed any more and
     * every announcement waited 3 s for the phone's voice. Now the flag is cleared in any case and one rendering waits
     * at most [RENDER_MAX_S] (the queue is shared with the messages read out).
     */
    private fun work(app: Context) {
        try {
            while (true) {
                val t = synchronized(this) { todo.removeFirstOrNull().also { current = it } } ?: return
                val r = runCatching { net.prototux.aare.speech.LocalTts.render(app, t, RENDER_MAX_S) }
                    .onFailure { Log.w(TAG, "voice: rendering \"${t.take(40)}\" failed", it) }.getOrNull()
                synchronized(this) { current = null; urgent.remove(t); if (r != null) cache[t] = r }
            }
        } finally {
            synchronized(this) { current = null; running = false }
        }
    }
    private const val RENDER_MAX_S = 30L

    /** The finished sound for [text], or null (not computed yet). */
    fun take(text: String, quiet: Boolean = false): Pair<FloatArray, Int>? = synchronized(this) { cache[text] }.also {
        if (it == null && !quiet) Log.i(TAG, "voice: not ready on the device for \"${text.take(40)}\"")
    }
}
