package net.prototux.aare.speech

import net.prototux.aare.speech.VoiceMatch.Kind
import net.prototux.aare.speech.VoiceMatch.Name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceMatchTest {
    private val names = listOf(
        Name(Kind.ARTIST, "1", "Wizo"), Name(Kind.ARTIST, "2", "Die Toten Hosen"), Name(Kind.ARTIST, "3", "Die Ärzte"),
        Name(Kind.ARTIST, "4", "Broilers"), Name(Kind.ARTIST, "5", "Green Day"), Name(Kind.ARTIST, "6", "Ramones"),
        Name(Kind.ARTIST, "7", "Wise Guys"), Name(Kind.ARTIST, "8", "Betontod"),
        Name(Kind.ALBUM, "10", "Jenseits von Eden", "Die Toten Hosen"), Name(Kind.ALBUM, "11", "American Idiot", "Green Day"),
        Name(Kind.ALBUM, "12", "Vanitas", "Broilers"), Name(Kind.ALBUM, "14", "Warning:", "Green Day"),
        Name(Kind.ALBUM, "15", "Learning English: Lesson 1", "Die Toten Hosen"), Name(Kind.ALBUM, "13", "Ballast der Republik", "Die Toten Hosen"),
    )

    private fun pick(text: String) = VoiceMatch.choose(VoiceMatch.find(text, names))?.name?.name

    @Test fun soundsAlike() {
        assertEquals(VoiceMatch.koelner("wizo"), VoiceMatch.koelner("wiezu"))
        assertEquals("65752", VoiceMatch.koelner("muellerludenscheidt").take(5))
    }

    // what Whisper made of it on the tablet (runs/o56-messung.log)
    @Test fun wizoFromWhisperTiny() = assertEquals("Wizo", pick("Spiele wie zu."))
    @Test fun albumFromWhisperTiny() = assertEquals("Jenseits von Eden", pick("Spiele das Album jenseits von jedem von den Totenhosen."))
    @Test fun albumFromWhisperBase() = assertEquals("Jenseits von Eden", pick("Spiele des Album Jenseits von den Toten hosen."))
    @Test fun artistWithArticle() = assertEquals("Die Toten Hosen", pick("Spiel die Toten Hosen"))
    @Test fun umlautArtist() = assertEquals("Die Ärzte", pick("spiele die aerzte"))
    @Test fun plainArtist() = assertEquals("Broilers", pick("Broilers bitte"))
    @Test fun englishAlbum() = assertEquals("American Idiot", pick("Spiele American Idiot"))
    @Test fun bluntSoundIsNoMatch() {
        val more = names + Name(Kind.ALBUM, "20", "Voyage", "Irie Révoltés")
        assertNull(VoiceMatch.choose(VoiceMatch.find("Spiele-Vic-O", more)))
    }
    @Test fun albumWithPartOfItsTitle() = assertEquals("Jenseits von Eden", pick("Spiele das Album Jenseits von den Toten hosen."))
    @Test fun albumByItsMainTitle() = assertEquals("Learning English: Lesson 1", pick("Spiele des Album Learning English"))
    @Test fun titleSimilarity() = org.junit.Assert.assertTrue(VoiceMatch.similarity("he Thomas", "Hey Thomas") > 0.6)
    @Test fun nothingFits() = assertNull(pick("Wie wird das Wetter morgen"))
}
