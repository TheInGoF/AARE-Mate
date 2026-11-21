package net.prototux.aare

import net.prototux.aare.speech.Voices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every language of the library listens and speaks: Whisper models to choose from and voices of its own. */
class LanguageSpeechTest {
    @Test fun everyLanguageHasModelsAndVoices() {
        for (lang in Language.SUPPORTED) {
            val s = Language.speech(lang)
            assertTrue("$lang: no Whisper model", s.whisper.isNotEmpty())
            assertEquals("$lang: the first voice speaks $lang", lang, Voices.standard(lang).lang)
            assertTrue("$lang: the first voice can be downloaded", Voices.standard(lang).repo != null)
            assertTrue("$lang: no voice besides the first to choose from", Voices.ALL.count { it.lang == lang && it.repo != null } > 1)
        }
    }

    @Test fun everyVoiceBelongsToALanguage() {
        Voices.ALL.forEach { assertTrue("${it.id}: language ${it.lang} not in the library", it.lang in Language.SUPPORTED) }
        assertEquals("voice ids unique", Voices.ALL.size, Voices.ALL.map { it.id }.distinct().size)
    }

    @Test fun englishOnlyModelsOnlyForEnglish() {
        for (lang in Language.SUPPORTED) if (lang != "en")
            assertTrue("$lang offers an English-only model", Language.speech(lang).whisper.keys.none { it.endsWith(".en") })
    }
}
