package net.prototux.aare.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Looper
import android.util.Log

/**
 * The car's microphone as the tablet's for other apps, Android Auto's way (System I-134, 26.11.2026: Google's recognition
 * "tut so, als ob sie zuhört, aber es passiert nichts" - it took no sound handed through, and the tablet's own microphone in
 * the seat pocket hears nobody): an audio policy whose mix catches every recording of the kinds a recognizer uses
 * (microphone, voice recognition, unprocessed) and feeds it what Mate writes - the car's microphone - instead of the
 * tablet's. Needs MODIFY_AUDIO_ROUTING, which the ROM gives Mate (System E-039). [open] null: not possible here.
 */
class MicInject private constructor(private val audioManager: AudioManager, private val policy: Any, private val track: AudioTrack) {
    @Volatile private var closed = false

    /** 16-bit mono PCM at the rate given to [open]; drops it once closed. */
    fun write(b: ByteArray, off: Int, len: Int) {
        if (closed) return
        runCatching { track.write(b, off, len) }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { track.stop() }
        runCatching { track.release() }
        runCatching { AudioManager::class.java.getMethod("unregisterAudioPolicy", policy.javaClass).invoke(audioManager, policy) }
            .onFailure { Log.w(TAG, "unregisterAudioPolicy", it) }
    }

    companion object {
        private const val TAG = "MateMicInject"
        private const val RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET = 0x2
        private const val ROUTE_FLAG_LOOP_BACK = 0x2
        private const val MIX_ROLE_INJECTOR = 1
        /** What recognizers record from: the microphone, voice recognition, unprocessed. */
        private val PRESETS = intArrayOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.DEFAULT)

        /** Why the last [open] failed, for the log. */
        @Volatile var lastFailure: String? = null
            private set

        fun open(context: Context, rate: Int): MicInject? {
            if (Build.VERSION.SDK_INT < 29) { lastFailure = "Android too old"; return null }
            val am = context.getSystemService(AudioManager::class.java) ?: return null
            var policy: Any? = null
            return try {
                net.prototux.aare.HiddenApi.exempt("Landroid/media/audiopolicy/", "Landroid/media/AudioManager;", "Landroid/media/AudioAttributes")
                val ruleBuilder = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder").getConstructor().newInstance()
                // Android 13+: the mix's role said first, or a capture rule is refused
                runCatching { ruleBuilder.javaClass.getMethod("setTargetMixRole", Int::class.javaPrimitiveType).invoke(ruleBuilder, MIX_ROLE_INJECTOR) }
                val setPreset = AudioAttributes.Builder::class.java.getMethod("setCapturePreset", Int::class.javaPrimitiveType)
                val addRule = ruleBuilder.javaClass.getMethod("addRule", AudioAttributes::class.java, Int::class.javaPrimitiveType)
                for (p in PRESETS) {
                    val attrs = (setPreset.invoke(AudioAttributes.Builder(), p) as AudioAttributes.Builder).build()
                    addRule.invoke(ruleBuilder, attrs, RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET)
                }
                val rule = ruleBuilder.javaClass.getMethod("build").invoke(ruleBuilder)
                val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
                val mixBuilder = Class.forName("android.media.audiopolicy.AudioMix\$Builder").getConstructor(rule.javaClass).newInstance(rule)
                mixBuilder.javaClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                mixBuilder.javaClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mixBuilder, ROUTE_FLAG_LOOP_BACK)
                val mix = mixBuilder.javaClass.getMethod("build").invoke(mixBuilder)
                val policyBuilder = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder").getConstructor(Context::class.java).newInstance(context)
                policyBuilder.javaClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix)
                policyBuilder.javaClass.getMethod("setLooper", Looper::class.java).invoke(policyBuilder, Looper.getMainLooper())
                val p = policyBuilder.javaClass.getMethod("build").invoke(policyBuilder)
                val status = AudioManager::class.java.getMethod("registerAudioPolicy", p.javaClass).invoke(am, p) as Int
                if (status != 0) throw IllegalStateException("registerAudioPolicy: $status")
                policy = p
                val track = p.javaClass.getMethod("createAudioTrackSource", mixClass).invoke(p, mix) as AudioTrack?
                    ?: throw IllegalStateException("createAudioTrackSource refused")
                track.play()
                lastFailure = null
                MicInject(am, p, track)
            } catch (e: Throwable) {
                val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                lastFailure = "${cause.javaClass.simpleName}: ${cause.message}"
                Log.w(TAG, "car microphone not injected", cause)
                policy?.let { pp -> runCatching { AudioManager::class.java.getMethod("unregisterAudioPolicy", pp.javaClass).invoke(am, pp) } }
                null
            }
        }
    }
}
