package net.prototux.aare.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

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

    fun process(buf: ByteArray, off: Int, len: Int) {
        if (seen != CarEq.version) update()
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
