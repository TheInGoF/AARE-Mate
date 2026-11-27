package net.prototux.aare.ui.car.navi

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.spatialk.geojson.Position

/**
 * The chargers the driver has charged at, with their rating (O226, 17.11.2026: "Ladepunkte, wo man war, sammeln, damit man
 * bewerten kann und er die Bewertung dann berücksichtigt. Aber der Filter siegt trotzdem. Nur wenn IONITY und Tesla
 * nebeneinander sind, dann soll er zu meinen Favoriten"). A visit counts only for a real charge (System I-143, 26.11.2026:
 * "Stationen, wo ich nicht gewesen war"): the charger said in the car's tile, the only one where a charge was noticed, or
 * the one picked or rated for a charge of the charge list (L-113) - a planned stop reached no longer counts, Mate Navi
 * passes it even when the car does not charge there. The rating (1-5 stars, 0 = none) is given in the phone app. The
 * planner takes it only among chargers it may choose anyway (the chosen networks): a well rated one wins over an unrated
 * or stronger one near it, a poorly rated one comes last.
 */
internal object ChargerVisits {
    /**
     * [power], [price]: 0 not told, 1 good, 2 middling, 3 poor; [worked]: 0 not told, 1 yes, 2 no - given later in the
     * phone app, at leisure (Navi L-004, E-003).
     */
    class Visit(val at: Position, val name: String, val network: String?, val kw: Int, val visits: Int, val lastMillis: Long, val rating: Int,
        val power: Int = 0, val price: Int = 0, val worked: Int = 0) {
        /** The driver said something about it: stars or a detail. */
        val judged: Boolean get() = rating > 0 || power > 0 || price > 0 || worked > 0
    }

    private fun prefs(c: Context) = c.getSharedPreferences("charger_visits", Context.MODE_PRIVATE)
    /** A charger's place as the list's key: to a metre, chargers side by side stay apart (four places merged some). */
    private fun key(p: Position) = "%.5f,%.5f".format(java.util.Locale.ROOT, p.latitude, p.longitude)

    @Volatile private var cache: Map<String, Visit>? = null

    /** Read anew from storage next time (tests, after writing the stored list directly). */
    fun forget() { cache = null; changes.intValue++ }

    /** Counts every change of the stored list, for what shows it (the badge on "Fahrtenbuch", Navi L-005). */
    val changes = androidx.compose.runtime.mutableIntStateOf(0)

    fun all(c: Context): List<Visit> = map(c).values.sortedByDescending { it.lastMillis }

    /** The chargers the driver judged ("Ladestationen", L-113): the best first, equal ones the last visited first. */
    fun judged(c: Context): List<Visit> = map(c).values.filter { it.judged }
        .sortedWith(compareByDescending<Visit> { judged(it.rating, it.power, it.price, it.worked) }.thenByDescending { it.lastMillis })

    /** The visit at [at], if any. */
    fun at(c: Context, at: Position): Visit? = map(c)[key(at)]

    private fun map(c: Context): Map<String, Visit> = cache ?: runCatching {
        val a = JSONArray(prefs(c).getString("list", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.associate { o ->
            val v = Visit(Position(longitude = o.getDouble("lon"), latitude = o.getDouble("lat")), o.optString("name"),
                o.optString("network").ifBlank { null }, o.optInt("kw"), o.optInt("visits"), o.optLong("last"), o.optInt("rating"),
                o.optInt("power"), o.optInt("price"), o.optInt("worked"))
            key(v.at) to v
        }
    }.getOrDefault(emptyMap()).also { cache = it }

    private fun save(c: Context, m: Map<String, Visit>) {
        val a = JSONArray(m.values.map { v -> JSONObject().put("lat", v.at.latitude).put("lon", v.at.longitude).put("name", v.name)
            .put("network", v.network ?: "").put("kw", v.kw).put("visits", v.visits).put("last", v.lastMillis).put("rating", v.rating)
            .put("power", v.power).put("price", v.price).put("worked", v.worked) })
        prefs(c).edit { putString("list", a.toString()) }
        cache = m
        changes.intValue++
    }

    /**
     * A charge at [ch] at [whenMillis]: counted as a visit unless one was counted there within two hours of it (the tile's
     * answer and the charge list's for the same charge); the last visit is the newest of them.
     */
    @Synchronized fun seen(c: Context, ch: Charger, whenMillis: Long = System.currentTimeMillis()) {
        val m = map(c).toMutableMap(); val k = key(ch.at); val old = m[k]
        if (old != null && kotlin.math.abs(whenMillis - old.lastMillis) <= SAME_CHARGE_MS) return
        m[k] = Visit(ch.at, NaviTripPlan.chargerName(ch), ch.network, ch.kw, (old?.visits ?: 0) + 1, maxOf(whenMillis, old?.lastMillis ?: 0L),
            old?.rating ?: 0, old?.power ?: 0, old?.price ?: 0, old?.worked ?: 0)
        save(c, m)
    }

    private const val SAME_CHARGE_MS = 2 * 3600_000L

    @Synchronized fun rate(c: Context, at: Position, stars: Int) {
        val m = map(c).toMutableMap(); val k = key(at); val v = m[k] ?: return
        m[k] = Visit(v.at, v.name, v.network, v.kw, v.visits, v.lastMillis, stars.coerceIn(0, 5), v.power, v.price, v.worked)
        save(c, m)
        RatingShare.changed(c, m.getValue(k))
    }

    /** The details of a charge, given later in the phone app (Navi L-004): each 0 (not told) to 3 (worked: 0 to 2). */
    @Synchronized fun details(c: Context, at: Position, power: Int, price: Int, worked: Int) {
        val m = map(c).toMutableMap(); val k = key(at); val v = m[k] ?: return
        m[k] = Visit(v.at, v.name, v.network, v.kw, v.visits, v.lastMillis, v.rating, power.coerceIn(0, 3), price.coerceIn(0, 3), worked.coerceIn(0, 2))
        save(c, m)
        RatingShare.changed(c, m.getValue(k))
    }

    /** How many charges wait to be looked at (Navi L-005): the badge on the phone app's "Fahrtenbuch". */
    fun freshCount(c: Context): Int = prefs(c).getInt("fresh", 0)

    /** A charge was noticed in the car (L-113): one more on the badge, until the charge list is opened. */
    @Synchronized fun noticed(c: Context) {
        prefs(c).edit { putInt("fresh", freshCount(c) + 1) }
        changes.intValue++
    }

    /**
     * The charge list was looked at: the badge goes, whether or not anything was filled in (Navi L-005). Gives when it was
     * looked at before - what came after that is "neu" while the list is open.
     */
    @Synchronized fun allSeen(c: Context, nowMillis: Long = System.currentTimeMillis()): Long {
        // never looked at before (or not since the tidying): nothing is "neu", or the whole list would be
        val p = prefs(c); val before = p.getLong("seen_at", 0L).takeIf { it > 0 } ?: nowMillis
        p.edit { putInt("fresh", 0).putLong("seen_at", nowMillis) }
        changes.intValue++
        return before
    }

    /**
     * Once (System I-143): the chargers counted before without a real charge go - every visit nobody judged (a planned
     * stop passed, the nearest of several where another was charged at). They come back with a charge of the list. A
     * judged one stays. What came before counts as seen.
     */
    @Synchronized fun tidy(c: Context, nowMillis: Long = System.currentTimeMillis()) {
        val p = prefs(c)
        if (p.getBoolean("tidied", false)) return
        val m = map(c)
        val keep = m.filterValues { it.judged }
        if (keep.size != m.size) save(c, keep)
        p.edit { putBoolean("tidied", true).putInt("fresh", 0).putLong("seen_at", nowMillis) }
        if (keep.size != m.size) net.prototux.aare.DiagLog.add("charge visits: ${m.size - keep.size} never rated removed, ${keep.size} rated kept", area = "CHARGE")
    }

    /**
     * Which charger a charge of the list was at, as the driver said (L-113): by the charge's key, the charger's place,
     * "" for "none of these".
     */
    fun links(c: Context): Map<String, String> = runCatching {
        val o = JSONObject(prefs(c).getString("links", "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.optString(it) }
    }.getOrDefault(emptyMap())

    @Synchronized fun link(c: Context, charge: String, at: Position?) {
        val o = runCatching { JSONObject(prefs(c).getString("links", "{}") ?: "{}") }.getOrDefault(JSONObject())
        o.put(charge, at?.let { "%.6f,%.6f".format(java.util.Locale.ROOT, it.latitude, it.longitude) } ?: "")
        prefs(c).edit { putString("links", o.toString()) }
        changes.intValue++
    }

    /** The place a link names; null for "none". */
    fun linkPlace(link: String): Position? = link.split(',').mapNotNull { it.toDoubleOrNull() }.takeIf { it.size == 2 }
        ?.let { Position(longitude = it[1], latitude = it[0]) }

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
