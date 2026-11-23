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
    /** Mate Musik's downloaded titles, lists and covers (System L-086, 22.11.2026: "Musik muss ich auch auf der externen SD-Karte speichern können"). */
    const val MUSIC = "musik"
    /**
     * Two places of their own (23.11.2026: "Zusätzlich ein extra Speicherort einbauen, damit man die getrennt verlinken
     * kann"): the maps with the speech models (page "Offline-Karten"), the music on its own (page "Musik").
     */
    val MAP_KINDS = listOf(MAPS, SPEECH)
    private const val TAG = "MateStorage"
    /** The largest file FAT32 takes (4 GiB - 1). */
    const val FAT32_MAX = 4L * 1024 * 1024 * 1024 - 1

    /** A place for the files: the device's own storage ([id] "") or a card ([id] its volume UUID). */
    class Volume(val id: String, val label: String, val root: File, val removable: Boolean, val free: Long, val total: Long, val fat32: Boolean) {
        val internal get() = id.isEmpty()
    }

    // a file of its own, not "settings": a transfer to another device must not carry the card's UUID along
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("storage", Context.MODE_PRIVATE)

    /** The chosen place's id ("" = the device's storage) of the maps and speech models. */
    fun chosen(c: Context): String = chosen(c, MAPS)

    /** The chosen place of [kind]'s group; the music follows the maps' place until it has one of its own. */
    fun chosen(c: Context, kind: String): String {
        val p = prefs(c)
        if (kind == MUSIC) settleMusic(c)
        return (if (kind == MUSIC && p.contains("volume_music")) p.getString("volume_music", "") else p.getString("volume", "")) ?: ""
    }

    /**
     * The music gets a place of its own once, where its titles actually lie (23.11.2026: "Musik ist gelöscht"): it followed
     * the maps' place, so maps moved to the card before the music could move (L-080 before L-086) left the titles in the
     * device's storage while Mate looked for them on the card - and loaded them anew there. The place with more titles wins;
     * the other copy is left alone.
     */
    private fun settleMusic(c: Context) {
        val p = prefs(c)
        if (p.contains("volume_music")) return
        // nothing subscribed: what lies in the device is left over from before (deleted while Mate looked on the card), not revived
        if (net.prototux.aare.music.Offline.lists(c).isEmpty()) return
        val maps = p.getString("volume", "") ?: ""
        if (maps.isEmpty()) return                       // both in the device's storage: nothing to settle yet
        val root = volumes(c).firstOrNull { it.id == maps }?.root ?: p.getString("root", null)?.let(::File)
        val inner = titles(internalDir(c, MUSIC))
        val card = root?.let { titles(File(it, MUSIC)) } ?: 0L
        val place = if (inner > card) "" else maps
        Log.i(TAG, "music's own place: ${if (place.isEmpty()) "device storage" else "card"} (titles: device $inner, card $card bytes)")
        p.edit().putString("volume_music", place).putString("root_music", if (place.isEmpty()) null else root?.path).commit()
    }

    private fun titles(d: File) = File(d, "t").listFiles()?.sumOf { it.length() } ?: 0L

    /** The device's own folder of [kind] - where the files always lay before L-080. */
    fun internalDir(c: Context, kind: String): File = when (kind) {
        MAPS -> File(c.filesDir, MAPS)
        MUSIC -> File(c.noBackupFilesDir, "music")          // where the downloads always lay
        else -> File(c.getExternalFilesDir(null) ?: c.filesDir, kind)
    }

    /** The folder of [kind] in the chosen place (on a card even while it is out - see [cardMissing]). */
    fun dir(c: Context, kind: String): File {
        if (kind == MUSIC) musicFolder(c)?.let { return it }
        val id = chosen(c, kind)
        if (id.isEmpty()) return internalDir(c, kind)
        val p = prefs(c)
        val savedRoot = if (kind == MUSIC && p.contains("volume_music")) p.getString("root_music", null) else p.getString("root", null)
        val root = volumes(c).firstOrNull { it.id == id }?.root ?: savedRoot?.let(::File)
        return if (root != null) File(root, kind) else internalDir(c, kind)
    }

    /** A card is chosen but not there (taken out, not mounted): its maps and voices are missing until it is back. */
    fun cardMissing(c: Context): Boolean = cardMissing(c, MAPS)

    /** As [cardMissing], for [kind]'s group. */
    fun cardMissing(c: Context, kind: String): Boolean = chosen(c, kind).let { id -> id.isNotEmpty() && volumes(c).none { it.id == id } }

    /**
     * Mate Musik's own folder, chosen freely (23.11.2026: "ich möchte auf der SD-Karte wirklich einen Ordner anlegen können
     * für Mate Music"), e.g. "Mate Musik" on the card: seen by other apps, kept when Mate goes. Needs all-files access. Null:
     * the place of [chosen].
     */
    fun musicFolder(c: Context): File? = prefs(c).getString("music_folder", null)?.let(::File)

    /** Every place the music may lie in (the chosen folder, the device, Mate's folder on each card) - for deleting all. */
    fun musicPlaces(c: Context): List<File> = (listOfNotNull(musicFolder(c), internalDir(c, MUSIC)) +
        volumes(c).filter { !it.internal }.map { File(it.root, MUSIC) } +
        listOfNotNull(prefs(c).getString("root_music", null), prefs(c).getString("root", null)).map { File(it, MUSIC) }).distinctBy { it.absolutePath }

    /** The folder a document tree of the system's picker stands for ("primary:Music/Mate", "1234-ABCD:Mate Musik"), null if none. */
    fun folderOf(treeId: String, primary: File = Environment.getExternalStorageDirectory()): File? {
        val vol = treeId.substringBefore(':', "").ifEmpty { return null }
        val rest = treeId.substringAfter(':')
        val root = if (vol == "primary") primary else File("/storage", vol)
        return if (rest.isEmpty()) root else File(root, rest)
    }

    /**
     * Moves the music into [to] (a folder of the driver's, or null: back into the device) and makes it the music's place;
     * copied and checked first, the old files go after. Blocking.
     */
    fun moveMusic(c: Context, to: File?, progress: (Phase, Long, Long) -> Unit) {
        val app = c.applicationContext
        val from = dir(app, MUSIC)
        val target = to ?: internalDir(app, MUSIC)
        if (from.exists() && from.canonicalPath != target.canonicalPath) {
            target.mkdirs()
            if (!target.canWrite()) throw MoveFailed(MoveFailed.Why.NOT_WRITABLE)
            val need = sizeOf(from)
            if (need + 50L * 1024 * 1024 > target.usableSpace) throw MoveFailed(MoveFailed.Why.NO_ROOM)
            transfer(listOf(from to target), progress)
        }
        val e = prefs(app).edit()
        if (to != null) e.putString("music_folder", to.path)
        else e.remove("music_folder").putString("volume_music", "").remove("root_music")
        e.commit()
        if (from.exists() && from.canonicalPath != target.canonicalPath) {
            // only Mate's own subfolders: the driver's folder may hold more
            listOf("t", "a", "l").forEach { File(from, it).deleteRecursively() }
            if (from.list().isNullOrEmpty()) from.delete()
        }
        net.prototux.aare.music.Offline.forgetDir()
        Log.i(TAG, "music now in ${if (to == null) "device storage" else "its own folder"}")
    }

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
            // a card formatted by Android is called only "External" by it: named as a memory card, its own name in brackets
            val desc = v.getDescription(app)
            // ("External" alone, the system's word in every language, said as "Speicherkarte" - 21.11.2026 it read
            // "Speicherkarte (External)" in the German pages)
            val label = when {
                v.isRemovable && desc.trim().equals("External", ignoreCase = true) -> app.getString(R.string.storage_card_plain)
                v.isRemovable && !desc.contains("SD", ignoreCase = true) -> app.getString(R.string.storage_card, desc)
                else -> desc
            }
            out += Volume(uuid, label, f, v.isRemovable, freeOf(f), totalOf(f), fsType(uuid) == "vfat")
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

    /** Why a move stopped, for the page to word it (StorageMove.describe); nothing has changed then. */
    class MoveFailed(val why: Why) : ShownFailure(why.name, why.text) {
        enum class Why(@androidx.annotation.StringRes val text: Int) {
            NO_ROOM(R.string.storage_reason_room), TOO_LARGE(R.string.storage_reason_fat32),
            NOT_WRITABLE(R.string.storage_reason_not_writable), VERIFY(R.string.storage_reason_verify)
        }
    }

    fun problem(c: Context, to: Volume): Problem? {
        val from = MAP_KINDS.map { dir(c, it) }
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
        val id = chosen(c, kind)
        return id.isEmpty() || volumes(c).firstOrNull { it.id == id }?.fat32 != true
    }

    /** A move under way: bytes done and in all, and the phase. */
    enum class Phase { COPY, CHECK }

    /**
     * Moves the maps and speech models to [to] and makes it the chosen place: everything is copied and checked (size,
     * and the SHA-256 kept beside a map file) before the old files go; on any error the copies are dropped and the old
     * place stays chosen. Blocking - off the main thread. The music moves with [moveMusic] (its folder may be the driver's).
     */
    fun move(c: Context, to: Volume, progress: (Phase, Long, Long) -> Unit) {
        val app = c.applicationContext
        // the same check the page shows, here too: nothing is copied that cannot fit
        problem(app, to)?.let { throw MoveFailed(if (it is Problem.TooLarge) MoveFailed.Why.TOO_LARGE else MoveFailed.Why.NO_ROOM) }
        // the music followed the maps' place so far: it gets that place as its own before the maps move without it
        prefs(app).let { p -> if (!p.contains("volume_music"))
            p.edit().putString("volume_music", p.getString("volume", "") ?: "").putString("root_music", p.getString("root", null)).commit() }
        val pairs = MAP_KINDS.map { dir(app, it) to target(app, to, it) }.filter { (a, b) -> a.exists() && a.canonicalPath != b.canonicalPath }
        transfer(pairs, progress)
        prefs(app).edit().putString("volume", to.id).putString("root", to.root.path).commit()
        pairs.forEach { (a, _) -> a.deleteRecursively() }
        Log.i(TAG, "moved, the old files are gone")
    }

    private fun transfer(pairs: List<Pair<File, File>>, progress: (Phase, Long, Long) -> Unit) {
        val files = pairs.flatMap { (a, b) -> a.walkTopDown().filter { it.isFile }.map { it to File(b, it.relativeTo(a).path) }.toList() }
        val total = files.sumOf { it.first.length() }
        Log.i(TAG, "move: ${files.size} files, $total bytes")
        var done = 0L
        try {
            for ((src, dst) in files) {
                dst.parentFile?.mkdirs()
                src.inputStream().use { i -> dst.outputStream().use { o ->
                    val buf = ByteArray(1 shl 20)
                    while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n; progress(Phase.COPY, done, total) }
                    o.fd.sync()
                } }
                if (dst.length() != src.length()) throw MoveFailed(MoveFailed.Why.VERIFY)
                dst.setLastModified(src.lastModified())
            }
            // the map files are read back from the new place and checked against the checksum they were loaded with
            val marked = files.filter { (src, _) -> File(src.path + ".sha256").isFile }
            val checkTotal = marked.sumOf { it.first.length() }
            var checked = 0L
            for ((src, dst) in marked) {
                val want = File(src.path + ".sha256").readText().trim()
                if (sha256(dst) { checked += it; progress(Phase.CHECK, checked, checkTotal) } != want) throw MoveFailed(MoveFailed.Why.VERIFY)
            }
        } catch (e: Exception) {
            Log.w(TAG, "move failed (${e.javaClass.simpleName}: ${e.message}), the copies go, nothing changed")
            // only the copies made (a driver's folder may hold more)
            files.forEach { (_, dst) -> dst.delete() }
            throw e
        }
    }

    internal fun target(c: Context, to: Volume, kind: String) = if (to.internal) internalDir(c, kind) else File(to.root, kind)

    private fun sha256(f: File, read: (Int) -> Unit): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n); read(n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
