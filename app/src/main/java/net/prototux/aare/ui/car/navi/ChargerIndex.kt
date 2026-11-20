package net.prototux.aare.ui.car.navi

import android.content.Context
import org.maplibre.spatialk.geojson.Position
import kotlin.math.cos
import kotlin.math.floor

/** A fast charger: where, its highest power, how many points, who runs it. */
internal class Charger(val at: Position, val kw: Int, val points: Int, val operator: String) {
    /** The network it belongs to ([ChargeNetworks]), found once when the list is read. */
    val network: String? = ChargeNetworks.of(operator)
}

/**
 * The fast chargers (50 kW and up) of Germany and Denmark shipped with the app (assets/navi/schnelllader.tsv, made by
 * projekt/werkzeuge/schnelllader.py from OpenStreetMap and the Bundesnetzagentur's register), in a grid of 0.1° cells
 * for looking up what lies near a route. Read once, on first use.
 */
internal class ChargerIndex private constructor(private val cells: Map<Long, List<Charger>>) {

    /** Chargers of at least [minKw] within [radiusM] of [p]. */
    fun near(p: Position, radiusM: Double, minKw: Int): List<Charger> {
        val dLat = radiusM / M_PER_DEG
        val dLon = radiusM / (M_PER_DEG * cos(Math.toRadians(p.latitude)))
        val out = ArrayList<Charger>()
        for (la in cell(p.latitude - dLat)..cell(p.latitude + dLat))
            for (lo in cell(p.longitude - dLon)..cell(p.longitude + dLon))
                cells[key(la, lo)]?.forEach { c -> if (c.kw >= minKw && distanceM(c.at, p) <= radiusM) out += c }
        return out
    }

    companion object {
        private const val CELL = 0.1
        const val M_PER_DEG = 111_320.0
        @Volatile private var loaded: ChargerIndex? = null

        fun get(context: Context): ChargerIndex = loaded ?: synchronized(this) {
            loaded ?: read(context).also { loaded = it }
        }

        private fun read(context: Context): ChargerIndex {
            val cells = HashMap<Long, MutableList<Charger>>()
            context.assets.open("navi/schnelllader.tsv").bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
                    val f = line.split('\t')
                    if (f.size < 4) return@forEach
                    val c = Charger(Position(longitude = f[1].toDouble(), latitude = f[0].toDouble()), f[2].toInt(), f[3].toInt(),
                        // runs of spaces in the register ("Söhne  GmbH") made one
                        f.getOrElse(4) { "" }.trim().replace(SPACES, " "))
                    cells.getOrPut(key(cell(c.at.latitude), cell(c.at.longitude))) { ArrayList() } += c
                }
            }
            return ChargerIndex(cells)
        }

        private fun cell(deg: Double) = floor(deg / CELL).toInt()
        private fun key(lat: Int, lon: Int) = (lat.toLong() shl 32) or (lon.toLong() and 0xffffffffL)

        fun distanceM(a: Position, b: Position): Double {
            val x = (a.longitude - b.longitude) * cos(Math.toRadians((a.latitude + b.latitude) / 2))
            val y = a.latitude - b.latitude
            return Math.sqrt(x * x + y * y) * M_PER_DEG
        }
    }
}

private val SPACES = Regex("\\s+")
