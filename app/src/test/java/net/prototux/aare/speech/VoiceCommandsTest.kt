package net.prototux.aare.speech

import net.prototux.aare.speech.VoiceCommands.Kind
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The sentences of the wish of 18.11.2026 and their kin, as Whisper writes them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceCommandsTest {
    private val g = VoiceCommands.grammar("de")
    private fun p(s: String) = VoiceCommands.parse(s, g)
    private fun check(s: String, kind: Kind, target: String = "", app: String? = null, text: String? = null) {
        val c = p(s)
        assertEquals("$s -> kind", kind, c.kind)
        if (kind != Kind.UNKNOWN) assertEquals("$s -> target", target, c.target)
        assertEquals("$s -> app", app, c.app)
        assertEquals("$s -> text", text, c.text)
    }

    @Test fun openApp() {
        check("Öffne die Podimo-App.", Kind.OPEN_APP, "podimo")
        check("Starte Spotify", Kind.OPEN_APP, "spotify")
        check("Mach bitte Audible auf", Kind.OPEN_APP, "audible")
        check("Spotify öffnen", Kind.OPEN_APP, "spotify")
        check("Hey Mate, öffne mir die Wetter App", Kind.OPEN_APP, "wetter")
    }

    @Test fun navigate() {
        check("Navigiere zu Hamburger Straße 17 in Berlin.", Kind.NAVIGATE, "hamburger straße 17 in berlin")
        check("Navigiere nach Hamburger Straße 17", Kind.NAVIGATE, "hamburger straße 17")
        check("Leite mich nach Leipzig", Kind.NAVIGATE, "leipzig")
        check("Bring mich zum Hauptbahnhof Hamburg", Kind.NAVIGATE, "hauptbahnhof hamburg")
        check("Starte die Navigation nach Dresden", Kind.NAVIGATE, "dresden")
        check("Fahr zur Arbeit", Kind.NAVIGATE, "arbeit")
    }

    @Test fun home() {
        check("Navigiere mich nach Hause", Kind.HOME)
        check("Bring mich nach Hause.", Kind.HOME)
        check("Nach Hause", Kind.HOME)
        check("Fahr heim", Kind.HOME)
    }

    @Test fun play() {
        check("Spiele Wizo", Kind.PLAY, "wizo")
        check("Spiel mir was von den Toten Hosen", Kind.PLAY, "den toten hosen")
        check("Spiele das Album Jenseits von Eden", Kind.PLAY, "das album jenseits von eden")
    }

    @Test fun call() {
        check("Rufe Maria an.", Kind.CALL, "maria")
        check("Ruf bitte bei Maria Müller an", Kind.CALL, "maria müller")
        check("Telefoniere mit Peter", Kind.CALL, "peter")
        check("Maria anrufen", Kind.CALL, "maria")
    }

    @Test fun message() {
        check("Schreibe eine Nachricht an Maria.", Kind.MESSAGE, "maria")
        check("Schreibe eine Nachricht über WhatsApp an Maria.", Kind.MESSAGE, "maria", "com.whatsapp")
        check("Schreibe eine WhatsApp an Maria", Kind.MESSAGE, "maria", "com.whatsapp")
        check("Schreibe eine WhatsApp-Nachricht an Maria.", Kind.MESSAGE, "maria", "com.whatsapp")
        check("Schreibe eine Element X Nachricht an Maria", Kind.MESSAGE, "maria", "io.element.android.x")
        check("Schreib Maria eine Signal-Nachricht", Kind.MESSAGE, "maria", "org.thoughtcrime.securesms")
        check("Schicke eine Nachricht an Maria, dass ich später komme", Kind.MESSAGE, "maria", null, "ich später komme")
        check("Schreibe Maria: bin gleich da", Kind.MESSAGE, "maria", null, "bin gleich da")
    }

    @Test fun media() {
        check("Pause", Kind.PAUSE)
        check("Nächster Titel", Kind.NEXT)
        check("Zurück.", Kind.PREVIOUS)
        check("Weiterspielen", Kind.RESUME)
    }

    @Test fun unknown() {
        check("Wie wird das Wetter morgen", Kind.UNKNOWN)
        check("Öffne", Kind.UNKNOWN)
    }
}
