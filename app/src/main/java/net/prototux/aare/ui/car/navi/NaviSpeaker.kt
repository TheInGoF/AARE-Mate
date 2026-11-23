package net.prototux.aare.ui.car.navi

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.UUID

/**
 * Mate Navi's voice (F4, O189 "Ansagen (Zeitpunkt, Wortwahl)" like Google): Valhalla's spoken instructions, when
 * Ferrostar reaches their trigger distance, read by the phone's speech engine on the navigation channel - the car
 * plays it as guidance and lowers the music for it (the focus is asked for "may duck" and given back when done).
 * Each instruction once (by its id). The words are smoothed first ([polish]).
 */
internal class NaviSpeaker(context: Context) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
    private var ready = false
    private var spoken: UUID? = null
    /** The driver's choice, kept (Settings.naviVoice); quieter than before stops what is being said. */
    var mode: Mode = Mode.of(net.prototux.aare.Settings(app).naviVoice)
        set(v) { field = v; net.prototux.aare.Settings(app).naviVoice = v.key; if (v != Mode.LOUD) stop(); Log.i(TAG, "voice: ${v.key}") }

    /**
     * As in Google Maps (O192): everything, only warnings, nothing - and between everything and warnings [BRIEF]
     * (17.11.2026: "weniger Sprache ... nicht die ganzen Straßennamen vorlesen, sondern nur Navigationsanweisungen. Und
     * diese auch nicht dreimal, sondern nur einmal zur Erinnerung ... in 1 Kilometer von der Autobahn abfahren"): one
     * announcement per turn, the first within [BRIEF_M], without road names and destinations; warnings as ever.
     */
    enum class Mode(val key: String) {
        LOUD("loud"), BRIEF("brief"), ALERTS("alerts"), MUTE("mute");
        fun next() = entries[(ordinal + 1) % entries.size]
        companion object { fun of(k: String) = entries.firstOrNull { it.key == k } ?: LOUD }
    }

    /**
     * The phone's speech engine. Navi I-008 (17.11.2026: after "Zurück" the announcement broke off and none came on the
     * following turns): a binding to the engine that died or never came up was never made anew - every later
     * announcement was dropped at "ready". Now it is bound again when it fails (at most every 20 s).
     */
    private var tts: TextToSpeech = bind()
    private var boundAt = android.os.SystemClock.uptimeMillis()

    // the phone's own engine (Google's when there), not the preferred one: with Mate's voices as the device's engine
    // the stand-in for a turn not computed yet would wait behind Mate's own queue again
    private fun bind(): TextToSpeech = TextToSpeech(app, { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) init()
        Log.i(TAG, "voice: " + if (ready) "ready" else "no speech engine ($status)")
    }, PHONE_ENGINE.takeIf { runCatching { app.packageManager.getPackageInfo(it, 0) }.isSuccess })

    /** The engine bound anew after a failure; true if it can speak now. */
    private fun heal(): Boolean {
        if (ready) return true
        val now = android.os.SystemClock.uptimeMillis()
        if (now - boundAt > REBIND_MS) {
            Log.w(TAG, "voice: speech engine not ready, bound again")
            runCatching { tts.shutdown() }
            boundAt = now; tts = bind()
        }
        return false
    }

    /** The phone's voice says [words]; a refusal of the engine marks it for binding again. */
    private fun phone(words: String, queue: Int, id: String) {
        if (!heal()) { Log.w(TAG, "voice: no speech engine for \"${words.take(40)}\""); return }
        net.prototux.aare.audio.MediaFade.duck()
        audio.requestAudioFocus(focus)
        // quieter during a call, as the device's voice
        val quiet = net.prototux.aare.car.Calls.promptFactor(app)
        val params = if (quiet != 1f) android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, quiet) } else null
        if (tts.speak(words, queue, params, id) == TextToSpeech.ERROR) {
            Log.w(TAG, "voice: speech engine refused, binding again"); ready = false; boundAt = 0; heal()
            // no onDone/onError comes for a refused one: the music and a paused book back now
            promptOver()
        }
    }

    /** A prompt is over: the focus given back, the music and a paused book back. */
    private fun promptOver() { audio.abandonAudioFocusRequest(focus); net.prototux.aare.audio.MediaFade.release() }

    private fun init() {
        // the language Valhalla speaks (Language.tag), the device's
        tts.language = net.prototux.aare.Language.locale()
        tts.setAudioAttributes(attrs)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) = promptOver()
            @Deprecated("Deprecated in Java") override fun onError(id: String?) = promptOver()
        })
    }

    /** The turn [BRIEF] has spoken for already (the steps left), so a turn is said once. */
    private var briefStep = -1

    /**
     * Speaks [text] once for the instruction [id]; a newer one cuts an older one off (it is out of date). [step] names the
     * turn (the steps left), [beforeM] how far before it the instruction is said - for [Mode.BRIEF].
     */
    fun say(id: UUID, text: String, step: Int = -1, beforeM: Double = 0.0) {
        if (id == spoken) return
        spoken = id
        when (mode) {
            Mode.LOUD -> speak(polish(text), id.toString(), TextToSpeech.QUEUE_FLUSH)
            // only the turns (18.11.2026: "wenn kurze Ansagen aktiviert sind, dann sollte er auch nur noch Abbiegehinweise
            // machen und nicht sagen, ja, fahren Sie jetzt 10 Kilometer geradeaus") - a mere "go on" is not said, and it no
            // longer takes the turn's one announcement (it was said instead of the turn after it)
            Mode.BRIEF -> if (step != briefStep && beforeM <= BRIEF_M && !goOnOnly(text)) {
                briefStep = step
                speak(brief(polish(text)), id.toString(), TextToSpeech.QUEUE_FLUSH)
            }
            else -> {}
        }
    }

    /**
     * A warning (O193: danger spots, speed cameras, route changes - never the speed limit): said when loud and when only
     * warnings are wanted.
     */
    fun warn(text: String) {
        if (mode == Mode.MUTE) return
        speak(text, "warn-" + System.nanoTime(), TextToSpeech.QUEUE_ADD)
    }

    /** The car is away (NaviKeep.carGone): nothing is said - it would come from the tablet's own speaker. */
    @Volatile var muted = false

    private fun speak(words: String, id: String, queue: Int) {
        if (muted) { Log.i(TAG, "voice (car away, silent): \"$words\""); return }
        if (NaviDebug.quiet && NaviKeep.testDrive.value == true) { Log.i(TAG, "voice (test drive, silent): \"$words\""); return }
        // O230: the chosen Kokoro voice when the announcement was computed ahead; otherwise the phone's voice
        if (NaviKokoro.enabled(app)) {
            NaviKokoro.take(words, quiet = true)?.let { (samples, rate) -> play(samples, rate, flush = queue == TextToSpeech.QUEUE_FLUSH); Log.i(TAG, "voice (device): \"$words\""); return }
            // not computed yet (the first ones of a trip, warnings with changing words): to the front of the queue and
            // up to 3 s waited for - one voice throughout rather than the phone's in between (17.11.2026: "wieso haben
            // gerade zwei unterschiedliche Stimmen gesprochen?")
            NaviKokoro.prepare(app, listOf(words), first = true)
            val gen = ++waitGen
            val stops = playGen
            val t0 = android.os.SystemClock.uptimeMillis()
            fun check() {
                if (gen != waitGen && queue == TextToSpeech.QUEUE_FLUSH) return      // a newer instruction came
                if (stops != playGen) return                                          // stopped meanwhile
                NaviKokoro.take(words, quiet = true)?.let { (samples, rate) ->
                    play(samples, rate, flush = queue == TextToSpeech.QUEUE_FLUSH)
                    Log.i(TAG, "voice (device, after ${android.os.SystemClock.uptimeMillis() - t0} ms): \"$words\""); return }
                // 18.11.2026 ("Es werden teilweise Sprachdateien auch von dem Android-System gesprochen, was immer noch ein Hin
                // und Her an Sprechern ist ... zum Beispiel Baustelle in 2,7 Kilometer"): a warning's words change with the
                // distance, so it is never computed ahead - and after 3 s the phone's voice said it. A warning may come a
                // few seconds late, an instruction less so; the phone's voice only when the device's really brings nothing.
                val wait = if (queue == TextToSpeech.QUEUE_FLUSH) WAIT_MS else WAIT_WARNING_MS
                if (android.os.SystemClock.uptimeMillis() - t0 < wait) { main.postDelayed({ check() }, 100); return }
                // System I-092 ("soll alles über Piper gehen"): no phone's voice in between any more - one voice throughout; an
                // announcement the device's voice could not make in time is left out (the next one comes in its voice)
                net.prototux.aare.DiagLog.warn("voice: the device's voice was not ready in ${wait / 1000} s - left out, not said with the phone's voice: \"${words.take(40)}\"", "VOICE")
            }
            check(); return
        }
        phone(words, queue, id)
        Log.i(TAG, "voice: \"$words\"")
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var waitGen = 0
    private var track: android.media.AudioTrack? = null
    private var playingUntil = 0L
    private var playGen = 0

    /** A finished announcement on the navigation channel; a newer instruction cuts an older one off, a warning waits. */
    private fun play(raw: FloatArray, rawRate: Int, flush: Boolean) {
        // made for the car's navigation channel: its rate, below full scale, soft edges (17.11.2026: "knackt und knarzt")
        val rate = carRate.takeIf { it in 8_000..48_000 } ?: rawRate
        // and at the music's target level, as everything Mate itself says or sounds (18.11.2026)
        // during a call half of that: said, but not over the talk (18.11.2026)
        val quiet = net.prototux.aare.car.Calls.promptFactor(app)
        val samples = net.prototux.aare.audio.Resample.tidy(
            net.prototux.aare.audio.Loudness.own(net.prototux.aare.audio.Resample.to(raw, rawRate, rate), rate), rate)
            .also { s -> if (quiet != 1f) for (i in s.indices) s[i] *= quiet }
        val now = android.os.SystemClock.uptimeMillis()
        // the music down softly first and the voice half a second later, unless one is playing already (17.11.2026)
        val fresh = track == null && now >= playingUntil
        if (fresh) net.prototux.aare.audio.MediaFade.duck()
        val wait = (if (flush) 0L else (playingUntil - now).coerceAtLeast(0)) + if (fresh) net.prototux.aare.audio.MediaFade.DOWN_MS else 0L
        if (flush) stopTrack()
        val gen = playGen
        val end = now + wait + samples.size * 1000L / rate + 300
        main.postDelayed({
            if (gen != playGen) return@postDelayed      // stopped meanwhile (I-008: a waiting one played after "Zurück")
            stopTrack()
            val t = runCatching { net.prototux.aare.audio.StaticTrack.of(attrs, rate, samples) }
                .onFailure { Log.w(TAG, "voice: sound could not be played", it) }.getOrNull() ?: run {
                    // nothing plays now and nothing waits behind it: the music and a paused book back
                    if (playingUntil <= end) net.prototux.aare.audio.MediaFade.release()
                    return@postDelayed
                }
            audio.requestAudioFocus(focus)
            runCatching { t.play() }.onFailure { Log.w(TAG, "voice: sound could not be played", it) }; track = t
            val ms = samples.size * 1000L / rate + 300
            main.postDelayed({ if (track === t) { stopTrack(); promptOver() } }, ms)
        }, wait)
        playingUntil = end
    }

    private fun stopTrack() { track?.let { runCatching { it.stop() }; it.release() }; track = null }

    /** O230: the coming announcements (Valhalla's, smoothed) computed now in Kokoro, for their moment. */
    fun prepare(texts: List<String>) = NaviKokoro.prepare(app, texts.map { polish(it) }.let { p -> if (mode == Mode.BRIEF) p.map { brief(it) } else p })
    /** O230: warnings with fixed words computed once (new route, ...). */
    fun prepareWarnings(texts: List<String>) = NaviKokoro.prepare(app, texts, first = true)

    fun stop() { runCatching { tts.stop() }; playGen++; playingUntil = 0; stopTrack(); promptOver() }

    companion object {
        private const val TAG = "MateNavi"
        private const val WAIT_MS = 10_000L
        private const val WAIT_WARNING_MS = 45_000L
        private const val REBIND_MS = 20_000L
        /** [Mode.BRIEF]: the turn is said at its first announcement within this distance. */
        const val BRIEF_M = 1_500.0

        /** A mere "go on" without a turn, in Valhalla's [lang] (NaviPhrases). */
        fun goOnOnly(text: String, lang: String = net.prototux.aare.Language.code): Boolean = NaviPhrases.of(lang).goOnOnly(text)
        /** The turn without road names and destinations, for [Mode.BRIEF]. */
        fun brief(text: String, lang: String = net.prototux.aare.Language.code): String = NaviPhrases.of(lang).brief(text)
        /** The sample rate of the car's navigation channel (set when it opens), 0 = unknown. */
        @Volatile var carRate = 0
        private const val PHONE_ENGINE = "com.google.android.tts"

        /** Valhalla's words as one would say them (NaviPhrases). */
        fun polish(text: String, lang: String = net.prototux.aare.Language.code): String = NaviPhrases.of(lang).polish(text)
    }
}
