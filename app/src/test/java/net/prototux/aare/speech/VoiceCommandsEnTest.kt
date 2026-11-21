package net.prototux.aare.speech

import net.prototux.aare.speech.VoiceCommands.Kind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The English command words (befehle-en.json): the German test sentences of VoiceCommandsTest, said in English. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceCommandsEnTest {
    private val g = VoiceCommands.grammar("en")
    private fun check(s: String, kind: Kind, target: String = "", app: String? = null, text: String? = null) {
        val c = VoiceCommands.parse(s, g)
        assertEquals("$s -> kind", kind, c.kind)
        if (kind != Kind.UNKNOWN) assertEquals("$s -> target", target, c.target)
        assertEquals("$s -> app", app, c.app)
        assertEquals("$s -> text", text, c.text)
    }

    @Test fun openApp() {
        check("Open the Podimo app.", Kind.OPEN_APP, "podimo")
        check("Start Spotify", Kind.OPEN_APP, "spotify")
        check("Hey Mate, open the weather app", Kind.OPEN_APP, "weather")
        check("Launch Audible please", Kind.OPEN_APP, "audible")
    }

    @Test fun navigate() {
        check("Navigate to 17 Hamburg Street in Berlin.", Kind.NAVIGATE, "17 hamburg street in berlin")
        check("Take me to the main station", Kind.NAVIGATE, "main station")
        check("Go to Leipzig", Kind.NAVIGATE, "leipzig")
        check("Start the navigation to Dresden", Kind.NAVIGATE, "dresden")
        check("Drive to work", Kind.NAVIGATE, "work")
    }

    @Test fun home() {
        check("Take me home", Kind.HOME)
        check("Navigate home.", Kind.HOME)
        check("Home", Kind.HOME)
        check("Navigate to home", Kind.HOME)
    }

    @Test fun play() {
        check("Play Wizo", Kind.PLAY, "wizo")
        check("Play me something by the Rolling Stones", Kind.PLAY, "the rolling stones")
    }

    @Test fun call() {
        check("Call Maria.", Kind.CALL, "maria")
        check("Please call Maria Miller", Kind.CALL, "maria miller")
        check("Phone Peter", Kind.CALL, "peter")
    }

    @Test fun message() {
        check("Send a message to Maria.", Kind.MESSAGE, "maria")
        check("Send a WhatsApp message to Maria", Kind.MESSAGE, "maria", "com.whatsapp")
        check("Write a message via Signal to Maria", Kind.MESSAGE, "maria", "org.thoughtcrime.securesms")
        check("Send a message to Maria that I will be late", Kind.MESSAGE, "maria", null, "i will be late")
        check("Write Maria: on my way", Kind.MESSAGE, "maria", null, "on my way")
    }

    @Test fun music() {
        check("Pause", Kind.PAUSE)
        check("Next track", Kind.NEXT)
        check("Back.", Kind.PREVIOUS)
        check("Resume", Kind.RESUME)
    }

    /** Every befehle-<language>.json level with the German one: the same commands in the same order, the same answers, the same placeholders. */
    @Test fun everyLanguageHasEveryCommandAndAnswer() {
        fun read(lang: String) = VoiceCommands::class.java.getResourceAsStream("befehle-$lang.json")!!.bufferedReader().use { JSONObject(it.readText()) }
        fun kinds(o: JSONObject) = o.getJSONArray("commands").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("kind") } }
        fun words(o: JSONObject) = o.getJSONObject("words").let { w -> w.keys().asSequence().associateWith { Regex("%[sd]").findAll(w.getString(it)).count() } }
        val de = read("de")
        val problems = mutableListOf<String>()
        for (lang in listOf("en")) {
            val o = read(lang)
            if (kinds(o) != kinds(de)) problems += "$lang: commands ${kinds(o)}, German ${kinds(de)}"
            if (words(o) != words(de)) problems += "$lang: answers ${words(o)}, German ${words(de)}"
            if (o.getJSONObject("messengers").keys().asSequence().toSet() != de.getJSONObject("messengers").keys().asSequence().toSet()) problems += "$lang: messengers differ"
            for (k in listOf("polite", "articles", "cancel", "confirm", "home")) if (o.getJSONArray(k).length() == 0) problems += "$lang: $k empty"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun unknown() {
        check("What will the weather be tomorrow", Kind.UNKNOWN)
        check("Open", Kind.UNKNOWN)
    }
}
