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
                val voices: String = "voices.bin", val tokens: String = "tokens.txt", val sizeMb: Int, val lang: String = "de")
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
        // German in high quality besides Thorsten (21.11.2026: "ob es eine weitere Piper hoch Stimme für deutsch gibt.
        // Aktuell Eva ist sehr sparsam in der Aussprache"): dii (a woman) and miro (a man), OpenVoiceOS, CC BY-NC-SA 4.0
        Voice("piper-dii-high", "Dii hoch (Piper)", true, Kind.PIPER, "csukuangfj/vits-piper-de_DE-dii-high", "de_DE-dii-high.onnx", sizeMb = 63),
        Voice("piper-miro-high", "Miro hoch (Piper)", false, Kind.PIPER, "csukuangfj/vits-piper-de_DE-miro-high", "de_DE-miro-high.onnx", sizeMb = 63),
        // English (the language library, 21.11.2026: "Zu den Sprachen gehört auch Alternativen whisper und Piper anzubieten"):
        // American and British, women and men, all medium quality
        Voice("piper-en-lessac", "Lessac (Piper, US)", true, Kind.PIPER, "csukuangfj/vits-piper-en_US-lessac-medium", "en_US-lessac-medium.onnx", sizeMb = 63, lang = "en"),
        Voice("piper-en-ryan", "Ryan (Piper, US)", false, Kind.PIPER, "csukuangfj/vits-piper-en_US-ryan-medium", "en_US-ryan-medium.onnx", sizeMb = 63, lang = "en"),
        Voice("piper-en-hfc-female", "HFC (Piper, US)", true, Kind.PIPER, "csukuangfj/vits-piper-en_US-hfc_female-medium", "en_US-hfc_female-medium.onnx", sizeMb = 63, lang = "en"),
        Voice("piper-en-alba", "Alba (Piper, GB)", true, Kind.PIPER, "csukuangfj/vits-piper-en_GB-alba-medium", "en_GB-alba-medium.onnx", sizeMb = 63, lang = "en"),
        Voice("piper-en-northern", "Northern English (Piper, GB)", false, Kind.PIPER, "csukuangfj/vits-piper-en_GB-northern_english_male-medium", "en_GB-northern_english_male-medium.onnx", sizeMb = 63, lang = "en"),
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

    /** Offered in the phone app: the device language's voices, downloadable or already on the device. */
    fun offered(context: Context, lang: String = net.prototux.aare.Language.code) =
        ALL.filter { it.lang == lang && (it.repo != null || available(context, it)) }

    /** The voice set up first for [lang] (Language.speech). */
    fun standard(lang: String = net.prototux.aare.Language.code): Voice = byId(net.prototux.aare.Language.speech(lang).voice)!!
}
