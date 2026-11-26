package net.prototux.aare.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import net.prototux.aare.audio.MicInject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Android's own speech recognition (System O-036, Q-064, 25.11.2026: "dann baue Google in den Einstellungen ein"), as an
 * alternative to Whisper. In the car the car's microphone is injected as the tablet's while the speaker talks (MicInject);
 * elsewhere the recognizer listens from the tablet's microphone ([Live]). What it hears goes through the same matching as Whisper's text
 * (VoiceMatch against the own music, VoiceCommands). Mate's own recognition service is never asked (it would ask Whisper).
 * Any failure: null - the caller falls back to Whisper.
 */
object GoogleAsr {
    const val WHISPER = "whisper"
    const val SYSTEM = "system"
    private const val TAG = "MateAsr"
    /** Google's recognizers first: "Speech Services by Google", then the Google app. */
    private val PREFERRED = listOf("com.google.android.tts", "com.google.android.googlequicksearchbox", "com.google.android.as")

    /** The recognizers on the device other than Mate's own, the preferred ones first. */
    fun services(context: Context): List<ComponentName> =
        context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filter { it.packageName != context.packageName }
            .sortedBy { PREFERRED.indexOf(it.packageName).let { i -> if (i < 0) PREFERRED.size else i } }

    /** Whether the system's recognition can be used: a recognizer other than Mate's is there. */
    fun usable(context: Context): Boolean = services(context).isNotEmpty()

    /** Which recognition the dictation uses now: the system's when chosen and usable, else Whisper. */
    fun chosen(context: Context): Boolean = net.prototux.aare.Settings(context).asrEngine == SYSTEM && usable(context)

    /** A label for the settings: the recognizer that would be asked (its app's name), or null. */
    fun label(context: Context): String? = services(context).firstOrNull()?.let { c ->
        runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(c.packageName, 0)).toString() }.getOrNull() ?: c.packageName
    }

    /**
     * Whether Google hears the car's microphone injected as the tablet's (MicInject): null untried, false not (then Whisper
     * listens in the car, until Mate restarts). The tablet's own microphone is not asked in the car: there it gave Google
     * nothing (System I-134, log 26.11.2026 16:43-16:44: ten times "no speech heard", the tablet in the centre console -
     * likely silence from Android, the service lacking the microphone with the screen off), the car's is meant for it.
     */
    @Volatile var carMicInjected: Boolean? = null

    /**
     * One listening of the system's recognizer. In the car ([mic], the car's microphone through the car session) its sound
     * is injected as the tablet's microphone while the speaker talks, piece by piece as it comes, and the recognizer records
     * as it always does (26.11.2026: "Erst den Versuch mit dem laufend durchgereichten Mikrofon, wie im Original" - handed
     * through by a pipe, Google took nothing; Android Auto injects it the same way). Not possible, or Google answers nothing
     * while the car's microphone heard speech: null, Whisper listens, and Google is not asked again in the car until Mate
     * restarts. [run] blocks; [stop] ends the listening now and gets what was said, [cancel] gives it up.
     */
    class Live(context: Context, private val pauseMs: Int = 1200,
               private val mic: ((sink: (ByteArray, Int, Int) -> Unit, onStopped: () -> Unit) -> net.prototux.aare.host.MicHandle?)? = Dictation.carMic) {
        private val app = context.applicationContext
        private val main = Handler(Looper.getMainLooper())
        @Volatile private var recognizer: SpeechRecognizer? = null
        @Volatile private var cancelled = false
        @Volatile private var feed: java.util.concurrent.LinkedBlockingQueue<ByteArray>? = null
        /** The last listening heard speech and got no text (System I-138). */
        @Volatile private var emptyAfterSpeech = false

        fun stop() { feed?.offer(END); main.post { runCatching { recognizer?.stopListening() } } }
        fun cancel() { cancelled = true; feed?.offer(END); main.post { runCatching { recognizer?.cancel() } } }

        /**
         * The alternatives heard, best first; empty when nobody spoke; null when the system's recognition failed (then
         * Whisper). [onEnd]: the speaker has finished, the words are being worked out.
         */
        fun run(onEnd: () -> Unit = {}, maxMs: Long = 30_000L): List<String>? {
            if (mic != null) {
                // in the car: the car's microphone injected as the tablet's (26.11.2026: handed through by a pipe Google
                // took nothing, and through the tablet's microphone Google heard nothing either) - not possible: Whisper
                if (carMicInjected == false) return null
                val (r, heardSpeech) = once(onEnd, maxMs, mic)
                if (cancelled) return emptyList()
                // Google heard the speech but gave no text: not the injection's fault - Whisper this time (System I-138)
                if (r == null && emptyAfterSpeech) return null
                if (r != null && (r.isNotEmpty() || !heardSpeech)) {
                    if (r.isNotEmpty() && carMicInjected != true) { carMicInjected = true; net.prototux.aare.DiagLog.add("speech: Google hears the car's microphone (injected)") }
                    return r
                }
                carMicInjected = false
                net.prototux.aare.DiagLog.add("speech: Google did not hear the car's microphone injected (" +
                    (MicInject.lastFailure ?: if (r == null) "an error" else "nothing recognized while the car's microphone heard speech") +
                    ") - Whisper listens in the car until Mate restarts")
                return null
            }
            return once(onEnd, maxMs, null).first
        }

        /** One listening; the alternatives (as [run]) and whether the car's microphone heard speech meanwhile. */
        private fun once(onEnd: () -> Unit, maxMs: Long,
                         car: ((sink: (ByteArray, Int, Int) -> Unit, onStopped: () -> Unit) -> net.prototux.aare.host.MicHandle?)?): Pair<List<String>?, Boolean> {
            val service = services(app).firstOrNull() ?: return null to false
            val done = CountDownLatch(1)
            var result: List<String>? = null
            var error: Int? = null
            var spoke = false
            var silenced: Boolean? = null
            var partial: List<String>? = null
            var partials = 0
            var keys: String? = null
            val t0 = android.os.SystemClock.elapsedRealtime()
            var endAt = 0L
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
                // the interim results too: where the final one comes back empty, the last of them counts (System I-138)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, pauseMs)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, pauseMs)
            }
            // the car's microphone: its 16 kHz chunks injected as the tablet's microphone (MicInject), by a thread of its own
            // (a recognizer that does not read must not hold up the car's microphone); the recognizer records as usual
            var handle: net.prototux.aare.host.MicHandle? = null
            var writer: Thread? = null
            var inject: MicInject? = null
            var loud = false
            if (car != null) {
                inject = MicInject.open(app, Dictation.RATE) ?: return null to false
                val q = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
                feed = q
                val into = inject
                writer = Thread({
                    runCatching { while (true) { val b = q.take(); if (b === END) break; into.write(b, 0, b.size) } }
                }, "mate-asr-carmic").apply { isDaemon = true; start() }
                handle = runCatching { car({ b, off, len ->
                    var sum = 0.0; val n = len / 2
                    for (i in 0 until n) { val v = ((b[off + 2 * i].toInt() and 0xff) or (b[off + 2 * i + 1].toInt() shl 8)).toShort().toDouble(); sum += v * v }
                    if (n > 0 && kotlin.math.sqrt(sum / n) > LOUD) loud = true
                    q.offer(b.copyOfRange(off, off + len))
                }, { q.offer(END) }) }.getOrNull()
                if (handle == null) { q.offer(END); feed = null; inject.close(); return null to false }
            }
            checkSupport(app, service, intent)
            main.post {
                if (cancelled) { done.countDown(); return@post }
                runCatching {
                    recognizer = SpeechRecognizer.createSpeechRecognizer(app, service).apply {
                        setRecognitionListener(object : RecognitionListener {
                            override fun onResults(b: Bundle?) {
                                result = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                                keys = b?.keySet()?.sorted()?.joinToString("/")
                                done.countDown()
                            }
                            override fun onError(e: Int) { error = e; done.countDown() }
                            override fun onReadyForSpeech(p: Bundle?) {}
                            override fun onBeginningOfSpeech() { spoke = true }
                            override fun onRmsChanged(v: Float) {}
                            override fun onBufferReceived(b: ByteArray?) {}
                            override fun onEndOfSpeech() { endAt = android.os.SystemClock.elapsedRealtime(); onEnd() }
                            override fun onPartialResults(b: Bundle?) {
                                partials++
                                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.map { it.trim() }?.filter { it.isNotEmpty() }
                                    ?.takeIf { it.isNotEmpty() }?.let { partial = it }
                            }
                            override fun onEvent(t: Int, b: Bundle?) {}
                        })
                        startListening(intent)
                    }
                }.onFailure { Log.w(TAG, "system recognition did not start", it); done.countDown() }
                // whether Android hands the recording silence (System I-134: an app without the right to the microphone
                // in the background gets silence, not an error - Google then hears "no speech")
                main.postDelayed({ silenced = silencedNow(app) }, 1_000)
            }
            val inTime = done.await(maxMs, TimeUnit.MILLISECONDS)
            runCatching { handle?.close() }
            feed?.offer(END); feed = null
            runCatching { writer?.join(500) }
            inject?.close()
            main.post { runCatching { if (!inTime) recognizer?.cancel(); recognizer?.destroy(); recognizer = null } }
            val now = android.os.SystemClock.elapsedRealtime()
            var r = result?.map { it.trim() }?.filter { it.isNotEmpty() }
            // System I-138 (26.11.2026, 2.5.13 at home: "erkennt er ja genauso lange, wie lange ich auch spreche … Aber dann
            // blockiert er" - the log: Google heard the speech end, then "0 alternatives"): an empty final result after
            // speech takes the last interim one; still empty, it is a failure - Whisper listens - and Google is asked online
            val tookPartial = r != null && r.isEmpty() && partial != null
            if (tookPartial) r = partial
            val emptyAfterSpeech = r != null && r.isEmpty() && spoke && error == null && inTime && !cancelled
            this.emptyAfterSpeech = emptyAfterSpeech
            val how = when {
                cancelled -> "given up"
                !inTime -> "no answer in ${maxMs / 1000} s"
                error != null -> "error $error"
                r == null -> "did not start"
                else -> "${r.size} alternatives"
            }
            // what was said stays out of the log (dictated messages)
            net.prototux.aare.DiagLog.add("speech: system recognition (${service.packageName}, " + (if (car != null) "car's microphone injected" else "tablet's microphone") +
                ") $how after ${(now - t0) / 1000.0} s" + (if (endAt > 0) ", ${now - endAt} ms after the speaker stopped" else "") +
                (if (spoke) "" else ", no speech heard") + (if (silenced == true) ", Android silenced the recording" else "") +
                ", ${if (intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)) "offline" else "online"}, $partials interim" +
                (if (tookPartial) " (the final one empty: the last interim one taken)" else "") +
                (if (emptyAfterSpeech) " - speech heard, no text: a failure (answer's keys ${keys ?: "-"})" else "") + (if (car != null) (if (loud) ", the car's microphone heard speech" else ", the car's microphone quiet") else ""))
            if (cancelled) return emptyList<String>() to loud
            if (emptyAfterSpeech) {
                if (preferOffline) { preferOffline = false; net.prototux.aare.DiagLog.add("speech: Google gave no text offline - from now on it is asked online (until Mate restarts)") }
                return null to loud
            }
            // nobody spoke: an answer (silence); everything else falls back
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) return emptyList<String>() to loud
            return (if (inTime && error == null) r else null) to loud
        }
    }

    /** Offline first (the German package on the device); off once Google gave no text offline (System I-138). */
    @Volatile var preferOffline = true
    @Volatile private var supportChecked = false

    /**
     * Once per start of Mate: whether the recognizer has the language on the device (installed, being downloaded, possible)
     * and online - into the log; possible but not installed, its download is started (System I-138).
     */
    private fun checkSupport(context: Context, service: ComponentName, intent: Intent) {
        if (supportChecked || android.os.Build.VERSION.SDK_INT < 33) return
        supportChecked = true
        val lang = intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE) ?: return
        val main = Handler(Looper.getMainLooper())
        main.post {
            runCatching {
                val rec = SpeechRecognizer.createSpeechRecognizer(context, service)
                fun has(list: List<String>) = list.any { it.equals(lang, true) || it.substringBefore('-').equals(lang.substringBefore('-'), true) }
                rec.checkRecognitionSupport(intent, { it.run() }, object : android.speech.RecognitionSupportCallback {
                    override fun onSupportResult(s: android.speech.RecognitionSupport) {
                        val installed = has(s.installedOnDeviceLanguages)
                        val pending = has(s.pendingOnDeviceLanguages)
                        val possible = has(s.supportedOnDeviceLanguages)
                        net.prototux.aare.DiagLog.add("speech: ${service.packageName} for $lang - on the device " +
                            (if (installed) "installed" else if (pending) "being downloaded" else if (possible) "possible, not installed" else "not offered") +
                            ", online " + (if (has(s.onlineLanguages)) "yes" else "not said"))
                        if (possible && !installed && !pending) runCatching { rec.triggerModelDownload(intent) }
                            .onSuccess { net.prototux.aare.DiagLog.add("speech: the download of the $lang package for the device was started") }
                        main.postDelayed({ runCatching { rec.destroy() } }, 2_000)
                    }
                    override fun onError(error: Int) {
                        net.prototux.aare.DiagLog.add("speech: ${service.packageName} does not tell its languages (error $error)")
                        main.post { runCatching { rec.destroy() } }
                    }
                })
            }.onFailure { net.prototux.aare.DiagLog.add("speech: languages not asked (${it.javaClass.simpleName})") }
        }
    }

    /** Whether a recording now running is silenced by Android (its app may not use the microphone); null unknown. */
    private fun silencedNow(context: Context): Boolean? {
        if (android.os.Build.VERSION.SDK_INT < 29) return null
        val am = context.getSystemService(android.media.AudioManager::class.java) ?: return null
        val now = runCatching { am.activeRecordingConfigurations }.getOrNull() ?: return null
        return if (now.isEmpty()) null else now.any { it.isClientSilenced }
    }

    private val END = ByteArray(0)
    /** Above this the car's microphone hears speech (as Dictation's pause detection: clearly above a quiet car). */
    private const val LOUD = 900.0
}
