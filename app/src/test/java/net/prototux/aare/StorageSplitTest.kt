package net.prototux.aare

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** 23.11.2026: the music has a place of its own - it follows the maps' place until then, and stays when the maps move. */
@RunWith(RobolectricTestRunner::class)
class StorageSplitTest {
    private val ctx: Context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
    private fun prefs() = ctx.getSharedPreferences("storage", Context.MODE_PRIVATE)

    @Test fun musicFollowsTheMapsUntilTheyMoveWithoutIt() {
        val card = File(ctx.cacheDir, "karte").apply { mkdirs() }
        prefs().edit().putString("volume", "CARD-1").putString("root", card.path).commit()
        // before: one place for all
        assertEquals("CARD-1", Storage.chosen(ctx, Storage.MUSIC))
        assertEquals(File(card, Storage.MUSIC), Storage.dir(ctx, Storage.MUSIC))
        File(card, Storage.MUSIC).mkdirs(); File(card, "${Storage.MUSIC}/titel.mp3").writeText("x")
        // the maps move to the device: the music keeps its card
        // the device as a place with room (Robolectric reports no free bytes)
        val inner = Storage.Volume("", "intern", ctx.filesDir, false, 1L shl 40, 1L shl 40, false)
        Storage.move(ctx, inner) { _, _, _ -> }
        assertEquals("", Storage.chosen(ctx, Storage.MAPS))
        assertEquals("CARD-1", Storage.chosen(ctx, Storage.MUSIC))
        assertEquals(File(card, Storage.MUSIC), Storage.dir(ctx, Storage.MUSIC))
        // now the music on its own to the device: its file goes along
        Storage.moveMusic(ctx, null) { _, _, _ -> }
        assertEquals("", Storage.chosen(ctx, Storage.MUSIC))
        assertEquals("x", File(Storage.dir(ctx, Storage.MUSIC), "titel.mp3").readText())
    }

    /** "Musik ist gelöscht": maps went to the card before the music could, its titles stayed in the device - Mate finds them there. */
    @Test fun musicStaysWhereItsTitlesLie() {
        val card = File(ctx.cacheDir, "karte2").apply { mkdirs() }
        prefs().edit().clear().putString("volume", "CARD-2").putString("root", card.path).commit()
        val inner = Storage.internalDir(ctx, Storage.MUSIC)
        File(inner, "t").mkdirs(); File(inner, "t/titel").writeText("musik")
        net.prototux.aare.music.Offline.subscribe(ctx, "liste", true)
        assertEquals("", Storage.chosen(ctx, Storage.MUSIC))
        assertEquals(inner, Storage.dir(ctx, Storage.MUSIC))
        assertEquals("CARD-2", Storage.chosen(ctx, Storage.MAPS))
    }

    /** Deleted on the card while the titles lay in the device: with nothing subscribed the leftovers are not revived. */
    @Test fun leftoversAreNotRevived() {
        val card = File(ctx.cacheDir, "karte3").apply { mkdirs() }
        prefs().edit().clear().putString("volume", "CARD-3").putString("root", card.path).commit()
        net.prototux.aare.music.Offline.clear(ctx)
        val inner = Storage.internalDir(ctx, Storage.MUSIC)
        File(inner, "t").mkdirs(); File(inner, "t/alt").writeText("13 GB")
        assertEquals("CARD-3", Storage.chosen(ctx, Storage.MUSIC))
        // "Alles löschen" reaches them too
        net.prototux.aare.music.Offline.clear(ctx)
        assertEquals(false, File(inner, "t/alt").exists())
    }

    /** L-100: a folder of one's own, e.g. "Mate Musik" on the card; the titles move in, the folder's other files stay. */
    @Test fun ownFolder() {
        assertEquals(File("/storage/1234-ABCD/Mate Musik"), Storage.folderOf("1234-ABCD:Mate Musik"))
        assertEquals(File("/x/Music/Mate"), Storage.folderOf("primary:Music/Mate", File("/x")))
        assertEquals(null, Storage.folderOf("raw"))
        prefs().edit().clear().commit()
        val inner = Storage.internalDir(ctx, Storage.MUSIC)
        File(inner, "t").mkdirs(); File(inner, "t/titel").writeText("musik")
        val own = File(ctx.cacheDir, "Mate Musik").apply { mkdirs() }
        File(own, "eigenes.txt").writeText("bleibt")
        Storage.moveMusic(ctx, own) { _, _, _ -> }
        assertEquals(own, Storage.dir(ctx, Storage.MUSIC))
        assertEquals("musik", File(own, "t/titel").readText())
        assertEquals(false, File(inner, "t/titel").exists())
        // and back: the driver's own file stays in the folder
        Storage.moveMusic(ctx, null) { _, _, _ -> }
        assertEquals(inner, Storage.dir(ctx, Storage.MUSIC))
        assertEquals("musik", File(inner, "t/titel").readText())
        assertEquals("bleibt", File(own, "eigenes.txt").readText())
        assertEquals(false, File(own, "t").exists())
    }
}
