package net.prototux.aare.ui.car.navi

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.spatialk.geojson.Position

/**
 * The chargers the driver has charged at, with their rating (O226, 17.11.2026: "Ladepunkte, wo man war, sammeln, damit man
 * bewerten kann und er die Bewertung dann berücksichtigt. Aber der Filter siegt trotzdem. Nur wenn IONITY und Tesla
 * nebeneinander sind, dann soll er zu meinen Favoriten"). A visit is noted when Mate Navi reaches a planned charging stop;
 * the rating (1-5 stars, 0 = none) is given in the phone app. The planner takes it only among chargers it may choose anyway
 * (the chosen networks): a well rated one wins over an unrated or stronger one near it, a poorly rated one comes last.
 */
internal object ChargerVisits {
    /**
     * [power], [price]: 0 not told, 1 good, 2 middling, 3 poor; [worked]: 0 not told, 1 yes, 2 no - given later in the
     * phone app, at leisure (Navi L-004, E-003). [fresh]: a charge noticed there whose rating the driver has not looked at
     * yet (Navi L-005, the badge).
     */
    class Visit(val at: Position, val name: String, val network: String?, val kw: Int, val visits: Int, val lastMillis: Long, val rating: Int,
        val power: Int = 0, val price: Int = 0, val worked: Int = 0, val fresh: Boolean = false)

    private fun prefs(c: Context) = c.getSharedPreferences("charger_visits", Context.MODE_PRIVATE)
    private fun key(p: Position) = "%.4f,%.4f".format(java.util.Locale.ROOT, p.latitude, p.longitude)

    @Volatile private var cache: Map<String, Visit>? = null

    /** Read anew from storage next time (tests, after writing the stored list directly). */
    fun forget() { cache = null; changes.intValue++ }

    /** Counts every change of the stored list, for what shows it (the badge on "Routenplanung", Navi L-005). */
    val changes = androidx.compose.runtime.mutableIntStateOf(0)

    fun all(c: Context): List<Visit> = map(c).values.sortedByDescending { it.lastMillis }

    private fun map(c: Context): Map<String, Visit> = cache ?: runCatching {
        val a = JSONArray(prefs(c).getString("list", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.associate { o ->
            val v = Visit(Position(longitude = o.getDouble("lon"), latitude = o.getDouble("lat")), o.optString("name"),
                o.optString("network").ifBlank { null }, o.optInt("kw"), o.optInt("visits"), o.optLong("last"), o.optInt("rating"),
                o.optInt("power"), o.optInt("price"), o.optInt("worked"), o.optBoolean("fresh"))
            key(v.at) to v
        }
    }.getOrDefault(emptyMap()).also { cache = it }

    private fun save(c: Context, m: Map<String, Visit>) {
        val a = JSONArray(m.values.map { v -> JSONObject().put("lat", v.at.latitude).put("lon", v.at.longitude).put("name", v.name)
            .put("network", v.network ?: "").put("kw", v.kw).put("visits", v.visits).put("last", v.lastMillis).put("rating", v.rating)
            .put("power", v.power).put("price", v.price).put("worked", v.worked).put("fresh", v.fresh) })
        prefs(c).edit { putString("list", a.toString()) }
        cache = m
        changes.intValue++
    }

    /** A charge at [ch] (a planned stop reached). */
    @Synchronized fun noteVisit(c: Context, ch: Charger, nowMillis: Long = System.currentTimeMillis()) {
        val m = map(c).toMutableMap(); val k = key(ch.at); val old = m[k]
        m[k] = Visit(ch.at, NaviTripPlan.chargerName(ch), ch.network, ch.kw, (old?.visits ?: 0) + 1, nowMillis, old?.rating ?: 0,
            old?.power ?: 0, old?.price ?: 0, old?.worked ?: 0, fresh = true)
        save(c, m)
    }

    /**
     * A charge at [ch] noticed afterwards (Navi L-001): counted as a visit unless one was counted there within the last
     * two hours (the planned stop reached, or the same charge found twice).
     */
    @Synchronized fun seen(c: Context, ch: Charger, nowMillis: Long = System.currentTimeMillis()) {
        val last = map(c)[key(ch.at)]?.lastMillis ?: 0L
        if (nowMillis - last > 2 * 3600_000L) noteVisit(c, ch, nowMillis)
    }

    @Synchronized fun rate(c: Context, at: Position, stars: Int) {
        val m = map(c).toMutableMap(); val k = key(at); val v = m[k] ?: return
        m[k] = Visit(v.at, v.name, v.network, v.kw, v.visits, v.lastMillis, stars.coerceIn(0, 5), v.power, v.price, v.worked, v.fresh)
        save(c, m)
        RatingShare.changed(c, m.getValue(k))
    }

    /** The details of a charge, given later in the phone app (Navi L-004): each 0 (not told) to 3 (worked: 0 to 2). */
    @Synchronized fun details(c: Context, at: Position, power: Int, price: Int, worked: Int) {
        val m = map(c).toMutableMap(); val k = key(at); val v = m[k] ?: return
        m[k] = Visit(v.at, v.name, v.network, v.kw, v.visits, v.lastMillis, v.rating, power.coerceIn(0, 3), price.coerceIn(0, 3), worked.coerceIn(0, 2), v.fresh)
        save(c, m)
        RatingShare.changed(c, m.getValue(k))
    }

    /** How many charges wait to be looked at (Navi L-005): the badge on the phone app's "Routenplanung". */
    fun freshCount(c: Context): Int = map(c).values.count { it.fresh }

    /** The list was looked at: the badge goes, whether or not anything was filled in (Navi L-005). */
    @Synchronized fun allSeen(c: Context) {
        val m = map(c)
        if (m.values.none { it.fresh }) return
        save(c, m.mapValues { (_, v) -> Visit(v.at, v.name, v.network, v.kw, v.visits, v.lastMillis, v.rating, v.power, v.price, v.worked, false) })
    }

    @Synchronized fun remove(c: Context, at: Position) { save(c, map(c) - key(at)) }

    /** The rating of [ch] (0: none), for the planner: the stars, or without them what the details say ([judged]). */
    fun rating(c: Context, ch: Charger): Int = map(c)[key(ch.at)]?.let { judged(it.rating, it.power, it.price, it.worked) } ?: 0

    /**
     * Navi L-006 (b): "bevorzugt die App Ladestationen, die der Nutzer selbst gut bewertet hat" - the stars where given; without
     * them the details count: did not work or very slow ranks like 2 stars (last), worked with the power as stated and cheap like
     * 4 (first); anything else stays unrated. Only the driver's own answers, never anyone else's.
     */
    fun judged(stars: Int, power: Int, price: Int, worked: Int): Int = when {
        stars > 0 -> stars
        worked == 2 || power == 3 -> 2
        worked == 1 && power == 1 && price == 1 -> 4
        else -> 0
    }

    /** The planner's order among chargers it may choose: rated 4-5 first, then unrated and 3, then 1-2. */
    fun rank(rating: Int): Int = when { rating >= 4 -> 2; rating in 1..2 -> 0; else -> 1 }
}
