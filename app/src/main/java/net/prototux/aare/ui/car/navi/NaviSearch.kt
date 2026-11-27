package net.prototux.aare.ui.car.navi

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import org.maplibre.spatialk.geojson.Position
import kotlin.math.roundToInt

/**
 * The map server's search (Photon behind the gate, /suche - server handover 16.11.2026): for now the way back from a
 * place to its address. Without a server address the build PC's Photon through `adb reverse tcp:2322` (test builds only).
 */
internal object NaviSearch {
    private const val DEV_PHOTON = "http://127.0.0.1:2322"

    /** Null without a map server outside test builds (Navi I-053e: no asking localhost in a release). */
    private fun base(context: Context): String? =
        net.prototux.aare.Settings(context).mapServer.ifBlank { null }?.let { it + NaviServer.SEARCH_PATH }
            ?: DEV_PHOTON.takeIf { net.prototux.aare.BuildConfig.DEBUG }

    /**
     * How far, as the hits' lines show it: metres in [step]s below a kilometre (cut down, or to the nearest step when
     * [rounded]), else kilometres with one decimal.
     */
    internal fun distanceText(m: Double, step: Int, rounded: Boolean = false): String =
        if (m < 1000) "${(if (rounded) (m / step).roundToInt() else (m / step).toInt()) * step} m"
        else String.format(java.util.Locale.getDefault(), "%.1f km", m / 1000)

    /**
     * The address of [at] as the car's lists show it (O172, 16.11.2026: "Ort - Straße Hausnummer"), e.g.
     * "Berlin – Potsdamer Platz 1"; null when the server cannot be reached or knows nothing there.
     */
    suspend fun address(context: Context, at: Position): String? = withContext(Dispatchers.IO) {
        val b = base(context) ?: return@withContext null
        runCatching {
            val url = (b + "/reverse").toHttpUrl().newBuilder()
                .addQueryParameter("lat", "%.6f".format(java.util.Locale.ROOT, at.latitude))
                .addQueryParameter("lon", "%.6f".format(java.util.Locale.ROOT, at.longitude))
                .addQueryParameter("lang", net.prototux.aare.Language.code).build()
            NaviServer.client(context).newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@runCatching null
                val f = JSONObject(r.body.string()).optJSONArray("features")?.optJSONObject(0)?.optJSONObject("properties")
                    ?: return@runCatching null
                label(f)
            }
        }.getOrNull()
    }

    /** Photon's properties of the place nearest [at] (within a kilometre); null without the server or an answer (Navi L-051). */
    suspend fun reverse(context: Context, at: Position): JSONObject? = withContext(Dispatchers.IO) {
        val b = base(context) ?: return@withContext null
        runCatching {
            val url = (b + "/reverse").toHttpUrl().newBuilder()
                .addQueryParameter("lat", "%.6f".format(java.util.Locale.ROOT, at.latitude))
                .addQueryParameter("lon", "%.6f".format(java.util.Locale.ROOT, at.longitude))
                .addQueryParameter("radius", "1").addQueryParameter("limit", "1")
                .addQueryParameter("lang", net.prototux.aare.Language.code).build()
            NaviServer.client(context).newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@runCatching null
                JSONObject(r.body.string()).optJSONArray("features")?.optJSONObject(0)?.optJSONObject("properties")
            }
        }.getOrNull()
    }

    /** A hit of the search: what it is called, its address line, where it lies; [own] one of the driver's places. */
    /** [charger]: a charger found by category, with its [network] for the logo (O210). */
    class Hit(val name: String, val address: String, val at: Position, val own: Boolean = false,
              val charger: Boolean = false, val network: String? = null)

    /**
     * The driver's own places for what was typed, forgiving typos (F6, O190: "eigene Ziele zuerst"): the lists' places by
     * name and address, then the last destinations by name; the best [max], best first.
     */
    fun own(context: Context, query: String, max: Int = 3): List<Hit> {
        val book = net.prototux.aare.places.Places.load(context)
        val places = book.places.map { Hit(it.name, it.address, Position(longitude = it.lon, latitude = it.lat), own = true) to "${it.name} ${it.address}" }
        val recent = NaviPins.loadPins(context).filter { it.name.isNotBlank() }.map { Hit(it.name, "", it.at, own = true) to it.name }
        return (places + recent).mapNotNull { (h, text) -> NaviFuzzy.cost(query, text)?.let { h to it } }
            .sortedBy { it.second }.map { it.first }
            .fold(emptyList<Hit>()) { acc, h -> if (acc.any { ChargerIndex.distanceM(it.at, h.at) < 50 }) acc else acc + h }
            .take(max)
    }

    /** The own ones first, then the server's that are not one of them (within 50 m); null only when both have nothing to say. */
    fun merged(own: List<Hit>, server: List<Hit>?): List<Hit>? =
        if (server == null && own.isEmpty()) null
        else own + server.orEmpty().filter { s -> own.none { ChargerIndex.distanceM(it.at, s.at) < 50 } }

    /**
     * The same name within [SAME_PLACE_M] once (16.11.2026: "Hamburg Hauptbahnhof" five times - its entrances and stops in
     * OpenStreetMap), the first, the best match, kept; the same name in another town stays.
     */
    fun oneEach(hits: List<Hit>): List<Hit> {
        val out = ArrayList<Hit>()
        for (h in hits) if (out.none { it.name.equals(h.name, ignoreCase = true) && ChargerIndex.distanceM(it.at, h.at) < SAME_PLACE_M }) out += h
        return out
    }
    private const val SAME_PLACE_M = 1_000.0

    /**
     * Navi I-006 ("Die Suche ist gerade nicht erreichbar"): why the last search failed, said with the message - no map
     * server set, no connection, or the server's answer (HTTP code); null after a search that worked.
     */
    enum class Failure { NO_SERVER, NO_CONNECTION, SERVER }
    @Volatile var lastFailure: Failure? = null
    @Volatile var lastCode = 0

    /** [search], once more after a second when the connection failed (a short gap in the mobile net). */
    suspend fun search(context: Context, query: String, near: Position?): List<Hit>? =
        searchOnce(context, query, near) ?: if (lastFailure == Failure.NO_CONNECTION) {
            kotlinx.coroutines.delay(1_000); searchOnce(context, query, near)
        } else null

    private suspend fun searchOnce(context: Context, query: String, near: Position?): List<Hit>? = withContext(Dispatchers.IO) {
        val b = base(context) ?: run { lastFailure = Failure.NO_SERVER; return@withContext null }
        runCatching {
            val url = (b + "/api").toHttpUrl().newBuilder()
                .addQueryParameter("q", query).addQueryParameter("lang", net.prototux.aare.Language.code).addQueryParameter("limit", "6").apply {
                    near?.let {
                        addQueryParameter("lat", "%.5f".format(java.util.Locale.ROOT, it.latitude))
                        addQueryParameter("lon", "%.5f".format(java.util.Locale.ROOT, it.longitude))
                        // near first, but not only near: at full weight "hamburg" from Berlin found only Berlin's streets
                        // (16.11.2026) - a weaker weight over a wide area lets cities come first
                        addQueryParameter("zoom", "9")
                        addQueryParameter("location_bias_scale", "0.3")
                    }
                }.build()
            NaviServer.client(context).newCall(Request.Builder().url(url).build()).execute().use { r ->
                val raw = r.body.string()
                android.util.Log.i("MateNavi", "search: HTTP ${r.code}, ${raw.length} bytes, ${url.encodedPath}")
                if (!r.isSuccessful) { lastFailure = Failure.SERVER; lastCode = r.code; return@runCatching null }
                lastFailure = null
                val a = JSONObject(raw).optJSONArray("features") ?: return@runCatching emptyList()
                (0 until a.length()).mapNotNull { i ->
                    val f = a.optJSONObject(i) ?: return@mapNotNull null
                    val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
                    val p = f.optJSONObject("properties") ?: return@mapNotNull null
                    fun s(k: String) = p.optString(k).takeIf { it.isNotBlank() }
                    val street = s("street")?.let { listOfNotNull(it, s("housenumber")).joinToString(" ") }
                    val name = s("name") ?: street ?: return@mapNotNull null
                    val town = listOfNotNull(s("postcode"), s("city") ?: s("locality") ?: s("county")).joinToString(" ")
                    val address = listOfNotNull(street?.takeIf { it != name }, town.ifBlank { null }).joinToString(", ")
                    Hit(name, address, Position(longitude = c.getDouble(0), latitude = c.getDouble(1)))
                }.distinctBy { it.name + it.address }.let(::oneEach)
            }
        }.onFailure { e ->
            // a broken JSON answer is the server's; anything on the way (timeout, no route, no name) the connection's
            lastFailure = if (e is java.io.IOException) Failure.NO_CONNECTION else Failure.SERVER
            android.util.Log.w("MateNavi", "search failed: $e")
        }.getOrNull()
    }

    /**
     * O228: the nearest places of one kind (Photon's reverse search filtered by an OSM tag, e.g. amenity:restaurant) -
     * by distance, not by name: the forward search with "restaurant" found places named so, even in Greece (17.11.2026).
     */
    suspend fun nearby(context: Context, tag: String, near: Position): List<Hit>? = withContext(Dispatchers.IO) {
        // the reason of a failure for the panel, as the text search gives it (Navi I-052l)
        val b = base(context) ?: run { lastFailure = Failure.NO_SERVER; return@withContext null }
        runCatching {
            val url = (b + "/reverse").toHttpUrl().newBuilder()
                .addQueryParameter("lat", "%.5f".format(java.util.Locale.ROOT, near.latitude))
                .addQueryParameter("lon", "%.5f".format(java.util.Locale.ROOT, near.longitude))
                .addQueryParameter("radius", "10").addQueryParameter("osm_tag", tag)
                .addQueryParameter("limit", "12").addQueryParameter("lang", net.prototux.aare.Language.code).build()
            NaviServer.client(context).newCall(Request.Builder().url(url).build()).execute().use { r ->
                val raw = r.body.string()
                android.util.Log.i("MateNavi", "nearby $tag: HTTP ${r.code}, ${raw.length} bytes")
                if (!r.isSuccessful) { lastFailure = Failure.SERVER; lastCode = r.code; return@runCatching null }
                lastFailure = null
                val a = JSONObject(raw).optJSONArray("features") ?: return@runCatching emptyList()
                (0 until a.length()).mapNotNull { i ->
                    val f = a.optJSONObject(i) ?: return@mapNotNull null
                    val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
                    val p = f.optJSONObject("properties") ?: return@mapNotNull null
                    fun s(k: String) = p.optString(k).takeIf { it.isNotBlank() }
                    val at = Position(longitude = c.getDouble(0), latitude = c.getDouble(1))
                    val name = s("name") ?: return@mapNotNull null
                    val street = s("street")?.let { listOfNotNull(it, s("housenumber")).joinToString(" ") }
                    val m = ChargerIndex.distanceM(near, at)
                    val far = distanceText(m, 50)
                    Hit(name, listOfNotNull(far, street).joinToString(" · "), at)
                }.sortedBy { ChargerIndex.distanceM(near, it.at) }
            }
        }.onFailure { e ->
            lastFailure = if (e is java.io.IOException) Failure.NO_CONNECTION else Failure.SERVER
            android.util.Log.w("MateNavi", "nearby $tag failed: $e")
        }.getOrNull()
    }

    /** "Ort – Straße Hausnummer" from Photon's properties; the place's own name where there is no street. */
    private fun label(p: JSONObject): String? {
        fun s(k: String) = p.optString(k).takeIf { it.isNotBlank() }
        val town = s("city") ?: s("locality") ?: s("county")
        val street = s("street") ?: s("name").takeIf { p.optString("type") == "street" }
        val line = street?.let { listOfNotNull(it, s("housenumber")).joinToString(" ") } ?: s("name")
        return listOfNotNull(town, line).distinct().joinToString(" – ").ifBlank { null }
    }
}
