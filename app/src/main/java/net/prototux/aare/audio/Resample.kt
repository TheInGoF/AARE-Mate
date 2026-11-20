package net.prototux.aare.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * A spoken announcement made fit for the car's navigation channel (17.11.2026: "die Stimme knackt und knarzt ... ob das
 * zu den Ausgabefrequenzen von dem Autoradio passt"): Piper speaks at 22 050 Hz, Kokoro at 24 000 Hz, the car's
 * navigation channel mostly takes 16 000 Hz - Android's mixer brought it down with a plain resampler, which leaves
 * harsh, crackling overtones. Here it is converted with a windowed-sinc filter (no tones above the new half rate),
 * kept below full scale and faded in and out over a few milliseconds.
 */
object Resample {
    private const val TAPS = 24            // each side
    /** The loudest a sample may be (about -1 dB): no clipping in the car's 16 bits. */
    private const val PEAK = 0.89f
    private const val FADE_MS = 6

    /** [x] at [from] Hz as [to] Hz. */
    fun to(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty()) return x.copyOf()
        val ratio = to.toDouble() / from
        val cutoff = minOf(1.0, ratio) * 0.92            // of the input's half rate
        val n = floor(x.size * ratio).toInt()
        val out = FloatArray(n)
        val span = TAPS / minOf(1.0, ratio)
        for (i in 0 until n) {
            val t = i / ratio                            // where in the input
            val lo = maxOf(0, floor(t - span).toInt() + 1); val hi = minOf(x.size - 1, floor(t + span).toInt())
            var acc = 0.0; var wsum = 0.0
            for (k in lo..hi) {
                val d = t - k
                val a = d * cutoff
                val sinc = if (abs(a) < 1e-9) 1.0 else sin(PI * a) / (PI * a)
                val w = 0.5 + 0.5 * cos(PI * d / span)  // Hann window
                val c = sinc * w
                acc += x[k] * c; wsum += c
            }
            out[i] = if (wsum != 0.0) (acc / wsum).toFloat() else 0f
        }
        return out
    }

    /** Below [PEAK] (scaled down only if louder) and faded in and out: no click at the start, none at the end. */
    fun tidy(x: FloatArray, rate: Int): FloatArray {
        var peak = 0f
        for (v in x) peak = maxOf(peak, abs(v))
        val g = if (peak > PEAK) PEAK / peak else 1f
        val fade = minOf(x.size / 2, rate * FADE_MS / 1000)
        return FloatArray(x.size) { i ->
            val edge = when { i < fade -> i.toFloat() / fade; i >= x.size - fade -> (x.size - 1 - i).toFloat() / fade; else -> 1f }
            x[i] * g * edge
        }
    }
}
