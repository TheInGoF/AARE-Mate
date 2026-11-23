package net.prototux.aare.ui.car.navi

import org.maplibre.spatialk.geojson.Position

/**
 * The weather on the way for the charging plan (Navi L-029 step G, L-015: "Gegen-/Rückenwind je Abschnitt, Regen,
 * Temperatur zur Ankunftszeit"). IDmate's model has the rain it learned (kept first, [NaviRain]) and the temperature of
 * now, but no wind and no forecast; those come from Open-Meteo as the weather beside the clock does ([net.prototux.aare.car.DashboardInfo],
 * no account): one request for the hours ahead at a point every [STEP_M] of the route, rounded to 0.1° (about 10 km -
 * enough for the weather, and it says less about the way). Each piece of the route takes the point nearest to it at the
 * hour the car gets there. Without an answer the plan is made as before and the log says so.
 */
internal class ChargeWeather private constructor(private val points: List<Position>, private val hours: LongArray,
                                                 private val celsius: Array<DoubleArray>, private val windMs: Array<DoubleArray>,
                                                 private val windFrom: Array<DoubleArray>, private val rainMmH: Array<DoubleArray>,
                                                 private val nowMs: () -> Long) : ChargeEnergy.Weather {

    override fun at(at: Position, secondsAhead: Double): ChargeEnergy.Conditions? {
        if (points.isEmpty() || hours.isEmpty()) return null
        var k = 0; var bd = Double.MAX_VALUE
        for (i in points.indices) { val d = ChargerIndex.distanceM(points[i], at); if (d < bd) { bd = d; k = i } }
        if (bd > FAR_M) return null
        val t = (nowMs() + (secondsAhead * 1000).toLong()) / 1000
        // the hour the car is there (hours start at their full hour)
        val h = (hours.indexOfLast { it <= t }).takeIf { it >= 0 } ?: return null
        if (t - hours[h] > 2 * 3600) return null
        fun v(a: Array<DoubleArray>) = a[k].getOrNull(h)?.takeIf { !it.isNaN() }
        return ChargeEnergy.Conditions(v(celsius), v(windFrom), v(windMs) ?: 0.0, v(rainMmH) ?: 0.0)
    }

    /** For the log: the range of what the plan was made with. */
    fun describe(): String {
        fun range(a: Array<DoubleArray>) = a.flatMap { it.filter { v -> !v.isNaN() } }
        val t = range(celsius); val w = range(windMs); val r = range(rainMmH)
        return "weather of Open-Meteo at %d points: %.0f to %.0f °C, wind up to %.0f m/s, rain up to %.1f mm/h".format(
            java.util.Locale.ROOT, points.size, t.minOrNull() ?: 0.0, t.maxOrNull() ?: 0.0, w.maxOrNull() ?: 0.0, r.maxOrNull() ?: 0.0)
    }

    companion object {
        /** A weather point every so many metres of the route. */
        const val STEP_M = 40_000.0
        private const val MAX_POINTS = 20
        private const val FAR_M = 60_000.0
        private const val KEEP_MS = 30 * 60_000L
        /** A failed request is not asked again for this long (each planning would wait for its timeouts), then again. */
        private const val FAILED_KEEP_MS = 2 * 60_000L

        /** The points asked for along [line]: every [STEP_M], the start and the end, rounded to 0.1°, each once. */
        fun points(line: List<Position>): List<Position> {
            if (line.isEmpty()) return emptyList()
            val total = line.zipWithNext { a, b -> ChargerIndex.distanceM(a, b) }.sum()
            val step = maxOf(STEP_M, total / (MAX_POINTS - 1))
            val out = arrayListOf(line.first()); var since = 0.0
            for (i in 1 until line.size) {
                since += ChargerIndex.distanceM(line[i - 1], line[i])
                if (since >= step) { out += line[i]; since = 0.0 }
            }
            out += line.last()
            return out.map { Position(longitude = Math.round(it.longitude * 10) / 10.0, latitude = Math.round(it.latitude * 10) / 10.0) }.distinct()
        }

        fun url(points: List<Position>): String = "https://api.open-meteo.com/v1/forecast?latitude=" +
            points.joinToString(",") { "%.1f".format(java.util.Locale.ROOT, it.latitude) } + "&longitude=" +
            points.joinToString(",") { "%.1f".format(java.util.Locale.ROOT, it.longitude) } +
            "&hourly=temperature_2m,wind_speed_10m,wind_direction_10m,precipitation&wind_speed_unit=ms&timeformat=unixtime&forecast_days=2"

        /** Open-Meteo's answer: one object for one point, a list of them for several. Null when it holds no hours. */
        fun parse(points: List<Position>, json: String, nowMs: () -> Long = System::currentTimeMillis): ChargeWeather? {
            val t = json.trim()
            val all = if (t.startsWith("[")) org.json.JSONArray(t).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
                else listOf(org.json.JSONObject(t))
            if (all.size != points.size) return null
            val hourly = all.map { it.optJSONObject("hourly") ?: return null }
            val time = hourly.first().optJSONArray("time") ?: return null
            val hours = LongArray(time.length()) { time.getLong(it) }
            if (hours.isEmpty()) return null
            fun field(name: String) = Array(hourly.size) { p ->
                val a = hourly[p].optJSONArray(name)
                DoubleArray(hours.size) { i -> if (a == null || a.isNull(i)) Double.NaN else a.optDouble(i, Double.NaN) }
            }
            return ChargeWeather(points, hours, field("temperature_2m"), field("wind_speed_10m"), field("wind_direction_10m"),
                field("precipitation"), nowMs)
        }

        /** The points' key, until when the answer counts, the answer. */
        private var kept: Triple<String, Long, ChargeWeather?>? = null

        /**
         * The weather along [line], asked once and kept [KEEP_MS] for the same points (the routes of a preview, the
         * planning on the way); a failed request only [FAILED_KEEP_MS]. [said] tells the log what came. Blocking.
         */
        fun along(line: List<Position>, said: (String) -> Unit = {}): ChargeWeather? {
            val pts = points(line)
            if (pts.size < 2) return null
            val key = pts.joinToString(";") { "${it.latitude},${it.longitude}" }
            val now = System.currentTimeMillis()
            synchronized(this) { kept?.let { (k, until, w) -> if (k == key && now < until) return w } }
            var failed = false
            val w = runCatching {
                val c = java.net.URL(url(pts)).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 6_000; c.readTimeout = 8_000
                try { parse(pts, c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() }
            }.onFailure { failed = true; said("weather: none (${it.message ?: it}) - planned without it") }.getOrNull()
            if (w != null) said(w.describe()) else if (!failed && kept?.first != key) said("weather: no hours in the answer - planned without it")
            synchronized(this) { kept = Triple(key, now + if (failed) FAILED_KEEP_MS else KEEP_MS, w) }
            return w
        }
    }
}
