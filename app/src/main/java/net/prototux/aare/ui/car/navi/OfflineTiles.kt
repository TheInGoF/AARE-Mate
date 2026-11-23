package net.prototux.aare.ui.car.navi

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The map without the net from all region packages at once (21.11.2026: "ich möchte eigentlich wieder einzelne regionen
 * laden können um auch einzelne regionen updaten zu können und nicht immer einen 10gb brocken zu bewegen"): MapLibre reads
 * one source, a package is one PMTiles file - so Mate serves the tiles itself, on 127.0.0.1 only, each from the first
 * package that has it (RegionPacks.anyTile). The map server cuts every tile that touches a region's border whole into
 * that package, so a tile at a border is the same in both neighbours: no gaps between regions, nothing drawn twice.
 * Started on first use, kept for the process; an empty answer (204) where no package has the tile.
 */
internal object OfflineTiles {
    private const val TAG = "MateNavi"
    @Volatile private var server: ServerSocket? = null
    @Volatile private var sawTile = false
    @Volatile private var sawEmpty = false
    // a thread for each connection MapLibre keeps open (21.11.2026: four threads, each held by an idle keep-alive
    // connection for up to 20 s - the next requests waited until MapLibre gave up: "Socket closed", blank tiles)
    private val pool = java.util.concurrent.ThreadPoolExecutor(0, MAX_CONNECTIONS, 30, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.SynchronousQueue()) { r -> Thread(r, "offline-tiles").apply { isDaemon = true } }
    private const val MAX_CONNECTIONS = 32

    /** The tile URL for the style's vector source. */
    fun url(context: Context): String = "http://127.0.0.1:${port(context)}/t/{z}/{x}/{y}.mvt"

    @Synchronized private fun port(context: Context): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val app = context.applicationContext
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = s
        Thread({
            var failedAt = 0L
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrElse { e ->
                    if (s.isClosed) break
                    // a lasting failure (too many open files): not round and round at full load, said once a minute
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - failedAt > 60_000L) { failedAt = now; Log.w(TAG, "offline tiles: accept failed", e) }
                    Thread.sleep(200)
                    null
                } ?: continue
                // more connections than threads: this one is closed at once, the client asks anew (no queue to wait in)
                runCatching { pool.execute { runCatching { serve(app, c) } } }.onFailure { runCatching { c.close() } }
            }
        }, "offline-tiles-accept").apply { isDaemon = true }.start()
        Log.i(TAG, "offline tiles on port ${s.localPort}")
        return s.localPort
    }

    private val PATH = Regex("""^GET /t/(\d{1,2})/(\d+)/(\d+)\.mvt(?:\?\S*)? HTTP/1\.[01]$""")

    /** One connection, kept open for the next request (MapLibre asks many tiles in a row). */
    private fun serve(app: Context, c: Socket) { c.use { sock ->
        sock.soTimeout = 20_000
        val inp = BufferedInputStream(sock.getInputStream())
        val out = sock.getOutputStream()
        while (true) {
            val first = line(inp) ?: return
            var close = first.endsWith("1.0")
            while (true) {                                  // the headers: only "Connection: close" matters
                val h = line(inp) ?: return
                if (h.isEmpty()) break
                if (h.startsWith("connection:", ignoreCase = true) && h.contains("close", ignoreCase = true)) close = true
            }
            val m = PATH.matchEntire(first)
            val body = m?.let { RegionPacks.anyTile(app, it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
            val head = when {
                m == null -> "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n"
                body == null -> "HTTP/1.1 204 No Content\r\n"
                else -> "HTTP/1.1 200 OK\r\nContent-Type: application/x-protobuf\r\nContent-Length: ${body.size}\r\n"
            } + "Cache-Control: no-store\r\nConnection: ${if (close) "close" else "keep-alive"}\r\n\r\n"
            // the first tile served and the first without a package, once per process: the log shows the service works
            if (m != null && (if (body != null) !sawTile else !sawEmpty)) {
                if (body != null) sawTile = true else sawEmpty = true
                Log.i(TAG, "offline tiles: first ${if (body != null) "tile served (${body.size} bytes)" else "tile without a package"} at zoom ${m.groupValues[1]}")
            }
            out.write(head.toByteArray(Charsets.US_ASCII))
            body?.let { out.write(it) }
            out.flush()
            if (close) return
        }
    } }

    /** A line up to CRLF, null at the end of the stream; at most 8 KB. */
    private fun line(i: InputStream): String? {
        val b = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return if (b.isEmpty()) null else b.toString()
            if (c == '\n'.code) return b.toString().trimEnd('\r')
            if (b.length > 8192) return null
            b.append(c.toChar())
        }
    }
}
