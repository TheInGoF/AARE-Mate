package net.prototux.aare.speech

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Loads a Whisper model into the app's folder (O56): the three files of the sherpa-onnx conversion, straight
 * from its public repository - no archive to unpack. Progress per size for the phone app; a file is written
 * under a temporary name first, so a broken download never looks like a model.
 */
object ModelDownload {
    /**
     * Sizes offered, with their download size in MB: base only (18.11.2026: "das große Whisper-Modell mit 160 MB funktioniert
     * ganz gut, das nehmen wir als Standard, das kleine kann man rausnehmen"); small is too slow on the S6 Lite.
     */
    val OFFERED: Map<String, Int> get() = net.prototux.aare.Language.speech().whisper

    /** size -> progress 0..1 while loading, -1 after a failure. */
    val progress = mutableStateMapOf<String, Float>()

    private fun url(size: String, file: String) =
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-$size/resolve/main/$file"

    fun start(context: Context, size: String) {
        if (progress[size]?.let { it in 0f..0.999f } == true) return
        progress[size] = 0f
        val app = context.applicationContext
        Thread {
            val dir = LocalAsr.dir(app, size).apply { mkdirs() }
            val files = listOf("$size-encoder.int8.onnx", "$size-decoder.int8.onnx", "$size-tokens.txt")
            runCatching {
                val lengths = files.map { f -> head(url(size, f)) }
                val total = lengths.sum().coerceAtLeast(1L)
                var done = 0L
                for (f in files) {
                    val target = File(dir, f)
                    val part = File(dir, "$f.part")
                    val c = URL(url(size, f)).openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = true
                    c.connectTimeout = 15_000; c.readTimeout = 30_000
                    c.inputStream.use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                progress[size] = (done.toFloat() / total).coerceAtMost(0.999f)
                            }
                        }
                    }
                    check(part.renameTo(target)) { "rename $f" }
                }
                progress[size] = 1f
            }.onFailure {
                android.util.Log.w("MateAsr", "model $size download failed", it)
                progress[size] = -1f
            }
        }.start()
    }

    /** Key of the Piper voice in [progress]; the Kokoro voices go by their id. */
    const val VOICE = "piper"

    /**
     * Loads a Kokoro voice (O224): its three files from its repository, and the pronunciation rules (espeak-ng-data,
     * from the Piper voice's repository) unless the device has them already.
     */
    fun startKokoro(context: Context, v: Voices.Voice) {
        val repo = v.repo ?: return
        if (progress[v.id]?.let { it in 0f..0.999f } == true) return
        progress[v.id] = 0f
        val app = context.applicationContext
        Thread {
            runCatching {
                val jobs = ArrayList<Triple<String, String, File>>()   // repo, path, target
                val d = Voices.dir(app, v)
                // a Piper voice has no voice pack, only model and tokens (its pronunciation rules are shared)
                (if (v.kind == Voices.Kind.PIPER) listOf(v.model, v.tokens) else listOf(v.model, v.voices, v.tokens)).forEach { jobs += Triple(repo, it, File(d, it)) }
                if (!Voices.espeak(app).isDirectory) {
                    val list = org.json.JSONArray(URL("https://huggingface.co/api/models/$VOICE_REPO/tree/main?recursive=true").readText())
                    (0 until list.length()).map { list.getJSONObject(it) }.filter { it.optString("type") == "file" && it.getString("path").startsWith("espeak-ng-data/") }
                        .forEach { jobs += Triple(VOICE_REPO, it.getString("path"), File(Voices.espeak(app).parentFile, it.getString("path"))) }
                }
                val total = jobs.sumOf { (r, p, _) -> head("https://huggingface.co/$r/resolve/main/$p") }.coerceAtLeast(1L)
                var done = 0L
                for ((r, path, target) in jobs) {
                    target.parentFile?.mkdirs()
                    val part = File(target.path + ".part")
                    val c = URL("https://huggingface.co/$r/resolve/main/$path").openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = true; c.connectTimeout = 15_000; c.readTimeout = 30_000
                    c.inputStream.use { input -> part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n
                            progress[v.id] = (done.toFloat() / total).coerceAtMost(0.999f) }
                    } }
                    check(part.renameTo(target)) { "rename $path" }
                }
                progress[v.id] = 1f
            }.onFailure {
                android.util.Log.w("MateTts", "voice ${v.id} download failed", it)
                progress[v.id] = -1f
            }
        }.start()
    }
    private const val VOICE_REPO = "csukuangfj/vits-piper-de_DE-thorsten-medium"

    /**
     * Loads the Piper voice Thorsten (O110): model, tokens and the espeak-ng data (pronunciation rules), the files
     * listed by the repository itself - some 360 files, 77 MB.
     */
    fun startVoice(context: Context) {
        if (progress[VOICE]?.let { it in 0f..0.999f } == true) return
        progress[VOICE] = 0f
        val app = context.applicationContext
        Thread {
            runCatching {
                val list = org.json.JSONArray(URL("https://huggingface.co/api/models/$VOICE_REPO/tree/main?recursive=true").readText())
                val files = (0 until list.length()).map { list.getJSONObject(it) }
                    .filter { it.optString("type") == "file" }
                    .map { it.getString("path") to it.optLong("size") }
                    .filter { (p, _) -> p.endsWith(".onnx") || p == "tokens.txt" || p.startsWith("espeak-ng-data/") }
                val total = files.sumOf { it.second }.coerceAtLeast(1L)
                val dir = LocalTts.dir(app)
                var done = 0L
                for ((path, _) in files) {
                    val target = File(dir, path).apply { parentFile?.mkdirs() }
                    val part = File(target.path + ".part")
                    val c = URL("https://huggingface.co/$VOICE_REPO/resolve/main/$path").openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = true; c.connectTimeout = 15_000; c.readTimeout = 30_000
                    c.inputStream.use { input -> part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n
                            progress[VOICE] = (done.toFloat() / total).coerceAtMost(0.999f) }
                    } }
                    check(part.renameTo(target)) { "rename $path" }
                }
                progress[VOICE] = 1f
            }.onFailure {
                android.util.Log.w("MateTts", "voice download failed", it)
                progress[VOICE] = -1f
            }
        }.start()
    }

    private fun head(u: String): Long = runCatching {
        val c = URL(u).openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"; c.instanceFollowRedirects = true
        c.connectTimeout = 15_000
        c.contentLengthLong.also { c.disconnect() }
    }.getOrDefault(0L).coerceAtLeast(0L)
}
