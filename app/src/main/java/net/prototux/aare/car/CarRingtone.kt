package net.prototux.aare.car

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import net.prototux.aare.DiagLog
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The ring in the car while a call comes in (18.11.2026: "Was leider noch nicht funktioniert, ist, dass es irgendwie einen
 * Hinweiston gibt, wenn mich jemand anruft. Also wir bräuchten verschiedene Klingeltöne, die wir in der App einstellen
 * können"). The car takes the call over Bluetooth, but stays silent while it rings: it waits for the phone to ring
 * through the call's own sound, and the tablet did not. Mate rings itself, on the car's navigation channel - the way its
 * warning tone reaches the car - over and over until the call is answered, declined or gone (one minute at most).
 *
 * The tones are made here (no sound files); "tablet" plays the tablet's own ringtone the same way, "off" none.
 */
object CarRingtone {
    enum class Tone(val key: String) { CLASSIC("classic"), MARIMBA("marimba"), BELL("bell"), SOFT("soft"), TABLET("tablet"), OFF("off") }

    fun tone(key: String?) = Tone.entries.firstOrNull { it.key == key } ?: Tone.MARIMBA

    private const val RATE = 44_100
    private const val MAX_MS = 60_000L
    private val main = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private var player: android.media.MediaPlayer? = null
    private val timeUp = Runnable { stop("one minute rung") }

    /** Rings [tone] at [volume] (0-1) until [stop]; again while ringing changes nothing. Main thread. */
    fun start(context: Context, tone: Tone, volume: Float, sample: Boolean = false) {
        if (tone == Tone.OFF) return
        if (track != null || player != null) { if (!sample) return; stop(null) }
        net.prototux.aare.audio.MediaFade.duck()
        runCatching {
            if (tone == Tone.TABLET) {
                val uri = android.media.RingtoneManager.getActualDefaultRingtoneUri(context, android.media.RingtoneManager.TYPE_RINGTONE)
                    ?: android.provider.Settings.System.DEFAULT_RINGTONE_URI
                player = android.media.MediaPlayer().apply {
                    setAudioAttributes(attributes())
                    setDataSource(context, uri)
                    isLooping = true
                    setVolume(volume, volume)
                    prepare(); start()
                }
            } else {
                val pcm = levelled(render(tone))
                track = AudioTrack.Builder()
                    .setAudioAttributes(attributes())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build().apply {
                        write(pcm, 0, pcm.size)
                        setLoopPoints(0, pcm.size, -1)
                        setVolume(volume.coerceIn(0f, 1f))
                        play()
                    }
            }
        }.onFailure {
            DiagLog.add("ringtone: could not ring (${it.javaClass.simpleName})")
            stop(null)
            return
        }
        main.removeCallbacks(timeUp)
        main.postDelayed(timeUp, if (sample) 6_000L else MAX_MS)
        if (!sample) DiagLog.add("ringtone: ringing in the car (${tone.key}) on the navigation channel")
    }

    /** Quiet again; [why] for the log (null: say nothing). Main thread. */
    fun stop(why: String?) {
        main.removeCallbacks(timeUp)
        val was = track != null || player != null
        track?.let { runCatching { it.stop() }; it.release() }
        player?.let { runCatching { it.stop() }; it.release() }
        track = null; player = null
        if (was) {
            net.prototux.aare.audio.MediaFade.release()
            why?.let { DiagLog.add("ringtone: stopped - $it") }
        }
    }

    // the navigation channel: Mate's own sound that reaches the car while the music is held
    private fun attributes() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    /** At the music's target level like Mate's other sounds (L-039); the chosen volume goes on top. */
    private fun levelled(r: ShortArray): ShortArray {
        val g = runCatching { net.prototux.aare.audio.Loudness.ownGain(FloatArray(r.size) { r[it] / 32768f }, RATE) }.getOrDefault(1f)
        return if (g == 1f) r else ShortArray(r.size) { (r[it] * g).toInt().coerceIn(-32768, 32767).toShort() }
    }

    /** A note: frequency, start and length in seconds, how fast it fades (larger = shorter), and its overtones. */
    private class Note(val f: Double, val start: Double, val len: Double, val decay: Double = 4.0, val partials: List<Pair<Double, Double>> = listOf(1.0 to 1.0))

    /** One round of the ring with its pause after it, as 16-bit samples; the player loops it. */
    fun render(tone: Tone): ShortArray {
        val marimba = listOf(1.0 to 1.0, 4.0 to 0.25, 10.0 to 0.05)
        val bell = listOf(1.0 to 1.0, 2.0 to 0.5, 3.0 to 0.25, 4.2 to 0.12)
        val (notes, round) = when (tone) {
            // two bursts of the old phone's two tones, then quiet
            Tone.CLASSIC -> (listOf(0.0, 0.5).flatMap { s -> listOf(Note(440.0, s, 0.4, 0.3), Note(480.0, s, 0.4, 0.3)) }) to 3.0
            // a rising marimba figure, played twice
            Tone.MARIMBA -> listOf(0.0, 0.9).flatMap { s ->
                listOf(523.25, 659.25, 783.99, 1046.5).mapIndexed { i, f -> Note(f, s + i * 0.14, 0.5, 6.0, marimba) } } to 3.2
            // a bell struck twice
            Tone.BELL -> listOf(Note(1046.5, 0.0, 1.2, 3.0, bell), Note(1046.5, 0.45, 1.4, 3.0, bell)) to 3.4
            // two soft, slow notes
            Tone.SOFT -> listOf(Note(659.25, 0.0, 1.0, 2.5, listOf(1.0 to 1.0, 2.0 to 0.15)), Note(880.0, 0.6, 1.3, 2.5, listOf(1.0 to 1.0, 2.0 to 0.15))) to 3.6
            Tone.TABLET, Tone.OFF -> return ShortArray(0)
        }
        val out = DoubleArray((round * RATE).toInt())
        for (n in notes) {
            val s0 = (n.start * RATE).toInt(); val count = (n.len * RATE).toInt()
            for (i in 0 until count) {
                if (s0 + i >= out.size) break
                val t = i.toDouble() / RATE
                // a soft start (5 ms) and a fade to nothing at the end: no click
                val env = minOf(1.0, t / 0.005) * exp(-n.decay * t / n.len) * (1 - i.toDouble() / count)
                var v = 0.0
                for ((k, a) in n.partials) v += a * sin(2 * PI * n.f * k * t)
                out[s0 + i] += v * env
            }
        }
        val peak = out.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
        return ShortArray(out.size) { (out[it] / peak * 0.8 * Short.MAX_VALUE).toInt().toShort() }
    }
}
