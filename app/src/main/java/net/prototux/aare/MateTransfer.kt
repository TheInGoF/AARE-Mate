package net.prototux.aare

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Mate's settings into one file and back (17.11.2026: "Kannst du die alten App-Einstellungen portieren?" - the first
 * release is signed with a new key, so Mate is installed afresh and its data would be gone). A ZIP with the app's
 * preferences as JSON (each value with its type), the files that belong to the driver (places, the car's certificates)
 * and the secrets kept in the Keystore - in plain text, because the key belongs to the old installation: the phone app
 * says so and asks to delete the file after the import. Caches, logs and offline region packages stay out.
 */
internal object MateTransfer {
    /** Preferences not taken: the web view's and the map library's own, the day's TomTom count, a trip in progress. */
    private val SKIP_PREFS = setOf("AwOriginVisitLoggerPrefs", "MapboxSharedPreferences", "WebViewChromiumPrefs", "tomtom_budget", "navi_resume", "storage")
    /** Files and folders taken from the app's files directory. */
    private val FILES = listOf("places.json", "certs")
    /** Secrets of TokenVault, by name. */
    private val SECRETS = listOf("map_token")
    /** The offline region packages (optional, large). */
    private const val MAPS = "regionen"

    /** Voices on the device that are not offered for download (their files exist only here). */
    private fun ownVoices(c: Context) = net.prototux.aare.speech.Voices.ALL.filter { it.repo == null && net.prototux.aare.speech.Voices.available(c, it) }
        .map { it to net.prototux.aare.speech.Voices.dir(c, it) }

    /** Bytes the offline maps take now. */
    fun mapsBytes(context: Context): Long = net.prototux.aare.Storage.dir(context, MAPS).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** [withMaps]: the offline region packages too (files/regionen, gigabytes - stored, not compressed); [progress] in bytes. */
    /** Bytes the own voices (Kokoro, not downloadable) take now. */
    fun voicesBytes(context: Context): Long = ownVoices(context).sumOf { (_, d) -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    /**
     * [withVoices]: the own Kokoro voices too (17.11.2026: "alle Kokoro-Stimmen standardmäßig raus ... spart Platz und
     * für das Tablet hier leider nicht zu gebrauchen") - off unless ticked.
     */
    fun export(context: Context, to: Uri, withMaps: Boolean = false, progress: (Long) -> Unit = {}, withVoices: Boolean = false): Int {
        val app = context.applicationContext
        var count = 0
        app.contentResolver.openOutputStream(to)!!.use { os -> ZipOutputStream(os).use { zip ->
            val prefsDir = File(app.applicationInfo.dataDir, "shared_prefs")
            val prefs = JSONObject()
            prefsDir.listFiles().orEmpty().filter { it.name.endsWith(".xml") }.map { it.name.removeSuffix(".xml") }
                .filter { it !in SKIP_PREFS }.forEach { name ->
                    val o = JSONObject()
                    app.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) ->
                        // the encrypted secrets are carried in plain text below
                        if (k.endsWith("_enc")) return@forEach
                        o.put(k, when (v) {
                            is Boolean -> JSONObject().put("t", "b").put("v", v)
                            is Int -> JSONObject().put("t", "i").put("v", v)
                            is Long -> JSONObject().put("t", "l").put("v", v)
                            is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                            is String -> JSONObject().put("t", "s").put("v", v)
                            is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                            else -> return@forEach
                        }); count++
                    }
                    prefs.put(name, o)
                }
            zip.putNextEntry(ZipEntry("prefs.json")); zip.write(prefs.toString(1).toByteArray()); zip.closeEntry()
            // the voices that cannot be downloaded again (converted at the build PC): only when wanted
            val own = if (withVoices) ownVoices(app) else emptyList()
            own.forEach { (v, dir) -> dir.walkTopDown().filter { it.isFile }.forEach { file ->
                zip.setLevel(0)
                zip.putNextEntry(ZipEntry("voices/${v.id}/" + file.relativeTo(dir).path)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry(); count++
            } }
            // and their pronunciation rules: they lay in the Piper voice's folder and were lost with a new install
            // (17.11.2026 - the voices came over, but none could speak)
            if (own.isNotEmpty()) net.prototux.aare.speech.Voices.espeak(app).takeIf { it.isDirectory }?.let { es ->
                es.walkTopDown().filter { it.isFile }.forEach { file ->
                    zip.setLevel(0)
                    zip.putNextEntry(ZipEntry("voices/espeak-ng-data/" + file.relativeTo(es).path)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry(); count++
                }
            }
            zip.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION)
            val secrets = JSONObject()
            SECRETS.forEach { n -> TokenVault(app, n).read().takeIf { it.isNotEmpty() }?.let { secrets.put(n, it); count++ } }
            zip.putNextEntry(ZipEntry("secrets.json")); zip.write(secrets.toString().toByteArray()); zip.closeEntry()
            val files = app.filesDir
            var done = 0L
            // the maps may lie on a memory card (Storage, L-080): in the file always as files/regionen/…
            val maps = net.prototux.aare.Storage.dir(app, MAPS)
            (FILES.map { File(files, it) } + if (withMaps) listOf(maps) else emptyList()).filter { it.exists() }.forEach { f ->
                f.walkTopDown().filter { it.isFile }.forEach { file ->
                    val isMap = f == maps
                    // the map packages are compressed already: stored as they are (level 0), much faster
                    zip.setLevel(if (isMap) 0 else java.util.zip.Deflater.DEFAULT_COMPRESSION)
                    zip.putNextEntry(ZipEntry("files/" + if (isMap) "$MAPS/" + file.relativeTo(maps).path else file.relativeTo(files).path))
                    file.inputStream().use { inp -> val buf = ByteArray(1 shl 16); while (true) { val n = inp.read(buf); if (n < 0) break; zip.write(buf, 0, n); done += n; progress(done) } }
                    zip.closeEntry(); count++
                }
            }
        } }
        return count
    }

    /** Takes a file of [export] in: preferences set value by value, secrets into the Keystore, files written. */
    /**
     * [progress]: the bytes taken in so far over the whole file (17.11.2026: "er läuft mehrfach hoch von 0 bis ein paar
     * hundert MB" - it counted each map package from 0 again); [item]: what is restored now, e.g. "Karte sachsen.pmtiles",
     * "Stimme Kerstin" - "Wäre gut zu sehen, was er gerade entpackt".
     */
    fun import(context: Context, from: Uri, progress: (Long) -> Unit = {}, item: (String) -> Unit = {}): Int {
        val app = context.applicationContext
        var count = 0
        var done = 0L
        var lastItem = ""
        fun tell(what: String) { if (what != lastItem) { lastItem = what; item(what) } }
        fun copy(zip: ZipInputStream, o: java.io.OutputStream) { val buf = ByteArray(1 shl 16); while (true) { val n = zip.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n; progress(done) } }
        app.contentResolver.openInputStream(from)!!.use { input -> ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                tell(when {
                    e.name.startsWith("files/$MAPS/") -> "Karte " + e.name.substringAfterLast('/')
                    e.name.startsWith("voices/espeak-ng-data") -> "Aussprache-Regeln"
                    e.name.startsWith("voices/") -> "Stimme " + (net.prototux.aare.speech.Voices.byId(e.name.removePrefix("voices/").substringBefore('/'))?.name ?: e.name.removePrefix("voices/").substringBefore('/'))
                    e.name == "prefs.json" -> "Einstellungen"
                    else -> e.name.substringAfterLast('/')
                })
                // map packages go straight to their file (too large for memory)
                if (e.name.startsWith("files/$MAPS/") && !e.isDirectory && !e.name.contains("..")) {
                    File(net.prototux.aare.Storage.dir(app, MAPS), e.name.removePrefix("files/$MAPS/")).apply { parentFile?.mkdirs() }.outputStream().use { copy(zip, it) }
                    count++; continue
                }
                if (e.name.startsWith("voices/") && !e.isDirectory && !e.name.contains("..")) {
                    val rest = e.name.removePrefix("voices/"); val id = rest.substringBefore('/')
                    if (id == "espeak-ng-data") {
                        File(File(net.prototux.aare.Storage.dir(app, net.prototux.aare.Storage.SPEECH), "espeak-ng-data"), rest.substringAfter('/')).apply { parentFile?.mkdirs() }.outputStream().use { copy(zip, it) }
                        count++; continue
                    }
                    net.prototux.aare.speech.Voices.byId(id)?.let { v ->
                        File(net.prototux.aare.speech.Voices.dir(app, v), rest.substringAfter('/')).apply { parentFile?.mkdirs() }.outputStream().use { copy(zip, it) }
                        count++
                    }
                    continue
                }
                val bytes = zip.readBytes()
                when {
                    e.name == "prefs.json" -> {
                        val all = JSONObject(String(bytes))
                        all.keys().forEach { name ->
                            if (name in SKIP_PREFS) return@forEach
                            val o = all.getJSONObject(name)
                            val ed = app.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
                            o.keys().forEach { k ->
                                val v = o.getJSONObject(k)
                                when (v.getString("t")) {
                                    "b" -> ed.putBoolean(k, v.getBoolean("v"))
                                    "i" -> ed.putInt(k, v.getInt("v"))
                                    "l" -> ed.putLong(k, v.getLong("v"))
                                    "f" -> ed.putFloat(k, v.getDouble("v").toFloat())
                                    "s" -> ed.putString(k, v.getString("v"))
                                    "set" -> ed.putStringSet(k, v.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                                }
                                count++
                            }
                            ed.commit()
                        }
                    }
                    e.name == "secrets.json" -> JSONObject(String(bytes)).let { s ->
                        s.keys().forEach { n -> if (n in SECRETS) { TokenVault(app, n).write(s.getString(n)); count++ } }
                    }
                    e.name.startsWith("files/") && !e.isDirectory -> {
                        val rel = e.name.removePrefix("files/")
                        // only below the files directory and only what is exported
                        if (rel.contains("..") || FILES.none { rel == it || rel.startsWith("$it/") }) continue
                        File(app.filesDir, rel).apply { parentFile?.mkdirs() }.writeBytes(bytes); count++
                    }
                }
            }
        } }
        return count
    }
}
