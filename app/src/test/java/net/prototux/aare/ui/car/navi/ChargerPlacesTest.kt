package net.prototux.aare.ui.car.navi

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Navi L-051: "Tesla Supercharger und klein darunter den Ort" - the line from what Photon's reverse search says of the place
 * nearest a charger (properties as Photon gives them).
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36])
class ChargerPlacesTest {
    private fun p(vararg kv: Pair<String, String>) = JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }

    @Test fun theTownAndTheMotorway() =
        assertEquals("Hermsdorf · A 9", ChargerPlaces.line(p("name" to "Tesla Supercharger", "street" to "A 9", "city" to "Hermsdorf", "type" to "house")))

    @Test fun aServiceAreaByItsName() =
        assertEquals("Schkeuditz · Rastanlage Kappellenberg", ChargerPlaces.line(p("name" to "Rastanlage Kappellenberg", "city" to "Schkeuditz", "street" to "A 9")))

    @Test fun inTownTheTownAlone() =
        assertEquals("Berlin", ChargerPlaces.line(p("name" to "Ladepark", "street" to "Potsdamer Straße", "city" to "Berlin")))

    @Test fun withoutACityTheNextBiggerPlace() {
        assertEquals("Mittelfeld · E 55", ChargerPlaces.line(p("locality" to "Mittelfeld", "street" to "E55")))
        assertEquals("Landkreis Beispiel", ChargerPlaces.line(p("county" to "Landkreis Beispiel")))
    }

    @Test fun noPlaceNoLine() = assertNull(ChargerPlaces.line(p("name" to "Tesla Supercharger", "street" to "A 9")))
}
