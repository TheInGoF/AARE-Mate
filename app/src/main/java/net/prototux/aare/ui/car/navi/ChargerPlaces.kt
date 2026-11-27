package net.prototux.aare.ui.car.navi

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject
import org.maplibre.spatialk.geojson.Position

/**
 * Where a charger stands, in words (Navi L-051, 27.11.2026: "Tesla Supercharger und klein darunter den Ort, wo das sein
 * soll … damit man einordnen kann, was er da gemacht hat, ohne in der Karte suchen zu müssen"): the town, and the motorway
 * or the service area where it is one - e.g. "Hermsdorf · A 9". Asked once per charger of the map server's search
 * (Photon's reverse) and kept on the device; chargers do not move. Without the server or an answer: no line.
 */
internal object ChargerPlaces {
    private const val PREFS = "charger_places"
    private const val MAX = 600
    /** What is known, read by the screens (a change shows at once). */
    private val known = mutableStateMapOf<String, String>()
    private val asked = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile private var loaded = false

    private fun key(p: Position) = "%.5f,%.5f".format(java.util.Locale.ROOT, p.latitude, p.longitude)

    /** The words for [ch] if known (read inside a composable: shown as soon as they come). */
    fun of(ch: Charger): String? = known[key(ch.at)]

    /** Looks up [ch] once (blocking network: call off the main thread); kept for later. */
    suspend fun load(context: Context, ch: Charger) {
        val app = context.applicationContext
        if (!loaded) readStored(app)
        val k = key(ch.at)
        if (known.containsKey(k) || !asked.add(k)) return
        val line = NaviSearch.reverse(app, ch.at)?.let(::line) ?: run { asked.remove(k); return }
        known[k] = line
        store(app, k, line)
    }

    /**
     * The line from Photon's properties of the place nearest the charger: the town (city, else locality, district, county),
     * then a motorway ("A 9") or a service area by name where the place is one; null without a town.
     */
    fun line(p: JSONObject): String? {
        fun s(k: String) = p.optString(k).takeIf { it.isNotBlank() }
        val town = s("city") ?: s("locality") ?: s("district") ?: s("county") ?: return null
        val name = s("name")
        val area = name?.takeIf { SERVICE.containsMatchIn(it) }
        // "A9" and "A 9" alike, written as signs show them
        val road = s("street")?.let { MOTORWAY.matchEntire(it) }?.let { "${it.groupValues[1]} ${it.groupValues[2]}" }
        return listOfNotNull(town, area ?: road).distinct().joinToString(" · ")
    }

    private val SERVICE = Regex("(?i)rast(st(ä|ae)tte|anlage|hof|platz)|autohof|tank ?& ?rast|service ?area")
    private val MOTORWAY = Regex("^([AE]) ?(\\d{1,3})$")

    @Synchronized private fun readStored(c: Context) {
        if (loaded) return
        runCatching {
            val o = JSONObject(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("all", "{}") ?: "{}")
            o.keys().forEach { k -> known.putIfAbsent(k, o.getString(k)) }
        }
        loaded = true
    }

    @Synchronized private fun store(c: Context, k: String, line: String) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val o = runCatching { JSONObject(p.getString("all", "{}") ?: "{}") }.getOrDefault(JSONObject())
        o.put(k, line)
        // the oldest go first when there are too many (the order kept is the order written)
        while (o.length() > MAX) o.remove(o.keys().next())
        p.edit().putString("all", o.toString()).apply()
    }
}
