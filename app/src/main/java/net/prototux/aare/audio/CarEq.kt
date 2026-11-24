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

    /** System L-104: the bass of music that has little of it lifted, after the filter's bands. */
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
 * that share is below [TARGET_DB], lifts the bass with a low shelf at [SHELF_HZ] - at most [MAX_DB], slowly ([STEP_DB] per
 * block) so nothing pumps. Bass-rich music stays as it is (never cut). The whole signal goes down by half the lift as
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
    private var saidDb = 0.0
    private var saidAt = 0L

    fun idle() { liftDb = 0.0; shelfDb = 0.0; shelf = null }

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
        if (allPower > 0 && lowPower > 0) {
            shareDb = 10 * log10(lowPower / allPower)
            val want = ((TARGET_DB - shareDb) * 0.7).coerceIn(0.0, MAX_DB)
            val step = STEP_DB * frames / (rate * 0.02)
            liftDb += (want - liftDb).coerceIn(-step, step)
        }
        if (abs(liftDb - shelfDb) > 0.1) { shelfDb = liftDb; shelf = if (liftDb < 0.05) null else lowShelf(SHELF_HZ.toDouble(), liftDb, rate) }
        say()
        val k = shelf ?: return
        // little bass to begin with: lifting it raises the peaks only a little - half the lift as headroom is enough,
        // and the song does not drop in loudness by the whole lift
        val pre = 10.0.pow(-liftDb / 2 / 20.0)
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

    /** A change of a whole dB into the log, at most every [SAY_MS]. */
    private fun say() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (abs(liftDb - saidDb) < 1.0 || now - saidAt < SAY_MS) return
        saidDb = liftDb; saidAt = now
        net.prototux.aare.DiagLog.add("sound: bass of the music %.0f dB below its whole, lifted by %.0f dB (%s)"
            .format(java.util.Locale.ROOT, -shareDb, liftDb, CarSound.playing ?: "?"), area = "AUDIO")
    }

    companion object {
        const val SPLIT_HZ = 150
        const val SHELF_HZ = 120
        /** A song whose bass carries less than this share of its power (in dB) gets lifted. */
        const val TARGET_DB = -6.0
        const val MAX_DB = 6.0
        /** At most this much change of the lift per 20 ms block: 1 dB a second. */
        const val STEP_DB = 0.02
        const val AVERAGE_S = 6.0
        const val QUIET = 300.0
        const val SAY_MS = 20_000L

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
