package net.prototux.aare.ui.car.navi

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Region packages for driving without the net (server handover 16.11.2026, "Regionspakete (offline)", like OsmAnd): the
 * catalogue <base>/pakete/katalog.json, regions as a tree by their parent, each with files - "karte" (PMTiles, Protomaps v4)
 * and "grenze" (GeoJSON of what the package covers); unknown kinds and fields are skipped, an unknown format version means
 * the catalogue is not used. A file loads into <name>.part, goes on with "Range" after a break, is checked by SHA-256 and
 * only then takes its name. Kept in regionen/<id>/ of the place Storage names (device storage or memory card, L-080).
 */
internal object RegionPacks {
    const val FORMAT = 1
    /**
     * The one package the map server builds from its whole selection (Navi E-018: "immer Gesamtpakete bauen" - the
     * borders of all chosen regions joined, nothing twice, no holes at the borders between them). Once it is on the
     * device it is the only map drawn from, and the single regions loaded before it are dropped.
     */
    const val GESAMT = "gesamt"
    private val KNOWN = setOf("karte", "grenze")
    private const val DEV_PACKS = "http://127.0.0.1:8091"

    class PackFile(val kind: String, val path: String, val bytes: Long, val sha256: String)
    /** [parent] is the Geofabrik id of the region above, which need not be in the catalogue; [path] the names of the ancestors, top first ("Europe", "Germany"), for grouping. */
    /** [contains]: for the whole package (E-018) the names of the regions chosen at the server ("enthaelt"). */
    class Region(val id: String, val name: String, val parent: String?, val bbox: List<Double>, val files: List<PackFile>, val path: List<String> = emptyList(),
                 val contains: List<String> = emptyList(), val mapDate: String = "") {
        val bytes get() = files.sumOf { it.bytes }
    }
    class Catalogue(val mapDate: String, val regions: List<Region>)

    // the build PC over adb only in debug builds (as NaviSearch): a release without a map server has no catalogue
    private fun base(context: Context): String? =
        net.prototux.aare.Settings(context).mapServer.ifBlank { null }?.let { "$it/pakete" } ?: DEV_PACKS.takeIf { net.prototux.aare.BuildConfig.DEBUG }

    /** A map server to ask is set (or the build PC's in a debug build). */
    fun hasServer(context: Context): Boolean = base(context) != null

    fun dir(context: Context, id: String) = File(root(context), id)

    /** Where the packages lie: the device's storage or the memory card (Storage, System L-080). */
    fun root(context: Context) = net.prototux.aare.Storage.dir(context, net.prototux.aare.Storage.MAPS)

    /** The catalogue, or null when it cannot be read or is of a format this app does not know. */
    suspend fun catalogue(context: Context): Catalogue? = withContext(Dispatchers.IO) {
        val b = base(context) ?: return@withContext null
        runCatching {
            NaviServer.client(context).newCall(Request.Builder().url("$b/katalog.json").build()).execute().use { r ->
                android.util.Log.i("MateNavi", "catalogue: HTTP ${r.code}")
                // reached, but no catalogue yet (the server has not built its packages): an empty one, not "no connection"
                if (r.code == 404) return@runCatching Catalogue("", emptyList())
                if (!r.isSuccessful) return@runCatching null
                parse(r.body.string())
            }
        }.getOrNull()
    }

    fun parse(json: String): Catalogue? {
        val o = JSONObject(json)
        if (o.optInt("format") != FORMAT) return null
        val a = o.optJSONArray("regionen") ?: return null
        val regions = (0 until a.length()).mapNotNull { i ->
            val r = a.optJSONObject(i) ?: return@mapNotNull null
            // Geofabrik ids (germany, baden-wuerttemberg, …); the id is also the folder on the device - nothing else let in
            val id = r.optString("id").takeIf { it.isNotBlank() && it.matches(Regex("[a-z0-9_-]+")) } ?: return@mapNotNull null
            val f = r.optJSONArray("dateien")
            val files = (0 until (f?.length() ?: 0)).mapNotNull { j ->
                val d = f!!.optJSONObject(j) ?: return@mapNotNull null
                val kind = d.optString("art")
                val path = d.optString("pfad")
                // unknown kinds (routing, search later) are skipped; a path must stay inside /pakete
                if (kind !in KNOWN || path.isBlank() || path.contains("..")) null
                else PackFile(kind, path, d.optLong("bytes"), d.optString("sha256").lowercase())
            }
            val bb = r.optJSONArray("bbox")
            val pf = r.optJSONArray("pfad")
            val en = r.optJSONArray("enthaelt")
            Region(id, r.optString("name", id), r.optString("eltern").takeIf { !r.isNull("eltern") && it.isNotBlank() },
                (0 until (bb?.length() ?: 0)).map { bb!!.getDouble(it) }, files,
                (0 until (pf?.length() ?: 0)).mapNotNull { pf!!.optString(it).ifBlank { null } },
                (0 until (en?.length() ?: 0)).mapNotNull { en!!.optString(it).ifBlank { null } }, r.optString("stand"))
        }
        return Catalogue(o.optString("stand_karte"), regions)
    }

    /** The whole package of a catalogue (E-018), null while the server has not built one. */
    fun whole(c: Catalogue): Region? = c.regions.firstOrNull { it.id == GESAMT }

    /** The single regions of a catalogue (since 21.11.2026 the server builds each region on its own again). */
    fun singles(c: Catalogue): List<Region> = c.regions.filter { it.id != GESAMT && it.files.isNotEmpty() }

    /** Package folders on the device that the catalogue no longer has (the old whole package, regions dropped at the server). */
    fun orphans(context: Context, c: Catalogue): List<String> = root(context).listFiles().orEmpty()
        .filter { it.isDirectory && c.regions.none { r -> r.id == it.name } }.map { it.name }.sorted()

    fun deleteDir(context: Context, id: String) { if (id.matches(Regex("[a-z0-9_-]+"))) dir(context, id).deleteRecursively() }

    /** When the files of [r] on the device were loaded (the newest of them), null when none are there. */
    fun loadedAt(context: Context, r: Region): Long? =
        r.files.mapNotNull { File(dir(context, r.id), local(it)).takeIf { f -> f.isFile }?.lastModified() }.maxOrNull()

    /** What is on the device for [r]: nothing, complete and current, or complete but older than the catalogue. */
    enum class State { NONE, PARTLY, CURRENT, UPDATE }

    fun state(context: Context, r: Region): State {
        if (r.files.isEmpty()) return State.NONE
        val d = dir(context, r.id)
        val here = r.files.count { File(d, local(it)).isFile }
        if (here == 0) return if (r.files.any { File(d, local(it) + ".part").isFile }) State.PARTLY else State.NONE
        if (here < r.files.size) return State.PARTLY
        // the checksum of the file as loaded is kept beside it: a new one in the catalogue means an update
        return if (r.files.all { File(d, local(it) + ".sha256").readTextOrNull() == it.sha256 }) State.CURRENT else State.UPDATE
    }

    private fun local(f: PackFile) = f.path.substringAfterLast('/')
    private fun File.readTextOrNull() = runCatching { readText().trim() }.getOrNull()

    /**
     * Loads all files of [r] (again, if their checksum changed); [progress] gets bytes done and in all. Throws on a broken
     * connection (the .part stays and the next try goes on from there) and on a wrong checksum (the .part is dropped).
     */
    suspend fun download(context: Context, r: Region, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val b = base(context) ?: throw java.io.IOException("no map server")
        val d = dir(context, r.id).apply { mkdirs() }
        File(d, "bbox.json").writeText(org.json.JSONArray(r.bbox).toString())
        val total = r.bytes
        var before = 0L
        // a card formatted FAT32 takes no file of 4 GB or more (L-080): said before loading, not after gigabytes
        r.files.firstOrNull { !net.prototux.aare.Storage.takes(context, net.prototux.aare.Storage.MAPS, it.bytes) }?.let { throw java.io.IOException("FAT32") }
        if (net.prototux.aare.Storage.cardMissing(context)) throw java.io.IOException("card")
        for (f in r.files) {
            val target = File(d, local(f))
            val mark = File(d, local(f) + ".sha256")
            if (target.isFile && mark.readTextOrNull() == f.sha256) { before += f.bytes; progress(before, total); continue }
            val part = File(d, local(f) + ".part")
            var have = if (part.isFile) part.length() else 0L
            // a .part as long as the file (Mate ended between the last byte and the check): checked as it is - asked
            // from its end the server answers 416 again and again
            var checked = false
            var fetch = !(f.bytes > 0 && have >= f.bytes)
            while (fetch) {
                fetch = false
                val req = Request.Builder().url("$b/" + f.path).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
                NaviServer.client(context).newCall(req).execute().use { resp ->
                    if (resp.code == 416 && have > 0) {
                        // nothing after the .part's end: complete, or not this file any more - loaded anew without "Range"
                        if (sha256(part) == f.sha256) { checked = true; return@use }
                        android.util.Log.w("MateNavi", "region package ${r.id}: ${local(f)}.part refused (416) and not the file - loaded anew")
                        part.delete(); have = 0L; fetch = true; return@use
                    }
                    val resume = resp.code == 206
                    if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                    FileOutputStream(part, resume).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var done = if (resume) have else 0L
                        resp.body.byteStream().use { inp ->
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = inp.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n
                                progress(before + done, total)
                            }
                        }
                    }
                }
            }
            if (!checked && sha256(part) != f.sha256) { part.delete(); throw java.io.IOException("SHA-256") }
            target.delete()
            if (!part.renameTo(target)) throw java.io.IOException("rename")
            mark.writeText(f.sha256)
            before += f.bytes
        }
        if (r.id == GESAMT && state(context, r) == State.CURRENT) dropSingles(context)
    }

    /** The single region packages of before the whole one (E-018): they would lie under it twice. */
    fun dropSingles(context: Context) {
        root(context).listFiles().orEmpty().filter { it.isDirectory && it.name != GESAMT }.forEach {
            android.util.Log.i("MateNavi", "region package ${it.name} dropped: the whole package replaces it")
            it.deleteRecursively()
        }
    }

    fun delete(context: Context, r: Region) { dir(context, r.id).deleteRecursively() }

    /**
     * A package's map on the device: the PMTiles file, the box it covers [west, south, east, north] and, when its
     * "grenze" was loaded, the border it was cut along (polygons of rings of lon/lat) - the box alone also holds land
     * outside the border, where the package has only the coarse tiles (I-032).
     */
    class LocalMap(val file: File, val bbox: List<Double>, val id: String = file.parentFile?.name ?: "", val border: List<List<List<DoubleArray>>>? = null) {
        fun inBox(lon: Double, lat: Double) = bbox.size == 4 && lon in bbox[0]..bbox[2] && lat in bbox[1]..bbox[3]
        fun covers(lon: Double, lat: Double) = inBox(lon, lat) && (border == null || border.any { poly ->
            inRing(lon, lat, poly[0]) && poly.drop(1).none { inRing(lon, lat, it) } })
        val area get() = if (bbox.size == 4) (bbox[2] - bbox[0]) * (bbox[3] - bbox[1]) else Double.MAX_VALUE
    }

    private fun inRing(lon: Double, lat: Double, ring: List<DoubleArray>): Boolean {
        var inside = false
        for (i in 0 until ring.size - 1) {
            val (x1, y1) = ring[i].let { it[0] to it[1] }; val (x2, y2) = ring[i + 1].let { it[0] to it[1] }
            if ((y1 > lat) != (y2 > lat) && lon < (x2 - x1) * (lat - y1) / (y2 - y1) + x1) inside = !inside
        }
        return inside
    }

    /** The polygons of a GeoJSON Feature, FeatureCollection, Polygon or MultiPolygon; null when there are none. */
    fun parseBorder(json: String): List<List<List<DoubleArray>>>? = runCatching {
        fun ring(a: org.json.JSONArray) = (0 until a.length()).map { i -> a.getJSONArray(i).let { doubleArrayOf(it.getDouble(0), it.getDouble(1)) } }
        fun polygon(a: org.json.JSONArray) = (0 until a.length()).map { ring(a.getJSONArray(it)) }
        fun geometry(g: JSONObject): List<List<List<DoubleArray>>> = when (g.optString("type")) {
            "Polygon" -> listOf(polygon(g.getJSONArray("coordinates")))
            "MultiPolygon" -> g.getJSONArray("coordinates").let { a -> (0 until a.length()).map { polygon(a.getJSONArray(it)) } }
            "Feature" -> g.optJSONObject("geometry")?.let(::geometry).orEmpty()
            "FeatureCollection" -> g.getJSONArray("features").let { a -> (0 until a.length()).flatMap { geometry(a.getJSONObject(it)) } }
            else -> emptyList()
        }
        geometry(JSONObject(json)).filter { it.isNotEmpty() && it[0].size >= 4 }.ifEmpty { null }
    }.getOrNull()

    /** The complete map files on the device, for drawing without the net (pmtiles://file://…). */
    fun maps(context: Context): List<LocalMap> = root(context).listFiles().orEmpty().mapNotNull { d ->
        val f = File(d, "karte.pmtiles").takeIf { it.isFile } ?: return@mapNotNull null
        val bb = runCatching { org.json.JSONArray(File(d, "bbox.json").readText()) }.getOrNull()
        val border = File(d, "grenze.geojson").takeIf { it.isFile }?.let { runCatching { parseBorder(it.readText()) }.getOrNull() }
        LocalMap(f, (0 until (bb?.length() ?: 0)).map { bb!!.getDouble(it) }, d.name, border)
    }

    fun mapFor(context: Context, lon: Double, lat: Double): LocalMap? = pick(maps(context), lon, lat)

    /**
     * A tile from all packages at once (OfflineTiles, 21.11.2026 single regions again): the packages that hold the tile's
     * middle first (the smallest first), then the others whose box touches the tile, the old whole package last; the
     * first that has the tile gives it. A tile at a border is whole in each neighbour, so any of them is right.
     */
    fun anyTile(context: Context, z: Int, x: Int, y: Int): ByteArray? {
        val all = cachedMaps(context).ifEmpty { return null }
        val n = 1 shl z
        fun lonOf(tx: Double) = tx / n * 360.0 - 180.0
        fun latOf(ty: Double) = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * ty / n))))
        val lon = lonOf(x + 0.5); val lat = latOf(y + 0.5)
        val w = lonOf(x.toDouble()); val e = lonOf(x + 1.0); val north = latOf(y.toDouble()); val s = latOf(y + 1.0)
        val touching = all.filter { m -> m.bbox.size != 4 || (m.bbox[0] <= e && m.bbox[2] >= w && m.bbox[1] <= north && m.bbox[3] >= s) }
        val order = touching.sortedWith(compareBy<LocalMap>({ it.id == GESAMT }, { !it.covers(lon, lat) }, { it.area }))
        for (m in order) reader(m)?.let { r -> runCatching { r.tile(z, x, y) }.getOrNull()?.let { return it } }
        return null
    }

    // a package opened outside any lock (22.11.2026, log of 2.4.1: four tile threads waited 540 ms while one opened the
    // packages and looked at the folder - in the guidance, where new tiles are needed all the time, the map waited); two
    // threads opening the same package at once keep the first and close the other
    private fun reader(m: LocalMap): PmTiles? {
        val key = m.file.absolutePath + "@" + m.file.lastModified()
        readers[key]?.let { return it }
        // a broken file is not opened again for every tile (each try left a file descriptor open until collected)
        if (key in broken) return null
        val opened = runCatching { PmTiles(m.file) }.onFailure {
            if (broken.add(key)) android.util.Log.w("MateNavi", "region package ${m.id}: map file cannot be read - left out", it)
        }.getOrNull() ?: return null
        return readers.putIfAbsent(key, opened)?.also { opened.close() } ?: opened
    }

    private val readers = java.util.concurrent.ConcurrentHashMap<String, PmTiles>()
    /** Map files (path@time) that could not be opened; a new file has a new time and is tried again. */
    private val broken: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    @Volatile private var mapsKey = ""
    @Volatile private var mapsKept: List<LocalMap> = emptyList()
    private val looking = java.util.concurrent.locks.ReentrantLock()

    // the folder is looked at again at most every [MAPS_CHECK_MS] (21.11.2026: it was listed for every tile, with the
    // time of each package on the memory card, one tile after the other - the map waited)
    @Volatile private var mapsCheckedAt = 0L

    /** The packages there are; one thread looks at the folder, the others go on with the packages known so far. */
    private fun cachedMaps(context: Context): List<LocalMap> {
        if (mapsKey.isNotEmpty() && android.os.SystemClock.uptimeMillis() - mapsCheckedAt < MAPS_CHECK_MS) return mapsKept
        // before anything is known everyone waits for the first look; later nobody waits for the next one
        if (mapsKey.isEmpty()) looking.lock() else if (!looking.tryLock()) return mapsKept
        try {
            val now = android.os.SystemClock.uptimeMillis()
            if (mapsKey.isNotEmpty() && now - mapsCheckedAt < MAPS_CHECK_MS) return mapsKept
            val key = root(context).listFiles().orEmpty()
                .joinToString("|") { d -> d.path + ":" + File(d, "karte.pmtiles").lastModified() }.ifEmpty { "-" }
            if (key != mapsKey) {
                mapsKept = maps(context); mapsKey = key
                val old = readers.values.toList(); readers.clear(); old.forEach { it.close() }
            }
            mapsCheckedAt = now
            return mapsKept
        } finally { looking.unlock() }
    }
    private const val MAPS_CHECK_MS = 10_000L

    /**
     * The package to draw from at [lon]/[lat] without the net (one at a time): the whole package when it is there (E-018);
     * else the smallest whose border holds the place (its box alone is not enough, I-032), else the largest there is.
     */
    fun pick(all: List<LocalMap>, lon: Double, lat: Double): LocalMap? =
        all.firstOrNull { it.id == GESAMT }
            ?: all.filter { it.covers(lon, lat) }.minByOrNull { it.area }
            ?: all.maxByOrNull { if (it.area == Double.MAX_VALUE) 0.0 else it.area }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * The loads running, kept by the process and not by the page: leaving "Offline-Karten" does not stop them (Mate runs
     * as long as the phone is on). Progress 0..1 and an error text per region, read by the page.
     */
    object Loads {
        private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        /** The one load running; the others wait in [waiting] (O185: one after the other, for a weak Wi-Fi abroad). */
        private var current: Pair<String, kotlinx.coroutines.Job>? = null
        private val queue = ArrayDeque<Pair<Region, Context>>()
        val progress = androidx.compose.runtime.mutableStateMapOf<String, Float>()
        val waiting = androidx.compose.runtime.mutableStateListOf<String>()
        /** The running load: MB per second (over the last few seconds) and bytes done / in all (O186). */
        val speed = androidx.compose.runtime.mutableStateMapOf<String, Double>()
        val bytes = androidx.compose.runtime.mutableStateMapOf<String, Pair<Long, Long>>()
        val errors = androidx.compose.runtime.mutableStateMapOf<String, String>()
        /** Counts finished loads, so the page reads the states on the device anew. */
        val finished = androidx.compose.runtime.mutableIntStateOf(0)

        /** Puts [r] at the end of the queue; it loads when the ones before it are done. */
        @Synchronized fun start(context: Context, r: Region) {
            if (current?.first == r.id || r.id in waiting) return
            errors.remove(r.id)
            queue.addLast(r to context.applicationContext); waiting.add(r.id)
            if (current == null) next()
        }

        @Synchronized private fun next() {
            val (r, app) = queue.removeFirstOrNull() ?: run { current = null; return }
            waiting.remove(r.id); progress[r.id] = 0f
            current = r.id to scope.launch {
                // the speed over a sliding window of about 3 s, shown once a second (a weak Wi-Fi jumps a lot)
                val marks = ArrayDeque<Pair<Long, Long>>()
                var shownAt = 0L
                runCatching { download(app, r) { done, all ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - shownAt >= 1000 || done == all) {
                        shownAt = now
                        progress[r.id] = if (all > 0) done.toFloat() / all else 0f
                        bytes[r.id] = done to all
                        marks.addLast(now to done); while (marks.size > 1 && now - marks.first().first > 3000) marks.removeFirst()
                        val (t0, b0) = marks.first()
                        if (now > t0) speed[r.id] = (done - b0) / 1_000_000.0 / ((now - t0) / 1000.0)
                    }
                } }
                    .onFailure { e ->
                        if (e !is kotlinx.coroutines.CancellationException) errors[r.id] = app.getString(
                            when (e.message) {
                                "SHA-256" -> net.prototux.aare.R.string.regions_bad_sum
                                "FAT32" -> net.prototux.aare.R.string.storage_fat32_load
                                "card" -> net.prototux.aare.R.string.storage_card_missing
                                else -> net.prototux.aare.R.string.regions_broken
                            })
                    }
                progress.remove(r.id); speed.remove(r.id); bytes.remove(r.id); finished.intValue++
                synchronized(this@Loads) { current = null; next() }
            }
        }

        /** Stops the running load (its .part stays for "Fortsetzen") or takes a waiting one out of the queue. */
        @Synchronized fun stop(id: String) {
            if (current?.first == id) { current?.second?.cancel(); return }
            queue.removeAll { it.first.id == id }; waiting.remove(id)
        }
    }
}
