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
    /**
     * Rates outside this are no warming by the heater (a reading jumped, the car stood in the sun, it charged). Navi I-065
     * (26.11.2026: "die Zeiten … sehr spät oder fast zu spät"): 3 K/min let one jump of 1.45 K/min through, and with it every
     * hint thought 25 K took 17 min; the MEB's heater does some 0.25-0.5 K/min.
     */
    const val MIN_RATE = 0.08
    const val MAX_RATE = 0.8
    /** Until a band has [TRUST_N] warmings, its rate counts at most this fast: rather a hint too early than too late. */
    const val CAUTIOUS_RATE = 0.5
    const val TRUST_N = 3

    /**
     * The hint: start now, it takes [minutes] and about [kwh] (from [fromC] to [targetC]); [why] the weighing of the targets
     * for the log (Navi L-052), empty without a planned stop to weigh it by.
     */
    class Advice(val minutes: Double, val kwh: Double, val fromC: Double, val targetC: Double = TARGET_C, val why: String = "")

    // ── Navi L-052: the target by the situation ─────────────────────────────────────────────────────────────────────
    // (27.11.2026: "Lohnt es sich überhaupt, auf 25 Grad zu heizen, oder reichen 18 oder 20 Grad? Das sollte alles dynamisch
    // und so intelligent wie möglich passieren"): each target weighed by the minutes it saves at the charger against the
    // minutes it costs to charge back what the warming took - the battery's level at the charger, how much is charged
    // there, the charger's power and the heating rate learned at this outside temperature all go in.

    /** The targets weighed (°C). */
    val TARGETS = listOf(15.0, 18.0, 20.0, 23.0, 25.0)
    /** A warming saving less than this at the charger is not worth the hint. */
    const val WORTH_MIN = 3.0
    /** While it charges the battery warms by itself (the charge's losses, the car's own heating at the charger), K/min. */
    const val SELF_K_MIN = 0.3

    /**
     * The share of its full DC power the battery takes at [c] °C - the MEB as others measured it (cold it takes far less;
     * 25 °C and up all of it). A first guess: to be learned from the charges themselves (IDmate's power and battery
     * temperature per charge) once they are there.
     */
    fun powerShare(c: Double): Double {
        val t = SHARE
        if (c <= t.first().first) return t.first().second
        if (c >= t.last().first) return t.last().second
        val i = t.indexOfLast { it.first <= c }
        val (x0, y0) = t[i]; val (x1, y1) = t[i + 1]
        return y0 + (y1 - y0) * (c - x0) / (x1 - x0)
    }
    private val SHARE = listOf(-5.0 to 0.22, 0.0 to 0.3, 5.0 to 0.42, 10.0 to 0.55, 15.0 to 0.7, 20.0 to 0.88, 25.0 to 1.0)

    /** Minutes to charge [car] from [fromPct] to [toPct] at a charger of [kw], the battery at [startC] and warming as it charges. */
    fun chargeMinutes(car: net.prototux.aare.host.EnergyModel.Car, kw: Int, fromPct: Double, toPct: Double, startC: Double): Double {
        val stepWh = car.capacityWh / 100.0
        var t = 0.0; var c = startC; var pct = fromPct
        while (pct < toPct - 1e-9) {
            val part = minOf(1.0, toPct - pct)
            val w = minOf(kw * 1000.0, net.prototux.aare.host.EnergyModel.dcCurveW((pct + part / 2) * stepWh, car) * powerShare(c)).coerceAtLeast(1_000.0)
            val dt = stepWh * part / w * 60.0
            t += dt; c = minOf(c + SELF_K_MIN * dt, 30.0); pct += part
        }
        return t
    }

    /** A target weighed: warming [minutes] for [kwh], and the minutes it [saves] at the charger, all told. */
    class Weighed(val targetC: Double, val minutes: Double, val kwh: Double, val saves: Double)

    /**
     * The targets for a charge from [arrivePct] to [leavePct] at a charger of [kw], the battery at [batteryC] warming at
     * [rate] K/min: each above the battery now (by more than [CLOSE_C]) with what it saves - the charge cold, against the
     * charge warm from the level lowered by the warming's energy (that energy charged back counts in); none that would
     * bring the car below [leastPct] at the charger.
     */
    fun weigh(car: net.prototux.aare.host.EnergyModel.Car, kw: Int, arrivePct: Double, leavePct: Double, batteryC: Double, rate: Double,
              leastPct: Double): List<Weighed> {
        val cold = chargeMinutes(car, kw, arrivePct, leavePct, batteryC)
        return TARGETS.filter { it > batteryC + CLOSE_C }.mapNotNull { target ->
            val min = (target - batteryC) / rate
            val kwh = min / 60.0 * HEATER_KW
            val arrive = arrivePct - kwh * 1000.0 / car.capacityWh * 100.0
            if (arrive < leastPct) return@mapNotNull null
            Weighed(target, min, kwh, cold - chargeMinutes(car, kw, arrive, leavePct, target))
        }
    }

    /** The best of [weighed]: the most minutes saved, of nearly equals (within a minute) the cooler; null when none is worth it. */
    fun best(weighed: List<Weighed>): Weighed? {
        val top = weighed.maxOfOrNull { it.saves } ?: return null
        if (top < WORTH_MIN) return null
        return weighed.filter { it.saves >= top - 1.0 }.minByOrNull { it.targetC }
    }

    /** The stop the warming is for: the levels at the charger and what the driver allows least there. */
    class Stop(val car: net.prototux.aare.host.EnergyModel.Car, val arrivePct: Double, val leavePct: Double, val leastPct: Double)

    /** Minutes the battery needs from [batteryC] to [TARGET_C] at [rateKMin]; 0 when it is warm enough. */
    fun minutesNeeded(batteryC: Double, rateKMin: Double): Double =
        if (batteryC >= TARGET_C - CLOSE_C) 0.0 else (TARGET_C - batteryC) / rateKMin

    /**
     * The hint for a charger of [chargerKw] reached in [minutesToCharger], the battery at [batteryC] and the air at
     * [outsideC]; null when there is nothing to do yet: no fast charger, the battery warm enough, or still too far.
     */
    fun advice(batteryC: Double?, outsideC: Double?, chargerKw: Int, minutesToCharger: Double, rates: Rates, stop: Stop? = null): Advice? {
        if (batteryC == null || chargerKw < FAST_KW) return null
        val rate = rates.at(outsideC)
        if (stop == null) {
            val need = minutesNeeded(batteryC, rate)
            if (need <= 0.0) return null
            if (minutesToCharger > need + MARGIN_MIN) return null
            return Advice(need, need / 60.0 * HEATER_KW, batteryC)
        }
        // Navi L-052: the target worth most for this charge; started once the time left is what it takes
        val weighed = weigh(stop.car, chargerKw, stop.arrivePct, stop.leavePct, batteryC, rate, stop.leastPct)
        val pick = best(weighed) ?: return null
        if (minutesToCharger > pick.minutes + MARGIN_MIN) return null
        val why = weighed.joinToString { "%.0f °C %+.0f min (%.0f min, %.1f kWh)".format(java.util.Locale.ROOT, it.targetC, it.saves, it.minutes, it.kwh) } +
            "; charging %.0f -> %.0f %%".format(java.util.Locale.ROOT, stop.arrivePct, stop.leavePct)
        return Advice(pick.minutes, pick.kwh, batteryC, pick.targetC, why)
    }

    /**
     * The heating rate learned per outside temperature: each warming seen ([learn]) goes into its band of [BAND_C]
     * degrees, the rate of a band is the mean of its last [KEEP]; a band without any takes the nearest one, none at all
     * [START_RATE_K_MIN].
     */
    class Rates(private val seen: Map<Int, List<Double>> = emptyMap()) {
        private fun band(c: Double) = Math.floorDiv(Math.floor(c).toInt(), BAND_C) * BAND_C

        /** The warmings the rate at [outsideC] rests on: its band's, the nearest band's, or all; empty without any. */
        private fun basis(outsideC: Double?): List<Double> {
            if (seen.isEmpty()) return emptyList()
            if (outsideC == null) return seen.values.flatten()
            val b = band(outsideC)
            return seen.getValue(seen.keys.minByOrNull { Math.abs(it - b) }!!)
        }

        /** The middle one of the warmings seen (one odd reading does not pull it); a few only: at most [CAUTIOUS_RATE]. */
        fun at(outsideC: Double?): Double {
            val l = basis(outsideC).sorted()
            if (l.isEmpty()) return START_RATE_K_MIN
            val median = if (l.size % 2 == 1) l[l.size / 2] else (l[l.size / 2 - 1] + l[l.size / 2]) / 2
            return if (l.size < TRUST_N) minOf(median, CAUTIOUS_RATE) else median
        }

        /** What the rate at [outsideC] rests on, for the log. */
        fun said(outsideC: Double?): String {
            val n = basis(outsideC).size
            return "%.2f K/min (%s)".format(java.util.Locale.ROOT, at(outsideC),
                if (n == 0) "start value" else if (n < TRUST_N) "$n warming(s) seen, held back" else "$n warmings seen")
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
                    val (b, l) = part.split(':'); b.toInt() to l.split(',').filter { it.isNotBlank() }.map { it.toDouble() }
                        // kept before the bounds were narrowed (I-065): what is no heater's warming goes
                        .filter { it in MIN_RATE..MAX_RATE } }
                    .filterValues { it.isNotEmpty() }
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
        private var lastMs = 0L; private var lastSource: String? = null

        /** Nothing seen so far counts (the car charged meanwhile). */
        fun reset() { startC = Double.NaN; lastC = Double.NaN; startMs = 0L; lastMs = 0L; lastSource = null }

        /**
         * A reading of [batteryC] at [atMs] from [source] ("stick", "backend" …); the rate in K/min when a warming has just
         * been seen, else null. A gap of more than [GAP_MIN] or another source starts anew (I-065: an old server value
         * followed by the stick's looked like a fast warming).
         */
        fun reading(batteryC: Double, atMs: Long, source: String? = null): Double? {
            val jump = lastMs > 0 && (atMs - lastMs > GAP_MIN * 60_000 || source != lastSource)
            if (lastC.isNaN() || jump || batteryC < lastC - 0.5 || atMs - startMs > WINDOW_MIN * 60_000) { startC = batteryC; startMs = atMs }
            lastC = batteryC; lastMs = atMs; lastSource = source
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
            /** Readings further apart than this are no watch of one warming. */
            const val GAP_MIN = 6
        }
    }
}
