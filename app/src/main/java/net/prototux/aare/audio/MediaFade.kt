package net.prototux.aare.audio

import android.os.SystemClock

/**
 * The music's own ramps around a prompt (17.11.2026: "nicht immer nur an, aus die Musik. Also eine halbe Sekunde bis eine
 * Sekunde rein- und rausfaden. Auch bevor er spricht. Also die Sprache sollte dann eine halbe Sekunde warten"): Mate
 * lowers the music it sends to the car over [DOWN_MS] before a prompt ([duck], the prompt waits as long), keeps it low
 * while it plays and brings it up over [UP_MS] after it ([release]). The car still lowers it as well while the prompt
 * plays - this makes the edges soft. A duck never released lifts itself after [MAX_DUCK_MS]. Media channel only.
 */
object MediaFade {
    const val DOWN_MS = 500L
    const val UP_MS = 800L
    private const val LOW = 0.3
    private const val MAX_DUCK_MS = 30_000L
    @Volatile private var from = 1.0
    @Volatile private var to = 1.0
    @Volatile private var since = 0L
    @Volatile private var span = 1L
    @Volatile private var duckedAt = 0L
    /** When the music goes up by itself if the car does not give it back first (0: not waiting). */
    @Volatile private var upAt = 0L
    /** The car raises the music some 5 s after a prompt (log of 17.11.2026): Mate waits for it, at most this long. */
    private const val WAIT_CAR_MS = 7_000L

    private fun level(now: Long): Double {
        if (to < 1.0 && (now - duckedAt > MAX_DUCK_MS || (upAt != 0L && now >= upAt))) { from = level0(now); to = 1.0; since = now; span = UP_MS; upAt = 0L }
        return level0(now)
    }
    private fun level0(now: Long): Double {
        val t = ((now - since).toDouble() / span).coerceIn(0.0, 1.0)
        return from + (to - from) * t
    }

    /** A prompt is coming: down to [LOW] over [DOWN_MS]. */
    fun duck() { val now = SystemClock.elapsedRealtime(); from = level(now); to = LOW; since = now; span = DOWN_MS; duckedAt = now; upAt = 0L }

    /**
     * The prompt is over: the music stays low until the car gives it back ([start], then up over [UP_MS]) - rising earlier
     * would only meet the car's own hard step - or by itself after [WAIT_CAR_MS].
     */
    fun release() { if (to >= 1.0) return; upAt = SystemClock.elapsedRealtime() + WAIT_CAR_MS }

    /** The car gave the music back after a prompt: a soft start even when nothing of Mate's own lowered it. */
    fun start() { val now = SystemClock.elapsedRealtime(); from = if (to < 1.0) level(now) else LOW; to = 1.0; since = now; span = UP_MS; upAt = 0L }

    // The microphone (18.11.2026: "Die Whisper-Erkennung erkennt ja auch Musik ... er muss schon, wenn das Mikrofon
    // angeht, die Musik runterfahren. Sonst ist das unmachbar"): while Mate listens, everything on the media channel is
    // silent - Mate's music and any other app's - and comes back softly when the listening ends.
    private const val MIC_DOWN_MS = 150L
    private const val MIC_UP_MS = 600L
    /** A microphone never closed must not leave the car silent. */
    private const val MIC_MAX_MS = 45_000L
    @Volatile private var micFrom = 1.0
    @Volatile private var micTo = 1.0
    @Volatile private var micSince = 0L
    @Volatile private var micSpan = 1L

    private fun micLevel(now: Long): Double {
        if (micTo < 1.0 && now - micSince > MIC_MAX_MS) { micFrom = 0.0; micTo = 1.0; micSince = now; micSpan = MIC_UP_MS }
        val t = ((now - micSince).toDouble() / micSpan).coerceIn(0.0, 1.0)
        return micFrom + (micTo - micFrom) * t
    }

    /** The microphone opens ([on]) or closes (the one microphone of the car session, MicRouter). */
    @Synchronized fun mic(on: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val to = if (on) 0.0 else 1.0
        if (to == micTo) { if (on) micSince = now - MIC_DOWN_MS; return }
        micFrom = micLevel(now); micTo = to; micSince = now; micSpan = if (on) MIC_DOWN_MS else MIC_UP_MS
    }

    /** Whether the media channel is being held silent for the microphone right now. */
    val micOpen: Boolean get() = micTo < 1.0

    /** The gain the last block ended with: the next one starts there (System I-055). */
    @Volatile private var lastGain = 1.0

    /**
     * Applies the ramp to 16-bit little-endian PCM in place; nothing when at full level.
     *
     * System I-055 (knarzen around prompts, 20.11.2026, Mate Debug: "crackle heard" with the music coming back): each
     * 20 ms block ran from the level at [now] to the level 20 ms later - but [now] is when the block is handled, and the
     * blocks come in bursts (several within the same millisecond), so every block of a burst started again at the same
     * level: the gain jumped back at each block's edge, a 50 Hz saw while the music went down or up. Now each block runs
     * from where the last one ended to the level at its end - no step between blocks.
     */
    @Synchronized fun apply(b: ByteArray, off: Int, len: Int, now: Long) {
        val a = lastGain; val z = level(now + 20) * micLevel(now + 20)
        lastGain = z
        if (a >= 0.999 && z >= 0.999) return
        val n = len / 2
        for (i in 0 until n) {
            val g = a + (z - a) * i / n
            val v = ((b[off + 2 * i].toInt() and 0xff) or (b[off + 2 * i + 1].toInt() shl 8)).toShort()
            val y = (v * g).toInt().coerceIn(-32768, 32767)
            b[off + 2 * i] = y.toByte(); b[off + 2 * i + 1] = (y shr 8).toByte()
        }
    }
}
