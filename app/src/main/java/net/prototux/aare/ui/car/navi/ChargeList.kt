package net.prototux.aare.ui.car.navi

import org.maplibre.spatialk.geojson.Position

/**
 * The charges of the logbook's "Ladungen" (System L-113, 26.11.2026: "Vergangene Ladesessions … eine riesen Liste an bewerteten
 * Stationen und auch Stationen, wo ich nicht gewesen war. Die müssen vernünftig eingebaut werden, entweder als extra Reiter im
 * Tab Fahrtenbuch"): IDmate's charge book where there is a server (as its charge overview lists it), the tablet's own charges
 * otherwise - and for each the charger it was at, to rate there, charge by charge. Pure, for the tests.
 */
internal object ChargeList {
    /** One charge; [key] names it for the driver's answer ("s<id>" from the server, "t<id>" from the tablet). */
    class Entry(
        val key: String, val startMs: Long?, val endMs: Long?, val place: Position?, val name: String?, val dc: Boolean?, val home: Boolean,
        val socStart: Double?, val socEnd: Double?, val kwh: Double?, val kwAvg: Double?, val kwMax: Double?, val cost: Double?,
    ) {
        /** When it was, for sorting and the month: its end, or its start. */
        val atMs: Long? get() = endMs ?: startMs
    }

    /**
     * The charger of a charge: [choices] the chargers near where the car stood (the likely one first), [charger] the one taken
     * (null: none known, or the driver said "none of these"), [sure]: the driver said so, or only this one is there.
     */
    class Station(val choices: List<Charger>, val charger: Charger?, val sure: Boolean)

    /** How many chargers near a charge are offered to pick from. */
    private const val CHOICES = 5
    /** A visit noted this long before the charge began up to this long after it ended belongs to it (the car's tile). */
    private const val BEFORE_MS = 30 * 60_000L
    private const val AFTER_MS = 3 * 3600_000L
    /** The same charger: the nearest within this of a place. */
    private const val SAME_M = 20.0

    fun fromServer(list: List<net.prototux.aare.dash.ServerLogbook.Charge>): List<Entry> = list.map { c ->
        Entry("s${c.id}", c.startMs, c.endMs, if (c.lat != null && c.lon != null) Position(longitude = c.lon, latitude = c.lat) else null,
            c.at, c.dc, c.home, c.socStart, c.socEnd, c.energy, c.powerAvg, null, c.cost)
    }

    fun fromTablet(list: List<net.prototux.aare.trips.TripStore.ChargeRow>): List<Entry> = list.map { c ->
        Entry("t${c.id}", c.startMs, c.endMs, if (c.lat != null && c.lon != null) Position(longitude = c.lon, latitude = c.lat) else null,
            c.name, c.dc, false, c.socStart, c.socEnd, c.energyKwh, null, c.maxKw, null)
    }

    /**
     * The charger of [e]: the one the driver picked for it ([link]); else one rated or said in the car's tile at the time of it
     * ([visits]); else the likely one of those near where the car stood ([near], with the answers [learned] there, as the
     * car's tile asks) - sure only where it is the only one. [find]: the chargers within a few metres of a place (for a
     * charge whose place is not known). A wallbox charge has none.
     */
    fun station(e: Entry, near: List<Charger>, learned: List<ChargeAsk.Learned>, visits: List<ChargerVisits.Visit>, link: String?,
                find: (Position) -> Charger?): Station {
        if (e.home) return Station(emptyList(), null, true)
        val place = e.place
        val likely = place?.let { ChargeAsk.candidates(near, it, learned) }.orEmpty()
        val choices = (likely + near.sortedBy { c -> place?.let { ChargerIndex.distanceM(c.at, it) } ?: 0.0 })
            .distinct().take(maxOf(CHOICES, likely.size))
        fun at(p: Position) = choices.minByOrNull { ChargerIndex.distanceM(it.at, p) }?.takeIf { ChargerIndex.distanceM(it.at, p) < SAME_M } ?: find(p)
        if (link != null) {
            val p = ChargerVisits.linkPlace(link) ?: return Station(choices, null, true)
            at(p)?.let { return Station(withFirst(choices, it), it, true) }
        }
        val from = e.startMs ?: e.endMs
        val to = e.endMs ?: e.startMs
        if (from != null && to != null) visits.filter { it.lastMillis in (from - BEFORE_MS)..(to + AFTER_MS) }
            .filter { v -> place == null || ChargerIndex.distanceM(v.at, place) <= ChargeAsk.NEAR_M }
            .minByOrNull { kotlin.math.abs(it.lastMillis - to) }
            ?.let { v -> at(v.at)?.let { return Station(withFirst(choices, it), it, true) } }
        return Station(choices, likely.firstOrNull(), likely.size == 1)
    }

    private fun withFirst(l: List<Charger>, c: Charger) = listOf(c) + l.filter { it !== c }

    /** The entries by month, newest first: (year, month 1-12) to its charges. */
    fun byMonth(list: List<Entry>, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): List<Pair<Pair<Int, Int>, List<Entry>>> =
        list.sortedByDescending { it.atMs ?: 0L }.groupBy { e ->
            e.atMs?.let { val d = java.time.Instant.ofEpochMilli(it).atZone(zone); d.year to d.monthValue } ?: (0 to 0)
        }.toList()
}
