package net.prototux.aare.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** System L-106: podcasts apart from audio books, and what each kind does under a prompt. */
@RunWith(RobolectricTestRunner::class)
class PromptKindTest {
    @Test
    fun podcastAppsApartFromAudioBooks() {
        assertEquals(CarSound.Kind.PODCAST, CarSound.kindOfName("au.com.shiftyjelly.pocketcasts"))
        assertEquals(CarSound.Kind.PODCAST, CarSound.kindOfName("de.danoeh.antennapod"))
        assertEquals(CarSound.Kind.PODCAST, CarSound.kindOfName("Podimo"))
        assertEquals(CarSound.Kind.BOOK, CarSound.kindOfName("com.audible.application"))
        assertEquals(CarSound.Kind.BOOK, CarSound.kindOfName("com.audiobookshelf.app"))
        assertEquals(CarSound.Kind.BOOK, CarSound.kindOfName("Hörbücher"))
        assertNull(CarSound.kindOfName("com.spotify.music"))
    }

    @Test
    fun musicLoweredTalkPausedUnlessChosen() {
        val s = net.prototux.aare.Settings(RuntimeEnvironment.getApplication())
        assertFalse(s.promptPauses(CarSound.Kind.MUSIC))
        assertTrue(s.promptPauses(CarSound.Kind.PODCAST))
        assertTrue(s.promptPauses(CarSound.Kind.BOOK))
        assertEquals(20, s.promptDuckPercent)
        s.setPromptPauses(CarSound.Kind.PODCAST, false)
        assertFalse(s.promptPauses(CarSound.Kind.PODCAST))
        s.promptDuckPercent = 80
        assertEquals(60, s.promptDuckPercent)
    }

    @Test
    fun podcastTakesTheAudioBookPresetUntilChosen() {
        val c = RuntimeEnvironment.getApplication()
        assertEquals(CarSound.presetKey(c, CarSound.Kind.BOOK), CarSound.presetKey(c, CarSound.Kind.PODCAST))
    }
}
