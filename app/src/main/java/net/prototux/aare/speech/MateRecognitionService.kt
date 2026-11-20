package net.prototux.aare.speech

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Mate's speech recognition for the whole device (18.11.2026: "Dann wäre nicht schlecht, wenn diese Sprachsuche auch wieder dem
 * System bereitgestellt wird"): chosen in the system settings as the voice input, any app that asks Android to listen gets
 * Whisper on the device - no server, no Google. Listens until the speaker pauses, as the dictation in the car.
 */
class MateRecognitionService : RecognitionService() {
    private val main = Handler(Looper.getMainLooper())
    private var dictation: Dictation? = null

    override fun onStartListening(intent: Intent?, cb: Callback) {
        dictation?.cancel()
        safe { cb.readyForSpeech(Bundle()) }
        safe { cb.beginningOfSpeech() }
        val d = Dictation(this)
        dictation = d
        d.run({ main.post { safe { cb.endOfSpeech() } } }) { text ->
            main.post {
                if (dictation === d) dictation = null
                Log.i(TAG, "for ${intent?.getStringExtra("calling_package") ?: "an app"}: \"${text.orEmpty().take(40)}\"")
                when {
                    text == null -> safe { cb.error(SpeechRecognizer.ERROR_CLIENT) }
                    text.isBlank() -> safe { cb.error(SpeechRecognizer.ERROR_NO_MATCH) }
                    else -> safe {
                        cb.results(Bundle().apply {
                            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                            putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(0.9f))
                        })
                    }
                }
            }
        }
    }

    override fun onStopListening(cb: Callback) { dictation?.stop() }

    override fun onCancel(cb: Callback) { dictation?.cancel(); dictation = null }

    private inline fun safe(block: () -> Unit) { try { block() } catch (e: Exception) { Log.w(TAG, "caller gone: ${e.javaClass.simpleName}") } }

    companion object { private const val TAG = "MateAsr" }
}

/**
 * A voice command on the phone (headset button, the assistant gesture where Mate is chosen): Mate's assistant listens
 * and does it, as the bar's microphone in the car. Without a car screen the navigation and apps go through the phone.
 */
class MateVoiceActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VoiceAssistant.tap(this)
        finish()
    }
}
