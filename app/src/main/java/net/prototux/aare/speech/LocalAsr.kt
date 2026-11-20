package net.prototux.aare.speech

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File

/**
 * Speech recognition on the device (wishes O56/O66): Whisper through sherpa-onnx, no server. The models are not part
 * of the app (tens to hundreds of MB): they sit in the app's own folder on the device, one folder per size -
 * `Android/data/<app>/files/sprache/whisper-<size>/` with `<size>-encoder.int8.onnx`, `<size>-decoder.int8.onnx`
 * and `<size>-tokens.txt`, as the sherpa-onnx model downloads name them.
 */
object LocalAsr {
    val SIZES = listOf("tiny", "base", "small")

    fun dir(context: Context, size: String) = File(context.getExternalFilesDir(null), "sprache/whisper-$size")

    private fun files(context: Context, size: String) = dir(context, size).let {
        Triple(File(it, "$size-encoder.int8.onnx"), File(it, "$size-decoder.int8.onnx"), File(it, "$size-tokens.txt"))
    }

    /** The one model used (base); others left from earlier versions are deleted once it is there. */
    const val STANDARD = "base"

    fun cleanup(context: Context) {
        if (STANDARD !in installed(context)) return
        SIZES.filter { it != STANDARD }.forEach { s -> dir(context, s).takeIf { it.exists() }?.deleteRecursively() }
    }

    /** The model sizes found on the device. */
    fun installed(context: Context): List<String> = SIZES.filter { s -> files(context, s).toList().all { it.isFile } }

    /** Loads a model: takes a while (seconds for the bigger ones), so off the main thread. */
    fun load(context: Context, size: String, threads: Int): OfflineRecognizer {
        val (enc, dec, tok) = files(context, size)
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(encoder = enc.path, decoder = dec.path, language = "de", task = "transcribe"),
                tokens = tok.path, numThreads = threads, modelType = "whisper", provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        )
        return OfflineRecognizer(config = config)
    }

    /** The text in [samples] (mono, -1..1, at [rate] Hz; resampled inside). */
    fun recognize(recognizer: OfflineRecognizer, samples: FloatArray, rate: Int): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, rate)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }
}
