package net.prototux.aare.dash

import android.content.Context
import net.prototux.aare.R
import net.prototux.aare.ShownFailure
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * The IDmate server's logbook in Mate (System L-102, IDMate L-012): one month as the server's own logbook page builds it
 * (`/api/v1/logbook`), the lists to pick from (`/logbook/options`), and purpose, partner, occasion and note of a trip
 * changed from the tablet (`POST /trips/<id>`) where the server's admin allowed it for the vehicle. The server stays the
 * owner of the trips and keeps working on its own; Mate only mirrors and edits. Over the access of the dashboard
 * ([Link]); calls block - off the main thread.
 */
object ServerLogbook {
    /** One line of the logbook. [kind]: a business trip, one [OPEN] without purpose, [PRIVATE] trips in a row, a [GAP] of the odometer. */
    data class Row(
        val kind: String, val startMs: Long?, val endMs: Long?, val odoStart: Double?, val odoEnd: Double?, val distance: Double?,
        val from: String?, val to: String?, val purpose: String?, val destination: String?, val visitReason: String?, val note: String?,
        val trips: List<Long>, val count: Int,
    )

    /**
     * One trip of the month with its logbook fields, and what it used as IDmate's trip page shows it (System L-111, IDMate
     * L-013: energy in kWh, consumption in kWh/100 km, outside temperature in °C - null where the server has none).
     */
    data class Item(
        val id: Long, val startMs: Long?, val endMs: Long?, val from: String?, val to: String?, val odoStart: Double?, val odoEnd: Double?,
        val distance: Double?, val socStart: Double?, val socEnd: Double?, val purpose: String, val destination: String,
        val visitReason: String, val note: String, val private: Boolean,
        val energy: Double? = null, val consumption: Double? = null, val tExt: Double? = null,
    )

    /** Where a trip began or ended. */
    data class End(val lat: Double, val lon: Double, val name: String?)

    /** The way a trip went (System L-110, `GET /trips/<id>/route`): [points] as (lat, lon) in driving order. */
    data class Route(val id: Long, val points: List<DoubleArray>, val start: End?, val end: End?) {
        /** west, south, east, north of the way and its ends; null without any place. */
        val box: DoubleArray? get() {
            val all = points + listOfNotNull(start, end).map { doubleArrayOf(it.lat, it.lon) }
            if (all.isEmpty()) return null
            return doubleArrayOf(all.minOf { it[1] }, all.minOf { it[0] }, all.maxOf { it[1] }, all.maxOf { it[0] })
        }
        /** The way as GeoJSON for the map (a line from two points on). */
        fun lineJson(): String = if (points.size < 2) EMPTY_JSON else
            """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[""" +
                points.joinToString(",") { "[${it[1]},${it[0]}]" } + "]}}"
        /** Where it began, as a GeoJSON point (empty without one). */
        fun startJson(): String = pointJson(start)
        /** Where it ended. */
        fun endJson(): String = pointJson(end)
        private fun pointJson(e: End?) = e?.let { """{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[${it.lon},${it.lat}]}}""" } ?: EMPTY_JSON
    }

    const val EMPTY_JSON = """{"type":"FeatureCollection","features":[]}"""

    data class Totals(
        val odoStart: Double?, val odoEnd: Double?, val trips: Int, val total: Double, val business: Double, val private: Double,
        val open: Double, val gaps: Double, val purposes: List<Pair<String, Double>>,
    )

    data class Month(val year: Int, val month: Int, val edit: Boolean, val months: List<String>, val rows: List<Row>,
                     val totals: Totals, val items: List<Item>) {
        fun item(id: Long) = items.firstOrNull { it.id == id }
        /** Trips without a purpose (the server's logbook warns of them). */
        val open: Int get() = rows.count { it.kind == OPEN }
    }

    /** A purpose as the server keeps it: private ones count as private trips, [main] is the pinned one of the quick button. */
    data class Purpose(val name: String, val private: Boolean, val main: Boolean = false, val color: Int? = null)

    /**
     * What to pick from (System L-112, IDMate L-014): the lists, and [usage] - for [PURPOSE], [DESTINATION] and [REASON]
     * how often each value was used on the vehicle in the last 30 days, as IDmate's trip list counts it.
     */
    data class Options(val edit: Boolean, val purposes: List<Purpose>, val destinations: List<String>, val visitReasons: List<String>,
                       val usage: Map<String, Map<String, Int>> = emptyMap()) {
        val main: Purpose? get() = purposes.firstOrNull { it.main }
        fun color(purpose: String?): Int? = purposes.firstOrNull { it.name == purpose }?.color
        /** The values of [field] to pick from, as IDmate's picker lists them ([ordered]). */
        fun choices(field: String): Choices = ordered(when (field) {
            PURPOSE -> purposes.map { it.name }; DESTINATION -> destinations; else -> visitReasons
        }, usage[field].orEmpty())
    }

    /** A picker's list: [used] (value and its 30-day count, most used first), then the [rest] alphabetically. */
    data class Choices(val used: List<Pair<String, Int>>, val rest: List<String>)

    /**
     * IDmate's picker (trips.html makePicker): the values used lately first, most used on top (equal counts by name), a line,
     * then the others alphabetically - values only in the counts are offered too.
     */
    fun ordered(values: List<String>, counts: Map<String, Int>): Choices {
        val byName = java.text.Collator.getInstance(java.util.Locale.getDefault())
        val all = (values + counts.keys).filter { it.isNotEmpty() }.distinct()
        val used = all.filter { (counts[it] ?: 0) > 0 }.sortedWith(compareByDescending<String> { counts[it] ?: 0 }.thenComparator { a, b -> byName.compare(a, b) })
        val rest = all.filter { (counts[it] ?: 0) <= 0 }.sortedWith { a, b -> byName.compare(a, b) }
        return Choices(used.map { it to (counts[it] ?: 0) }, rest)
    }

    const val PURPOSE = "purpose"
    const val DESTINATION = "destination"
    const val REASON = "visit_reason"

    const val TRIP = "trip"
    const val OPEN = "open"
    const val PRIVATE = "private"
    const val GAP = "gap"

    private const val CONNECT_MS = 4000
    private const val READ_MS = 8000
    private const val MAX_BYTES = 1024 * 1024

    /** Whether the dashboard is connected to an IDmate server (then its logbook can be shown). */
    fun available(context: Context): Boolean = Link.load(context) != null

    /** The month [year]/[month] (null: the server's current month). */
    @Throws(IOException::class)
    fun month(context: Context, year: Int? = null, month: Int? = null): Month =
        parseMonth(JSONObject(request(context, "/logbook" + if (year != null && month != null) "?year=$year&month=$month" else "", null)))

    /** The way trip [id] went (an older server without it: 404, shown as such). */
    @Throws(IOException::class)
    fun route(context: Context, id: Long): Route =
        parseRoute(JSONObject(request(context, "/trips/$id/route", null, notFound = R.string.logbook_route_404)))

    @Throws(IOException::class)
    fun options(context: Context): Options = parseOptions(JSONObject(request(context, "/logbook/options", null)))

    /** Changes the given fields of trip [id] (keys purpose, destination, visit_reason, note; "" clears); the trip as it is now. */
    @Throws(IOException::class)
    fun edit(context: Context, id: Long, fields: Map<String, String>): Item {
        val body = JSONObject().apply { fields.forEach { (k, v) -> put(k, v) } }
        return parseItem(JSONObject(request(context, "/trips/$id", body.toString())).getJSONObject("data"))
    }

    private fun request(context: Context, path: String, post: String?, notFound: Int = R.string.logbook_server_404): String {
        val link = Link.load(context) ?: throw ShownFailure("no IDmate access", R.string.logbook_server_none)
        val c = URL(link.url + path).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = CONNECT_MS
            c.readTimeout = READ_MS
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.setRequestProperty("Authorization", "Bearer " + link.token)
            c.setRequestProperty("Accept", "application/json")
            if (post != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(post.toByteArray(Charsets.UTF_8)) }
            }
            when (val code = c.responseCode) {
                200 -> Unit
                401 -> throw ShownFailure("access refused (401)", R.string.api_401)
                403 -> throw ShownFailure("logbook editing not allowed (403)", R.string.logbook_server_no_edit)
                // an older server without the logbook answers 404 too: the read API may be on all the same
                404 -> throw ShownFailure("not on the server (404): $path", notFound)
                429 -> throw ShownFailure("too many failed attempts (429)", R.string.api_429)
                else -> throw IOException("HTTP $code")
            }
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    if (out.size() + n > MAX_BYTES) throw ShownFailure("answer too large", R.string.api_too_large)
                    out.write(buf, 0, n)
                }
            }
            return out.toString("UTF-8")
        } finally {
            c.disconnect()
        }
    }

    // ── answers ──────────────────────────────────────────────────────────────

    private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k).ifEmpty { null } else null
    private fun JSONObject.num(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeUnless { it.isNaN() } else null
    private fun JSONObject.time(k: String): Long? = str(k)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifEmpty { null } }
    private fun JSONArray?.longs(): List<Long> = if (this == null) emptyList() else (0 until length()).map { optLong(it) }

    internal fun parseMonth(telegram: JSONObject): Month {
        val d = telegram.getJSONObject("data")
        val rows = d.optJSONArray("rows")
        val items = d.optJSONArray("items")
        return Month(
            d.getInt("year"), d.getInt("month"), d.optBoolean("edit"), d.optJSONArray("months").strings(),
            (0 until (rows?.length() ?: 0)).map { parseRow(rows!!.getJSONObject(it)) },
            parseTotals(d.optJSONObject("totals") ?: JSONObject()),
            (0 until (items?.length() ?: 0)).map { parseItem(items!!.getJSONObject(it)) },
        )
    }

    private fun parseRow(o: JSONObject) = Row(
        o.optString("kind", OPEN), o.time("start"), o.time("end"), o.num("odo_start"), o.num("odo_end"), o.num("distance"),
        o.str("from"), o.str("to"), o.str("purpose"), o.str("destination"), o.str("visit_reason"), o.str("note"),
        o.optJSONArray("trips").longs(), o.optInt("count", 1),
    )

    internal fun parseItem(o: JSONObject) = Item(
        o.getLong("id"), o.time("start"), o.time("end"), o.str("from"), o.str("to"), o.num("odo_start"), o.num("odo_end"),
        o.num("distance"), o.num("soc_start"), o.num("soc_end"), o.str("purpose").orEmpty(), o.str("destination").orEmpty(),
        o.str("visit_reason").orEmpty(), o.str("note").orEmpty(), o.optBoolean("private"),
        o.num("energy"), o.num("consumption"), o.num("t_ext"),
    )

    internal fun parseRoute(telegram: JSONObject): Route {
        val d = telegram.getJSONObject("data")
        val pts = d.optJSONArray("points")
        fun end(o: JSONObject?) = o?.let { e -> e.num("lat")?.let { la -> e.num("lon")?.let { lo -> End(la, lo, e.str("name")) } } }
        return Route(d.optLong("id"),
            (0 until (pts?.length() ?: 0)).mapNotNull { i -> pts!!.optJSONArray(i)?.takeIf { it.length() >= 2 }?.let { doubleArrayOf(it.optDouble(0), it.optDouble(1)) } }
                .filter { !it[0].isNaN() && !it[1].isNaN() },
            end(d.optJSONObject("start")), end(d.optJSONObject("end")))
    }

    private fun parseTotals(o: JSONObject): Totals {
        val p = o.optJSONObject("purposes")
        return Totals(
            o.num("odo_start"), o.num("odo_end"), o.optInt("trips"), o.optDouble("total", 0.0), o.optDouble("business", 0.0),
            o.optDouble("private", 0.0), o.optDouble("open", 0.0), o.optDouble("gaps", 0.0),
            p?.keys()?.asSequence()?.map { it to p.optDouble(it, 0.0) }?.toList().orEmpty(),
        )
    }

    internal fun parseOptions(telegram: JSONObject): Options {
        val d = telegram.getJSONObject("data")
        val p = d.optJSONArray("purposes")
        val u = d.optJSONObject("usage_30d")
        fun counts(field: String) = u?.optJSONObject(field)?.let { o -> o.keys().asSequence().associateWith { o.optInt(it) } }.orEmpty()
        return Options(
            d.optBoolean("edit"),
            (0 until (p?.length() ?: 0)).map { p!!.getJSONObject(it).let { o ->
                Purpose(o.optString("name"), o.optBoolean("private"), o.optBoolean("main"), o.str("color")?.let(::color)) } }
                .filter { it.name.isNotEmpty() },
            d.optJSONArray("destinations").strings(), d.optJSONArray("visit_reasons").strings(),
            mapOf(PURPOSE to counts(PURPOSE), DESTINATION to counts(DESTINATION), REASON to counts(REASON)),
        )
    }

    /** "#rrggbb" as ARGB, null for anything else. */
    internal fun color(hex: String): Int? = hex.trim().removePrefix("#").takeIf { it.length == 6 }
        ?.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
}
