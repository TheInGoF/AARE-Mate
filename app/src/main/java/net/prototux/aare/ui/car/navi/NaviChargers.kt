package net.prototux.aare.ui.car.navi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import net.prototux.aare.ui.car.Mate
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.spatialk.geojson.Position

/**
 * The fast chargers along a route on the map (F5, O189: "Ladesäulen … an der Route", as Google Maps and ABRP show them):
 * the planned stops large in the accent, the other strong chargers close to the route small and quiet - a planned stop
 * is drawn once, not again among the others.
 */
internal object NaviChargers {
    /** How far from the route a charger still counts, how strong it must be, how many are drawn at most. */
    const val NEAR_M = 1_000.0
    const val MIN_KW = 150
    const val MAX_OTHERS = 80
    /** While planning (System L-099): every charger of the chosen networks this far from the route, at most this many. */
    const val PLAN_M = 15_000.0
    const val PLAN_MAX = 400
    private const val STEP_M = 1_500.0
    private const val SAME_M = 150.0

    class Found(val stops: List<Position>, val others: List<Charger>)

    /** The chargers near [route] (points in order), the [stops] apart. Pure lookup, no drawing. */
    fun along(route: List<Position>, stops: List<Position>, near: (Position, Double, Int) -> List<Charger>,
              /** System L-099: while planning a wide strip of the driver's networks, else the near ones of 150 kW */
              radiusM: Double = NEAR_M, minKw: Int = MIN_KW, keep: (Charger) -> Boolean = { true }, max: Int = MAX_OTHERS): Found {
        val seen = LinkedHashSet<Charger>()
        var last: Position? = null
        var walked = 0.0
        val step = maxOf(STEP_M, radiusM / 2)
        for (p in route) {
            walked += last?.let { ChargerIndex.distanceM(it, p) } ?: step
            last = p
            if (walked < step) continue
            walked = 0.0
            seen += near(p, radiusM, minKw).filter(keep)
        }
        val others = seen.filter { c -> stops.none { ChargerIndex.distanceM(it, c.at) < SAME_M } }
            .sortedByDescending { it.kw }.take(max)
        return Found(stops, others)
    }

    fun geoJson(points: List<Position>): String {
        val f = JSONArray()
        points.forEach { p ->
            f.put(JSONObject().put("type", "Feature").put("properties", JSONObject())
                .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(p.longitude).put(p.latitude))))
        }
        return JSONObject().put("type", "FeatureCollection").put("features", f).toString()
    }

    /**
     * A round badge with a lightning bolt, [px] wide: a planned stop ([stop]) in the accent with a dark bolt, any other
     * charger dark with a quiet ring and bolt, so the stops stand out and the rest stays in the background.
     */
    fun image(px: Int, stop: Boolean): ImageBitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = px / 2f
        val ring = px * 0.09f
        p.color = (if (stop) Mate.bg else Mate.routeAlt).toArgb()
        c.drawCircle(r, r, r, p)
        p.color = (if (stop) Mate.accent else Mate.bg).toArgb()
        c.drawCircle(r, r, r - ring, p)
        p.color = (if (stop) Mate.onAccent else Mate.routeAlt).toArgb()
        val s = px / 100f
        c.drawPath(Path().apply {
            moveTo(56 * s, 18 * s); lineTo(30 * s, 56 * s); lineTo(48 * s, 56 * s)
            lineTo(42 * s, 84 * s); lineTo(70 * s, 44 * s); lineTo(52 * s, 44 * s); close()
        }, p)
        return bmp.asImageBitmap()
    }
}
