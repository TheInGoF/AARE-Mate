package net.prototux.aare.dash

import android.content.Context
import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The data of the dashboard's own map from IDmate (System L-079): what IDmate's page read for its map, through the same
 * display session - the read API's token once as a header on <read API>/display, then the server's session cookie
 * (app.DISPLAY_ENDPOINTS: /api/range-area/last, /api/wind, /api/trail); the rain from the read API itself. The token never goes elsewhere; the
 * cookie stays in memory. Blocking calls: off the main thread.
 */
internal class DashMapData(context: Context) {
    private val app = context.applicationContext
    private val jar = object : CookieJar {
        private val kept = HashMap<String, Cookie>()
        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { cookies.forEach { kept[it.domain + it.name] = it } }
        // the server's cookie is "Secure": over https only - except on the device itself (the test server on
        // 127.0.0.1), which browsers count as secure as well
        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> = kept.values.filter { c ->
            c.expiresAt > System.currentTimeMillis() && (c.matches(url) ||
                (url.host == "127.0.0.1" || url.host == "localhost") && c.domain == url.host && url.encodedPath.startsWith(c.path))
        }
        @Synchronized fun clear() = kept.clear()
    }
    private val http = OkHttpClient.Builder().cookieJar(jar).followRedirects(true)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    @Volatile private var opened: String? = null
    /** IDmate searches long ferries (Valhalla and a ferry source set up): the dashboard shows the button "Fähren". */
    @Volatile var ferriesOffered = false
        private set

    /** The IDmate server's own address (the read API's without /api/v1), null without a link. */
    private fun base(link: Link): String? = link.url.trimEnd('/').removeSuffix("/api/v1").takeIf { it.toHttpUrlOrNull() != null }

    /** Opens the display session when there is none for this link yet. False without a link or when the server refused. */
    private fun session(link: Link): Boolean {
        if (opened == link.url + link.token.hashCode()) return true
        jar.clear()
        return runCatching {
            http.newCall(Request.Builder().url(link.url.trimEnd('/') + "/display").header("Authorization", "Bearer " + link.token).build()).execute().use { r ->
                // the page's own flags: whether IDmate can search long ferries (its map button "Fähren")
                ferriesOffered = r.isSuccessful && mapBoot(runCatching { r.body.string() }.getOrDefault(""))?.optBoolean("ferries") == true
                Log.i(TAG, "dashboard map: display session HTTP ${r.code}, landed on ${r.request.url.encodedPath}, cookies ${jar.loadForRequest(r.request.url).size}, ferries ${if (ferriesOffered) "offered" else "not offered"}")
                r.isSuccessful.also { ok -> if (ok) opened = link.url + link.token.hashCode() else Log.w(TAG, "dashboard map: display session refused (HTTP ${r.code})") }
            }
        }.onFailure { Log.w(TAG, "dashboard map: no display session: ${it.javaClass.simpleName}") }.getOrDefault(false)
    }

    /** GET [path] of the IDmate server as JSON text; once more with a new session when the old one has ended. */
    private fun get(path: String): String? {
        val link = Link.load(app) ?: return null
        val base = base(link) ?: return null
        repeat(2) { attempt ->
            if (!session(link)) return null
            val text = runCatching {
                http.newCall(Request.Builder().url(base + path).header("Accept", "application/json").build()).execute().use { r ->
                    val body = r.body.string()
                    if (!r.isSuccessful || !body.trimStart().startsWith("{") && !body.trimStart().startsWith("[")) Log.i(TAG, "dashboard map: $path HTTP ${r.code} at ${r.request.url.encodedPath}: ${body.take(60).replace(Regex("\\s+"), " ")}")
                    // an ended session lands on the login page (HTML) or gets 401/403
                    if (r.code == 401 || r.code == 403 || !body.trimStart().let { it.startsWith("{") || it.startsWith("[") }) null
                    else if (r.isSuccessful) body else null
                }
            }.getOrNull()
            if (text != null) return text
            Log.i(TAG, "dashboard map: $path not answered" + if (attempt == 0) ", a new session" else "")
            if (attempt == 0) opened = null
        }
        return null
    }

    /** The stored range area (status "ok" with "geojson" and "ts"), null when there is none or no connection. */
    fun area(): JSONObject? = get("/api/range-area/last")?.let { runCatching { JSONObject(it) }.getOrNull() }?.takeIf { it.optString("status") == "ok" }

    /** The wind points of this hour, null when off or none. */
    fun wind(): JSONArray? = get("/api/wind")?.let { runCatching { JSONObject(it) }.getOrNull() }?.takeIf { it.optString("status") == "ok" }?.optJSONArray("points")

    /**
     * The rain raster (frames, cell_km) from the read API with its token (/api/rain is not among the display session's
     * endpoints): the telegram's data, null without frames or connection.
     */
    fun rain(): JSONObject? {
        val link = Link.load(app) ?: return null
        return runCatching {
            http.newCall(Request.Builder().url(link.url.trimEnd('/') + "/rain").header("Authorization", "Bearer " + link.token).build()).execute().use { r ->
                if (!r.isSuccessful) null else JSONObject(r.body.string()).optJSONObject("data")
            }
        }.getOrNull()?.takeIf { (it.optJSONArray("frames")?.length() ?: 0) > 0 }
    }

    /** The car's trail of the last 10 minutes. */
    fun trail(): JSONArray? = get("/api/trail")?.let { runCatching { JSONArray(it) }.getOrNull() }

    /** IDmate's last search of long ferries (status "none", "running", "ok" with "ferries" and "union", "error"). */
    fun ferries(): JSONObject? = get("/api/ferries")?.let { runCatching { JSONObject(it) }.getOrNull() }

    /**
     * Starts IDmate's search of long ferries (its map button "Fähren", /api/ferries/start): "running", "ok" (a recent
     * one at hand), "off", "no_model", "forbidden" (IDmate lets a display session only read) or "error".
     */
    fun startFerries(): String {
        val link = Link.load(app) ?: return "error"
        val base = base(link) ?: return "error"
        if (!session(link)) return "error"
        val url = (base + "/api/ferries/start").toHttpUrlOrNull() ?: return "error"
        // IDmate's pages send the token of the csrf_token cookie with every change, from their own address
        val csrf = jar.loadForRequest(url).firstOrNull { it.name == "csrf_token" }?.value
        return runCatching {
            http.newCall(Request.Builder().url(url).post(ByteArray(0).toRequestBody(null)).header("Accept", "application/json")
                .header("Referer", "$base/").apply { csrf?.let { header("X-CSRFToken", it) } }.build()).execute().use { r ->
                if (r.code == 401 || r.code == 403) return@use "forbidden"
                val j = JSONObject(r.body.string())
                val st = j.optString("status", "error")
                if (st == "error" && j.optString("error") == "no_model") "no_model" else st
            }
        }.onFailure { Log.w(TAG, "dashboard map: ferry search not started: ${it.javaClass.simpleName}") }.getOrDefault("error")
    }

    companion object {
        private const val TAG = "IDmateStage"

        /** The flags IDmate's dashboard page carries for its map (<script id="mapBoot">), null when there are none. */
        fun mapBoot(html: String): JSONObject? = Regex("""id="mapBoot">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html)
            ?.groupValues?.get(1)?.let { runCatching { JSONObject(it) }.getOrNull() }
    }
}
