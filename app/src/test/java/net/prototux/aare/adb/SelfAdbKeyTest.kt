package net.prototux.aare.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.security.cert.X509Certificate

/** System L-063: the key Mate pairs with is made once, self-signed, and the same when read again. */
class SelfAdbKeyTest {
    @Test fun keyMadeOnceAndKept() {
        val dir = Files.createTempDirectory("selfadb").toFile()
        val (k1, c1) = selfAdbKey(dir)
        val (k2, c2) = selfAdbKey(dir)
        assertArrayEquals(k1.encoded, k2.encoded)
        assertArrayEquals(c1.encoded, c2.encoded)
        val x = c1 as X509Certificate
        x.verify(x.publicKey)
        assertEquals("CN=Mate", x.subjectX500Principal.name)
        dir.deleteRecursively()
    }
}
