package net.prototux.aare.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** System L-100: the music page offers a folder of one's own; without all-files access it asks for it first. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "de-w600dp-h1000dp-xhdpi")
class MusicFolderUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun asksForAccessThenFolder() {
        rule.setContent { androidx.compose.material3.MaterialTheme { MusicFolderCard() } }
        rule.onNodeWithText("Musik-Ordner").assertExists()
        rule.onNodeWithText("Im Speicher von Mate (Gerät)").assertExists()
        rule.onNodeWithText("Zugriff erlauben").assertExists()
        if (System.getProperty("preview") != null) {
            val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
            File("build/preview/phone").apply { mkdirs() }.let { File(it, "musik-ordner.png") }.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** "Er zeigt den laufenden Umzug beim Scrollen sowieso nicht mehr an": the card gone and back still shows the move. */
    @Test fun moveShownAfterScrollingBack() {
        var shown by androidx.compose.runtime.mutableStateOf(true)
        rule.setContent { androidx.compose.material3.MaterialTheme { if (shown) MusicFolderCard() } }
        StorageMove.progress.value = Triple(net.prototux.aare.Storage.Phase.COPY, 1L shl 30, 13L shl 30)
        rule.runOnIdle { shown = false }
        rule.runOnIdle { shown = true }
        rule.onNodeWithText("Kopiere", substring = true).assertExists()
        StorageMove.progress.value = null
    }
}
