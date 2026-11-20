package net.prototux.aare.trips

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.prototux.aare.DiagLog
import net.prototux.aare.car.CarData
import net.prototux.aare.dash.DashData
import net.prototux.aare.ui.car.navi.CarFixes
import org.maplibre.spatialk.geojson.Position

/**
 * The trip log and the charge log fed while the car is connected (System L-076/L-077): every [STEP_MS] the car's gear,
 * speed, GPS and odometer and the stick's level, charging and power go to the [TripDetector]; what it finds goes into the
 * [TripStore], its places named in the background (own places first, else the map server's address).
 */
object TripWatch {
    private const val STEP_MS = 2_000L
    /** The stick's values older than this are not used for a sample. */
    private const val STICK_MS = 2 * 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var detector: TripDetector? = null

    /** The car connected. Main thread. */
    fun start(context: Context) {
        val app = context.applicationContext
        if (job?.isActive == true) return
        job = scope.launch {
            val store = TripStore.get(app)
            val grace = net.prototux.aare.Settings(app).tripGraceMin.coerceIn(1, 30) * 60_000L
            val d = TripDetector(grace, { DashData.energy(app)?.capacityKwh }, store.lastEnd(), store.lastCutCharge())
            synchronized(this@TripWatch) { detector = d }
            DiagLog.add("trip log: watching (a trip ends after ${grace / 60_000} min in P)")
            name(app)
            while (isActive) {
                val events = synchronized(this@TripWatch) { detector?.feed(sample(app)) } ?: break
                handle(app, store, events)
                delay(STEP_MS)
            }
        }
    }

    /** The car went away: an open trip ends, an open charge is cut. Main thread. */
    fun stop(context: Context) {
        val app = context.applicationContext
        job?.cancel(); job = null
        val events = synchronized(this) { detector?.carGone().also { detector = null } } ?: return
        scope.launch { handle(app, TripStore.get(app), events) }
    }

    private fun sample(app: Context): TripDetector.Sample {
        val s = CarData.state.value
        val now = System.currentTimeMillis()
        val gear = when (s.gear?.value) {
            null -> TripDetector.Gear.UNKNOWN
            101 -> TripDetector.Gear.PARK
            else -> TripDetector.Gear.DRIVE        // drive, reverse, neutral
        }
        val fix = CarFixes.last.value?.takeIf { CarFixes.ageMs() < 10_000 }
        return TripDetector.Sample(
            t = now, gear = gear,
            speedKmh = s.speed?.value?.metersPerSecond?.times(3.6)?.toDouble(),
            lat = fix?.latitude, lon = fix?.longitude,
            soc = DashData.number(app, "soc", STICK_MS),
            odoKm = s.odometer?.value?.km?.toDouble() ?: DashData.number(app, "odometer", 15 * 60_000L),   // the stick keeps it fresh 15 min
            charging = DashData.flag(app, "charging", STICK_MS),
            dc = DashData.flag(app, "dc", STICK_MS),
            powerKw = DashData.number(app, "power", STICK_MS),
        )
    }

    private fun handle(app: Context, store: TripStore, events: List<TripDetector.Event>) {
        if (events.isEmpty()) return
        for (e in events) when (e) {
            is TripDetector.Event.TripStarted -> DiagLog.add("trip log: trip started")
            is TripDetector.Event.TripEnded -> {
                store.add(e.trip)
                DiagLog.add("trip log: trip saved - %.1f km, %d min, %d points%s".format(java.util.Locale.ROOT,
                    e.trip.distanceKm ?: 0.0, (e.trip.endMs - e.trip.startMs) / 60_000, e.trip.points.size,
                    e.trip.consumption?.let { ", %.1f kWh/100 km".format(java.util.Locale.ROOT, it) } ?: ""))
            }
            is TripDetector.Event.ChargeEnded -> {
                store.add(e.charge)
                DiagLog.add("trip log: charge saved - ${e.charge.source.name.lowercase()}" + (if (e.charge.cut) " (cut, the car went away)" else "") +
                    ", %s → %s %%".format(java.util.Locale.ROOT, e.charge.socStart?.let { "%.0f".format(java.util.Locale.ROOT, it) } ?: "?",
                        e.charge.socEnd?.let { "%.0f".format(java.util.Locale.ROOT, it) } ?: "?"))
            }
            is TripDetector.Event.ChargeCompleted -> { store.complete(e.charge); DiagLog.add("trip log: cut charge completed from the level") }
        }
        if (events.any { it !is TripDetector.Event.TripStarted }) scope.launch { name(app) }
    }

    /** Places of trips and charges without a name: the own place within [OWN_M], else the address from the map server. */
    suspend fun name(context: Context) {
        val app = context.applicationContext
        val store = TripStore.get(app)
        val own = runCatching { net.prototux.aare.places.Places.load(app).places }.getOrDefault(emptyList())
        suspend fun nameOf(lat: Double?, lon: Double?): String? {
            if (lat == null || lon == null) return null
            own.minByOrNull { TripDetector.distanceM(lat, lon, it.lat, it.lon) }
                ?.takeIf { TripDetector.distanceM(lat, lon, it.lat, it.lon) <= OWN_M }?.let { return it.name }
            return net.prototux.aare.ui.car.navi.NaviSearch.address(app, Position(longitude = lon, latitude = lat))
        }
        for (t in store.unnamedTrips()) {
            val a = if (t.startName == null) nameOf(t.startLat, t.startLon) else null
            val b = if (t.endName == null) nameOf(t.endLat, t.endLon) else null
            if (a != null || b != null) store.nameTrip(t.id, a, b)
        }
        for (c in store.unnamedCharges()) nameOf(c.lat, c.lon)?.let { store.nameCharge(c.id, it) }
    }

    private const val OWN_M = 150.0
}
