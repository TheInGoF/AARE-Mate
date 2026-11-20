package net.prototux.aare.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.prototux.aare.R

/**
 * Music by voice (O56): the microphone button beside the search in Mate Musik. Listens, recognizes on the device,
 * finds the artist or album in the user's own collection (VoiceMatch, in the music service) and plays it; says
 * what plays, or that nothing fitted. A second tap ends listening early.
 */
object MusicVoice {
    enum class Phase { IDLE, LISTEN, THINK }

    var phase by mutableStateOf(Phase.IDLE)
        private set
    private var dictation: Dictation? = null
    private val main = Handler(Looper.getMainLooper())

    fun tap(context: Context) {
        when (phase) {
            Phase.LISTEN -> dictation?.stop()
            Phase.THINK -> {}
            Phase.IDLE -> start(context.applicationContext)
        }
    }

    private fun say(text: String) { net.prototux.aare.notify.CarNotificationListener.instance?.speak(text) }

    /**
     * Nothing in the collection fitted: the same sound once more with the small model, which hears names
     * differently ("Wizo": base heard "Vic-O", tiny "wie zu" - and that one is found).
     */
    private fun secondTry(ctx: Context, first: String) {
        val audio = Dictation.lastAudio
        // the small model is gone (18.11.2026): the second try only where one is still there from earlier
        if (audio == null || "tiny" !in LocalAsr.installed(ctx)) { phase = Phase.IDLE; say(ctx.getString(R.string.music_voice_none, first)); return }
        Thread {
            val text = runCatching {
                val rec = LocalAsr.load(ctx, "tiny", 2)
                try { LocalAsr.recognize(rec, audio.first, audio.second) } finally { rec.release() }
            }.getOrNull()?.trim().orEmpty()
            android.util.Log.i("MateMusic", "zweiter Versuch (tiny): \"$text\"")
            main.post {
                if (text.isBlank() || text.equals(first, ignoreCase = true) ||
                    !net.prototux.aare.music.MusicService.voice(text) { r ->
                        phase = Phase.IDLE
                        say(if (r != null) ctx.getString(R.string.music_voice_playing, r) else ctx.getString(R.string.music_voice_none, first))
                    }) { phase = Phase.IDLE; say(ctx.getString(R.string.music_voice_none, first)) }
            }
        }.start()
    }

    private fun start(ctx: Context) {
        phase = Phase.LISTEN
        dictation = Dictation(ctx).also { d ->
            d.run({ main.post { phase = Phase.THINK } }) { heard ->
                main.post {
                    dictation = null
                    when {
                        heard == null -> { phase = Phase.IDLE; say(ctx.getString(R.string.idmate_dictate_failed)) }
                        heard.isBlank() -> { phase = Phase.IDLE; say(ctx.getString(R.string.idmate_dictate_nothing)) }
                        !net.prototux.aare.music.MusicService.voice(heard) { r ->
                            if (r != null) { phase = Phase.IDLE; say(ctx.getString(R.string.music_voice_playing, r)) }
                            else secondTry(ctx, heard)
                        } -> { phase = Phase.IDLE; say(ctx.getString(R.string.music_voice_not_running)) }
                    }
                }
            }
        }
    }
}
