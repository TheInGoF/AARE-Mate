package net.prototux.aare.ui.car.navi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import net.prototux.aare.ui.car.Mate
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.spatialk.geojson.Position

/**
 * The places one has driven to (wish of 16.11.2026: "nachdem wir das ziel erreicht haben steckt ein pin in der karte
 * symbolisch damit man unsere letzten ziele ja auch wiederfinden kann"): after an arrival its destination is kept on the
 * device, the last [KEEP] of them, and they stand on Mate Navi's map as pins.
 */
internal object NaviPins {
    private const val PREFS = "mate_navi"
    private const val KEY = "last_destinations"
    const val KEEP = 10

    /** A destination reached: where, under which name, when. */
    class Pin(val at: Position, val name: String, val time: Long)

    fun loadPins(context: Context): List<Pin> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getJSONObject(it).let { o ->
                Pin(Position(longitude = o.getDouble("lon"), latitude = o.getDouble("lat")), o.optString("name"), o.optLong("t")) } }
        }.getOrDefault(emptyList())
    }

    fun load(context: Context): List<Position> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getJSONObject(it).let { o -> Position(longitude = o.getDouble("lon"), latitude = o.getDouble("lat")) } }
        }.getOrDefault(emptyList())
    }

    /** Drops the pins of test drives ("mate-test …"); how many went. */
    fun removeTests(context: Context): Int {
        val all = loadPins(context)
        val kept = all.filterNot { it.name.startsWith("mate-test") }
        if (kept.size != all.size) save(context, kept)
        return all.size - kept.size
    }

    /** Adds [p] as the newest pin (a pin within 30 m of it goes) and keeps the last [KEEP]. */
    fun add(context: Context, p: Position, name: String = ""): List<Position> {
        val k = 111_320.0
        val kept = loadPins(context).filter {
            Math.hypot((it.at.latitude - p.latitude) * k, (it.at.longitude - p.longitude) * k * Math.cos(Math.toRadians(p.latitude))) > 30
        }
        val all = (listOf(Pin(p, name, System.currentTimeMillis())) + kept).take(KEEP)
        save(context, all)
        return all.map { it.at }
    }

    /**
     * Gives every pin without a name its address from the map server's search ("Ort – Straße Hausnummer", O172) and keeps
     * it; pins the server cannot name stay as they are and are asked again next time. True when a name was added.
     */
    suspend fun fillNames(context: Context, standIn: String): Boolean {
        // older pins kept the stand-in "Ort auf der Karte" as their name - they count as unnamed
        val pins = loadPins(context).map { if (it.name == standIn) Pin(it.at, "", it.time) else it }
        if (pins.none { it.name.isBlank() }) return false
        val named = pins.map { p -> if (p.name.isNotBlank()) p else NaviSearch.address(context, p.at)?.let { Pin(p.at, it, p.time) } ?: p }
        if (named.zip(pins).none { (a, b) -> a.name != b.name && a.name.isNotBlank() }) return false
        save(context, named)
        return true
    }

    private fun save(context: Context, all: List<Pin>) {
        val a = JSONArray()
        all.forEach { a.put(JSONObject().put("lat", it.at.latitude).put("lon", it.at.longitude).put("name", it.name).put("t", it.time)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }

    /** The pins as a GeoJSON feature collection for a MapLibre source. */
    fun geoJson(pins: List<Position>): String {
        val f = JSONArray()
        pins.forEach { p ->
            f.put(JSONObject().put("type", "Feature").put("properties", JSONObject())
                .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(p.longitude).put(p.latitude))))
        }
        return JSONObject().put("type", "FeatureCollection").put("features", f).toString()
    }

    /**
     * Design A of projekt/navi-pin-optionen ("pin A aber ein wenig weniger schräg"): a ball-headed pin of glossy plastic
     * in the accent with a highlight, on a metal needle, stuck in slightly slanted ([TILT_DEG]). Its tip is the bottom
     * centre of the image, so the pin stands exactly at the place (anchor bottom); a small shadow where it goes in.
     */
    fun image(px: Int): ImageBitmap {
        val ball = px * 0.30f
        val needle = px * 0.95f
        val rad = Math.toRadians(TILT_DEG.toDouble())
        val reach = (needle + ball) * Math.sin(rad).toFloat() + ball
        val w = (2 * reach + px * 0.1f).toInt()
        val h = (needle + 2 * ball + px * 0.12f).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val tipX = w / 2f; val tipY = h - px * 0.04f
        // where it goes in
        p.color = Mate.bg.copy(alpha = 0.55f).toArgb()
        c.drawOval(tipX - px * 0.10f, tipY - px * 0.04f, tipX + px * 0.10f, tipY + px * 0.03f, p)
        c.save()
        c.rotate(TILT_DEG, tipX, tipY)
        // fully opaque from here on: the shadow's half-transparent colour left the ball dull and see-through
        p.color = Mate.bg.toArgb(); p.alpha = 255
        // the metal needle, lit from the left
        p.shader = android.graphics.LinearGradient(tipX - px * 0.04f, 0f, tipX + px * 0.04f, 0f,
            intArrayOf(0xFF9AA4AE.toInt(), 0xFFE8EDF2.toInt(), 0xFF6B7580.toInt()), floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP)
        // the needle ends inside the ball's lower edge, so nothing of it shows through the ball
        c.drawRoundRect(tipX - px * 0.035f, tipY - needle + ball * 0.2f, tipX + px * 0.035f, tipY, px * 0.03f, px * 0.03f, p)
        p.shader = null
        // the glossy ball
        val cx = tipX; val cy = tipY - needle - ball * 0.6f
        p.shader = android.graphics.RadialGradient(cx - ball * 0.3f, cy - ball * 0.35f, ball * 1.45f,
            intArrayOf(androidx.compose.ui.graphics.lerp(Mate.accent, androidx.compose.ui.graphics.Color.White, 0.6f).toArgb(),
                Mate.accent.toArgb(), androidx.compose.ui.graphics.lerp(Mate.accent, Mate.bg, 0.15f).toArgb()),
            floatArrayOf(0f, 0.4f, 1f), android.graphics.Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, ball, p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = px * 0.035f; p.color = Mate.bg.toArgb()
        c.drawCircle(cx, cy, ball, p)
        p.style = Paint.Style.FILL; p.color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.75f).toArgb()
        c.drawOval(cx - ball * 0.62f, cy - ball * 0.62f, cx - ball * 0.05f, cy - ball * 0.25f, p)
        c.restore()
        return bmp.asImageBitmap()
    }

    private const val TILT_DEG = 10f
}
