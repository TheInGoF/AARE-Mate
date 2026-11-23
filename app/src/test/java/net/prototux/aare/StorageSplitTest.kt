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
        Storage.move(ctx, inner, Storage.MAP_KINDS) { _, _, _ -> }
        assertEquals("", Storage.chosen(ctx, Storage.MAPS))
        assertEquals("CARD-1", Storage.chosen(ctx, Storage.MUSIC))
        assertEquals(File(card, Storage.MUSIC), Storage.dir(ctx, Storage.MUSIC))
        // now the music on its own to the device: its file goes along
        Storage.move(ctx, inner, Storage.MUSIC_KINDS) { _, _, _ -> }
        assertEquals("", Storage.chosen(ctx, Storage.MUSIC))
        assertEquals("x", File(Storage.dir(ctx, Storage.MUSIC), "titel.mp3").readText())
    }
}
