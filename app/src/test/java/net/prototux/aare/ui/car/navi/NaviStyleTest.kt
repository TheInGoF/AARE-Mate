package net.prototux.aare.ui.car.navi

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * L-047: the guiding veil lies in the style itself, right under the map's first label layer (21.11.2026: anchored from
 * Compose it brought Mate down at the start of every guidance).
 */
@RunWith(RobolectricTestRunner::class)
class NaviStyleTest {
    @Test fun veilInTheStyleUnderTheFirstLabelLayer() {
        val raw = java.io.File("src/main/assets/navi/karte-v4.json").readText()
        val o = JSONObject(withVeil(raw, "#112233"))
        assertTrue("the veil's source", o.getJSONObject("sources").has(VEIL_ID))
        val layers = (0 until o.getJSONArray("layers").length()).map { o.getJSONArray("layers").getJSONObject(it) }
        val veil = layers.indexOfFirst { it.getString("id") == VEIL_ID }
        val firstLabel = layers.indexOfFirst { it.optString("type") == "symbol" }
        assertEquals("right under the first label layer", firstLabel - 1, veil)
        assertEquals("every layer of the style kept", JSONObject(raw).getJSONArray("layers").length() + 1, layers.size)
    }

    @Test fun aBrokenStyleStaysAsItIs() {
        assertEquals("not json", withVeil("not json", "#112233"))
    }
}
