package net.prototux.aare.ui.car.navi

import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream

/**
 * Reads single tiles from a PMTiles v3 file on the device (the region package's karte.pmtiles), for the map without the
 * net: OfflineTiles serves them to MapLibre tile by tile, from all packages at once (RegionPacks.anyTile).
 * Header, root and leaf directories as in the PMTiles v3 spec; the directories are kept once read. Tiles come out as
 * they are meant to be drawn - a gzip'd tile is unpacked here, the web view would not do it.
 */
internal class PmTiles(private val file: File) {
    class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Long)

    private class Header(val rootOffset: Long, val rootLength: Int, val leafOffset: Long, val dataOffset: Long,
                         val internalCompression: Int, val tileCompression: Int, val minZoom: Int, val maxZoom: Int)

    /**
     * The file kept open (21.11.2026: opened anew for each read, on the memory card each tile cost several openings and
     * the map's tiles came too late - blank squares in the dashboard's near view); positional reads, safe from any thread.
     */
    private val opening = lazy { RandomAccessFile(file, "r").channel }
    private val channel: java.nio.channels.FileChannel by opening

    private val header: Header
    private val root: List<Entry>
    private val leaves = object : LinkedHashMap<Long, List<Entry>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Entry>>?) = size > 64
    }
    val minZoom get() = header.minZoom
    val maxZoom get() = header.maxZoom

    init {
        try {
            val h = read(0, 127)
            require(String(h, 0, 7, Charsets.US_ASCII) == "PMTiles" && h[7].toInt() == 3) { "not PMTiles v3" }
            header = Header(u64(h, 8), u64(h, 16).toInt(), u64(h, 40), u64(h, 56),
                h[97].toInt() and 0xFF, h[98].toInt() and 0xFF, h[100].toInt() and 0xFF, h[101].toInt() and 0xFF)
            root = directory(read(header.rootOffset, header.rootLength))
        } catch (e: Throwable) {
            // a broken file: not left open until collected
            if (opening.isInitialized()) runCatching { channel.close() }
            throw e
        }
    }

    /**
     * The tile z/x/y unpacked, or null when the file has none there. Safe from any thread and not one after the other
     * (22.11.2026: the whole read was locked, the tile threads of the offline map queued on the memory card); only the
     * cache of leaf directories is locked.
     */
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        if (z < header.minZoom || z > header.maxZoom) return null
        val id = tileId(z, x, y)
        var dir = root
        repeat(4) {
            val e = find(dir, id) ?: return null
            if (e.runLength > 0) return unpack(read(header.dataOffset + e.offset, e.length), header.tileCompression)
            val at = header.leafOffset + e.offset
            dir = synchronized(leaves) { leaves[at] } ?: directory(read(at, e.length)).also { d -> synchronized(leaves) { leaves[at] = d } }
        }
        return null
    }

    private fun directory(raw: ByteArray): List<Entry> {
        val b = unpack(raw, header.internalCompression)
        val r = Varints(b)
        val n = r.next().toInt()
        val ids = LongArray(n); var last = 0L
        for (i in 0 until n) { last += r.next(); ids[i] = last }
        val runs = LongArray(n) { r.next() }
        val lengths = IntArray(n) { r.next().toInt() }
        val offsets = LongArray(n)
        for (i in 0 until n) {
            val v = r.next()
            offsets[i] = if (v == 0L && i > 0) offsets[i - 1] + lengths[i - 1] else v - 1
        }
        return List(n) { Entry(ids[it], offsets[it], lengths[it], runs[it]) }
    }

    private fun read(at: Long, length: Int): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(length)
        var pos = at
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n < 0) throw java.io.EOFException("${file.name} at $pos")
            pos += n
        }
        return buf.array()
    }

    /** The file let go (the package changed or went). */
    fun close() { runCatching { channel.close() } }

    private class Varints(val b: ByteArray) {
        var i = 0
        fun next(): Long {
            var v = 0L; var shift = 0
            while (true) {
                val c = b[i++].toInt() and 0xFF
                v = v or ((c and 0x7F).toLong() shl shift)
                if (c < 0x80) return v
                shift += 7
            }
        }
    }

    companion object {
        /** The tile's place on the Hilbert curve of its zoom, after all tiles of the zooms above (PMTiles v3). */
        fun tileId(z: Int, x: Int, y: Int): Long {
            var acc = 0L
            for (t in 0 until z) acc += (1L shl t) * (1L shl t)
            var px = x.toLong(); var py = y.toLong(); var d = 0L
            var s = (1L shl z) / 2
            while (s > 0) {
                val rx = if (px and s > 0) 1L else 0L
                val ry = if (py and s > 0) 1L else 0L
                d += s * s * ((3 * rx) xor ry)
                if (ry == 0L) {
                    if (rx == 1L) { px = s - 1 - px; py = s - 1 - py }
                    val t = px; px = py; py = t
                }
                s /= 2
            }
            return acc + d
        }

        /** The entry holding [id]: the last starting at or before it, if its run reaches it (a leaf has run 0). */
        fun find(dir: List<Entry>, id: Long): Entry? {
            var lo = 0; var hi = dir.size - 1
            while (lo <= hi) {
                val m = (lo + hi) ushr 1
                val c = dir[m].tileId
                if (c == id) return dir[m]
                if (c < id) lo = m + 1 else hi = m - 1
            }
            if (hi < 0) return null
            val e = dir[hi]
            return if (e.runLength == 0L || id - e.tileId < e.runLength) e else null
        }

        private fun u64(b: ByteArray, at: Int): Long {
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
            return v
        }

        /** 1 none, 2 gzip, 0 unknown (gzip by its mark); others (brotli, zstd) are not used by the map server's packages. */
        private fun unpack(b: ByteArray, compression: Int): ByteArray =
            if (compression == 2 || (compression == 0 && b.size > 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()))
                GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
            else b
    }
}
