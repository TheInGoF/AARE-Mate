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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Android's own speech recognition (System O-036, Q-064, 25.11.2026: "dann baue Google in den Einstellungen ein"), as an
 * alternative to Whisper. The recognizer listens itself, from the tablet's microphone ([Live]; sound handed over by Mate
 * it did not take, 25.11.2026). What it hears goes through the same matching as Whisper's text
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
     * One listening of the system's recognizer, from the tablet's microphone (25.11.2026, log of the test: Google's
     * recognizer took no sound handed over by Mate - it answered "nothing heard" after a second every time; now it listens
     * itself, ends at the speaker's pause and is done within a second). [run] blocks; [stop] ends the listening now and
     * gets what was said, [cancel] gives it up.
     */
    class Live(context: Context, private val pauseMs: Int = 1200) {
        private val app = context.applicationContext
        private val main = Handler(Looper.getMainLooper())
        @Volatile private var recognizer: SpeechRecognizer? = null
        @Volatile private var cancelled = false

        fun stop() { main.post { runCatching { recognizer?.stopListening() } } }
        fun cancel() { cancelled = true; main.post { runCatching { recognizer?.cancel() } } }

        /**
         * The alternatives heard, best first; empty when nobody spoke; null when the system's recognition failed (then
         * Whisper). [onEnd]: the speaker has finished, the words are being worked out.
         */
        fun run(onEnd: () -> Unit = {}, maxMs: Long = 30_000L): List<String>? {
            val service = services(app).firstOrNull() ?: return null
            val done = CountDownLatch(1)
            var result: List<String>? = null
            var error: Int? = null
            var spoke = false
            val t0 = android.os.SystemClock.elapsedRealtime()
            var endAt = 0L
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, pauseMs)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, pauseMs)
            }
            main.post {
                if (cancelled) { done.countDown(); return@post }
                runCatching {
                    recognizer = SpeechRecognizer.createSpeechRecognizer(app, service).apply {
                        setRecognitionListener(object : RecognitionListener {
                            override fun onResults(b: Bundle?) { result = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty(); done.countDown() }
                            override fun onError(e: Int) { error = e; done.countDown() }
                            override fun onReadyForSpeech(p: Bundle?) {}
                            override fun onBeginningOfSpeech() { spoke = true }
                            override fun onRmsChanged(v: Float) {}
                            override fun onBufferReceived(b: ByteArray?) {}
                            override fun onEndOfSpeech() { endAt = android.os.SystemClock.elapsedRealtime(); onEnd() }
                            override fun onPartialResults(b: Bundle?) {}
                            override fun onEvent(t: Int, b: Bundle?) {}
                        })
                        startListening(intent)
                    }
                }.onFailure { Log.w(TAG, "system recognition did not start", it); done.countDown() }
            }
            val inTime = done.await(maxMs, TimeUnit.MILLISECONDS)
            main.post { runCatching { if (!inTime) recognizer?.cancel(); recognizer?.destroy(); recognizer = null } }
            val now = android.os.SystemClock.elapsedRealtime()
            val r = result?.map { it.trim() }?.filter { it.isNotEmpty() }
            val how = when {
                cancelled -> "given up"
                !inTime -> "no answer in ${maxMs / 1000} s"
                error != null -> "error $error"
                r == null -> "did not start"
                else -> "${r.size} alternatives"
            }
            // what was said stays out of the log (dictated messages)
            net.prototux.aare.DiagLog.add("speech: system recognition (${service.packageName}) $how after ${(now - t0) / 1000.0} s" +
                (if (endAt > 0) ", ${now - endAt} ms after the speaker stopped" else "") + (if (spoke) "" else ", no speech heard"))
            if (cancelled) return emptyList()
            // nobody spoke: an answer (silence); everything else falls back to Whisper
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) return emptyList()
            return if (inTime && error == null) r else null
        }
    }
}
