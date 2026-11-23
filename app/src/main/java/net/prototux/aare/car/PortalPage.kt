package net.prototux.aare.car

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import net.prototux.aare.DiagLog
import net.prototux.aare.R

/**
 * The car Wi-Fi's login page to tap when [PortalLogin] could not confirm it alone (a page made of scripts): opened over
 * that Wi-Fi itself (Mate's process bound to it while the page shows), since Android's own sign-in rarely or never came
 * (23.11.2026: "Nur die Bestätigungsseite kommt halt selten oder nie. Ich weiß nicht, wie man sich denn da einloggen soll").
 */
class PortalPage : Activity() {
    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (network, url) = PortalLogin.pending ?: run { finish(); return }
        val cm = getSystemService(ConnectivityManager::class.java)
        runCatching { cm.bindProcessToNetwork(network) }
        web = WebView(this).apply {
            @Suppress("SetJavaScriptEnabled")
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = PlainClient(network)
            loadUrl(url)
        }
        setContentView(web)
        title = getString(R.string.portal_title)
    }

    override fun onDestroy() {
        web?.destroy()
        // back to what Mate's traffic went over before (mobile data while the Wi-Fi gave none, else Android's choice)
        runCatching { getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(InternetWatch.mateBound) }
        PortalLogin.pending?.let { (n, _) -> Thread { PortalLogin.recheck(applicationContext, n) }.start() }
        super.onDestroy()
    }

    /**
     * I-118c: the login pages of car Wi-Fis are often plain http, which the app's network security config refuses (the
     * WebView too). Rather than allowing plain HTTP for all of Mate, the WebView's plain-http GETs are fetched over the
     * Wi-Fi [network] by [PortalLogin.plainRaw] and handed back; cookies go through the WebView's own store. A redirect is
     * given as a small page that sends on (a WebView takes no 3xx from here). A form sent by POST cannot go this way - the
     * WebView does not hand out the form's body - so it is left to the WebView (refused as before) and named in the log.
     */
    private class PlainClient(private val network: Network) : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url ?: return null
            if (!url.scheme.equals("http", true)) return null
            val u = url.toString()
            if (!request.method.equals("GET", true)) {
                DiagLog.add("login page: ${request.method} to ${url.host} over plain http cannot be passed on (no form body from the page view) - left to the page view", area = "NET")
                return null
            }
            return runCatching {
                val headers = LinkedHashMap(request.requestHeaders.orEmpty())
                val cookies = CookieManager.getInstance()
                cookies.getCookie(u)?.takeIf { it.isNotEmpty() }?.let { headers["Cookie"] = it }
                val raw = PortalLogin.plainRaw(network, u, null, headers, PortalLogin.PAGE_MAX_BYTES, PortalLogin.PAGE_TOTAL_MS, strict = true)
                raw.all("Set-Cookie").forEach { cookies.setCookie(u, it) }
                if (raw.code in 300..399) {
                    val to = raw.header("Location")?.let { runCatching { java.net.URL(java.net.URL(u), it).toString() }.getOrNull() }
                    if (request.isForMainFrame) DiagLog.add("login page: ${url.host} sends on (${raw.code}) to ${to?.let { android.net.Uri.parse(it).host } ?: "nowhere"}", area = "NET")
                    val safe = android.text.TextUtils.htmlEncode(to ?: "")
                    val page = "<!DOCTYPE html><html><head><meta http-equiv=\"refresh\" content=\"0; url=$safe\"></head><body></body></html>"
                    return@runCatching WebResourceResponse("text/html", "UTF-8", page.byteInputStream(Charsets.UTF_8))
                }
                val type = raw.header("Content-Type")
                val mime = type?.substringBefore(';')?.trim()?.ifEmpty { null } ?: "application/octet-stream"
                val keep = raw.headers.filter { (k, _) -> k.lowercase() !in DROP }
                    .groupBy({ it.first }, { it.second }).mapValues { it.value.joinToString(", ") }
                val code = if (raw.code in 100..299 || raw.code in 400..599) raw.code else 200
                if (request.isForMainFrame) DiagLog.add("login page: ${url.host} fetched over plain http for the page view, answer ${raw.code}, ${raw.bytes.size} bytes", area = "NET")
                WebResourceResponse(mime, raw.charset()?.name(), code, raw.reason.ifEmpty { "OK" }, keep, raw.bytes.inputStream())
            }.getOrElse {
                DiagLog.add("login page: ${url.host} over plain http failed - ${it.javaClass.simpleName}: ${it.message}", area = "NET")
                null
            }
        }
    }

    companion object {
        private const val CHANNEL = "car_wifi_login"
        private const val ID = 7341
        /** Headers of a fetched answer not handed to the page view (cookies go through its store, the body is whole). */
        private val DROP = setOf("set-cookie", "content-length", "transfer-encoding", "content-type", "connection")

        /** A notification on the tablet that opens the page. */
        fun offer(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            runCatching {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.portal_title), NotificationManager.IMPORTANCE_HIGH))
                val pi = PendingIntent.getActivity(context, 0, Intent(context, PortalPage::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                nm.notify(ID, android.app.Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle(context.getString(R.string.portal_title)).setContentText(context.getString(R.string.portal_text))
                    .setContentIntent(pi).setAutoCancel(true).build())
            }
        }

        fun withdraw(context: Context) { runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(ID) } }
    }
}
