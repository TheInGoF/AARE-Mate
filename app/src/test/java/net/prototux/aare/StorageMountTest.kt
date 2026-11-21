package net.prototux.aare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageMountTest {
    @Test fun cardItself() {
        assertEquals("vfat", Storage.mountType("/dev/block/vold/public:179,1 /mnt/media_rw/0670-0FE8 vfat rw,dirsync,nosuid 0 0", "0670-0FE8"))
        assertEquals("exfat", Storage.mountType("/dev/block/vold/public:179,1 /mnt/media_rw/ABCD-1234 exfat rw 0 0", "ABCD-1234"))
    }

    @Test fun notTheCard() {
        // the views of the card Android puts on top (sdcardfs, fuse) do not count, nor other cards
        assertNull(Storage.mountType("/mnt/media_rw/0670-0FE8 /mnt/runtime/default/0670-0FE8 sdcardfs rw 0 0", "0670-0FE8"))
        assertNull(Storage.mountType("/dev/fuse /storage/0670-0FE8 fuse rw 0 0", "0670-0FE8"))
        assertNull(Storage.mountType("/dev/block/vold/public:179,1 /mnt/media_rw/0670-0FE8 vfat rw 0 0", "1111-2222"))
    }

    @Test fun limit() { assertEquals(4294967295L, Storage.FAT32_MAX) }
}
