package net.prototux.aare.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import net.prototux.aare.DiagLog
import kotlin.math.PI
import kotlin.math.sin

/**
 * Trials for the navigation channel, run from the debug app in the car (19.11.2026: "Ich habe tatsächlich immer noch
 * Knarzgeräusche bei den Navi-Ansagen ... dass wir eine Debug-App bauen, die ich in den Apps öffnen kann, und dann kann
 * ich zum Beispiel in der Rubrik Audio verschiedene Tests fahren, zum Beispiel Navi-Ansage mit unterschiedlichen
 * Bitstreams, 48 Kilohertz, was auch immer"; and: "nach einer Navi-Ansage die Musik nicht wieder laut wurde. Sie bleibt
 * dann auf der Lautstärke des Navi-Kanals").
 *
 * - [play]: a test sound on the navigation channel, as a prompt goes (navigation usage, transient focus, the music faded
 *   down and up), in one of the [Tone] variants - the rate and format handed to Android, a sweep, the same under load.
 *   Each run and what the channel saw ([CarAudioOut]'s stream line) go into the log, tagged AUDIO.
 * - [musicBack]: how the music's focus is asked back after a prompt ([MusicBack]); kept, so a drive can be made with it.
 */
object AudioTrials {
    /** A test sound: what Android gets ([rate], float or 16 bit), what it is (tone or sweep), with the processor busy or not. */
    enum class Tone(val rate: Int, val float: Boolean, val sweep: Boolean = false, val load: Boolean = false) {
        TONE_48K(48_000, true), TONE_48K_16BIT(48_000, false), TONE_24K(24_000, true), TONE_22K(22_050, true), TONE_16K(16_000, true),
        SWEEP_48K(48_000, true, sweep = true), TONE_48K_LOAD(48_000, true, load = true), SWEEP_48K_LOAD(48_000, true, sweep = true, load = true)
    }

    /**
     * How the music comes back after a prompt: [GAIN] as before (its focus asked for at once); [RELEASE_FIRST] the
     * prompt's focus given up first, the music's asked for 0.3 s later; [RESTART_MEDIA] as before, and the music's stream
     * to the car closed and opened anew once the prompt is over; [GAIN_TWICE] as before, and asked once more after 1.5 s.
     */
    enum class MusicBack { GAIN, RELEASE_FIRST, RESTART_MEDIA, GAIN_TWICE }

    private const val PREFS = "mate_debug"
    @Volatile var musicBack: MusicBack = MusicBack.GAIN
        private set
    /** Set by [MusicBack.RESTART_MEDIA] when a prompt ended: the media stream to the car closes once and opens again. */
    @Volatile var restartMedia = false

    fun load(context: Context) {
        musicBack = runCatching { MusicBack.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("music_back", null) ?: "GAIN") }
            .getOrDefault(MusicBack.GAIN)
    }

    fun setMusicBack(context: Context, m: MusicBack) {
        musicBack = m
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("music_back", m.name).apply()
        DiagLog.add("audio test: music back after a prompt now ${m.name}", area = "AUDIO")
    }

    @Volatile private var track: AudioTrack? = null

    /** [t] on the navigation channel, about [seconds] long; the log says what was played. */
    fun play(context: Context, t: Tone, seconds: Double = 3.0) {
        stop()
        val app = context.applicationContext
        val samples = make(t, seconds)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
        val audio = app.getSystemService(AudioManager::class.java)
        val format = AudioFormat.Builder().setSampleRate(t.rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(if (t.float) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT).build()
        val bytes = samples.size * (if (t.float) 4 else 2)
        val tr = runCatching {
            AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(format).setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bytes).build().also { tr ->
                    if (t.float) tr.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                    else tr.write(ShortArray(samples.size) { (samples[it] * 32767).toInt().toShort() }, 0, samples.size)
                }
        }.onFailure { DiagLog.error("audio test: ${t.name} could not be played: ${it.message}", "AUDIO") }.getOrNull() ?: return
        track = tr
        DiagLog.add("audio test: ${t.name} - ${if (t.sweep) "sweep 200 Hz to 8 kHz" else "tone 1 kHz"}, %.1f s, %d Hz %s%s"
            .format(java.util.Locale.ROOT, seconds, t.rate, if (t.float) "float" else "16 bit", if (t.load) ", the processor kept busy" else ""), area = "AUDIO")
        MediaFade.duck()
        val busy = if (t.load) busy(seconds) else null
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.postDelayed({
            if (track !== tr) return@postDelayed
            audio.requestAudioFocus(focus)
            runCatching { tr.play() }
            main.postDelayed({
                if (track === tr) stop()
                audio.abandonAudioFocusRequest(focus)
                MediaFade.release()
                busy?.forEach { it.interrupt() }
            }, (seconds * 1000).toLong() + 300)
        }, MediaFade.DOWN_MS)
    }

    fun stop() { track?.let { runCatching { it.stop() }; it.release() }; track = null }

    /** A 1 kHz tone or a logarithmic sweep at 0.5 of full scale, with 20 ms edges. */
    fun make(t: Tone, seconds: Double): FloatArray {
        val n = (t.rate * seconds).toInt()
        val edge = t.rate / 50
        var phase = 0.0
        return FloatArray(n) { i ->
            val f = if (t.sweep) 200.0 * Math.pow(8000.0 / 200.0, i.toDouble() / n) else 1000.0
            phase += 2 * PI * f / t.rate
            val e = when { i < edge -> i.toFloat() / edge; i > n - edge -> (n - i).toFloat() / edge; else -> 1f }
            (0.5 * sin(phase)).toFloat() * e
        }
    }

    /** Threads that keep every core busy for [seconds] - is the crackle the processor being too busy (a voice computed meanwhile)? */
    private fun busy(seconds: Double): List<Thread> = (0 until Runtime.getRuntime().availableProcessors()).map {
        Thread {
            val until = System.nanoTime() + ((seconds + 1) * 1e9).toLong()
            var x = 0.0
            try { while (System.nanoTime() < until && !Thread.currentThread().isInterrupted) x += sin(x) } catch (_: Throwable) {}
        }.apply { isDaemon = true; start() }
    }
}
