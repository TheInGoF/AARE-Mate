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

    /** One trip of the month with its logbook fields. */
    data class Item(
        val id: Long, val startMs: Long?, val endMs: Long?, val from: String?, val to: String?, val odoStart: Double?, val odoEnd: Double?,
        val distance: Double?, val socStart: Double?, val socEnd: Double?, val purpose: String, val destination: String,
        val visitReason: String, val note: String, val private: Boolean,
    )

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

    data class Purpose(val name: String, val private: Boolean)
    data class Options(val edit: Boolean, val purposes: List<Purpose>, val destinations: List<String>, val visitReasons: List<String>)

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

    @Throws(IOException::class)
    fun options(context: Context): Options = parseOptions(JSONObject(request(context, "/logbook/options", null)))

    /** Changes the given fields of trip [id] (keys purpose, destination, visit_reason, note; "" clears); the trip as it is now. */
    @Throws(IOException::class)
    fun edit(context: Context, id: Long, fields: Map<String, String>): Item {
        val body = JSONObject().apply { fields.forEach { (k, v) -> put(k, v) } }
        return parseItem(JSONObject(request(context, "/trips/$id", body.toString())).getJSONObject("data"))
    }

    private fun request(context: Context, path: String, post: String?): String {
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
                404 -> throw ShownFailure("no logbook on the server (404)", R.string.logbook_server_404)
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
    )

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
        return Options(
            d.optBoolean("edit"),
            (0 until (p?.length() ?: 0)).map { p!!.getJSONObject(it).let { o -> Purpose(o.optString("name"), o.optBoolean("private")) } }
                .filter { it.name.isNotEmpty() },
            d.optJSONArray("destinations").strings(), d.optJSONArray("visit_reasons").strings(),
        )
    }
}
