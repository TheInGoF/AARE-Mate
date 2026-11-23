package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 23.11.2026: the car Wi-Fi's login page only wants a confirmation - Mate finds the form and what to send. */
class PortalLoginTest {
    @Test fun acceptFormWithHiddenFieldsAndBox() {
        val html = """<html><body><form method="post" action="/login">
            <input type="hidden" name="token" value="abc">
            <input type="checkbox" name="terms" value="yes"> Ich stimme zu
            <button type="submit" name="go">Akzeptieren und verbinden</button></form></body></html>"""
        val f = PortalLogin.pick(PortalLogin.forms(html))!!
        assertTrue(f.post)
        assertEquals("/login", f.action)
        assertEquals(listOf("token" to "abc", "terms" to "yes"), f.fields)
        assertEquals("go" to "Akzeptieren und verbinden", f.buttons.single())
    }

    @Test fun theConfirmingFormAmongSeveral() {
        val html = """<form action='/search'><input name=q><input type=submit value=Suchen></form>
            <form action='/ok' method='GET'><input type=submit value='Connect'></form>"""
        assertEquals("/ok", PortalLogin.pick(PortalLogin.forms(html))!!.action)
    }

    @Test fun pageOfScriptsHasNothing() {
        assertNull(PortalLogin.pick(PortalLogin.forms("<html><script>app()</script><div id=root></div></html>")))
    }
}
