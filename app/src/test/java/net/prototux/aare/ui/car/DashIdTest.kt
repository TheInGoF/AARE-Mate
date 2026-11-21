package net.prototux.aare.ui.car

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 21.11.2026: with its own app id (2.1) Mate no longer found its dashboard - the id is written out in full since. */
class DashIdTest {
    @Test fun bothFormsOfTheId() {
        assertTrue(isDashId("car:net.prototux.aare/.dash.IdmateCarService"))          // up to 2.0, app id = code package
        assertTrue(isDashId("car:app.idmate.mate/net.prototux.aare.dash.IdmateCarService"))   // 2.1, own app id
        assertTrue(isDashId("car:app.idmate.mate.probe/net.prototux.aare.dash.IdmateCarService"))
    }

    @Test fun othersAreNot() {
        assertFalse(isDashId("car:de.fredersdorf.idmate.auto/.IdmateCarService"))
        assertFalse(isDashId("car:de.fredersdorf.idmate.auto/.dash.IdmateCarService"))
        assertFalse(isDashId("car:com.iternio.abrpapp/.car.AbrpCarService"))
    }
}
