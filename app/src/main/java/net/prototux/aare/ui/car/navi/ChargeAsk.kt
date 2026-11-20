package net.prototux.aare.ui.car.navi

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import org.maplibre.spatialk.geojson.Position

/**
 * "Wie war die Ladung?" (Navi L-001, E-003): "Erkennt die App ... einen Ladevorgang, blendet sie bei Abfahrt von der
 * Ladestation eine kleine Pop-up-Kachel ein. Darüber bewertet man die Ladestation ... Die Kachel verschwindet nach
 * 10 Sekunden von selbst oder sofort über ein X ... Während des Ladens kommen keine direkten Daten. Die App erkennt das
 * Laden nachträglich an einem SoC-Sprung ... Zuordnung der Ladestation: über den letzten Standort vor dem SoC-Sprung".
 * In the tile only the stars are given (E-003); the charger is the nearest one known at the place the car stood, and
 * where several networks stand side by side the tile lets the driver say which it was (L-002), and Mate learns from the
 * answers where each one stands: "Die App merkt sich die Antworten mit den GPS-Positionen und baut daraus lernend
 * Geofencing-Bereiche je Ladestation auf, damit sie Ladestationen künftig besser eingrenzen kann" - on the device only
 * (the server for all devices is Navi E-010).
 */
internal object ChargeAsk {
    /** A charge to ask about: the chargers it may have been at, the nearest first. */
    class Ask(val candidates: List<Charger>, val fromPercent: Double, val toPercent: Double, val place: Position? = null)

    /** One answer: the car stood at [at] and it was the charger at [charger]. */
    class Learned(val at: Position, val charger: Position)

    /** Where the car stood within this of an answer, that answer counts (a charging bay and its neighbours). */
    const val GEOFENCE_M = 35.0
    /** As many answers alike there, and no other: Mate knows the charger and asks no more which it was. */
    const val SURE_AFTER = 2
    private const val MAX_LEARNED = 300

    /**
     * The charger the answers near [place] name, and how many name it; null where none is near or they disagree evenly.
     * Pure, for the tests.
     */
    fun learnedFor(learned: List<Learned>, place: Position): Pair<Position, Int>? {
        val near = learned.filter { ChargerIndex.distanceM(it.at, place) <= GEOFENCE_M }
        if (near.isEmpty()) return null
        val groups = near.groupBy { l -> near.first { ChargerIndex.distanceM(it.charger, l.charger) < 5.0 }.charger }
        val ranked = groups.entries.sortedByDescending { it.value.size }
        if (ranked.size > 1 && ranked[0].value.size == ranked[1].value.size) return null
        return ranked[0].key to ranked[0].value.size - (near.size - ranked[0].value.size)
    }

    /** What the car screen shows now (the tile over the controls); null: nothing to ask. */
    val pending = mutableStateOf<Ask?>(null)

    /** The level must have risen by this much to count as charged (recuperation and a second source differ by less). */
    const val MIN_RISE = 5.0
    /** The car counts as driving off above this speed, m/s. */
    const val MOVING_MS = 2.0
    /** How far from where the car stood a charger may be to be the one. */
    const val NEAR_M = 250.0

    /** A car that stood this long may have charged there. */
    const val LONG_STOP_MS = 4 * 60_000L
    /** A level found higher this long after driving off still belongs to that stop (IDmate's level comes late). */
    const val AFTER_STOP_MS = 30 * 60_000L
    private const val SAME_PLACE_M = 300.0
    private const val MIN_KW = 20

    class Charged(val at: Position?, val from: Double, val to: Double)

    /**
     * Follows the level and where the car stands: a level found [MIN_RISE] higher than before was charged where the car
     * stood - where it stands now, or where it last stood for a while (the level may arrive only after driving off:
     * "weil man dann schon eingestiegen ist und fährt"). The charge is given out once, as soon as the car drives.
     * [baseSoc], [lastAt], [lastMs] are kept over a restart of Mate: the tablet is off while the car charges, so a gap
     * with the car still at the same place counts as standing there.
     */
    class Watch(var baseSoc: Double? = null, var lastAt: Position? = null, var lastMs: Long = 0L) {
        private var standingSince = 0L
        private var standingAt: Position? = null
        private var stop: Position? = null
        private var stopUntil = 0L
        private var waiting: Charged? = null

        fun step(soc: Double, at: Position, speedMs: Double?, nowMs: Long): Charged? {
            val prevAt = lastAt; val prevMs = lastMs
            val v = speedMs ?: if (prevAt != null && nowMs > prevMs && nowMs - prevMs < 60_000L)
                ChargerIndex.distanceM(prevAt, at) / ((nowMs - prevMs) / 1000.0) else 0.0
            val moving = v > MOVING_MS
            if (prevAt != null && prevMs > 0 && nowMs - prevMs >= LONG_STOP_MS) {
                if (ChargerIndex.distanceM(prevAt, at) <= SAME_PLACE_M) { stop = prevAt; stopUntil = nowMs }
                standingSince = 0L; standingAt = null
            }
            if (!moving) { if (standingSince == 0L) { standingSince = nowMs; standingAt = at } }
            else {
                if (standingSince != 0L && nowMs - standingSince >= LONG_STOP_MS) { stop = standingAt; stopUntil = nowMs }
                standingSince = 0L; standingAt = null
            }
            val base = baseSoc
            if (base != null && soc >= base + MIN_RISE) {
                val lately = stop?.takeIf { nowMs - stopUntil <= AFTER_STOP_MS }
                val place = if (standingSince != 0L && nowMs - standingSince >= LONG_STOP_MS) standingAt else lately ?: standingAt
                waiting = Charged(place ?: waiting?.at, waiting?.from ?: base, soc)
                baseSoc = soc
            } else if (base == null || moving || soc < base) baseSoc = soc
            lastAt = at; lastMs = nowMs
            return if (moving) waiting.also { waiting = null } else null
        }
    }

    private var watch: Watch? = null
    private var storedSoc = Double.NaN
    private var storedAt: Position? = null
    private var storedMs = 0L

    private fun prefs(c: Context) = c.getSharedPreferences("charge_ask", Context.MODE_PRIVATE)

    /**
     * One reading of the level (IDmate's, as the dashboard shows it) with the car's place and speed; called every few
     * seconds while Mate runs in the car. Asks once the car drives after a charge at a charger Mate knows.
     */
    @Synchronized fun reading(context: Context, soc: Double, at: Position, speedMs: Double?, nowMs: Long = System.currentTimeMillis()) {
        val w = watch ?: prefs(context).let { p ->
            val s = p.getFloat("soc", Float.NaN)
            Watch(if (s.isNaN()) null else s.toDouble(),
                if (p.contains("lat")) Position(longitude = p.getFloat("lon", 0f).toDouble(), latitude = p.getFloat("lat", 0f).toDouble()) else null,
                p.getLong("ms", 0L))
        }.also { watch = it }
        val found = w.step(soc, at, speedMs, nowMs)
        // kept when it changed noticeably or once a minute, not with every reading
        val s = w.baseSoc
        if (s != null && (storedSoc.isNaN() || kotlin.math.abs(s - storedSoc) >= 0.5 || nowMs - storedMs >= 60_000L ||
                storedAt?.let { ChargerIndex.distanceM(at, it) > 100 } != false)) {
            storedSoc = s; storedAt = at; storedMs = nowMs
            prefs(context).edit().putFloat("soc", s.toFloat()).putFloat("lat", at.latitude.toFloat()).putFloat("lon", at.longitude.toFloat()).putLong("ms", nowMs).apply()
        }
        if (found == null) return
        val place = found.at
        val learned = if (place == null) emptyList() else learned(context)
        val near = if (place == null) emptyList() else candidates(ChargerIndex.get(context).near(place, NEAR_M, MIN_KW), place, learned)
        val known = place?.let { learnedFor(learned, it) }
        net.prototux.aare.DiagLog.add("charge noticed: %.0f -> %.0f %%, %s".format(found.from, found.to,
            if (place == null) "where is not known - not asked" else if (near.isEmpty()) "no charger known within ${NEAR_M.toInt()} m - not asked"
            else "asked about " + near.joinToString { NaviTripPlan.chargerName(it) } +
                (known?.let { " (learned here from ${it.second} answer(s) more than any other)" } ?: "")))
        if (near.isEmpty()) return
        // the visit is in the list of the chargers been at, so the rating has its place (a planned stop is there already)
        ChargerVisits.seen(context, near.first())
        pending.value = Ask(near, found.from, found.to, place)
    }

    /**
     * The chargers it may have been: the nearest, and the nearest of each other network beside it (three at most). Where
     * answers were given at this place before ([learned], L-002) their charger comes first, and once [SURE_AFTER] more
     * answers name it than any other, it stands alone - the tile asks only for the stars.
     */
    fun candidates(near: List<Charger>, place: Position, learned: List<Learned> = emptyList()): List<Charger> {
        val sorted = near.sortedBy { ChargerIndex.distanceM(it.at, place) }
        val first = sorted.firstOrNull() ?: return emptyList()
        fun net(c: Charger) = c.network ?: c.operator
        val known = learnedFor(learned, place)
        val hit = known?.let { (at, _) -> sorted.firstOrNull { ChargerIndex.distanceM(it.at, at) < 5.0 } }
        if (hit != null && known.second >= SURE_AFTER) return listOf(hit)
        val head = hit ?: first
        val others = sorted.filter { it !== head && net(it) != net(head) }.distinctBy { net(it) }
        return (listOf(head) + others).take(3)
    }

    /** The answers kept on the device, oldest first. */
    fun learned(context: Context): List<Learned> = prefs(context).getString("learned", "").orEmpty().split(';').mapNotNull { e ->
        val v = e.split(',').mapNotNull { it.toDoubleOrNull() }
        if (v.size != 4) null else Learned(Position(longitude = v[1], latitude = v[0]), Position(longitude = v[3], latitude = v[2]))
    }

    /** The driver said which charger it was where the car stood (only asked where several stood side by side). */
    @Synchronized fun chose(context: Context, ask: Ask, ch: Charger) {
        val place = ask.place ?: return
        if (ask.candidates.size < 2) return
        val all = (learned(context) + Learned(place, ch.at)).takeLast(MAX_LEARNED)
        prefs(context).edit().putString("learned", all.joinToString(";") {
            "%.6f,%.6f,%.6f,%.6f".format(java.util.Locale.ROOT, it.at.latitude, it.at.longitude, it.charger.latitude, it.charger.longitude)
        }).apply()
        net.prototux.aare.DiagLog.add("charge place learned: ${NaviTripPlan.chargerName(ch)} (${learnedFor(all, place)?.second ?: 0} answer(s) here more than any other)")
    }

    /** The stars given in the tile for [ch]. */
    fun rate(context: Context, ch: Charger, stars: Int) {
        ChargerVisits.seen(context, ch)
        ChargerVisits.rate(context, ch.at, stars)
        net.prototux.aare.DiagLog.add("charge rated: ${NaviTripPlan.chargerName(ch)} $stars of 5")
    }
}
