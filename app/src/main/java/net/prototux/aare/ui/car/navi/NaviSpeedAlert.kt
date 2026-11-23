package net.prototux.aare.ui.car.navi

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The optional speed limit warning (O194, 16.11.2026: "tempolimit warnungen kann man optional doch bauen. dann aber
 * ausschaltbar in den einstellungen. mit lautstärke verschiedenn tönen. wie oft sie kommen nur 3 mal bis zur nächsten
 * überschreitung und ab wiviel kmh"). Off unless the driver turns it on in the phone app (page Navi).
 *
 * One overspeed: the car goes more than [overKmh] above the limit. It is warned once (O198, 16.11.2026: "wenn der
 * jenige länger trotzdem 60 statt 50 fährt dann ist das so. wir warnen nur einmal je überschrietung nciht alle 10s") -
 * the tone sounding [times] (1-3) in a row; only after the car has come back under the limit (with [SLACK_KMH] of
 * slack, so hovering at the edge does not count as new) a new overspeed is warned again. A plain counter, no Android.
 */
internal class NaviSpeedAlert(private val overKmh: Int, val times: Int) {
    private var over = false

    /** The car's [kmh] under [limit]: true when the warning is due now (once per overspeed). */
    fun update(kmh: Double?, limit: Int?): Boolean {
        if (kmh == null || limit == null || limit <= 0) { over = false; return false }
        if (!over) {
            if (kmh > limit + overKmh) { over = true; return true }
            return false
        }
        if (kmh <= limit + overKmh - SLACK_KMH) over = false
        return false
    }

    companion object {
        const val SLACK_KMH = 2

        /** The tones to choose from, made here (no sound files): a short sine shape each. */
        enum class Tone(val key: String) { GONG("gong"), BEEP("beep"), DOUBLE("double"), BELL("bell") }
        fun tone(key: String) = Tone.entries.firstOrNull { it.key == key } ?: Tone.GONG

        private const val RATE = 44_100

        /** Plays [tone] [times] in a row at [volume] (0-1) on the navigation channel: the car plays it as guidance. */
        fun play(tone: Tone, volume: Float, times: Int = 1) {
            // the music down first, softly, and the tone half a second later (17.11.2026: "auch bevor er spricht ... eine
            // halbe Sekunde warten"); the music up again once the tone is over - by a timer, not only by the track's marker
            net.prototux.aare.audio.MediaFade.duck()
            main.postDelayed({ playNow(tone, volume, times) }, net.prototux.aare.audio.MediaFade.DOWN_MS)
        }

        private val main = android.os.Handler(android.os.Looper.getMainLooper())

        private fun playNow(tone: Tone, volume: Float, times: Int) {
            // at the music's target level like Mate's speech (18.11.2026); the volume chosen for the tone goes on top
            val one = render(tone).let { r ->
                val g = net.prototux.aare.audio.Loudness.ownGain(FloatArray(r.size) { r[it] / 32768f }, RATE)
                if (g == 1f) r else ShortArray(r.size) { (r[it] * g).toInt().coerceIn(-32768, 32767).toShort() }
            }
            val gap = ShortArray(RATE / 4)
            val pcm = ShortArray(0).let { var a = it; repeat(times.coerceIn(1, 3)) { i -> a += one; if (i < times.coerceIn(1, 3) - 1) a += gap }; a }
            val track = runCatching { AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
                .also { t ->
                    // a track that could not take the tone is let go here (I-118i), else it stays held
                    val written = runCatching { t.write(pcm, 0, pcm.size) }.getOrElse { e -> t.release(); throw e }
                    if (written < 0) { t.release(); throw IllegalStateException("write failed ($written)") }
                } }
                .onFailure { android.util.Log.w("MateNavi", "speed warning: tone could not be played", it) }.getOrNull() ?: run {
                    // the music up again, it was turned down for the tone
                    net.prototux.aare.audio.MediaFade.release()
                    return
                }
            // during a call half of it, as Mate's speech (18.11.2026)
            val quiet = runCatching { net.prototux.aare.car.Calls.promptFactor(net.prototux.aare.AareApp.instance) }.getOrDefault(1f)
            track.setVolume(volume.coerceIn(0f, 1f) * quiet)
            runCatching { track.play() }
            main.postDelayed({ runCatching { track.stop() }; track.release(); net.prototux.aare.audio.MediaFade.release() },
                pcm.size * 1000L / RATE + 200)
        }

        /** The tone as 16-bit samples: notes of (frequency, start s, length s), each fading out, summed and limited. */
        fun render(tone: Tone): ShortArray {
            val notes: List<Triple<Double, Double, Double>> = when (tone) {
                Tone.GONG -> listOf(Triple(784.0, 0.0, 0.6), Triple(587.0, 0.25, 0.8))
                Tone.BEEP -> listOf(Triple(1000.0, 0.0, 0.18))
                Tone.DOUBLE -> listOf(Triple(1200.0, 0.0, 0.12), Triple(1200.0, 0.2, 0.12))
                Tone.BELL -> listOf(Triple(1568.0, 0.0, 1.0), Triple(3136.0, 0.0, 0.5))
            }
            val len = notes.maxOf { it.second + it.third }
            val out = DoubleArray((len * RATE).toInt() + 1)
            for ((f, start, dur) in notes) {
                val s0 = (start * RATE).toInt(); val n = (dur * RATE).toInt()
                for (i in 0 until n) {
                    val t = i.toDouble() / RATE
                    // a soft start (5 ms) and an exponential fade, so no click at either end
                    val env = minOf(1.0, t / 0.005) * exp(-4.0 * t / dur) * (1 - i.toDouble() / n)
                    out[s0 + i] += sin(2 * PI * f * t) * env
                }
            }
            val peak = out.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1.0)
            return ShortArray(out.size) { (out[it] / peak * 0.8 * Short.MAX_VALUE).toInt().toShort() }
        }
    }
}
