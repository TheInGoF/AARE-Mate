package net.prototux.aare

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Where the large files lie (System L-080, 21.11.2026: "Die App muss anbieten Maps etc. auf sd Karte auszulagern. Bei
 * heutigen Geräten egal. Aber hier ein Muss"): the offline maps ([MAPS]) and the speech models ([SPEECH]) either in the
 * device's own storage or in Mate's folder on a memory card (Android/data/<app>/files on the card - no permission
 * needed, removed with the app). One choice for both; the files move along when it changes ([move]).
 *
 * A card formatted FAT32 takes no file of 4 GB or more (the whole map package is one file of ~10 GB): [Volume.fat32]
 * and [problem] say so before anything is copied. Android formats cards above 32 GB as exFAT, which has no such limit.
 * While the chosen card is out, [dir] still names the card's folder (nothing silently lands in the device's storage);
 * [cardMissing] tells the pages.
 */
internal object Storage {
    const val MAPS = "regionen"
    const val SPEECH = "sprache"
    private const val TAG = "MateStorage"
    /** The largest file FAT32 takes (4 GiB - 1). */
    const val FAT32_MAX = 4L * 1024 * 1024 * 1024 - 1

    /** A place for the files: the device's own storage ([id] "") or a card ([id] its volume UUID). */
    class Volume(val id: String, val label: String, val root: File, val removable: Boolean, val free: Long, val total: Long, val fat32: Boolean) {
        val internal get() = id.isEmpty()
    }

    // a file of its own, not "settings": a transfer to another device must not carry the card's UUID along
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("storage", Context.MODE_PRIVATE)

    /** The chosen place's id ("" = the device's storage). */
    fun chosen(c: Context): String = prefs(c).getString("volume", "") ?: ""

    /** The device's own folder of [kind] - where the files always lay before L-080. */
    fun internalDir(c: Context, kind: String): File =
        if (kind == MAPS) File(c.filesDir, MAPS) else File(c.getExternalFilesDir(null) ?: c.filesDir, kind)

    /** The folder of [kind] in the chosen place (on a card even while it is out - see [cardMissing]). */
    fun dir(c: Context, kind: String): File {
        val id = chosen(c)
        if (id.isEmpty()) return internalDir(c, kind)
        val root = volumes(c).firstOrNull { it.id == id }?.root ?: prefs(c).getString("root", null)?.let(::File)
        return if (root != null) File(root, kind) else internalDir(c, kind)
    }

    /** A card is chosen but not there (taken out, not mounted): its maps and voices are missing until it is back. */
    fun cardMissing(c: Context): Boolean = chosen(c).let { id -> id.isNotEmpty() && volumes(c).none { it.id == id } }

    /** The device's storage first, then each card that is mounted. */
    fun volumes(c: Context): List<Volume> {
        val app = c.applicationContext
        val sm = app.getSystemService(StorageManager::class.java)
        val inner = app.filesDir
        val out = mutableListOf(Volume("", app.getString(R.string.storage_internal), inner, false, freeOf(inner), totalOf(inner), false))
        app.getExternalFilesDirs(null).drop(1).filterNotNull().forEach { f ->
            val v = runCatching { sm.getStorageVolume(f) }.getOrNull() ?: return@forEach
            if (v.state != Environment.MEDIA_MOUNTED || v.isPrimary) return@forEach
            val uuid = v.uuid ?: return@forEach
            out += Volume(uuid, v.getDescription(app), f, v.isRemovable, freeOf(f), totalOf(f), fsType(uuid) == "vfat")
        }
        return out
    }

    private fun freeOf(f: File) = runCatching { StatFs(f.path).availableBytes }.getOrDefault(0L)
    private fun totalOf(f: File) = runCatching { StatFs(f.path).totalBytes }.getOrDefault(0L)

    /** The file system of the card [uuid] as the kernel mounted it ("vfat", "exfat", …), null when not to be seen. */
    internal fun fsType(uuid: String): String? = runCatching {
        File("/proc/self/mounts").readLines().firstNotNullOfOrNull { mountType(it, uuid) }
    }.getOrNull()

    /** The type in a line of /proc/mounts when it mounts the card [uuid] itself (/mnt/media_rw/<uuid>), else null. */
    internal fun mountType(line: String, uuid: String): String? {
        val p = line.split(' ')
        return if (p.size >= 3 && p[1] == "/mnt/media_rw/$uuid") p[2] else null
    }

    /** The bytes of [kind] in the chosen place. */
    fun bytes(c: Context, kind: String): Long = sizeOf(dir(c, kind))
    private fun sizeOf(d: File) = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    private fun largest(d: File) = d.walkTopDown().filter { it.isFile }.maxOfOrNull { it.length() } ?: 0L

    /** Why [to] cannot take the files of now, null when it can: too little room, or a file too large for FAT32. */
    sealed class Problem {
        class NoRoom(val need: Long, val free: Long) : Problem()
        class TooLarge(val bytes: Long) : Problem()
    }

    fun problem(c: Context, to: Volume): Problem? {
        val from = listOf(MAPS, SPEECH).map { dir(c, it) }
        val big = from.maxOf { largest(it) }
        if (to.fat32 && big > FAT32_MAX) return Problem.TooLarge(big)
        val need = from.sumOf { sizeOf(it) }
        // a little room left over for the system and the next update
        if (need + 200L * 1024 * 1024 > to.free) return Problem.NoRoom(need, to.free)
        return null
    }

    /** Whether a file of [bytes] fits where [kind] lies (FAT32 on a card), for loading a new map or model. */
    fun takes(c: Context, kind: String, bytes: Long): Boolean {
        if (bytes <= FAT32_MAX) return true
        val id = chosen(c)
        return id.isEmpty() || volumes(c).firstOrNull { it.id == id }?.fat32 != true
    }

    /** A move under way: bytes done and in all, and the phase. */
    enum class Phase { COPY, CHECK }

    /**
     * Moves the maps and speech models to [to] and makes it the chosen place: everything is copied and checked (size,
     * and the SHA-256 kept beside a map file) before the old files go; on any error the copies are dropped and the old
     * place stays chosen. Blocking - off the main thread.
     */
    fun move(c: Context, to: Volume, progress: (Phase, Long, Long) -> Unit) {
        val app = c.applicationContext
        // the same check the page shows, here too: nothing is copied that cannot fit
        problem(app, to)?.let { throw IOException(if (it is Problem.TooLarge) "FAT32" else "room") }
        val pairs = listOf(MAPS, SPEECH).map { dir(app, it) to target(app, to, it) }.filter { (a, b) -> a.exists() && a.canonicalPath != b.canonicalPath }
        val files = pairs.flatMap { (a, b) -> a.walkTopDown().filter { it.isFile }.map { it to File(b, it.relativeTo(a).path) }.toList() }
        val total = files.sumOf { it.first.length() }
        Log.i(TAG, "move to ${if (to.internal) "device storage" else "card"}: ${files.size} files, $total bytes")
        var done = 0L
        try {
            for ((src, dst) in files) {
                dst.parentFile?.mkdirs()
                src.inputStream().use { i -> dst.outputStream().use { o ->
                    val buf = ByteArray(1 shl 20)
                    while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n; progress(Phase.COPY, done, total) }
                    o.fd.sync()
                } }
                if (dst.length() != src.length()) throw IOException("size")
                dst.setLastModified(src.lastModified())
            }
            // the map files are read back from the new place and checked against the checksum they were loaded with
            val marked = files.filter { (src, _) -> File(src.path + ".sha256").isFile }
            val checkTotal = marked.sumOf { it.first.length() }
            var checked = 0L
            for ((src, dst) in marked) {
                val want = File(src.path + ".sha256").readText().trim()
                if (sha256(dst) { checked += it; progress(Phase.CHECK, checked, checkTotal) } != want) throw IOException("checksum")
            }
        } catch (e: Exception) {
            Log.w(TAG, "move failed (${e.javaClass.simpleName}: ${e.message}), the copies go, nothing changed")
            pairs.forEach { (_, b) -> b.deleteRecursively() }
            throw e
        }
        prefs(app).edit().putString("volume", to.id).putString("root", to.root.path).commit()
        pairs.forEach { (a, _) -> a.deleteRecursively() }
        Log.i(TAG, "moved, the old files are gone")
    }

    private fun target(c: Context, to: Volume, kind: String) = if (to.internal) internalDir(c, kind) else File(to.root, kind)

    private fun sha256(f: File, read: (Int) -> Unit): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n); read(n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
