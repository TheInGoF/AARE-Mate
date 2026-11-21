package net.prototux.aare.ui.car.navi

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * The driver's charger ratings to the map server's collecting point (Navi L-003, E-010: "dass das auf dem Kartenserver
 * gesammelt wird"), anonymous: the charger's place, power and network, the stars and the details - no device, no time
 * beyond the day the server notes, no route. Each charge gets an id of its own (random, nothing of the device), so that
 * a rating changed later replaces the one sent before. Sent only with a map server set up and sharing on
 * (Settings.shareRatings); without the net it waits and goes with the next change or the next look at the list.
 */
internal object RatingShare {
    private const val TAG = "MateRatings"
    private const val PATH = "/sammeln/bewertung"

    private fun prefs(c: Context) = c.getSharedPreferences("rating_share", Context.MODE_PRIVATE)
    private fun chargerKey(v: ChargerVisits.Visit) = "%.4f,%.4f".format(java.util.Locale.ROOT, v.at.latitude, v.at.longitude)

    /** What a rating says, as one word: the stars and the three details; null when nothing is told. */
    fun signature(stars: Int, power: Int, price: Int, worked: Int): String? =
        if (stars <= 0 && power <= 0 && price <= 0 && worked <= 0) null else "$stars,$power,$price,$worked"

    /**
     * The rating of [v] changed (stars in the car, details in the phone app): queued unless it is what was sent last for
     * this charger, then sent.
     */
    fun changed(c: Context, v: ChargerVisits.Visit) {
        val sig = signature(v.rating, v.power, v.price, v.worked) ?: return
        val p = prefs(c)
        val key = chargerKey(v)
        if (p.getString("sent:$key", null) == sig) return
        // one id per charge: the visit's time names the charge
        val idKey = "id:$key@${v.lastMillis}"
        val id = p.getString(idKey, null) ?: java.util.UUID.randomUUID().toString().also { p.edit().putString(idKey, it).apply() }
        val body = JSONObject().put("lat", v.at.latitude).put("lon", v.at.longitude).put("kw", v.kw).put("kennung", id)
            .apply { v.network?.let { put("netz", it) } }
            .apply { if (v.rating > 0) put("sterne", v.rating); if (v.power > 0) put("leistung", v.power) }
            .apply { if (v.price > 0) put("preis", v.price); if (v.worked > 0) put("funktion", v.worked) }
        p.edit().putString("wait:$key", "$sig|$body").apply()
        flush(c)
    }

    /** Sends what waits, off the main thread. */
    fun flush(c: Context) {
        val app = c.applicationContext
        val settings = net.prototux.aare.Settings(app)
        if (!settings.shareRatings || settings.mapServer.isBlank()) return
        val waiting = prefs(app).all.filterKeys { it.startsWith("wait:") }.mapValues { it.value as? String }.filterValues { it != null }
        if (waiting.isEmpty()) return
        Thread({
            val url = settings.mapServer.trimEnd('/') + PATH
            var sent = 0
            for ((k, v) in waiting) {
                val (sig, body) = v!!.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                val ok = runCatching {
                    NaviServer.client(app).newCall(Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).build())
                        .execute().use { it.code == 201 }
                }.getOrDefault(false)
                val key = k.removePrefix("wait:")
                if (ok) { prefs(app).edit().remove(k).putString("sent:$key", sig).apply(); sent++ }
                Log.i(TAG, "rating " + (if (ok) "shared" else "kept for later"))
                if (!ok) break
            }
            net.prototux.aare.DiagLog.add("charge ratings: $sent of ${waiting.size} to the map server, " +
                "${prefs(app).all.keys.count { it.startsWith("wait:") }} still waiting", area = "CHARGE")
        }, "rating-share").start()
    }
}
