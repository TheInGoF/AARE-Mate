package net.prototux.aare.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mate's sound filter for the car's speakers (wish O50, stage 1): a ten band equalizer on the sound Mate sends to
 * the car's media channel - its own music, audiobooks and every foreign app alike. Navigation prompts on their own
 * channel stay untouched. Set by hand in the phone app, kept for each car ([net.prototux.aare.Settings.eqGains]);
 * a later stage measures the car with the phone's microphone and fills in the same bands.
 *
 * The bands are peaking filters an octave apart (RBJ audio EQ cookbook). Raised bands lower the whole signal by
 * as much first ([preampDb]), so a boost never clips what was loud already.
 */
object CarEq {
    /** Centre frequencies of the bands in Hz. */
    val BANDS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    const val MIN_DB = -12
    const val MAX_DB = 6
    /** One octave per band: Q = sqrt(2). */
    private const val Q = 1.4142135

    @Volatile var enabled = false
        private set
    @Volatile var gains = FloatArray(BANDS.size)
        private set
    /** Counts every change, so a running stream picks up new values between two chunks. */
    @Volatile var version = 0
        private set
    /** While measuring the car (stage 2) the filter stands aside: the test sound must reach the speakers unchanged. */
    @Volatile var bypass = false
        set(v) { if (field != v) { field = v; version++ } }
    /** Whether the car reports driving: measuring only while parked. Set by the car runtime. */
    @Volatile var carDriving = false
    /** Whether a car media stream is open, so a test sound actually reaches the car. Set by [CarAudioOut]. */
    @Volatile var mediaLinked = false
    /** System L-104: lift the bass of music that has little of it ([BassLevel]); only for music, not audio books. */
    @Volatile var bassLevel = false
    /**
     * The song Mate's own music plays now (System L-105): its Plex key and its bass share over the whole song when known
     * (NaN: not yet). Null for other apps' music - their bass is measured as it plays.
     */
    @Volatile var song: Pair<String, Double>? = null
    /** Told the bass share of a song once enough of it was heard (key, dB below the whole) - Mate's music keeps it. */
    @Volatile var onSongShare: ((String, Double) -> Unit)? = null
        set(v) { if (field != v) { field = v; version++ } }

    fun set(on: Boolean, dB: FloatArray) {
        val g = FloatArray(BANDS.size) { i -> dB.getOrElse(i) { 0f }.coerceIn(MIN_DB.toFloat(), MAX_DB.toFloat()) }
        if (on == enabled && g.contentEquals(gains)) return
        gains = g
        enabled = on
        version++
    }

    /** How much the whole signal goes down first: as much as the highest raised band. */
    fun preampDb(dB: FloatArray): Float = -(dB.maxOrNull() ?: 0f).coerceAtLeast(0f)

    /** One band's filter as (b0, b1, b2, a1, a2), normalised to a0 = 1; null if the band does nothing at this rate. */
    fun coefficients(freq: Int, dB: Float, rate: Int): DoubleArray? {
        if (dB == 0f || freq >= rate * 0.45) return null
        val a = 10.0.pow(dB / 40.0)
        val w = 2 * PI * freq / rate
        val alpha = sin(w) / (2 * Q)
        val c = cos(w)
        val a0 = 1 + alpha / a
        return doubleArrayOf((1 + alpha * a) / a0, -2 * c / a0, (1 - alpha * a) / a0, -2 * c / a0, (1 - alpha / a) / a0)
    }

    /** The filter's effect at [freq] in dB, for the curve in the phone app (preamp included). */
    fun responseDb(dB: FloatArray, freq: Double, rate: Int = 48000): Double {
        var total = preampDb(dB).toDouble()
        val w = 2 * PI * freq / rate
        // |H(e^jw)|² of each biquad from its coefficients
        val c1 = cos(w); val c2 = cos(2 * w); val s1 = sin(w); val s2 = sin(2 * w)
        BANDS.forEachIndexed { i, f ->
            val k = coefficients(f, dB.getOrElse(i) { 0f }, rate) ?: return@forEachIndexed
            val nr = k[0] + k[1] * c1 + k[2] * c2; val ni = -(k[1] * s1 + k[2] * s2)
            val dr = 1 + k[3] * c1 + k[4] * c2; val di = -(k[3] * s1 + k[4] * s2)
            total += 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
        }
        return total
    }
}

/**
 * Applies [CarEq] to one stream of 16 bit little-endian PCM, in place. Keeps the filters' memory for each channel,
 * so chunk boundaries don't click; new settings take effect at the next chunk.
 */
class CarEqProcessor(private val rate: Int, private val channels: Int) {
    private var seen = -1
    private var on = false
    private var coeffs: Array<DoubleArray> = emptyArray()
    private var gain = 1.0
    /** Per band and channel: x1, x2, y1, y2. */
    private var state: Array<DoubleArray> = emptyArray()

    private fun update() {
        seen = CarEq.version
        val dB = CarEq.gains
        val active = CarEq.BANDS.indices.mapNotNull { i -> CarEq.coefficients(CarEq.BANDS[i], dB[i], rate) }
        on = CarEq.enabled && !CarEq.bypass && active.isNotEmpty()
        if (active.size != coeffs.size) state = Array(active.size * channels) { DoubleArray(4) }
        coeffs = active.toTypedArray()
        gain = 10.0.pow(CarEq.preampDb(dB) / 20.0)
    }

    /** The chunk as numbers, kept between calls so no array is made per chunk. */
    private var work = DoubleArray(0)

    /** System L-104: the bass of music that has little of it lifted, measured before the filter's bands (the preset does not count). */
    private val bass = BassLevel(rate, channels)

    fun process(buf: ByteArray, off: Int, len: Int) {
        if (seen != CarEq.version) update()
        if (CarEq.bassLevel && !CarEq.bypass) bass.process(buf, off, len) else bass.idle()
        if (!on) return
        val n = len / 2
        if (work.size < n) work = DoubleArray(n)
        val x = work
        for (i in 0 until n) x[i] = ((buf[off + 2 * i].toInt() and 0xff) or (buf[off + 2 * i + 1].toInt() shl 8)).toShort() * gain
        // band by band over the whole chunk, each channel on its own: the filter's numbers stay in registers
        for (b in coeffs.indices) {
            val k = coeffs[b]
            val b0 = k[0]; val b1 = k[1]; val b2 = k[2]; val a1 = k[3]; val a2 = k[4]
            for (ch in 0 until channels) {
                val st = state[b * channels + ch]
                var x1 = st[0]; var x2 = st[1]; var y1 = st[2]; var y2 = st[3]
                var i = ch
                while (i < n) {
                    val v = x[i]
                    val y = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                    x2 = x1; x1 = v; y2 = y1; y1 = y
                    x[i] = y
                    i += channels
                }
                st[0] = x1; st[1] = x2; st[2] = y1; st[3] = y2
            }
        }
        for (i in 0 until n) {
            val y = x[i]
            val v = if (y > 32767.0) 32767 else if (y < -32768.0) -32768 else y.toInt()
            buf[off + 2 * i] = v.toByte()
            buf[off + 2 * i + 1] = (v shr 8).toByte()
        }
    }
}

/**
 * System L-104 (24.11.2026: "Musik mit sehr, sehr wenig Bass … So ein Musik-Bass-Analyzer, dass man ungefähr gleichmäßige
 * Auslastung der Anlage hat"): measures how much of the music's power lies below [SPLIT_HZ] over the last seconds and, when
 * that share is below [ZERO_AT_DB], lifts the bass along a straight line ([liftFor]) with a low shelf at [SHELF_HZ] - at most [MAX_DB], gently ([STEP_DB] per
 * block) so nothing pumps. Bass-rich music stays as it is (never cut). The whole signal goes down by part of the lift ([HEADROOM]) as
 * headroom (a song with little bass gains little in its peaks), so it neither clips nor drops much in loudness. Measured on what comes in, before the lift.
 */
class BassLevel(private val rate: Int, private val channels: Int) {
    private val low = lowPass(SPLIT_HZ.toDouble(), rate)
    private val lowState = DoubleArray(4)
    private var lowPower = 0.0
    private var allPower = 0.0
    private var shelf: DoubleArray? = null
    private var shelfState = Array(channels) { DoubleArray(4) }
    /** The lift applied now, in dB (0 to [MAX_DB]). */
    var liftDb = 0.0
        private set
    /** The music's bass share now, in dB below its whole power (for the log and the tests). */
    var shareDb = 0.0
        private set
    private var shelfDb = 0.0
    private var songKey: String? = null
    private var songLow = 0.0
    private var songAll = 0.0
    private var songS = 0.0
    private var songKnown = false
    private var saidDb = 0.0
    private var saidAt = 0L

    fun idle() { liftDb = 0.0; shelfDb = 0.0; shelf = null; measuredMs = 0L }

    fun process(buf: ByteArray, off: Int, len: Int) {
        val n = len / 2
        val frames = n / channels
        if (frames == 0) return
        // the share of the bass in this block, from the channels' mean
        var lp = 0.0; var ap = 0.0
        for (f in 0 until frames) {
            var m = 0.0
            for (ch in 0 until channels) {
                val i = off + 2 * (f * channels + ch)
                m += ((buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)).toShort().toDouble()
            }
            m /= channels
            val y = low[0] * m + low[1] * lowState[0] + low[2] * lowState[1] - low[3] * lowState[2] - low[4] * lowState[3]
            lowState[1] = lowState[0]; lowState[0] = m; lowState[3] = lowState[2]; lowState[2] = y
            lp += y * y; ap += m * m
        }
        // quiet passages say nothing about the song's bass
        if (ap / frames > QUIET * QUIET) {
            val a = frames.toDouble() / rate / AVERAGE_S
            lowPower += a * (lp / frames - lowPower); allPower += a * (ap / frames - allPower)
        }
        // the whole song (L-105): measured over all of it while it plays, told once enough was heard; a song measured
        // before gets its lift from the first second and keeps it - no swaying with its parts
        val song = CarEq.song
        if (song?.first != songKey) {
            songKey?.let { k -> if (songS >= SONG_MIN_S && songLow > 0 && songAll > 0) CarEq.onSongShare?.invoke(k, 10 * log10(songLow / songAll)) }
            songKey = song?.first; songLow = 0.0; songAll = 0.0; songS = 0.0
        }
        if (songKey != null && ap / frames > QUIET * QUIET) { songLow += lp; songAll += ap; songS += frames.toDouble() / rate }
        val known = song?.second?.takeUnless { it.isNaN() }
        songKnown = known != null
        if (known != null || (allPower > 0 && lowPower > 0)) {
            if (allPower > 0 && lowPower > 0) shareDb = 10 * log10(lowPower / allPower)
            val want = liftFor(known ?: shareDb)
            val step = STEP_DB * frames / (rate * 0.02)
            liftDb += (want - liftDb).coerceIn(-step, step)
        }
        if (abs(liftDb - shelfDb) > 0.1) { shelfDb = liftDb; shelf = if (liftDb < 0.05) null else lowShelf(SHELF_HZ.toDouble(), liftDb, rate) }
        say()
        val k = shelf ?: return
        // little bass to begin with: lifting it raises the peaks only a little - part of the lift ([HEADROOM]) as headroom is
        // enough, and the song does not drop in loudness by the whole lift
        val pre = 10.0.pow(-liftDb * HEADROOM / 20.0)
        for (ch in 0 until channels) {
            val st = shelfState[ch]
            var x1 = st[0]; var x2 = st[1]; var y1 = st[2]; var y2 = st[3]
            var f = 0
            while (f < frames) {
                val i = off + 2 * (f * channels + ch)
                val v = ((buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)).toShort() * pre
                val y = k[0] * v + k[1] * x1 + k[2] * x2 - k[3] * y1 - k[4] * y2
                x2 = x1; x1 = v; y2 = y1; y1 = y
                val o = if (y > 32767.0) 32767 else if (y < -32768.0) -32768 else y.toInt()
                buf[i] = o.toByte(); buf[i + 1] = (o shr 8).toByte()
                f++
            }
            st[0] = x1; st[1] = x2; st[2] = y1; st[3] = y2
        }
    }

    /**
     * Into the log: once the first seconds were measured (also when nothing is lifted - log 24.11.2026 showed no line at all,
     * so whether a song was thought bass-rich could not be seen), then each change of a whole dB of the lift or of 2 dB of
     * the bass share (a new song), at most every [SAY_MS].
     */
    private var saidShare = 99.0
    private var measuredMs = 0L
    private fun say() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (measuredMs == 0L) measuredMs = now
        if (now - measuredMs < FIRST_SAY_MS || now - saidAt < SAY_MS) return
        if (abs(liftDb - saidDb) < 1.0 && abs(shareDb - saidShare) < 2.0) return
        saidDb = liftDb; saidShare = shareDb; saidAt = now
        net.prototux.aare.DiagLog.add("sound: bass of the music %.0f dB below its whole, lifted by %.0f dB (%s)%s"
            .format(java.util.Locale.ROOT, -shareDb, liftDb, CarSound.playing ?: "?",
                if (songKnown) String.format(java.util.Locale.ROOT, " - the song's own %.0f dB, measured over all of it", -(CarEq.song?.second ?: 0.0)) else ""), area = "AUDIO")
    }

    companion object {
        const val SPLIT_HZ = 150
        /**
         * Where the lift's shelf turns (was 120 Hz: the whole lift only below 60 Hz, at 160 Hz a quarter - 25.11.2026: "eventuell
         * wirklich nur bei der Kickdrum … manche Songs haben anscheinend nicht mal diesen 80 Hertz Bereich. Da passiert wieder
         * fast gar nichts"). At 200 Hz: all of it up to 80 Hz (subwoofer, kick), nine tenths at 100 Hz, two thirds at 160 Hz
         * (bass guitar, the kick's body), half at 200 Hz, a third at 250 Hz - so songs without deep bass get their bass notes
         * lifted, the ones the speakers carry, not only the sub range they hardly have.
         */
        const val SHELF_HZ = 200
        /** Of the lift, this much is taken off the whole first as headroom (the wider shelf lifts more of the music's power). */
        const val HEADROOM = 0.6
        /**
         * The line from the song's bass share to its lift (24.11.2026: "Lieder mit wenig Bass, die bleiben bei 0 oder bei 10.
         * Also wäre cool, wenn wir wirklich so eine Kennlinie hinkriegen würden. Also plus 10, plus 0, plus 6"): no lift from
         * [ZERO_AT_DB] up (bass of its own), the whole [MAX_DB] from [FULL_AT_DB] down (thin), straight in between - before,
         * most real songs fell on one of the two ends.
         */
        // 25.11.2026 (log of the morning: the songs measured 3 to 8 dB below the whole, rarely 10-14 - so they got 0 to 3 dB,
        // which nobody hears: "ich erkenne vom Hören subjektiv keinen Unterschied"): the line now spans what real songs measure
        const val ZERO_AT_DB = -2.0
        const val FULL_AT_DB = -10.0
        fun liftFor(shareDb: Double): Double = ((ZERO_AT_DB - shareDb) / (ZERO_AT_DB - FULL_AT_DB) * MAX_DB).coerceIn(0.0, MAX_DB)
        /**
         * 24.11.2026, after a drive ("Es klingt ein bisschen voller, gerade ältere Songs, aber es ist noch nicht so, als wenn man
         * das Schlagzeug vernünftig raushören würde. Plus 6 dB, vielleicht reicht das nicht"): old songs mastered thin for
         * vinyl get up to 10 dB (was 6); songs with bass of their own still get none.
         */
        const val MAX_DB = 10.0
        /** At most this much change of the lift per 20 ms block: 2 dB a second. */
        const val STEP_DB = 0.04
        /** Over this long the share is measured (was 6 s: the lift went up and down with the parts of one song). */
        const val AVERAGE_S = 12.0
        const val QUIET = 300.0
        const val SAY_MS = 20_000L
        /** Heard this long, a song's bass share counts as measured over the whole of it. */
        const val SONG_MIN_S = 60.0
        const val FIRST_SAY_MS = 10_000L

        /** RBJ low pass (Q 0.707) as (b0, b1, b2, a1, a2). */
        fun lowPass(f: Double, rate: Int): DoubleArray {
            val w = 2 * PI * f / rate; val alpha = sin(w) / (2 * 0.7071); val c = cos(w); val a0 = 1 + alpha
            return doubleArrayOf((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }

        /** RBJ low shelf (slope 1) of [dB] as (b0, b1, b2, a1, a2). */
        fun lowShelf(f: Double, dB: Double, rate: Int): DoubleArray {
            val a = 10.0.pow(dB / 40.0); val w = 2 * PI * f / rate; val c = cos(w); val s = sin(w)
            val alpha = s / 2 * sqrt((a + 1 / a) * (1 / 1.0 - 1) + 2)
            val q = 2 * sqrt(a) * alpha
            val a0 = (a + 1) + (a - 1) * c + q
            return doubleArrayOf(a * ((a + 1) - (a - 1) * c + q) / a0, 2 * a * ((a - 1) - (a + 1) * c) / a0,
                a * ((a + 1) - (a - 1) * c - q) / a0, -2 * ((a - 1) + (a + 1) * c) / a0, ((a + 1) + (a - 1) * c - q) / a0)
        }
    }
}
