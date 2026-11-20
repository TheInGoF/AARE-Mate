package net.prototux.aare.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * Listening for as long as the speaker wants (18.11.2026, on "Fehler markieren": "Die Sprachaufnahme bricht immer nach
 * ein paar Sekunden ab. Man darf keine Pause einlegen beim Sprechen, sonst fängt er sofort an, die Musik weiterzuspielen
 * und die Aufnahme zu beenden. Er muss weiter zuhören, aber er hat wohl Probleme damit, lange Texte zu verstehen ...
 * Vielleicht sollte er im Hintergrund Textstücke, die bereits gesprochen sind, schon erkennen, während er noch zuhört.
 * Aber die Musik nicht automatisch wieder anfangen zu spielen ... ich möchte die Aufnahme manuell beenden und nicht,
 * wenn er denkt, dass ich genug gesprochen habe").
 *
 * The microphone stays open until [stop] (the car stays silent that long, MicRouter). A pause only ends a piece: each
 * stretch of speech goes to the recognition at once, beside the listening, and its words are handed on in order - so
 * a long description is many short pieces, which the recognition understands, not one long one, which it does not.
 */
class LongDictation(private val context: Context) {
    @Volatile private var stopped = false
    @Volatile private var cancelled = false

    /** The speaker has finished: what was said so far is still worked out and handed on, then [run]'s onEnd comes. */
    fun stop() { stopped = true }
    /** Given up: nothing more is handed on, no onEnd. */
    fun cancel() { cancelled = true; stopped = true }

    /**
     * [onPiece]: the words of each stretch of speech, in order. [onWaiting]: how many stretches are still being worked
     * out. [onEnd]: all is handed on (false: the microphone or the recognition could not be had). Any thread calls them.
     */
    fun run(onPiece: (String) -> Unit, onWaiting: (Int) -> Unit, onEnd: (Boolean) -> Unit) = Thread({
        val pieces = LinkedBlockingQueue<FloatArray>()
        val waiting = AtomicInteger(0)
        val end = FloatArray(0)
        val modelThere = java.util.concurrent.atomic.AtomicBoolean(true)
        val worker = Thread({
            val model = runCatching { Dictation.Model.get(context) }.getOrNull()
            try {
                if (model == null) { modelThere.set(false); return@Thread }
                while (true) {
                    val p = pieces.take()
                    if (p === end) break
                    val text = if (cancelled) null else runCatching {
                        synchronized(Dictation.Model.oneAtATime) { if (cancelled) null else LocalAsr.recognize(model, p, Dictation.RATE) }
                    }.onFailure { Log.w(TAG, "a piece was not understood", it) }.getOrNull()
                    val left = waiting.decrementAndGet()
                    if (cancelled) continue
                    text?.let { Dictation.clean(it) }?.takeIf { it.isNotBlank() }?.let(onPiece)
                    onWaiting(left)
                }
            } finally {
                Dictation.Model.done()
            }
        }, "mate-long-asr").apply { start() }

        fun hand(samples: ShortArray, n: Int) {
            if (n < Dictation.RATE * 3 / 10) return
            onWaiting(waiting.incrementAndGet())
            pieces.put(FloatArray(n) { samples[it] / 32768f })
        }

        val am = context.getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).build()).build()
        val ok = runCatching {
            am.requestAudioFocus(focus)
            Dictation.listening = true
            val cut = Cutter { s, n -> hand(s, n) }
            val routed = Dictation.carMic?.let { open -> routed(open, cut) } ?: false
            if (!routed && !stopped) phone(cut)
            if (!cancelled) cut.rest()
            true
        }.onFailure { Log.w(TAG, "listening failed", it) }.getOrDefault(false)
        Dictation.listening = false
        runCatching { am.abandonAudioFocusRequest(focus) }
        pieces.put(end)
        runCatching { worker.join(WORK_MAX_MS) }
        if (!cancelled) onEnd(ok && modelThere.get())
    }, "mate-long-dictation").apply { start() }

    /**
     * Cuts what the microphone brings into stretches of speech: one ends with [PAUSE_MS] of quiet after speech, or
     * after [PIECE_MAX_S] at the latest; quiet without speech is thrown away. The background is the quietest moment
     * heard so far.
     */
    internal class Cutter(private val rate: Int = Dictation.RATE, private val out: (ShortArray, Int) -> Unit) {
        private var buf = ShortArray(rate * (PIECE_MAX_S + 1))
        private var got = 0
        private var total = 0L
        private var floor = -1.0
        private var spoke = false
        private var quietMs = 0

        fun feed(chunk: ShortArray, n: Int) {
            if (n <= 0) return
            var sum = 0.0
            val take = minOf(n, buf.size - got)
            for (i in 0 until take) { buf[got + i] = chunk[i]; sum += chunk[i].toDouble() * chunk[i] }
            got += take; total += take
            val rms = sqrt(sum / take)
            // the first 200 ms are the music going down
            if (total > rate * 2 / 10) floor = if (floor < 0) rms else minOf(floor, rms)
            val loud = floor >= 0 && rms > maxOf(floor * 3, 300.0)
            if (loud) {
                // speech begins: of the quiet before it only a moment is kept
                val lead = got - take; val keep = rate * 3 / 10
                if (!spoke && lead > keep) { System.arraycopy(buf, lead - keep, buf, 0, keep + take); got = keep + take }
                spoke = true; quietMs = 0
            } else quietMs += take * 1000 / rate
            when {
                spoke && (quietMs >= PAUSE_MS || got >= rate * PIECE_MAX_S) -> cut()
                // nothing said for a while: only the last moment is kept, so a word's beginning is not lost
                !spoke && got >= rate * 2 -> { val keep = rate * 3 / 10; System.arraycopy(buf, got - keep, buf, 0, keep); got = keep }
            }
        }

        private fun cut() {
            out(buf, got)
            buf = ShortArray(buf.size); got = 0; spoke = false; quietMs = 0
        }

        /** The listening is over: what was begun is a piece too. */
        fun rest() { if (spoke) cut() }
    }

    /** From the car session's microphone (the car's own or the phone's through the router); false when it gives nothing. */
    private fun routed(open: (sink: (ByteArray, Int, Int) -> Unit, onStopped: () -> Unit) -> net.prototux.aare.host.MicHandle?, cut: Cutter): Boolean {
        val queue = LinkedBlockingQueue<ShortArray>()
        val ended = java.util.concurrent.atomic.AtomicBoolean(false)
        val handle = open({ b, off, len ->
            val n = len / 2
            queue.offer(ShortArray(n) { i -> ((b[off + 2 * i].toInt() and 0xff) or (b[off + 2 * i + 1].toInt() shl 8)).toShort() })
        }, { ended.set(true) }) ?: return false
        val t0 = android.os.SystemClock.elapsedRealtime()
        var any = false
        var keptAt = t0
        try {
            while (!stopped && !ended.get()) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - t0 > TOTAL_MAX_MS) break
                // the car stays silent for as long as this listens (the silence would end by itself otherwise)
                if (now - keptAt > 5_000) { keptAt = now; net.prototux.aare.audio.MediaFade.mic(true) }
                val chunk = queue.poll(200, TimeUnit.MILLISECONDS)
                if (chunk == null) { if (!any && now - t0 > 1500) return false; continue }
                any = true
                cut.feed(chunk, chunk.size)
            }
        } finally {
            handle.close()
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun phone(cut: Cutter) {
        val min = AudioRecord.getMinBufferSize(Dictation.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Dictation.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, Dictation.RATE))
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "microphone" }
        val frame = ShortArray(Dictation.RATE * 30 / 1000)
        val t0 = android.os.SystemClock.elapsedRealtime()
        try {
            rec.startRecording()
            while (!stopped && android.os.SystemClock.elapsedRealtime() - t0 < TOTAL_MAX_MS) {
                val n = rec.read(frame, 0, frame.size)
                if (n <= 0) error("microphone read $n")
                cut.feed(frame, n)
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    companion object {
        private const val TAG = "MateDictation"
        /** A pause this long ends a piece (not the listening). */
        const val PAUSE_MS = 900
        /** A piece without a pause is cut after this long: the recognition is good at short pieces. */
        const val PIECE_MAX_S = 12
        /** A microphone forgotten open closes after this long. */
        private const val TOTAL_MAX_MS = 5 * 60_000L
        private const val WORK_MAX_MS = 90_000L
    }
}
