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
    /** Every model Mate knows: the languages' (Language.speech) and the ones of earlier versions, cleaned up. */
    val SIZES: List<String> get() = (net.prototux.aare.Language.ALL_WHISPER + listOf("tiny", "base", "small")).distinct()

    fun dir(context: Context, size: String) = File(net.prototux.aare.Storage.dir(context, net.prototux.aare.Storage.SPEECH), "whisper-$size")

    private fun files(context: Context, size: String) = dir(context, size).let {
        Triple(File(it, "$size-encoder.int8.onnx"), File(it, "$size-decoder.int8.onnx"), File(it, "$size-tokens.txt"))
    }

    /** The device language's standard model (the first offered), to set up first. */
    val STANDARD: String get() = net.prototux.aare.Language.speech().whisper.keys.first()

    /**
     * The model to listen with: the one chosen in the phone app when it is on the device and fits the language, else the
     * language's models in their order, else any on the device; null when there is none.
     */
    fun chosen(context: Context): String? {
        val on = installed(context)
        val offered = net.prototux.aare.Language.speech().whisper.keys
        return net.prototux.aare.Settings(context).asrModel.takeIf { it in on && it in offered }
            ?: offered.firstOrNull { it in on } ?: on.firstOrNull()
    }

    /** Models of no language any more (left from earlier versions) are deleted once a model of the language is there. */
    fun cleanup(context: Context) {
        val keep = net.prototux.aare.Language.ALL_WHISPER
        if (installed(context).none { it in net.prototux.aare.Language.speech().whisper.keys }) return
        SIZES.filter { it !in keep }.forEach { s -> dir(context, s).takeIf { it.exists() }?.deleteRecursively() }
    }

    /** The model sizes found on the device. */
    fun installed(context: Context): List<String> = SIZES.filter { s -> files(context, s).toList().all { it.isFile } }

    /** Loads a model: takes a while (seconds for the bigger ones), so off the main thread. */
    fun load(context: Context, size: String, threads: Int): OfflineRecognizer {
        val (enc, dec, tok) = files(context, size)
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(encoder = enc.path, decoder = dec.path, language = net.prototux.aare.Language.code, task = "transcribe"),
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
