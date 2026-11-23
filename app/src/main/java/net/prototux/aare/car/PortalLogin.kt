package net.prototux.aare.car

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import net.prototux.aare.DiagLog
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Confirms the car Wi-Fi's login page by itself (23.11.2026: "die will eigentlich nur 'ne Bestätigung haben. Das
 * Datenpaket, das ist gebucht. Nur die Bestätigungsseite kommt halt selten oder nie"): over the Wi-Fi itself the check
 * page of Android is asked for; when an answer other than "204" comes (a page or a redirect to one), that page is read
 * and its form sent the way a tap on "accept" would - hidden fields kept, boxes ticked, the confirming button pressed.
 * Afterwards the check is made again and Android is told, so it takes the Wi-Fi with internet. Pages that only work with
 * scripts cannot be confirmed so; then the log names the page's parts and the tablet offers it to tap ([PortalPage]).
 */
internal object PortalLogin {
    private const val CHECK = "http://connectivitycheck.gstatic.com/generate_204"
    private const val EVERY_MS = 90_000L
    private val last = HashMap<Network, Long>()
    /** The login page last found and not confirmed, for [PortalPage]. */
    @Volatile var pending: Pair<Network, String>? = null

    /** A form of a page: where it goes, how, its fields and the buttons' words. */
    internal class Form(val action: String, val post: Boolean, val fields: List<Pair<String, String>>, val buttons: List<Pair<String, String>>)

    /** After the page was tapped: checked again, Android told. Off the main thread. */
    fun recheck(context: Context, n: Network) {
        val ok = runCatching { get(n, CHECK, LinkedHashMap()).code == 204 }.getOrDefault(false)
        DiagLog.add("login page: after tapping it the Wi-Fi " + if (ok) "carries internet" else "still gives none", area = "NET")
        runCatching { context.getSystemService(ConnectivityManager::class.java).reportNetworkConnectivity(n, ok) }
        if (ok) { pending = null; PortalPage.withdraw(context) }
    }

    /** Off the main thread: whether the Wi-Fi [n] carries internet afterwards. */
    fun confirm(context: Context, n: Network): Boolean {
        synchronized(last) {
            val now = SystemClock.elapsedRealtime()
            if (now - (last[n] ?: -EVERY_MS) < EVERY_MS) return false
            last[n] = now
        }
        val cookies = LinkedHashMap<String, String>()
        val first = runCatching { get(n, CHECK, cookies) }.getOrElse {
            DiagLog.add("login page: the Wi-Fi answers nothing (${it.javaClass.simpleName})", area = "NET"); return false
        }
        if (first.code == 204) { pending = null; PortalPage.withdraw(context); return true }
        // follow the way to the page
        var page = first
        var url = CHECK
        repeat(5) {
            val to = page.location ?: return@repeat
            url = URL(URL(url), to).toString()
            page = runCatching { get(n, url, cookies) }.getOrElse { return@repeat }
        }
        val host = runCatching { URL(url).host }.getOrDefault("?")
        val forms = forms(page.body)
        DiagLog.add("login page: $host answers ${page.code}, ${forms.size} form(s)" + forms.joinToString("") { f ->
            "; form ${if (f.post) "POST" else "GET"} to ${runCatching { URL(URL(url), f.action).host }.getOrDefault("?")}, fields " +
                f.fields.joinToString(",") { it.first }.ifEmpty { "none" } + ", buttons " + f.buttons.joinToString(",") { "\"" + it.second + "\"" }.ifEmpty { "none" }
        } + (if (page.body.contains("<script", true)) ", with scripts" else ""), area = "NET")
        val form = pick(forms)
        if (form == null) { pending = n to url; PortalPage.offer(context); DiagLog.add("login page: nothing to confirm without scripts - offered to tap on the tablet", area = "NET"); return false }
        val target = URL(URL(url), form.action.ifEmpty { url }).toString()
        val values = form.fields + listOfNotNull(form.buttons.firstOrNull { ACCEPT.containsMatchIn(it.second) || ACCEPT.containsMatchIn(it.first) }
            ?.takeIf { it.first.isNotEmpty() }?.let { it.first to it.second })
        val body = values.joinToString("&") { URLEncoder.encode(it.first, "UTF-8") + "=" + URLEncoder.encode(it.second, "UTF-8") }
        val sent = runCatching { if (form.post) post(n, target, body, cookies, url) else get(n, "$target${if ('?' in target) '&' else '?'}$body", cookies) }
        DiagLog.add("login page: confirmed, answer ${sent.getOrNull()?.code ?: sent.exceptionOrNull()?.javaClass?.simpleName}", area = "NET")
        val ok = runCatching { get(n, CHECK, cookies).code == 204 }.getOrDefault(false)
        DiagLog.add("login page: " + if (ok) "the car's Wi-Fi now carries internet" else "still no internet after confirming", area = "NET")
        runCatching { context.getSystemService(ConnectivityManager::class.java).reportNetworkConnectivity(n, ok) }
        pending = if (ok) null else n to url
        if (ok) PortalPage.withdraw(context) else PortalPage.offer(context)
        return ok
    }

    private val ACCEPT = Regex("(?i)akzept|accept|zustimm|agree|bestätig|confirm|verbinden|connect|weiter|continue|los|start|ok\\b|einverstanden|login|anmelden")

    /** The form to send: one with a confirming button, else the only one. */
    internal fun pick(forms: List<Form>): Form? =
        forms.firstOrNull { f -> f.buttons.any { ACCEPT.containsMatchIn(it.second) || ACCEPT.containsMatchIn(it.first) } } ?: forms.singleOrNull()

    private val FORM = Regex("(?is)<form\\b([^>]*)>(.*?)</form>")
    private val INPUT = Regex("(?is)<input\\b([^>]*)>")
    private val BUTTON = Regex("(?is)<button\\b([^>]*)>(.*?)</button>")
    private fun attr(tag: String, name: String) =
        Regex("(?is)\\b$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))").find(tag)?.let { m -> m.groupValues[2].ifEmpty { m.groupValues[3].ifEmpty { m.groupValues[4] } } }

    /** The forms of [html] with their fields (hidden and text as they are, boxes ticked) and buttons. */
    internal fun forms(html: String): List<Form> = FORM.findAll(html).map { m ->
        val head = m.groupValues[1]; val inner = m.groupValues[2]
        val fields = ArrayList<Pair<String, String>>(); val buttons = ArrayList<Pair<String, String>>()
        INPUT.findAll(inner).forEach { i ->
            val t = i.groupValues[1]
            val name = attr(t, "name") ?: ""
            when ((attr(t, "type") ?: "text").lowercase()) {
                "submit", "button", "image" -> buttons += name to (attr(t, "value") ?: "")
                "checkbox", "radio" -> if (name.isNotEmpty()) fields += name to (attr(t, "value") ?: "on")
                else -> if (name.isNotEmpty()) fields += name to (attr(t, "value") ?: "")
            }
        }
        BUTTON.findAll(inner).forEach { b -> buttons += (attr(b.groupValues[1], "name") ?: "") to b.groupValues[2].replace(Regex("<[^>]*>"), "").trim() }
        // radios: only the first of a name
        val seen = HashSet<String>()
        Form(attr(head, "action") ?: "", (attr(head, "method") ?: "get").equals("post", true), fields.filter { seen.add(it.first) }, buttons)
    }.toList()

    private class Answer(val code: Int, val location: String?, val body: String)

    private fun open(n: Network, url: String, cookies: Map<String, String>): HttpURLConnection =
        (n.openConnection(URL(url)) as HttpURLConnection).apply {
            connectTimeout = 6_000; readTimeout = 6_000; instanceFollowRedirects = false
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Mate")
            if (cookies.isNotEmpty()) setRequestProperty("Cookie", cookies.entries.joinToString("; ") { it.key + "=" + it.value })
        }

    private fun read(c: HttpURLConnection, cookies: MutableMap<String, String>): Answer = try {
        val code = c.responseCode
        c.headerFields["Set-Cookie"]?.forEach { v -> v.substringBefore(';').split('=', limit = 2).takeIf { it.size == 2 }?.let { cookies[it[0].trim()] = it[1].trim() } }
        val body = runCatching { (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { r -> r.readText().take(200_000) } }.getOrNull() ?: ""
        // a page that sends on by itself (<meta http-equiv="refresh" content="0; url=...">)
        val meta = Regex("(?is)http-equiv\\s*=\\s*[\"']?refresh[^>]*url\\s*=\\s*([^\"'>]+)").find(body)?.groupValues?.get(1)?.trim()
        Answer(code, c.getHeaderField("Location") ?: meta, body)
    } finally { c.disconnect() }

    private fun get(n: Network, url: String, cookies: MutableMap<String, String>) = read(open(n, url, cookies), cookies)

    private fun post(n: Network, url: String, body: String, cookies: MutableMap<String, String>, referer: String): Answer {
        val c = open(n, url, cookies)
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded"); c.setRequestProperty("Referer", referer)
        c.outputStream.use { it.write(body.toByteArray()) }
        return read(c, cookies)
    }
}
