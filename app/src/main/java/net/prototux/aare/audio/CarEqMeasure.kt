package net.prototux.aare.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Measuring the car (wish O50, stage 2): sine sweeps (System L-005) go to the car's speakers through Mate, the phone's
 * microphone at the driver's seat listens, and the ten bands of [CarEq] get the correction that flattens what it heard.
 *
 * Each band is compared with the sweeps' own level in it, so in a perfect car each band would be heard as it was
 * played; how far a band sits above or below the middle is what the car (speakers, cabin) does to it. The correction takes back
 * three quarters of that, raises no band by more than [CarEq.MAX_DB], and only ever lowers the outermost bands -
 * a phone microphone hears little at 31 Hz and 16 kHz, and boosting what it can't hear would only add distortion.
 */
object CarEqAnalysis {
    const val FFT = 8192
    /** The bands whose average is "the middle" (125 Hz to 8 kHz). */
    private val MIDDLE = 2..8
    /** Below this distance to the background noise a band's reading is not trusted (dB). */
    const val MIN_SNR_DB = 10.0
    private const val SHARE = 0.75

    /** Power in each band (octave around its centre) in dB, averaged over the recording (Welch, Hann window). */
    fun bandLevels(x: FloatArray, rate: Int): DoubleArray {
        val power = DoubleArray(FFT / 2)
        val window = DoubleArray(FFT) { 0.5 - 0.5 * cos(2 * PI * it / (FFT - 1)) }
        val re = DoubleArray(FFT); val im = DoubleArray(FFT)
        var blocks = 0
        var start = 0
        while (start + FFT <= x.size) {
            for (i in 0 until FFT) { re[i] = x[start + i] * window[i]; im[i] = 0.0 }
            fft(re, im)
            for (k in 1 until FFT / 2) power[k] += re[k] * re[k] + im[k] * im[k]
            blocks++
            start += FFT / 2
        }
        val bin = rate.toDouble() / FFT
        return DoubleArray(CarEq.BANDS.size) { b ->
            val f = CarEq.BANDS[b]
            val lo = (f / sqrt(2.0) / bin).roundToInt().coerceAtLeast(1)
            val hi = (f * sqrt(2.0) / bin).roundToInt().coerceAtMost(FFT / 2)
            var sum = 0.0
            for (k in lo until hi) sum += power[k]
            10 * log10(sum / maxOf(blocks, 1) + 1e-12)
        }
    }

    class Result(val levels: DoubleArray, val noise: DoubleArray, val gains: FloatArray?, val error: String?)

    /**
     * Target curves (stage 3): how the car should sound after measuring, in dB per band against flat.
     * Neutral is flat; warm gives a little more low end and softer highs; bass lifts the low end clearly.
     */
    val TARGETS: List<Pair<String, FloatArray>> = listOf(
        "neutral" to FloatArray(10),
        "warm" to floatArrayOf(3f, 3f, 2f, 1f, 0f, 0f, 0f, -1f, -2f, -2f),
        "bass" to floatArrayOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
    )

    /**
     * The correction from what was heard with the test sound ([levels]) and before it ([noise]), towards [target].
     * [reference]: the test sound's own levels (System L-005: the sweep is not pink like the noise), taken off before
     * comparing; [micDb]: the microphone's own deviation per band (a measuring microphone's calibration file).
     */
    fun correction(levels: DoubleArray, noise: DoubleArray, target: FloatArray = FloatArray(levels.size),
                   reference: DoubleArray? = null, micDb: DoubleArray? = null): Result {
        val trusted = levels.indices.filter { levels[it] - noise[it] >= MIN_SNR_DB }
        if (MIDDLE.count { it in trusted } < 5) return Result(levels, noise, null, "quiet")
        val rel = DoubleArray(levels.size) { levels[it] - (reference?.getOrNull(it) ?: 0.0) - (micDb?.getOrNull(it) ?: 0.0) }
        val middle = MIDDLE.filter { it in trusted }.map { rel[it] }.average()
        val gains = FloatArray(levels.size) { b ->
            if (b !in trusted) return@FloatArray 0f
            val c = (-(rel[b] - middle - target.getOrElse(b) { 0f }) * SHARE).roundToInt()
            val top = if (b == 0 || b == levels.lastIndex) 0 else CarEq.MAX_DB
            c.coerceIn(CarEq.MIN_DB, top).toFloat()
        }
        return Result(levels, noise, gains, null)
    }

    /**
     * System L-005 ("Töne, die Bass, Mitten und Hochtöner sauber auseinanderhalten (z. B. Gleitsinus über den ganzen
     * Frequenzbereich, ggf. mehrfach und gemittelt)"): an exponential sine sweep from [f1] to [f2] Hz over [seconds] -
     * it spends the same time in every octave, so each band gets its full loudness in turn instead of a share of the
     * noise; soft edges against clicks.
     */
    fun sweep(rate: Int, seconds: Double, f1: Double = 20.0, f2: Double = 20_000.0, amplitude: Double = 0.35): FloatArray {
        val n = (rate * seconds).toInt()
        val k = Math.log(f2 / f1)
        val fade = (rate * 0.05).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / rate
            val phase = 2 * PI * f1 * seconds / k * (Math.exp(t / seconds * k) - 1)
            val edge = minOf(1.0, i.toDouble() / fade, (n - 1 - i).toDouble() / fade)
            (sin(phase) * amplitude * edge).toFloat()
        }
    }

    /** [count] sweeps of [seconds] with [gap] seconds of silence after each: what is played. */
    fun sweeps(rate: Int, seconds: Double, gap: Double, count: Int): FloatArray {
        val one = sweep(rate, seconds)
        val pause = (rate * gap).toInt()
        val out = FloatArray((one.size + pause) * count)
        for (c in 0 until count) one.copyInto(out, c * (one.size + pause))
        return out
    }

    /**
     * A measuring microphone's calibration (L-005: "mit einem späteren echten Messmikrofon spürbar besser"): lines of
     * "frequency dB" as the makers ship them (comment lines with " or * or letters are skipped), read at the bands'
     * centres (log interpolation); null without a usable file.
     */
    fun calibration(text: String): DoubleArray? {
        val pts = text.lineSequence().mapNotNull { l ->
            val p = l.trim().split(Regex("[\\s,;]+"))
            val f = p.getOrNull(0)?.toDoubleOrNull(); val d = p.getOrNull(1)?.toDoubleOrNull()
            if (f != null && d != null && f > 0) f to d else null
        }.sortedBy { it.first }.toList()
        if (pts.size < 2) return null
        return DoubleArray(CarEq.BANDS.size) { b ->
            val f = CarEq.BANDS[b].toDouble()
            val hi = pts.indexOfFirst { it.first >= f }
            when {
                hi <= 0 -> if (hi == 0) pts[0].second else pts.last().second
                else -> { val (f0, d0) = pts[hi - 1]; val (f1, d1) = pts[hi]
                    d0 + (d1 - d0) * Math.log(f / f0) / Math.log(f1 / f0) }
            }
        }
    }

    /** In-place radix-2 FFT. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci; val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/**
 * One measuring run: [QUIET_S] seconds of listening to the background, then [SWEEPS] sine sweeps to the car while listening on.
 * Takes the audio focus, so music pauses meanwhile; the sound filter stands aside ([CarEq.bypass]).
 */
class CarEqMeasure(private val context: Context) {
    enum class Phase { QUIET, SWEEP, DONE }

    @Volatile private var cancelled = false
    @Volatile private var stopNoise = false
    fun cancel() { cancelled = true }

    @SuppressLint("MissingPermission") // checked by the phone app before starting
    fun run(target: FloatArray, onPhase: (Phase) -> Unit, onDone: (CarEqAnalysis.Result) -> Unit) = Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val am = context.getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).build()
        var record: AudioRecord? = null
        var track: AudioTrack? = null
        try {
            am.requestAudioFocus(focus)
            CarEq.bypass = true
            val src = if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
                MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
            val inMin = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            record = AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(inMin, RATE))
            check(record.state == AudioRecord.STATE_INITIALIZED) { "microphone" }
            // L-005: a measuring microphone on USB, when one is plugged in, instead of the device's own
            am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE || it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET }
                ?.let { d -> record.setPreferredDevice(d); Log.i(TAG, "measuring with the USB microphone ${d.productName}") }
            record.startRecording()
            onPhase(Phase.QUIET)
            val quiet = read(record, QUIET_S)
            track = AudioTrack.Builder().setAudioAttributes(attrs)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(RATE).setTransferMode(AudioTrack.MODE_STREAM).build()
            val t = track
            // System L-005: SWEEPS sine sweeps one after the other (each band clearly on its own, averaged over all),
            // after SETTLE_S of silence for the car to open its stream
            val played = CarEqAnalysis.sweeps(RATE, SWEEP_S, SWEEP_GAP_S, SWEEPS)
            val total = RATE * SETTLE_S + played.size + RATE * TAIL_S
            val player = Thread {
                val chunk = ShortArray(RATE / 50 * 2)
                var n = 0
                t.play()
                while (!cancelled && !stopNoise && n < total) {
                    for (i in 0 until chunk.size / 2) {
                        val at = n + i - RATE * SETTLE_S
                        val v = ((if (at in played.indices) played[at] else 0f) * 32767).toInt().coerceIn(-32768, 32767).toShort()
                        chunk[2 * i] = v; chunk[2 * i + 1] = v
                    }
                    t.write(chunk, 0, chunk.size)
                    n += chunk.size / 2
                }
            }.apply { start() }
            onPhase(Phase.SWEEP)
            read(record, SETTLE_S)
            // the sweeps and a little after them (the car's delay, the cabin's echo)
            val heard = readSamples(record, played.size + RATE * TAIL_S)
            stopNoise = true
            player.join(2000)
            onPhase(Phase.DONE)
            val reference = CarEqAnalysis.bandLevels(played + FloatArray(RATE * TAIL_S), RATE)
            val mic = micFile(context)?.let { runCatching { CarEqAnalysis.calibration(it.readText()) }.getOrNull() }
            if (mic != null) Log.i(TAG, "microphone calibration used: " + mic.joinToString { "%.1f".format(it) })
            // the background over the same time as the sweeps: its levels are per block, as the sweeps'
            val result = CarEqAnalysis.correction(CarEqAnalysis.bandLevels(heard, RATE), CarEqAnalysis.bandLevels(quiet, RATE), target, reference, mic)
            Log.i(TAG, "levels " + result.levels.joinToString { "%.1f".format(it) } + " / noise " + result.noise.joinToString { "%.1f".format(it) } +
                " -> " + (result.gains?.joinToString() ?: result.error))
            onDone(result)
        } catch (e: Exception) {
            Log.w(TAG, "measuring failed", e)
            onDone(CarEqAnalysis.Result(DoubleArray(0), DoubleArray(0), null, e.message ?: "error"))
        } finally {
            runCatching { track?.stop() }; track?.release()
            runCatching { record?.stop() }; record?.release()
            CarEq.bypass = false
            am.abandonAudioFocusRequest(focus)
        }
    }, "mate-eq-measure").apply { start() }

    private fun read(r: AudioRecord, seconds: Int): FloatArray = readSamples(r, RATE * seconds)

    private fun readSamples(r: AudioRecord, count: Int): FloatArray {
        val out = FloatArray(count)
        val buf = ShortArray(RATE / 10)
        var got = 0
        while (got < out.size) {
            if (cancelled) error("cancelled")
            val n = r.read(buf, 0, minOf(buf.size, out.size - got))
            if (n <= 0) error("microphone read $n")
            for (i in 0 until n) out[got + i] = buf[i] / 32768f
            got += n
        }
        return out
    }

    companion object {
        private const val TAG = "MateEqMeasure"
        const val RATE = 48000
        const val QUIET_S = 2
        /** Time for the car to open its stream and the noise to fade in, not measured. */
        const val SETTLE_S = 2
        /** L-005: three sweeps of 5 s, 1 s apart, averaged; 1 s after the last for the car's delay and the cabin's echo. */
        const val SWEEPS = 3
        const val SWEEP_S = 5.0
        const val SWEEP_GAP_S = 1.0
        const val TAIL_S = 1

        /** A measuring microphone's calibration file, if put on the device: files/messmikrofon.txt ("frequency dB"). */
        fun micFile(context: Context) = java.io.File(context.getExternalFilesDir(null), "messmikrofon.txt").takeIf { it.isFile }
    }
}
