package net.prototux.aare.car

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import net.prototux.aare.DiagLog
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
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

    /** Keeps the cookies [setCookies] and finds where [body] sends on by itself. */
    private fun answer(code: Int, location: String?, setCookies: List<String>, body: String, cookies: MutableMap<String, String>): Answer {
        setCookies.forEach { v -> v.substringBefore(';').split('=', limit = 2).takeIf { it.size == 2 }?.let { cookies[it[0].trim()] = it[1].trim() } }
        // a page that sends on by itself (<meta http-equiv="refresh" content="0; url=...">)
        val meta = Regex("(?is)http-equiv\\s*=\\s*[\"']?refresh[^>]*url\\s*=\\s*([^\"'>]+)").find(body)?.groupValues?.get(1)?.trim()
        return Answer(code, location ?: meta, body)
    }

    private fun open(n: Network, url: String, cookies: Map<String, String>): HttpURLConnection =
        (n.openConnection(URL(url)) as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS; readTimeout = TIMEOUT_MS; instanceFollowRedirects = false
            setRequestProperty("User-Agent", AGENT)
            if (cookies.isNotEmpty()) setRequestProperty("Cookie", cookieHeader(cookies))
        }

    private fun read(c: HttpURLConnection, cookies: MutableMap<String, String>): Answer = try {
        val code = c.responseCode
        val body = runCatching { (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { r -> r.readText().take(MAX_CHARS) } }.getOrNull() ?: ""
        answer(code, c.getHeaderField("Location"), c.headerFields["Set-Cookie"].orEmpty(), body, cookies)
    } finally { c.disconnect() }

    private fun get(n: Network, url: String, cookies: MutableMap<String, String>): Answer =
        if (plain(url)) plainRequest(n, url, null, cookies, null) else read(open(n, url, cookies), cookies)

    private fun post(n: Network, url: String, body: String, cookies: MutableMap<String, String>, referer: String): Answer {
        if (plain(url)) return plainRequest(n, url, body, cookies, referer)
        val c = open(n, url, cookies)
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded"); c.setRequestProperty("Referer", referer)
        c.outputStream.use { it.write(body.toByteArray()) }
        return read(c, cookies)
    }

    private const val TIMEOUT_MS = 6_000
    private const val TOTAL_MS = 20_000L
    private const val MAX_CHARS = 200_000
    private const val MAX_BYTES = 600_000
    private const val AGENT = "Mozilla/5.0 (Linux; Android) Mate"

    private fun cookieHeader(cookies: Map<String, String>) = cookies.entries.joinToString("; ") { it.key + "=" + it.value }

    private fun plain(url: String) = runCatching { URL(url).protocol.equals("http", true) }.getOrDefault(false)

    /**
     * A small HTTP/1.1 request over the Wi-Fi's own sockets, for the check page and login pages that are plain http.
     * The app's network security config keeps HttpURLConnection HTTPS only (plain HTTP is refused there), so these few
     * pages go this way; everything else stays HTTPS only. [body] null sends a GET, else a form POST.
     */
    private fun plainRequest(n: Network, url: String, body: String?, cookies: MutableMap<String, String>, referer: String?): Answer {
        val u = URL(url)
        val port = if (u.port > 0) u.port else 80
        val path = u.file.ifEmpty { "/" }
        val address = n.getAllByName(u.host).first()
        val deadline = SystemClock.elapsedRealtime() + TOTAL_MS
        n.socketFactory.createSocket().use { s ->
            s.connect(InetSocketAddress(address, port), TIMEOUT_MS)
            s.soTimeout = TIMEOUT_MS
            val payload = body?.toByteArray(Charsets.UTF_8)
            val head = StringBuilder()
            head.append(if (payload != null) "POST" else "GET").append(' ').append(path).append(" HTTP/1.1\r\n")
            head.append("Host: ").append(u.host).append(if (u.port > 0 && u.port != 80) ":${u.port}" else "").append("\r\n")
            head.append("User-Agent: ").append(AGENT).append("\r\n")
            head.append("Accept: text/html,*/*\r\n")
            head.append("Accept-Encoding: identity\r\n")
            head.append("Connection: close\r\n")
            if (cookies.isNotEmpty()) head.append("Cookie: ").append(cookieHeader(cookies)).append("\r\n")
            if (payload != null) {
                head.append("Content-Type: application/x-www-form-urlencoded\r\n")
                head.append("Content-Length: ").append(payload.size).append("\r\n")
                if (referer != null) head.append("Referer: ").append(referer).append("\r\n")
            }
            head.append("\r\n")
            val out = BufferedOutputStream(s.getOutputStream())
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (payload != null) out.write(payload)
            out.flush()

            val input = BufferedInputStream(s.getInputStream())
            var code: Int
            var headers: List<Pair<String, String>>
            do { // skip interim answers (100 Continue)
                val status = line(input, deadline) ?: throw IOException("no status line")
                code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status line")
                headers = generateSequence { line(input, deadline)?.takeIf { it.isNotEmpty() } }
                    .take(200).mapNotNull { h -> h.indexOf(':').takeIf { it > 0 }?.let { h.substring(0, it).trim() to h.substring(it + 1).trim() } }.toList()
            } while (code in 100..199)
            fun header(name: String) = headers.firstOrNull { it.first.equals(name, true) }?.second
            val bytes = when {
                code == 204 || code == 304 -> ByteArray(0)
                header("Transfer-Encoding")?.contains("chunked", true) == true -> chunked(input, deadline)
                else -> header("Content-Length")?.toLongOrNull()?.let { exactly(input, it, deadline) } ?: rest(input, deadline)
            }
            val cs = header("Content-Type")?.let { Regex("(?i)charset=\"?([\\w.:-]+)").find(it)?.groupValues?.get(1) }
                ?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8
            val text = String(bytes, cs).take(MAX_CHARS)
            return answer(code, header("Location"), headers.filter { it.first.equals("Set-Cookie", true) }.map { it.second }, text, cookies)
        }
    }

    private fun due(deadline: Long) { if (SystemClock.elapsedRealtime() > deadline) throw SocketTimeoutException("page took too long") }

    /** One header line without its line end, null at the end of the stream. */
    private fun line(input: InputStream, deadline: Long): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            due(deadline)
            val c = input.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("ISO-8859-1")
            if (c == '\n'.code) return b.toString("ISO-8859-1").trimEnd('\r')
            if (b.size() >= 8_192) throw IOException("header line too long")
            b.write(c)
        }
    }

    /** Up to [count] bytes (at most [MAX_BYTES]). */
    private fun exactly(input: InputStream, count: Long, deadline: Long): ByteArray {
        val want = count.coerceIn(0, MAX_BYTES.toLong()).toInt()
        val b = ByteArray(want)
        var got = 0
        while (got < want) {
            due(deadline)
            val r = input.read(b, got, want - got)
            if (r < 0) break
            got += r
        }
        return b.copyOf(got)
    }

    /** All bytes until the server closes (at most [MAX_BYTES]). */
    private fun rest(input: InputStream, deadline: Long): ByteArray {
        val b = ByteArrayOutputStream()
        val buf = ByteArray(8_192)
        while (b.size() < MAX_BYTES) {
            due(deadline)
            val r = input.read(buf, 0, minOf(buf.size, MAX_BYTES - b.size()))
            if (r < 0) break
            b.write(buf, 0, r)
        }
        return b.toByteArray()
    }

    /** A body sent in chunks (at most [MAX_BYTES]). */
    private fun chunked(input: InputStream, deadline: Long): ByteArray {
        val b = ByteArrayOutputStream()
        while (b.size() < MAX_BYTES) {
            val size = line(input, deadline)?.substringBefore(';')?.trim()?.toLongOrNull(16) ?: break
            if (size <= 0) break
            val part = exactly(input, minOf(size, (MAX_BYTES - b.size()).toLong()), deadline)
            b.write(part)
            if (part.size.toLong() < size) break
            line(input, deadline) // the line end after the chunk
        }
        return b.toByteArray()
    }
}
