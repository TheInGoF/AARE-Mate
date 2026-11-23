package net.prototux.aare.ui.car.navi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.maplibre.spatialk.geojson.Position

/**
 * Destinations handed to Mate Navi from outside (16.11.2026: a spoken "Navigiere nach ..." and the navigation intents of
 * other car apps went to another navigation app even with Mate Navi chosen): a text to search for, or a place with its
 * coordinates (and a name, if the sender gave one). Mate Navi takes the pending one when it shows.
 */
internal object NaviRequests {
    class Request(val query: String?, val at: Position?, val name: String? = null)

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> get() = _pending

    fun ask(r: Request) { _pending.value = r }
    fun take(): Request? = _pending.value.also { _pending.value = null }

    private val LAT_LON = Regex("""^\s*(-?\d{1,2}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*(?:\((.*)\))?\s*$""")

    /**
     * What a navigation URI asks for: geo:52.52,13.41 · geo:0,0?q=Alexanderplatz · geo:0,0?q=52.52,13.41(Name) ·
     * google.navigation:q=Hamburg · google.navigation:q=52.52,13.41 · google.navigation:ll=52.52,13.41. Null when it
     * names nothing.
     */
    fun fromUri(uri: String?): Request? {
        if (uri.isNullOrBlank()) return null
        val scheme = uri.substringBefore(':').lowercase()
        val rest = uri.substringAfter(':')
        // google.navigation: carries its parameters right after the colon, geo: after a '?'
        val query = if (scheme == "google.navigation" && '?' !in rest) rest else rest.substringAfter('?', "")
        val params = query.split('&').mapNotNull { p ->
            p.split('=', limit = 2).takeIf { it.size == 2 }?.let {
                // a broken escape ("50%", "%G1") from another app must not crash Mate: keep the raw value then
                it[0].lowercase() to runCatching { java.net.URLDecoder.decode(it[1], "UTF-8") }.getOrDefault(it[1])
            }
        }.toMap()
        val q = params["q"] ?: if (scheme == "google.navigation") params["ll"] else null
        q?.let { text -> LAT_LON.find(text)?.let { m -> return point(m.groupValues[1], m.groupValues[2], m.groupValues[3]) } }
        if (!q.isNullOrBlank()) return Request(q.trim(), null)
        if (scheme == "geo") LAT_LON.find(rest.substringBefore('?').substringBefore(';'))?.let { m ->
            if (m.groupValues[1].toDouble() != 0.0 || m.groupValues[2].toDouble() != 0.0) return point(m.groupValues[1], m.groupValues[2], null)
        }
        return null
    }

    private fun point(lat: String, lon: String, name: String?): Request? {
        val la = lat.toDoubleOrNull() ?: return null; val lo = lon.toDoubleOrNull() ?: return null
        if (la !in -90.0..90.0 || lo !in -180.0..180.0) return null
        return Request(null, Position(longitude = lo, latitude = la), name?.takeIf { it.isNotBlank() })
    }
}
