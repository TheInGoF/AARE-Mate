package net.prototux.aare.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Android's own speech recognition (System O-036, Q-064, 25.11.2026: "dann baue Google in den Einstellungen ein"), as an
 * alternative to Whisper. Mate records as before - the car's microphone, its own pause detection - and hands the
 * recorded sound to the system's recognizer (Android 13 and later can take sound from the app, EXTRA_AUDIO_SOURCE);
 * the recognizer never opens a microphone of its own. What it hears goes through the same matching as Whisper's text
 * (VoiceMatch against the own music, VoiceCommands). Mate's own recognition service is never asked (it would ask Whisper).
 * Any failure: null - the caller falls back to Whisper.
 */
object GoogleAsr {
    const val WHISPER = "whisper"
    const val SYSTEM = "system"
    private const val TAG = "MateAsr"
    private const val TIMEOUT_MS = 12_000L
    /** Google's recognizers first: "Speech Services by Google", then the Google app. */
    private val PREFERRED = listOf("com.google.android.tts", "com.google.android.googlequicksearchbox", "com.google.android.as")

    /** The recognizers on the device other than Mate's own, the preferred ones first. */
    fun services(context: Context): List<ComponentName> =
        context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filter { it.packageName != context.packageName }
            .sortedBy { PREFERRED.indexOf(it.packageName).let { i -> if (i < 0) PREFERRED.size else i } }

    /** Whether the system's recognition can be used: Android 13 or later and a recognizer other than Mate's. */
    fun usable(context: Context): Boolean = Build.VERSION.SDK_INT >= 33 && services(context).isNotEmpty()

    /** Which recognition the dictation uses now: the system's when chosen and usable, else Whisper. */
    fun chosen(context: Context): Boolean = net.prototux.aare.Settings(context).asrEngine == SYSTEM && usable(context)

    /** A label for the settings: the recognizer that would be asked (its app's name), or null. */
    fun label(context: Context): String? = services(context).firstOrNull()?.let { c ->
        runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(c.packageName, 0)).toString() }.getOrNull() ?: c.packageName
    }

    /**
     * The recognized alternatives for [pcm] (mono, -1..1, [rate] Hz), best first; null when the system's recognition
     * failed or is not there. Blocks up to [TIMEOUT_MS]; never on the main thread.
     */
    fun recognize(context: Context, pcm: FloatArray, rate: Int): List<String>? {
        if (Build.VERSION.SDK_INT < 33) return null
        val service = services(context).firstOrNull() ?: return null
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        val done = CountDownLatch(1)
        var result: List<String>? = null
        var error: Int? = null
        val pipe = ParcelFileDescriptor.createPipe()
        var recognizer: SpeechRecognizer? = null
        val t0 = android.os.SystemClock.elapsedRealtime()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, rate)
        }
        main.post {
            runCatching {
                recognizer = SpeechRecognizer.createSpeechRecognizer(app, service).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onResults(b: Bundle?) { result = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty(); done.countDown() }
                        override fun onError(e: Int) { error = e; done.countDown() }
                        override fun onReadyForSpeech(p: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(v: Float) {}
                        override fun onBufferReceived(b: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onPartialResults(b: Bundle?) {}
                        override fun onEvent(t: Int, b: Bundle?) {}
                    })
                    startListening(intent)
                }
            }.onFailure { Log.w(TAG, "system recognition did not start", it); done.countDown() }
        }
        // the sound, then a little quiet so the recognizer hears the end; closing the pipe ends it
        val writer = Thread({
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                    val quiet = rate * 8 / 10
                    val bytes = ByteArray((pcm.size + quiet) * 2)
                    for (i in pcm.indices) {
                        val s = (pcm[i].coerceIn(-1f, 1f) * 32767f).toInt()
                        bytes[2 * i] = s.toByte(); bytes[2 * i + 1] = (s shr 8).toByte()
                    }
                    out.write(bytes)
                }
            }
        }, "mate-asr-feed").apply { start() }
        val inTime = done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        main.post { runCatching { if (!inTime) recognizer?.cancel(); recognizer?.destroy() } }
        runCatching { pipe[0].close() }
        runCatching { writer.join(1000) }
        val ms = android.os.SystemClock.elapsedRealtime() - t0
        val r = result?.map { it.trim() }?.filter { it.isNotEmpty() }
        val how = when {
            !inTime -> "no answer in ${TIMEOUT_MS / 1000} s"
            error != null -> "error ${error}"
            r == null -> "did not start"
            else -> "${r.size} alternatives"
        }
        // what was said stays out of the log (dictated messages)
        net.prototux.aare.DiagLog.add("speech: system recognition (${service.packageName}) $how in ${ms} ms for %.1f s of sound".format(pcm.size / rate.toDouble()))
        // no speech heard is an answer (silence), everything else falls back
        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) return emptyList()
        return if (inTime && error == null) r else null
    }
}
