package net.prototux.aare.dash

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * System L-117 (27.11.2026: "dass die Weglinie, wo wir herkommen, auch noch mitgezeichnet wird, anstatt vom Server alle
 * paar Minuten nachgeladen … im Dashboard nur eine Nice-to-Know-Linie"): the way the car came, from the car's own GPS as
 * it drives (as Mate Navi takes it), kept on the tablet for the dashboard's map - for the eye only; IDmate's trail (the
 * server's, for the logbook) stays beneath it as it was.
 *
 * Two parts, so the map does not take the whole line anew all the time: the [settled] part, renewed once [RECENT_MAX]
 * points came on, and the [recent] ones since; the dashboard draws the last bit to the car itself. A plain clock and
 * plain numbers: for the tests.
 */
internal object DashTrail {
    /** lon, lat */
    private val points = ArrayList<DoubleArray>()
    private var settledUpTo = 0
    private var lastAtMs = 0L
    private var shownAtMs = 0L

    private val _settled = MutableStateFlow(DashMapGeo.EMPTY)
    private val _recent = MutableStateFlow(DashMapGeo.EMPTY)
    /** The line up to some hundred points ago (GeoJSON). */
    val settled: StateFlow<String> get() = _settled
    /** The line since then (GeoJSON), renewed at most every [RECENT_EVERY_MS]. */
    val recent: StateFlow<String> get() = _recent

    /** A fix of the car at [atMs] (elapsed realtime). A new drive after [NEW_DRIVE_MS] without one starts a new line. */
    @Synchronized fun note(lat: Double, lon: Double, atMs: Long) {
        if (lat == 0.0 && lon == 0.0) return
        if (lastAtMs != 0L && atMs - lastAtMs > NEW_DRIVE_MS) clear()
        lastAtMs = atMs
        val last = points.lastOrNull()
        if (last != null) {
            val d = DashDrive.distanceM(org.maplibre.spatialk.geojson.Position(longitude = last[0], latitude = last[1]),
                org.maplibre.spatialk.geojson.Position(longitude = lon, latitude = lat))
            if (d < STEP_M) return
            // a jump (the car carried, a fix far off): a gap in the line, not a straight stroke across the map
            if (d > JUMP_M) { points += doubleArrayOf(Double.NaN, Double.NaN) }
        }
        points += doubleArrayOf(lon, lat)
        if (points.size > MAX) { val cut = points.size - MAX; points.subList(0, cut).clear(); settledUpTo = maxOf(0, settledUpTo - cut) }
        if (points.size - settledUpTo > RECENT_MAX) { settledUpTo = points.size - 1; _settled.value = geo(0, settledUpTo + 1) }
        if (atMs - shownAtMs >= RECENT_EVERY_MS) { shownAtMs = atMs; _recent.value = geo(settledUpTo, points.size) }
    }

    /** The last place of the line (null: none, or after a gap). */
    @Synchronized fun last(): org.maplibre.spatialk.geojson.Position? =
        points.lastOrNull()?.takeIf { !it[0].isNaN() }?.let { org.maplibre.spatialk.geojson.Position(longitude = it[0], latitude = it[1]) }

    @Synchronized fun clear() { points.clear(); settledUpTo = 0; shownAtMs = 0L; _settled.value = DashMapGeo.EMPTY; _recent.value = DashMapGeo.EMPTY }

    /** Points [from] until [to] as lines, split at the gaps. */
    private fun geo(from: Int, to: Int): String {
        val lines = ArrayList<List<DoubleArray>>(); var cur = ArrayList<DoubleArray>()
        for (i in from until to) { val p = points[i]; if (p[0].isNaN()) { lines += cur; cur = ArrayList() } else cur += p }
        lines += cur
        return DashMapGeo.lineCollection(lines)
    }

    /** A point at most every this many metres. */
    const val STEP_M = 30.0
    private const val JUMP_M = 3_000.0
    /** About 600 km. */
    private const val MAX = 20_000
    private const val RECENT_MAX = 300
    private const val RECENT_EVERY_MS = 1_000L
    /** A pause longer than this (the car parked for the night) begins a new line; a charging stop keeps it. */
    const val NEW_DRIVE_MS = 3 * 3600_000L
}
