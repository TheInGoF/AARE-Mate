package net.prototux.aare.dash

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * IDmate's map data kept at hand (21.11.2026: "Es wäre gut, wenn er die Wetterdaten immer ein bisschen auf Vorrat hat,
 * dass er nicht jedes Mal, wenn man auf Dashboard klickt, die Daten ewig laden muss. Die Wetterdaten selber bräuchten wir
 * auch für die Reichweitenberechnung in der App"): while the car is connected the range area, the trail, the rain and
 * the wind are fetched in the background - the dashboard shows the last ones at once and the rest of the app can read
 * the wind around the car ([windPoints]). One process-wide store; the dashboard asks for a round when it opens.
 */
internal object DashMapCache {
    private const val TAG = "IDmateStage"
    private const val ROUND_MS = 30_000L
    private const val RAIN_EVERY_MS = 2 * 60_000L
    private const val WIND_EVERY_MS = 10 * 60_000L

    private val _area = MutableStateFlow<DashMapGeo.Area?>(null)
    private val _trail = MutableStateFlow(DashMapGeo.EMPTY)
    private val _wind = MutableStateFlow(List(4) { DashMapGeo.EMPTY })
    private val _windSeen = MutableStateFlow(false)
    private val _rain = MutableStateFlow<JSONObject?>(null)

    /** The range ring (IDmate's expected range), null before IDmate has one. */
    val area: StateFlow<DashMapGeo.Area?> get() = _area
    val trail: StateFlow<String> get() = _trail
    /** The wind arrows by class (DashMapGeo.wind). */
    val wind: StateFlow<List<String>> get() = _wind
    val windSeen: StateFlow<Boolean> get() = _windSeen
    /** IDmate's rain raster (frames, cell_km) as last fetched. */
    val rain: StateFlow<JSONObject?> get() = _rain

    @Volatile private var windRaw: JSONArray? = null
    @Volatile private var areaTs: String? = null
    @Volatile private var rainAt = 0L
    @Volatile private var windAt = 0L
    @Volatile private var data: DashMapData? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    /** The wind of this hour around the car as IDmate has it ([{lat, lon, kmh, dir, gust?}]), null before the first. */
    fun windPoints(): JSONArray? = windRaw

    /** The car connected: the rounds start (and run until [stop]). */
    @Synchronized fun start(context: Context) {
        if (loop?.isActive == true) return
        val app = context.applicationContext
        loop = scope.launch {
            while (isActive) { round(app); delay(ROUND_MS) }
        }
    }

    /** The car gone: no more rounds; what was fetched stays. */
    @Synchronized fun stop() { loop?.cancel(); loop = null }

    /** A round now (the dashboard opened), off the main thread. */
    fun refreshSoon(context: Context) { val app = context.applicationContext; scope.launch { round(app) } }

    @Synchronized private fun client(context: Context) = data ?: DashMapData(context).also { data = it }

    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun round(context: Context) {
        if (!busy.compareAndSet(false, true)) return
        try {
            val d = client(context)
            val got = d.area()
            if (got != null) {
                val ts = got.optString("ts")
                if (ts != areaTs) {
                    val made = got.optJSONObject("geojson")?.let(DashMapGeo::area)
                    Log.i(TAG, "dashboard map: range area " + (if (made != null) "taken (${got.optJSONArray("kms")} km)" else "not readable"))
                    if (made != null) { _area.value = made; areaTs = ts }
                }
            }
            d.trail()?.let { _trail.value = DashMapGeo.trail(it) }
            val now = System.currentTimeMillis()
            if (now - rainAt > RAIN_EVERY_MS) d.rain()?.let { _rain.value = it; rainAt = now }
            if (now - windAt > WIND_EVERY_MS) d.wind()?.let { w -> windRaw = w; _wind.value = DashMapGeo.wind(w); _windSeen.value = w.length() > 0; windAt = now }
        } catch (e: Exception) {
            Log.w(TAG, "dashboard map: a round failed: ${e.javaClass.simpleName}")
        } finally {
            busy.set(false)
        }
    }
}
