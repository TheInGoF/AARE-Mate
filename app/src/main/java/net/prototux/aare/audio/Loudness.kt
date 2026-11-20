package net.prototux.aare.audio

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * One level for everything that goes to the car (18.11.2026: "wenn ich in der Musik-App sage, dass er den Zielpegel auf
 * minus 15 oder 18 festlegen soll, dann ist die Musik-App leiser. Das trifft aber leider nicht auf Hörbücher,
 * Sprachansagen etc. zu ... alle anderen System-Apps sollten dann auch leiser gemacht werden und dem Zielpegel von der
 * Musik-App angepasst sein, damit man nicht ständig laut und leise machen muss im Auto").
 *
 * Mate Musik is levelled title by title with what the Plex server measured. Every other source has no such number, so
 * Mate measures it itself where its sound passes on the way to the car: the loudness as broadcasting measures it
 * (ITU-R BS.1770, in LUFS), learned per source over minutes and kept, and the source is turned down to the target -
 * never up, as with the music. A source keeps one gain; it is not a compressor (no pumping within a title).
 * Calls do not pass here: they run over the car's hands-free connection.
 */
object Loudness {
    /** Whether other sources are levelled at all, and to what (the music's target, -18 LUFS + its boost). */
    @Volatile var on = false
    @Volatile var targetLufs = -18.0
    /** Mate's own package: its music is levelled already, title by title. */
    @Volatile var own = ""

    /** What was learned per source: mean square (K-weighted, gated) and the seconds of sound behind it. */
    val known = ConcurrentHashMap<String, DoubleArray>()

    const val WINDOW_S = 180.0
    const val MAX_CUT_DB = 15.0
    private const val PREFS = "mate_level"
    @Volatile private var loaded = false
    @Volatile private var readAt = 0L

    private fun app(): android.content.Context? = runCatching { net.prototux.aare.AareApp.instance }.getOrNull()

    /** The settings as they are now; read again every few seconds by the streams themselves (cheap: kept in memory). */
    fun refresh(now: Long = android.os.SystemClock.elapsedRealtime()) {
        if (now - readAt < 3000 && readAt != 0L) return
        readAt = now
        val c = app() ?: return
        val s = net.prototux.aare.Settings(c)
        on = s.musicLevel && s.levelAll
        targetLufs = -18.0 + s.musicLevelBoostDb
        own = c.packageName
        if (!loaded) {
            loaded = true
            runCatching {
                c.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).all.forEach { (k, v) ->
                    val p = (v as? String)?.split(';')?.mapNotNull { it.toDoubleOrNull() }
                    if (p != null && p.size == 2 && p[0] > 0) known[k] = doubleArrayOf(p[0], p[1])
                }
            }
        }
    }

    fun store(key: String, ms: Double, secs: Double) {
        known[key] = doubleArrayOf(ms, secs)
        val c = app() ?: return
        runCatching { c.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putString(key, "$ms;$secs").apply() }
    }

    /**
     * The loudness of a whole sound on one channel (a spoken sentence, a tone), counted as coming from both sides of
     * the car - the same number the stream to the car gives it. Pauses within it do not count.
     */
    fun integrated(x: FloatArray, rate: Int): Double {
        if (x.isEmpty()) return -120.0
        val k = kWeighting(rate)
        val st = Array(2) { DoubleArray(4) }
        val block = (rate * 0.4).toInt().coerceAtLeast(1)
        val blocks = ArrayList<Double>()
        var sum = 0.0; var n = 0
        for (s in x) {
            var v = s.toDouble()
            for (f in 0..1) {
                val c = k[f]; val m = st[f]
                val y = c[0] * v + c[1] * m[0] + c[2] * m[1] - c[3] * m[2] - c[4] * m[3]
                m[1] = m[0]; m[0] = v; m[3] = m[2]; m[2] = y
                v = y
            }
            sum += v * v * 2
            if (++n == block) { blocks += sum / n; sum = 0.0; n = 0 }
        }
        // the rest counts when it is most of a block, or all there is
        if (n > 0 && (blocks.isEmpty() || n >= block / 2)) blocks += sum / n
        val heard = blocks.filter { lufs(it) > -50.0 }
        if (heard.isEmpty()) return -120.0
        val about = lufs(heard.average())
        return lufs(heard.filter { lufs(it) > about - 10.0 }.average())
    }

    /**
     * Mate's own sound at the music's target (18.11.2026: "wenn wir im App-Menü einstellen, wie weit wir die Musik
     * runterdrehen, müssen wir die anderen Ausgaben der App auch so weit runterdrehen. Sonst muss man ständig lauter
     * und leiser stellen"): a spoken sentence or a tone, measured as a whole and brought to the target - down as far
     * as it takes, up by 6 dB at most. Unchanged while the levelling is off. The factor for [x].
     */
    fun ownGain(x: FloatArray, rate: Int): Float {
        refresh()
        if (!on) return 1f
        val l = integrated(x, rate)
        if (l < -60.0) return 1f
        return 10.0.pow((targetLufs - l).coerceIn(-24.0, 6.0) / 20).toFloat()
    }

    /** [x] at the music's target ([ownGain]); a new array. */
    fun own(x: FloatArray, rate: Int): FloatArray { val g = ownGain(x, rate); return FloatArray(x.size) { x[it] * g } }

    /** LUFS of a K-weighted mean square summed over the channels. */
    fun lufs(ms: Double): Double = if (ms <= 0) -120.0 else -0.691 + 10 * log10(ms)

    /** The two filters of the K-weighting for [rate]: each b0, b1, b2, a1, a2 (48 kHz gives the standard's numbers). */
    fun kWeighting(rate: Int): Array<DoubleArray> {
        val vh = 10.0.pow(3.999843853973347 / 20); val vb = vh.pow(0.4996667741545416)
        var k = tan(PI * 1681.974450955533 / rate); var q = 0.7071752369554196
        var a0 = 1 + k / q + k * k
        val shelf = doubleArrayOf((vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0,
            2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
        k = tan(PI * 38.13547087602444 / rate); q = 0.5003270373238773
        a0 = 1 + k / q + k * k
        val high = doubleArrayOf(1.0, -2.0, 1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
        return arrayOf(shelf, high)
    }
}

/**
 * Levels one stream of 16 bit little-endian PCM in place ([Loudness]). [source] names what plays now (a package, or
 * null); it is asked per chunk, the learned loudness follows it.
 */
class LoudnessLeveller(private val rate: Int, private val channels: Int, private val fixedKey: String? = null,
                       private val source: () -> String? = { null }) {
    private val k = Loudness.kWeighting(rate)
    /** Per filter and channel: x1, x2, y1, y2. */
    private val state = Array(2 * channels) { DoubleArray(4) }
    private val blockFrames = (rate * 0.4).toInt()
    private var frames = 0
    private var sum = 0.0
    private var key = ""
    private var ms = 0.0
    private var secs = 0.0
    private var gainDb = 0.0
    private var storedAt = 0.0
    private var saidDb = 99.0
    /** What the stream is turned down by now, in dB (0 or below), for the log and the tests. */
    val cutDb: Double get() = gainDb

    private fun take(newKey: String) {
        if (key.isNotEmpty() && secs > 0) Loudness.store(key, ms, secs)
        key = newKey
        val k = Loudness.known[newKey]
        ms = k?.get(0) ?: 0.0; secs = k?.get(1) ?: 0.0
        storedAt = secs; saidDb = 99.0
    }

    private fun wanted(): Double {
        if (!Loudness.on || key == Loudness.own || secs < 2.0) return 0.0
        return (Loudness.targetLufs - Loudness.lufs(ms)).coerceIn(-Loudness.MAX_CUT_DB, 0.0)
    }

    fun process(buf: ByteArray, off: Int, len: Int) {
        Loudness.refresh()
        val now = fixedKey ?: source() ?: "media"
        if (now != key) take(now)
        val n = len / 2
        val from = 10.0.pow(gainDb / 20)
        // towards what is wanted at 3 dB a second at most: a new source glides to its level, nothing jumps
        val step = 3.0 * (n / channels) / rate
        gainDb += (wanted() - gainDb).coerceIn(-step, step)
        val to = 10.0.pow(gainDb / 20)
        val mono = if (channels == 1) 2.0 else 1.0       // one channel sounds from both sides of the car
        var i = 0
        while (i < n) {
            val ch = i % channels
            val o = off + 2 * i
            val raw = ((buf[o].toInt() and 0xff) or (buf[o + 1].toInt() shl 8)).toShort().toInt()
            // measured as it comes, before the gain
            var v = raw / 32768.0
            for (f in 0..1) {
                val c = k[f]; val st = state[f * channels + ch]
                val y = c[0] * v + c[1] * st[0] + c[2] * st[1] - c[3] * st[2] - c[4] * st[3]
                st[1] = st[0]; st[0] = v; st[3] = st[2]; st[2] = y
                v = y
            }
            sum += v * v * mono
            if (ch == channels - 1 && ++frames >= blockFrames) block()
            if (from != 1.0 || to != 1.0) {
                val out = (raw * (from + (to - from) * i / n)).toInt()
                buf[o] = out.toByte(); buf[o + 1] = (out shr 8).toByte()
            }
            i++
        }
    }

    /** 400 ms measured: counted when it is sound (not a pause) and not far below what the source usually is. */
    private fun block() {
        val z = sum / frames
        sum = 0.0; frames = 0
        val l = Loudness.lufs(z)
        if (l < -50.0 || (secs >= 3.0 && l < Loudness.lufs(ms) - 10.0)) return
        val t = 0.4
        ms += (z - ms) * (t / (secs + t).coerceAtMost(Loudness.WINDOW_S))
        secs = (secs + t).coerceAtMost(Loudness.WINDOW_S)
        // kept after the first ten seconds of sound and every half minute then (and when another source takes over); said in
        // the log when the source's level has moved by 2 dB
        if (secs < storedAt || secs - storedAt >= (if (storedAt < 10.0) 10.0 else 30.0)) { storedAt = secs; Loudness.store(key, ms, secs) }
        val w = wanted()
        if (secs >= 10.0 && abs(w - saidDb) >= 2.0) {
            saidDb = w
            runCatching {
                net.prototux.aare.DiagLog.add("level: $key at %.1f LUFS, target %.0f - turned down by %.1f dB".format(java.util.Locale.ROOT, Loudness.lufs(ms), Loudness.targetLufs, -w))
            }
        }
    }
}
