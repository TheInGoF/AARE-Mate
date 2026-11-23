package net.prototux.aare.speech

import android.annotation.SuppressLint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import kotlin.math.sqrt

/**
 * Dictating an answer (wish O96): listens until the speaker pauses, then turns it into text on the device. Music
 * pauses meanwhile (audio focus). The model stays loaded between answers ([Model]) - loading base takes ~2 s.
 */
class Dictation(private val context: Context) {
    @Volatile private var stopped = false
    @Volatile private var cancelled = false

    /** Ends listening now and recognizes what was said so far. */
    fun stop() { stopped = true }
    fun cancel() { cancelled = true; stopped = true }

    /** [onHeard] gets the text (blank: nothing understood), or null if it could not listen or no model is there. */
    @SuppressLint("MissingPermission") // the phone app asks for the microphone; without it the recorder fails and null comes back
    fun run(onThinking: () -> Unit, onHeard: (String?) -> Unit) = Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val am = context.getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).build()).build()
        // the model loads while the speaker talks
        var model: OfflineRecognizer? = null
        val loader = Thread {
            model = runCatching { Model.get(context) }.onFailure { Log.w(TAG, "model load failed", it) }.getOrNull()
        }.apply { start() }
        val result = runCatching {
            am.requestAudioFocus(focus)
            val test = debugWave.also { debugWave = null }
            val (pcm, rate) = if (test != null) test.samples to test.rate
                else try { listen() to RATE } finally { am.abandonAudioFocusRequest(focus) }
            if (test != null) am.abandonAudioFocusRequest(focus)
            if (cancelled) return@runCatching null
            lastAudio = pcm to rate
            onThinking()
            loader.join()
            val m = model ?: return@runCatching null
            // one recognition at a time (a new listening while the last one still thinks: two at once took the
            // tablet's processor and memory), and none for a listening given up meanwhile
            if (pcm.isEmpty()) "" else synchronized(Model.oneAtATime) { if (cancelled) null else LocalAsr.recognize(m, pcm, rate) }
        }.onFailure { Log.w(TAG, "dictation failed", it) }.getOrNull()
        // the model is free to go once its loading and this recognition are over
        runCatching { loader.join() }
        Model.done()
        if (!cancelled) onHeard(result?.let { clean(it) })
    }, "mate-dictation").apply { start() }

    /** Records until 1.2 s of quiet after speech, [MAX_S] at most, or [stop]; empty when nobody spoke within [WAIT_S]. */
    /** Through the car session's microphone router when connected, else straight from the phone. */
    private fun listen(): FloatArray {
        listening = true
        try { return carMic?.let { listenRouted(it) } ?: listenPhone() } finally { listening = false }
    }

    /** The same pause detection on the 16 kHz chunks the router hands over (car microphone or phone). */
    private fun listenRouted(open: (sink: (ByteArray, Int, Int) -> Unit, onStopped: () -> Unit) -> net.prototux.aare.host.MicHandle?): FloatArray {
        val queue = java.util.concurrent.LinkedBlockingQueue<ShortArray>()
        val ended = java.util.concurrent.atomic.AtomicBoolean(false)
        val handle = open({ b, off, len ->
            val n = len / 2
            queue.offer(ShortArray(n) { i -> ((b[off + 2 * i].toInt() and 0xff) or (b[off + 2 * i + 1].toInt() shl 8)).toShort() })
        }, { ended.set(true) }) ?: return listenPhone()
        val out = ShortArray(RATE * MAX_S)
        var got = 0
        var floor = -1.0
        var spoke = false
        var quietMs = 0
        val t0 = android.os.SystemClock.elapsedRealtime()
        var fallback = false
        try {
            while (!stopped && !ended.get() && got < out.size) {
                val elapsed = android.os.SystemClock.elapsedRealtime() - t0
                // by the clock too: a car that announces a microphone but sends nothing must not keep us listening
                if (elapsed > MAX_S * 1000L || (!spoke && elapsed > WAIT_S * 1000L)) break
                val chunk = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    if (got == 0 && elapsed > 1500) { fallback = true; break }
                    continue
                }
                val n = minOf(chunk.size, out.size - got)
                var sum = 0.0
                for (i in 0 until n) { out[got + i] = chunk[i]; sum += chunk[i].toDouble() * chunk[i] }
                got += n
                val rms = sqrt(sum / maxOf(n, 1))
                // the first 200 ms are the music going down; after that the quietest stretch so far is the background
                if (got < RATE * 2 / 10) continue
                floor = if (floor < 0) rms else minOf(floor, rms)
                if (got < RATE * 4 / 10) continue
                val loud = rms > maxOf(floor * 3, 300.0)
                if (loud) { spoke = true; quietMs = 0 } else quietMs += n * 1000 / RATE
                if (spoke && quietMs >= 1200) break
                if (!spoke && got >= RATE * WAIT_S) return FloatArray(0)
            }
        } finally {
            handle.close()
        }
        if (fallback) { Log.i(TAG, "car microphone sends nothing: the phone's"); return listenPhone() }
        if (!spoke && !stopped) return FloatArray(0)
        return FloatArray(got) { out[it] / 32768f }
    }

    @SuppressLint("MissingPermission")
    private fun listenPhone(): FloatArray {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "microphone" }
        val out = ShortArray(RATE * MAX_S)
        var got = 0
        val frame = RATE * 30 / 1000
        var floor = -1.0
        var spoke = false
        var quietFrames = 0
        try {
            rec.startRecording()
            while (!stopped && got + frame <= out.size) {
                val n = rec.read(out, got, frame)
                if (n <= 0) error("microphone read $n")
                var sum = 0.0
                for (i in got until got + n) sum += out[i].toDouble() * out[i]
                val rms = sqrt(sum / n)
                got += n
                // the quietest stretch so far is the background (a driver who speaks at once must not become it);
                // speech is clearly above it
                floor = if (floor < 0) rms else minOf(floor, rms)
                if (got < RATE * 3 / 10) continue
                val loud = rms > maxOf(floor * 3, 300.0)
                if (loud) { spoke = true; quietFrames = 0 } else quietFrames++
                if (spoke && quietFrames * 30 >= 1200) break
                if (!spoke && got >= RATE * WAIT_S) return FloatArray(0)
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        if (!spoke && !stopped) return FloatArray(0)
        return FloatArray(got) { out[it] / 32768f }
    }

    private fun clean(text: String): String = Companion.clean(text)

    /** The loaded model, kept for the next answer: base when it is on the device, else the first size there. */
    /**
     * The model in memory. Kept only a short while after its last use (18.11.2026: Mate was ended for memory right after
     * "Fehler markieren" had loaded it beside the map and the voice - until then it stayed loaded for good once used):
     * a second sentence soon after needs no new load, then its some hundred MB are free again.
     */
    object Model {
        private const val KEEP_MS = 20_000L
        private var size: String? = null
        private var rec: OfflineRecognizer? = null
        private var users = 0
        private val timer by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
        private val free = Runnable { Thread({ release() }, "mate-asr-free").start() }
        /** Whether the model is in memory now (for the diagnosis log). */
        val inMemory: Boolean get() = rec != null
        /** Held by the one recognition running. */
        val oneAtATime = Any()

        /** The model, loaded if need be; every [get] is followed by one [done] when the recognizing has ended. */
        @Synchronized fun get(context: Context): OfflineRecognizer? {
            users++
            runCatching { timer.removeCallbacks(free) }
            LocalAsr.cleanup(context)
            val want = LocalAsr.chosen(context) ?: return null
            if (size != want || rec == null) {
                rec?.release()
                rec = null
                net.prototux.aare.Memory.makeRoom(context, "the speech recognition")
                rec = LocalAsr.load(context, want, 2)
                size = want
                Log.i(TAG, "model whisper-$want loaded")
            }
            return rec
        }

        @Synchronized fun done() {
            if (users > 0) users--
            if (users == 0) runCatching { timer.removeCallbacks(free); timer.postDelayed(free, KEEP_MS) }
        }

        /** Frees the model unless it is in use right now (released under a recognition it took Mate down). */
        @Synchronized fun release(): Boolean {
            if (users > 0 || rec == null) return false
            rec?.release(); rec = null; size = null
            Log.i(TAG, "model freed")
            return true
        }
    }

    companion object {
        /**
         * Whisper's habits removed (18.11.2026: "dynamische Musik hat er erkannt ... oder er sagt, ich schreie"): noises
         * it describes instead of words - in brackets, parentheses or between stars ("[Musik]", "(dynamische Musik)",
         * "*schreit*") -, and the lines it invents over music or silence ("Untertitel ...", "Vielen Dank fürs Zuschauen").
         */
        fun clean(text: String): String {
            val t = text.replace(Regex("\\[[^]]*]|\\([^)]*\\)|\\*[^*]*\\*|♪[^♪]*♪|[♪♫]"), " ").replace(Regex("\\s+"), " ").trim()
            val plain = t.lowercase().trim(' ', '.', '!', '?', ',', '-', '…')
            return if (INVENTED.any { plain.startsWith(it) } || plain in NOISES) "" else t
        }
        private val INVENTED = listOf("untertitel", "vielen dank fürs zuschauen", "vielen dank für's zuschauen", "danke fürs zuschauen",
            "bis zum nächsten mal", "copyright", "swr ", "zdf ",
            // English (base.en): the same habits
            "subtitles by", "thank you for watching", "thanks for watching", "please subscribe", "see you next time")
        private val NOISES = setOf("dynamische musik", "spannende musik", "ruhige musik", "applaus", "schreit", "gelächter",
            "upbeat music", "dramatic music", "soft music", "calm music", "applause", "laughter", "laughing", "screaming")

        /** Debug builds only (tool debug_dictate_file): the next dictation hears this instead of the microphone. */
        @Volatile var debugWave: SpeechBench.Wave? = null
        /** Mate is listening right now (the orange microphone beside the car screen's status, O108). */
        var listening by androidx.compose.runtime.mutableStateOf(false)
        /** The car session's microphone (the car's own when it has one, else the phone's), 16 kHz mono 16 bit; null without a car. */
        @Volatile var carMic: ((sink: (ByteArray, Int, Int) -> Unit, onStopped: () -> Unit) -> net.prototux.aare.host.MicHandle?)? = null
        /** The last dictation's sound and its rate, for a second try with another model (music by voice). */
        @Volatile var lastAudio: Pair<FloatArray, Int>? = null
        private const val TAG = "MateDictation"
        const val RATE = 16000
        const val MAX_S = 15
        const val WAIT_S = 6
    }
}
