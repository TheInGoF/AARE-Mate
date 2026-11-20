package net.prototux.aare.ui.car

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saved views (wish O6): what the car screen starts with, under a name - the area in front and the apps that fill
 * the navigation and the music area. Made and chosen in the phone app; the car screen reads the chosen one when a
 * car connects. Without a chosen view the car screen starts as it always did, with the dashboard.
 */
class MateViews(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val choice = context.getSharedPreferences("idmate_choice", Context.MODE_PRIVATE)

    /**
     * [naviRight]/[share]: for a split start, the navigation's side and its share of the width (18.11.2026: "festlegen,
     * welche App links, rechts und wie das Teilungsverhältnis ist").
     */
    data class View(val name: String, val start: String, val navi: String?, val music: String?,
                    val naviRight: Boolean = true, val share: Float = 0.5f)

    val all: List<View>
        get() = runCatching {
            val a = JSONArray(prefs.getString("views", "[]"))
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                View(o.getString("name"), o.optString("start", START_DASHBOARD),
                    o.optString("navi").ifBlank { null }, o.optString("music").ifBlank { null },
                    o.optBoolean("naviRight", true), o.optDouble("share", 0.5).toFloat())
            }
        }.getOrDefault(emptyList())

    /** The view the car screen starts with, or null for the plain start with the dashboard. */
    val active: View? get() = prefs.getString("active", null)?.let { n -> all.firstOrNull { it.name == n } }

    private fun store(list: List<View>) = prefs.edit().putString("views", JSONArray(list.map {
        JSONObject().put("name", it.name).put("start", it.start).put("navi", it.navi ?: "").put("music", it.music ?: "")
            .put("naviRight", it.naviRight).put("share", it.share.toDouble())
    }).toString()).apply()

    /** Saves [v] (replacing one of the same name) and makes it the active view. */
    fun save(v: View) {
        store(all.filter { it.name != v.name } + v)
        activate(v.name)
    }

    fun delete(name: String) {
        store(all.filter { it.name != name })
        if (prefs.getString("active", null) == name) prefs.edit().remove("active").apply()
    }

    /** Chooses the view to start with (null: the plain start); its apps become the areas' default apps at once. */
    fun activate(name: String?) {
        prefs.edit().putString("active", name).apply()
        val v = all.firstOrNull { it.name == name } ?: return
        apply(v)
    }

    /** [v]'s apps become the areas' apps, and a split view's side and share the split's (also when called from the car). */
    fun apply(v: View) {
        choice.edit().apply {
            v.navi?.let { putString("navi", it) }
            v.music?.let { putString("music", it) }
        }.apply()
        if (v.start == START_SPLIT) split.edit().putFloat("navi_share", v.share).putBoolean("navi_right", v.naviRight).apply()
    }

    private val split = context.getSharedPreferences("mate_split", Context.MODE_PRIVATE)

    companion object {
        const val PREFS = "mate_views"
        const val START_DASHBOARD = "dashboard"
        const val START_NAVI = "navi"
        const val START_MUSIC = "music"
        const val START_MESSAGES = "messages"
        /** Navigation beside what is playing. */
        const val START_SPLIT = "split"
        val STARTS = listOf(START_DASHBOARD, START_NAVI, START_MUSIC, START_MESSAGES, START_SPLIT)
    }
}
