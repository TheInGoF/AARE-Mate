package net.prototux.aare.audio

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * The car's sound in two layers (System L-007, I-011: "Ein Preset überschreibt die Einmessung"):
 *  - below, the **measurement** of the car's speakers (a correction, per car), kept until measured anew; several
 *    measurements are kept and one of them is in use (L-006);
 *  - above, the **preset** for the taste - by the kind of app that plays (L-009, E-006: music, audio book), built-in
 *    ones and the driver's own (L-008).
 * The filter ([CarEq]) gets the sum of both. Choosing or switching off a preset never touches the measurement.
 * Stored in its own preferences ("mate_sound"); the old single curve per car (Settings.eqGains) is taken over once.
 */
object CarSound {
    private const val PREFS = "mate_sound"
    private const val TAG = "MateSound"

    /** The kinds of app a preset follows (E-006); [NAVI]'s prompts stay unfiltered, its other sound counts as music. */
    enum class Kind(val key: String) { MUSIC("music"), BOOK("book"), NAVI("navi");
        companion object { fun of(k: String?) = entries.firstOrNull { it.key == k } }
    }

    /** A preset: [key] "flat", "bass", … or "own:<n>" for the driver's own, its [name] only for own ones. */
    class Preset(val key: String, val gains: FloatArray, val name: String? = null) {
        val own get() = key.startsWith("own:")
    }

    /** Ready-made presets (L-008: "neben Flach, Bass, Höhen und Sprache auch z. B. Jazz, Rock, Pop, Klassik, Hörbuch"). */
    val BUILT_IN: List<Preset> = listOf(
        Preset("flat", FloatArray(10)),
        Preset("bass", floatArrayOf(5f, 4f, 3f, 1f, 0f, 0f, 0f, 0f, 0f, 0f)),
        Preset("treble", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1f, 2f, 3f, 3f)),
        Preset("voice", floatArrayOf(-4f, -3f, -1f, 0f, 1f, 2f, 3f, 2f, 0f, -1f)),
        Preset("jazz", floatArrayOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f)),
        Preset("rock", floatArrayOf(4f, 3f, 2f, 0f, -1f, -1f, 1f, 2f, 3f, 3f)),
        Preset("pop", floatArrayOf(-1f, 1f, 2f, 3f, 2f, 0f, -1f, -1f, 0f, 1f)),
        Preset("classic", floatArrayOf(3f, 2f, 1f, 0f, 0f, 0f, 0f, 1f, 2f, 2f)),
        Preset("book", floatArrayOf(-5f, -4f, -2f, 0f, 1f, 2f, 3f, 2f, -1f, -3f)),
    )

    /** A kept measurement of a car (L-006): when, the correction, the target it was measured for. */
    class Measure(val id: Long, val gains: FloatArray, val target: String, val name: String = "")

    private fun prefs(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun carKey(car: String) = car.ifBlank { "-" }
    private fun parse(s: String?): FloatArray? = s?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }?.takeIf { it.size == 10 }?.toFloatArray()
    private fun text(g: FloatArray) = g.joinToString(",") { if (it % 1f == 0f) it.toInt().toString() else it.toString() }
    private fun json(g: FloatArray) = JSONArray().apply { g.forEach { put(it.toDouble()) } }
    private fun gains(a: JSONArray?) = a?.takeIf { it.length() == 10 }?.let { FloatArray(10) { i -> it.optDouble(i, 0.0).toFloat() } }

    // ---------------------------------------------------------------- measurement (below)

    /** The measurements kept for [car], newest first. */
    fun measures(c: Context, car: String): List<Measure> {
        migrate(c, car)
        val a = runCatching { JSONArray(prefs(c).getString("measures:${carKey(car)}", "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o ->
            gains(o.optJSONArray("g"))?.let { Measure(o.optLong("id"), it, o.optString("target", "neutral"), o.optString("name")) } } }
            .sortedByDescending { it.id }
    }

    private fun saveMeasures(c: Context, car: String, list: List<Measure>) {
        val a = JSONArray()
        list.forEach { m -> a.put(JSONObject().put("id", m.id).put("g", json(m.gains)).put("target", m.target).put("name", m.name)) }
        prefs(c).edit().putString("measures:${carKey(car)}", a.toString()).apply()
    }

    /** Keeps a new measurement (at most [KEEP_MEASURES]) and uses it at once. */
    fun addMeasure(c: Context, car: String, gains: FloatArray, target: String): Measure {
        val m = Measure(System.currentTimeMillis(), gains.copyOf(), target)
        saveMeasures(c, car, (listOf(m) + measures(c, car)).take(KEEP_MEASURES))
        useMeasure(c, car, m.id)
        return m
    }

    fun renameMeasure(c: Context, car: String, id: Long, name: String) =
        saveMeasures(c, car, measures(c, car).map { if (it.id == id) Measure(it.id, it.gains, it.target, name.trim()) else it })

    fun deleteMeasure(c: Context, car: String, id: Long) {
        saveMeasures(c, car, measures(c, car).filter { it.id != id })
        if (activeMeasure(c, car) == id) useMeasure(c, car, 0)
    }

    /** The measurement in use ([id] 0: none - the car's speakers uncorrected). */
    fun useMeasure(c: Context, car: String, id: Long) {
        prefs(c).edit().putLong("measure_on:${carKey(car)}", id).apply()
        apply(c, car)
    }

    fun activeMeasure(c: Context, car: String): Long { migrate(c, car); return prefs(c).getLong("measure_on:${carKey(car)}", 0) }

    /** The correction in use for [car], zeros without one. */
    fun correction(c: Context, car: String): FloatArray {
        val id = activeMeasure(c, car)
        return measures(c, car).firstOrNull { it.id == id }?.gains ?: FloatArray(10)
    }

    // ---------------------------------------------------------------- presets (above)

    fun ownPresets(c: Context): List<Preset> {
        val a = runCatching { JSONArray(prefs(c).getString("own_presets", "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o ->
            gains(o.optJSONArray("g"))?.let { Preset(o.optString("key"), it, o.optString("name")) } } }
    }

    fun presets(c: Context): List<Preset> = BUILT_IN + ownPresets(c)
    fun preset(c: Context, key: String?): Preset = presets(c).firstOrNull { it.key == key } ?: BUILT_IN.first()

    /** Saves [gains] as the driver's own preset [name] (one of that name is replaced); its key. */
    fun saveOwn(c: Context, name: String, gains: FloatArray): String {
        val list = ownPresets(c)
        val same = list.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        val key = same?.key ?: "own:" + System.currentTimeMillis()
        val all = list.filter { it.key != key } + Preset(key, gains.copyOf(), name.trim())
        val a = JSONArray(); all.forEach { p -> a.put(JSONObject().put("key", p.key).put("name", p.name).put("g", json(p.gains))) }
        prefs(c).edit().putString("own_presets", a.toString()).apply()
        return key
    }

    fun deleteOwn(c: Context, key: String) {
        val a = JSONArray(); ownPresets(c).filter { it.key != key }.forEach { p -> a.put(JSONObject().put("key", p.key).put("name", p.name).put("g", json(p.gains))) }
        prefs(c).edit().putString("own_presets", a.toString()).apply()
        Kind.entries.filter { presetKey(c, it) == key }.forEach { setPreset(c, it, "flat") }
    }

    /** The preset for a kind of app (L-009): music flat, audio books the audio book preset unless chosen otherwise. */
    fun presetKey(c: Context, kind: Kind): String = prefs(c).getString("preset:${kind.key}", null) ?: when (kind) {
        Kind.BOOK -> "book"
        else -> prefs(c).getString("legacy_preset", null) ?: "flat"
    }

    fun setPreset(c: Context, kind: Kind, key: String) { prefs(c).edit().putString("preset:${kind.key}", key).apply(); apply(c, lastCar) }

    // ---------------------------------------------------------------- kinds of app

    private val BOOK_APPS = Regex("audible|audiobook|audiobookshelf|hoerbuch|podcast|podimo|pocketcast|antennapod|castbox|storytel|bookbeat|librivox|nextory|bookmate", RegexOption.IGNORE_CASE)

    /** The kind Mate sees in [pkg]: an audio book or podcast app by its name, a map app by its category, else music. */
    fun autoKind(c: Context, pkg: String): Kind {
        if (BOOK_APPS.containsMatchIn(pkg)) return Kind.BOOK
        val pm = c.packageManager
        val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
        if (info?.category == android.content.pm.ApplicationInfo.CATEGORY_MAPS) return Kind.NAVI
        val label = info?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }.orEmpty()
        if (BOOK_APPS.containsMatchIn(label) || label.contains("hörbuch", ignoreCase = true)) return Kind.BOOK
        return Kind.MUSIC
    }

    /** The kind of [pkg]: the driver's choice for it (E-006: "jede App lässt sich von Hand einer anderen Art zuweisen"), else [autoKind]. */
    fun kind(c: Context, pkg: String): Kind = Kind.of(prefs(c).getString("kind:$pkg", null)) ?: autoKind(c, pkg)
    fun setKind(c: Context, pkg: String, kind: Kind?) {
        prefs(c).edit().apply { if (kind == null) remove("kind:$pkg") else putString("kind:$pkg", kind.key) }.apply()
        if (pkg == playing) apply(c, lastCar)
    }

    // ---------------------------------------------------------------- the filter

    /** The app whose sound plays now (from the media hub), for the preset of its kind. */
    @Volatile var playing: String? = null
        private set
    @Volatile private var lastCar = ""

    /** The media hub's "now playing" moved to [pkg]: its kind's preset, if that is another. */
    fun follow(c: Context, pkg: String?) {
        if (pkg == null || pkg == playing) return
        playing = pkg
        apply(c, lastCar)
    }

    /** Sets [CarEq] for [car]: the filter switch (Settings.eqOn) and correction + preset of what plays. */
    fun apply(c: Context, car: String) {
        lastCar = car
        val on = net.prototux.aare.Settings(c).eqOn(car)
        val kind = playing?.let { kind(c, it) } ?: Kind.MUSIC
        val sum = combined(correction(c, car), preset(c, presetKey(c, kind)).gains)
        CarEq.set(on, sum)
        android.util.Log.i(TAG, "sound for ${car.ifBlank { "any car" }}: ${if (on) "on" else "off"}, ${kind.key} preset ${presetKey(c, kind)}, measurement ${activeMeasure(c, car)}")
    }

    /** The two layers added, band by band, within the filter's range. */
    fun combined(correction: FloatArray, preset: FloatArray): FloatArray =
        FloatArray(10) { i -> (correction.getOrElse(i) { 0f } + preset.getOrElse(i) { 0f }).coerceIn(CarEq.MIN_DB.toFloat(), CarEq.MAX_DB.toFloat()) }

    /**
     * The old single curve per car (before the two layers): equal to a preset it was that preset, otherwise it was a
     * measurement (or set by hand) and is kept as one. Once per car.
     */
    private fun migrate(c: Context, car: String) {
        val p = prefs(c)
        val k = carKey(car)
        if (p.getBoolean("migrated:$k", false)) return
        p.edit().putBoolean("migrated:$k", true).apply()
        val old = net.prototux.aare.Settings(c).eqGains(car)
        if (old.all { it == 0f }) return
        val preset = BUILT_IN.firstOrNull { it.gains.contentEquals(old) }
        if (preset != null) { if (!p.contains("legacy_preset")) p.edit().putString("legacy_preset", preset.key).apply(); return }
        val m = Measure(System.currentTimeMillis(), old.copyOf(), p.getString("target", "neutral") ?: "neutral")
        val a = JSONArray().put(JSONObject().put("id", m.id).put("g", json(m.gains)).put("target", m.target).put("name", ""))
        p.edit().putString("measures:$k", a.toString()).putLong("measure_on:$k", m.id).apply()
        android.util.Log.i(TAG, "old curve of ${car.ifBlank { "any car" }} kept as a measurement: ${text(old)}")
    }

    const val KEEP_MEASURES = 10
}
