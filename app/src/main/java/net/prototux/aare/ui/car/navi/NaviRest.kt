package net.prototux.aare.ui.car.navi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import net.prototux.aare.ui.car.Mate
import org.maplibre.spatialk.geojson.Position
import kotlin.math.cos
import kotlin.math.floor

/**
 * Rest stops along the route (F5, O189: "Rastplätze an der Route", as Google Maps shows them): the service areas
 * (Raststätte) always, the rest areas (Rastplatz) only with toilets, within [NEAR_M] of the route - they lie beside the
 * road. Shipped with the app (assets/navi/rast.tsv, projekt/werkzeuge/rast.py from OpenStreetMap), read once.
 */
internal object NaviRest {
    class Stop(val at: Position, val services: Boolean, val toilets: Boolean?, val name: String)

    const val NEAR_M = 300.0
    private const val STEP_M = 200.0
    private const val CELL = 0.1
    @Volatile private var cells: Map<Long, List<Stop>>? = null

    private fun index(context: Context): Map<Long, List<Stop>> = cells ?: synchronized(this) {
        cells ?: HashMap<Long, MutableList<Stop>>().also { m ->
            context.assets.open("navi/rast.tsv").bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
                    val f = line.split('\t')
                    if (f.size < 4) return@forEach
                    val s = Stop(Position(longitude = f[1].toDouble(), latitude = f[0].toDouble()), f[2] == "S",
                        when (f[3]) { "1" -> true; "0" -> false; else -> null }, f.getOrElse(4) { "" })
                    m.getOrPut(key(cell(s.at.latitude), cell(s.at.longitude))) { ArrayList() } += s
                }
            }
        }.also { cells = it }
    }

    private fun cell(deg: Double) = floor(deg / CELL).toInt()
    private fun key(lat: Int, lon: Int) = (lat.toLong() shl 32) or (lon.toLong() and 0xffffffffL)

    /** Whether the map shows it: a service area always, a rest area only with toilets. */
    fun worthShowing(s: Stop) = s.services || s.toilets == true

    /** The rest stops worth showing within [NEAR_M] of [route], each once. */
    fun along(context: Context, route: List<Position>): List<Stop> = along(route) { p, r -> near(index(context), p, r) }

    fun along(route: List<Position>, near: (Position, Double) -> List<Stop>): List<Stop> {
        val seen = LinkedHashSet<Stop>()
        var last: Position? = null
        var walked = STEP_M
        for (p in route) {
            walked += last?.let { ChargerIndex.distanceM(it, p) } ?: 0.0
            last = p
            if (walked < STEP_M) continue
            walked = 0.0
            near(p, NEAR_M).filterTo(seen, ::worthShowing)
        }
        return seen.toList()
    }

    /** The rest stops worth showing within [radiusM] of [p] (the search's category, F6). */
    fun near(context: Context, p: Position, radiusM: Double): List<Stop> = near(index(context), p, radiusM).filter(::worthShowing)

    private fun near(cells: Map<Long, List<Stop>>, p: Position, radiusM: Double): List<Stop> {
        val dLat = radiusM / ChargerIndex.M_PER_DEG
        val dLon = radiusM / (ChargerIndex.M_PER_DEG * cos(Math.toRadians(p.latitude)))
        val out = ArrayList<Stop>()
        for (la in cell(p.latitude - dLat)..cell(p.latitude + dLat))
            for (lo in cell(p.longitude - dLon)..cell(p.longitude + dLon))
                cells[key(la, lo)]?.forEach { s -> if (ChargerIndex.distanceM(s.at, p) <= radiusM) out += s }
        return out
    }

    /**
     * A small round badge like the chargers': a service area with a cup, a rest area with a "P" - quiet colours, so the
     * route and the charging stops stay first.
     */
    fun image(px: Int, services: Boolean): ImageBitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = px / 2f
        p.color = Mate.routeAlt.toArgb(); c.drawCircle(r, r, r, p)
        p.color = Mate.bg.toArgb(); c.drawCircle(r, r, r - px * 0.09f, p)
        p.color = Mate.routeAlt.toArgb()
        val s = px / 100f
        if (services) {
            // a cup with a handle and a saucer
            c.drawRoundRect(30 * s, 34 * s, 62 * s, 66 * s, 6 * s, 6 * s, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 6 * s
            c.drawCircle(66 * s, 48 * s, 8 * s, p)
            p.style = Paint.Style.FILL
            c.drawRoundRect(24 * s, 70 * s, 72 * s, 76 * s, 3 * s, 3 * s, p)
        } else {
            p.textAlign = Paint.Align.CENTER; p.textSize = 58 * s; p.isFakeBoldText = true
            c.drawText("P", r, r + 20 * s, p)
        }
        return bmp.asImageBitmap()
    }
}
