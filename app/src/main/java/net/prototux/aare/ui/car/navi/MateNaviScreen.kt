package net.prototux.aare.ui.car.navi

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.EditLocationAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.Wc
import androidx.compose.ui.Alignment
import androidx.compose.animation.togetherWith
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.roundToLong
import net.prototux.aare.ui.car.Mate
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position
import uniffi.ferrostar.TripState

/**
 * Mate's own navigation (O34, projekt/navi-app_v0.1.md): the map on the whole car screen, drawn by MapLibre from the own
 * vector tiles, and a guided trip by Ferrostar on a route of the own Valhalla. Until the tile server is rebuilt
 * (projekt/kartenserver-zusammenlegen_v0.1.md) tiles and routes come from the build PC through `adb reverse`
 * (8080, 8002); the style is a stand-in in Mate's map colours without lettering (no glyphs on the device yet, D-10).
 * Step 3b: a fixed test trip, driven by a simulated position along the route.
 */
@Composable
internal fun MateNaviScreen(modifier: Modifier, driving: Boolean = false, carName: String = "", driverOnRight: Boolean = false) {
    val context = LocalContext.current
    // F1: the real position (the car's GPS, else the phone's) - simulated only in the desk window (it must never show
    // where the desk is) and for the test hooks; pictures are only taken of simulated drives (mate-bild.sh)
    val deskWindow = remember(carName) { carName.contains("desktop head unit", ignoreCase = true) }
    // the trip is kept over the screen coming and going (NaviKeep): the guidance goes on behind the other areas
    remember(carName) { if (NaviKeep.testDrive.value == null || NaviKeep.car != carName) { NaviKeep.testDrive.value = deskWindow; NaviKeep.car = carName } }
    var testDrive by remember { object : androidx.compose.runtime.MutableState<Boolean> {
        override var value: Boolean get() = NaviKeep.testDrive.value ?: deskWindow; set(v) { NaviKeep.testDrive.value = v }
        override fun component1() = value
        override fun component2(): (Boolean) -> Unit = { value = it }
    } }
    // a trip lost with the connection (the car switched off at a charging stop): offered as "Fortsetzen", not in tests
    var resume by remember { mutableStateOf<NaviResume.Saved?>(null) }
    LaunchedEffect(testDrive) {
        resume = if (testDrive) null else NaviResume.load(context)
        resume?.let { Log.i(TAG, "resume offered: ${it.own.size} own stop(s), saved ${(System.currentTimeMillis() - it.atMillis) / 60_000} min ago") }
    }
    val night = net.prototux.aare.ui.theme.LocalCarNight.current
    // with the net the map server, without it the package on the device that covers where the map starts (one at a time)
    remember { naviFpsSetting = net.prototux.aare.Settings(context).naviFps }
    val online = rememberOnline(context)
    // the package that covers where the car is (its last fix), the test start only without one
    val offlineMap = remember(online) { if (online) null else (CarFixes.last.value?.let { it.longitude to it.latitude } ?: (testFrom().longitude to testFrom().latitude))
        .let { (lon, lat) -> RegionPacks.mapFor(context, lon, lat) } }
    LaunchedEffect(online, offlineMap) { Log.i(TAG, "map source: " + (offlineMap?.file?.parentFile?.name?.let { "package $it (offline)" } ?: "server")) }
    // F2: the traffic layer follows its switch in the route options
    val trafficShown = NaviTrafficSwitch.on.value ?: net.prototux.aare.Settings(context).trafficShown
    val style = remember(night, offlineMap, trafficShown) { BaseStyle.Json(mapStyle(context, night, offlineMap)) }
    // the loaded style's own sources (the guiding veil's among them)
    val styleState = org.maplibre.compose.style.rememberStyleState()
    val camera = rememberCameraState(CameraPosition(target = testFrom(), zoom = START_ZOOM))
    val scope = rememberCoroutineScope()
    val parts = remember { NaviServer.install(context); RouteOptions.of(net.prototux.aare.Settings(context)).let { o -> NaviKeep.parts(context, o) { sc, lv -> NaviSession(sc, NaviServer.valhalla(context), NaviServer.client(context), o, lv) } } }
    val session = parts.first
    val live = parts.second
    // left while guiding, the trip goes on (the voice and the car's displays from NaviKeep); otherwise it ends here
    DisposableEffect(session) { onDispose { if (!NaviKeep.guiding.value) session.stop() } }
    var routeJson by NaviKeep.routeJson
    // F5: the routes not chosen in the preview, grey under the chosen one (one feature collection)
    var altJson by remember { mutableStateOf<String?>(null) }
    var drawnLine by NaviKeep.drawnLine
    var startedAt by NaviKeep.startedAt
    var plannedKm by NaviKeep.plannedKm
    var done by remember { mutableStateOf<TripDone?>(null) }
    // the last destinations, as pins on the map (wish of 16.11.2026)
    var pins by remember { mutableStateOf(NaviPins.load(context)) }
    var recentPins by remember { mutableStateOf(NaviPins.loadPins(context)) }
    var planError by remember { mutableStateOf<String?>(null) }
    var limits by NaviKeep.limits
    var destination by NaviKeep.destination
    // Choosing the destination (O169): no trip starts by itself; "Ziel" opens the lists, a place gives the preview, from
    // which the guidance starts. In a test drive the simulated car sets off at TEST_FROM (or a test's own start), otherwise from where the car is.
    var panel by remember { mutableStateOf<NaviStep>(NaviStep.None) }
    // "Letzte Ziele": pins without a name get their address from the map server's search while the list is open (O172)
    LaunchedEffect(panel) {
        if (panel != NaviStep.Recent) return@LaunchedEffect
        recentPins = NaviPins.loadPins(context)
        if (NaviPins.fillNames(context, context.getString(net.prototux.aare.R.string.places_on_map))) recentPins = NaviPins.loadPins(context)
    }
    var purpose by remember { mutableStateOf(Purpose.NEW) }
    var target by NaviKeep.target
    var pending by remember { mutableStateOf<net.prototux.aare.places.Places.Place?>(null) }
    // where the car is now (the glide's point), for planning a change or a stop from here
    var carAt by remember { mutableStateOf<Position?>(null) }
    var guiding by NaviKeep.guiding
    var book by remember { mutableStateOf(net.prototux.aare.places.Places.load(context)) }
    var planned by remember { mutableStateOf<uniffi.ferrostar.Route?>(null) }
    var chargeStops by NaviKeep.chargeStops
    // O209: the driver's own stops of the guided trip with their names, for the trip plan; set when a preview is planned
    var ownStops by NaviKeep.ownStops
    var pendingOwn by remember { mutableStateOf<List<Pair<Position, String>>>(emptyList()) }
    // the level on arrival as the tile shows it, for the trip plan
    var arrivalNow by remember { mutableStateOf<NaviCharge.Arrival?>(null) }
    var preview by remember { mutableStateOf<Preview?>(null) }
    var routeOptions by remember { mutableStateOf<List<RouteOption>>(emptyList()) }
    // F5: the fast chargers along the shown route, the planned stops apart (GeoJSON: stops, others)
    var chargerJson by remember { mutableStateOf<Pair<String, String>?>(null) }
    // System L-099: the other chargers on the map, each with its network's logo (the shown ones are what a tap picks from)
    var chargerOthers by remember { mutableStateOf<List<Charger>>(emptyList()) }
    // O220: the closed stretches that touched the planned routes - the only closures drawn
    var closedLines by remember { mutableStateOf(session.closureLines) }
    // F5: rest stops along it (GeoJSON: service areas, rest areas)
    var restJson by remember { mutableStateOf<Pair<String, String>?>(null) }
    var chosen by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var countdown by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // F3: where the preview was planned from and to (for planning a stop over another charger), and whether the driver
    // looks at the stops (the preview then does not start by itself)
    var planFrom by remember { mutableStateOf<Triple<Position, Position, List<Position>>?>(null) }
    var holdStart by remember { mutableStateOf(false) }
    // the plans in the works belong to the newest preview only; a trip started before its charging plan was done waits for it
    var planGen by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var startedPending by remember { mutableStateOf<NaviSession.Kind?>(null) }
    LaunchedEffect(session) { session.simulated.warpFactor = SIM_WARP.toUInt() }
    LaunchedEffect(testDrive) {
        // a test drive: the map waits at the simulation's start, no real position shown (the picture tool checks this line)
        if (testDrive) Log.i(TAG, "position source: simulated (waiting at the test start)")
        else {
            live.reset(); Log.i(TAG, "position source: live (waiting for a fix)")
            // the map opens where the car was last, not at the test start in Berlin, until the first fix comes
            if (!guiding) runCatching { live.lastLocation() }.getOrNull()?.let { l ->
                camera.position = camera.position.copy(target = Position(longitude = l.longitude, latitude = l.latitude))
            }
        }
    }
    fun show(route: uniffi.ferrostar.Route) {
        val raw = route.geometry.map { Position(longitude = it.lng, latitude = it.lat) }
        val drawn = NaviRoute.smooth(raw)
        routeJson = NaviRoute(drawn).geoJson()
        destination = raw.lastOrNull()
        plannedKm = route.distance / 1000
        Log.i(TAG, "line smoothed: ${raw.size} -> ${drawn.size} points")
    }
    /**
     * [c] with its charging stops (O182): none needed - as it is; needed - planned again over the chargers and timed on
     * the new route; the alternative cannot be kept over chargers and is left out then.
     */
    suspend fun charged(c: NaviSession.Choice, car: net.prototux.aare.host.EnergyModel.Car, from: Position, to: Position, via: List<Position>, onlyOne: Boolean = false,
                        fixed: List<Charger> = emptyList()): NaviSession.Choice? {
        // the index, IDmate's rain, the heights and the weather side by side, each with a limit (19.11.2026: "Bei langen Routen
        // ... dauert das über 30 Sekunden bis zu einer Minute ... bei schlechter Verbindung"): without an answer in time
        // the plan goes on without it, as it does without the server's heights or the weather
        // started apart from this plan (the screen's scope): a call that does not answer in time is left to finish on its
        // own and does not hold the plan up (the heights it brings are kept for the next plan of the same route)
        val (index, heights, weather) = run {
            val idx = scope.async(kotlinx.coroutines.Dispatchers.IO) { runCatching { ChargerIndex.get(context) } }
            val rain = scope.async(kotlinx.coroutines.Dispatchers.IO) { runCatching { NaviRain.refresh(context) } }
            // Navi L-029 step A: the heights of the route, once per route from the map server (the climb of each piece)
            val h = scope.async(kotlinx.coroutines.Dispatchers.IO) { runCatching {
                ChargeHeights.forLine(NaviServer.client(context), NaviServer.valhalla(context), raw(c.route)) { chargeLog("charging: ${c.kind} heights $it") } }.getOrNull() }
            // L-029 step G: wind, temperature and rain on the way at the time the car gets there (Open-Meteo)
            val w = scope.async(kotlinx.coroutines.Dispatchers.IO) { runCatching { ChargeWeather.along(raw(c.route)) { chargeLog("charging: ${c.kind} $it") } }.getOrNull() }
            val hs = kotlinx.coroutines.withTimeoutOrNull(HEIGHTS_WAIT_MS) { h.await() }
                .also { if (it == null && !h.isCompleted) chargeLog("charging: ${c.kind} heights not in ${HEIGHTS_WAIT_MS / 1000} s - planned flat") }
            val ws = kotlinx.coroutines.withTimeoutOrNull(WEATHER_WAIT_MS) { w.await() }
                .also { if (it == null && !w.isCompleted) chargeLog("charging: ${c.kind} weather not in ${WEATHER_WAIT_MS / 1000} s - planned without it") }
            kotlinx.coroutines.withTimeoutOrNull(RAIN_WAIT_MS) { rain.await() }
            if (NaviRain.active) chargeLog("charging: rain taken in (IDmate's raster and learned extra per grade)")
            Triple(idx.await().getOrThrow(), hs, ws)
        }
        val weatherNote = weather?.describe() ?: "no weather (planned as before)"
        if (heights != null) NaviKeep.heights = heights
        NaviKeep.weather = weather
        fun tr(r: uniffi.ferrostar.Route) = ChargePlanner.track(r, car, heights = heights, weather = weather)
        val settings = net.prototux.aare.Settings(context)
        // home with a wallbox: the car may arrive with the driver's value for home (O205), it charges there anyway
        val home = net.prototux.aare.places.Places.isHomeWithWallbox(net.prototux.aare.places.Places.load(context), to.latitude, to.longitude)
        val rules = ChargePlanner.Rules.of(settings).let { if (home) it.copy(atDestination = settings.chargeMinAtHome.toDouble()) else it }
        if (home) chargeLog("charging: destination is home with a wallbox, at least ${settings.chargeMinAtHome} % there")
        // no buffer on the least level (Navi O-013, 19.11.2026: "Der Puffer fällt ganz weg. Das Modell soll ja selbstrechnend
        // sein"): the least level of the phone app holds as it is; the model's error is what the drive measures (ChargeRolling)
        // a note, not a fault: the word "error" in it made it an ERROR in the log's list of problems, at every route (21.11.2026)
        chargeLog("charging: ${c.kind} no buffer on the least level; the model's error of past stretches, for the record: " + ChargeBuffer.of(context).describe(),
            net.prototux.aare.DiagLog.Level.INFO)
        // O225: a stop of the driver's at a charger is a charging stop - charged there, never left out
        // [fixed]: charging stops kept from the plan before (on the part of the route a move left as it was) - kept like these
        val ownChargers = via.mapNotNull { p -> index.near(p, OWN_CHARGER_M, 0).minByOrNull { ChargerIndex.distanceM(it.at, p) } } + fixed
        if (ownChargers.isNotEmpty()) chargeLog("charging: ${ownChargers.size} own stop(s) at a charger")
        val options = RouteOptions.of(settings)
        // Navi L-029 step E: the chain on the device, one request for the route over its chargers, at most three in all
        val routing = ChargeRouting<uniffi.ferrostar.Route>(
            route = { chargers -> session.planOne(c.kind, from, to, NaviStops.inOrder(raw(c.route), via + chargers.map { it.at }), options) },
            trackOf = ::tr,
            matrix = { cands, track -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ChargeMatrix.fetch(NaviServer.client(context), NaviServer.valhalla(context), cands, track) } })
        // an alternative is not planned over chargers as well (it stays grey) - unless it is the only route left
        val overStops = !(c.kind == NaviSession.Kind.ALT && !onlyOne)
        val out = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            routing.plan(c.route, car, car.socPercent, index::near, rules, ownChargers, overStops)
        }
        val plan = out.plan
        // one line per plan (L-029 Diagnose): candidates, chain, arrivals, buffer, weather, requests, time on the device
        chargeLog(ChargeRouting.logLine(c.kind.name, out.route.distance / 1000, car.socPercent, out.plan, out, rules, weatherNote))
        tr(out.route).parts?.let { chargeLog("charging: ${c.kind} energy: $it") }
        out.notes.forEach { chargeLog("charging: ${c.kind} $it") }
        if (out.first.field.isNotEmpty()) chargeLog("charging: ${c.kind} candidates - ${out.first.field}")
        // a plan that does not reach the destination with its stops: every stop into the log (P-044)
        if (plan.arrivePercent < rules.atDestination - 0.5 && plan.stops.isNotEmpty())
            chargeLog("charging: ${c.kind} falls short - charged up to ${rules.chargeUpTo.toInt()} %, preferred: ${rules.preferred.ifEmpty { setOf("none") }.joinToString()}")
        if (plan.stops.isEmpty()) return NaviSession.Choice(c.kind, c.route, plan)
        if (!overStops) return null
        return NaviSession.Choice(c.kind, out.route, plan)
    }
    /** One of the routes chosen in the preview: on the map, its figures in the panel, the countdown afresh. */
    fun pick(i: Int) {
        val o = routeOptions.getOrNull(i) ?: return
        chosen = i; planned = o.route; preview = o.preview; show(o.route)
        altJson = routeOptions.filterIndexed { k, _ -> k != i }.takeIf { it.isNotEmpty() }?.let { others ->
            org.json.JSONObject().put("type", "FeatureCollection").put("features", org.json.JSONArray(others.map { org.json.JSONObject(NaviRoute(NaviRoute.smooth(raw(it.route))).geoJson()) })).toString()
        }
    }
    /**
     * Navi L-029 step F: the charging plan of [route] kept up to date while driving - its stops (the driver's own at a
     * charger among them, never left out) checked every few km against the real level.
     */
    fun guidedTrack(r: uniffi.ferrostar.Route, car: net.prototux.aare.host.EnergyModel.Car) =
        ChargePlanner.track(r, car, heights = NaviKeep.heights, weather = NaviKeep.weather)
    fun startRolling(route: uniffi.ferrostar.Route) {
        val car = net.prototux.aare.host.CarDataService.car(context) ?: run { NaviKeep.rolling = null; return }
        val index = ChargerIndex.get(context)
        val own = ownStops.mapNotNull { (p, _) -> index.near(p, OWN_CHARGER_M, 0).minByOrNull { ChargerIndex.distanceM(it.at, p) } }
        NaviKeep.rolling = ChargeRolling(guidedTrack(route, car), car, ChargePlanner.Rules.of(net.prototux.aare.Settings(context)), index::near,
            car.socPercent, chargeStops.map { it.charger }.filter { it !in own }, own, ChargeBuffer.of(context).missPer100) { missed, km ->
            ChargeBuffer.note(context, missed, km)
            chargeLog("charging: stretch of %.0f km driven, the car used %+.1f %% more than the model said (hit list)".format(java.util.Locale.ROOT, km, missed))
        }
        NaviKeep.rollingRoute = route; NaviKeep.rollingDriven = 0.0
    }
    /** A planned route as an option of the preview: its figures, its charging stops and the route as a list (L-035). */
    fun optionOf(c: NaviSession.Choice, car: net.prototux.aare.host.EnergyModel.Car?, name: String, own: List<Pair<Position, String>>, destination: String,
                 pendingCharge: Boolean = false): RouteOption {
        val r = c.route
        val wh = car?.let { NaviCharge.energyWh(r.steps, null, it) }
        val plan = c.charging
        val pct = plan?.arrivePercent ?: if (car != null && wh != null) car.socPercent - wh / car.capacityWh * 100 else null
        return RouteOption(c.kind, r, Preview(name, r.distance / 1000, r.steps.sumOf { it.duration } / 60.0 + (plan?.stayMinutes ?: 0.0),
            wh?.let { it / 1000 }, pct, pct != null && pct < NaviCharge.reservePercent(context), plan?.stops.orEmpty(),
            planRows(r, own, plan?.stops.orEmpty(), destination, pct), pending = pendingCharge))
    }
    /**
     * The trip began before its charging plan was done: from where the car is now over the planned chargers ([c]'s stops,
     * with the own stops still ahead), timed from the level now; guidance and the plan on the way go on with that route.
     * Without stops in the plan the route stays as it is.
     */
    fun switchToPlan(c: NaviSession.Choice, car: net.prototux.aare.host.EnergyModel.Car, to: Position) {
        val stops = c.charging?.stops.orEmpty()
        if (stops.isEmpty()) { chargeStops = emptyList(); chargeLog("charging: plan done while driving - no stops needed, the route stays"); return }
        val here = carAt ?: return
        scope.launch {
            val own = ownStops.map { it.first }
            val r = runCatching { session.planOne(c.kind, here, to, NaviStops.inOrder(raw(c.route), own + stops.map { it.charger.at }), RouteOptions.of(net.prototux.aare.Settings(context))) }
                .onFailure { chargeLog("charging: plan done while driving, but the route over its stops failed: ${it.message ?: it} - the plan on the way takes over") }
                .getOrNull() ?: return@launch
            if (!guiding) return@launch
            val now = net.prototux.aare.host.CarDataService.car(context) ?: car
            val plan = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                ChargePlanner.replay(guidedTrack(r, now), now, now.socPercent, stops.map { it.charger }, ChargePlanner.Rules.of(net.prototux.aare.Settings(context)))
            }
            chargeStops = plan.stops
            session.go(r, simulate = testDrive)
            startRolling(r)
            drawnLine = NaviRoute.smooth(raw(r))
            limits = null
            scope.launch { limits = NaviLimits.load(context, raw(r)) }
            chargeLog("charging: plan done while driving - now over " + plan.stops.joinToString { NaviTripPlan.chargerName(it.charger) } +
                ", %.0f %% at the destination".format(plan.arrivePercent))
        }
    }
    /**
     * The charging plan of the route [kind] is done ([out]; null: an alternative that would need stops, dropped as before):
     * the route's option in the preview gets it - the chosen one shown anew, without moving the picture. A trip started on
     * that route before the plan was there goes on over the planned chargers from where the car is now ([switchToPlan]).
     */
    fun chargePlanned(kind: NaviSession.Kind, out: NaviSession.Choice?, car: net.prototux.aare.host.EnergyModel.Car, name: String,
                      own: List<Pair<Position, String>>, destination: String, to: Position) {
        if (startedPending == kind && guiding) {
            startedPending = null
            if (out != null) switchToPlan(out, car, to)
        }
        if (panel !is NaviStep.Preview) return
        val i = routeOptions.indexOfFirst { it.kind == kind }
        if (i < 0) return
        val wasChosen = i == chosen
        if (out == null) {
            routeOptions = routeOptions.filterIndexed { k, _ -> k != i }
            if (routeOptions.isEmpty()) return
            pick(if (wasChosen) 0 else if (chosen > i) chosen - 1 else chosen)
            return
        }
        routeOptions = routeOptions.toMutableList().also { it[i] = optionOf(out, car, name, own, destination) }
        if (wasChosen) pick(i) else pick(chosen)
    }
    fun choose(place: net.prototux.aare.places.Places.Place, resumeOwn: List<Pair<Position, String>>? = null) {
        panel = NaviStep.Preview(place); preview = null; planned = null; planError = null; routeOptions = emptyList()
        // from where the car is now; a stop goes before the destination that is guided to
        val from = if (testDrive) (if (guiding) carAt ?: testFrom() else testFrom()) else carAt ?: run {
            // no position yet (no GPS fix from the car nor the phone): said, not planned from somewhere
            planError = context.getString(net.prototux.aare.R.string.mate_navi_no_position); null
        }
        if (from == null) { Log.i(TAG, "route: no position yet"); return }
        val stop = purpose == Purpose.STOP && target != null
        val to = if (stop) target!! else place
        // a stop joins the driver's own stops still ahead (O222: several stops - a new one replaced the last before)
        // an own stop counts as ahead while a waypoint within OWN_SNAP_M is left - Valhalla puts a stop off the road onto
        // the nearest one, so its waypoint can lie a few hundred metres off (17.11.: the first stop got lost)
        val ownAhead = if (stop) ownStops.filter { (p, _) -> (session.state.value.tripState as? TripState.Navigating)?.remainingWaypoints?.any { w ->
            ChargerIndex.distanceM(Position(longitude = w.coordinate.lng, latitude = w.coordinate.lat), p) < OWN_SNAP_M } == true } else emptyList()
        val newOwn = resumeOwn ?: if (stop) {
            // the new stop where it lies along the guided route (17.11.: added last, the trip grew to 522 km)
            val all = ownAhead + (Position(longitude = place.lon, latitude = place.lat) to place.name)
            session.route.value?.let { g -> val order = NaviStops.inOrder(raw(g), all.map { it.first }); order.map { p -> all.first { it.first == p } } } ?: all
        } else emptyList()
        val via = newOwn.map { it.first }
        if (stop) Log.i(TAG, "stop added: ${ownStops.size} own before, ${ownAhead.size} still ahead, ${newOwn.size} now; waypoints left " +
            ((session.state.value.tripState as? TripState.Navigating)?.remainingWaypoints?.size ?: -1))
        pending = to
        pendingOwn = newOwn
        holdStart = false
        planFrom = Triple(from, Position(longitude = to.lon, latitude = to.lat), via)
        scope.launch {
            val askedAt = android.os.SystemClock.elapsedRealtime()
            val choices = runCatching {
                session.planChoices(from, Position(longitude = to.lon, latitude = to.lat), via, RouteOptions.of(net.prototux.aare.Settings(context)),
                    // F2: around the closed roads (with the traffic shown and the net)
                    closuresAlong = if (trafficShown && offlineMap == null) ({ line -> NaviTraffic.closuresAlong(context, line) }) else null,
                    // System L-095 (22.11.2026: "dass ich die Route sage und er dann locker 30 Sekunden braucht … dass er die
                    // Route so schnell wie möglich anzeigt, damit man losfahren kann. Und dass man sieht, es tut sich was"):
                    // Valhalla's routes on the map and in the panel at once; the closures and then the charging stops follow
                    onFirst = { first -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (panel !is NaviStep.Preview || routeOptions.isNotEmpty()) return@withContext
                        val car0 = net.prototux.aare.host.CarDataService.car(context)
                        val name0 = if (stop) "${place.name} → ${to.name}" else place.name
                        routeOptions = first.map { c -> optionOf(c, car0, name0, newOwn, to.name, pendingCharge = car0 != null) }
                        pick(0)
                        net.prototux.aare.DiagLog.add("route: shown after %.1f s, closures and charging stops still to come".format(java.util.Locale.ROOT,
                            (android.os.SystemClock.elapsedRealtime() - askedAt) / 1000.0), area = "NAVI")
                        val all0 = routeOptions.flatMap { raw(it.route) }
                        if (all0.isNotEmpty()) scope.launch { runCatching { camera.animateTo(org.maplibre.spatialk.geojson.BoundingBox(all0.minOf { it.longitude }, all0.minOf { it.latitude },
                            all0.maxOf { it.longitude }, all0.maxOf { it.latitude }), 0.0, 0.0, PaddingValues(start = 640.dp, top = 80.dp, end = 80.dp, bottom = 160.dp)) } }
                    } })
            }.onFailure { e ->
                    Log.w(TAG, "route: $e")
                    // said in words instead of planning for ever: token refused, server out of reach, or no way there;
                    // routes already shown stay without it (the preview shows the error above them now, Navi I-052f)
                    if (routeOptions.isEmpty()) planError = context.getString(when {
                        NaviServer.rejected.value -> net.prototux.aare.R.string.mate_navi_token_rejected
                        e is java.io.IOException || e.cause is java.io.IOException -> net.prototux.aare.R.string.mate_navi_route_offline
                        else -> net.prototux.aare.R.string.mate_navi_route_none
                    })
                }.getOrNull() ?: return@launch
            closedLines = session.closureLines
            val car = net.prototux.aare.host.CarDataService.car(context)
            val name = if (stop) "${place.name} → ${to.name}" else place.name
            val toPos = Position(longitude = to.lon, latitude = to.lat)
            // closed while planning: nothing to show any more (a running trip keeps its line)
            if (panel !is NaviStep.Preview) return@launch
            // the routes at once (19.11.2026: "damit die Oberfläche schneller erscheint, damit man schneller losfahren kann"):
            // shown and startable as they come; the charging stops are planned behind them and fill in each route as its
            // plan is done (O182)
            val gen = ++planGen
            val shownFirst = routeOptions.isNotEmpty()
            val keep = chosen
            routeOptions = choices.map { c -> optionOf(c, car, name, newOwn, to.name, pendingCharge = car != null) }
            // the routes shown first stay chosen where the driver picked one; the closures may have changed them
            pick(if (shownFirst) keep.coerceIn(0, routeOptions.size - 1) else 0)
            net.prototux.aare.DiagLog.add("route: routes final after %.1f s (closures looked at)".format(java.util.Locale.ROOT,
                (android.os.SystemClock.elapsedRealtime() - askedAt) / 1000.0), area = "NAVI")
            Log.i(TAG, "routes to choose: " + routeOptions.joinToString { "%s %.1f km %.0f min".format(it.kind, it.preview.km, it.preview.minutes) } +
                (if (car != null) " - charging stops planned behind them" else ""))
            // all routes in the picture beside the panel
            val all = routeOptions.flatMap { raw(it.route) }
            val b = org.maplibre.spatialk.geojson.BoundingBox(all.minOf { it.longitude }, all.minOf { it.latitude }, all.maxOf { it.longitude }, all.maxOf { it.latitude })
            scope.launch { camera.animateTo(b, 0.0, 0.0, PaddingValues(start = 640.dp, top = 80.dp, end = 80.dp, bottom = 160.dp)) }
            if (car == null) { chargeLog("charging: no car profile or no battery level - no charging planned"); return@launch }
            val began = android.os.SystemClock.elapsedRealtime()
            // with the car's profile each route is planned over chargers where the battery would not reach (O182), all side by
            // side; each on its own (Navi I-003): a failure in one took the charging stops of all of them away
            val done = kotlinx.coroutines.coroutineScope { choices.map { c -> async {
                val out = runCatching { charged(c, car, from, toPos, via, onlyOne = choices.size == 1) }
                    .onFailure { e -> if (e is kotlinx.coroutines.CancellationException) throw e; Log.w(TAG, "charging plan failed for ${c.kind}", e); chargeLog("charging: plan failed for ${c.kind}, without charging stops: ${e.message ?: e}") }
                    .getOrElse { c }
                if (planGen == gen) chargePlanned(c.kind, out, car, name, newOwn, to.name, toPos)
                out
            } }.map { it.await() } }
            chargeLog("charging: from %.0f %% (%s), stops %s, planned in %.1f s".format(car.socPercent, if (net.prototux.aare.host.CarDataService.debugSoc != null) "test" else "IDmate",
                done.joinToString { it?.charging?.stops?.size?.toString() ?: "-" }, (android.os.SystemClock.elapsedRealtime() - began) / 1000.0))
        }
    }
    /**
     * A preview left without starting: while guiding the guided route comes back on the map (16.11.2026: "Abbrechen"
     * of a new preview took the line of the running trip away), otherwise the map is empty again.
     */
    fun dropPreview() {
        val g = session.route.value
        preview = null; routeOptions = emptyList(); altJson = null
        if (guiding && g != null) show(g) else routeJson = null
    }
    /**
     * The chosen route planned over [chargers] (in any order; put along the route here) instead of its stops - another
     * charger for a stop (F3), a stop taken out or in again (L-035): planned again over the own stops and these chargers,
     * timed anew, the preview's figures and list updated in place. [removed] what the driver took out, kept to take in again.
     */
    fun setChargers(chargers0: List<Charger>, removed: List<Charger>, what: String) {
        val o = routeOptions.getOrNull(chosen) ?: return
        planGen++          // a plan still in the works would undo the driver's change
        val (from, to, via) = planFrom ?: return
        val car = net.prototux.aare.host.CarDataService.car(context) ?: return
        holdStart = true
        val line = raw(o.route)
        val chargers = NaviStops.inOrder(line, chargers0.map { it.at }).map { p -> chargers0.first { it.at == p } }
        scope.launch {
            val r = runCatching { session.planOne(o.kind, from, to, NaviStops.inOrder(line, via + chargers.map { it.at }), RouteOptions.of(net.prototux.aare.Settings(context))) }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "charging: $what: $e")
                    // said, and the countdown goes on with the plan as it was (Navi I-052f)
                    planError = context.getString(net.prototux.aare.R.string.mate_navi_stops_change_failed); holdStart = false
                }.getOrNull() ?: return@launch
            val rules = ChargePlanner.Rules.of(net.prototux.aare.Settings(context))
            val plan = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                ChargePlanner.replay(ChargePlanner.track(r, car, heights = NaviKeep.heights, weather = NaviKeep.weather), car, car.socPercent, chargers, rules)
            }
            val wh = NaviCharge.energyWh(r.steps, null, car)
            val prev = o.preview
            val updated = RouteOption(o.kind, r, Preview(prev.name, r.distance / 1000, r.steps.sumOf { it.duration } / 60.0 + plan.stayMinutes,
                wh / 1000, plan.arrivePercent, plan.arrivePercent < NaviCharge.reservePercent(context), plan.stops,
                planRows(r, pendingOwn, plan.stops, pending?.name.orEmpty(), plan.arrivePercent), removed))
            if (panel !is NaviStep.Preview) return@launch
            planError = null
            routeOptions = routeOptions.toMutableList().also { it[chosen] = updated }
            pick(chosen)
            chargeLog("charging: $what: " + plan.stops.joinToString { "%s %d kW %.0f%%->%.0f%% %.0f min".format(it.charger.network ?: "other", it.charger.kw, it.arrivePercent, it.leavePercent, it.chargeMinutes) }
                .ifEmpty { "no stops" } + ", %.0f %% at the destination".format(plan.arrivePercent))
        }
    }
    fun replaceStop(old: Charger, charger: Charger) {
        val o = routeOptions.getOrNull(chosen) ?: return
        setChargers(o.preview.stops.map { if (it.charger === old) charger else it.charger }, o.preview.removed,
            "stop over ${charger.network ?: "other"} ${charger.kw} kW instead")
    }
    fun removeStop(c: Charger) {
        val o = routeOptions.getOrNull(chosen) ?: return
        setChargers(o.preview.stops.map { it.charger }.filter { it !== c }, o.preview.removed + c, "stop taken out by the driver")
    }
    fun restoreStop(c: Charger) {
        val o = routeOptions.getOrNull(chosen) ?: return
        setChargers(o.preview.stops.map { it.charger } + c, o.preview.removed.filter { it !== c }, "stop taken in again by the driver")
    }
    fun removeAllStops() {
        val o = routeOptions.getOrNull(chosen) ?: return
        setChargers(emptyList(), o.preview.removed + o.preview.stops.map { it.charger }, "all stops taken out by the driver")
    }
    /**
     * Own stops and destination in a new order from the preview's list ([order], the destination last; L-035, 19.11.2026:
     * "dass dann das Zwischenziel zum Endziel wird ... dass er bei solchen Änderungen quasi nicht immer die komplette Route
     * neu berechnen muss ... wenn ich am Ende der Route etwas verschiebe, dann ändert sich ja die erste Ladestation nicht").
     * The route up to the last place that stayed where it was is the same: its charging stops are kept as they are, the
     * chosen route is planned once over them and the stops in the new order, and charging stops are planned only where
     * the rest needs them. The other routes of the preview (eco, alternative) were for the old order and go.
     */
    fun reorderStops(order: List<Pair<Position, String>>) {
        val o = routeOptions.getOrNull(chosen) ?: return
        val (from, _, _) = planFrom ?: return
        if (order.isEmpty()) return
        val rows = o.preview.plan
        val places = rows.filter { (it.kind == NaviTripPlan.Kind.OWN || it.kind == NaviTripPlan.Kind.DESTINATION) && it.at != null }
        val k = order.indices.firstOrNull { places.getOrNull(it)?.at != order[it].first } ?: return
        // the route as it was up to the place before the first one moved: its waypoints (own stops and chargers) kept
        val keepTo = if (k == 0) -1 else rows.indexOf(places[k - 1])
        // an own stop without its place is left out (rows elsewhere check "at != null" too; it.at!! below took Mate down)
        val keptRows = rows.take(keepTo + 1).filter { (it.kind == NaviTripPlan.Kind.OWN && it.at != null) || (it.kind == NaviTripPlan.Kind.CHARGE && it.charger != null) }
        val kept = keptRows.mapNotNull { it.charger }
        val newDest = order.last(); val newOwn = order.dropLast(1)
        val to = net.prototux.aare.places.Places.Place("map", "", newDest.second, "", newDest.first.latitude, newDest.first.longitude)
        val car = net.prototux.aare.host.CarDataService.car(context)
        Log.i(TAG, "stops reordered in the preview: ${order.size} place(s), the first ${k} as before, ${kept.size} charging stop(s) kept")
        if (car == null) { purpose = Purpose.NEW; choose(to, newOwn); return }
        pending = to; pendingOwn = newOwn; holdStart = true; planGen++
        planFrom = Triple(from, newDest.first, newOwn.map { it.first })
        val via = keptRows.mapNotNull { it.charger?.at ?: it.at } + newOwn.drop(keptRows.count { it.kind == NaviTripPlan.Kind.OWN }).map { it.first }
        preview = null
        scope.launch {
            val r = runCatching { session.planOne(o.kind, from, newDest.first, via, RouteOptions.of(net.prototux.aare.Settings(context))) }
                .onFailure { e -> Log.w(TAG, "route: reorder: $e"); planError = context.getString(net.prototux.aare.R.string.mate_navi_route_none) }
                .getOrNull() ?: return@launch
            val c = runCatching { charged(NaviSession.Choice(o.kind, r), car, from, newDest.first, newOwn.map { it.first }, onlyOne = true, fixed = kept) }
                .onFailure { e -> if (e is kotlinx.coroutines.CancellationException) throw e; chargeLog("charging: plan failed after the reorder: ${e.message ?: e}") }
                .getOrNull() ?: NaviSession.Choice(o.kind, r)
            if (panel !is NaviStep.Preview) return@launch
            routeOptions = listOf(optionOf(c, car, newDest.second, newOwn, newDest.second))
            pick(0)
            chargeLog("charging: reordered, ${kept.size} stop(s) kept from before, now " +
                (c.charging?.stops?.joinToString { NaviTripPlan.chargerName(it.charger) }?.ifEmpty { null } ?: "no stops"))
        }
    }
    /** The device's own plan again (L-035 "Ladestopps neu berechnen"): the trip planned afresh with the own stops as they are. */
    fun recalcStops(order: List<Pair<Position, String>> = pendingOwn) {
        val to = pending ?: return
        chargeLog("charging: planned anew by the driver (${order.size} own stop(s))")
        purpose = Purpose.NEW; choose(to, order)
    }
    fun end() {
        // ended close to the destination: it counts for "Letzte Ziele" (Navi I-001)
        (session.state.value.tripState as? TripState.Navigating)?.let { t ->
            if (guiding && !testDrive) { NaviKeep.notePin(context, t.progress.distanceRemaining < END_NEAR_M); pins = NaviPins.load(context) } }
        startedPending = null
        session.stop(); guiding = false; panel = NaviStep.None; routeJson = null; drawnLine = emptyList(); target = null; limits = null
        NaviKeep.rolling = null
        NaviResume.clear(context)
        Log.i(TAG, "guidance ended by the driver")
    }
    fun start() {
        val route = planned ?: return
        // the charging stops planned for it (O182), so the battery on arrival counts their charge while driving
        chargeStops = preview?.stops.orEmpty()
        // started before the charging plan was done: the trip goes on over its stops once it is (switchToPlan)
        startedPending = if (preview?.pending == true) routeOptions.getOrNull(chosen)?.kind else null
        if (startedPending != null) chargeLog("charging: started before the charging plan was done - it follows")
        ownStops = pendingOwn
        panel = NaviStep.None
        target = pending
        session.go(route, simulate = testDrive)
        startRolling(route)
        // kept for "Fortsetzen" over a lost connection - a real trip only
        if (!testDrive) pending?.let { NaviResume.save(context, it, pendingOwn) }
        resume = null
        guiding = true
        // the speed limits along it, asked once from Valhalla (the route itself brings none)
        limits = null
        scope.launch { limits = NaviLimits.load(context, raw(route)).also { Log.i(TAG, "speed limits: " + (if (it == null) "none" else "loaded")) } }
        drawnLine = NaviRoute.smooth(raw(route))
        // a new trip plans its stops again (taken out only for the trip it was done on)
        if (!NaviKeep.continuing) NaviKeep.noNewStops = false
        // a trip going on after a stop keeps its way driven, its time and its level at the start
        if (!NaviKeep.continuing || startedAt == 0L) {
            startedAt = android.os.SystemClock.elapsedRealtime()
            NaviKeep.startTrip(net.prototux.aare.host.CarDataService.car(context)?.socPercent)
        }
        NaviKeep.continuing = false
        // the picture tool takes this line as the source: "simulated" only for a test drive (a live trip said "simulated"
        // at first, 16.11.2026 - pictures of the real position would have been allowed)
        Log.i(TAG, "position source: %s; route %.1f km, %d steps".format(if (testDrive) "simulated" else live.source ?: "live", route.distance / 1000, route.steps.size))
    }
    // the trip goes on by itself after a stop (19.11.2026: "dass nach einem Ladestopp die Route weiterläuft, inklusive
    // Wegstrecke, Linie"): a trip lost with the connection within [AUTO_RESUME_MS] is planned anew from here as soon as
    // the car's position is known - the preview's countdown starts it; the way driven and the time count on
    LaunchedEffect(resume, carAt != null, guiding) {
        val r = resume ?: return@LaunchedEffect
        if (testDrive || guiding || carAt == null || NaviKeep.autoResumed == r.atMillis) return@LaunchedEffect
        if (System.currentTimeMillis() - r.atMillis > AUTO_RESUME_MS) return@LaunchedEffect
        NaviKeep.autoResumed = r.atMillis
        NaviKeep.continuing = true
        net.prototux.aare.DiagLog.add("navigation: the trip goes on after the stop (saved ${(System.currentTimeMillis() - r.atMillis) / 60_000} min ago, ${r.own.size} own stop(s)) - planned anew from here", area = "NAVI")
        purpose = Purpose.NEW
        choose(r.place, r.own)
    }
    // F4: Mate Navi's voice (loud / only warnings / mute, O192)
    val speaker = parts.third
    var voiceMode by remember { mutableStateOf(speaker.mode) }
    // The first announcements of the route chosen in the preview are computed while it is looked at (18.11.2026, the
    // drive's log: "voice: the device's voice was not ready in 6 s, the phone's said ..." four times right after a
    // start - the voice had to be loaded first, so the phone's voice began the trip). Not while another trip is guided:
    // its own announcements are in the works then.
    LaunchedEffect(planned, guiding) {
        val r = planned ?: return@LaunchedEffect
        if (guiding) return@LaunchedEffect
        speaker.prepare(r.steps.asSequence().flatMap { st -> st.spokenInstructions.asSequence().map { it.text } }.take(8).toList())
    }
    // the voice is NaviKeep's and lives on with the trip: not released here (it shut the speech down for the guidance
    // behind other areas, 16.11.2026); only quiet when nothing is guided
    DisposableEffect(speaker) { onDispose { if (!NaviKeep.guiding.value) speaker.stop() } }
    // the guided route (F1: changes after a new route); the warnings along it come from NaviKeep (O193/O194)
    val guided by session.route.collectAsState()
    // F1: a new route after leaving the old one - drawn, its limits loaded, the car glides on along it
    LaunchedEffect(session) {
        var seen = 0
        session.route.collect { r ->
            if (r == null || !guiding || session.reroutes == seen) return@collect
            seen = session.reroutes
            // the warning "new route" is said from NaviKeep, shown or not
            show(r)
            drawnLine = NaviRoute.smooth(raw(r))
            limits = null
            launch { limits = NaviLimits.load(context, raw(r)) }
        }
    }
    // F5: chargers along the route shown - in the preview the chosen route and its stops, while guiding the guided one
    val previewing = panel is NaviStep.Preview
    val chargeRoute = if (routeJson == null) null else if (guiding && !previewing) guided ?: planned else planned
    val chargeAt = (if (guiding && !previewing) chargeStops else preview?.stops.orEmpty()).map { it.charger.at }
    // System L-099 (23.11.2026: "ich wollte ja einen Reiseplaner alle gefilterten Ladestationen angezeigt bekommen, die in
    // meinem Netzwerk existieren … eventuell einen größeren Umweg in Kauf nehme … Mit Icon"): while planning (preview or
    // trip plan open) every charger of the driver's filter in a wide strip, otherwise the near strong ones as before
    val planningMap = previewing || panel == NaviStep.Plan
    LaunchedEffect(chargeRoute, chargeAt, planningMap) {
        val r = chargeRoute ?: run { chargerJson = null; chargerOthers = emptyList(); restJson = null; return@LaunchedEffect }
        val rules = ChargePlanner.Rules.of(net.prototux.aare.Settings(context))
        val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            if (planningMap) NaviChargers.along(raw(r), chargeAt, ChargerIndex.get(context)::near, NaviChargers.PLAN_M, rules.minKw,
                { c -> !rules.outside(c) }, NaviChargers.PLAN_MAX)
            else NaviChargers.along(raw(r), chargeAt, ChargerIndex.get(context)::near)
        }
        chargerJson = NaviChargers.geoJson(found.stops) to NaviChargers.geoJson(found.others.map { it.at })
        // networks chosen (e.g. only Tesla and IONITY): all of theirs, not only those along the route
        chargerOthers = if (planningMap && rules.preferred.isNotEmpty()) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            NaviChargers.ofNetworks(ChargerIndex.get(context).all(rules.minKw) { !rules.outside(it) }, chargeAt)
        } else found.others
        Log.i(TAG, "chargers on the map: ${found.stops.size} stops, ${found.others.size} others")
        val rest = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { NaviRest.along(context, raw(r)) }
        restJson = NaviChargers.geoJson(rest.filter { it.services }.map { it.at }) to NaviChargers.geoJson(rest.filter { !it.services }.map { it.at })
        Log.i(TAG, "rest stops on the map: ${rest.count { it.services }} service areas, ${rest.count { !it.services }} rest areas")
    }
    // F3: the charging plan checked against the real level every half minute while guiding (as ABRP); a hint offers to
    // plan again from here - shown once per finding until it changes
    var chargeCheck by remember { mutableStateOf<NaviChargeWatch.Result?>(null) }
    var chargeCheckLeft by remember { mutableStateOf<NaviChargeWatch.Verdict?>(null) }
    // Navi L-029 step F: the plan kept up to date with the real level (ChargeRolling); the stops change only when it is
    // worth it, then the route over the new ones is asked once, said and shown. A test drive with a set level has its
    // level made from the model (charged at the stops as planned), so that nothing moves for the level standing still.
    /**
     * A charging stop taken out during the trip, [c] null: all (19.11.2026: "Im Reiseplan der Navi-App möchte ich
     * Ladestationen entfernen, damit ich auch ohne festgelegte Ladestops reisen kann ... nur bei einzelnen Schichten, wo es
     * mir wirklich um die Restkilometerreichweite geht"). The route from here over the own stops and the chargers left; the
     * plan on the way adds or moves no stop for the rest of this trip - the tile shows what the battery holds.
     */
    fun dropCharging(c: Charger?) {
        val r = guided ?: return
        val t = session.state.value.tripState as? TripState.Navigating ?: return
        val keep = if (c == null) emptyList() else chargeStops.filter { it.charger !== c }
        NaviKeep.noNewStops = true
        chargeLog("charging: " + (if (c == null) "all stops taken out by the driver" else "stop taken out by the driver: " + NaviTripPlan.chargerName(c)) +
            " - on with ${keep.size} stop(s), no new ones on the way")
        val here = Position(longitude = t.userLocation.coordinates.lng, latitude = t.userLocation.coordinates.lat)
        val end = t.remainingWaypoints.lastOrNull()?.let { Position(longitude = it.coordinate.lng, latitude = it.coordinate.lat) } ?: return
        val ownAhead = ownStops.map { it.first }.filter { p -> t.remainingWaypoints.any { w ->
            ChargerIndex.distanceM(Position(longitude = w.coordinate.lng, latitude = w.coordinate.lat), p) < OWN_SNAP_M } }
        val via = NaviStops.inOrder(raw(r), ownAhead + keep.map { it.charger.at })
        scope.launch {
            val nr = runCatching { session.planOne(NaviSession.Kind.FAST, here, end, via, RouteOptions.of(net.prototux.aare.Settings(context))) }
                .onFailure { e -> if (e is kotlinx.coroutines.CancellationException) throw e }.getOrNull()
            if (nr == null) { chargeLog("charging: the route without the stop was refused - it stays as it was"); NaviKeep.noNewStops = false; return@launch }
            chargeStops = keep
            session.go(nr, simulate = testDrive)
            show(nr); drawnLine = NaviRoute.smooth(raw(nr)); limits = null
            scope.launch { limits = NaviLimits.load(context, raw(nr)) }
            startRolling(nr)
            chargeCheck = null; chargeCheckLeft = null
            chargeLog("charging: new route %.1f km without the stop(s) taken out".format(java.util.Locale.ROOT, nr.distance / 1000))
        }
    }
    /**
     * System L-092: other chargers for one stop of the running trip - the chosen networks near it (25 km before to 10 km
     * after), still reachable: the level on arrival there, counted from the car's level now and what the way to the stop
     * takes per km, at least the driver's least level at a charger ([NaviCharge.chargerWarnPercent]); none behind the car.
     */
    fun guidedOptions(ch: Charger): List<NaviTripPlan.Option> {
        val r = guided ?: return emptyList()
        val t = session.state.value.tripState as? TripState.Navigating ?: return emptyList()
        val car = net.prototux.aare.host.CarDataService.car(context) ?: return emptyList()
        val stop = chargeStops.firstOrNull { it.charger === ch } ?: return emptyList()
        val driven = r.distance - t.progress.distanceRemaining
        val atM = NaviKeep.rolling?.takeIf { NaviKeep.rollingRoute === r }?.alongM(ch) ?: stop.atM
        val here = ChargePlanner.Stop(ch, atM, stop.arrivePercent, stop.leavePercent, stop.chargeMinutes)
        val perKm = ((car.socPercent - stop.arrivePercent) / ((atM - driven) / 1000).coerceAtLeast(1.0)).coerceIn(0.05, 0.6)
        return ChargePlanner.alternatives(guidedTrack(r, car), here, chargeStops.map { it.charger }, ChargerIndex.get(context)::near,
            ChargePlanner.Rules.of(net.prototux.aare.Settings(context)), max = 60)
            .filter { atM + it.shiftM > driven + 1_000 }
            .map { a -> NaviTripPlan.Option(a.charger, a.shiftM, stop.arrivePercent - a.shiftM / 1000 * perKm) }
            .filter { (it.arrivePercent ?: 100.0) >= NaviCharge.chargerWarnPercent(context) }
    }

    /** System L-092: one stop of the running trip at another charger - the other stops stay, the route goes over the new one. */
    fun swapGuided(old: Charger, charger: Charger) {
        val r = guided ?: return
        val t = session.state.value.tripState as? TripState.Navigating ?: return
        val keep = chargeStops.map { s -> if (s.charger === old) ChargePlanner.Stop(charger, s.atM, s.arrivePercent, s.leavePercent, s.chargeMinutes, s.overLimit) else s }
        chargeLog("charging: stop moved by the driver: ${NaviTripPlan.chargerName(old)} -> ${NaviTripPlan.chargerName(charger)} - the other stops stay")
        val here = Position(longitude = t.userLocation.coordinates.lng, latitude = t.userLocation.coordinates.lat)
        val end = t.remainingWaypoints.lastOrNull()?.let { Position(longitude = it.coordinate.lng, latitude = it.coordinate.lat) } ?: return
        val ownAhead = ownStops.map { it.first }.filter { p -> t.remainingWaypoints.any { w ->
            ChargerIndex.distanceM(Position(longitude = w.coordinate.lng, latitude = w.coordinate.lat), p) < OWN_SNAP_M } }
        val via = NaviStops.inOrder(raw(r), ownAhead + keep.map { it.charger.at })
        scope.launch {
            val nr = runCatching { session.planOne(NaviSession.Kind.FAST, here, end, via, RouteOptions.of(net.prototux.aare.Settings(context))) }
                .onFailure { e -> if (e is kotlinx.coroutines.CancellationException) throw e }.getOrNull()
            if (nr == null) { chargeLog("charging: the route over the other charger was refused - it stays as it was"); return@launch }
            chargeStops = keep
            session.go(nr, simulate = testDrive)
            show(nr); drawnLine = NaviRoute.smooth(raw(nr)); limits = null
            scope.launch { limits = NaviLimits.load(context, raw(nr)) }
            startRolling(nr)
            chargeCheck = null; chargeCheckLeft = null
            chargeLog("charging: new route %.1f km over %s".format(java.util.Locale.ROOT, nr.distance / 1000, NaviTripPlan.chargerName(charger)))
        }
    }
    LaunchedEffect(guided, guiding) {
        chargeCheck = null; chargeCheckLeft = null
        val r = guided ?: return@LaunchedEffect
        if (!guiding) return@LaunchedEffect
        var simSoc: Double? = null; var simM = 0.0
        while (true) {
            kotlinx.coroutines.delay(CHARGE_CHECK_MS)
            val car = net.prototux.aare.host.CarDataService.car(context) ?: continue
            val t = session.state.value.tripState as? TripState.Navigating ?: continue
            val roll = NaviKeep.rolling ?: continue
            val driven = r.distance - t.progress.distanceRemaining
            // a route of its own on the way (left the route, a faster one taken): the plan goes on on it
            if (NaviKeep.rollingRoute !== r) {
                val prev = NaviKeep.rollingDriven
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { roll.retrack(guidedTrack(r, car), prev) }
                NaviKeep.rollingRoute = r; simM = 0.0
            }
            NaviKeep.rollingDriven = driven
            val soc = if (testDrive && net.prototux.aare.host.CarDataService.debugSoc != null) {
                var lv = (simSoc ?: car.socPercent) - roll.modelUsed(simM, driven)
                chargeStops.filter { it.atM > simM && it.atM <= driven }.maxOfOrNull { it.leavePercent }?.let { lv = maxOf(lv, it) }
                simM = driven; simSoc = lv; lv
            } else car.socPercent
            val before = roll.chain
            val d = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { roll.check(driven, soc) } ?: continue
            if (d.kind != ChargeRolling.Kind.KEEP || d.why.contains("waiting")) chargeLog("charging on the way: ${d.kind}, ${d.why}")
            // System I-094 (23.11.2026: "er braucht aber nicht beim Navi starten … sagen, dass der Ladestopp sich ändert"): right
            // after a route set on purpose (the start, the plan put in, a stop changed) the plan has only just been made -
            // no moving of stops in its first two minutes (the log of 22.11. had a move every 30 s right after the start)
            val sinceGo = android.os.SystemClock.elapsedRealtime() - session.goneAt
            if (d.kind == ChargeRolling.Kind.MOVE && sinceGo < ROLL_CALM_MS) {
                roll.undo(before)
                chargeLog("charging on the way: not moved %d s after the route was set - the plan has only just been made".format(sinceGo / 1000))
                continue
            }
            // the driver took stops out for this trip: none added or moved, no card asking for one
            if (NaviKeep.noNewStops && d.kind != ChargeRolling.Kind.KEEP) {
                if (d.kind == ChargeRolling.Kind.MOVE) roll.undo(before)
                chargeLog("charging on the way: left as it is - the driver took the stops out for this trip")
                continue
            }
            when (d.kind) {
                ChargeRolling.Kind.KEEP -> {
                    if (chargeCheck?.verdict == NaviChargeWatch.Verdict.TOO_LOW) chargeCheck = null
                    // the levels to charge to at the stops ahead, as the plan has them now (the card at the charger)
                    d.result?.links?.let { links -> chargeStops = chargeStops.map { st ->
                        links.firstOrNull { it.cand.charger === st.charger }?.let { l -> ChargePlanner.Stop(st.charger, st.atM, l.arrive, l.leave, l.minutes, l.overLimit) } ?: st } }
                }
                ChargeRolling.Kind.LOW -> chargeCheck = NaviChargeWatch.Result(NaviChargeWatch.Verdict.TOO_LOW,
                    d.result?.let { it.links.firstOrNull()?.arrive ?: it.arrivePercent } ?: 0.0)
                ChargeRolling.Kind.MOVE -> {
                    val here = Position(longitude = t.userLocation.coordinates.lng, latitude = t.userLocation.coordinates.lat)
                    val end = t.remainingWaypoints.lastOrNull()?.let { Position(longitude = it.coordinate.lng, latitude = it.coordinate.lat) } ?: continue
                    // the driver's own stops still ahead stay, the planned chargers are the new ones
                    val ownAhead = ownStops.map { it.first }.filter { p -> t.remainingWaypoints.any { w ->
                        ChargerIndex.distanceM(Position(longitude = w.coordinate.lng, latitude = w.coordinate.lat), p) < OWN_SNAP_M } }
                    val via = NaviStops.inOrder(raw(r), ownAhead + d.chain.map { it.at })
                    val nr = runCatching { session.planOne(NaviSession.Kind.FAST, here, end, via, RouteOptions.of(net.prototux.aare.Settings(context))) }
                        .onFailure { e -> if (e is kotlinx.coroutines.CancellationException) throw e }.getOrNull()
                    if (nr == null) {
                        roll.undo(before)
                        chargeLog("charging on the way: the route over the new stops was refused, the stops stay as they were")
                        continue
                    }
                    val nt = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { guidedTrack(nr, car) }
                    roll.retrack(nt, driven, d.chain)
                    NaviKeep.rollingRoute = nr; NaviKeep.rollingDriven = 0.0; simM = 0.0
                    val timed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        val rules = ChargePlanner.Rules.of(net.prototux.aare.Settings(context))
                        val ownCh = ownAhead.mapNotNull { p -> ChargerIndex.get(context).near(p, OWN_CHARGER_M, 0).minByOrNull { ChargerIndex.distanceM(it.at, p) } }
                        ChargeChain.solve(nt, ChargeCorridor.candidates(nt, { _, _, _ -> emptyList() }, rules, 0, d.chain + ownCh), car, soc, rules)
                    }
                    chargeStops = timed.stops()
                    session.go(nr, simulate = testDrive)
                    show(nr); drawnLine = NaviRoute.smooth(raw(nr)); limits = null
                    scope.launch { limits = NaviLimits.load(context, raw(nr)) }
                    val next = timed.stops().firstOrNull()
                    val text = next?.let { context.getString(net.prototux.aare.R.string.mate_navi_charge_moved, NaviTripPlan.chargerName(it.charger), Math.round(it.atM / 1000).toInt()) }
                        ?: context.getString(net.prototux.aare.R.string.mate_navi_charge_none)
                    // said only once the trip is under way (I-094); the card shows it in any case
                    if (sinceGo > ROLL_SAY_AFTER_MS) speaker.warn(next?.let { context.getString(net.prototux.aare.R.string.mate_navi_charge_moved_say, ChargeNetworks.name(it.charger.network) ?: it.charger.operator, Math.round(it.atM / 1000).toInt()) }
                        ?: context.getString(net.prototux.aare.R.string.mate_navi_charge_none_say))
                    chargeCheckLeft = null
                    chargeCheck = NaviChargeWatch.Result(NaviChargeWatch.Verdict.MOVED, next?.arrivePercent ?: timed.arrivePercent, text)
                    chargeLog("charging on the way: new route %.1f km over %s, 1 request".format(java.util.Locale.ROOT, nr.distance / 1000,
                        timed.describe()))
                }
            }
        }
    }
    // a destination handed over (spoken, or from another app): a place straight into the preview, a text into the search
    LaunchedEffect(Unit) {
        NaviRequests.pending.collect { r ->
            if (r == null) return@collect
            NaviRequests.take()
            purpose = Purpose.NEW
            val at = r.at
            if (at != null) choose(net.prototux.aare.places.Places.Place("request", "", r.name ?: context.getString(net.prototux.aare.R.string.places_on_map),
                "", at.latitude, at.longitude))
            else { NaviDebug.searchQuery = r.query; panel = NaviStep.Search }
        }
    }
    // test hook for the drive in the desk window (debug builds): miss the next turn
    DisposableEffect(session) {
        // a test destination drives simulated from the test start, wherever Mate runs (never the real position in a picture)
        NaviDebug.goTo = { lat, lon -> scope.launch { purpose = Purpose.NEW; testDrive = true
            choose(net.prototux.aare.places.Places.Place("mate-test-ziel", "mate-test", "mate-test Ziel", "", lat, lon)) } }
        NaviDebug.missTurn = { scope.launch { Log.i(TAG, "test: " + runCatching { session.missTurn() }.getOrElse { "failed: ${it.message}" }) } }
        NaviDebug.openSearch = { q -> scope.launch { NaviDebug.searchQuery = q; panel = NaviStep.Search } }
        NaviDebug.addStop = { lat, lon, name -> scope.launch { purpose = Purpose.STOP
            choose(net.prototux.aare.places.Places.Place("mate-test-stop", "mate-test", name, "", lat, lon)) } }
        NaviDebug.live = { scope.launch { if (guiding) end() else { panel = NaviStep.None; dropPreview(); routeJson = null; drawnLine = emptyList() }; testDrive = false; Log.i(TAG, "test: live position") } }
        onDispose { NaviDebug.missTurn = null; NaviDebug.goTo = null; NaviDebug.openSearch = null; NaviDebug.live = null; NaviDebug.addStop = null }
    }
    val nav by session.state.collectAsState()
    val carState by net.prototux.aare.car.CarData.state.collectAsState()
    // only while Mate guides: after "Navigation beenden" Ferrostar keeps its last state, the panels must not
    val trip = if (guiding) nav.tripState as? TripState.Navigating else null
    val here = trip?.snappedUserLocation
    // N-019 step 1 (E-029): warm the battery before a fast charger - the battery's readings teach the heating rate, and
    // once the next stop is a fast charger nearer than the warming takes, a hint says to start it now (once per charger)
    var preheat by remember { mutableStateOf<Pair<Charger, NaviPreheat.Advice>?>(null) }
    var preheatLeft by remember { mutableStateOf<Charger?>(null) }
    // the chargers the hint was said for already: said once each, also when it goes and comes back (a reading too old)
    val preheatSaid = remember { HashSet<Charger>() }
    val tripForWarm by androidx.compose.runtime.rememberUpdatedState(trip)
    // off the main thread: the charger's place along the route goes over all of its points (no stutter on the map)
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val watch = NaviPreheat.Watch()
        var lastAt = 0L
        while (true) {
            val bt = net.prototux.aare.dash.DashData.batteryCelsius(context)
            val out = net.prototux.aare.dash.DashData.outsideCelsius(context)
            val at = net.prototux.aare.dash.DashData.batteryAtMs(context)
            // while charging the battery warms by the charge itself: no heating rate learned then, the watch starts anew
            if (net.prototux.aare.dash.DashData.charging(context)) watch.reset()
            else if (bt != null && at > lastAt) {
                lastAt = at
                watch.reading(bt, at)?.let { rate -> if (out != null) {
                    val r = NaviPreheat.Rates.of(context).learn(out, rate)
                    NaviPreheat.Rates.save(context, r)
                    net.prototux.aare.DiagLog.add("battery warm: %.2f K/min seen at %.0f °C outside - %s".format(java.util.Locale.ROOT, rate, out, r), area = "CHARGE")
                } }
            }
            val t = tripForWarm; val g = session.route.value; val roll = NaviKeep.rolling
            val next = if (t == null || g == null || roll == null || NaviKeep.rollingRoute !== g || testDrive) null else {
                val driven = g.distance - t.progress.distanceRemaining
                NaviKeep.stillToCharge(t).firstOrNull { it !== NaviKeep.atCharger.value }?.let { next ->
                    val toM = roll.alongM(next.charger)
                    if (toM <= driven) null
                    else NaviPreheat.advice(bt, out, next.charger.kw, roll.secondsBetween(driven, toM) / 60.0, NaviPreheat.Rates.of(context))?.let { next.charger to it }
                }
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { preheat = next }
            kotlinx.coroutines.delay(30_000)
        }
    } }
    // said once per charger, as it appears
    LaunchedEffect(preheat?.first) {
        val (c, a) = preheat ?: return@LaunchedEffect
        if (c === preheatLeft || !preheatSaid.add(c)) return@LaunchedEffect
        net.prototux.aare.DiagLog.add("battery warm: start now - %.0f °C, %.0f min to %.0f °C, about %.1f kWh, %d kW charger".format(java.util.Locale.ROOT,
            a.fromC, a.minutes, NaviPreheat.TARGET_C, a.kwh, c.kw), area = "CHARGE")
        // a tone is enough, no words (22.11.2026: "Dafür muss er aber nicht unbedingt eine Navi-Ansage machen … sondern ein
        // Hinweiston würde reichen"); the hint itself blinks three times (NaviPreheatHint)
        if (voiceMode != NaviSpeaker.Mode.MUTE && !NaviKeep.away) NaviSpeedAlert.play(NaviSpeedAlert.Companion.Tone.DOUBLE, 0.8f, 1)
    }
    // "Fortsetzen": an own stop reached is dropped from what is kept, so going on does not lead back to it (put back
    // 17.11.2026 - it went missing when the car's displays moved to NaviKeep)
    LaunchedEffect(trip?.remainingWaypoints?.size) {
        val t = trip ?: return@LaunchedEffect
        if (testDrive || ownStops.isEmpty()) return@LaunchedEffect
        val left = ownStops.filter { (p, _) -> t.remainingWaypoints.any { w ->
            ChargerIndex.distanceM(Position(longitude = w.coordinate.lng, latitude = w.coordinate.lat), p) < OWN_SNAP_M } }
        if (left.size != ownStops.size) { ownStops = left; target?.let { NaviResume.save(context, it, left) } }
    }
    // F4: the spoken instructions, each once when Ferrostar reaches its distance
    val spoken = trip?.spokenInstruction
    // spoken from NaviKeep, shown or not
    LaunchedEffect(guiding) { if (!guiding) speaker.stop() }
    // Smooth motion (UI-05, wishes of 16.11.2026): the positions come once a second; between two of them the car glides
    // along the smoothed line (so it stays on it in curves), its own heading the line's direction 10 m behind to 10 m
    // ahead. The camera follows it and turns calmly towards the way 30 m ahead; the arrow turns by itself against it.
    val track = remember(drawnLine) { if (drawnLine.size >= 2) NaviTrack(drawnLine) else null }
    val glide = remember(track) { track?.let { Glide(it).apply { label = if (testDrive) "simulated" else "live" } } }
    var arrowTurn by remember { mutableStateOf(0f) }
    // the glide's time, for what moves in the map's beat (the limit sign's breathing) - no frames of its own
    var beat by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    // where the car is on the screen: its place on the map through the camera's projection (wish of 16.11.2026: "der
    // pfeil gehört zum standort auf der map nicht zum display view")
    var arrowAt by remember { mutableStateOf<androidx.compose.ui.unit.DpOffset?>(null) }
    LaunchedEffect(here, glide) {
        val g = glide ?: return@LaunchedEffect
        here ?: return@LaunchedEffect
        g.next(here.coordinates.lat, here.coordinates.lng, android.os.SystemClock.uptimeMillis())
    }
    var following by remember { mutableStateOf(true) }
    var returning by remember { mutableStateOf(false) }
    // where the car is: the simulation's start until a trip runs (the puck shows there while waiting too)
    var lastPoint by remember { mutableStateOf<Position?>(if (testDrive) testFrom() else null) }
    // F1: not guiding and not testing - the map shows where the car is and follows it, as Google Maps does before a trip
    LaunchedEffect(testDrive, guiding) {
        if (testDrive || guiding) return@LaunchedEffect
        live.locationUpdates(1000).collect { l ->
            val p = Position(longitude = l.longitude, latitude = l.latitude)
            lastPoint = p; carAt = p
            if (following && !returning && panel == NaviStep.None) camera.position = camera.position.copy(target = p, bearing = 0.0, tilt = 0.0)
            camera.projection?.screenLocationFromPosition(p)?.let { arrowAt = it }
            if (l.hasBearing()) arrowTurn = (((l.bearing - camera.position.bearing + 540) % 360) - 180).toFloat()
        }
    }
    // L-009 (I-007): without a route a touch, pinch or turn of the map stops following and the map stays where the driver
    // put it - no glide back by time; only the centre button brings it back to the car. The loop above runs once a second
    // and would overwrite the move, so the gesture is caught here at the camera's own pace.
    LaunchedEffect(testDrive, guiding) {
        if (testDrive || guiding) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { camera.isCameraMoving && camera.moveReason == org.maplibre.compose.camera.CameraMoveReason.GESTURE }
            .collect { if (it) following = false }
    }
    // the car placed anew on every move of the camera (a touch, a pinch, the glide back), not only at the loop's rate
    LaunchedEffect(camera) {
        androidx.compose.runtime.snapshotFlow { camera.position }.collect {
            lastPoint?.let { pt -> camera.projection?.screenLocationFromPosition(pt)?.let { at -> arrowAt = at } }
        }
    }
    var touchedAt by remember { mutableStateOf(0L) }
    // System L-089: a stop of the trip plan shown on the map - the camera stays there while the plan is open
    var planLook by remember { mutableStateOf<Position?>(null) }
    // System L-096 (22.11.2026: "wenn man neben den Routenplaner klickt, soll dieser aufbleiben, weil man hat ja auf die
    // Karte geklickt"): a place or charger picked on the map while the trip plan or the preview is open - its card beside
    // them, they stay open
    var sidePick by remember { mutableStateOf<NaviStep.Pick?>(null) }
    // System L-092: the stop whose alternatives are on the map, the stations shown, the one tapped
    var altFor by remember { mutableStateOf<Charger?>(null) }
    var altMarks by remember { mutableStateOf<List<NaviTripPlan.Option>>(emptyList()) }
    var altPicked by remember { mutableStateOf<NaviTripPlan.Option?>(null) }
    var returnStartedAt by remember { mutableStateOf(0L) }
    // a panel that puts its own picture on the map (the preview's routes, the last destinations' pins) holds the camera
    // while the car drives on (16.11.2026: a new preview while guiding stayed at the car); about 1 s after it closes the
    // camera glides back
    val overview by androidx.compose.runtime.rememberUpdatedState(panel is NaviStep.Preview || panel == NaviStep.Recent || panel is NaviStep.Pick)
    // F5: the driving zoom by speed and the next turn (NaviZoom), gliding; the arrow keeps its size at it
    val nextTurnM by androidx.compose.runtime.rememberUpdatedState(trip?.progress?.distanceToNextManeuver)
    val driveZoom = remember { doubleArrayOf(NaviZoom.NEAR) }
    LaunchedEffect(glide) {
        val g = glide ?: return@LaunchedEffect
        var lastZoomAt = android.os.SystemClock.uptimeMillis()
        // A plain timer at the map's rate (mapFps). Asking Compose for every display frame (withFrameMillis) kept it
        // running 60 times a second for nothing - measured as most of the CPU while driving (O168).
        while (true) {
            val now = android.os.SystemClock.uptimeMillis()
            g.at(now)?.let { p ->
                g.measure(now, p)
                // the driver moves or pinches the map: it stops following; 8 s after the last touch it glides back
                // only while the camera is really moving by a touch: the reason stays GESTURE after the touch ended, which kept the
                // map from ever coming back
                if (camera.isCameraMoving && camera.moveReason == org.maplibre.compose.camera.CameraMoveReason.GESTURE) { following = false; touchedAt = now }
                if (overview) { following = false; touchedAt = now - RESUME_FOLLOW_MS + 1000 }
                if (planLook != null) { following = false; touchedAt = now }
                val kmh = (NaviDebug.speedKmh ?: g.smoothSpeedMs?.let { it / (if (session.simulating) SIM_WARP else 1) })?.let { it * 3.6 }
                g.cameraSpeedMs = kmh?.let { it / 3.6 }
                driveZoom[0] = NaviZoom.glide(driveZoom[0], NaviZoom.target(kmh, nextTurnM), now - lastZoomAt)
                lastZoomAt = now
                val follow = CameraPosition(target = p.point, zoom = driveZoom[0], bearing = p.cameraBearing, tilt = DRIVE_TILT,
                    padding = PaddingValues(top = FOCUS_TOP))
                // 17.11.2026 (drive): "nach Zoom, Pinch-to-Zoom und Kartenbewegung springt er nicht mehr zurück": a glide back
                // that never finished (its coroutine cancelled, the animation waiting) kept "returning" set for good -
                // no further glide back was started. Now it counts as done after 3 s.
                if (returning && now - returnStartedAt > RETURN_MAX_MS) { returning = false; following = true; Log.i(TAG, "glide back took too long: following again") }
                if (!following && !returning && now - touchedAt > RESUME_FOLLOW_MS) {
                    // glide back beside this loop, so the car keeps being placed while the camera moves (it lagged)
                    returning = true; returnStartedAt = now
                    scope.launch {
                        try { runCatching { camera.animateTo(follow, kotlin.time.Duration.parse("900ms")) } }
                        finally { following = true; returning = false }
                    }
                } else if (following) camera.position = follow
                arrowTurn = (((p.heading - camera.position.bearing + 540) % 360) - 180).toFloat()
                beat = now
                lastPoint = p.point
                carAt = p.point
                // the camera follows: its move comes back from MapLibre and places the car (the collector above) - placing
                // it here too made two frames of the screen for every step, ~60 a second at a map of 30 (16.11.2026);
                // a still camera (the driver looks around) does not come back, so the car is placed here then
                if (!following && !returning) arrowAt = camera.projection?.screenLocationFromPosition(p.point)
            }
            val step = 1000L / mapFps()
            kotlinx.coroutines.delay(step - (android.os.SystemClock.uptimeMillis() - now).coerceAtMost(step - 1))
        }
    }
    // what the guidance says, in the log until the manoeuvre card is drawn (step 4)
    val instruction = trip?.visualInstruction?.primaryContent?.text
    LaunchedEffect(instruction) {
        if (instruction != null && trip != null) Log.i(TAG, "instruction: $instruction, next in %.0f m, %.0f m left"
            .format(trip.progress.distanceToNextManeuver, trip.progress.distanceRemaining))
    }
    val arrived = nav.tripState is TripState.Complete
    LaunchedEffect(arrived) {
        if (!arrived) return@LaunchedEffect
        // the last stretch for the buffer (L-029 step D): what the model missed since the start or the last charging
        if (!testDrive) NaviKeep.rolling?.let { roll -> net.prototux.aare.host.CarDataService.car(context)?.let { c ->
            roll.arrived(NaviKeep.rollingRoute?.distance ?: 0.0, c.socPercent) } }
        NaviKeep.rolling = null
        guiding = false
        val minutes = (android.os.SystemClock.elapsedRealtime() - startedAt) / 60_000.0
        // the way driven over all the trip's routes (a new route after a detour counted from where it began); the
        // planned length only when nothing was counted (a test start)
        val drivenKm = NaviKeep.drivenTotalM.doubleValue / 1000
        val km = if (drivenKm > 0.05) drivenKm else plannedKm
        val avg = if (minutes > 0) km / (minutes / 60.0) else 0.0
        val known = net.prototux.aare.host.CarDataService.car(context)
        val car = known ?: net.prototux.aare.host.EnergyModel.Car(capacityWh = 86_000, socPercent = 80.0, gtx = true)
        // the energy the battery gave (IDmate's level at the start and now), when it fell by enough to tell and nothing was
        // charged on the way; else Mate's energy model over the way driven at the average speed
        val startSoc = NaviKeep.startSoc
        val fell = if (known != null && startSoc != null) startSoc - known.socPercent else null
        val measured = fell?.takeIf { it >= MEASURED_MIN_PERCENT && chargeStops.isEmpty() }?.let { it / 100.0 * known!!.capacityWh / 1000.0 }
        val kwh = measured ?: (net.prototux.aare.host.EnergyModel.tripW(avg, car) * (minutes / 60.0) / 1000.0)
        chargeLog("trip: %.1f km driven (%.1f km on the last route), %.0f min, %.1f kWh %s".format(java.util.Locale.ROOT, km, plannedKm, minutes, kwh,
            if (measured != null) "from the level %.0f -> %.0f %%".format(java.util.Locale.ROOT, startSoc, known?.socPercent) else "from the model"))
        kotlinx.coroutines.delay(ARRIVAL_DELAY_MS)
        done = TripDone(minutes, km, kwh, estimated = measured == null && known?.curve == null,
            name = target?.name?.takeIf { it != context.getString(net.prototux.aare.R.string.places_on_map) })
        // no more gliding after the arrival: the loop ran on at the map's rate with no positions (16.11.2026); the line
        // goes with it (it stayed on the map under the arrival card)
        drawnLine = emptyList(); routeJson = null
        Log.i(TAG, "arrived: glide stopped")
        val onMap = context.getString(net.prototux.aare.R.string.places_on_map)
        // a test drive leaves nothing behind (16.11.2026: "Letzte Ziele" held six "mate-test Ziel")
        if (!testDrive) {
            destination?.let { pins = NaviPins.add(context, it, target?.name?.takeIf { n -> n != onMap } ?: "") }
            // a visit to one of the user's places counts for its list's "most visited of late" (O169)
            destination?.let { net.prototux.aare.places.Places.noteArrival(context, it.latitude, it.longitude) }
        }
        NaviResume.clear(context)
        Log.i(TAG, "arrived after %.1f min, %.1f km".format(minutes, km))
    }

    // the whole display, like the other navigation apps (the shell lets it under the controls at the bottom left)
    // no scale bar, compass, logo or attribution button of the library: nothing on the map that does not help to drive.
    // Made once: a new object on every position made the map take its options anew each time.
    val options = remember(mapFps()) { MapOptions(renderOptions = RenderOptions(maximumFps = mapFps()), ornamentOptions = OrnamentOptions.AllDisabled) }
    // the controls while guiding only 5 s after the start and after a touch of the screen (O183); a touch is watched
    // without taking it, so the map still moves and pinches
    var controlsAt by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var controlsShown by remember { mutableStateOf(false) }
    LaunchedEffect(controlsAt) {
        if (controlsAt == 0L) return@LaunchedEffect
        controlsShown = true; kotlinx.coroutines.delay(CONTROLS_MS); controlsShown = false
    }
    LaunchedEffect(guiding) { if (guiding) controlsAt = android.os.SystemClock.uptimeMillis() }
    // the jams' stretches on the route line (17.11.2026), recomputed for new traffic and every 200 m driven
    val aheadNow = NaviKeep.trafficAhead.value
    val flowNow = NaviKeep.flowAhead.value
    // a sign on the strip right at a charging stop (19.11.2026: "Ladestops als Durchfahrtsverbot angezeigt"): which
    // message it is, once each, so the next drive's log tells whether TomTom reports the charger's place itself
    val signsLogged = remember { mutableSetOf<String>() }
    LaunchedEffect(aheadNow, chargeStops) {
        // every closure (lane or road) TomTom reports on or beside the route, once: its words, whether it runs along our way
        for (a in aheadNow) if ((a.incident.kind == 7 || a.incident.kind == 8) &&
            signsLogged.add("c${a.incident.kind}@${a.incident.start.latitude},${a.incident.start.longitude}"))
            chargeLog("strip: %s (TomTom kind %d) at %.1f km of the route, %s - \"%s\"".format(java.util.Locale.ROOT, NaviTraffic.word(context, a.incident.kind),
                a.incident.kind, a.alongM / 1000, if (NaviTraffic.onOurWay(a)) "on our way, shown" else "beside it (a ramp, a service area), not shown", a.incident.text.take(120)))
        for (a in aheadNow) for (st in chargeStops) {
            val d = ChargerIndex.distanceM(a.incident.start, st.charger.at)
            if (d < 500 && signsLogged.add("${a.incident.kind}@${a.incident.start.latitude},${a.incident.start.longitude}"))
                chargeLog("strip: %s (TomTom kind %d) %.0f m from the charging stop %s".format(java.util.Locale.ROOT,
                    NaviTraffic.word(context, a.incident.kind), a.incident.kind, d, NaviTripPlan.chargerName(st.charger)))
        }
    }
    val drivenStep = guided?.let { g -> trip?.let { ((g.distance - it.progress.distanceRemaining) / 200).toInt() } } ?: 0
    val jamJson = remember(guided, aheadNow, flowNow, drivenStep, guiding) {
        val g = guided
        if (!guiding || g == null || (aheadNow.isEmpty() && flowNow.isEmpty())) null
        // cut from the drawn (smoothed) line itself, so the colour covers it exactly ("die Einfärbung ist auf der Linie,
        // nicht die Linie selbst", 17.11.2026)
        else run {
            val raw = g.geometry.map { Position(longitude = it.lng, latitude = it.lat) }
            val drawn = NaviRoute.smooth(raw)
            fun len(l: List<Position>) = (1 until l.size).sumOf { ChargerIndex.distanceM(l[it - 1], l[it]) }
            val scale = len(raw).let { r -> if (r > 0) len(drawn) / r else 1.0 }
            NaviTraffic.stretches(drawn, aheadNow, drivenStep * 200.0, flowNow, scale)
        }.also { (a, b, c) ->
            Log.i(TAG, "route traffic: slow ${a.split("\"Feature\"").size - 1}, jam ${b.split("\"Feature\"").size - 1}, standing ${c.split("\"Feature\"").size - 1} stretches") }
    }
    // O237 ("im Stau die Zeit im Stau anzeigen"): what each jam ahead costs ("+4 min" at it, as Google) and, inside one,
    // the time to its end in the trip's tile
    val jams = remember(guided, aheadNow, flowNow, drivenStep, guiding) {
        val g = guided
        if (!guiding || g == null || (aheadNow.isEmpty() && flowNow.isEmpty())) emptyList()
        else NaviTraffic.jams(aheadNow, flowNow, drivenStep * 200.0) { m -> freeMs(g, m) }
    }
    val jamLabels = remember(jams, guided) {
        val g = guided ?: return@remember emptyList()
        val raw = g.geometry.map { Position(longitude = it.lng, latitude = it.lat) }
        jams.take(JAM_LABELS).map { j -> pointAt(raw, j.midM) to "+" + Math.round(j.delayS / 60.0).coerceAtLeast(1) + " min" }
    }
    BoxWithConstraints(modifier.background(Mate.mapLand).pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                if (e.changes.any { it.pressed && !it.previousPressed }) controlsAt = android.os.SystemClock.uptimeMillis()
            }
        }
    }) {
        val altDensity = androidx.compose.ui.platform.LocalDensity.current.density
        MaplibreMap(modifier = Modifier.fillMaxSize(), baseStyle = style, cameraState = camera, styleState = styleState, options = options,
            // O217: "lange auf das gewünschte Ziel in der Map drücken" - a pin there and the choice, as Google offers it
            // L-092: a station of the alternatives tapped on the map - the nearest within a finger's width
            onMapClick = { pos, _ ->
                if (altMarks.isEmpty()) {
                    // System L-096 (22.11.2026: "auf der Map direkt anklicken kann und sagen kann, ich möchte über diese Station
                    // fahren … als festen Ladepunkt"): a charger tapped where the map shows them (zoom 10 and nearer)
                    val mPerPx = 156_543.0 * Math.cos(Math.toRadians(pos.latitude)) / Math.pow(2.0, camera.position.zoom) / altDensity
                    // the chargers shown (L-099: from zoom 7 while planning, 10 otherwise)
                    val ch = if (camera.position.zoom < (if (planningMap) 7.0 else 10.0)) null
                        else chargerOthers.filter { ChargerIndex.distanceM(it.at, pos) < 40 * mPerPx }.minByOrNull { ChargerIndex.distanceM(it.at, pos) }
                    if (ch == null) org.maplibre.compose.util.ClickResult.Pass else {
                        Log.i(TAG, "map: charger tapped")
                        val p = NaviStep.Pick(ch.at, ch, (panel as? NaviStep.Preview)?.place)
                        if (panel == NaviStep.Plan || panel is NaviStep.Preview) sidePick = p else panel = p
                        org.maplibre.compose.util.ClickResult.Consume
                    }
                } else {
                    val mPerPx = 156_543.0 * Math.cos(Math.toRadians(pos.latitude)) / Math.pow(2.0, camera.position.zoom) / altDensity
                    val hit = altMarks.minByOrNull { ChargerIndex.distanceM(it.charger.at, pos) }
                        ?.takeIf { ChargerIndex.distanceM(it.charger.at, pos) < 36 * mPerPx }
                    if (hit == null) org.maplibre.compose.util.ClickResult.Pass else {
                        altPicked = hit; planLook = hit.charger.at; following = false; touchedAt = android.os.SystemClock.uptimeMillis()
                        Log.i(TAG, "trip plan: alternative tapped on the map")
                        org.maplibre.compose.util.ClickResult.Consume
                    }
                }
            },
            onMapLongClick = { pos, _ ->
                Log.i(TAG, "map held: a place picked")
                val p = NaviStep.Pick(pos, null, (panel as? NaviStep.Preview)?.place)
                if (panel == NaviStep.Plan || panel is NaviStep.Preview) sidePick = p else panel = p
                org.maplibre.compose.util.ClickResult.Consume
            }) {
            // Navi L-047: while guiding the other roads step back - a veil in the land's colour over the base map, under
            // its labels and the route. It lies in the style itself (withVeil), empty, and only gets its extent here:
            // anchored from Compose below a label layer it brought Mate down at every start of a guidance (21.11.2026,
            // "Die App stürzt beim Navigieren oder beim Berechnen der Route ab": "Layer ID 'gewaessername' not found in
            // base style" - the map takes the style's layers when it has loaded, and the anchor came before). A style not
            // loaded yet has no such source: then nothing happens, and it is set once the source is there.
            // A new style (night, traffic, offline) leaves the old source's native side gone for a moment ("invalid native
            // peer", found at the desk head unit on switching to night while guiding): tried again until the new style's
            // source takes it, for a few seconds at most.
            // Keyed on whether the source is there, not on its object: styleState.sources wraps every source anew whenever
            // any source is added or removed (route, signs, markers), and keyed on that wrapper the veil was set again and
            // again - the map flickered, standing most (I-074, 22.11.2026: "Das map flackern ist bei .3 nur im stand
            // vorhanden", "Es lief letzte Woche vor 2.1").
            val veilThere = styleState.sources[VEIL_ID] is org.maplibre.compose.sources.GeoJsonSource
            LaunchedEffect(veilThere, guiding, style) {
                val data = GeoJsonData.JsonString(if (guiding) VEIL_WORLD else VEIL_NONE)
                repeat(20) {
                    val now = styleState.sources[VEIL_ID] as? org.maplibre.compose.sources.GeoJsonSource
                    if (now != null && runCatching { now.setData(data) }.isSuccess) return@LaunchedEffect
                    kotlinx.coroutines.delay(250)
                }
                Log.w(TAG, "guiding veil: the style's source did not take it")
            }
            // F5: the other routes of the preview, under the chosen one (only while choosing)
            if (panel is NaviStep.Preview) altJson?.let { json ->
                val altData = remember(json) { GeoJsonData.JsonString(json) }
                val alt = rememberGeoJsonSource(altData)
                LineLayer(id = "route-alt-edge", source = alt, color = const(Mate.routeAltEdge), width = const(9.dp),
                    cap = const(LineCap.Round), join = const(LineJoin.Round))
                LineLayer(id = "route-alt", source = alt, color = const(Mate.routeAlt), width = const(5.dp),
                    cap = const(LineCap.Round), join = const(LineJoin.Round))
            }
            routeJson?.let { json ->
                // the data made once per route: a new object per recomposition reloaded the source - the line flickered
                val lineData = remember(json) { GeoJsonData.JsonString(json) }
                val line = rememberGeoJsonSource(lineData)
                // a dark edge under the line keeps it apart from the streets (UI-07), round ends and joins (UI-03)
                // a little wider while guiding (17.11.2026: "die Routenlinie bei der Navigation ein bisschen breiter")
                LineLayer(id = "route-edge", source = line, color = const(Mate.routeEdge), width = const(if (guiding) 15.dp else 13.dp),
                    cap = const(LineCap.Round), join = const(LineJoin.Round))
                LineLayer(id = "route", source = line, color = const(Mate.route), width = const(if (guiding) 11.dp else 9.dp),
                    cap = const(LineCap.Round), join = const(LineJoin.Round))
                // in the same block as the line, so they lie on it (added later on their own they went under it)
                // jams on the route as Google colours them: slow orange, jam red, standing dark red (17.11.2026)
                jamJson?.let { (slow, jam, stand) ->
                    listOf(Triple("route-slow", slow, "verkehr_zaeh"), Triple("route-jam", jam, "verkehr_stau"), Triple("route-stand", stand, "verkehr_steht")).forEach { (id, json, color) ->
                        val data = remember(json) { GeoJsonData.JsonString(json) }
                        val src = rememberGeoJsonSource(data)
                        // the map's own traffic colours (by day and night), so road and route agree
                        val c = remember(color, night) { Mate.karte(context, color, night) ?: Mate.warn }
                        LineLayer(id = id, source = src, color = const(c), width = const(9.dp), cap = const(LineCap.Round), join = const(LineJoin.Round))
                    }
            }
                // O237: the delay of each jam ahead as a small sign on the line; always the same layers, empty when unused
                for (k in 0 until JAM_LABELS) {
                    val l = jamLabels.getOrNull(k)
                    val data = remember(l?.first) { GeoJsonData.JsonString(l?.let { NaviPins.geoJson(listOf(it.first)) } ?: EMPTY_FC) }
                    val img = remember(l?.second, night) { jamSign(l?.second ?: "", night) }
                    org.maplibre.compose.layers.SymbolLayer(id = "jam-time-$k", source = rememberGeoJsonSource(data),
                        iconImage = org.maplibre.compose.expressions.dsl.image(img), iconAllowOverlap = const(true),
                        iconPitchAlignment = const(org.maplibre.compose.expressions.value.IconPitchAlignment.Viewport))
                }
            }
            // rest stops under the chargers, only nearer in (F5)
            restJson?.let { (servicesJson, areasJson) ->
                val servicesData = remember(servicesJson) { GeoJsonData.JsonString(servicesJson) }
                val areasData = remember(areasJson) { GeoJsonData.JsonString(areasJson) }
                val servicesImage = remember { NaviRest.image(CHARGER_PX, services = true) }
                val areasImage = remember { NaviRest.image(CHARGER_PX, services = false) }
                org.maplibre.compose.layers.SymbolLayer(id = "rest-areas", source = rememberGeoJsonSource(areasData), minZoom = 12f,
                    iconImage = org.maplibre.compose.expressions.dsl.image(areasImage),
                    iconPitchAlignment = const(org.maplibre.compose.expressions.value.IconPitchAlignment.Viewport))
                org.maplibre.compose.layers.SymbolLayer(id = "rest-services", source = rememberGeoJsonSource(servicesData), minZoom = 11f,
                    iconImage = org.maplibre.compose.expressions.dsl.image(servicesImage),
                    iconPitchAlignment = const(org.maplibre.compose.expressions.value.IconPitchAlignment.Viewport))
            }
            // F2: closed roads as small red crosses in a red ring along them (O214: "lieber kleine X rot mit roten Rand"),
            // only those that touched the planned routes (O220: "nur anzeigen wenn dadurch die Route ... beeinflusst wird")
            // a "closed" stretch the route still runs through is no closure for it (19.11.2026: "Ist eine Autobahnbaustelle,
            // also statt 3 Spuren 2 Spuren ... Es wäre allerdings logischer, wenn er sowas ... als normale Baustelle anzeigt.
            // Ich habe überall die Xe auf der Route") - TomTom marks a carriageway of road works closed while the traffic
            // goes on narrowed or switched over: drawn with the road works sign; closed stretches beside the route keep the X
            val routeNow = remember(drawnLine, routeJson) { drawnLine.ifEmpty { planned?.let { raw(it) }.orEmpty() } }
            val (worksLines, closedOnly) = remember(closedLines, routeNow) {
                closedLines.partition { l -> routeNow.size > 1 && NaviTraffic.onClosures(routeNow, listOf(l)) > 0 } }
            fun lineJson(ls: List<List<Position>>) = ls.takeIf { it.isNotEmpty() }?.let {
                org.json.JSONObject().put("type", "FeatureCollection").put("features", org.json.JSONArray(it.map { l ->
                    org.json.JSONObject().put("type", "Feature").put("properties", org.json.JSONObject()).put("geometry",
                        org.json.JSONObject().put("type", "LineString").put("coordinates", org.json.JSONArray(l.map { p -> org.json.JSONArray().put(p.longitude).put(p.latitude) })))
                })).toString() }
            val closedJson = remember(closedOnly) { lineJson(closedOnly) }
            val worksJson = remember(worksLines) { lineJson(worksLines) }
            if (trafficShown && closedJson != null) {
                val closedData = remember(closedJson) { GeoJsonData.JsonString(closedJson) }
                val closedImage = remember(night) { NaviTraffic.closedImage(CLOSED_PX, night) }
                org.maplibre.compose.layers.SymbolLayer(id = "verkehr-gesperrt", source = rememberGeoJsonSource(closedData), minZoom = 10f,
                    placement = const(org.maplibre.compose.expressions.value.SymbolPlacement.Line), spacing = const(120.dp),
                    iconImage = org.maplibre.compose.expressions.dsl.image(closedImage),
                    iconRotationAlignment = const(org.maplibre.compose.expressions.value.IconRotationAlignment.Viewport),
                    iconPitchAlignment = const(org.maplibre.compose.expressions.value.IconPitchAlignment.Viewport))
            }
            if (trafficShown && worksJson != null) {
                val worksData = remember(worksJson) { GeoJsonData.JsonString(worksJson) }
                val worksImage = NaviTraffic.rememberSignImage(9, WORKS_PX)
                org.maplibre.compose.layers.SymbolLayer(id = "verkehr-baustelle", source = rememberGeoJsonSource(worksData), minZoom = 10f,
                    placement = const(org.maplibre.compose.expressions.value.SymbolPlacement.Line), spacing = const(260.dp),
                    iconImage = org.maplibre.compose.expressions.dsl.image(worksImage),
                    iconRotationAlignment = const(org.maplibre.compose.expressions.value.IconRotationAlignment.Viewport),
                    iconPitchAlignment = const(org.maplibre.compose.expressions.value.IconPitchAlignment.Viewport))
            }
            chargerJson?.let { (stopsJson, _) ->
                // the others with their network's logo (System L-099, N-037), the bolt without one; only nearer in while driving,
                // from further out while planning
                val ctxLogo = LocalContext.current
                val logoPx = (26 * altDensity).toInt()
                val plateCol = Mate.logoPlate.toArgb(); val edgeCol = Mate.text.copy(alpha = 0.6f).toArgb()
                val byNet = remember(chargerOthers) { chargerOthers.groupBy { it.network?.takeIf { n -> NetworkLogos.of(n) != null } ?: "" } }
                byNet.forEach { (net, list) ->
                    val img = remember(net, logoPx) { NetworkLogos.bitmap(ctxLogo, net, logoPx, plateCol, edgeCol) ?: NaviChargers.image(CHARGER_PX, stop = false) }
                    val json = remember(list) { NaviChargers.geoJson(list.map { it.at }) }
                    org.maplibre.compose.layers.SymbolLayer(id = "chargers-${net.ifEmpty { "other" }}", source = rememberGeoJsonSource(remember(json) { GeoJsonData.JsonString(json) }),
                        minZoom = if (planningMap) 7f else 10f,
                        iconImage = org.maplibre.compose.expressions.dsl.image(img),
                        iconPitchAlignment = const(org.maplibre.compose.expressions.value.IconPitchAlignment.Viewport))
                }
                val stopsData = remember(stopsJson) { GeoJsonData.JsonString(stopsJson) }
                val stopImage = remember { NaviChargers.image(STOP_PX, stop = true) }
                org.maplibre.compose.layers.SymbolLayer(id = "charge-stops", source = rememberGeoJsonSource(stopsData),
                    iconImage = org.maplibre.compose.expressions.dsl.image(stopImage),
                    iconPitchAlignment = const(org.maplibre.compose.expressions.value.IconPitchAlignment.Viewport),
                    iconAllowOverlap = const(true), iconIgnorePlacement = const(true))
            }
            // L-092: the alternatives of a stop, all of them, each with its network's logo (22.11.2026: "statt graue Symbole
            // die Ladestation Anbietersymbole"), the bolt without one; the tapped one larger
            if (altMarks.isNotEmpty()) {
                val ctxMap = LocalContext.current
                val logoPx = (34 * altDensity).toInt()
                val plate = Mate.logoPlate.toArgb(); val edge = Mate.text.copy(alpha = 0.85f).toArgb()
                val groups = remember(altMarks) { altMarks.groupBy { it.charger.network?.takeIf { n -> NetworkLogos.of(n) != null } ?: "" } }
                groups.forEach { (net, list) ->
                    val img = remember(net, logoPx) { NetworkLogos.bitmap(ctxMap, net, logoPx, plate, edge) ?: NaviChargers.image(STOP_PX, stop = false) }
                    val json = remember(list) { NaviPins.geoJson(list.map { it.charger.at }) }
                    org.maplibre.compose.layers.SymbolLayer(id = "alt-chargers-${net.ifEmpty { "other" }}", source = rememberGeoJsonSource(remember(json) { GeoJsonData.JsonString(json) }),
                        iconImage = org.maplibre.compose.expressions.dsl.image(img),
                        iconPitchAlignment = const(org.maplibre.compose.expressions.value.IconPitchAlignment.Viewport),
                        iconAllowOverlap = const(true), iconIgnorePlacement = const(true))
                }
                altPicked?.let { o ->
                    val net = o.charger.network?.takeIf { n -> NetworkLogos.of(n) != null } ?: ""
                    val img = remember(net, logoPx) { NetworkLogos.bitmap(ctxMap, net, (logoPx * 1.5f).toInt(), plate, Mate.accent.toArgb()) ?: NaviChargers.image(STOP_PX, stop = true) }
                    val one = remember(o) { GeoJsonData.JsonString(NaviPins.geoJson(listOf(o.charger.at))) }
                    org.maplibre.compose.layers.SymbolLayer(id = "alt-picked", source = rememberGeoJsonSource(one),
                        iconImage = org.maplibre.compose.expressions.dsl.image(img),
                        iconPitchAlignment = const(org.maplibre.compose.expressions.value.IconPitchAlignment.Viewport),
                        iconAllowOverlap = const(true), iconIgnorePlacement = const(true))
                }
            }
            // O217: the pin of the place held on the map
            (panel as? NaviStep.Pick)?.let { pick ->
                val pickData = remember(pick) { GeoJsonData.JsonString(NaviPins.geoJson(listOf(pick.at))) }
                val pickImage = remember { NaviPins.image(PIN_PX) }
                org.maplibre.compose.layers.SymbolLayer(id = "pick", source = rememberGeoJsonSource(pickData),
                    iconImage = org.maplibre.compose.expressions.dsl.image(pickImage),
                    iconAnchor = const(org.maplibre.compose.expressions.value.SymbolAnchor.Bottom),
                    iconPitchAlignment = const(org.maplibre.compose.expressions.value.IconPitchAlignment.Viewport),
                    iconAllowOverlap = const(true), iconIgnorePlacement = const(true))
            }
            if (pins.isNotEmpty() && panel == NaviStep.Recent) {
                val pinData = remember(pins) { GeoJsonData.JsonString(NaviPins.geoJson(pins)) }
                val pinSource = rememberGeoJsonSource(pinData)
                val pinImage = remember { NaviPins.image(PIN_PX) }
                // stuck in the map: anchored at the point by the needle's tip, standing upright however the map is tilted
                org.maplibre.compose.layers.SymbolLayer(id = "pins", source = pinSource,
                    iconImage = org.maplibre.compose.expressions.dsl.image(pinImage),
                    iconAnchor = const(org.maplibre.compose.expressions.value.SymbolAnchor.Bottom),
                    iconPitchAlignment = const(org.maplibre.compose.expressions.value.IconPitchAlignment.Viewport),
                    iconAllowOverlap = const(true), iconIgnorePlacement = const(true))
            }
        }
        // the turn card of every navigation app on Mate (host/templates): this turn large, the next small below (N3)
        // the turn card slides in from the top; a new turn pushes the old one up and out (Mate's 240-320 ms)
        // the car at the camera's focus (under the cards and panels: they cover it, it does not cover them) - the camera aims at its place on the line - turned by its own heading
        if (here != null || (!guiding && done == null)) {
            // placed in the layout phase from the projected position: no recomposition per frame
            NaviArrow(DRIVE_TILT, { arrowTurn }, Modifier.align(Alignment.TopStart).offset {
                val at = arrowAt ?: return@offset androidx.compose.ui.unit.IntOffset(-10_000, -10_000)
                androidx.compose.ui.unit.IntOffset((at.x - ARROW / 2).roundToPx(), (at.y - ARROW / 2).roundToPx())
            }.size(ARROW).graphicsLayer {
                // smaller as the map shows more (O178, 16.11.2026: at the start it covered whole streets): full at the
                // driving zoom, ~70 % at the start, half at most in the overview; read here only, no frames of its own
                val k = Math.pow(2.0, (camera.position.zoom - driveZoom[0]) / 3.0).coerceIn(0.5, 1.0).toFloat()
                scaleX = k; scaleY = k
            })
        }
        val card = trip?.let { NaviManeuver.card(it) }
        // for tests without a picture: when an exit number or lanes show, and which lanes are to be taken
        val laneLog = card?.let { c -> (c.exit?.let { "exit $it" } ?: "") + if (c.lanes.isEmpty()) "" else " lanes " + c.lanes.joinToString("") { if (it.take) "X" else "-" } }
        LaunchedEffect(laneLog) { if (!laneLog.isNullOrBlank()) Log.i(TAG, "turn card:$laneLog") }
        // under an open panel (the menu) the card would only shine through it - it gives way and comes back after
        androidx.compose.animation.AnimatedVisibility(
            visible = card != null && panel == NaviStep.None,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp).width(cardWidth(maxWidth)),
            enter = androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(320)) { -it } +
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(240)),
            exit = androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(280)) { -it } +
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(240)),
        ) {
            var shown by remember { mutableStateOf(card) }
            if (card != null) shown = card
            val c = shown ?: return@AnimatedVisibility
            androidx.compose.animation.AnimatedContent(
                targetState = c,
                contentKey = { it.info.currentStep?.cue?.toString() },
                transitionSpec = {
                    (androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(300)) { it / 3 } +
                        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(240))) togetherWith
                        (androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(260)) { -it / 3 } +
                            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)))
                },
                label = "turn",
            ) { now -> NaviTurnCard(now.info, now.nextGap, Modifier, now.exit, now.lanes) }
        }
        // a tap beside the card closes it, on the card nothing (18.11.2026: "Wenn man versucht, auf diesen Info-Button
        // raufzudrücken und trifft ihn nicht ganz, dann schließt das Fenster sofort. Das Fenster darf erst schließen, wenn man
        // aus dem Fenster rausklickt")
        if (done != null) Box(Modifier.matchParentSize().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null) { done = null })
        NaviArrival(done, onClose = { done = null }, Modifier.align(Alignment.Center))
        // destination: the button while no trip runs, the panel at the driver's side over the map
        // the chosen route starts by itself after 15 s (O180); choosing another starts the count again, leaving stops it
        LaunchedEffect(panel, planned, holdStart) {
            countdown = 0
            if (panel !is NaviStep.Preview || planned == null || guiding || holdStart) return@LaunchedEffect
            for (i in 15 downTo 1) { countdown = i; kotlinx.coroutines.delay(1000) }
            countdown = 0
            start()
        }
        // O206: the next stop on the way (a charging stop or the driver's own), placed once per guided route; also the
        // trip plan's stops (O209)
        val stopMarks = remember(guided) {
            guided?.let { r -> NaviStops.marks(raw(r), r.steps.map { it.distance to it.duration },
                r.waypoints.drop(1).dropLast(1).map { Position(longitude = it.coordinate.lng, latitude = it.coordinate.lat) }) }.orEmpty()
        }
        val panelMod = Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 16.dp,
            bottom = net.prototux.aare.host.templates.LocalCarBottomInset.current + 12.dp).width(560.dp)
        // O228: the full-screen tile menus, over the map but under the bar at the bottom
        val hubMod = Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 16.dp, end = 16.dp,
            bottom = net.prototux.aare.host.templates.LocalCarBottomInset.current + 12.dp)
        // the heading of the lists and the search says what is chosen - a stop is easily taken for the destination otherwise
        // (bauplan step 12)
        val pickTitle = androidx.compose.ui.res.stringResource(when (purpose) {
            Purpose.STOP -> net.prototux.aare.R.string.mate_navi_destination_stop
            Purpose.CHANGE -> net.prototux.aare.R.string.mate_navi_destination_change
            else -> net.prototux.aare.R.string.mate_navi_destination })
        // the card of a place or charger picked on the map, as a panel of its own or beside the trip plan / preview (L-096)
        @Composable fun PickCard(pk: NaviStep.Pick, onDone: () -> Unit, onPicked: () -> Unit = {}) {
                // the address of the place, asked from the map server's search; "Ort auf der Karte" until then; a charger by its name
                var name by remember(pk) { mutableStateOf<String?>(pk.charger?.let { NaviTripPlan.chargerName(it, context.getString(net.prototux.aare.R.string.mate_navi_charger)) }) }
                LaunchedEffect(pk) { if (pk.charger == null) name = NaviSearch.address(context, pk.at) }
                val onMap = context.getString(net.prototux.aare.R.string.places_on_map)
                fun place() = net.prototux.aare.places.Places.Place("map", "", name ?: onMap, "", pk.at.latitude, pk.at.longitude)
                val inPreview = pk.fromPreview
                NaviMapPick(name ?: onMap, (guiding && target != null) || inPreview != null,
                    onGo = { onPicked(); purpose = if (guiding) Purpose.CHANGE else Purpose.NEW; choose(place()) },
                    onStop = {
                        onPicked()
                        if (inPreview != null) {
                            // System L-096: a charger (or a place) put into the route being previewed - the own stops along
                            // the route in order, the charging planned around it
                            val all = pendingOwn + (pk.at to (name ?: onMap))
                            val line = routeOptions.getOrNull(chosen)?.let { raw(it.route) }
                            val ordered = line?.let { l -> NaviStops.inOrder(l, all.map { it.first }).map { p -> all.first { it.first == p } } } ?: all
                            purpose = Purpose.NEW; choose(inPreview, ordered)
                        } else { purpose = Purpose.STOP; choose(place()) }
                    },
                    onClose = { onDone() },
                    charger = pk.charger)
        }
        // L-096: the picked place beside the open trip plan or preview - both stay open
        sidePick?.takeIf { panel == NaviStep.Plan || panel is NaviStep.Preview }?.let { sp ->
            NaviPanel(Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 16.dp,
                bottom = net.prototux.aare.host.templates.LocalCarBottomInset.current + 12.dp).width(480.dp)) {
                PickCard(sp, onDone = { sidePick = null }, onPicked = { sidePick = null })
            }
        }
        LaunchedEffect(panel) { if (panel != NaviStep.Plan && panel !is NaviStep.Preview) sidePick = null }
        when (val st = panel) {
            NaviStep.None -> if (trip == null && done == null) androidx.compose.foundation.layout.Column(Modifier.align(Alignment.BottomStart)
                .padding(start = 20.dp, bottom = net.prototux.aare.host.templates.LocalCarBottomInset.current + 12.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                resume?.takeIf { !testDrive }?.let { r ->
                    NaviResumeButton(r.place.name.ifBlank { r.place.address },
                        // where it is, for the tests without a picture (a live position must not be photographed)
                        Modifier.onGloballyPositioned { c -> val b = c.boundsInRoot(); Log.i(TAG, "resume button at %.0f,%.0f".format(b.center.x, b.center.y)) },
                        onGo = { purpose = Purpose.NEW; choose(r.place, r.own) },
                        onForget = { NaviResume.clear(context); resume = null })
                }
                // L-009: back to the car, only when the driver moved the map away
                if (!following && !testDrive) RecenterButton {
                    following = true
                    lastPoint?.let { p -> returning = true; scope.launch {
                        runCatching { camera.animateTo(camera.position.copy(target = p, bearing = 0.0, tilt = 0.0), kotlin.time.Duration.parse("700ms")) }
                        returning = false } }
                }
                NaviDestinationButton(Modifier) {
                    book = net.prototux.aare.places.Places.load(context); purpose = Purpose.NEW; panel = NaviStep.Lists }
            }
            // O228: the route's own full-screen tiles while guiding (the small menu before)
            NaviStep.Menu -> {
                val tiles = buildList {
                    add(HubTile(androidx.compose.material.icons.Icons.Rounded.Route, context.getString(net.prototux.aare.R.string.mate_navi_plan)) { panel = NaviStep.Plan })
                    add(HubTile(androidx.compose.material.icons.Icons.Rounded.AddLocationAlt, context.getString(net.prototux.aare.R.string.mate_navi_menu_stop)) {
                        book = net.prototux.aare.places.Places.load(context); purpose = Purpose.STOP; panel = NaviStep.Lists })
                    add(HubTile(androidx.compose.material.icons.Icons.Rounded.EditLocationAlt, context.getString(net.prototux.aare.R.string.mate_navi_menu_change)) {
                        book = net.prototux.aare.places.Places.load(context); purpose = Purpose.CHANGE; panel = NaviStep.Lists })
                    add(HubTile(androidx.compose.material.icons.Icons.Rounded.Tune, context.getString(net.prototux.aare.R.string.navi_options)) { panel = NaviStep.Options(NaviStep.Menu) })
                    add(HubTile(androidx.compose.material.icons.Icons.Rounded.Close, context.getString(net.prototux.aare.R.string.mate_navi_menu_end), Mate.danger) { end() })
                }
                NaviHub(target?.name ?: context.getString(net.prototux.aare.R.string.mate_navi_menu), tiles, hubMod, onBack = { panel = NaviStep.Plan }, onClose = { panel = NaviStep.None },
                    driverOnRight = driverOnRight,
                    onKeyboard = { book = net.prototux.aare.places.Places.load(context); purpose = Purpose.CHANGE; panel = NaviStep.Search },
                    onMic = { purpose = Purpose.CHANGE; NaviSearchStart.voice = true; panel = NaviStep.Search })
            }
            is NaviStep.Pick -> NaviPanel(panelMod) { PickCard(st, onDone = { panel = st.fromPreview?.let { NaviStep.Preview(it) } ?: NaviStep.None }) }
            NaviStep.Plan -> NaviPanel(panelMod) {
                val r = guided; val t = trip
                // the trip ended under the open plan: closed
                if (r == null || t == null) { LaunchedEffect(Unit) { panel = NaviStep.None }; return@NaviPanel }
                val routeS = r.steps.sumOf { it.duration }
                // once per 50 m is enough for the minutes and the kilometres
                val rows = remember(t.progress.distanceRemaining.roundToLong() / 50, stopMarks, chargeStops, ownStops, arrivalNow?.percent?.roundToLong()) {
                    NaviTripPlan.rows(stopMarks, chargeStops, ownStops, r.distance - t.progress.distanceRemaining, routeS - t.progress.durationRemaining,
                        r.distance, routeS, System.currentTimeMillis(), target?.name.orEmpty(), arrivalNow?.percent, ferries = NaviTripPlan.ferries(r))
                }
                LaunchedEffect(Unit) { Log.i(TAG, "trip plan: " + rows.joinToString { "%s %s %.1f km".format(it.kind, it.name, it.metresLeft / 1000) }) }
                // the plan closed: back to the guidance at once (the trip went on meanwhile)
                androidx.compose.runtime.DisposableEffect(Unit) { onDispose {
                    altFor = null; altMarks = emptyList(); altPicked = null
                    if (planLook != null) { planLook = null; touchedAt = 0L } } }
                NaviTripPlanPanel(rows, onBack = null, onClose = { panel = NaviStep.None },
                    onAddStop = { book = net.prototux.aare.places.Places.load(context); purpose = Purpose.STOP; panel = NaviStep.Lists },
                    onChangeDestination = { book = net.prototux.aare.places.Places.load(context); purpose = Purpose.CHANGE; panel = NaviStep.Lists },
                    onMenu = { panel = NaviStep.Menu },
                    nowPercent = net.prototux.aare.host.CarDataService.car(context)?.socPercent,
                    selected = planLook,
                    altFor = altFor, altCount = altMarks.size, altPicked = altPicked, altList = altMarks,
                    onAltPick = { o ->
                        altPicked = o; planLook = o.charger.at; following = false; touchedAt = android.os.SystemClock.uptimeMillis()
                        scope.launch { runCatching { camera.animateTo(CameraPosition(target = o.charger.at, zoom = PLAN_LOOK_ZOOM - 1, bearing = 0.0, tilt = 0.0,
                            padding = PaddingValues(start = 640.dp, top = 80.dp, end = 80.dp, bottom = 160.dp)), kotlin.time.Duration.parse("900ms")) } }
                    },
                    onAlternatives = { ch ->
                        val opts = guidedOptions(ch)
                        altFor = ch; altMarks = opts; altPicked = null
                        Log.i(TAG, "trip plan: ${opts.size} alternative(s) on the map")
                        val pts: List<Position> = opts.map { it.charger.at }.toMutableList().apply { add(ch.at) }
                        planLook = ch.at; following = false; touchedAt = android.os.SystemClock.uptimeMillis()
                        if (pts.size > 1) scope.launch { runCatching { camera.animateTo(org.maplibre.spatialk.geojson.BoundingBox(pts.minOf { it.longitude }, pts.minOf { it.latitude },
                            pts.maxOf { it.longitude }, pts.maxOf { it.latitude }), 0.0, 0.0, PaddingValues(start = 680.dp, top = 100.dp, end = 100.dp, bottom = 180.dp)) } }
                    },
                    onAltClose = { altFor = null; altMarks = emptyList(); altPicked = null },
                    onSwap = { old, c -> planLook = c.at; altFor = null; altMarks = emptyList(); altPicked = null; swapGuided(old, c) },
                    onPick = { row -> (row.charger?.at ?: row.at)?.let { p ->
                        planLook = p; following = false; touchedAt = android.os.SystemClock.uptimeMillis()
                        Log.i(TAG, "trip plan: showing ${row.kind} on the map")
                        scope.launch { runCatching { camera.animateTo(CameraPosition(target = p, zoom = PLAN_LOOK_ZOOM, bearing = 0.0, tilt = 0.0,
                            padding = PaddingValues(start = 640.dp, top = 80.dp, end = 80.dp, bottom = 160.dp)), kotlin.time.Duration.parse("1200ms")) } }
                    } },
                    // O222: the own stops in the new order - planned from here into the preview
                    onReorder = { order -> target?.let { tg -> Log.i(TAG, "stops reordered: ${order.size}"); purpose = Purpose.NEW; choose(tg, order) } },
                    // L-035: the route from here into the preview, where its charging stops can be taken out, in, or planned anew
                    onEdit = { order -> target?.let { tg -> Log.i(TAG, "trip plan: edit the route (${order.size} own stop(s) ahead)"); purpose = Purpose.NEW; choose(tg, order) } },
                    onDropCharge = { c -> panel = NaviStep.None; dropCharging(c) })
            }
            NaviStep.Search -> NaviPanel(Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 16.dp,
                bottom = net.prototux.aare.host.templates.LocalCarBottomInset.current + 12.dp).fillMaxHeight()
                .fillMaxWidth(net.prototux.aare.media.KEYBOARD_SHARE)) {
                // the nearest first: around the car, before a trip around what the map shows
                // F6: the categories - ahead on the guided route, otherwise around
                val categories: (NaviCategories.Kind) -> List<NaviCategories.Found> = { kind ->
                    val r = guided?.takeIf { guiding }
                    val at = here?.let { Position(longitude = it.coordinates.lng, latitude = it.coordinates.lat) } ?: camera.position.target
                    val index = ChargerIndex.get(context)
                    NaviCategories.find(kind, at, r?.let { raw(it) }, r?.let { it.distance - (trip?.progress?.distanceRemaining ?: it.distance) } ?: 0.0,
                        { q, rad -> index.near(q, rad, NaviChargers.MIN_KW) }, { q, rad -> NaviRest.near(context, q, rad) })
                }
                NaviSearchPanel(here?.let { Position(longitude = it.coordinates.lng, latitude = it.coordinates.lat) } ?: camera.position.target, driving,
                    categories = categories, alongRoute = guiding, title = pickTitle,
                    onPick = { h -> choose(net.prototux.aare.places.Places.Place("search", "", h.name, h.address, h.at.latitude, h.at.longitude)) },
                    onBack = { panel = NaviStep.Lists }, onClose = { panel = NaviStep.None })
            }
            NaviStep.Recent -> NaviPanel(panelMod) { NaviRecent(recentPins, onPick = { p ->
                    choose(net.prototux.aare.places.Places.Place("recent", "", p.name.ifBlank { context.getString(net.prototux.aare.R.string.places_on_map) }, "", p.at.latitude, p.at.longitude)) },
                onBack = { panel = NaviStep.Lists }, onClose = { panel = NaviStep.None }) }
            // O228: full-screen tiles instead of a small panel - where to go (or where to stop on the way)
            NaviStep.Lists -> {
                val home = book.places.firstOrNull { it.home }
                val pinned = book.places.filter { it.pinned && !it.home }
                fun category(kind: NaviCategories.Kind) { NaviSearchStart.kept = ""; NaviSearchStart.category = kind; panel = NaviStep.Search }
                val tiles = buildList {
                    if (pins.isNotEmpty()) add(HubTile(androidx.compose.material.icons.Icons.Rounded.History, context.getString(net.prototux.aare.R.string.mate_navi_recent)) {
                        panel = NaviStep.Recent
                        // the pins in the picture beside the panel
                        scope.launch {
                            val pad = 0.004
                            val b = org.maplibre.spatialk.geojson.BoundingBox(pins.minOf { it.longitude } - pad, pins.minOf { it.latitude } - pad,
                                pins.maxOf { it.longitude } + pad, pins.maxOf { it.latitude } + pad)
                            camera.animateTo(b, 0.0, 0.0, PaddingValues(start = 640.dp, top = 80.dp, end = 80.dp, bottom = 160.dp))
                        }
                    })
                    val places = listOfNotNull(home?.let { h -> HubTile(androidx.compose.material.icons.Icons.Rounded.Home, context.getString(net.prototux.aare.R.string.mate_navi_home)) { choose(h) } }) +
                        pinned.map { p -> HubTile(androidx.compose.material.icons.Icons.Rounded.PushPin, p.name) { choose(p) } }
                    val kinds = listOf(
                        HubTile(androidx.compose.material.icons.Icons.Rounded.Bolt, context.getString(net.prototux.aare.R.string.mate_navi_cat_chargers)) {
                            category(NaviCategories.Kind.CHARGER) },
                        HubTile(androidx.compose.material.icons.Icons.Rounded.Wc, context.getString(net.prototux.aare.R.string.mate_navi_cat_rest)) {
                            category(NaviCategories.Kind.REST) },
                        // Google's further kinds, the nearest by the map server's reverse search (O228)
                        HubTile(androidx.compose.material.icons.Icons.Rounded.Restaurant, context.getString(net.prototux.aare.R.string.mate_navi_cat_food)) {
                            NaviSearchStart.tag = "amenity:restaurant"; panel = NaviStep.Search },
                        HubTile(androidx.compose.material.icons.Icons.Rounded.Coffee, context.getString(net.prototux.aare.R.string.mate_navi_cat_cafe)) {
                            NaviSearchStart.tag = "amenity:cafe"; panel = NaviStep.Search },
                        HubTile(androidx.compose.material.icons.Icons.Rounded.ShoppingCart, context.getString(net.prototux.aare.R.string.mate_navi_cat_shop)) {
                            NaviSearchStart.tag = "shop:supermarket"; panel = NaviStep.Search })
                    // on the way the chargers and rest stops first (Google's "Hinzufügen"), else the own places
                    if (purpose == Purpose.STOP) { addAll(kinds); addAll(places) } else { addAll(places); addAll(kinds) }
                    book.categories.forEach { c -> add(HubTile(net.prototux.aare.ui.placeIcon(c.icon), c.name) { panel = NaviStep.List(c) }) }
                    if (purpose != Purpose.STOP) add(HubTile(androidx.compose.material.icons.Icons.Rounded.Tune, context.getString(net.prototux.aare.R.string.navi_options)) {
                        panel = NaviStep.Options(NaviStep.Lists) })
                }
                NaviHub(pickTitle, tiles, hubMod, onBack = if (guiding) ({ panel = NaviStep.Plan }) else null, onClose = { panel = NaviStep.None },
                    driverOnRight = driverOnRight,
                    onKeyboard = { panel = NaviStep.Search }, onMic = { NaviSearchStart.voice = true; panel = NaviStep.Search })
            }
            is NaviStep.List -> NaviPanel(panelMod) { NaviPlaces(book, book.categories.firstOrNull { it.id == st.category.id } ?: st.category,
                carAt ?: (if (testDrive) testFrom() else null), onPlace = { choose(it) },
                onOrder = { o -> book = net.prototux.aare.places.Places.setOrder(context, st.category.id, o) },
                onBack = { panel = NaviStep.Lists }, onClose = { panel = NaviStep.None }) }
            is NaviStep.Options -> NaviPanel(panelMod) { NaviRouteOptions(onBack = {
                    val back = st.back
                    // a preview is planned again with what is avoided now, keeping the own stops it had (Navi I-052b)
                    if (back is NaviStep.Preview) choose(back.place, if (purpose == Purpose.STOP) null else pendingOwn) else panel = back
                }, onClose = { panel = NaviStep.None; dropPreview() }) }
            is NaviStep.Preview -> NaviPanel(panelMod) { NaviPreview(preview, planError, st.place.name, routeOptions.map { it.kind to it.preview }, chosen, countdown,
                alternatives = { ch ->
                    val o = routeOptions.getOrNull(chosen); val car = net.prototux.aare.host.CarDataService.car(context)
                    val stop = o?.preview?.stops?.firstOrNull { it.charger === ch }
                    if (o == null || car == null || stop == null) emptyList() else
                        ChargePlanner.alternatives(ChargePlanner.track(o.route, car), stop, o.preview.stops.map { it.charger },
                            ChargerIndex.get(context)::near, ChargePlanner.Rules.of(net.prototux.aare.Settings(context)))
                },
                onStopOpened = { holdStart = true }, onReplaceStop = { old, c -> replaceStop(old, c) },
                onRemoveStop = { removeStop(it) }, onRestoreStop = { restoreStop(it) }, onRemoveAll = { removeAllStops() },
                onRecalc = { recalcStops() }, canPlan = net.prototux.aare.host.CarDataService.car(context) != null,
                onReorder = { order -> reorderStops(order) },
                onOptions = { panel = NaviStep.Options(st) }, onPick = { pick(it) }, onStart = { start() },
                onBack = { panel = book.categories.firstOrNull { it.id == st.place.category }?.let { NaviStep.List(it) } ?: NaviStep.Lists; dropPreview() },
                onClose = { panel = NaviStep.None; dropPreview() }) }
        }
        // own speed and the limit above the controls at the bottom left (wish of 16.11.2026); the limit a placeholder for now
        val ahead = if (trip == null || stopMarks.isEmpty()) null else guided?.let { r ->
            NaviStops.next(stopMarks, r.distance - trip.progress.distanceRemaining, r.steps.sumOf { it.duration } - trip.progress.durationRemaining)
        }
        val aheadCharger = ahead != null && chargeStops.any { ChargerIndex.distanceM(it.charger.at, ahead.mark.at) < 150 }
        // the charging still ahead counts into the arrival, as the preview and Google show it (it said 3 h 28 min where the
        // preview said 3 h 56 min with two stops, 16.11.2026)
        // with the stop the car stands at now (it charges there before going on)
        val chargingAheadS = if (trip == null) 0.0 else NaviKeep.stillToCharge(trip).sumOf { it.stayMinutes } * 60 +
            // F2: and what the incidents ahead cost (Valhalla's times know no traffic), as Google's arrival does
            (guided?.let { g -> NaviKeep.trafficDelayS(g.distance - trip.progress.distanceRemaining) } ?: 0.0)
        // O237: inside a jam, the time to its end and how far it still goes
        val jamLeft = guided?.let { g -> trip?.let { t -> val d = g.distance - t.progress.distanceRemaining
            jams.firstNotNullOfOrNull { j -> j.leftS(d, freeMs(g, d))?.let { it to (j.toM - d) } } } }
        if (trip != null) {
            val covered = net.prototux.aare.host.templates.LocalCarBottomInset.current
            // the speed the car reports first (its sensor - the desk window's setting there), then the location's, only then
            // the estimate along the line (smoothed: it jumped). It showed the simulation's estimate before (16.11.2026).
            val carSpeed = carState.speed?.takeIf { System.currentTimeMillis() - it.atMillis < 3000 }?.value?.metersPerSecond?.toDouble()
            // while the simulation drives, its own speed: the desk window's car stands at 0 and the tile said 0 on the move
            // (16.11.2026: "bleiben hängen weil wir aktuell keine geschwindigkeit simmulieren?")
            val simSpeed = if (session.simulating) glide?.smoothSpeedMs?.let { it / SIM_WARP } else null
            // the GPS's speed while the car's fix is good (enough satellites), the speedometer's in a tunnel (System L-088)
            val fix = CarFixes.last.value
            val sats = carState.satellites
            val picked = NaviSpeedSource.pick(CarFixes.gpsSpeedMs(), CarFixes.ageMs(), sats?.value?.inUse,
                sats?.let { System.currentTimeMillis() - it.atMillis } ?: Long.MAX_VALUE, fix?.takeIf { it.hasAccuracy() }?.accuracy, carSpeed)
            val speedFrom = if (NaviDebug.speedKmh != null || simSpeed != null) null else picked?.second
            val kmh = (NaviDebug.speedKmh ?: simSpeed ?: picked?.first ?: trip.userLocation.speed?.value ?: glide?.smoothSpeedMs)?.let { it * 3.6 }
            // the battery on arrival above it (O170): once per position is enough
            // with the charge of the planned stops not reached yet: a charger still among the waypoints ahead (also after a
            // new route) - before, the tile showed 0 % and the plus pole on a route that charges twice (16.11.2026)
            // the plan's own model where its rolling check runs on this route (weather, heights, what the car took): the
            // tile and the plan say the same (19.11.2026: the tile drifted from 20 to 40 % while a new plan said 6 %)
            val rollNow = NaviKeep.rolling?.takeIf { guided != null && NaviKeep.rollingRoute === guided && !testDrive }
            val arrival = remember(trip.progress.distanceRemaining.roundToLong() / 50, chargeStops, NaviKeep.atCharger.value, rollNow) {
                val ahead = NaviKeep.stillToCharge(trip)
                val charged = ahead.sumOf { it.leavePercent - it.arrivePercent }
                val car = net.prototux.aare.host.CarDataService.car(context)
                val g = guided
                if (rollNow != null && car != null && g != null) {
                    val driven = g.distance - trip.progress.distanceRemaining
                    val p = rollNow.levelAt(driven, car.socPercent, rollNow.endM) + charged
                    NaviCharge.Arrival(p, p < NaviCharge.reservePercent(context), spread = rollNow.spreadAt(driven, car.socPercent, rollNow.endM))
                } else NaviCharge.arrival(context, trip.remainingSteps, trip.progress.distanceToNextManeuver, charged)
            }
            SideEffect { arrivalNow = arrival }
            // the tile: the level at the next charging stop while one is ahead (not the one the car charges at now), the
            // destination's after the last; the trip plan keeps the destination's (arrivalNow)
            val tileArrival = remember(trip.progress.distanceRemaining.roundToLong() / 50, chargeStops, NaviKeep.atCharger.value, stopMarks, arrival, rollNow) {
                val g = guided
                val next = NaviKeep.stillToCharge(trip).firstOrNull { it !== NaviKeep.atCharger.value }
                val mark = next?.let { st -> stopMarks.firstOrNull { ChargerIndex.distanceM(it.at, st.charger.at) < 150 } }
                val driven = g?.let { it.distance - trip.progress.distanceRemaining }
                val car = net.prototux.aare.host.CarDataService.car(context)
                if (next != null && rollNow != null && car != null && driven != null && rollNow.alongM(next.charger) > driven) {
                    val at = rollNow.alongM(next.charger)
                    val p = rollNow.levelAt(driven, car.socPercent, at)
                    NaviCharge.Arrival(p, p < NaviCharge.chargerWarnPercent(context), atCharger = true, spread = rollNow.spreadAt(driven, car.socPercent, at))
                } else if (mark != null && driven != null && mark.metres > driven)
                    NaviCharge.arrivalAtCharger(context, trip.remainingSteps, trip.progress.distanceToNextManeuver, mark.metres - driven) ?: arrival
                else arrival
            }
            // each whole percent the tile changes, into the log with what it was counted from (19.11.2026: "Nur bei der
            // laufenden Route wird die Ankunftsenergie nicht nachgezogen an den aktuellen SoC" - to see which it is)
            LaunchedEffect(tileArrival?.percent?.roundToLong(), tileArrival?.atCharger) {
                val t = tileArrival ?: return@LaunchedEffect
                val car = net.prototux.aare.host.CarDataService.car(context)
                val age = (System.currentTimeMillis() - net.prototux.aare.dash.DashData.socAtMs(context)) / 60_000
                net.prototux.aare.DiagLog.add("charging: tile %.0f %%%s at the %s - level now %s (IDmate's reading %d min old), %.1f km left on the route"
                    .format(java.util.Locale.ROOT, t.percent, t.spread?.let { " ± %.1f".format(java.util.Locale.ROOT, it) } ?: "", if (t.atCharger) "next charger" else "destination",
                        car?.let { "%.1f %%".format(java.util.Locale.ROOT, it.socPercent) } ?: "unknown", age, trip.progress.distanceRemaining / 1000), area = "CHARGE")
            }
            androidx.compose.foundation.layout.Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = covered + 12.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                // ending the route at the driver's side, not only behind the tile of what is left (O179)
                // not while a panel is open: the menu has its own "Navigation beenden" (16.11.2026: "beenden oder beenden. ist auch doppelt")
                androidx.compose.animation.AnimatedVisibility(controlsShown && panel == NaviStep.None, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CancelButton(Modifier.heightIn(min = 64.dp), label = net.prototux.aare.R.string.mate_navi_end_route) { end() }
                        Spacer(Modifier.width(12.dp))
                        // back to the car at once, without waiting for the glide back
                        if (!following) { RecenterButton { touchedAt = 0L }; Spacer(Modifier.width(12.dp)) }
                        // the voice on or off (F4, as Google's loudspeaker button), beside "Route beenden" at the driver's side
                        VoiceButton(voiceMode) { voiceMode = voiceMode.next(); speaker.mode = voiceMode }
                    }
                }
                // by the distance driven along the guided route (Ferrostar's progress), not by the nearest point (the tunnel)
                val driven = guided?.let { it.distance - trip.progress.distanceRemaining }
                val limit = remember(driven?.let { (it / 5).roundToLong() }, limits) { driven?.let { limits?.along(it) } }
                // for tests without a picture: each change of the sign, with where along the route
                // into the diagnosis log too, with where along the route and how fast the car went (18.11.2026: "in der Stadt ...
                // immer noch 130 angezeigt, obwohl die Stadtautobahn schon auf 80 runtergesetzt war ... bei 50 hat er das aber
                // erst 200 Meter später übernommen ... Vielleicht sollte man das auch mitloggen") - to tell late data from a late sign
                LaunchedEffect(limit) {
                    Log.i(TAG, "speed limit: ${limit ?: "none"} at %.0f m".format(driven ?: 0.0))
                    if (driven != null) net.prototux.aare.DiagLog.add("speed limit: now ${when (limit) { null -> "unknown"; NaviLimits.UNLIMITED -> "none (motorway)"; else -> "$limit km/h" }}" +
                        " at %.2f km of the route%s, the car at %s".format(java.util.Locale.ROOT, driven / 1000,
                            trip.userLocation.coordinates.let { c -> " (%.5f, %.5f)".format(java.util.Locale.ROOT, c.lat, c.lng) },
                            kmh?.let { "%.0f km/h".format(java.util.Locale.ROOT, it) } ?: "unknown speed"))
                }
                // not under an open panel (16.11.2026, a new preview while guiding: the tiles covered "Abbrechen", and
                // "am Ziel" stood twice); the warnings below go on
                androidx.compose.animation.AnimatedVisibility(panel == NaviStep.None, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
                    androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                        // the charge at the destination and the speed, below them arrival, time and way left at the driver's side
                        // (18.11.2026: "Ankunft, Uhrzeit, Restzeit ... Restweg ... links ... dann hat man das mehr im Blick als
                        // Fahrer"; Navi L-033, 19.11.2026: "schieben wir den Balken mit den 3 Angaben unter die anderen beiden
                        // Felder. Dann sieht das aufgeräumter aus"); a tap on the bar opens the guidance menu as before
                        // a combustion car: no battery tile (18.11.2026)
                        if (!net.prototux.aare.Settings(context).combustion) NaviChargeTile(tileArrival, Modifier)
                        NaviSpeed(kmh, limit, Modifier, speedFrom) { beat }
                        NaviEta(trip.progress.distanceRemaining, trip.progress.durationRemaining + chargingAheadS, Modifier,
                            ahead, aheadCharger, jamLeft) { panel = NaviStep.Plan }
                    }
                }
                // O193/O194: the tone over the limit comes from NaviKeep, shown or not
            }
        }
        // the route as a strip (18.11.2026): at a quarter of the screen instead of the map (drawn last, below), at full
        // width, 3/4 and 2/3 slim and without labels along the right edge - under the hint cards there
        val stripG = guided
        val share = LocalNaviShare.current
        val stripData = if ((share < 0.3f || share >= 0.6f) && trip != null && stripG != null) {
            val routeS = stripG.steps.sumOf { it.duration }
            val drivenM = stripG.distance - trip.progress.distanceRemaining
            val rows = remember(trip.progress.distanceRemaining.roundToLong() / 100, stopMarks, chargeStops, ownStops, arrivalNow?.percent?.roundToLong()) {
                NaviTripPlan.rows(stopMarks, chargeStops, ownStops, drivenM, routeS - trip.progress.durationRemaining,
                    stripG.distance, routeS, System.currentTimeMillis(), target?.name.orEmpty(), arrivalNow?.percent, ferries = NaviTripPlan.ferries(stripG))
            }
            // each jam ahead in metres from here, its level from how much slower it is than free flow
            val stripJams = jams.map { j ->
                val freeS = (j.toM - j.fromM) / freeMs(stripG, j.midM).coerceAtLeast(1.0)
                val slow = (freeS + j.delayS) / freeS.coerceAtLeast(1.0)
                Triple(j.fromM - drivenM, j.toM - drivenM, when { slow < 1.5 -> 0; slow < 2.5 -> 1; slow < 5 -> 2; else -> 3 })
            }
            rows to stripJams
        } else null
        // road works, closures, accidents ahead (jams are coloured on the line already): their signs on the strip
        val stripIncidents = if (stripData != null && stripG != null && trip != null) {
            val d = stripG.distance - trip.progress.distanceRemaining
            NaviKeep.trafficAhead.value.filter { it.incident.kind != 6 && it.alongM > d && NaviTraffic.onOurWay(it) }.map { (it.alongM - d) to it.incident.kind }
        } else emptyList()
        // the whole height of the screen, with some room above and below (19.11.2026: "Der Wegstrahl, der kann ruhig die
        // komplette Höhe des Displays mit Abständen einnehmen, damit er ein wenig detaillierter dargestellt wird, wenn
        // mehrere Sachen auf der Route passieren sollten"); the hint cards top right move aside for it
        val slimStrip = stripData != null && share >= 0.6f && panel == NaviStep.None && trip != null
        val hintMod = Modifier.align(Alignment.TopEnd).padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = if (slimStrip) STRIP_ROOM else 16.dp).widthIn(max = 620.dp)
        if (slimStrip && stripData != null && trip != null)
            NaviRouteStrip(stripData.first, stripData.second, trip.progress.distanceRemaining, 0.0,
                Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 10.dp,
                    bottom = net.prototux.aare.host.templates.LocalCarBottomInset.current + 40.dp).fillMaxHeight(), labels = false,
                totalM = stripG?.distance ?: trip.progress.distanceRemaining, incidents = stripIncidents)
        // at a planned charging stop: what to charge to and how long (as ABRP), top right; the plan's hint waits meanwhile
        val atStop = NaviKeep.atCharger.value
        if (atStop != null && panel == NaviStep.None && trip != null) NaviAtCharger(atStop,
            // after this stop: the driving and the later stops' charging
            target?.name?.let { context.getString(net.prototux.aare.R.string.mate_navi_at_charger_after,
                minutesText(trip.progress.durationRemaining / 60.0 + NaviKeep.stillToCharge(trip).filter { it !== atStop }.sumOf { it.stayMinutes }), it) },
            hintMod) { NaviKeep.atCharger.value = null }
        // F2: a faster route round a jam, top right, before all other hints (O218)
        val fasterNow = NaviKeep.faster.value?.takeIf { atStop == null && panel == NaviStep.None && trip != null }
        if (fasterNow != null) NaviFasterHint(fasterNow.second, hintMod,
            onSwitch = {
                val r = fasterNow.first
                NaviKeep.faster.value = null; NaviKeep.aroundClosure.value = false
                session.go(r, simulate = testDrive)
                show(r); drawnLine = NaviRoute.smooth(raw(r)); limits = null
                scope.launch { limits = NaviLimits.load(context, raw(r)) }
                Log.i(TAG, "faster route taken: %.1f km".format(r.distance / 1000))
            },
            onDismiss = { NaviKeep.faster.value = null; NaviKeep.aroundClosure.value = false }, closure = NaviKeep.aroundClosure.value)
        // off the route, no new one yet: first of all hints, top right (as Google)
        val offRoute by session.offRoute.collectAsState()
        var rerouteFailing by remember { mutableStateOf(false) }
        LaunchedEffect(offRoute) { while (offRoute) { rerouteFailing = NaviServer.routeFailAt > 0; kotlinx.coroutines.delay(1_000) }; rerouteFailing = false }
        if (offRoute && trip != null) NaviRerouteHint(rerouteFailing, hintMod)
        // F2 step 2: the next incident on the route within 10 km, top right - not over the charging cards
        val drivenNow = guided?.let { g -> trip?.let { g.distance - it.progress.distanceRemaining } }
        val nextIncident = if (offRoute || drivenNow == null || atStop != null || fasterNow != null || panel != NaviStep.None) null
            else NaviKeep.trafficAhead.value.firstOrNull { it.alongM > drivenNow && it.alongM - drivenNow < 10_000 && NaviTraffic.onOurWay(it) }
        if (nextIncident != null && drivenNow != null) NaviTrafficHint(nextIncident, nextIncident.alongM - drivenNow,
            hintMod)
        // F3: the hint when the plan no longer fits the battery, top right
        if (atStop == null && nextIncident == null) chargeCheck?.takeIf { it.verdict != chargeCheckLeft && panel == NaviStep.None && trip != null }?.let { c ->
            NaviChargeHint(c, hintMod,
                onReplan = { chargeCheckLeft = c.verdict; target?.let { purpose = Purpose.NEW; choose(it) } },
                onDismiss = { chargeCheckLeft = c.verdict })
        }
        // N-019 step 1: warm the battery now, top right, below the other hints (it waits while one of them shows)
        val chargeHintShown = chargeCheck?.let { it.verdict != chargeCheckLeft } == true
        if (atStop == null && nextIncident == null && fasterNow == null && !offRoute && !chargeHintShown && panel == NaviStep.None && trip != null)
            preheat?.takeIf { it.first !== preheatLeft }?.let { (c, a) -> NaviPreheatHint(a, hintMod) { preheatLeft = c } }
        // what is left: distance, time, arrival (wish of 16.11.2026), bottom right
        // above the strip naming what plays when the bar shows one (O191: the music as everywhere, its title runs along
        // the bottom edge)
        val strip = net.prototux.aare.ui.car.ShellDebug.titleShown
        // the trip's figures give way to a panel: a preview shows its own, and the menu is what the tile opens
        // the licence asks for OpenStreetMap to be named (ODbL), not the library: small, at the free bottom right - at
        // the top right while the title strip takes the bottom edge
        // TomTom's traffic named as its terms ask, while it is drawn (22.11.2026: "die copyrights erwähnungen … fehlen")
        // System I-085 (22.11.2026: "der Wegstrahl … über den Text von OpenStreetMap … im geteilten Display … unter dem
        // Bedienfeld die Copyrights … Da müssten die oben angezeigt werden"): at the bottom only where nothing covers it -
        // no title strip and no controls laid over the map's bottom edge; otherwise at the top, left of the route strip
        val bottomCovered = strip || net.prototux.aare.host.templates.LocalCarBottomInset.current > 0.dp
        Text(if (trafficShown) "$OSM_CREDIT · " + androidx.compose.ui.res.stringResource(net.prototux.aare.R.string.mate_navi_tomtom_credit) else OSM_CREDIT, color = Mate.textFaint, style = MaterialTheme.typography.labelSmall,
            modifier = if (bottomCovered) Modifier.align(Alignment.TopEnd).padding(end = if (slimStrip) STRIP_ROOM else 16.dp, top = 2.dp)
                else Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp))
        // a quarter of the screen: the route strip over the map
        if (stripData != null && share < 0.3f && trip != null)
            NaviRouteStrip(stripData.first, stripData.second, trip.progress.distanceRemaining, trip.progress.durationRemaining + chargingAheadS,
                Modifier.pointerInput(Unit) { detectTapGestures { } }, incidents = stripIncidents)
    }
}

private const val TAG = "MateNavi"

/**
 * The test trip (wish of 16.11.2026: the old one "zu gerade und dauert zu lange"): Potsdamer Platz to the Siegessäule,
 * 2.8 km with the bends of the Tiergartenstraße, turns and the roundabout Großer Stern, simulated [SIM_WARP] times as
 * fast - about two minutes a test.
 */
private val TEST_FROM = Position(longitude = 13.3762, latitude = 52.5096)
/** The test trip's start: [TEST_FROM], or the place a test set (debug_navi_from, test builds only). */
private fun testFrom(): Position = NaviDebug.from ?: TEST_FROM
private const val SIM_WARP = 3
/** No stop moved this long after a route set on purpose, and a move said only after [ROLL_SAY_AFTER_MS] (System I-094). */
private const val ROLL_CALM_MS = 120_000L
private const val ROLL_SAY_AFTER_MS = 300_000L
/** How close the map comes to a stop picked in the trip plan (System L-089). */
private const val PLAN_LOOK_ZOOM = 17.0
/** After the driver moved or zoomed the map, how long until it follows the car again (wish: "nach 5-10s zum auto zurück"). */
private const val RESUME_FOLLOW_MS = 6000L

/** How long the charging plan waits for the map server's heights, the weather and IDmate's rain (then without them). */
private const val HEIGHTS_WAIT_MS = 12_000L
/** What the slim route strip at the right edge takes, the hint cards keep clear of it. */
private val STRIP_ROOM = 64.dp
/** The level must have fallen by this much for the trip's energy to be taken from it (1 % is some 0.8 kWh). */
private const val MEASURED_MIN_PERCENT = 3.0
private const val WEATHER_WAIT_MS = 8_000L
private const val RAIN_WAIT_MS = 5_000L

/**
 * The charging plan's lines for the diagnosis log too (17.11.2026: Hamburg from 99 % - beyond the range - planned no
 * stop; the exported log must say why, logcat is not at hand in the car).
 */
private fun chargeLog(msg: String, level: net.prototux.aare.DiagLog.Level? = null) { Log.i("MateNavi", msg); net.prototux.aare.DiagLog.add(msg, level) }
/** A glide back to the car that has not finished by then counts as done. */
private const val RETURN_MAX_MS = 3000L
/** Navi I-001: the guidance ended this close to the destination counts as arrived for "Letzte Ziele". */
private const val END_NEAR_M = 1_000.0
private const val START_ZOOM = 15.0
private const val CONTROLS_MS = 5_000L
private const val DRIVE_TILT = 45.0
/** Where the car sits: the camera's focus moved down, the way ahead in view. */
private val FOCUS_TOP = 360.dp
private val ARROW = 84.dp
private const val PIN_PX = 110
/** O237: at most this many jams ahead get their "+N min" sign. */
private const val JAM_LABELS = 3
private const val EMPTY_FC = "{\"type\":\"FeatureCollection\",\"features\":[]}"

/** The route's own speed at [m] metres along it (its step's distance by its time), m/s - the free speed for the jams. */
private fun freeMs(r: uniffi.ferrostar.Route, m: Double): Double {
    var at = 0.0
    for (st in r.steps) { at += st.distance; if (at >= m) return if (st.duration > 0) st.distance / st.duration else 13.9 }
    return 13.9
}

/** The point [m] metres along [line]. */
private fun pointAt(line: List<Position>, m: Double): Position {
    var at = 0.0
    for (i in 1 until line.size) {
        val d = ChargerIndex.distanceM(line[i - 1], line[i])
        if (at + d >= m && d > 0) { val f = (m - at) / d
            return Position(longitude = line[i - 1].longitude + (line[i].longitude - line[i - 1].longitude) * f,
                latitude = line[i - 1].latitude + (line[i].latitude - line[i - 1].latitude) * f) }
        at += d
    }
    return line.last()
}

/** The guiding veil (L-047): its source and layer in the style, under the first label layer (withVeil, NaviStyleTest). */
internal const val VEIL_ID = "fuehrung-schleier"
private const val VEIL_NONE = """{"type":"FeatureCollection","features":[]}"""
/** The whole world (Web Mercator's latitudes) - while guiding. */
private const val VEIL_WORLD = """{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[[-180,-85.05],[180,-85.05],[180,85.05],[-180,85.05],[-180,-85.05]]]}}"""

/** How strongly the other roads step back while guiding (L-047): the land's colour over the base map. */
private const val GUIDE_VEIL = 0.35f

/** O237: the jam's sign, "+4 min" in white on the map's jam red, as Google's; nothing for an empty [text]. */
private fun jamSign(text: String, night: Boolean): androidx.compose.ui.graphics.ImageBitmap {
    if (text.isEmpty()) return android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f; typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD) }
    val w = p.measureText(text) + 28f; val h = 46f
    val bmp = android.graphics.Bitmap.createBitmap(w.toInt() + 4, h.toInt() + 4, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    p.color = android.graphics.Color.WHITE
    c.drawRoundRect(0f, 0f, w + 4, h + 4, (h + 4) / 2, (h + 4) / 2, p)
    p.color = if (night) 0xFFB3261E.toInt() else 0xFFD93025.toInt()
    c.drawRoundRect(2f, 2f, w + 2, h + 2, h / 2, h / 2, p)
    p.color = android.graphics.Color.WHITE
    c.drawText(text, 16f, 2f + h / 2 - (p.descent() + p.ascent()) / 2, p)
    return bmp.asImageBitmap()
}
private const val CHARGER_PX = 40
private const val CLOSED_PX = 30
private const val WORKS_PX = 40
/** How far Valhalla may put an own stop off its chosen place onto the road (O222). */
internal const val OWN_SNAP_M = 1_000.0
/** A stop of the driver's this close to a charger is at it (O225). */
private const val OWN_CHARGER_M = 120.0
/** How often the charging plan is checked against the battery while guiding (F3). */
private const val CHARGE_CHECK_MS = 30_000L
/** A trip lost with the connection this recently goes on by itself (a charging stop, a break). */
private const val AUTO_RESUME_MS = 3 * 60 * 60_000L
private const val STOP_PX = 64
private const val ARRIVAL_DELAY_MS = 3000L
private const val BEARING_DAMPING_MS = 700.0
private const val CAMERA_BEHIND_M = 40.0
/** N6: how far ahead the camera looks when the car is too fast for the coming curve (it goes on straight, likely). */
private const val CAMERA_NEAR_M = 30.0


/**
 * An even 30 frames a second, whatever the car's video runs at (decision of 16.11.2026, O168: "Gleichmäßig 30"): with a
 * 60 frames video the tablet managed only 45-52, unevenly (22-40 ms), at about a full core.
 */
private fun mapFps() = if (naviFpsSetting == "video") net.prototux.aare.car.projectionFps.coerceIn(30, 60) else 30
/** Read when Mate Navi opens (O174: settable for tests, Tablet-App → Navi). */
private var naviFpsSetting = "30"

internal const val OSM_CREDIT = "© OpenStreetMap"

/** The width of the template turn card (host/templates/MapUi.kt panelWidth). */
private fun cardWidth(max: androidx.compose.ui.unit.Dp) = (max * 0.42f).coerceIn(320.dp, 480.dp).coerceAtMost(max)

/**
 * IDmate's map (Roundtrip, Protomaps schema v4 - the map server's tiles): assets/navi/karte-v4.json, generated with its
 * colours from IDmate's mapstyle.js and maprules.js (projekt/werkzeuge/navi-stil.mjs); the colours themselves in
 * res/values/mate_colors.xml, by day and by night as the car says. Lettering in Noto Sans from assets/navi/glyphs.
 */
internal fun mapStyle(context: Context, night: Boolean, offline: RegionPacks.LocalMap? = null): String {
    val json = context.assets.open("navi/karte-v4.json").bufferedReader().use { it.readText() }
    val styled = Regex("""\{k:([a-z_]+)\}""").replace(json.replace("{TILES}", NaviServer.tiles(context))) { m ->
        Mate.karte(context, m.groupValues[1], night)?.let(::hex) ?: "#FF00FF"
    }
    // the land's colour for the guiding veil, from the context (Mate's colours may not be set up yet, e.g. in the phone app)
    val land = String.format("#%06X", context.getColor(net.prototux.aare.R.color.mate_map_land) and 0xFFFFFF)
    if (offline == null) return withVeil(if (net.prototux.aare.Settings(context).trafficShown && !TomTomBudget.exhausted(context)) withTraffic(context, styled, night) else styled, land)
    // without the net: the same style on the region packages on the device - all of them at once, through Mate's own
    // tile service (OfflineTiles), so the regions meet without gaps
    val o = org.json.JSONObject(styled)
    o.getJSONObject("sources").getJSONObject("pm").apply { put("tiles", org.json.JSONArray().put(OfflineTiles.url(context))) }
    return withVeil(o.toString(), land)
}

/**
 * The guiding veil (L-047) in [style] itself: an empty GeoJSON source and a fill in the land's colour right under the
 * first label layer (over the traffic, as before). Mate Navi gives it the world's extent while guiding.
 */
internal fun withVeil(style: String, land: String): String = runCatching {
    val o = org.json.JSONObject(style)
    o.getJSONObject("sources").put(VEIL_ID, org.json.JSONObject("""{"type":"geojson","data":$VEIL_NONE}"""))
    val layers = o.getJSONArray("layers")
    val veil = org.json.JSONObject("""{"id":"$VEIL_ID","type":"fill","source":"$VEIL_ID","paint":{"fill-color":"$land","fill-opacity":$GUIDE_VEIL}}""")
    val at = (0 until layers.length()).firstOrNull { layers.getJSONObject(it).optString("type") == "symbol" } ?: layers.length()
    val out = org.json.JSONArray()
    for (i in 0 until layers.length()) { if (i == at) out.put(veil); out.put(layers.get(i)) }
    if (at == layers.length()) out.put(veil)
    o.put("layers", out)
    o.toString()
}.getOrDefault(style)

/**
 * F2: the traffic on the roads from TomTom's flow tiles through the map server's gate (handover: the gate passes
 * TomTom's own paths on, /verkehr/traffic/map/4/tile/flow/relative/{z}/{x}/{y}.pbf, layer "Traffic flow", traffic_level
 * 0 standing ... 1 free): only what is slow, as Google colours it - orange, red, dark red. Closed roads are not a colour
 * (dark red is standing traffic): small red crosses in a red ring along them, drawn by the screen (wish 17.11.2026). Under
 * the labels, beside the road for each direction; tiles of zoom 10-12 only, drawn larger beyond (each tile is a TomTom
 * call - the day's budget, TomTomBudget); none once the budget is used up.
 */
private fun withTraffic(context: Context, style: String, night: Boolean): String = runCatching {
    val base = net.prototux.aare.Settings(context).mapServer.trimEnd('/').ifBlank { return style }
    fun c(k: String) = Mate.karte(context, k, night)?.let(::hex) ?: "#FF00FF"
    val o = org.json.JSONObject(style)
    o.getJSONObject("sources").put("verkehr", org.json.JSONObject().put("type", "vector").put("minzoom", 10).put("maxzoom", 12)
        .put("tiles", org.json.JSONArray().put("$base/verkehr/traffic/map/4/tile/flow/relative/{z}/{x}/{y}.pbf")))
    val layer = org.json.JSONObject("""{"id":"verkehr","type":"line","source":"verkehr","source-layer":"Traffic flow","minzoom":10,
        "filter":["all",["!",["has","road_closure"]],["<",["to-number",["get","traffic_level"],1],0.75]],
        "layout":{"line-cap":"round","line-join":"round"},
        "paint":{"line-color":["step",["to-number",["get","traffic_level"],1],"${c("verkehr_steht")}",0.25,"${c("verkehr_stau")}",0.5,"${c("verkehr_zaeh")}"],
          "line-width":["interpolate",["linear"],["zoom"],9,1.5,13,3,16,6], "line-opacity":0.45,
          "line-offset":["interpolate",["linear"],["zoom"],9,0.5,13,2,16,4]}}""")
    // under it a light edge a little wider (the map's main roads are reddish themselves)
    val edge = org.json.JSONObject(layer.toString()).put("id", "verkehr-kontur").apply {
        getJSONObject("paint").put("line-color", c("verkehr_kontur"))
            .put("line-width", org.json.JSONArray("""["interpolate",["linear"],["zoom"],9,3,13,5.5,16,9]"""))
    }
    val layers = o.getJSONArray("layers")
    val at = (0 until layers.length()).firstOrNull { layers.getJSONObject(it).optString("type") == "symbol" } ?: layers.length()
    val out = org.json.JSONArray()
    for (i in 0 until layers.length()) { if (i == at) { out.put(edge); out.put(layer) }; out.put(layers.get(i)) }
    if (at == layers.length()) { out.put(edge); out.put(layer) }
    o.put("layers", out); o.toString()
}.getOrElse { android.util.Log.w("MateNavi", "traffic layer: $it"); style }

/** Whether the device has the net now: a real network under it - Tailscale's VPN stays "connected and validated" with Wi-Fi and mobile data gone (16.11.2026). */
internal fun deviceOnline(context: Context): Boolean {
    // test builds: "offline" (debug_navi_offline) also picks the packages on the device as the map's source
    if (net.prototux.aare.BuildConfig.TEST_HOOKS && NaviDebug.offline) return false
    val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
    @Suppress("DEPRECATION")
    return cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.let {
        it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) && !it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
    } == true }
}

/** Whether the device has the net now (a validated connection), followed while the screen shows. */
@Composable
internal fun rememberOnline(context: Context): Boolean {
    val cm = remember { context.getSystemService(android.net.ConnectivityManager::class.java) }
    fun now() = deviceOnline(context)
    var online by remember { mutableStateOf(now()) }
    DisposableEffect(cm) {
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: android.net.Network, c: android.net.NetworkCapabilities) { online = now() }
            override fun onLost(n: android.net.Network) { online = now() }
        }
        cm.registerNetworkCallback(android.net.NetworkRequest.Builder().build(), cb)
        onDispose { cm.unregisterNetworkCallback(cb) }
    }
    return online
}

private fun hex(c: Color) = String.format("#%06X", c.toArgb() and 0xFFFFFF)

/** The car between two positions: it glides along the [track] from where it is shown to the newest position. */
private class Glide(private val track: NaviTrack) {
    /** For the log line: "simulated" in a test drive (the picture tool looks for it), else "live". */
    var label = "simulated"
    class Pose(val point: Position, val heading: Double, val cameraBearing: Double)
    private var fromS = -1.0
    private var toS = -1.0
    private var start = 0L
    private var span = 1000L
    private var last = 0L
    private var shownS = -1.0
    private var cam: Double? = null
    private var lastAt = 0L
    var speedMs: Double? = null
        private set
    /** The car's real speed for the camera (N6), set by the screen each frame (the simulation runs faster). */
    var cameraSpeedMs: Double? = null
    private var lastInto: Boolean? = null
    private var intoNow = true
    /** The estimate over several positions (a single one jumps with the simulation's uneven steps). */
    var smoothSpeedMs: Double? = null
        private set
    private var fixes = 0
    private var frames = 0
    private var meters = 0.0
    private var maxStep = 0.0
    private var maxTurn = 0.0
    private var since = 0L
    private var prev: Pose? = null

    fun next(lat: Double, lon: Double, now: Long) {
        fixes++
        val s = track.project(lat, lon)
        // the speed along the line between two positions, for a position source that does not tell it (the simulation)
        if (last > 0 && toS >= 0 && now > last) {
            speedMs = ((s - toS) / ((now - last) / 1000.0)).coerceAtLeast(0.0)
            smoothSpeedMs = smoothSpeedMs?.let { it + (speedMs!! - it) * 0.25 } ?: speedMs
        }
        if (last > 0) span = (now - last).coerceIn(100L, 2000L)
        last = now
        fromS = if (shownS >= 0) shownS else s
        toS = s
        start = now
    }

    fun at(now: Long): Pose? {
        if (toS < 0) return null
        val t = ((now - start).toDouble() / span).coerceIn(0.0, 1.0)
        val s = fromS + (toS - fromS) * t
        shownS = s
        val heading = track.heading(s)
        // the camera turns calmly towards the way ahead (wish "die karte wackelt")
        // the map's direction over a long stretch, 40 m behind to 80 m ahead: a short jog (Verschwenkung) barely turns it;
        // N6: into a coming curve from the next 100 m when the speed fits it, else the road close ahead only
        val curve = ((track.bearing(s + 10.0, s + NaviCamera.AHEAD_M) - heading + 540) % 360) - 180
        val into = NaviCamera.intoCurve(curve, cameraSpeedMs, intoNow)
        intoNow = into
        if (into != lastInto && Math.abs(curve) >= 20) {
            lastInto = into
            android.util.Log.i("MateNavi", "camera: %s, curve %.0f° in 100 m at %.0f km/h".format(if (into) "into the curve" else "close ahead (too fast)",
                curve, (cameraSpeedMs ?: 0.0) * 3.6))
        }
        val target = if (into) track.bearing(s - CAMERA_BEHIND_M, s + NaviCamera.AHEAD_M)
            else track.bearing(s - CAMERA_BEHIND_M, s + CAMERA_NEAR_M)
        val dt = if (lastAt == 0L) 0L else now - lastAt
        lastAt = now
        val c = cam ?: target
        val d = ((target - c + 540) % 360) - 180
        cam = (c + d * (1 - Math.exp(-dt / BEARING_DAMPING_MS)) + 360) % 360
        return Pose(track.at(s), heading, cam!!)
    }

    /** Every 5 s in the log: moves and positions per second, metres per move, largest turn of the map per move. */
    fun measure(now: Long, p: Pose) {
        prev?.let { q ->
            val m = Math.hypot((p.point.latitude - q.point.latitude) * 111_320.0,
                (p.point.longitude - q.point.longitude) * 111_320.0 * Math.cos(Math.toRadians(p.point.latitude)))
            meters += m; if (m > maxStep) maxStep = m
            val turn = Math.abs(((p.cameraBearing - q.cameraBearing + 540) % 360) - 180); if (turn > maxTurn) maxTurn = turn
        }
        prev = p; frames++
        if (since == 0L) since = now
        if (now - since >= 5000) {
            android.util.Log.i("MateNavi", "glide ($label): %.1f moves/s, %.1f positions/s, %.2f m per move, largest %.2f m, largest map turn %.2f°".format(
                frames * 1000.0 / (now - since), fixes * 1000.0 / (now - since), meters / frames, maxStep, maxTurn))
            frames = 0; fixes = 0; meters = 0.0; maxStep = 0.0; maxTurn = 0.0; since = now
        }
    }
}

/** Where the destination choice stands. */
internal sealed interface NaviStep {
    data object None : NaviStep
    data object Lists : NaviStep
    data object Menu : NaviStep
    /** O217: a place held on the map - navigate there, or (while guiding) stop there on the way. */
    data class Pick(val at: Position, val charger: Charger? = null, val fromPreview: net.prototux.aare.places.Places.Place? = null) : NaviStep
    /** O209: the stops ahead and the destination, from the menu while guiding. */
    data object Plan : NaviStep
    data object Recent : NaviStep
    data object Search : NaviStep
    /** The route settings (O188); [back] is where the panel came from - a preview is planned again on return. */
    data class Options(val back: NaviStep) : NaviStep
    data class List(val category: net.prototux.aare.places.Places.Category) : NaviStep
    data class Preview(val place: net.prototux.aare.places.Places.Place) : NaviStep
}

private fun raw(route: uniffi.ferrostar.Route) = route.geometry.map { Position(longitude = it.lng, latitude = it.lat) }

/**
 * The route [r] as a list for the preview (L-035): its waypoints before the destination placed along it (the driver's
 * own stops [own] and the charging stops [stops]), each with the clock time from now and the way, the destination last.
 */
internal fun planRows(r: uniffi.ferrostar.Route, own: List<Pair<Position, String>>, stops: List<ChargePlanner.Stop>, destination: String,
                      destinationPercent: Double?): List<NaviTripPlan.Row> {
    val marks = NaviStops.marks(raw(r), r.steps.map { it.distance to it.duration },
        r.waypoints.drop(1).dropLast(1).map { Position(longitude = it.coordinate.lng, latitude = it.coordinate.lat) })
    val at = r.waypoints.lastOrNull()?.let { Position(longitude = it.coordinate.lng, latitude = it.coordinate.lat) }
    return NaviTripPlan.rows(marks, stops, own, 0.0, 0.0, r.distance, r.steps.sumOf { it.duration }, System.currentTimeMillis(), destination, destinationPercent, at,
        ferries = NaviTripPlan.ferries(r))
}

/** What a place chosen in the lists is for: a new trip, another destination, or a stop on the way. */
internal enum class Purpose { NEW, CHANGE, STOP }

/** A route of the preview to choose (O180). */
internal class RouteOption(val kind: NaviSession.Kind, val route: uniffi.ferrostar.Route, val preview: Preview)

/** Hooks for tests in the desk window (debug builds only, through mate-tool). */
internal object NaviDebug {
    /** Test drives silent (17.11.2026: "die Stimme hat gesprochen, obwohl keine Navigation lief") - only {"voice":true} speaks. */
    @Volatile var quiet = false
    /** Tests: the map server unreachable (every request through Mate's client fails), as without the net. */
    @Volatile var offline = false
    /** Tests: offline from the moment the missed turn's detour is planned (debug_navi_miss_turn {"offline":true}). */
    @Volatile var offlineAfterMiss = false
    @Volatile var missTurn: (() -> Unit)? = null
    /** A test destination straight into the preview (its countdown then starts the guidance). */
    @Volatile var goTo: ((Double, Double) -> Unit)? = null
    @Volatile var openSearch: ((String) -> Unit)? = null
    /** A stop on the way at a place (tests of several stops and their order, O222). */
    @Volatile var addStop: ((Double, Double, String) -> Unit)? = null
    /** A delay in seconds counted as ahead on the guided route (tests of the faster route; null = none). */
    @Volatile var fakeDelayS: Int? = null
    /** The position from the car/phone instead of the simulation (F1 tests; in the desk window the window's test place). */
    @Volatile var live: (() -> Unit)? = null
    /** Tests: the simulated car sets off here instead of the test start (routes far from Berlin, e.g. over a ferry). */
    @Volatile var from: Position? = null
    /** A speed in km/h the screen takes instead of the car's (tests of the limit sign and warning, O194/O195); null = real. */
    var speedKmh by androidx.compose.runtime.mutableStateOf<Double?>(null)
    /** Opens the search with this text, as if typed (F6 tests); taken once by the search panel. */
    var searchQuery by androidx.compose.runtime.mutableStateOf<String?>(null)
}

/** L-009: puts the map back on the car after the driver moved it (no glide back by time without a route). */
@Composable
private fun RecenterButton(onClick: () -> Unit) {
    Row(Modifier.heightIn(min = 64.dp).floatEdge(RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))
        .background(Mate.cardSolid).clickable(onClick = onClick).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.MyLocation, null, tint = Mate.text, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Text(androidx.compose.ui.res.stringResource(net.prototux.aare.R.string.mate_navi_recenter), style = MaterialTheme.typography.titleMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = Mate.text)
    }
}

/** Mate Navi's voice: loud, only warnings, mute (O192) - one tap goes to the next, its word beside the sign. */
@Composable
private fun VoiceButton(mode: NaviSpeaker.Mode, onClick: () -> Unit) {
    val (icon, label) = when (mode) {
        NaviSpeaker.Mode.LOUD -> Icons.AutoMirrored.Rounded.VolumeUp to net.prototux.aare.R.string.mate_navi_voice_loud
        NaviSpeaker.Mode.BRIEF -> Icons.AutoMirrored.Rounded.VolumeDown to net.prototux.aare.R.string.mate_navi_voice_brief
        NaviSpeaker.Mode.ALERTS -> Icons.Rounded.Warning to net.prototux.aare.R.string.mate_navi_voice_alerts
        NaviSpeaker.Mode.MUTE -> Icons.AutoMirrored.Rounded.VolumeOff to net.prototux.aare.R.string.mate_navi_voice_mute
    }
    Row(Modifier.heightIn(min = 64.dp).floatEdge(RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))
        .background(Mate.cardSolid).clickable(onClick = onClick).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (mode == NaviSpeaker.Mode.MUTE) Mate.warn else Mate.text, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Text(androidx.compose.ui.res.stringResource(label), style = MaterialTheme.typography.titleMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = Mate.text)
    }
}

/** The traffic switch as the map sees it: set when the driver turns it in the route options, else the setting. */
internal object NaviTrafficSwitch { val on = androidx.compose.runtime.mutableStateOf<Boolean?>(null) }
