package net.prototux.aare.speech

import android.content.Context
import java.io.File

/**
 * The voices Mate reads with on the device (O110, O224, 17.11.2026: "es gibt deutsche Kokoro-Stimmen ... diese einbauen"):
 * Thorsten as Piper (VITS) and the German Kokoro voices, all through sherpa-onnx. A Kokoro voice is three files in
 * `files/sprache/kokoro-<id>/` (model, voicepack, tokens); the pronunciation rules (espeak-ng-data) are shared with the
 * Piper voice. [repo] null: not offered for download (yet) - present only when its files are on the device.
 */
object Voices {
    class Voice(val id: String, val name: String, val female: Boolean, val kind: Kind, val repo: String?, val model: String,
                val voices: String = "voices.bin", val tokens: String = "tokens.txt", val sizeMb: Int)
    enum class Kind { PIPER, KOKORO }

    val ALL = listOf(
        Voice("piper", "Thorsten (Piper)", false, Kind.PIPER, "csukuangfj/vits-piper-de_DE-thorsten-medium", "de_DE-thorsten-medium.onnx", sizeMb = 77),
        // Victoria and Martin (Kokoro) left out (17.11.2026: "Victoria Kokoro kann raus. Martin auch. Die haben alle Sprachfehler.
        // Sprichscht.")
        // converted at the build PC from their published weights (Apache-2.0), put on the device for now
        Voice("kokoro-thorsten", "Thorsten (Kokoro)", false, Kind.KOKORO, null, "model.int8.onnx", sizeMb = 115),
        Voice("kokoro-kerstin", "Kerstin", true, Kind.KOKORO, null, "model.int8.onnx", sizeMb = 115),
        // more German Piper voices, public and downloadable in the app (17.11.2026: "Kann man die Modelle nicht in der App
        // verlinken? Zumindest die Piper Stimme. Irgendwas, was besser ist als die Original-Stimmen") - fast like Thorsten
        Voice("piper-thorsten-high", "Thorsten hoch (Piper)", false, Kind.PIPER, "csukuangfj/vits-piper-de_DE-thorsten-high", "de_DE-thorsten-high.onnx", sizeMb = 115),
        Voice("piper-eva", "Eva (Piper)", true, Kind.PIPER, "csukuangfj/vits-piper-de_DE-eva_k-x_low", "de_DE-eva_k-x_low.onnx", sizeMb = 21),
        Voice("piper-kerstin", "Kerstin (Piper)", true, Kind.PIPER, "csukuangfj/vits-piper-de_DE-kerstin-low", "de_DE-kerstin-low.onnx", sizeMb = 64),
        Voice("piper-ramona", "Ramona (Piper)", true, Kind.PIPER, "csukuangfj/vits-piper-de_DE-ramona-low", "de_DE-ramona-low.onnx", sizeMb = 64),
        Voice("piper-karlsson", "Karlsson (Piper)", false, Kind.PIPER, "csukuangfj/vits-piper-de_DE-karlsson-low", "de_DE-karlsson-low.onnx", sizeMb = 64),
    )

    fun byId(id: String?) = ALL.firstOrNull { it.id == id }

    fun dir(context: Context, v: Voice): File =
        if (v.id == "piper") LocalTts.dir(context) else File(context.getExternalFilesDir(null), "sprache/${v.id}")

    /** The pronunciation rules: the Piper voice's, or loaded on their own. */
    fun espeak(context: Context): File = File(LocalTts.dir(context), "espeak-ng-data").takeIf { it.isDirectory }
        ?: File(context.getExternalFilesDir(null), "sprache/espeak-ng-data")

    fun available(context: Context, v: Voice): Boolean {
        val d = dir(context, v)
        return File(d, v.model).isFile && File(d, v.tokens).isFile && espeak(context).isDirectory &&
            (v.kind == Kind.PIPER || File(d, v.voices).isFile)
    }

    /**
     * Frees a voice's space (17.11.2026: Kokoro "nur noch auf Bedarf"): its files go; the pronunciation rules stay (they
     * lie in Thorsten's folder and are shared by all voices).
     */
    fun delete(context: Context, v: Voice) {
        val d = dir(context, v)
        if (v.id == "piper") listOf(v.model, v.tokens).forEach { File(d, it).delete() }
        else d.listFiles().orEmpty().filter { it.name != "espeak-ng-data" }.forEach { it.deleteRecursively() }
        if (net.prototux.aare.Settings(context).ttsEngine == v.id) net.prototux.aare.Settings(context).ttsEngine = "system"
    }

    /** Offered in the phone app: downloadable, or already on the device. */
    fun offered(context: Context) = ALL.filter { it.repo != null || available(context, it) }
}
