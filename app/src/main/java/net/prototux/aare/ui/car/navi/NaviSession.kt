package net.prototux.aare.ui.car.navi

import okhttp3.RequestBody.Companion.toRequestBody
import com.stadiamaps.ferrostar.core.FerrostarCore
import com.stadiamaps.ferrostar.core.NavigationState
import com.stadiamaps.ferrostar.core.http.OkHttpClientProvider
import com.stadiamaps.ferrostar.core.location.NavigationLocationProvider
import com.stadiamaps.ferrostar.core.location.SimulatedLocationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.maplibre.spatialk.geojson.Position
import uniffi.ferrostar.CourseFiltering
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.LocationBias
import uniffi.ferrostar.NavigationControllerConfig
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteDeviationTracking
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointAdvanceMode
import uniffi.ferrostar.WaypointKind
import uniffi.ferrostar.WellKnownRouteProvider
import uniffi.ferrostar.stepAdvanceDistanceEntryAndExit
import uniffi.ferrostar.stepAdvanceDistanceToEndOfStep
import java.time.Instant

/**
 * One guided trip (step 3b of projekt/navi-app_v0.1.md): Ferrostar asks the own Valhalla for the route, follows the
 * position along it, advances the steps and notices a deviation. The position is the real one (F1: the car's GPS,
 * else the phone's - NaviLiveLocation), simulated along the route only for tests (the desk window, the test hooks);
 * pictures are taken of simulated drives only.
 */
internal class NaviSession(parent: CoroutineScope, private val valhalla: String, private val http: OkHttpClient = OkHttpClient(),
                           val routeOptions: RouteOptions = RouteOptions(), val live: NaviLiveLocation? = null) {
    /** The session's own child of [parent]: ended by [close] when the session is made anew. */
    private val job = SupervisorJob(parent.coroutineContext.job)
    private val scope = CoroutineScope(parent.coroutineContext + job)
    val simulated = SimulatedLocationProvider(scope)
    /**
     * F2 step 3: points on closed roads that the routes keep away from (Valhalla's exclude_locations) - set from TomTom's
     * closures along the planned routes; kept for the charging replans of the same trip.
     * Declared first: the core below is made from options() when the session is (it read null - the crash of 17.11. 05:12).
     */
    @Volatile var closures: List<Position> = emptyList()
    /** The closed stretches the last planning found on its routes (shown on the map, O220) and the areas kept from. */
    var closureLines: List<List<Position>> = emptyList(); private set
    @Volatile private var closureAreas: List<List<Position>> = emptyList()


    /** Ferrostar's switch between the real position and the simulation. */
    private val provider = live?.let { NavigationLocationProvider(it, simulated) }
    private val positions get() = provider ?: simulated

    private val config = NavigationControllerConfig(
        WaypointAdvanceMode.WaypointWithinRange(100.0),
        stepAdvanceDistanceEntryAndExit(30u, 5u, 32u),
        // the destination counts as reached 25 m from the route's end (it was 10: "wenn ich wirklich auf 1 Meter genau
        // zum Ziel fahren muss", 18.11.2026); coming to a stop near it ends the trip as well (NaviKeep, arriveNow)
        stepAdvanceDistanceToEndOfStep(25u, 32u),
        // off the route from 30 m on (F1: Google turns to the new route right after the junction; 50 m was a block late)
        RouteDeviationTracking.StaticThreshold(15u, DEVIATION_M),
        CourseFiltering.SNAP_TO_ROUTE,
    )

    /** Valhalla's answers read for the ferries on the way (NaviFerries) before Ferrostar takes them. */
    private val routeHttp: okhttp3.OkHttpClient = http.newBuilder().addInterceptor(closuresIn).addInterceptor(NaviFerries.interceptor).build()

    /**
     * System I-090 (22.11.2026: "die Sperrung wurde angezeigt auf der Karte, aber er hat dadurch navigieren wollen"): the
     * new route after a deviation is asked by Ferrostar with the options the session was made with - without the closures
     * found later, so Valhalla led back into the closed road again and again. Every route request without closures of its
     * own gets the trip's ones now (not those close to the car: a car standing in a closed stretch must get out).
     */
    private val closuresIn: okhttp3.Interceptor get() = okhttp3.Interceptor { chain ->
        val req = chain.request()
        val body = req.body
        // only the guidance's own new route after a deviation: the planning puts its closures in itself (and leaves out
        // those near a charging stop on purpose)
        val deviating = offRoute.value || (core.state.value.tripState as? uniffi.ferrostar.TripState.Navigating)?.deviation is uniffi.ferrostar.RouteDeviation.Deviation
        if (!deviating || !req.url.encodedPath.endsWith("/route") || body == null || (closures.isEmpty() && closureAreas.isEmpty())) return@Interceptor chain.proceed(req)
        val text = runCatching { okio.Buffer().also { body.writeTo(it) }.readUtf8() }.getOrNull() ?: return@Interceptor chain.proceed(req)
        val json = runCatching { org.json.JSONObject(text) }.getOrNull()
        if (json == null || json.has("exclude_locations") || json.has("exclude_polygons")) return@Interceptor chain.proceed(req)
        val from = json.optJSONArray("locations")?.optJSONObject(0)?.let { Position(longitude = it.optDouble("lon"), latitude = it.optDouble("lat")) }
        val points = closures.filter { from == null || ChargerIndex.distanceM(it, from) > NEAR_CAR_M }
        if (points.isNotEmpty()) json.put("exclude_locations", org.json.JSONArray(points.map { org.json.JSONObject().put("lat", it.latitude).put("lon", it.longitude) }))
        val areas = closureAreas.filter { ring -> from == null || ring.none { ChargerIndex.distanceM(it, from) < NEAR_CAR_M } }
        if (areas.isNotEmpty()) json.put("exclude_polygons", org.json.JSONArray(areas.map { ring -> org.json.JSONArray(ring.map { org.json.JSONArray().put(it.longitude).put(it.latitude) }) }))
        if (points.isEmpty() && areas.isEmpty()) return@Interceptor chain.proceed(req)
        android.util.Log.i(TAG, "route request: the trip's ${points.size} closure point(s), ${areas.size} area(s) put in")
        val first = chain.proceed(req.newBuilder().method(req.method, json.toString().toRequestBody(body.contentType())).build())
        // refused with them (a point Valhalla cannot place): once more as it was
        if (first.code == 400) { first.close(); net.prototux.aare.DiagLog.add("reroute: refused with the trip's closures - asked without them"); chain.proceed(req) } else first
    }

    private val core = FerrostarCore(
        // German instructions (specification F-06); without it Valhalla answers in English
        // with the driver's route settings, so that a new route after leaving it keeps them too (O181)
        // up to three routes on a new route after leaving the guided one: the one with the least known jam is taken (I-080)
        WellKnownRouteProvider.Valhalla("$valhalla/route", "auto", options(false, REROUTE_ALTERNATES, routeOptions)),
        OkHttpClientProvider(routeHttp),
        positions,
        config,
        null,
    )

    val state: StateFlow<NavigationState> get() = core.state

    /** The route being guided; changes when Ferrostar replaces it after a deviation (F1). */
    private val _route = MutableStateFlow<Route?>(null)
    val route: StateFlow<Route?> get() = _route
    /** Recalculations of this trip and the time from noticing the deviation to the new route, for the log and tests. */
    var reroutes = 0; private set
    /** When the last route was set on purpose ([go]: the start, a plan put in, a stop changed, the faster route). */
    @Volatile var goneAt = 0L; private set
    private var offSince = 0L
    /** Whether Mate Navi's position comes from the simulation (tests) rather than the car or the phone. */
    var simulating = live == null; private set
    private var wasOff = false
    /** Jams met on this trip with their place and direction (NaviKeep), for choosing a new route (I-080). */
    @Volatile var knownJams: List<NaviTraffic.KnownJam> = emptyList()

    /** Off the route and no new one yet (Google's "Route wird neu berechnet"): the hint over the map. */
    val offRoute = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var lastOff = 0L

    init {
        // F1 "Neuberechnung sofort": ask again 2 s after the last new route at the earliest (Ferrostar's default waits
        // longer), and after 20 m of movement - a car that went the wrong way is far enough away by then
        core.minimumTimeBeforeRecalculation = kotlin.time.Duration.parse("2s")
        core.minimumMovementBeforeRecalculation = 20.0
        // the new route goes to the guidance and, in a simulated drive, to the simulated car (it drives the new one on)
        core.alternativeRouteProcessor = com.stadiamaps.ferrostar.core.AlternativeRouteProcessor { c, routes ->
            // the least of time plus the jams known on this trip (Valhalla knows no traffic and led back into the jam the
            // driver had just left, I-080)
            val jams = knownJams.filter { android.os.SystemClock.elapsedRealtime() - it.seenAtMs < JAM_KEEP_MS }
            val pick = NaviTraffic.pickRoute(routes.map { r -> r.steps.sumOf { it.duration } to r.geometry.map { Position(longitude = it.lng, latitude = it.lat) } }, jams)
            if (routes.size > 1 && jams.isNotEmpty()) {
                val desc = routes.mapIndexed { i, r -> "%.0f min + %d s jam".format(r.steps.sumOf { it.duration } / 60,
                    NaviTraffic.knownDelayS(r.geometry.map { Position(longitude = it.lng, latitude = it.lat) }, jams)) + if (i == pick) " (taken)" else "" }
                android.util.Log.i(TAG, "reroute: ${routes.size} routes, ${jams.size} known jam(s): ${desc.joinToString("; ")}")
                if (pick != 0) net.prototux.aare.DiagLog.add("reroute: not Valhalla's first route but number ${pick + 1}, round a known jam (${desc.joinToString("; ")})")
            }
            val r = routes.getOrNull(pick) ?: return@AlternativeRouteProcessor
            c.replaceRoute(r, config)
            if (simulating) simulated.setRoute(r, LocationBias.None)
            reroutes++
            pace()
            val ms = if (offSince > 0) android.os.SystemClock.elapsedRealtime() - offSince else -1
            offSince = 0
            offRoute.value = false
            android.util.Log.i(TAG, "reroute %d: new route %.1f km after %d ms off the route".format(reroutes, r.distance / 1000, ms))
            _route.value = r
        }
        scope.launch {
            core.state.collect { s ->
                val off = (s.tripState as? uniffi.ferrostar.TripState.Navigating)?.deviation is uniffi.ferrostar.RouteDeviation.Deviation
                // the moment it first left the route; kept until the new route is there (Ferrostar's states between
                // may already read "no deviation" while the new route is asked for)
                if (off && !wasOff && offSince == 0L) { offSince = android.os.SystemClock.elapsedRealtime(); android.util.Log.i(TAG, "off the route") }
                wasOff = off
                val now = android.os.SystemClock.elapsedRealtime()
                if (off) lastOff = now
                offRoute.value = offSince > 0 && now - lastOff < 5_000
                calm()
            }
        }
    }

    /** When the last new routes came (elapsedRealtime), to slow them down when they come in a row. */
    private val recentReroutes = ArrayDeque<Long>()

    /**
     * New routes in a row slow down (19.11.2026: "dass ich mich in der gesperrten Straße scheinbar wiederfinde von der
     * Autobahnbaustelle und er im Sekundentakt versucht, eine neue Route zu planen, was wahrscheinlich zum Dauerspam auf
     * meinen Server folgt"): in road works the traffic runs where the map has no road (switched over to the other
     * carriageway) - every new route is left at once again. More than [BURST] in a minute: the next one not before 30 s,
     * more than [BURST_HARD]: not before 60 s; after two minutes without one, at once again (2 s).
     */
    private fun pace() {
        val now = android.os.SystemClock.elapsedRealtime()
        recentReroutes.addLast(now)
        while (recentReroutes.isNotEmpty() && now - recentReroutes.first() > 60_000) recentReroutes.removeFirst()
        val wait = when {
            recentReroutes.size > BURST_HARD -> 60
            recentReroutes.size > BURST -> 30
            else -> 2
        }
        val was = core.minimumTimeBeforeRecalculation.inWholeSeconds.toInt()
        if (wait != was) {
            core.minimumTimeBeforeRecalculation = kotlin.time.Duration.parse("${wait}s")
            if (wait > 2) net.prototux.aare.DiagLog.warn("route: ${recentReroutes.size} new routes in a minute (left again at once - road works where the map has no road?): the next not before $wait s", "NAVI")
        }
    }

    /** Back to new routes at once after a calm while (called with every position). */
    private fun calm() {
        val last = recentReroutes.lastOrNull() ?: return
        if (android.os.SystemClock.elapsedRealtime() - last > 120_000 && core.minimumTimeBeforeRecalculation.inWholeSeconds > 2) {
            core.minimumTimeBeforeRecalculation = kotlin.time.Duration.parse("2s")
            recentReroutes.clear()
            net.prototux.aare.DiagLog.add("route: calm again - new routes at once again", area = "NAVI")
        }
    }

    /**
     * Test of F1 in a simulated drive: the car keeps straight on at the next manoeuvre instead of turning - it is sent
     * on a route of its own to a point 400 m beyond the junction in the direction it comes from. Returns what it did.
     */
    suspend fun missTurn(): String {
        val trip = core.state.value.tripState as? uniffi.ferrostar.TripState.Navigating ?: return "no trip"
        val step = trip.remainingSteps.firstOrNull() ?: return "no step"
        val g = step.geometry
        if (g.size < 2) return "step without line"
        val a = g[g.size - 2]; val b = g.last()
        val bearing = Math.atan2(b.lng - a.lng, (b.lat - a.lat) / Math.cos(Math.toRadians(b.lat)))
        val dLat = 400.0 / 111_320 * Math.cos(bearing)
        val dLng = 400.0 / (111_320 * Math.cos(Math.toRadians(b.lat))) * Math.sin(bearing)
        val past = GeographicCoordinate(b.lat + dLat, b.lng + dLng)
        val here = trip.userLocation
        val detour = withContext(Dispatchers.IO) {
            planner(options(false, 0, routeOptions)).getRoutes(here, listOf(Waypoint(past, WaypointKind.BREAK, null))).first()
        }
        simulated.setRoute(detour, LocationBias.None)
        if (NaviDebug.offlineAfterMiss) { NaviDebug.offlineAfterMiss = false; NaviDebug.offline = true; android.util.Log.i(TAG, "test: offline now") }
        android.util.Log.i(TAG, "test: straight on past \"${step.instruction}\", detour %.2f km".format(detour.distance / 1000))
        return "straight on past: ${step.instruction}"
    }

    /** What the driver chooses between (O180): the fastest, an economical one (fast too), an alternative. */
    enum class Kind { FAST, ECO, ALT }
    class Choice(val kind: Kind, val route: Route, val charging: ChargePlanner.Plan? = null)

    /**
     * Valhalla's options for the driver's route settings (O181: ferries, motorways, borders, tolls) and, for the
     * economical route, a top speed of 120: the time is reckoned with it, so the economical route stays on the
     * motorway instead of crawling over country roads (O180 "sparsam soll nicht bewirken dass er über land kriechen will").
     */
    private fun options(eco: Boolean, alternates: Int, o: RouteOptions,
                        points: List<Position> = closures, areas: List<List<Position>> = closureAreas): String {
        val auto = org.json.JSONObject()
        if (o.avoidFerries) auto.put("use_ferry", 0.0)
        if (o.avoidMotorways) auto.put("use_highways", 0.0)
        if (o.avoidTolls) auto.put("use_tolls", 0.0)
        if (o.avoidBorders) auto.put("country_crossing_penalty", 3600)
        if (eco) auto.put("top_speed", 120)
        // no date_time: with it Valhalla gives no alternative routes (17.11.2026, the preview had only one) - the live
        // speeds of O218 are for the map server to bring in without it (handover)
        return org.json.JSONObject().put("language", net.prototux.aare.Language.tag()).apply {
            if (alternates > 0) put("alternates", alternates)
            if (points.isNotEmpty()) put("exclude_locations", org.json.JSONArray(points.map { org.json.JSONObject().put("lat", it.latitude).put("lon", it.longitude) }))
            if (areas.isNotEmpty()) put("exclude_polygons", org.json.JSONArray(areas.map { ring -> org.json.JSONArray(ring.map { org.json.JSONArray().put(it.longitude).put(it.latitude) }) }))
            if (auto.length() > 0) put("costing_options", org.json.JSONObject().put("auto", auto))
        }.toString()
    }

    /** One core per set of options (Ferrostar takes them when it is made); the guidance stays on [core]. */
    private val planners = HashMap<String, FerrostarCore>()
    private fun planner(json: String) = synchronized(planners) {
        planners.also { if (it.size > 12 && json !in it) it.clear() }.getOrPut(json) {
            FerrostarCore(WellKnownRouteProvider.Valhalla("$valhalla/route", "auto", json), OkHttpClientProvider(routeHttp), positions, config, null)
        }
    }

    /** The routes to choose from: fast (and Valhalla's alternatives), economical; same routes only once. */
    /**
     * The routes to choose from, planned around the closed roads on them: [closuresAlong] gives the points on closed
     * roads a line runs over (TomTom through the gate); found any, the choices are planned again without them.
     */
    suspend fun planChoices(from: Position, to: Position, via: List<Position>, o: RouteOptions,
                            closuresAlong: (suspend (List<Position>) -> List<List<Position>>)? = null,
                            /** Valhalla's routes before the closures are looked for (System L-095): to be shown at once */
                            onFirst: (suspend (List<Choice>) -> Unit)? = null): List<Choice> {
        closures = emptyList(); closureAreas = emptyList(); closureLines = emptyList()
        var choices = planChoicesOnce(from, to, via, o)
        if (closuresAlong == null) return choices
        onFirst?.invoke(choices)
        fun key(l: List<Position>) = l.first().let { p -> "%.5f,%.5f".format(java.util.Locale.ROOT, p.latitude, p.longitude) }
        val lines = LinkedHashMap<String, List<Position>>()
        // O216/O219: never through a closure. A route planned round one closure may run into another (17.11.: round the
        // Behrenstraße into the closed Tunnel Tiergarten) - so the closures are asked again along the new routes, until
        // no new one turns up; points on the closed stretches first, narrow areas along the whole roads if still needed
        var lastStill = -1.0
        for (round in 1..MAX_ROUNDS) {
            // the routes asked side by side
            val found = runCatching { coroutineScope { choices.map { c -> async { closuresAlong(line(c.route)) } }.flatMap { it.await() } } }
                .onFailure { android.util.Log.w("MateNavi", "closures: $it") }.getOrNull().orEmpty()
            val fresh = found.filter { lines.putIfAbsent(key(it), it) == null }
            val still = choices.maxOf { NaviTraffic.onClosures(line(it.route), lines.values.toList()) }
            android.util.Log.i("MateNavi", "closures round $round: ${fresh.size} new, ${lines.size} in all; still on them: %.0f m".format(still))
            // done when free, or when a round brings nothing new and no better (a start or end in a closed block)
            if (fresh.isEmpty() && (still == 0.0 || still >= lastStill && lastStill >= 0)) break
            lastStill = still
            closures = NaviTraffic.excludePoints(lines.values.toList())
            // the areas only for the stretches a route still runs on (Valhalla's 100 corners)
            if (fresh.isEmpty() || round >= 2) closureAreas = NaviTraffic.excludeAreas(lines.values.filter { l ->
                choices.any { NaviTraffic.onClosures(line(it.route), listOf(l)) > 0 } })
            choices = runCatching { planChoicesOnce(from, to, via, o) }.onFailure {
                android.util.Log.w("MateNavi", "around closures: $it")
                net.prototux.aare.DiagLog.add("closures round $round: the routes round ${closures.size} point(s) and ${closureAreas.size} area(s) refused (${it.message ?: it}) - the routes before kept")
            }.getOrNull() ?: choices
        }
        closureLines = lines.values.toList()
        val all = closureLines
        val free = choices.filter { NaviTraffic.onClosures(line(it.route), all) == 0.0 }
        android.util.Log.i("MateNavi", "closures: ${all.size} stretches; routes free of them: ${free.size} of ${choices.size}")
        if (all.isNotEmpty()) net.prototux.aare.DiagLog.add("closures: ${all.size} stretch(es) along the routes, ${closures.size} point(s), ${closureAreas.size} area(s) kept out; routes free of them: ${free.size} of ${choices.size}")
        choices.firstOrNull()?.route?.steps?.take(5)?.let { st -> android.util.Log.i("MateNavi", "first steps: " + st.joinToString { "%s %.0f m".format(it.roadName ?: "-", it.distance) }) }
        // a free one instead only when it is not much longer (17.11.2026: a 422 km detour was taken for 40 m on a closure
        // at the start of a 300 km trip - a start on a closed stretch cannot be helped)
        val fastest = choices.minOf { it.route.steps.sumOf { s -> s.duration } }
        val shortest = choices.minOf { it.route.distance }
        // Navi I-064 (24.11.2026, a closed road in town on a 2.6 km trip): 15 % more way is a few hundred metres there - a way
        // round a closed street was thrown away; now the share or a fixed allowance (5 km, 10 min), whichever is more
        val fair = free.filter { it.route.steps.sumOf { s -> s.duration } <= maxOf(fastest * 1.1 + 300, fastest + 600) &&
            it.route.distance <= maxOf(shortest * 1.15, shortest + 5_000) }
        return if (fair.isNotEmpty() && fair.size < choices.size) fair else choices
    }

    private fun line(r: Route) = r.geometry.map { Position(longitude = it.lng, latitude = it.lat) }
    private val MAX_ROUNDS = 4

    private suspend fun planChoicesOnce(from: Position, to: Position, via: List<Position>, o: RouteOptions): List<Choice> = coroutineScope {
        val here = UserLocation(GeographicCoordinate(from.latitude, from.longitude), 5.0, null, Instant.now(), null)
        val points = (via + listOf(to)).map { Waypoint(GeographicCoordinate(it.latitude, it.longitude), WaypointKind.BREAK, null) }
        val fast = async(Dispatchers.IO) { planner(options(false, 2, o)).getRoutes(here, points) }
        val eco = async(Dispatchers.IO) { runCatching { planner(options(true, 0, o)).getRoutes(here, points).firstOrNull() }.getOrNull() }
        val f = fast.await()
        val out = mutableListOf(Choice(Kind.FAST, f.first()))
        eco.await()?.let { e -> if (!same(e, f.first())) out += Choice(Kind.ECO, e) }
        f.drop(1).firstOrNull { a -> out.none { same(it.route, a) } }?.let { out += Choice(Kind.ALT, it) }
        out
    }

    /**
     * F2: the routes from where the car is to the same waypoints (stops and destination), Valhalla's alternatives with
     * them, kept off the trip's closures - for the faster route while guiding.
     */
    suspend fun routesFrom(from: Position, waypoints: List<Position>): List<Route> = withContext(Dispatchers.IO) {
        val here = UserLocation(GeographicCoordinate(from.latitude, from.longitude), 5.0, null, Instant.now(), null)
        val points = waypoints.map { Waypoint(GeographicCoordinate(it.latitude, it.longitude), WaypointKind.BREAK, null) }
        // no exclusions right at the car: Valhalla finds no start on an excluded road and answers 400 (17.11.2026); the
        // points only, the areas are for the planning - given to this request, the trip's own closures stay as they are
        val json = options(false, 2, routeOptions, closures.filter { ChargerIndex.distanceM(it, from) > NEAR_CAR_M }, emptyList())
        runCatching { planner(json).getRoutes(here, points) }.getOrElse { e ->
            // Valhalla's own words for the log (Ferrostar keeps only the status)
            runCatching {
                val q = org.json.JSONObject(json).put("costing", "auto").put("locations", org.json.JSONArray(
                    (listOf(from) + waypoints).map { org.json.JSONObject().put("lat", it.latitude).put("lon", it.longitude) }))
                http.newCall(okhttp3.Request.Builder().url("$valhalla/route?json=" + java.net.URLEncoder.encode(q.toString(), "UTF-8")).build())
                    .execute().use { android.util.Log.w("MateNavi", "routes from here: ${it.code} ${it.body.string().take(300)}") }
            }
            throw e
        }
    }
    private val NEAR_CAR_M = 200.0
    private val BURST = 3
    private val BURST_HARD = 5

    /**
     * One route of [kind] over [via] (the charging stops), with the driver's route settings, kept off the trip's closures -
     * but not near the start, a stop or the destination (Navi I-035, the log of 19.11.2026: every route over the chargers
     * "refused: Route request failed with status code 400", so the plan's stops were only shown beside a route without
     * them; a charger at a service area whose access TomTom reports closed lies on an excluded road, and Valhalla finds no
     * road for it - as for the car's own place, 17.11.2026). Refused all the same: Valhalla's own words into the log, and
     * asked once more without the closures.
     */
    suspend fun planOne(kind: Kind, from: Position, to: Position, via: List<Position>, o: RouteOptions): Route = withContext(Dispatchers.IO) {
        val here = UserLocation(GeographicCoordinate(from.latitude, from.longitude), 5.0, null, Instant.now(), null)
        val points = (via + listOf(to)).map { Waypoint(GeographicCoordinate(it.latitude, it.longitude), WaypointKind.BREAK, null) }
        val places = listOf(from) + via + listOf(to)
        val (keptPoints, keptAreas) = awayFrom(places, closures, closureAreas, NEAR_STOP_M)
        if (keptPoints.size < closures.size || keptAreas.size < closureAreas.size)
            net.prototux.aare.DiagLog.add("route over the stops: ${closures.size - keptPoints.size} closure point(s) and ${closureAreas.size - keptAreas.size} area(s) near a stop left out")
        val json = options(kind == Kind.ECO, 0, o, keptPoints, keptAreas)
        try { planner(json).getRoutes(here, points).first() }
        catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            explain(json, places, "route over the stops")
            if (keptPoints.isEmpty() && keptAreas.isEmpty()) throw e
            net.prototux.aare.DiagLog.add("route over the stops: refused with ${keptPoints.size} closure point(s) and ${keptAreas.size} area(s) - asked again without them")
            planner(options(kind == Kind.ECO, 0, o, emptyList(), emptyList())).getRoutes(here, points).first()
        }
    }

    /** Valhalla's own words for a refused request (Ferrostar keeps only the status), into the diagnosis log - no places. */
    private fun explain(json: String, places: List<Position>, what: String) {
        runCatching {
            val q = org.json.JSONObject(json).put("costing", "auto").put("locations", org.json.JSONArray(
                places.map { org.json.JSONObject().put("lat", it.latitude).put("lon", it.longitude) }))
            http.newCall(okhttp3.Request.Builder().url("$valhalla/route?json=" + java.net.URLEncoder.encode(q.toString(), "UTF-8")).build())
                .execute().use { r ->
                    val body = r.body.string()
                    // only the error's code and words (Valhalla's error answers carry no places)
                    val words = runCatching { org.json.JSONObject(body).let { "error ${it.optInt("error_code")}: ${it.optString("error")}" } }.getOrDefault(body.take(160))
                    net.prototux.aare.DiagLog.add("$what: Valhalla answered ${r.code} - $words")
                }
        }.onFailure { net.prototux.aare.DiagLog.add("$what: refused, and asking Valhalla why failed: ${it.message ?: it}") }
    }
    /** Closures this close to the start, a stop or the destination are not sent with a route over stops. */
    private val NEAR_STOP_M = 1500.0

    private fun same(a: Route, b: Route) = Math.abs(a.distance - b.distance) < 50 && Math.abs(a.geometry.size - b.geometry.size) < 5

    /** Starts the guidance on a planned [route]: [simulate] - the simulated car sets off at its start, else the real one. */
    fun go(route: Route, simulate: Boolean = true) {
        simulating = simulate || provider == null
        if (simulating) { provider?.enableSimulationOn(route, LocationBias.None) ?: simulated.setRoute(route, LocationBias.None) }
        else provider?.disableSimulation()
        core.startNavigation(route, config)
        reroutes = 0; offSince = 0
        goneAt = android.os.SystemClock.elapsedRealtime()
        _route.value = route
    }

    fun stop() { core.stopNavigation(); _route.value = null }

    /** The session is done with (made anew): the guidance stopped, its collectors ended, the planning cores let go. */
    fun close() {
        stop()
        job.cancel()
        synchronized(planners) { planners.clear() }
    }

    /**
     * The trip ends as arrived, here and now (18.11.2026: the car has come to a stop near the destination): the steps
     * left are closed one by one until the trip is complete - the same end as driving onto the route's last metres.
     */
    fun arriveNow() {
        var left = (core.state.value.tripState as? uniffi.ferrostar.TripState.Navigating)?.remainingSteps?.size ?: return
        while (left-- >= 0 && core.state.value.tripState is uniffi.ferrostar.TripState.Navigating) core.advanceToNextStep()
    }

    companion object {
        private const val TAG = "MateNavi"
        const val DEVIATION_M = 30.0
        /** Alternatives asked with a new route after leaving the guided one (I-080). */
        private const val REROUTE_ALTERNATES = 2
        /** How long a jam once seen counts when choosing a new route. */
        private const val JAM_KEEP_MS = 30 * 60_000L
    }
}

/** The driver's route settings (O181), from the phone app's page Navi. */
internal data class RouteOptions(val avoidFerries: Boolean = false, val avoidMotorways: Boolean = false, val avoidTolls: Boolean = false, val avoidBorders: Boolean = false) {
    companion object {
        fun of(s: net.prototux.aare.Settings) = RouteOptions(s.avoidFerries, s.avoidMotorways, s.avoidTolls, s.avoidBorders)
    }
}

/**
 * The closure points and areas ([points], [areas]) that lie farther than [metres] from all of [places] (start, stops,
 * destination of a route over stops): Valhalla finds no road for a place on an excluded one and refuses the route (I-035).
 */
internal fun awayFrom(places: List<Position>, points: List<Position>, areas: List<List<Position>>, metres: Double): Pair<List<Position>, List<List<Position>>> {
    fun near(p: Position) = places.any { ChargerIndex.distanceM(it, p) < metres }
    return points.filterNot(::near) to areas.filterNot { ring -> ring.any(::near) }
}
