package net.prototux.aare.ui.car.navi

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Where Mate Navi gets its tiles and routes (wish of 16.11.2026: "ich gebe nur die tailscale adresse des servers an und
 * den tor token"): the map server's address and token from the phone app's settings page "Kartenserver". Without an
 * address the build PC through `adb reverse` as before. All of it is here, one place to change (the base URL changes
 * once the gate runs HTTPS through tailscale serve).
 */
internal object NaviServer {
    /** Paths on the server's gate (handover of the server team, 16.11.2026, projekt/unterlagen/Uebergabe_App-Team_Kartenserver_2026-11-16_v0.2.pdf):
     *  tiles Protomaps v4 z0-15, Valhalla under /route (its "/route" is appended by the routing code: /route/route),
     *  Photon under /suche (/suche/api, /suche/reverse). */
    const val TILES_PATH = "/kacheln/{z}/{x}/{y}.mvt"
    const val ROUTE_PATH = "/route"
    const val SEARCH_PATH = "/suche"
    // Build PC: a cut of Berlin from the public Protomaps build (same schema v4 as the server, kartenserver/daten/karten)
    private const val DEV_TILES = "http://127.0.0.1:8080/berlin-pv4/{z}/{x}/{y}.mvt"
    private const val DEV_VALHALLA = "http://127.0.0.1:8002"

    private fun settings(context: Context) = net.prototux.aare.Settings(context)

    fun tiles(context: Context): String = settings(context).mapServer.ifBlank { null }?.let { it + TILES_PATH } ?: DEV_TILES
    fun valhalla(context: Context): String = settings(context).mapServer.ifBlank { null }?.let { it + ROUTE_PATH } ?: DEV_VALHALLA

    /** The gate wants "Authorization: Bearer <64 hex>" on every request, 401 with an empty body otherwise (handover 16.11.2026). */
    fun interceptor(context: Context) = Interceptor { chain ->
        val token = settings(context).mapToken
        val r = chain.request()
        // the token only to the map server's gate - not to TomTom's traffic tiles or Mate's own tile service on the device
        val gate = settings(context).mapServer.toHttpUrlOrNull()?.host
        val ours = gate != null && r.url.host == gate
        chain.proceed(if (token.isBlank() || !ours) r else r.newBuilder().header("Authorization", "Bearer $token").build()).also {
            if (!ours) return@also
            // 401: the gate does not know the token (lost device, new token on the server) - the phone app asks for it again
            if (it.code == 401) rejected.value = true else if (it.isSuccessful) rejected.value = false
        }
    }

    /** The gate refused the token (HTTP 401) since it was last set; the car's panels and the phone app's page say so. */
    val rejected = androidx.compose.runtime.mutableStateOf(false)

    fun client(context: Context): OkHttpClient = shared ?: synchronized(this) { shared ?: OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .cache(okhttp3.Cache(java.io.File(context.applicationContext.cacheDir, "verkehr"), 30L * 1024 * 1024))
        // whether the last route request failed (no net, server down): the off-route hint says so
        .addInterceptor { ch -> val route = ch.request().url.encodedPath.endsWith("/route")
            try { ch.proceed(ch.request()).also { if (route) routeFailAt = if (it.code >= 500) android.os.SystemClock.elapsedRealtime() else 0L } }
            catch (e: java.io.IOException) { if (route) routeFailAt = android.os.SystemClock.elapsedRealtime(); throw e } }
        // (test "offline" cuts the net, not Mate's own tile service on the device)
        .addInterceptor { ch -> if (net.prototux.aare.BuildConfig.TEST_HOOKS && NaviDebug.offline && ch.request().url.host != "127.0.0.1") throw java.io.IOException("test: offline") else ch.proceed(ch.request()) }
        .addInterceptor(TomTomBudget.gate(context.applicationContext))
        .addInterceptor(interceptor(context.applicationContext))
        .addNetworkInterceptor(TomTomBudget.counter(context.applicationContext))
        .build().also { shared = it } }
    @Volatile private var shared: OkHttpClient? = null
    /** When the last route request failed (elapsed realtime), 0 after one went through. */
    @Volatile var routeFailAt = 0L

    /**
     * MapLibre's own downloads (the tiles) through the same client, so they carry the token too. MapLibre must be started
     * first: HttpRequestImpl builds its user agent from MapLibre's application context in its static initialiser, and
     * before MapLibre.getInstance that throws - the class is then dead (NoClassDefFoundError) and the native side aborts
     * at the first tile (the crash of 16.11.2026 morning, wrongly put down to OkHttp 5).
     */
    fun install(context: Context) {
        org.maplibre.android.MapLibre.getInstance(context.applicationContext)
        org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(client(context))
    }

    /**
     * Address and token from a QR code (O173, 16.11.2026: "der muss auch als qr code einscannbar sein. die tailscale
     * adresse und der tor token"). Read are: JSON {"server": …, "token": …} (also "adresse"/"url" and "tor_token"),
     * a link with ?token=… (the address is the link without path and query, e.g. mate-karten://… is not one),
     * two lines (address, token), or one of the two alone. Null for what is neither.
     */
    fun fromQr(text: String): Pair<String?, String?>? {
        val t = text.trim()
        val hex = Regex("^[0-9a-fA-F]{32,128}$")
        fun address(a: String?) = a?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        runCatching {
            val o = org.json.JSONObject(t)
            val server = address(listOf("server", "adresse", "url").firstNotNullOfOrNull { o.optString(it).ifBlank { null } })
            val token = listOf("token", "tor_token").firstNotNullOfOrNull { o.optString(it).ifBlank { null } }?.takeIf { hex.matches(it) }
            if (server != null || token != null) return server to token
        }
        val lines = t.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size == 2) {
            val a = lines.firstOrNull { address(it) != null }?.let(::address)
            val k = lines.firstOrNull { hex.matches(it) }
            if (a != null || k != null) return a to k
        }
        if (hex.matches(t)) return null to t
        val uri = runCatching { android.net.Uri.parse(t) }.getOrNull()
        if (uri != null && (uri.scheme == "http" || uri.scheme == "https") && uri.host != null) {
            val token = uri.getQueryParameter("token")?.takeIf { hex.matches(it) }
            val base = uri.scheme + "://" + uri.host + (if (uri.port > 0) ":" + uri.port else "")
            return base to token
        }
        return null
    }

    /** A pairing QR code of the gate (handover v0.2): {"v":1,"u":"<base URL>","c":"<one-time code>"}; null if it is none. */
    fun pairingFromQr(text: String): Pair<String, String>? = runCatching {
        val o = org.json.JSONObject(text.trim())
        if (o.optInt("v") != 1) return null
        val base = o.optString("u").trim().trimEnd('/').takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
        val code = normaliseCode(o.optString("c")) ?: return null
        base to code
    }.getOrNull()

    /** The one-time code as sent: 12 characters of A-Z and 2-9 (no 0/O/1/I); dashes and spaces from typing dropped. */
    fun normaliseCode(raw: String): String? = raw.uppercase().filter { !it.isWhitespace() && it != '-' }
        .takeIf { it.matches(Regex("[A-HJ-NP-Z2-9]{12}")) }

    sealed interface PairResult {
        data class Paired(val device: String) : PairResult
        /** The text to show: the code invalid or used (403), too many tries (429), the server out of reach. */
        data class Failed(val message: Int) : PairResult
    }

    /**
     * Trades the one-time [code] for this device's own token: POST <base>/koppeln {"code": …} without the Authorization
     * header; 200 brings {"token", "geraet"} - the token is kept encrypted, [base] becomes the server address.
     */
    suspend fun pair(context: Context, base: String, code: String): PairResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            // a client of its own: the usual one would add the (old) token
            val plain = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
            val body = org.json.JSONObject().put("code", code).toString()
                .toRequestBody("application/json".toMediaType())
            plain.newCall(okhttp3.Request.Builder().url("$base/koppeln").post(body).build()).execute().use { r ->
                when (r.code) {
                    200 -> {
                        val o = org.json.JSONObject(r.body.string())
                        val token = o.optString("token").takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
                            ?: return@runCatching PairResult.Failed(net.prototux.aare.R.string.mapserver_pair_bad_answer)
                        val s = settings(context)
                        s.mapServer = base; s.mapToken = token; s.mapDevice = o.optString("geraet")
                        rejected.value = false
                        PairResult.Paired(o.optString("geraet"))
                    }
                    403 -> PairResult.Failed(net.prototux.aare.R.string.mapserver_pair_403)
                    429 -> PairResult.Failed(net.prototux.aare.R.string.mapserver_pair_429)
                    else -> PairResult.Failed(net.prototux.aare.R.string.mapserver_pair_bad_answer)
                }
            }
        }.getOrElse { PairResult.Failed(net.prototux.aare.R.string.mapserver_pair_offline) }
    }
}
