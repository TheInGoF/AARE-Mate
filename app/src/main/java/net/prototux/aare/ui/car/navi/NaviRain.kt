package net.prototux.aare.ui.car.navi

import android.content.Context
import org.maplibre.spatialk.geojson.Position
import kotlin.math.abs
import kotlin.math.cos

/**
 * Rain on the way (18.11.2026: "Die Regenberechnung ist fertig" - IDmate 1.2.345 serves its rain raster at /rain and
 * the extra consumption per grade, learned from the car's own trips, in /energy-model): the grade of the raster cell a
 * place lies in at the time the car gets there, and what that costs. Read by the charging plan for every piece of the
 * route ([ChargePlanner.track]); without a learned extra or without rain nothing changes.
 * Not drawn on Mate Navi's map (18.11.2026: "Wetter und Regen nur im Dashboard, nicht in der Navi-Karte. Die Navi-Karte
 * soll so flüssig wie möglich laufen") - the rain is seen on the dashboard's map only.
 */
internal object NaviRain {
    /** The raster and the extras as last taken from IDmate ([refresh]). */
    @Volatile private var grid: Grid? = null
    @Volatile private var extra = DoubleArray(4)
    @Volatile private var waitedAt = -60_000L

    /**
     * Takes IDmate's extras and, only when a grade has been learned (without one rain changes nothing), its raster for
     * a plan made now - asked from the server here and waited for up to 4 s, so off the main thread. A raster already
     * taken is kept as it is; a new one is sorted into its cells - thousands of them on a rainy day.
     */
    fun refresh(context: Context) {
        extra = net.prototux.aare.dash.DashData.rainExtra(context)
        if (extra.none { it > 0 }) { grid = null; return }
        // one wait for a set of routes planned together, not one per route (without a network each would wait in vain)
        val now = android.os.SystemClock.elapsedRealtime()
        val wait = if (now - waitedAt > 60_000L) 4000L else 0L
        waitedAt = now
        val rain = net.prototux.aare.dash.DashData.rainNow(context, wait)
        if (rain !== grid?.rain) grid = rain?.let { Grid(it) }
    }

    /** Whether rain can change anything now: a raster and at least one learned grade. */
    val active: Boolean get() = grid != null && extra.any { it > 0 }

    /** Extra kWh/100 km at [at] reached [secondsAhead] from now (0 without rain or a learned grade). */
    fun kwh100At(at: Position, secondsAhead: Double): Double {
        val g = grid ?: return 0.0
        val level = g.level(at.latitude, at.longitude, System.currentTimeMillis() + (secondsAhead * 1000).toLong())
        return if (level in 1..3) extra[level] else 0.0
    }

    class Grid(val rain: net.prototux.aare.dash.DashData.Rain) {
        private val dLat = rain.cellKm / 111.2
        /** Per frame: cell (row, column) to grade. */
        private val maps: List<HashMap<Long, Int>> = rain.cells.map { a ->
            HashMap<Long, Int>().apply { for (k in 0 until a.size / 3) put(key(a[3 * k], a[3 * k + 1]), a[3 * k + 2].toInt()) }
        }
        /** The cell as IDmate counts it (triplog/rain_radar.py cell_of): rows round(lat / step), columns along the row. */
        private fun key(lat: Double, lon: Double): Long {
            val row = Math.round(lat / dLat)
            val la = row * dLat
            val lonStep = rain.cellKm / (111.2 * maxOf(0.01, cos(Math.toRadians(la))))
            val col = Math.round(lon / lonStep)
            return row * 1_000_003L + col
        }
        /** The picture for [atMs]: the nearest in time; past the last forecast, the last (rain moves on, not away). */
        fun frameFor(atMs: Long): Int {
            var best = 0; var gap = Long.MAX_VALUE
            for (i in rain.times.indices) { val g = abs(rain.times[i] - atMs); if (g < gap) { gap = g; best = i } }
            return best
        }
        fun level(lat: Double, lon: Double, atMs: Long): Int {
            val m = maps[frameFor(atMs)]
            return m[key(lat, lon)] ?: 0
        }
    }
}
