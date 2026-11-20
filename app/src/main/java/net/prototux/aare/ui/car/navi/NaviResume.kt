package net.prototux.aare.ui.car.navi

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.spatialk.geojson.Position

/**
 * The trip being guided, kept over a lost connection (16.11.2026, the night run: at a charging stop the car is switched
 * off, the projection ends and the route was gone - Google Maps and ABRP offer to go on). Saved when a real trip starts,
 * dropped on arrival and when the driver ends it; offered for [VALID_MS] after. The charging stops are not kept: they
 * are planned again from the level the car has then.
 */
internal object NaviResume {
    class Saved(val place: net.prototux.aare.places.Places.Place, val own: List<Pair<Position, String>>, val atMillis: Long)

    private fun prefs(c: Context) = c.getSharedPreferences("navi_resume", Context.MODE_PRIVATE)

    fun save(c: Context, place: net.prototux.aare.places.Places.Place, own: List<Pair<Position, String>>, nowMillis: Long = System.currentTimeMillis()) {
        val j = JSONObject().put("at", nowMillis).put("place", JSONObject().put("id", place.id).put("category", place.category)
            .put("name", place.name).put("address", place.address).put("lat", place.lat).put("lon", place.lon))
            .put("own", JSONArray(own.map { (p, n) -> JSONObject().put("lat", p.latitude).put("lon", p.longitude).put("name", n) }))
        prefs(c).edit { putString("trip", j.toString()) }
    }

    fun load(c: Context, nowMillis: Long = System.currentTimeMillis()): Saved? = runCatching {
        val j = JSONObject(prefs(c).getString("trip", null) ?: return null)
        val at = j.getLong("at")
        if (nowMillis - at > VALID_MS) { clear(c); return null }
        val p = j.getJSONObject("place")
        val own = j.optJSONArray("own")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let {
            Position(longitude = it.getDouble("lon"), latitude = it.getDouble("lat")) to it.optString("name") } } }.orEmpty()
        Saved(net.prototux.aare.places.Places.Place(p.optString("id"), p.optString("category"), p.optString("name"), p.optString("address"),
            p.getDouble("lat"), p.getDouble("lon")), own, at)
    }.getOrNull()

    fun clear(c: Context) = prefs(c).edit { remove("trip") }

    const val VALID_MS = 12 * 60 * 60 * 1000L
}
