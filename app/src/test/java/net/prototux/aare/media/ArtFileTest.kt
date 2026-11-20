package net.prototux.aare.media

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** 19.11.2026: the covers of Mate Musik's downloaded titles are files of Mate's own - allowed; no one else's file. */
@RunWith(RobolectricTestRunner::class)
class ArtFileTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun ownCoverFilesOnly() {
        val cover = File(File(ctx.filesDir, "offline/a").apply { mkdirs() }, "mate-test.jpg").apply { writeBytes(byteArrayOf(1)) }
        assertTrue(ArtLoader.allowed(ctx, Uri.fromFile(cover), ctx.packageName))
        // another app's media giving a file address: no
        assertFalse(ArtLoader.allowed(ctx, Uri.fromFile(cover), "com.example.other"))
        // out of Mate's storage, also by ".."
        assertFalse(ArtLoader.allowed(ctx, Uri.fromFile(File("/system/etc/hosts")), ctx.packageName))
        assertFalse(ArtLoader.allowed(ctx, Uri.parse("file://" + ctx.filesDir.path + "/../../../system/etc/hosts"), ctx.packageName))
    }
}
