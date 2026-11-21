package net.prototux.aare.speech

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log

/**
 * Mate as the device's speech engine (17.11.2026: "Mate Navi nutzt die Kokoro-Stimmen ... aber alle anderen Apps,
 * Better Route Planner und Google, nutzen immer noch die billige Google-lokale Stimme ... schön, wenn alle Programme die
 * vernünftigen Kokoro-Sprachdateien bekommen würden"): any app that speaks through Android's text-to-speech gets the
 * voice chosen in Mate (Kokoro or Piper), once Mate is set as the preferred engine in the tablet's settings.
 * In the chosen voice's language (German or English). Kokoro needs 2-3 s to the first sound on the S6 Lite - for turn prompts of other apps Piper (Thorsten)
 * answers at once.
 */
class MateTtsService : TextToSpeechService() {

    @Volatile private var stopped = false

    /** The chosen voice's language, the device's where none is chosen. */
    private fun voiceLang(): String = LocalTts.chosen(this)?.lang ?: net.prototux.aare.Language.code

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val mine = voiceLang()
        return when {
            lang == null || !lang.startsWith(mine) -> TextToSpeech.LANG_NOT_SUPPORTED
            !LocalTts.available(this) -> TextToSpeech.LANG_MISSING_DATA
            country.isNullOrEmpty() || country in COUNTRIES[mine].orEmpty() -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            else -> TextToSpeech.LANG_AVAILABLE
        }
    }

    override fun onGetLanguage(): Array<String> = when (voiceLang()) {
        "en" -> arrayOf("eng", if (LocalTts.chosen(this)?.name?.contains("GB") == true) "GBR" else "USA", "")
        else -> arrayOf("deu", "DEU", "")
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = onIsLanguageAvailable(lang, country, variant)

    override fun onStop() { stopped = true }

    /**
     * 17.11.2026: "wenn ich sage Wiedergeben, stürzt die Mate-App ab" - nothing of a request may take Mate down (it runs
     * in Mate's own process, the car's screen with it): any failure is logged and told to the caller as an error.
     */
    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        try { synthesize(request, callback) } catch (t: Throwable) {
            Log.e(TAG, "speaking for another app failed", t)
            runCatching { callback.error(TextToSpeech.ERROR_SYNTHESIS) }
        }
    }

    private fun synthesize(request: SynthesisRequest, callback: SynthesisCallback) {
        stopped = false
        val text = request.charSequenceText?.toString() ?: request.text.orEmpty()
        if (onIsLanguageAvailable(request.language, request.country, request.variant) < TextToSpeech.LANG_AVAILABLE) {
            callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET); return
        }
        if (text.isBlank()) { callback.start(22050, AudioFormat.ENCODING_PCM_16BIT, 1); callback.done(); return }
        // the device voice computes on Mate's own voice thread, one request at a time; a voice that is chosen but whose
        // files are gone is an error, not a crash
        if (LocalTts.chosen(this)?.let { Voices.available(this, it) } != true) { callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET); return }
        val t0 = android.os.SystemClock.elapsedRealtime()
        // the caller's speed (0.5-2.0 as 50-200) and pitch are not taken: the voices speak at their own pace
        var started = false
        val max = callback.maxBufferSize
        val rate = LocalTts.stream(this, text, ready = { r -> started = callback.start(r, AudioFormat.ENCODING_PCM_16BIT, 1) == TextToSpeech.SUCCESS }) { chunk ->
            if (!started) return@stream false
            if (stopped) return@stream false
            val bytes = ByteArray(chunk.size * 2)
            for (i in chunk.indices) {
                val v = (chunk[i] * 32767f).toInt().coerceIn(-32768, 32767)
                bytes[2 * i] = v.toByte(); bytes[2 * i + 1] = (v shr 8).toByte()
            }
            var off = 0
            while (off < bytes.size) {
                val n = minOf(max, bytes.size - off)
                if (callback.audioAvailable(bytes, off, n) != TextToSpeech.SUCCESS) return@stream false
                off += n
            }
            true
        }
        if (rate == null) { if (!started) callback.error(TextToSpeech.ERROR_SYNTHESIS) else callback.done(); return }
        Log.i(TAG, "spoke for ${callingPackage()}: \"${text.take(40)}\" in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
        callback.done()
    }

    private fun callingPackage(): String = runCatching { packageManager.getNameForUid(android.os.Binder.getCallingUid()) }.getOrNull() ?: "?"

    companion object {
        private const val TAG = "MateTts"
        /** The countries a language's voices answer for fully (ISO 3166 alpha-2 and alpha-3). */
        private val COUNTRIES = mapOf("de" to setOf("DE", "DEU"), "en" to setOf("US", "USA", "GB", "GBR"))
    }
}

/** The words "Beispiel anhören" in the tablet's settings speaks (Android asks the engine for them). */
class MateTtsSample : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(TextToSpeech.LANG_AVAILABLE, android.content.Intent().putExtra("sampleText",
            "Hallo, hier spricht " + (LocalTts.chosen(this)?.name ?: "Mate") + ". In dreihundert Metern rechts abbiegen."))
        finish()
    }
}

/** Android asks an engine whether its voice data is there (Settings, "Sprachdaten"): the German voice when Mate has one. */
class MateTtsCheck : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val ok = LocalTts.available(this)
        val data = android.content.Intent()
            .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, if (ok) arrayListOf("deu-DEU") else arrayListOf())
            .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, if (ok) arrayListOf() else arrayListOf("deu-DEU"))
        setResult(if (ok) TextToSpeech.Engine.CHECK_VOICE_DATA_PASS else TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL, data)
        finish()
    }
}
