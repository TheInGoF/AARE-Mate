package net.prototux.aare.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.prototux.aare.R

/**
 * A destination by voice (O97b): the "Ziel" button beside the area button while a navigation app is in front.
 * Listens, recognizes on the device, reads the place back and waits for "Los" / "Nochmal" / "Abbrechen"; "Los"
 * hands the place to the navigation app (Google Maps starts the guidance by itself, ABRP gets the Car App
 * navigation intent). Google Maps' own search cannot be used in Mate (its menus do not redraw, O97a).
 */
object NaviVoice {
    enum class Phase { IDLE, LISTEN, THINK, CONFIRM, FAILED }

    var phase by mutableStateOf(Phase.IDLE)
        private set
    var place by mutableStateOf("")
        private set
    private var dictation: Dictation? = null
    private val main = Handler(Looper.getMainLooper())

    private fun say(text: String) { net.prototux.aare.notify.CarNotificationListener.instance?.speak(text) }

    fun tap(context: Context) {
        when (phase) {
            Phase.LISTEN -> dictation?.stop()
            Phase.THINK -> {}
            else -> listen(context.applicationContext)
        }
    }

    fun listen(ctx: Context) {
        place = ""
        phase = Phase.LISTEN
        dictation = Dictation(ctx).also { d ->
            d.run({ main.post { phase = Phase.THINK } }) { heard ->
                main.post {
                    dictation = null
                    // "Navigiere nach …", "Fahr zu …": only the place goes to the navigation app
                    place = heard.orEmpty().replace(Regex("^(bitte\\s+)?(navigiere|navigation|fahr|fahre|route|führe mich|bring mich)\\w*\\s+(nach|zum|zur|zu|in die|in den)?\\s*", RegexOption.IGNORE_CASE), "")
                        .trim().trimEnd('.', '!', '?')
                    phase = if (heard == null || place.isBlank()) Phase.FAILED else Phase.CONFIRM
                    say(when {
                        heard == null -> ctx.getString(R.string.idmate_dictate_failed)
                        place.isBlank() -> ctx.getString(R.string.idmate_dictate_nothing)
                        else -> ctx.getString(R.string.navi_voice_readback, place)
                    })
                }
            }
        }
    }

    fun go(navigate: (String) -> Unit) {
        if (phase != Phase.CONFIRM || place.isBlank()) return
        navigate(place)
        phase = Phase.IDLE
    }

    fun cancel() { dictation?.cancel(); dictation = null; phase = Phase.IDLE }
}
