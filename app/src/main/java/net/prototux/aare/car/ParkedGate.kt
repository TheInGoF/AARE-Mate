package net.prototux.aare.car

/**
 * Whether the car stands parked for sure, the one condition for films and videos on the car screen (18.11.2026: "Parken
 * und Ladezeit, Amüsement ... dass man die Parkauswertung vom Auto hernimmt und die Geschwindigkeitsauswertung vom Auto.
 * Nur wenn beide eindeutig sind, dann lassen sich Apps öffnen ... Voraussetzung für meine Sicherheit und alle anderen ist
 * natürlich, dass das Auto geparkt ist und dass es keine GPS-Bewegung gibt. Meinetwegen können wir auch noch die
 * Geschwindigkeitssensoren auswerten").
 *
 * Every one must hold at once: the car's gear lever in P, the car's own speed below [STILL_KMH] (told within
 * [FRESH_MS]), the parking brake on where the car tells it, and no movement in the position over the last [WINDOW_MS]
 * (less than [MOVED_M] - a fix standing still wanders some metres -, the fixes' own speed below three times [STILL_KMH]).
 * What the car does not tell keeps it closed - only the parking brake may be missing (many cars do not report it), and
 * the position (none under a roof): there only a movement seen closes it. It opens once all of it has held for [SETTLE_MS] and closes at once.
 * The passenger switch does not lift it.
 */
class ParkedGate {
    /** The car's readings at one moment; null where nothing came. Times in milliseconds of one clock. */
    class Reading(
        val now: Long,
        val gear: Int?, val gearAt: Long,
        val speedKmh: Double?, val speedAt: Long,
        val parkingBrake: Boolean?,
        /** latitude, longitude, the speed the fix itself tells (km/h, null if none), the fix's time */
        val fix: Fix?,
    )
    class Fix(val lat: Double, val lon: Double, val kmh: Double?, val at: Long)

    private val fixes = ArrayDeque<Fix>()
    private var since = -1L

    /**
     * Every reading in words, for the log whenever the verdict changes (18.11.2026: "Dass der Bereich Kino geblockt wird
     * durch irgendwelche Sensorwerte, die dazwischen springen, das muss dann gelockt werden, damit wir das debuggen können
     * ... oder wir dann Zeitgrenzen einbauen müssen, wie lange etwas fehlen darf"): each value with its age.
     */
    fun describe(r: Reading): String {
        fun age(at: Long) = if (at <= 0) "never" else "${(r.now - at) / 1000} s old"
        val gear = when (r.gear) { null -> "none"; GEAR_PARK -> "P"; 100 -> "D"; 102 -> "R"; 0 -> "N"; else -> r.gear.toString() }
        val recent = fixes.filter { r.now - it.at <= WINDOW_MS }
        val moved = if (recent.size < 2) null else recent.maxOf { metres(recent.first(), it) }
        return "gear $gear (${age(r.gearAt)}), speed ${r.speedKmh?.let { "%.1f km/h".format(java.util.Locale.ROOT, it) } ?: "none"} (${age(r.speedAt)}), " +
            "parking brake ${r.parkingBrake?.let { if (it) "on" else "off" } ?: "not told"}, " +
            "position ${if (recent.isEmpty()) "none" else "${recent.size} fixes, moved ${moved?.let { "%.0f m".format(java.util.Locale.ROOT, it) } ?: "-"}, fix speed ${recent.last().kmh?.let { "%.1f km/h".format(java.util.Locale.ROOT, it) } ?: "-"}"}"
    }

    /** Open now? And why not, in words for the car screen and the log. */
    fun check(r: Reading): Pair<Boolean, String?> {
        r.fix?.let { f -> if (fixes.lastOrNull()?.at != f.at) fixes.addLast(f) }
        while (fixes.isNotEmpty() && r.now - fixes.first().at > WINDOW_MS + 5_000) fixes.removeFirst()
        val why = reason(r, fixes.toList())
        if (why != null) { since = -1L; return false to why }
        if (since < 0) since = r.now
        return if (r.now - since >= SETTLE_MS) true to null else false to "settling"
    }

    companion object {
        const val GEAR_PARK = 101
        const val STILL_KMH = 1.0
        const val MOVED_M = 25.0
        const val FRESH_MS = 5_000L
        const val WINDOW_MS = 20_000L
        const val SETTLE_MS = 3_000L
        /** One fix this fast is a movement on its own; slower ones count from two. */
        const val FAST_FIX_KMH = 8.0

        /** What keeps it closed (null: nothing). Pure, for the tests. */
        fun reason(r: Reading, fixes: List<Fix>): String? {
            if (r.gear == null) return "no gear from the car"
            if (r.gear != GEAR_PARK) return "not in P"
            val kmh = r.speedKmh ?: return "no speed from the car"
            if (r.now - r.speedAt > FRESH_MS && kmh >= STILL_KMH) return "speed not fresh"
            if (kmh >= STILL_KMH) return "moving"
            if (r.parkingBrake == false) return "parking brake off"
            val recent = fixes.filter { r.now - it.at <= WINDOW_MS }
            // no position at all (a car park under a roof): the car's P and speed decide; only a movement seen closes it
            if (recent.isEmpty()) return null
            // the fixes' own speed: one fix of a walking pace is the GPS wandering while standing (19.11.2026, 08:05: P and
            // 0 km/h from the car, 4 m wandered, one fix a little over 3 km/h - and the cinema closed); one fast fix, or
            // two of a walking pace, is a movement
            if (recent.any { (it.kmh ?: 0.0) >= FAST_FIX_KMH } || recent.count { (it.kmh ?: 0.0) >= STILL_KMH * 3 } >= 2) return "position moving"
            val first = recent.first()
            if (recent.any { metres(first, it) > MOVED_M }) return "position moving"
            return null
        }

        internal fun metres(a: Fix, b: Fix): Double {
            val dLat = (b.lat - a.lat) * 111_200.0
            val dLon = (b.lon - a.lon) * 111_200.0 * kotlin.math.cos(Math.toRadians((a.lat + b.lat) / 2))
            return kotlin.math.hypot(dLat, dLon)
        }
    }
}
