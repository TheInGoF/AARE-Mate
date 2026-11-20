package net.prototux.aare.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.Executors

/**
 * A natural voice on the device (wish O110): the German Piper voice "Thorsten" through sherpa-onnx, instead of the
 * system's text-to-speech. Files in the app's folder: `files/sprache/piper-thorsten/` with the .onnx model,
 * tokens.txt and espeak-ng-data. Sentences are spoken one after the other on a thread of their own; the voice
 * plays as media speech, like the system voice did, so it reaches the car with everything else.
 */
object LocalTts {
    private const val TAG = "MateTts"
    private val queue = Executors.newSingleThreadExecutor()
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var loadedId: String? = null
    /** The voice now in memory, for the diagnosis log's memory lines; null when none. */
    val inMemory: String? get() = if (tts != null) loadedId else null

    fun dir(context: Context) = File(context.getExternalFilesDir(null), "sprache/piper-thorsten")

    /** The voice chosen in the phone app (Voices), the Piper Thorsten for the old setting "piper". */
    fun chosen(context: Context): Voices.Voice? = Voices.byId(net.prototux.aare.Settings(context).ttsEngine)

    fun available(context: Context): Boolean = chosen(context)?.let { Voices.available(context, it) } ?: false

    private fun load(context: Context, v: Voices.Voice): OfflineTts {
        if (loadedId == v.id) tts?.let { return it }
        tts?.release(); tts = null
        val d = Voices.dir(context, v)
        val espeak = Voices.espeak(context).path
        val t0 = SystemClock.elapsedRealtime()
        val model = if (v.kind == Voices.Kind.PIPER) OfflineTtsModelConfig(
            vits = OfflineTtsVitsModelConfig(model = File(d, v.model).path, tokens = File(d, v.tokens).path, dataDir = espeak),
            numThreads = 2, provider = "cpu")
        else OfflineTtsModelConfig(
            kokoro = com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig(model = File(d, v.model).path, voices = File(d, v.voices).path,
                tokens = File(d, v.tokens).path, dataDir = espeak, lang = "de"),
            // the S6 Lite has two fast cores: more threads only wait on the slow ones (measured 17.11.2026)
            numThreads = 2, provider = "cpu")
        val made = OfflineTts(config = OfflineTtsConfig(model = model, maxNumSentences = 1))
        Log.i(TAG, "voice ${v.id} loaded in ${SystemClock.elapsedRealtime() - t0} ms")
        tts = made; loadedId = v.id
        return made
    }

    /** Speaks [text]; [onDone] runs when it has been heard (or failed). Returns at once. */
    /** O230 (17.11.2026, "nicht gestapelt"): a new sample stops the one playing and drops those waiting. */
    @Volatile private var sampleGen = 0
    @Volatile private var playing: AudioTrack? = null

    /** "Probe hören": [voice] says [text] at once - whatever sample was still playing or waiting is dropped. */
    fun sample(context: Context, text: String, voice: Voices.Voice) {
        val gen = ++sampleGen
        playing?.let { runCatching { it.pause(); it.flush() } }
        speak(context, text, voice, sampleOf = gen) {}
    }

    fun speak(context: Context, text: String, voice: Voices.Voice? = null, sampleOf: Int = 0, onDone: () -> Unit) {
        val app = context.applicationContext
        queue.execute {
            // a sample overtaken by a newer one before its turn: not said at all
            if (sampleOf != 0 && sampleOf != sampleGen) { onDone(); return@execute }
            try {
                // [voice]: "Probe hören" of that voice (17.11.2026: the samples always came in the chosen voice)
                val v = voice ?: chosen(app) ?: return@execute
                val t = load(app, v)
                val t0 = SystemClock.elapsedRealtime()
                val rate = t.sampleRate()
                // streamed sentence by sentence (O224: Kokoro takes about twice the speech's length on the S6 Lite - the
                // first sentence is heard while the next is computed)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT) * 4)
                    .build()
                var written = 0L; var firstMs = -1L
                try {
                    track.play(); playing = track
                    // in short pieces (also at ":" and ";"), so the first is heard soon. 18.11.2026 ("die Sprache knistert
                    // und knarzt schon wieder ... Bei Musik habe ich das nicht"): this way - messages, the voice control's
                    // answers, samples - gave the voice's numbers to the player as they came: above full scale they were
                    // cut off, and each piece began and ended hard. Now as Mate Navi's announcements: each piece computed
                    // whole, at the music's target level, below full scale, faded in and out.
                    for (piece in pieces(Pronounce.fix(text))) {
                        if (sampleOf != 0 && sampleOf != sampleGen) break
                        val raw = t.generate(piece, 0, 1.0f).samples
                        val chunk = net.prototux.aare.audio.Resample.tidy(net.prototux.aare.audio.Loudness.own(raw, rate), rate)
                        if (sampleOf != 0 && sampleOf != sampleGen) break
                        if (firstMs < 0) firstMs = SystemClock.elapsedRealtime() - t0
                        track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING); written += chunk.size
                    }
                    Log.i(TAG, "\"${text.take(40)}\" (${v.id}): ${written * 1000L / rate} ms of speech, first sound after $firstMs ms, all in ${SystemClock.elapsedRealtime() - t0} ms")
                    // until it has played out (plus a little for the car's buffer)
                    // (a newer sample ends the wait at once)
                    val until = android.os.SystemClock.elapsedRealtime() + (written - track.playbackHeadPosition).coerceAtLeast(0) * 1000L / rate + 250
                    while (android.os.SystemClock.elapsedRealtime() < until && (sampleOf == 0 || sampleOf == sampleGen)) Thread.sleep(50)
                } finally {
                    if (playing === track) playing = null
                    runCatching { track.stop() }
                    track.release()
                }
            } catch (e: Throwable) {
                Log.w(TAG, "speaking failed", e)
            } finally {
                onDone()
            }
        }
    }

    /**
     * The sentence callback for sherpa-onnx: its JNI side looks for exactly `invoke([F)Ljava/lang/Integer;` - a Kotlin
     * lambda has only the bridge invoke(Object) and brought Mate down (SIGABRT, 17.11.2026). 1 = go on.
     */
    /** The text in pieces at sentence ends and at ":" / ";" (kept with their piece). */
    /**
     * A sentence ends where a space or the end follows its mark - not inside "Fr.-Ebert-Str." or "2.7" (18.11.2026: an
     * abbreviation was cut off and computed alone, so "Dr." was spelled).
     */
    fun pieces(text: String): List<String> {
        val out = ArrayList<String>()
        val now = StringBuilder()
        for (i in text.indices) {
            now.append(text[i])
            if (text[i] in ".!?:;" && (i == text.lastIndex || text[i + 1].isWhitespace())) { out += now.toString().trim(); now.clear() }
        }
        out += now.toString().trim()
        return out.filter { p -> p.any { it.isLetterOrDigit() } }
    }

    /**
     * [piece] spoken into [cb] while it is computed. 18.11.2026 ("Wenn die App Testsätze spricht, stürzt sie immer noch
     * ab" - native crash in generateWithCallbackImpl, four times in the log): the release build had taken
     * `invoke(float[])` out of [Stop], the callback of Mate's voice for other apps and for the test sentences, as only
     * the first callback (Feed, gone since) was named in proguard-rules.pro. It is named there now, and a callback without that method is never
     * handed to the native side again: the piece is computed as a whole and given to it here.
     */
    private fun generateInto(t: OfflineTts, piece: String, cb: (FloatArray) -> Int?) {
        val reachable = runCatching { cb.javaClass.getMethod("invoke", FloatArray::class.java).returnType == Int::class.javaObjectType }.getOrDefault(false)
        if (reachable) {
            @Suppress("UNCHECKED_CAST")
            t.generateWithCallback(piece, 0, 1.0f, cb as (FloatArray) -> Int)
        } else {
            Log.w(TAG, "${cb.javaClass.name} has no invoke(float[]) for the voice's native side: the piece computed as a whole")
            cb(t.generate(piece, 0, 1.0f).samples)
        }
    }


    /**
     * O230: the whole of [text] computed, not played - Mate Navi's announcements ahead of their moment (Kokoro takes 2-3 s
     * to the first sound). Samples and rate; null without a voice on the device. Waits its turn behind what is spoken.
     */
    fun render(context: Context, text: String, maxSeconds: Long = 0): Pair<FloatArray, Int>? {
        val app = context.applicationContext
        return queue.submit<Pair<FloatArray, Int>?> {
            runCatching {
                val v = chosen(app)?.takeIf { Voices.available(app, it) } ?: return@runCatching null
                val t = load(app, v)
                val t0 = SystemClock.elapsedRealtime()
                val parts = pieces(Pronounce.fix(text)).map { t.generate(it, 0, 1.0f).samples }
                val all = FloatArray(parts.sumOf { it.size }); var at = 0
                parts.forEach { it.copyInto(all, at); at += it.size }
                Log.i(TAG, "rendered \"${text.take(40)}\" (${v.id}): ${all.size * 1000L / t.sampleRate()} ms of speech in ${SystemClock.elapsedRealtime() - t0} ms")
                all to t.sampleRate()
            }.onFailure { Log.w(TAG, "rendering failed", it) }.getOrNull()
        }.let { if (maxSeconds > 0) it.get(maxSeconds, java.util.concurrent.TimeUnit.SECONDS) else it.get() }
    }

    /**
     * For other apps (MateTtsService, 17.11.2026: "dann wäre es ja schön, wenn alle Programme die vernünftigen
     * Kokoro-Sprachdateien bekommen würden"): [text] in the chosen voice, piece by piece to [chunk] while it is computed
     * (the first sound early), on the voice's own thread; waits until done. [chunk] false stops. The rate, or null
     * without a voice on the device.
     */
    fun stream(context: Context, text: String, ready: (Int) -> Unit, chunk: (FloatArray) -> Boolean): Int? {
        val app = context.applicationContext
        return queue.submit<Int?> {
            runCatching {
                val v = chosen(app)?.takeIf { Voices.available(app, it) } ?: return@runCatching null
                val t = load(app, v)
                val rate = t.sampleRate()
                ready(rate)
                var go = true
                // each sentence at the music's target level, below full scale and with soft edges, as Mate's own speech
                val feed = Stop { c -> go = go && chunk(net.prototux.aare.audio.Resample.tidy(net.prototux.aare.audio.Loudness.own(c, rate), rate)); go }
                @Suppress("UNCHECKED_CAST")
                for (piece in pieces(Pronounce.fix(text))) { if (!go) break; generateInto(t, piece, feed) }
                rate
            }.onFailure { Log.w(TAG, "speaking for another app failed", it) }.getOrNull()
        }.get()
    }

    /**
     * The sentence callback for sherpa-onnx: its JNI side looks for exactly `invoke([F)Ljava/lang/Integer;` - a Kotlin
     * lambda has only the bridge invoke(Object) and brought Mate down (SIGABRT, 17.11.2026). 1 = go on, 0 = stop.
     */
    class Stop(private val each: (FloatArray) -> Boolean) : (FloatArray) -> Int? {
        override fun invoke(chunk: FloatArray): Int? = if (each(chunk)) 1 else 0
    }

    fun release() = queue.execute { tts?.release(); tts = null }

    /** [then] once what is queued now has been spoken. */
    fun afterQueue(then: () -> Unit) = queue.execute(then)
}
