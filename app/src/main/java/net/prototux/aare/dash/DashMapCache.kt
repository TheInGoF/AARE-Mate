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

    private val _ferries = MutableStateFlow<DashMapGeo.Ferries?>(null)
    private val _ferryState = MutableStateFlow(FERRY_OFF)
    private val _ferriesOffered = MutableStateFlow(false)
    /** IDmate's long ferries while the button is on, null otherwise or before the answer. */
    val ferries: StateFlow<DashMapGeo.Ferries?> get() = _ferries
    /** Where the search stands: [FERRY_OFF], [FERRY_BUSY], [FERRY_SHOWN] or why there is nothing (the other FERRY_). */
    val ferryState: StateFlow<String> get() = _ferryState
    /** IDmate offers the search (its page shows the button): Mate shows it too. */
    val ferriesOffered: StateFlow<Boolean> get() = _ferriesOffered
    private var ferryJob: Job? = null

    const val FERRY_OFF = "off"
    const val FERRY_BUSY = "busy"
    const val FERRY_SHOWN = "shown"
    const val FERRY_NONE = "none"
    const val FERRY_FORBIDDEN = "forbidden"
    const val FERRY_NO_MODEL = "no_model"
    const val FERRY_ERROR = "error"

    /**
     * The button "Fähren" switched on, as on IDmate's page: one search per switch-on (IDmate keeps it 3 h), asked every
     * 3 s until it is done. Where IDmate lets the display session only read, a search started on IDmate's own page is
     * shown when there is one.
     */
    @Synchronized fun ferriesOn(context: Context) {
        ferryJob?.cancel()
        _ferryState.value = FERRY_BUSY
        val app = context.applicationContext
        ferryJob = scope.launch {
            val d = client(app)
            val started = d.startFerries()
            Log.i(TAG, "dashboard map: ferry search $started")
            val end = when (started) {
                "running", "ok", FERRY_FORBIDDEN -> {
                    var answer = d.ferries()
                    val until = System.currentTimeMillis() + 3 * 60_000L
                    while (isActive && answer?.optString("status") == "running" && System.currentTimeMillis() < until) { delay(3_000); answer = d.ferries() }
                    val f = DashMapGeo.ferries(answer)
                    Log.i(TAG, "dashboard map: ferries " + (f?.let { "${it.count} long crossings" } ?: "not answered (${answer?.optString("status")})"))
                    when {
                        f != null && f.count > 0 -> { _ferries.value = f; FERRY_SHOWN }
                        f != null -> FERRY_NONE
                        started == FERRY_FORBIDDEN -> FERRY_FORBIDDEN
                        else -> FERRY_ERROR
                    }
                }
                "off" -> FERRY_ERROR
                FERRY_NO_MODEL -> FERRY_NO_MODEL
                else -> FERRY_ERROR
            }
            if (isActive) _ferryState.value = end
        }
    }

    /** The button switched off: the ferries go, a search on its way is no longer waited for. */
    @Synchronized fun ferriesOff() { ferryJob?.cancel(); ferryJob = null; _ferries.value = null; _ferryState.value = FERRY_OFF }

    @Volatile private var windRaw: JSONArray? = null
    /** IDmate's last range area as it came, made anew when the wind changes (RangeWind). */
    @Volatile private var areaRaw: JSONObject? = null
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

    /**
     * IDmate's area with the wind put in by Mate where IDmate left it out (RangeWind): stretched from the car's place
     * (else the middle of the area) by the mean of IDmate's wind points.
     */
    /**
     * System L-087: IDmate's area drawn to the car's own range (stick) - stretched from the car's place by car range /
     * IDmate's range, within 0.7..1.4; 1 without both or with the car outside the area. The factor last used is kept,
     * so a round can tell when the car's range has moved enough to draw anew.
     */
    @Volatile private var areaKm: Double? = null
    @Volatile private var stretchUsed = 1.0

    private fun stretchFactor(context: Context): Double {
        val car = DashData.rangeKmFrom(context, "stick", Telemetry.LIVE_HOLD_MS) ?: return 1.0
        val base = DashData.rangeKmFrom(context, "backend", 6 * 3600_000L) ?: areaKm ?: return 1.0
        if (base < 5) return 1.0
        return (car / base).coerceIn(0.7, 1.4)
    }

    private fun makeArea(context: Context, raw: JSONObject): DashMapGeo.Area? {
        val f = stretchFactor(context)
        val t0 = Hub.get(context).telemetry
        val cLat = t0.number("lat"); val cLon = t0.number("lon")
        val scale: ((List<DoubleArray>) -> List<DoubleArray>)? =
            if (Math.abs(f - 1) < 0.01 || cLat == null || cLon == null) null
            else { ring -> DashMapGeo.stretch(ring, doubleArrayOf(cLon, cLat), f) }
        stretchUsed = if (scale == null) 1.0 else f
        if (scale != null) Log.i(TAG, "dashboard map: range area stretched to the car's range (x%.3f)".format(java.util.Locale.ROOT, f))
        val wind = windRaw?.let { w -> RangeWind.mean((0 until w.length()).mapNotNull { i -> w.optJSONObject(i)?.let { p ->
            val kmh = p.optDouble("kmh", Double.NaN); val dir = p.optDouble("dir", Double.NaN)
            if (kmh.isNaN() || dir.isNaN()) null else kmh to dir } }) }
        if (wind == null || wind.ms < RangeWind.CALM_MS) return DashMapGeo.area(raw, scale = scale)
        val t = Hub.get(context).telemetry
        val lat = t.number("lat"); val lon = t.number("lon")
        val car = net.prototux.aare.host.CarDataService.car(context) ?: net.prototux.aare.host.EnergyModel.Car(capacityWh = 77_000, socPercent = 50.0)
        var center: DoubleArray? = null
        val made = DashMapGeo.area(raw, scale = scale, deform = { ring ->
            // the car's place when it lies in the area (it was made around it), else the area's middle: an area of
            // earlier with the car far off since would be stretched around a point outside it
            val c = center ?: run {
                val inside = lat != null && lon != null && lon in ring.minOf { it[0] }..ring.maxOf { it[0] } && lat in ring.minOf { it[1] }..ring.maxOf { it[1] }
                if (inside) doubleArrayOf(lon!!, lat!!) else doubleArrayOf(ring.map { it[0] }.average(), ring.map { it[1] }.average())
            }.also { center = it }
            RangeWind.deform(ring, c, wind, car)
        })
        // only when Mate did put it in: IDmate's outline (kind "line", nearly always) has the wind already
        if (made != null && center == null) Log.i(TAG, "dashboard map: range as IDmate drew it (its outline has the wind in it)")
        if (made != null && center != null) Log.i(TAG, "dashboard map: range with the wind from %.0f° at %.0f km/h put in by Mate (%.2f..%.2f of the range)".format(
            java.util.Locale.ROOT, wind.fromDeg, wind.ms * 3.6, RangeWind.factor(wind.fromDeg, wind, car), RangeWind.factor((wind.fromDeg + 180) % 360, wind, car)))
        return made
    }

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
                    val raw = got.optJSONObject("geojson")
                    areaKm = got.optJSONArray("kms")?.let { k -> (0 until k.length()).maxOfOrNull { k.optDouble(it, 0.0) } }?.takeIf { it > 0 }
                    val made = raw?.let { makeArea(context, it) }
                    Log.i(TAG, "dashboard map: range area " + (if (made != null) "taken (${got.optJSONArray("kms")} km)" else "not readable"))
                    if (made != null) { _area.value = made; areaTs = ts; areaRaw = raw }
                }
            }
            // the car's range moved off what the area was drawn for: drawn anew (System L-087)
            if (areaTs != null && Math.abs(stretchFactor(context) - stretchUsed) > 0.015)
                areaRaw?.let { raw -> makeArea(context, raw)?.let { _area.value = it } }
            _ferriesOffered.value = d.ferriesOffered
            d.trail()?.let { _trail.value = DashMapGeo.trail(it) }
            val now = System.currentTimeMillis()
            if (now - rainAt > RAIN_EVERY_MS) d.rain()?.let { _rain.value = it; rainAt = now }
            if (now - windAt > WIND_EVERY_MS) d.wind()?.let { w ->
                windRaw = w; _wind.value = DashMapGeo.wind(w); _windSeen.value = w.length() > 0; windAt = now
                // the range drawn anew with the new wind
                areaRaw?.let { raw -> makeArea(context, raw)?.let { _area.value = it } }
            }
        } catch (e: Exception) {
            Log.w(TAG, "dashboard map: a round failed: ${e.javaClass.simpleName}")
        } finally {
            busy.set(false)
        }
    }
}
