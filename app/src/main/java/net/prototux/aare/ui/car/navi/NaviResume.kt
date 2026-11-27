package net.prototux.aare.ui.car.navi

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.spatialk.geojson.Position

/**
 * The trip being guided, kept over a lost connection (16.11.2026, the night run: at a charging stop the car is switched
 * off, the projection ends and the route was gone - Google Maps and ABRP offer to go on). Saved when a real trip starts,
 * dropped on arrival and when the driver ends it; offered for [VALID_MS] after. Since Navi L-055 the charging stops and
 * the route's line are kept too, so a trip going on soon after keeps its plan.
 */
internal object NaviResume {
    /**
     * [kind] the route's kind, [stops] the charging stops of the plan, [line] the route as it ran (thinned) - kept since
     * Navi L-055 (27.11.2026: "wenn die Route einfach fortgeführt werden kann und er nicht eine neue Route und Berechnung
     * berechnet … Problem ist natürlich jetzt unser App-Update"): the trip goes on over the same stops after a stop or an
     * update of the app, with one request for the way from where the car is. Empty from a save before.
     */
    class Saved(val place: net.prototux.aare.places.Places.Place, val own: List<Pair<Position, String>>, val atMillis: Long,
                val kind: String? = null, val stops: List<ChargePlanner.Stop> = emptyList(), val line: List<Position> = emptyList()) {
        /** The charging stops still ahead of [here]: along the saved line beyond the car, not the one it stands at. */
        fun stopsAhead(here: Position): List<ChargePlanner.Stop> {
            if (stops.isEmpty()) return emptyList()
            if (line.size < 2) return stops.filter { ChargerIndex.distanceM(it.charger.at, here) > AT_STOP_M }
            val along = alongOf(line)
            val me = along[nearest(line, here)]
            return stops.filter { ChargerIndex.distanceM(it.charger.at, here) > AT_STOP_M && along[nearest(line, it.charger.at)] > me + PASSED_M }
        }
    }

    /** A stop this near the car is the one it stands at (it charged there); passed by this much along the line, behind it. */
    const val AT_STOP_M = 400.0
    const val PASSED_M = 200.0
    /** The line kept with a point at least this far from the one before. */
    const val LINE_STEP_M = 150.0

    private fun alongOf(l: List<Position>): DoubleArray = DoubleArray(l.size).also { m -> for (i in 1 until l.size) m[i] = m[i - 1] + ChargerIndex.distanceM(l[i - 1], l[i]) }
    private fun nearest(l: List<Position>, p: Position): Int = l.indices.minByOrNull { ChargerIndex.distanceM(l[it], p) } ?: 0

    /** [l] with a point every [LINE_STEP_M] or so (and its end). */
    fun thin(l: List<Position>): List<Position> {
        if (l.size < 3) return l
        val out = arrayListOf(l.first())
        for (i in 1 until l.size - 1) if (ChargerIndex.distanceM(out.last(), l[i]) >= LINE_STEP_M) out += l[i]
        out += l.last()
        return out
    }

    private fun prefs(c: Context) = c.getSharedPreferences("navi_resume", Context.MODE_PRIVATE)

    fun save(c: Context, place: net.prototux.aare.places.Places.Place, own: List<Pair<Position, String>>, nowMillis: Long = System.currentTimeMillis(),
             kind: String? = null, stops: List<ChargePlanner.Stop> = emptyList(), line: List<Position> = emptyList()) {
        val j = JSONObject().put("at", nowMillis).put("place", JSONObject().put("id", place.id).put("category", place.category)
            .put("name", place.name).put("address", place.address).put("lat", place.lat).put("lon", place.lon))
            .put("own", JSONArray(own.map { (p, n) -> JSONObject().put("lat", p.latitude).put("lon", p.longitude).put("name", n) }))
        if (kind != null) j.put("kind", kind)
        if (stops.isNotEmpty()) j.put("stops", JSONArray(stops.map { s -> JSONObject().put("lat", s.charger.at.latitude).put("lon", s.charger.at.longitude)
            .put("kw", s.charger.kw).put("points", s.charger.points).put("operator", s.charger.operator).put("atM", s.atM)
            .put("arrive", s.arrivePercent).put("leave", s.leavePercent).put("minutes", s.chargeMinutes).put("over", s.overLimit) }))
        if (line.size >= 2) j.put("line", JSONArray(thin(line).flatMap { listOf(Math.round(it.latitude * 1e5) / 1e5, Math.round(it.longitude * 1e5) / 1e5) }))
        prefs(c).edit { putString("trip", j.toString()) }
    }

    fun load(c: Context, nowMillis: Long = System.currentTimeMillis()): Saved? = runCatching {
        val j = JSONObject(prefs(c).getString("trip", null) ?: return null)
        val at = j.getLong("at")
        if (nowMillis - at > VALID_MS) { clear(c); return null }
        val p = j.getJSONObject("place")
        val own = j.optJSONArray("own")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let {
            Position(longitude = it.getDouble("lon"), latitude = it.getDouble("lat")) to it.optString("name") } } }.orEmpty()
        val stops = j.optJSONArray("stops")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            ChargePlanner.Stop(Charger(Position(longitude = o.getDouble("lon"), latitude = o.getDouble("lat")), o.getInt("kw"), o.optInt("points"),
                o.optString("operator")), o.optDouble("atM"), o.optDouble("arrive"), o.optDouble("leave"), o.optDouble("minutes"), o.optBoolean("over")) } } }.orEmpty()
        val line = j.optJSONArray("line")?.let { a -> (0 until a.length() / 2).map { i -> Position(longitude = a.getDouble(2 * i + 1), latitude = a.getDouble(2 * i)) } }.orEmpty()
        Saved(net.prototux.aare.places.Places.Place(p.optString("id"), p.optString("category"), p.optString("name"), p.optString("address"),
            p.getDouble("lat"), p.getDouble("lon")), own, at, j.optString("kind").ifBlank { null }, stops, line)
    }.getOrNull()

    fun clear(c: Context) = prefs(c).edit { remove("trip") }

    const val VALID_MS = 12 * 60 * 60 * 1000L
}
