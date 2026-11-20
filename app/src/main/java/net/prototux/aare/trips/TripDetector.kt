package net.prototux.aare.trips

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * System L-076/L-077 (Ingo, 20.11.2026: "eine Fahrt würde zum Beispiel starten, sobald das Fahrzeug in Drive geht. Und dann,
 * wenn es länger als eine Minute Park ist, dann ist die Fahrt vorbei. Oder 5 Minuten Sicherheitskarenz, wenn man mal schnell
 * beim Bäcker reinspringen möchte"): trips and charges from what the car and the stick tell Mate, on the tablet.
 *
 * Unlike IDmate's server (`triplog/detector.py`: speed only, a trip ends after 5 min standing or a gap in the data), Mate has
 * the car's gear: a trip starts when the car leaves P and ends once it has stood in P for [graceMs]; it ends at the moment P
 * was put in. Without a gear (an older head unit) the car's speed decides, as IDmate does. When the car goes away (Android
 * Auto ends - the car was switched off) an open trip ends at the last sample.
 *
 * Charges: while the stick says "charging", with its power summed up over time (each step at most [GAP_MS], as IDmate caps
 * telemetry gaps). The car is mostly off at a charger, so Mate often sees no charge at all: then a rise of the battery level
 * by [SOC_RISE] points or more between the end of one trip and the start of the next is a charge, found from the level.
 *
 * Pure logic, no Android: fed one [Sample] after another (time rising).
 */
class TripDetector(
    private val graceMs: Long = DEFAULT_GRACE_MS,
    /** The battery's usable capacity in kWh at that moment, for a trip's energy from its level; null when not known. */
    private val capacityKwh: () -> Double? = { null },
    /** Where the last trip ended, from the store, so a charge between two runs of Mate is still seen. */
    private var lastEnd: Spot? = null,
    /** The last charge when it was cut by the car going away (from the store): the next trip completes its level. */
    private var cutCharge: Charge? = null,
) {
    enum class Gear { PARK, DRIVE, UNKNOWN }

    data class Sample(
        val t: Long,
        val gear: Gear = Gear.UNKNOWN,
        val speedKmh: Double? = null,
        val lat: Double? = null,
        val lon: Double? = null,
        val soc: Double? = null,
        val odoKm: Double? = null,
        val charging: Boolean? = null,
        val dc: Boolean? = null,
        val powerKw: Double? = null,
    )

    /** A place and moment with the battery level, e.g. where a trip ended. */
    data class Spot(val t: Long, val lat: Double?, val lon: Double?, val soc: Double?)

    data class Point(val t: Long, val lat: Double, val lon: Double, val kmh: Double?, val soc: Double?)

    data class Trip(
        val startMs: Long, val endMs: Long,
        val startLat: Double?, val startLon: Double?, val endLat: Double?, val endLon: Double?,
        val distanceKm: Double?, val odoStart: Double?, val odoEnd: Double?,
        val socStart: Double?, val socEnd: Double?, val energyKwh: Double?, val consumption: Double?,
        val maxKmh: Double?, val points: List<Point>,
    )

    enum class ChargeSource { STICK, LEVEL }

    data class Charge(
        val startMs: Long, val endMs: Long, val lat: Double?, val lon: Double?, val dc: Boolean?,
        val socStart: Double?, val socEnd: Double?, val energyKwh: Double?, val maxKw: Double?,
        val source: ChargeSource,
        /** The charge was still running when the car went away: its end is the last moment Mate saw it. */
        val cut: Boolean = false,
    )

    sealed class Event {
        data class TripStarted(val atMs: Long) : Event()
        data class TripEnded(val trip: Trip) : Event()
        data class ChargeEnded(val charge: Charge) : Event()
        /** A cut charge completed with the level found at the next trip ([charge] replaces the one starting at the same time). */
        data class ChargeCompleted(val charge: Charge) : Event()
    }

    private class Open(val start: Sample) {
        val samples = ArrayList<Sample>()
        val points = ArrayList<Point>()
        var parkSince: Long? = null
    }

    private class OpenCharge(val start: Sample, val lat: Double?, val lon: Double?) {
        var last: Sample = start
        var kwh = 0.0
        var maxKw = 0.0
        var dc: Boolean? = start.dc
        var lastSoc: Double? = start.soc
    }

    private var trip: Open? = null
    private var charge: OpenCharge? = null
    private var last: Sample? = null
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastSoc: Double? = null
    /** A charge seen by the stick since [lastEnd]: then a rise of the level is not counted a second time. */
    private var chargeSinceEnd = false

    val inTrip: Boolean get() = trip != null
    val charging: Boolean get() = charge != null

    fun feed(s: Sample): List<Event> {
        val out = ArrayList<Event>()
        if (s.lat != null && s.lon != null) { lastLat = s.lat; lastLon = s.lon }
        if (s.soc != null) lastSoc = s.soc
        chargeStep(s, out)
        val moving = when (s.gear) {
            Gear.DRIVE -> true
            Gear.PARK -> false
            Gear.UNKNOWN -> (s.speedKmh ?: 0.0) > MOVING_KMH
        }
        val t = trip
        if (t == null) {
            if (moving) {
                levelCharge(s, out)
                trip = Open(s).also { add(it, s) }
                out += Event.TripStarted(s.t)
            }
        } else {
            add(t, s)
            if (moving) t.parkSince = null
            else {
                val since = t.parkSince ?: s.t.also { t.parkSince = it }
                if (s.t - since >= graceMs) out += endTrip(t, since)
            }
        }
        last = s
        return out
    }

    /** The car went away (switched off): an open trip ends at the last sample, an open charge is cut there. */
    fun carGone(): List<Event> {
        val out = ArrayList<Event>()
        val l = last ?: return out
        trip?.let { t -> out += endTrip(t, t.parkSince ?: l.t) }
        charge?.let { c -> val done = closeCharge(c, cut = true); cutCharge = done; out += Event.ChargeEnded(done); charge = null }
        return out
    }

    private fun add(t: Open, s: Sample) {
        t.samples += s
        val lat = s.lat ?: return
        val lon = s.lon ?: return
        val p = t.points.lastOrNull()
        if (p == null || s.t - p.t >= POINT_MS || distanceM(p.lat, p.lon, lat, lon) >= POINT_M)
            t.points += Point(s.t, lat, lon, s.speedKmh, s.soc)
    }

    private fun endTrip(t: Open, endMs: Long): List<Event> {
        trip = null
        val samples = t.samples.filter { it.t <= endMs }.ifEmpty { listOf(t.start) }
        val points = t.points.filter { it.t <= endMs }
        val odoStart = samples.firstNotNullOfOrNull { it.odoKm }
        val odoEnd = samples.lastOrNull { it.odoKm != null }?.odoKm
        val gpsKm = points.zipWithNext { a, b -> distanceM(a.lat, a.lon, b.lat, b.lon) }.sum() / 1000.0
        val distance = if (odoStart != null && odoEnd != null && odoEnd >= odoStart) odoEnd - odoStart else if (points.size >= 2) gpsKm else null
        val socStart = samples.firstNotNullOfOrNull { it.soc }
        val socEnd = samples.lastOrNull { it.soc != null }?.soc
        val cap = capacityKwh()
        val energy = if (socStart != null && socEnd != null && cap != null && socStart > socEnd) (socStart - socEnd) / 100.0 * cap else null
        val consumption = if (energy != null && distance != null && distance >= 1.0) energy / distance * 100.0 else null
        val first = points.firstOrNull()
        val lastPoint = points.lastOrNull()
        val endLat = lastPoint?.lat ?: lastLat
        val endLon = lastPoint?.lon ?: lastLon
        lastEnd = Spot(endMs, endLat, endLon, socEnd ?: lastSoc)
        chargeSinceEnd = false
        if ((distance ?: 0.0) < MIN_TRIP_KM) return emptyList()
        return listOf(Event.TripEnded(Trip(
            startMs = t.start.t, endMs = endMs,
            startLat = first?.lat ?: t.start.lat, startLon = first?.lon ?: t.start.lon, endLat = endLat, endLon = endLon,
            distanceKm = round1n(distance), odoStart = odoStart, odoEnd = odoEnd,
            socStart = socStart, socEnd = socEnd, energyKwh = energy?.let(::round2), consumption = consumption?.let(::round1),
            maxKmh = samples.mapNotNull { it.speedKmh }.maxOrNull()?.let(::round1), points = points,
        )))
    }

    private fun chargeStep(s: Sample, out: MutableList<Event>) {
        val c = charge
        when {
            s.charging == true && c == null -> { charge = OpenCharge(s, s.lat ?: lastLat, s.lon ?: lastLon); chargeSinceEnd = true; cutCharge = null }
            s.charging == true && c != null -> {
                val dtH = ((s.t - c.last.t).coerceIn(0L, GAP_MS)) / 3_600_000.0
                val p = abs(s.powerKw ?: 0.0)
                c.kwh += p * dtH
                if (p > c.maxKw) c.maxKw = p
                if (s.dc == true) c.dc = true else if (c.dc == null && s.dc != null) c.dc = s.dc
                if (s.soc != null) c.lastSoc = s.soc
                c.last = s
            }
            s.charging == false && c != null -> { c.last = s; if (s.soc != null) c.lastSoc = s.soc; out += Event.ChargeEnded(closeCharge(c, cut = false)); charge = null }
        }
    }

    private fun closeCharge(c: OpenCharge, cut: Boolean) = Charge(
        startMs = c.start.t, endMs = c.last.t, lat = c.lat, lon = c.lon, dc = c.dc,
        socStart = c.start.soc, socEnd = c.lastSoc, energyKwh = if (c.kwh > 0) round2(c.kwh) else null,
        maxKw = if (c.maxKw > 0) round1(c.maxKw) else null, source = ChargeSource.STICK, cut = cut,
    )

    /** A trip starts: had the level risen since the last trip ended without a charge seen by the stick, it was charged there. */
    private fun levelCharge(s: Sample, out: MutableList<Event>) {
        val now = s.soc ?: lastSoc ?: return
        cutCharge?.let { c ->
            cutCharge = null
            if (c.socEnd == null || now - c.socEnd >= 1.0) out += Event.ChargeCompleted(c.copy(socEnd = now))
            return
        }
        val end = lastEnd ?: return
        val before = end.soc ?: return
        if (!chargeSinceEnd && now - before >= SOC_RISE)
            out += Event.ChargeEnded(Charge(end.t, s.t, end.lat, end.lon, null, before, now, null, null, ChargeSource.LEVEL))
    }

    companion object {
        const val DEFAULT_GRACE_MS = 5 * 60_000L
        /** Without a gear, faster than this is driving (IDmate: any speed above 0; the car's speed jitters at a standstill). */
        const val MOVING_KMH = 3.0
        /** Shorter trips are not kept (moving the car in the drive). */
        const val MIN_TRIP_KM = 0.2
        /** A rise of the level by this many points between two trips is a charge (IDmate: SOC_JUMP_MIN 5; the stick's level is exact). */
        const val SOC_RISE = 3.0
        /** A step of the charge's power at most this long (a gap in the data must not count as charging). */
        const val GAP_MS = 5 * 60_000L
        const val POINT_MS = 15_000L
        const val POINT_M = 75.0

        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).let { it * it } + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
            return 2 * r * asin(sqrt(a))
        }

        private fun round1n(v: Double?) = v?.let(::round1)
        private fun round1(v: Double) = Math.round(v * 10) / 10.0
        private fun round2(v: Double) = Math.round(v * 100) / 100.0
    }
}
