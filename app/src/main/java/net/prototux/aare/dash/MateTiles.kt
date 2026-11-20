package net.prototux.aare.dash

import android.content.Context
import okhttp3.Request

/**
 * The dashboard's map from our map server (O175, 16.11.2026: "die map auf dem dashboard bekommt auch unsere server
 * map … auf dem dashboard tag map"). The dashboard's page draws IDmate's vector map (Protomaps v4, day colours) from
 * a stand-in address under the page's own origin ([PREFIX]); the web view's requests for it are answered here through the gate with the device's token
 * (a page cannot send it) - the same tiles as Mate Navi, kept in the dashboard's tile store.
 */
object MateTiles {
    /**
     * Under the page's own origin: IDmate's page allows connections only there (Content-Security-Policy connect-src
     * 'self'), a host of our own was refused (16.11.2026). Answered on the device - it never reaches IDmate's server.
     */
    const val PREFIX = "/__mate-karte"
    const val PATH_JS = "location.origin + '$PREFIX/kacheln/{z}/{x}/{y}.mvt'"
    private val PATH = Regex("""/kacheln/(\d{1,2})/(\d+)/(\d+)\.mvt""")

    @JvmStatic fun isOurs(url: String) = url.contains("$PREFIX/kacheln/")

    /**
     * The tile for a stand-in address from the map server (or the build PC), null when it cannot be had. Without the
     * net (or the server not answering) see [offline].
     */
    @JvmStatic fun fetch(context: Context, url: String): ByteArray? {
        val m = PATH.find(url) ?: return null
        if (!net.prototux.aare.ui.car.navi.deviceOnline(context)) return null
        val (z, x, y) = m.destructured
        val real = net.prototux.aare.ui.car.navi.NaviServer.tiles(context).replace("{z}", z).replace("{x}", x).replace("{y}", y)
        return runCatching {
            net.prototux.aare.ui.car.navi.NaviServer.client(context).newCall(Request.Builder().url(real).build()).execute().use { r ->
                // how it went, without the tile numbers (they tell where the car is)
                if (net.prototux.aare.BuildConfig.DEBUG) android.util.Log.d("IDmateTiles", "our tile: HTTP ${r.code}")
                if (r.isSuccessful) r.body.bytes() else null
            }
        }.getOrNull()?.also { source(context, null) }
    }

    /**
     * The tile from the region package on the device (Navi O175 / P-059: the dashboard's map without the net from the
     * whole package, like Mate Navi's). Not kept in the tile store - it is on the device already.
     */
    @JvmStatic fun offline(context: Context, url: String): ByteArray? {
        val m = PATH.find(url) ?: return null
        val (z, x, y) = m.destructured
        val (map, tile) = net.prototux.aare.ui.car.navi.RegionPacks.offlineTile(context, z.toInt(), x.toInt(), y.toInt()) ?: run { source(context, ""); return null }
        source(context, map.id)
        return tile
    }

    @Volatile private var lastSource: String? = "?"

    /** One log line per change, like Mate Navi's "map source: …" (null = server, "" = no package). */
    private fun source(context: Context, pack: String?) {
        if (pack == lastSource) return
        lastSource = pack
        android.util.Log.i("IDmateTiles", "dashboard map source: " + when (pack) { null -> "server"; "" -> "none (offline, no package)"; else -> "package $pack (offline)" })
    }

    /**
     * The answer to the page: the page comes from IDmate's address, the stand-in is another origin - without CORS
     * headers its fetch failed ("Failed to fetch", 16.11.2026).
     */
    @JvmStatic fun response(body: ByteArray?): android.webkit.WebResourceResponse {
        val headers = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=86400")
        return if (body == null || body.isEmpty())
            android.webkit.WebResourceResponse("application/x-protobuf", null, 404, "Not Found", headers, java.io.ByteArrayInputStream(ByteArray(0)))
        else android.webkit.WebResourceResponse("application/x-protobuf", null, 200, "OK", headers, java.io.ByteArrayInputStream(body))
    }
}
