package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

/** O175 / P-059: the dashboard's tiles read from the package file on the device (PMTiles v3). */
class PmTilesTest {
    @Test fun tileIdsFollowTheSpec() {
        assertEquals(0L, PmTiles.tileId(0, 0, 0))
        assertEquals(1L, PmTiles.tileId(1, 0, 0))
        assertEquals(2L, PmTiles.tileId(1, 0, 1))
        assertEquals(3L, PmTiles.tileId(1, 1, 1))
        assertEquals(4L, PmTiles.tileId(1, 1, 0))
        assertEquals(5L, PmTiles.tileId(2, 0, 0))
    }

    private fun varint(o: ByteArrayOutputStream, v0: Long) { var v = v0; while (v >= 0x80) { o.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }; o.write(v.toInt()) }
    private fun gz(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    /** entries: tileId, offset, length, run */
    private fun dir(entries: List<LongArray>): ByteArray {
        val o = ByteArrayOutputStream()
        varint(o, entries.size.toLong())
        var last = 0L; entries.forEach { varint(o, it[0] - last); last = it[0] }
        entries.forEach { varint(o, it[3]) }
        entries.forEach { varint(o, it[2]) }
        entries.forEachIndexed { i, e -> if (i > 0 && e[1] == entries[i - 1][1] + entries[i - 1][2]) varint(o, 0) else varint(o, e[1] + 1) }
        return o.toByteArray()
    }

    private fun file(root: ByteArray, leaves: ByteArray, data: ByteArray, minZ: Int, maxZ: Int): File {
        val h = ByteBuffer.allocate(127).order(ByteOrder.LITTLE_ENDIAN)
        h.put("PMTiles".toByteArray()); h.put(3)
        val rootAt = 127L; val leafAt = rootAt + root.size; val dataAt = leafAt + leaves.size
        h.putLong(rootAt); h.putLong(root.size.toLong()); h.putLong(0); h.putLong(0)
        h.putLong(leafAt); h.putLong(leaves.size.toLong()); h.putLong(dataAt); h.putLong(data.size.toLong())
        h.putLong(0); h.putLong(0); h.putLong(0)
        h.put(1); h.put(2); h.put(2); h.put(1); h.put(minZ.toByte()); h.put(maxZ.toByte())
        return File.createTempFile("mate-test", ".pmtiles").apply { deleteOnExit(); writeBytes(h.array() + root + leaves + data) }
    }

    @Test fun readsTilesThroughRootLeafAndRuns() {
        val a = gz("tile-a".toByteArray()); val b = gz("tile-b".toByteArray()); val c = gz("tile-c".toByteArray())
        val data = a + b + c
        // ids 0 (a), 1..2 (b, a run of 2 - same tile twice), 5.. in a leaf (c at id 7)
        val leaf = gz(dir(listOf(longArrayOf(7, (a.size + b.size).toLong(), c.size.toLong(), 1))))
        val root = gz(dir(listOf(longArrayOf(0, 0, a.size.toLong(), 1), longArrayOf(1, a.size.toLong(), b.size.toLong(), 2),
            longArrayOf(5, 0, leaf.size.toLong(), 0))))
        val p = PmTiles(file(root, leaf, data, 0, 2))
        assertArrayEquals("tile-a".toByteArray(), p.tile(0, 0, 0))
        assertArrayEquals("tile-b".toByteArray(), p.tile(1, 0, 0))
        assertArrayEquals("tile-b".toByteArray(), p.tile(1, 0, 1))
        assertNull(p.tile(1, 1, 1))                                       // id 3: after the run, before the leaf
        assertArrayEquals("tile-c".toByteArray(), p.tile(2, 1, 1))       // id 7
        assertNull(p.tile(2, 0, 0))                                       // id 5: in the leaf's range, not in it
        assertNull(p.tile(3, 0, 0))                                       // above the file's highest zoom
    }
}
