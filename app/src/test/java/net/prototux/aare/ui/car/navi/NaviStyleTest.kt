package net.prototux.aare.ui.car.navi

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** L-047: the guiding veil lies under the map's labels - the style's first label layer must be the one it names. */
@RunWith(RobolectricTestRunner::class)
class NaviStyleTest {
    @Test fun veilGoesUnderTheFirstLabelLayer() {
        val layers = JSONObject(java.io.File("src/main/assets/navi/karte-v4.json").readText()).getJSONArray("layers")
        val first = (0 until layers.length()).map { layers.getJSONObject(it) }.first { it.optString("type") == "symbol" }
        assertEquals(FIRST_LABEL_LAYER, first.getString("id"))
    }
}
