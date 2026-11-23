package net.prototux.aare.ui.car.navi

import android.content.Context

/**
 * When to warm the battery up before a fast charger (Navi N-019 step 1, E-029; Ingo, 21.11.2026: "Er kennt ja die
 * Batterie Temperatur. Und ich denke vw will am liebsten immer die 25° wobei weniger wahrscheinlich reichen. Wie schnell
 * er heizt bei welchen Wetter muss er auch lernen. Und ja das muss man leider dann selber starten."). The car warms its
 * battery only when its own navigation guides it, so Mate says when to start it by hand: the battery's temperature from
 * IDmate, [TARGET_C] at the charger, the heating rate learned per outside temperature ([Rates]), the start at the arrival
 * minus the time it takes. The driver starts it; Mate only says when and what it costs.
 */
internal object NaviPreheat {
    /** What VW aims at for fast charging. Whether less is enough is to be learned from the charging power later. */
    const val TARGET_C = 25.0
    /** Within this much of the target there is nothing worth warming. */
    const val CLOSE_C = 3.0
    /** Chargers from this power on are fast ones, worth a warm battery. */
    const val FAST_KW = 100
    /**
     * Before anything is learned: degrees per minute, and the heater's power. 0.25 K/min, not 0.8 (22.11.2026: the hint
     * came 6-7 min before the charger - "was bei dem VW leider wahrscheinlich nicht ausreichen wird. Die Temperatur der
     * Batterie hat sich noch nicht um 1 Grad weiter erhöht"): the MEB's heater takes about half an hour for 10 K.
     */
    const val START_RATE_K_MIN = 0.25
    const val HEATER_KW = 5.0
    /** A little more than the warming takes, so the battery is warm on arrival and not only then. */
    const val MARGIN_MIN = 8.0
    /** Rates outside this are no warming (a reading jumped, the car stood in the sun). */
    const val MIN_RATE = 0.08
    const val MAX_RATE = 3.0

    /** The hint: start now, it takes [minutes] and about [kwh] (from [fromC] to [TARGET_C]). */
    class Advice(val minutes: Double, val kwh: Double, val fromC: Double)

    /** Minutes the battery needs from [batteryC] to [TARGET_C] at [rateKMin]; 0 when it is warm enough. */
    fun minutesNeeded(batteryC: Double, rateKMin: Double): Double =
        if (batteryC >= TARGET_C - CLOSE_C) 0.0 else (TARGET_C - batteryC) / rateKMin

    /**
     * The hint for a charger of [chargerKw] reached in [minutesToCharger], the battery at [batteryC] and the air at
     * [outsideC]; null when there is nothing to do yet: no fast charger, the battery warm enough, or still too far.
     */
    fun advice(batteryC: Double?, outsideC: Double?, chargerKw: Int, minutesToCharger: Double, rates: Rates): Advice? {
        if (batteryC == null || chargerKw < FAST_KW) return null
        val rate = rates.at(outsideC)
        val need = minutesNeeded(batteryC, rate)
        if (need <= 0.0) return null
        if (minutesToCharger > need + MARGIN_MIN) return null
        return Advice(need, need / 60.0 * HEATER_KW, batteryC)
    }

    /**
     * The heating rate learned per outside temperature: each warming seen ([learn]) goes into its band of [BAND_C]
     * degrees, the rate of a band is the mean of its last [KEEP]; a band without any takes the nearest one, none at all
     * [START_RATE_K_MIN].
     */
    class Rates(private val seen: Map<Int, List<Double>> = emptyMap()) {
        private fun band(c: Double) = Math.floorDiv(Math.floor(c).toInt(), BAND_C) * BAND_C

        fun at(outsideC: Double?): Double {
            if (seen.isEmpty() || outsideC == null) return seen.values.flatten().takeIf { it.isNotEmpty() }?.average() ?: START_RATE_K_MIN
            val b = band(outsideC)
            val near = seen.keys.minByOrNull { Math.abs(it - b) } ?: return START_RATE_K_MIN
            return seen.getValue(near).average()
        }

        /** A warming of [kPerMin] seen at [outsideC]: kept, unless it is no warming. */
        fun learn(outsideC: Double, kPerMin: Double): Rates {
            if (kPerMin !in MIN_RATE..MAX_RATE) return this
            val b = band(outsideC)
            return Rates(seen + (b to ((seen[b] ?: emptyList()) + kPerMin).takeLast(KEEP)))
        }

        fun encode(): String = seen.entries.joinToString(";") { (b, l) -> "$b:" + l.joinToString(",") { "%.2f".format(java.util.Locale.ROOT, it) } }

        override fun toString() = if (seen.isEmpty()) "start value %.1f K/min".format(java.util.Locale.ROOT, START_RATE_K_MIN)
            else seen.entries.sortedBy { it.key }.joinToString { (b, l) -> "%d..%d °C %.2f K/min (%d)".format(java.util.Locale.ROOT, b, b + BAND_C, l.average(), l.size) }

        companion object {
            const val BAND_C = 5
            const val KEEP = 10
            fun decode(s: String?): Rates = Rates(runCatching {
                s.orEmpty().split(';').filter { it.contains(':') }.associate { part ->
                    val (b, l) = part.split(':'); b.toInt() to l.split(',').filter { it.isNotBlank() }.map { it.toDouble() } }
            }.getOrDefault(emptyMap()))

            private fun prefs(c: Context) = c.getSharedPreferences("battery_warm", Context.MODE_PRIVATE)
            fun of(c: Context): Rates = decode(prefs(c).getString("rates", null))
            fun save(c: Context, r: Rates) { prefs(c).edit().putString("rates", r.encode()).apply() }
        }
    }

    /**
     * Learns the heating rate from the battery's readings: a rise of at least [RISE_C] within [WINDOW_MIN] minutes is a
     * warming (the driver started it, or the car does on its own), its rate goes to [Rates]. Readings come every few
     * minutes from IDmate; a reading that falls starts a new watch.
     */
    class Watch {
        private var startC = Double.NaN; private var startMs = 0L; private var lastC = Double.NaN

        /** Nothing seen so far counts (the car charged meanwhile). */
        fun reset() { startC = Double.NaN; lastC = Double.NaN; startMs = 0L }

        /** A reading of [batteryC] at [atMs]; the rate in K/min when a warming has just been seen, else null. */
        fun reading(batteryC: Double, atMs: Long): Double? {
            if (lastC.isNaN() || batteryC < lastC - 0.5 || atMs - startMs > WINDOW_MIN * 60_000) { startC = batteryC; startMs = atMs }
            lastC = batteryC
            val min = (atMs - startMs) / 60_000.0
            if (min < MIN_SPAN_MIN || batteryC - startC < RISE_C) return null
            val rate = (batteryC - startC) / min
            startC = batteryC; startMs = atMs
            return rate
        }

        companion object {
            // a slow heater as the MEB's: 2 °C within 40 min count (readings come from the stick every few seconds now)
            const val RISE_C = 2.0
            const val WINDOW_MIN = 40
            const val MIN_SPAN_MIN = 5.0
        }
    }
}
