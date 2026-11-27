package net.prototux.aare.ui.car.navi

import android.content.Context
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.maplibre.spatialk.geojson.Position
import uniffi.ferrostar.TripState

/**
 * The guided trip outside the screen (16.11.2026, the night run: with the music in front the guidance ended - Mate Navi
 * lived only while shown). As Google Maps does, the trip goes on behind the other areas: the session, its position
 * source and voice live here for the process, with what the trip is (destination, stops, line); the spoken
 * instructions and the turns for the car's cluster and head-up display are given from here, shown or not. The screen
 * shows the trip and takes the driver's choices.
 */
internal object NaviKeep {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var made: Triple<NaviSession, NaviLiveLocation, NaviSpeaker>? = null

    /** A test sentence in Mate Navi's voice, as a prompt goes (the debug app); false while Mate Navi was not opened yet. */
    fun sayTest(text: String): Boolean = made?.third?.let { it.warn(text); true } ?: false

    /** The session, its live position and voice - made once with what the screen gives the first time. */
    fun parts(context: Context, options: RouteOptions, makeSession: (CoroutineScope, NaviLiveLocation) -> NaviSession): Triple<NaviSession, NaviLiveLocation, NaviSpeaker> {
        // the route settings go into the session when it is made (a new route after leaving one keeps them): changed in
        // the meantime and no trip running, it is made anew
        made?.let { m -> if (!guiding.value && m.first.routeOptions != options) {
            m.first.close()
            made = Triple(makeSession(scope, m.second), m.second, m.third).also { watch(context.applicationContext, it.first, it.third) }
        } }
        return made ?: run {
            val app = context.applicationContext
            val live = NaviLiveLocation(app)
            Triple(makeSession(scope, live), live, NaviSpeaker(app)).also { made = it; watch(app, it.first, it.third)
                it.third.prepareWarnings(listOf(app.getString(net.prototux.aare.R.string.mate_navi_warn_new_route))) }
        }
    }

    // the trip: kept over the screen coming and going
    val guiding = mutableStateOf(false)
    val testDrive = mutableStateOf<Boolean?>(null)
    val target = mutableStateOf<net.prototux.aare.places.Places.Place?>(null)
    val destination = mutableStateOf<Position?>(null)
    val routeJson = mutableStateOf<String?>(null)
    val drawnLine = mutableStateOf<List<Position>>(emptyList())
    val limits = mutableStateOf<NaviLimits?>(null)
    val chargeStops = mutableStateOf<List<ChargePlanner.Stop>>(emptyList())
    val ownStops = mutableStateOf<List<Pair<Position, String>>>(emptyList())
    val startedAt = mutableLongStateOf(0L)
    val plannedKm = mutableDoubleStateOf(0.0)
    /**
     * The way driven on this trip, over every route it had (19.11.2026: "Bei einer abgeschlossenen Route zeigt er an, Fahrt
     * dauert 22 Minuten, das stimmt, aber er zeigt an 0,9 Kilometer, weil er die Kilometer vor der Routenneuberechnung
     * vergessen hat"): added up from the guidance's progress, a new route taken from where the car is. [startSoc] the
     * level at the start, for the energy the trip took.
     */
    val drivenTotalM = mutableDoubleStateOf(0.0)
    @Volatile var startSoc: Double? = null
    @Volatile private var drivenRoute: Any? = null
    @Volatile private var drivenLast = 0.0
    fun startTrip(soc: Double?) { drivenTotalM.doubleValue = 0.0; drivenRoute = null; drivenLast = 0.0; startSoc = soc; eta = null; closuresHandled.clear(); visited.clear() }
    /** Navi I-075: the planned chargers the car reached on this trip (no more stops to guard). */
    val visited: MutableSet<Charger> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /**
     * The arrival as said and as it came, for the log (19.11.2026: "auf einer 15-Minuten-Route, hat er sich um 5 Minuten
     * verschätzt ... Ich sollte 8 Uhr ankommen, jetzt komme ich erst 8.05 Uhr an. Er hat die Staus und sowas drin gehabt mit
     * plus 2 Minuten, plus 1 Minute ... dass wir den Log haben, dass wir am Ball bleiben können"): the arrival said at the
     * start with what it was made of, every change of a minute or more (looked at every 2 min, at least every 5 min), and
     * at the end the difference, the time standing (lights, jams) and Valhalla's time for the way against the time driven.
     */
    private class Eta(val startMs: Long, val saidMs: Long, val routeS: Double, val trafficS: Double, val chargeS: Double) {
        var lastLogMs = startMs; var lastSaidMs = saidMs; var standingS = 0.0
        var lastTickMs = startMs; var lastRemainingM = Double.NaN; var valhallaS = 0.0; var lastDurationS = Double.NaN
        var done = false
    }
    @Volatile private var eta: Eta? = null

    private fun clock(ms: Long) = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(ms))
    private fun min(s: Double) = "%.1f".format(java.util.Locale.ROOT, s / 60)

    /** One step of the guidance: the arrival said now, followed for the log ([eta]). */
    private fun noteEta(t: TripState.Navigating, routeS: Double, trafficS: Double, chargeS: Double) {
        val now = System.currentTimeMillis()
        val said = now + ((routeS + trafficS + chargeS) * 1000).toLong()
        val e = eta ?: Eta(now, said, routeS, trafficS, chargeS).also {
            eta = it
            net.prototux.aare.DiagLog.add("eta: arrival ${clock(said)} at the start - way ${min(routeS)} min (Valhalla), traffic +${min(trafficS)} min, " +
                "charging +${min(chargeS)} min, %.1f km".format(java.util.Locale.ROOT, t.progress.distanceRemaining / 1000), area = "NAVI")
        }
        // standing: under 2 m on in a step of a second or more; Valhalla's time for the way: what its remaining time fell by
        val dt = (now - e.lastTickMs) / 1000.0
        if (!e.lastRemainingM.isNaN() && dt > 0.5) {
            if (e.lastRemainingM - t.progress.distanceRemaining < 2.0 * dt) e.standingS += dt
            val fell = e.lastDurationS - t.progress.durationRemaining
            if (fell > 0 && fell < 600) e.valhallaS += fell
        }
        e.lastTickMs = now; e.lastRemainingM = t.progress.distanceRemaining; e.lastDurationS = t.progress.durationRemaining
        val shift = (said - e.lastSaidMs) / 60_000.0
        if ((now - e.lastLogMs >= ETA_LOOK_MS && kotlin.math.abs(shift) >= 1.0) || now - e.lastLogMs >= ETA_EVERY_MS) {
            val sinceStart = (said - e.saidMs) / 60_000.0
            net.prototux.aare.DiagLog.add("eta: arrival ${clock(said)} (%+.0f min since the start's ${clock(e.saidMs)}), %.1f km left, way ${min(routeS)} min, ".format(
                java.util.Locale.ROOT, sinceStart, t.progress.distanceRemaining / 1000) + "traffic +${min(trafficS)} min, charging +${min(chargeS)} min, stood ${min(e.standingS)} min so far",
                if (kotlin.math.abs(sinceStart) >= 3) net.prototux.aare.DiagLog.Level.WARN else null, "NAVI")
            e.lastLogMs = now; e.lastSaidMs = said
        }
    }

    /** The trip arrived: the arrival against what was said at the start. */
    private fun etaArrived() {
        val e = eta ?: return
        if (e.done) return
        e.done = true
        val now = System.currentTimeMillis()
        val late = (now - e.saidMs) / 60_000.0
        val drove = (now - e.startMs) / 1000.0
        net.prototux.aare.DiagLog.add("eta: arrived ${clock(now)}, said ${clock(e.saidMs)} at the start - %s %.0f min; ".format(java.util.Locale.ROOT,
            if (late >= 0) "late by" else "early by", kotlin.math.abs(late)) +
            "drove ${min(drove)} min, Valhalla's time for the way ${min(e.valhallaS)} min (said ${min(e.routeS)} at the start), " +
            "traffic said +${min(e.trafficS)} min, charging +${min(e.chargeS)} min, stood ${min(e.standingS)} min",
            if (late >= 3) net.prototux.aare.DiagLog.Level.WARN else null, "NAVI")
    }
    private const val ETA_LOOK_MS = 120_000L
    private const val ETA_EVERY_MS = 300_000L
    /** One step of the guidance on route [r] ([lengthM] long): what it adds to the way driven (a new route counts from where it begins). */
    fun noteProgress(r: Any, lengthM: Double, remainingM: Double) {
        val done = lengthM - remainingM
        if (r !== drivenRoute) { drivenRoute = r; drivenLast = done; return }
        val d = done - drivenLast
        // forward only, and no jumps (a snap onto a far part of the line is no driving)
        if (d > 0 && d < MAX_STEP_M) drivenTotalM.doubleValue += d
        drivenLast = done
    }
    private const val MAX_STEP_M = 3000.0
    /** No "Neue Route" this long after a route set on purpose (System I-091). */
    private const val QUIET_AFTER_GO_MS = 60_000L
    private const val NEW_ROUTE_SAY_MS = 60_000L
    /**
     * Navi L-029 step F: the charging plan kept up to date on the way, made when the guidance starts; [rollingRoute] the
     * route its track was made of, [rollingDriven] how far along it the car was at the last check. [heights] the heights
     * of the planned routes, for the tracks of the routes the trip gets on the way.
     */
    @Volatile var rolling: ChargeRolling? = null
    @Volatile var rollingRoute: uniffi.ferrostar.Route? = null
    @Volatile var rollingDriven = 0.0
    @Volatile var heights: ChargeHeights? = null
    @Volatile var weather: ChargeEnergy.Weather? = null
    /** Navi L-056: the speeds recommended per stretch of the trip being guided */
    val speedLegs = mutableStateOf<ChargeSpeed.Legs?>(null)
    /** Navi L-056 step 2: the charging stop the car drove away from (not a card closed by a tap) - the speeds ahead anew */
    val leftCharger = mutableStateOf<ChargePlanner.Stop?>(null)
    @Volatile var leftWeighed: ChargePlanner.Stop? = null
    /** Navi L-056: one step slower (110, then 100) while the next stop is in doubt - and the stop it was for */
    val speedStep = mutableStateOf<Pair<Position?, Int>?>(null)

    /** A planned charging stop just reached (as ABRP: what to charge to and how long); gone once the car drives on. */
    val atCharger = mutableStateOf<ChargePlanner.Stop?>(null)
    /** What the last look at the traffic found, by kind, as logged (only a change is logged). */
    @Volatile private var lastTrafficSaid = ""

    /** F2 step 2: the incidents on the guided route ahead (TomTom), renewed every [TRAFFIC_MS] while guiding. */
    val trafficAhead = mutableStateOf<List<NaviTraffic.Ahead>>(emptyList())
    /** The flow's slow stretches on the guided route, in metres along it (0 slow, 1 jam, 2 standing). */
    val flowAhead = mutableStateOf<List<Triple<Double, Double, Int>>>(emptyList())

    /** Metres driven on the guided route now (0 without one) - for test hooks. */
    fun drivenM(): Double {
        val session = made?.first ?: return 0.0
        val r = session.route.value ?: return 0.0
        val t = (session.state.value.tripState as? uniffi.ferrostar.TripState.Navigating) ?: return 0.0
        return r.distance - t.progress.distanceRemaining
    }

    /** Where along [r] (metres from its start) it takes a ramp - off or on - or a fork. */
    fun rampsAt(r: uniffi.ferrostar.Route): List<Double> {
        val out = ArrayList<Double>(); var m = 0.0
        for (st in r.steps) {
            val t = st.visualInstructions.firstOrNull()?.primaryContent?.maneuverType
            if (t == uniffi.ferrostar.ManeuverType.OFF_RAMP || t == uniffi.ferrostar.ManeuverType.ON_RAMP || t == uniffi.ferrostar.ManeuverType.FORK) out += m + st.distance
            m += st.distance
        }
        return out
    }

    /** What the incidents ahead of [drivenM] cost, in seconds (those TomTom gives a delay for) - for the arrival. */
    fun trafficDelayS(drivenM: Double): Double = trafficAhead.value.filter { it.alongM > drivenM && NaviTraffic.onOurWay(it) }.sumOf { (it.incident.delayS ?: 0).toDouble() } +
        (NaviDebug.fakeDelayS ?: 0)

    /** F2: a faster route found while guiding (the route, minutes saved), until taken or left. */
    val faster = mutableStateOf<Pair<uniffi.ferrostar.Route, Int>?>(null)
    private var fasterCheckedAt = 0L

    /**
     * F2 (O218: "er soll auch bei Staus prüfen was schneller ist"): with [MIN_DELAY_S] or more of delay ahead, the routes
     * from here to the same waypoints are planned and each gets its own incidents' delay (the next 50 km); one at least
     * [MIN_SAVING_S] faster is offered - said and shown with "Wechseln", as Google does. Checked at most every
     * [FASTER_MS], and only with a jam ahead (the day's TomTom budget).
     */
    private suspend fun checkFaster(app: Context, session: NaviSession, speaker: NaviSpeaker, t: TripState.Navigating, r: uniffi.ferrostar.Route, driven: Double) {
        val nowMs = android.os.SystemClock.uptimeMillis()
        if (faster.value != null || nowMs - fasterCheckedAt < FASTER_MS) return
        val delay = trafficDelayS(driven)
        if (delay < MIN_DELAY_S) return
        fasterCheckedAt = nowMs
        val here = Position(longitude = t.userLocation.coordinates.lng, latitude = t.userLocation.coordinates.lat)
        val waypoints = t.remainingWaypoints.map { Position(longitude = it.coordinate.lng, latitude = it.coordinate.lat) }
        if (waypoints.isEmpty()) return
        val current = t.progress.durationRemaining + delay
        android.util.Log.i("MateNavi", "faster route: asking from %.5f,%.5f to %s".format(java.util.Locale.ROOT, here.latitude, here.longitude,
            waypoints.joinToString(";") { "%.5f,%.5f".format(java.util.Locale.ROOT, it.latitude, it.longitude) }))
        val alts = runCatching { session.routesFrom(here, waypoints, chargeStops.value.map { it.charger.at }) }.onFailure { android.util.Log.w("MateNavi", "faster route: $it") }.getOrNull().orEmpty()
        var best: Pair<uniffi.ferrostar.Route, Double>? = null
        for (a in alts) {
            val line = a.geometry.map { Position(longitude = it.lng, latitude = it.lat) }
            val near = ArrayList<Position>(); var m = 0.0
            for (k in line.indices) { if (k > 0) m += ChargerIndex.distanceM(line[k - 1], line[k]); near += line[k]; if (m > TRAFFIC_AHEAD_M) break }
            val own = if (TomTomBudget.exhausted(app)) 0.0 else runCatching { NaviTraffic.incidentsAlong(app, near) }.getOrNull().orEmpty()
                .sumOf { (it.incident.delayS ?: 0).toDouble() }
            val total = a.steps.sumOf { it.duration } + own
            if (best == null || total < best.second) best = a to total
        }
        val b = best ?: return
        val saved = current - b.second
        android.util.Log.i("MateNavi", "faster route: now %.0f min with %.0f min delay, best other %.0f min - saves %.0f min".format(current / 60, delay / 60, b.second / 60, saved / 60))
        if (saved >= MIN_SAVING_S) {
            aroundClosure.value = false
            faster.value = b.first to Math.round(saved / 60).toInt()
            speaker.warn(app.getString(net.prototux.aare.R.string.traffic_faster_say, Math.round(saved / 60).toInt()))
        }
    }

    /** The offer in [faster] is a way round a closed road (not a faster one). */
    val aroundClosure = mutableStateOf(false)
    /** The closures looked at on this trip: when a way round each was last looked for ([Long.MAX_VALUE]: offered). */
    private val closuresHandled = HashMap<String, Long>()

    /**
     * 17.11.2026 (drive): "Er hat eine Straßensperrung erkannt, 3 Kilometer vorher ... aber er leitet mich da trotzdem
     * durch ... Er sagt Straßensperrung, hau durch da." Closures were only kept away from when planning; one that shows up
     * on the way was only said. Now a closure on the route within [CLOSURE_AHEAD_M] is kept from as well: the routes from
     * here are planned without it and one free of it is offered at once ("Route um die Sperrung", "Wechseln"), said too.
     * Each closure offered once; one without a free route is looked at again after [CLOSURE_RETRY_MS].
     */
    private suspend fun checkClosure(app: Context, session: NaviSession, speaker: NaviSpeaker, t: TripState.Navigating, r: uniffi.ferrostar.Route, driven: Double) {
        if (faster.value != null) return
        val c = trafficAhead.value.firstOrNull { it.incident.kind == 8 && it.along && it.alongM > driven && it.alongM - driven < CLOSURE_AHEAD_M } ?: return
        val key = "%.4f,%.4f".format(java.util.Locale.ROOT, c.incident.start.latitude, c.incident.start.longitude)
        val nowMs = android.os.SystemClock.uptimeMillis()
        val tried = closuresHandled[key]
        if (tried != null && (tried == Long.MAX_VALUE || nowMs - tried < CLOSURE_RETRY_MS)) return
        closuresHandled[key] = nowMs
        // the closed stretch as the route runs over it
        val all = r.geometry.map { Position(longitude = it.lng, latitude = it.lat) }
        val closed = ArrayList<Position>(); var m = 0.0
        for (k in all.indices) { if (k > 0) m += ChargerIndex.distanceM(all[k - 1], all[k]); if (m >= c.alongM - 20 && m <= maxOf(c.endM, c.alongM + 50) + 20) closed += all[k] }
        if (closed.size < 2) return
        val here = Position(longitude = t.userLocation.coordinates.lng, latitude = t.userLocation.coordinates.lat)
        val waypoints = t.remainingWaypoints.map { Position(longitude = it.coordinate.lng, latitude = it.coordinate.lat) }
        if (waypoints.isEmpty()) return
        session.closures = (session.closures + NaviTraffic.excludePoints(listOf(closed))).distinct()
        val alts = runCatching { session.routesFrom(here, waypoints, chargeStops.value.map { it.charger.at }) }.onFailure { android.util.Log.w("MateNavi", "around the closure: $it") }.getOrNull().orEmpty()
        val free = alts.firstOrNull { a -> NaviTraffic.onClosures(a.geometry.map { Position(longitude = it.lng, latitude = it.lat) }, listOf(closed)) == 0.0 }
        // into the kept log (24.11.2026: whether a way round a closure was offered could not be seen)
        net.prototux.aare.DiagLog.add("closure %.0f m ahead: %d routes from here, %s".format(java.util.Locale.ROOT, c.alongM - driven, alts.size,
            if (free != null) "one free of it - offered" else "none free of it - looked at again in 2 min"), area = "NAVI")
        if (free == null) return
        closuresHandled[key] = Long.MAX_VALUE
        val more = free.steps.sumOf { it.duration } - t.progress.durationRemaining
        aroundClosure.value = true
        faster.value = free to Math.round(more / 60).toInt()
        speaker.warn(app.getString(net.prototux.aare.R.string.traffic_closure_route_say))
    }
    private const val CLOSURE_AHEAD_M = 20_000.0
    private const val CLOSURE_RETRY_MS = 2 * 60_000L

    /**
     * The next turn for the bar behind another area: the manoeuvre's type, a roundabout's exit angle (0: none; 19.11.2026:
     * "im Bedienfeld ... nur ein verkrüppeltes Kreisverkehrssymbol ..., dass ich in den Kreisverkehr reinfahren soll, aber
     * nicht welche Ausfahrt" - the bar got the type alone), the distance.
     */
    class NextTurn(val type: Int, val angle: Int, val distance: androidx.car.app.model.Distance)
    val nextTurn = mutableStateOf<NextTurn?>(null)

    /** Jams of [placed] (delay of a minute or more, along the route) into the session's known jams, newest kept. */
    private fun keepJams(session: NaviSession, all: List<Position>, placed: List<NaviTraffic.Ahead>) {
        val now = android.os.SystemClock.elapsedRealtime()
        val fresh = placed.filter { it.along && (it.incident.delayS ?: 0) >= JAM_MIN_S }.map { a ->
            NaviTraffic.KnownJam(a.incident.start, pointAlong(all, a.alongM + 60.0), a.incident.delayS ?: 0, now)
        }
        if (fresh.isEmpty()) return
        val key = { j: NaviTraffic.KnownJam -> "%.4f,%.4f".format(java.util.Locale.ROOT, j.start.latitude, j.start.longitude) }
        session.knownJams = (fresh + session.knownJams).distinctBy(key).take(JAM_MAX)
        android.util.Log.i("MateNavi", "known jams: ${session.knownJams.size} (${fresh.size} from this look)")
    }

    /** The point [m] metres along [line] (its end beyond). */
    private fun pointAlong(line: List<Position>, m: Double): Position {
        var acc = 0.0
        for (k in 1 until line.size) {
            val d = ChargerIndex.distanceM(line[k - 1], line[k])
            if (acc + d >= m) { val f = if (d > 0) (m - acc) / d else 0.0
                return Position(longitude = line[k - 1].longitude + f * (line[k].longitude - line[k - 1].longitude),
                    latitude = line[k - 1].latitude + f * (line[k].latitude - line[k - 1].latitude)) }
            acc += d
        }
        return line.last()
    }
    private const val JAM_MIN_S = 60
    private const val JAM_MAX = 40

    private var watching: Job? = null
    private var rerouteWatch: Job? = null
    private var preparedFor: List<String> = emptyList()
    private var warnWatch: Job? = null

    /**
     * Once a second while guiding, whatever is on the screen: the optional tone over the speed limit (O194) - it was only
     * heard with the map in front. Read from the settings at each new route. (The speed camera warning was taken out again
     * on 20.11.2026, before the repository went public.)
     */
    private suspend fun warnings(app: Context, session: NaviSession, speaker: NaviSpeaker) {
        var forRoute: uniffi.ferrostar.Route? = null
        var trafficAt = 0L
        var trafficFullAt = 0L
        val saidTraffic = HashSet<String>()
        var alert: NaviSpeedAlert? = null
        var tone = NaviSpeedAlert.tone(""); var volume = 1f
        var standing = 0
        while (true) {
            kotlinx.coroutines.delay(1000)
            val t = session.state.value.tripState as? TripState.Navigating
            val r = session.route.value
            if (!guiding.value || t == null || r == null) {
                forRoute = null; stillNearEnd = null; trafficAhead.value = emptyList(); flowAhead.value = emptyList(); saidTraffic.clear(); faster.value = null; aroundClosure.value = false
                if (!guiding.value) { session.knownJams = emptyList(); closuresHandled.clear() }
                continue
            }
            if (r !== forRoute) {
                forRoute = r; trafficAt = 0L; trafficFullAt = 0L; faster.value = null; aroundClosure.value = false; fasterCheckedAt = 0L; flowAhead.value = emptyList()
                val st = net.prototux.aare.Settings(app)
                alert = if (st.speedWarn) NaviSpeedAlert(st.speedWarnOver, st.speedWarnTimes) else null
                tone = NaviSpeedAlert.tone(st.speedWarnTone); volume = st.speedWarnVolume / 100f
            }
            val driven = r.distance - t.progress.distanceRemaining
            val kmh = speedMs(t, session)?.let { it * 3.6 }
            // 18.11.2026 ("nur noch oben links die Anzeige 10 Meter Ziel erreicht, aber ich habe weder das Pop-up gesehen
            // und die Route wurde nicht beendet ... es wäre schon hilfreich, wenn ich im Umkreis zum Stehen komme und er
            // dann sagt, okay, ich habe mein Ziel erreicht"): standing for [ARRIVE_STAND_S] seconds near the route's end
            // is the arrival - a parking place is rarely the route's last metre
            val end = r.geometry.lastOrNull()
            val here = t.userLocation.coordinates
            val crow = end?.let { ChargerIndex.distanceM(Position(longitude = here.lng, latitude = here.lat), Position(longitude = it.lng, latitude = it.lat)) }
            // the car in P, said within the last half minute
            val parked = net.prototux.aare.car.CarData.state.value.gear?.let { it.value == 101 && System.currentTimeMillis() - it.atMillis < 30_000 } == true
            // a planned charging stop or an own stop close by: P there is a break, not the arrival
            val hereP = Position(longitude = here.lng, latitude = here.lat)
            val atStop = atCharger.value != null || chargeStops.value.any { ChargerIndex.distanceM(it.charger.at, hereP) < STOP_NEAR_M } ||
                ownStops.value.any { ChargerIndex.distanceM(it.first, hereP) < STOP_NEAR_M }
            // for a car gone right now (carGone): standing or in P near the end counts as arrived
            stillNearEnd = if (!atStop && kmh != null && (kmh < 3.0 || parked) && t.progress.distanceRemaining <= ARRIVE_P_WAY_M)
                System.currentTimeMillis() to "%.0f m of the way left%s".format(t.progress.distanceRemaining, if (parked) ", in P" else "") else null
            val need = standNeededS(kmh, t.progress.distanceRemaining, crow, parked && !atStop)
            if (need != null) standing++ else standing = 0
            if (need != null && standing >= need) {
                standing = 0
                net.prototux.aare.DiagLog.add("arrived: the car stands %.0f m from the route's end (%.0f m of the way left)%s - the trip ends".format(crow ?: 0.0, t.progress.distanceRemaining, if (parked) ", in P" else ""))
                session.arriveNow()
                continue
            }
            // F2 step 2: the incidents ahead, asked again every few minutes (with the traffic on and a map server)
            val nowMs = android.os.SystemClock.uptimeMillis()
            val trafficOn = net.prototux.aare.Settings(app).trafficShown && !TomTomBudget.exhausted(app)
            if (!trafficOn) {
                // traffic off or the day's budget spent: what was found before is not kept on (said, in the arrival, on the line)
                trafficAt = 0L; trafficFullAt = 0L
                if (trafficAhead.value.isNotEmpty()) trafficAhead.value = emptyList()
                if (flowAhead.value.isNotEmpty()) flowAhead.value = emptyList()
            } else if (trafficAt == 0L || nowMs - trafficAt > TRAFFIC_MS) {
                trafficAt = nowMs
                // the next [TRAFFIC_AHEAD_M] every few minutes, the whole rest of the trip every [TRAFFIC_FULL_MS] (17.11.2026:
                // "Reichen 50 km aus um noch eine Alternativroute zu finden?" - a jam 80 km on can already call for another
                // motorway; the day's budget is spent sparingly all the same)
                val full = trafficFullAt == 0L || nowMs - trafficFullAt > TRAFFIC_FULL_MS
                if (full) trafficFullAt = nowMs
                val all = r.geometry.map { Position(longitude = it.lng, latitude = it.lat) }
                var m = 0.0; var start = 0
                for (k in 1 until all.size) { m += ChargerIndex.distanceM(all[k - 1], all[k]); if (m <= driven) start = k }
                val line = ArrayList<Position>(); var ahead = 0.0
                for (k in start until all.size) { if (k > start) ahead += ChargerIndex.distanceM(all[k - 1], all[k]); line += all[k]; if (!full && ahead > TRAFFIC_AHEAD_M) break }
                val nearEnd = driven + TRAFFIC_AHEAD_M
                scope.launch {
                    val found = runCatching { NaviTraffic.incidentsAlong(app, line) }.onFailure { android.util.Log.w("MateNavi", "incidents: $it") }.getOrNull()
                    if (found != null && session.route.value === r) {
                        // placed along the part asked: shifted to the whole route's measure
                        // what is about a ramp the route drives past is no matter for it (NaviTraffic.besideRamps)
                        val placed = NaviTraffic.besideRamps(found.map { it.shifted(startM(all, start)) }, rampsAt(r))
                        // a near look keeps what the last whole look found further on
                        trafficAhead.value = if (full) placed else (placed + trafficAhead.value.filter { it.alongM > nearEnd }).sortedBy { it.alongM }
                        // the jams with their direction, kept for the trip: a new route after leaving this one is chosen
                        // round them (I-080) - also once the car has left this route and its incidents are asked no more
                        keepJams(session, all, placed)
                        android.util.Log.i("MateNavi", "incidents on the route: ${found.size}, delay ahead %.0f s".format(trafficDelayS(driven)))
                        // Navi I-070: what TomTom gave, by kind, into the diagnosis log when it changes (roadworks missing on the
                        // strip could not be told from the log: only closures were in it, once each)
                        val kinds = placed.groupingBy { it.incident.kind }.eachCount().toSortedMap().entries.joinToString { (k, n) -> "${NaviTraffic.word(app, k)} $n" }
                        val said = "%s%s".format(if (full) "whole route" else "next km", if (kinds.isEmpty()) ": none" else ": $kinds")
                        if (said != lastTrafficSaid) { lastTrafficSaid = said; net.prototux.aare.DiagLog.add("traffic on the route ($said)", area = "NAVI") }
                    }
                    // the flow on the next 30 km: the route coloured as the roads (17.11.2026)
                    val flow = runCatching { NaviTraffic.flowAlong(app, line) }.onFailure { android.util.Log.w("MateNavi", "flow: $it") }.getOrNull()
                    if (flow != null && session.route.value === r) { val off = startM(all, start); flowAhead.value = flow.map { Triple(it.first + off, it.second + off, it.third) } }
                }
            }
            // F2: a faster route round a jam
            checkFaster(app, session, speaker, t, r, driven)
            // a road closed on the way, found while driving: a way round it
            checkClosure(app, session, speaker, t, r, driven)
            // the next one within [TRAFFIC_SAY_M] said once, as Google: "Stau in 3 Kilometern, etwa 6 Minuten Verzögerung"
            trafficAhead.value.firstOrNull { it.alongM > driven && it.alongM - driven < TRAFFIC_SAY_M && NaviTraffic.onOurWay(it) }?.let { a ->
                val key = "%.4f,%.4f".format(java.util.Locale.ROOT, a.incident.start.latitude, a.incident.start.longitude)
                if (saidTraffic.add(key)) {
                    // said as the sign shows it: a lane narrowed for works is "Baustelle" (I-070)
                    val text = NaviTraffic.spoken(app, a.incident, a.alongM - driven, NaviTraffic.shownKind(a))
                    speaker.warn(text)
                    android.util.Log.i("MateNavi", "incident warning: $text")
                }
            }
            alert?.let { a ->
                // Navi I-069 ("Auf der Autobahn sollte er, wenn er nicht weiß, dass es unbegrenzt ist, einfach ruhig sein"): only
                // the limits of the route driven on, and not while off it - else no limit is known and nothing is said
                val l = limits.value?.takeIf { it.of(r) && !session.offRoute.value }?.along(driven)
                if (a.update(kmh, l) && speaker.mode != NaviSpeaker.Mode.MUTE) {
                    NaviSpeedAlert.play(tone, volume, a.times)
                    net.prototux.aare.DiagLog.add("speed warning: %.0f km/h where the map says %d, %.1f km along the route".format(java.util.Locale.ROOT,
                        kmh ?: 0.0, l ?: 0, driven / 1000), area = "NAVI")
                }
            }
        }
    }

    /** The speed for the warnings: the test value, the simulation's own (it runs [SIM_WARP] times as fast), the car's. */
    private fun speedMs(t: TripState.Navigating, session: NaviSession): Double? {
        NaviDebug.speedKmh?.let { return it }
        if (session.simulating) return t.userLocation.speed?.value?.let { it / SIM_WARP }
        val car = net.prototux.aare.car.CarData.state.value.speed?.takeIf { System.currentTimeMillis() - it.atMillis < 3000 }?.value?.metersPerSecond?.toDouble()
        return car ?: t.userLocation.speed?.value
    }
    private const val SIM_WARP = 3

    /**
     * How long from an announcement being due to its sound in the car: the position comes once a second (on average half
     * a second old when the distance is passed), the music is lowered first (MediaFade.DOWN_MS), the player starts and the
     * sound goes over USB to the car and its buffer. The announcements are computed ahead, so no computing time.
     */
    internal const val LEAD_MS = 1_300L

    /** The speed the car covers ground at in real time (a test drive runs [SIM_WARP] times as fast, its time is real). */
    private fun leadSpeedMs(t: TripState.Navigating, session: NaviSession): Double? =
        if (session.simulating) t.userLocation.speed?.value else speedMs(t, session)

    /**
     * The current step's announcement due now when it is said [LEAD_MS] early: of those whose distance before the turn is
     * reached within the way the car covers meanwhile, the nearest to the turn. Null without a step (Ferrostar's own then).
     */
    internal fun dueInstruction(t: TripState.Navigating, speedMs: Double?): uniffi.ferrostar.SpokenInstruction? {
        val step = t.remainingSteps.firstOrNull() ?: return null
        val i = dueIndex(t.progress.distanceToNextManeuver, speedMs, step.spokenInstructions.map { it.triggerDistanceBeforeManeuver }) ?: return null
        return step.spokenInstructions[i]
    }

    /** Of the announcements' [triggers] (metres before the turn), the one due [leftM] before it at [speedMs]; null for none. */
    internal fun dueIndex(leftM: Double, speedMs: Double?, triggers: List<Double>): Int? {
        val early = (speedMs ?: 0.0).coerceIn(0.0, 70.0) * LEAD_MS / 1000.0
        return triggers.indices.filter { leftM - early <= triggers[it] }.minByOrNull { triggers[it] }
    }
    /**
     * How long standing (under 3 km/h) near the route's end has to last to be the arrival; null: it does not count.
     * Navi I-064 (24.11.2026: "die Navigation schon abgebrochen hat, bevor ich überhaupt in der Nähe des Ziels war … er sollte
     * schon ein bisschen darauf achten, dass er nicht im Umkreis von 300 Meter die Navigation dann beendet"): 5 s standing
     * 106 m before the end - a red light or the closed road ahead - had ended the trip. Now: in P ([parked]) on a car park
     * near it ([ARRIVE_CROW_M]) at once; without P only right at the end ([ARRIVE_NEAR_M]), there after [ARRIVE_STAND_S].
     */
    internal const val ARRIVE_CROW_M = 120.0
    internal const val ARRIVE_WAY_M = 300.0
    internal const val ARRIVE_NEAR_M = 40.0
    internal const val ARRIVE_STAND_S = 5
    internal const val ARRIVE_P_WAY_M = 2000.0
    internal const val ARRIVE_P_S = 3
    private const val STOP_NEAR_M = 300.0
    /**
     * 25.11.2026 ("wenn das Fahrzeug sich allgemein länger als 3 Sekunden im Parkmodus befindet. Auch wenn das Ziel dann
     * vielleicht 300 Meter entfernt ist, weil es in der Fußgängerzone ist"): P held [ARRIVE_P_S] seconds within the last
     * [ARRIVE_P_WAY_M] of the way is the arrival too - not at a charging or own stop (the caller passes [parked] false there).
     */
    internal fun standNeededS(kmh: Double?, wayLeftM: Double, crowM: Double?, parked: Boolean): Int? = when {
        kmh == null || kmh >= 3.0 || crowM == null -> null
        parked && crowM <= ARRIVE_CROW_M && wayLeftM <= ARRIVE_WAY_M -> 2
        parked && wayLeftM <= ARRIVE_P_WAY_M -> ARRIVE_P_S
        crowM <= ARRIVE_NEAR_M && wayLeftM <= ARRIVE_NEAR_M * 2 -> ARRIVE_STAND_S
        else -> null
    }
    // every 10 min (17.11.2026: "reicht auch, wenn wir alle 10 TomTom fragen") - about 40-50 TomTom calls an hour of driving
    private const val TRAFFIC_MS = 600_000L
    private const val FASTER_MS = 300_000L
    private const val MIN_DELAY_S = 300.0
    private const val MIN_SAVING_S = 300.0
    private const val TRAFFIC_AHEAD_M = 50_000.0
    private const val TRAFFIC_FULL_MS = 900_000L
    private fun startM(all: List<Position>, start: Int): Double { var m = 0.0; for (k in 1..start) m += ChargerIndex.distanceM(all[k - 1], all[k]); return m }
    private const val TRAFFIC_SAY_M = 3_000.0

    /**
     * The planned charging stops still to charge at: those ahead among the waypoints and the one the car stands at
     * (16.11.2026: once its waypoint was passed the tile said "0 % am Ziel" and the arrival lost its 24 min).
     */
    fun stillToCharge(t: TripState.Navigating): List<ChargePlanner.Stop> {
        val ahead = chargeStops.value.filter { s -> t.remainingWaypoints.any { w ->
            ChargerIndex.distanceM(Position(longitude = w.coordinate.lng, latitude = w.coordinate.lat), s.charger.at) < 150 } }
        return (listOfNotNull(atCharger.value) + ahead).distinct()
    }
    private const val LEAVE_M = 300.0
    private const val THERE_M = 60.0
    private const val NEAR_M = 150.0
    /** The car the trip was made with: another one starts afresh (a test drive in the desk window, then the real car). */
    var car: String? = null

    /**
     * Navi I-001 ("Unter Navigieren werden keine letzten Ziele angezeigt"): the destination goes into "Letzte Ziele" on
     * arrival whatever is on the screen - before, only when Mate Navi was in front at that moment - and when the driver
     * ends the guidance close to it ([nearEnd]). Not for test drives.
     */
    fun notePin(app: Context, nearEnd: Boolean = true) {
        if (!nearEnd || testDrive.value == true) return
        val at = destination.value ?: return
        val onMap = app.getString(net.prototux.aare.R.string.places_on_map)
        NaviPins.add(app, at, target.value?.name?.takeIf { it != onMap && !it.startsWith("mate-test") } ?: "")
    }

    /** The saved trip that went on by itself after a stop (its time of saving): once per saved trip. */
    @Volatile var autoResumed = 0L
    /** The driver took charging stops out of this trip (trip plan): the plan on the way adds or moves none. */
    @Volatile var noNewStops = false
    /** A trip going on after a stop is being started: its way driven and its time count on. */
    @Volatile var continuing = false

    /**
     * A break with the car off (22.11.2026: "Nach einer kurzen Pause auf der Raststätte, also keine 5 Minuten, hat der
     * Mate seine Navigation nicht weitergeführt, sondern musste neu berechnen … bis zu 10 Minuten nicht unbedingt
     * abgebrochen … sondern weiter im Hintergrund wartet"): the trip waits silently this long for the car to come back.
     */
    private const val AWAY_MS = 10 * 60_000L
    /** The car is gone and the trip waits for it ([carGone]); the voice is silent meanwhile. */
    @Volatile var away = false
        private set
    private var awayJob: kotlinx.coroutines.Job? = null
    /** When the guide loop last saw the car standing (or in P) near the end, and how; null while driving or far away. */
    @Volatile private var stillNearEnd: Pair<Long, String>? = null

    /**
     * The car disconnected. A trip being guided waits up to [AWAY_MS], silent, with its route and progress - the car back
     * in time ([carBack]), it goes on without asking Valhalla anew. Otherwise, or without a trip, the guidance stops (the
     * trip stays saved for "Fortsetzen") and the screen starts empty.
     */
    fun carGone() {
        // 25.11.2026 ("wenn die Verbindung von Android Auto abbricht, dann habe ich das Ziel ja scheinbar auch erreicht"):
        // the car stood or was in P near the end a moment ago - the trip is arrived; a drop while driving still waits
        val still = stillNearEnd?.takeIf { System.currentTimeMillis() - it.first < 5_000 }
        val m = made
        if (still != null && guiding.value && testDrive.value != true && m != null) {
            net.prototux.aare.DiagLog.add("arrived: the car went away standing (${still.second}) - the trip ends")
            m.first.arriveNow()
            awayJob?.cancel()
            awayJob = scope.launch { kotlinx.coroutines.delay(2000); stopNow() }
            return
        }
        if (guiding.value && testDrive.value != true && made != null) {
            away = true
            made?.third?.let { it.stop(); it.muted = true }
            awayJob?.cancel()
            awayJob = scope.launch {
                kotlinx.coroutines.delay(AWAY_MS)
                if (away) { android.util.Log.i("MateNavi", "car gone for 10 min: guidance stopped"); away = false; stopNow() }
            }
            android.util.Log.i("MateNavi", "car gone: the trip waits up to 10 min")
            net.prototux.aare.DiagLog.add("navi: car gone - the trip waits up to 10 min")
            return
        }
        stopNow()
    }

    /** The car connected again: a trip waiting since [carGone] goes on (its voice back). */
    fun carBack() {
        if (!away) return
        away = false
        awayJob?.cancel(); awayJob = null
        made?.third?.muted = false
        android.util.Log.i("MateNavi", "car back: the trip goes on")
        net.prototux.aare.DiagLog.add("navi: car back - the trip goes on without a new route")
    }

    private fun stopNow() {
        awayJob?.cancel(); awayJob = null; away = false; stillNearEnd = null
        made?.third?.muted = false
        made?.let { (session, _, speaker) -> session.stop(); speaker.stop() }
        guiding.value = false; routeJson.value = null; drawnLine.value = emptyList(); limits.value = null
        target.value = null; chargeStops.value = emptyList(); ownStops.value = emptyList(); testDrive.value = null; rolling = null
        faster.value = null; aroundClosure.value = false; flowAhead.value = emptyList(); closuresHandled.clear()
        android.util.Log.i("MateNavi", "car gone: guidance stopped")
    }

    /**
     * Spoken instructions and the turns for the car, from the session's state whatever is on the screen; nothing once the
     * guidance ends.
     */
    private fun watch(app: Context, session: NaviSession, speaker: NaviSpeaker) {
        watching?.cancel()
        // a new route after leaving the planned one is a warning (O193), said whatever is on the screen
        rerouteWatch?.cancel()
        rerouteWatch = scope.launch {
            var seen = session.reroutes
            var saidAt = 0L
            session.route.collect {
                // said once a minute at most: a new route every few seconds piled the words up, and the voice said them a
                // minute late (19.11.2026: "Thorsten immer noch eine Minute später neue Route gesagt")
                val now = android.os.SystemClock.elapsedRealtime()
                // System I-091 (22.11.2026: "dass er beim Starten in der Route und beim Neuberechnen in solchen Fällen, wo es
                // vorgesehen ist, nicht dann jedes Mal sagt, dass er neu berechnet"): only a new route after leaving the
                // planned one - a route put in on purpose resets the count (it was said then too), and the first minute
                // after a start or a planned change the car is often still beside the route (the car park, the charger)
                val more = session.reroutes > seen
                if (more && guiding.value && now - saidAt > NEW_ROUTE_SAY_MS && now - session.goneAt > QUIET_AFTER_GO_MS) {
                    saidAt = now
                    speaker.warn(app.getString(net.prototux.aare.R.string.mate_navi_warn_new_route))
                }
                seen = session.reroutes
            }
        }
        warnWatch?.cancel()
        warnWatch = scope.launch { warnings(app, session, speaker) }
        watching = scope.launch {
            var lastHeadUp: Pair<Int, Long>? = null
            var lastHeadUpMs = 0L
            // the announcements said, by their id: each once, and an older one never after a newer
            val said = LinkedHashSet<java.util.UUID>()
            var sentNothing = true
            var lastWaypoints: List<uniffi.ferrostar.Waypoint> = emptyList()
            var approaching: ChargePlanner.Stop? = null
            var closest = Double.MAX_VALUE
            session.state.collect { st ->
                // arrived, shown or not: nothing left to go on with (it was offered again after an arrival behind the
                // music and the car switched off)
                if (guiding.value && st.tripState is TripState.Complete) { NaviResume.clear(app); notePin(app); etaArrived() }
                val t = if (guiding.value) st.tripState as? TripState.Navigating else null
                if (t != null) session.route.value?.let { r ->
                    noteProgress(r, r.distance, t.progress.distanceRemaining)
                    runCatching { noteEta(t, t.progress.durationRemaining, trafficDelayS(r.distance - t.progress.distanceRemaining),
                        stillToCharge(t).sumOf { it.stayMinutes } * 60) }
                }
                // a waypoint passed: a planned charging stop reached is said and shown; driven 300 m on, it goes
                if (t != null) {
                    val now = t.remainingWaypoints
                    // Ferrostar passes a waypoint 100 m before it: the stop is noted then, said and shown once the car is there
                    if (now.size < lastWaypoints.size) lastWaypoints.firstOrNull()?.let { w ->
                        val at = Position(longitude = w.coordinate.lng, latitude = w.coordinate.lat)
                        approaching = chargeStops.value.firstOrNull { ChargerIndex.distanceM(it.charger.at, at) < 150 }; closest = Double.MAX_VALUE
                    }
                    val here = Position(longitude = t.userLocation.coordinates.lng, latitude = t.userLocation.coordinates.lat)
                    approaching?.let { stop ->
                        val d = ChargerIndex.distanceM(stop.charger.at, here)
                        // there: close by, or past the closest point of a charger a little off the road
                        val there = d <= THERE_M || (closest < NEAR_M && d > closest + 15)
                        closest = minOf(closest, d)
                        if (there) {
                            approaching = null
                            atCharger.value = stop
                            visited += stop.charger
                            // the charger planned, put first when a charge is noticed here (not in test drives); no visit yet -
                            // the car may drive on without charging (System I-143)
                            if (testDrive.value != true) ChargeAsk.plannedReached(app, stop.charger)
                            speaker.warn(app.getString(net.prototux.aare.R.string.mate_navi_at_charger_say, stop.leavePercent.toInt(), stop.chargeMinutes.toInt().coerceAtLeast(1)))
                            android.util.Log.i("MateNavi", "charging stop reached: %.0f -> %.0f %%, %.0f min".format(stop.arrivePercent, stop.leavePercent, stop.chargeMinutes))
                        } else if (d > LEAVE_M) approaching = null   // driven past
                    }
                    lastWaypoints = now
                    atCharger.value?.let { s ->
                        if (ChargerIndex.distanceM(s.charger.at, here) > LEAVE_M) { atCharger.value = null; leftCharger.value = s; android.util.Log.i("MateNavi", "charging stop left") }
                    }
                } else { lastWaypoints = emptyList(); atCharger.value = null; approaching = null }
                // only when what the bar shows changes, not with every position
                val turn = t?.let { tt -> NaviManeuver.routingInfo(tt, withNext = false)?.currentStep?.maneuver?.let { m ->
                    NextTurn(m.type, m.roundaboutExitAngle, NaviManeuver.distance(tt.progress.distanceToNextManeuver)) } }
                val was = nextTurn.value
                if (turn?.type != was?.type || turn?.angle != was?.angle || turn?.distance?.displayDistance != was?.distance?.displayDistance ||
                    turn?.distance?.displayUnit != was?.distance?.displayUnit) nextTurn.value = turn
                if (t == null) {
                    if (!sentNothing) { NaviHeadUp.sink?.invoke(null); sentNothing = true; lastHeadUp = null }
                    return@collect
                }
                // O230: the next steps' announcements computed ahead in Kokoro (when that voice is chosen)
                // The next announcements in order, not the whole rest of the route (18.11.2026: "Die App läuft aktuell
                // ziemlich unperformant"): a long trip has a few hundred, more than the voice keeps (NaviKokoro.KEEP) -
                // computing them all threw the nearest ones away again and began anew with every step, so the voice
                // computed without end on two cores beside the map. The next ones are ready long before they are due.
                val ahead = t.remainingSteps.asSequence().flatMap { st -> st.spokenInstructions.asSequence().map { it.text } }.take(NaviKokoro.AHEAD).toList()
                if (ahead != preparedFor) { preparedFor = ahead; speaker.prepare(ahead) }
                // F4: each instruction once at its distance - early by the way the car goes while Mate's chain takes to
                // the car's speakers (21.11.2026: "vor berechnen und zum richtigen Zeitpunkt ausgeben"), so it is heard at
                // its distance, not one or two seconds (20 to 55 m) after it
                val due = dueInstruction(t, leadSpeedMs(t, session)) ?: t.spokenInstruction
                due?.let { if (said.add(it.utteranceId)) { if (said.size > 500) said.remove(said.first()); speaker.say(it.utteranceId, it.text, t.remainingSteps.size, it.triggerDistanceBeforeManeuver) } }
                // step 8: once per 10 m and each new step
                // at most once a second (as Android Auto's own rate), a new step at once - every 10 m was three times a
                // second on the main thread in the faster test drive
                val key = t.remainingSteps.size to (t.progress.distanceToNextManeuver / 10).toLong()
                val nowMs = android.os.SystemClock.uptimeMillis()
                // standing still, the same again every 2 s: a new turn the car's displays hold back for a moment (NavStatusBridge,
                // against flicker) still gets there at a red light
                if ((key == lastHeadUp && nowMs - lastHeadUpMs < 2000) || (key.first == lastHeadUp?.first && nowMs - lastHeadUpMs < 1000)) return@collect
                lastHeadUp = key; lastHeadUpMs = nowMs
                val charging = stillToCharge(t).sumOf { it.stayMinutes } * 60 +
                    (session.route.value?.let { r -> trafficDelayS(r.distance - t.progress.distanceRemaining) } ?: 0.0)
                val trip = runCatching {
                    NaviHeadUp.trip(t, target.value?.name.orEmpty(), charging, System.currentTimeMillis()) { app.getString(net.prototux.aare.R.string.mate_navi_exit, it) }
                }.onFailure { android.util.Log.w("MateNavi", "head-up: $it") }.getOrNull()
                NaviHeadUp.sink?.invoke(trip); sentNothing = false
            }
        }
    }
}
