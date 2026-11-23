package net.prototux.aare.ui.car.navi

import net.prototux.aare.host.EnergyModel
import org.maplibre.spatialk.geojson.Position

/**
 * The charging plan kept up to date while driving (Navi L-029 step F, N-016: "Während der Fahrt alle paar Kilometer lokal
 * neu rechnen mit dem echten Ladestand (rollende Planung): liegt der Stand über dem Plan, wandert der nächste Stopp weiter
 * nach vorn ..., darunter nach hinten - nur bei einer Änderung ein Server-Aufruf"). Every [CHECK_M] the chain is worked out
 * on the device from where the car is, with the level it really has and with what it really took against the model so
 * far ([scale]). The stops change only when it is worth it - no flapping: the plan as it is no longer keeps the least
 * levels (at once), or another chain saves at least [SAVE_MIN] minutes, found at two checks in a row, and not within
 * [CALM_M] of the last change. Only then the caller asks the map server for the route over the new stops.
 *
 * It also notes for [ChargeBuffer] what the model missed on each stretch driven between charging (step D).
 *
 * [check] and [retrack] run off the main thread while the screen reads [levelAt], [spreadAt] and [alongM]: the changing
 * state is kept under the object's lock, and [track] is read once per look (it may be swapped meanwhile).
 */
internal class ChargeRolling(track: ChargePlanner.Track, private val car: EnergyModel.Car,
                             private val rules: ChargePlanner.Rules, private val near: (Position, Double, Int) -> List<Charger>,
                             startSoc: Double, chain: List<Charger>, private val own: List<Charger> = emptyList(),
                             /** The hit list's miss per 100 km of earlier drives (ChargeBuffer.missPer100), null: none yet. */
                             private val learnedMissPer100: Double? = null,
                             /** A stretch driven: percent the car used more than the model said, and its km (for the hit list). */
                             private val stretch: (Double, Double) -> Unit = { _, _ -> }) {
    enum class Kind {
        /** The plan holds. */
        KEEP,
        /** The stops change ([Decision.chain]): the route over them is to be planned. */
        MOVE,
        /** No chain keeps the least levels any more, not even with other stops. */
        LOW
    }

    /** What a check found; [why] in words for the log (level now against the plan, old and new stop). */
    class Decision(val kind: Kind, val chain: List<Charger>, val result: ChargeChain.Result?, val why: String, val urgent: Boolean = false)

    @Volatile private var track: ChargePlanner.Track = track

    /** The stops still ahead, in order. */
    @Volatile var chain: List<Charger> = chain; private set
    /** How many times the stops changed (each one request to the map server). */
    var moves = 0; private set

    // what [scale], [levelAt] and [spreadAt] read is guarded by this lock alone, not by the object: the screen reads them on
    // the main thread while a [check] plans for a long time under the object's lock (a stall of the map otherwise)
    private val scaleLock = Any()
    // the stretch since the start or the last charging: level, and the model's percent used before the present track
    private var refSoc = startSoc
    private var refM = 0.0
    // what the car took and what the model said on the stretches before the last charging (the car stays the car)
    private var takenEarlier = 0.0; private var modelEarlier = 0.0; private var kmEarlier = 0.0
    // chargers given up as the next stop: going back to one takes [BACK_MIN]
    private val left = HashSet<Charger>()
    private var usedBefore = 0.0; private var kmBefore = 0.0
    // what the plan said at this place: the level it was made with and the model's use since
    private var planSoc = startSoc; private var planM = 0.0; private var planUsedBefore = 0.0
    private var lastSoc = startSoc
    private var lastCheckM = -1e9
    private var lastMoveM = -1e9
    private var proposal: Charger? = null

    /** The model's percent used on the present track from [fromM] to [toM]. */
    private fun used(fromM: Double, toM: Double): Double { val t = track; return (whAt(t, toM) - whAt(t, fromM)) / car.capacityWh * 100.0 }

    private fun whAt(t: ChargePlanner.Track, m: Double): Double {
        val i = indexAt(t, m)
        if (i <= 0) return t.wh[0]
        val a = t.metres[i - 1]; val b = t.metres[i]
        val k = if (b > a) ((m - a) / (b - a)).coerceIn(0.0, 1.0) else 1.0
        return t.wh[i - 1] + (t.wh[i] - t.wh[i - 1]) * k
    }

    /** Seconds the plan's model takes from [fromM] to [toM] along the present track (minutes to a charger). */
    fun secondsBetween(fromM: Double, toM: Double): Double { val t = track; return secAt(t, toM) - secAt(t, fromM) }

    private fun secAt(t: ChargePlanner.Track, m: Double): Double {
        val i = indexAt(t, m)
        if (i <= 0) return t.seconds[0]
        val a = t.metres[i - 1]; val b = t.metres[i]
        val k = if (b > a) ((m - a) / (b - a)).coerceIn(0.0, 1.0) else 1.0
        return t.seconds[i - 1] + (t.seconds[i] - t.seconds[i - 1]) * k
    }

    private fun indexAt(m: Double): Int = indexAt(track, m)

    private fun indexAt(t: ChargePlanner.Track, m: Double): Int {
        var lo = 0; var hi = t.size - 1
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (t.metres[mid] < m) lo = mid + 1 else hi = mid }
        return lo
    }

    /**
     * What the car really took against the model since [refSoc]: above 1 it takes more. Trusted the more the further it
     * has gone - quicker when it takes more (to be safe), slower when it takes less.
     */
    fun scale(drivenM: Double, soc: Double): Double = synchronized(scaleLock) {
        val model = modelEarlier + usedBefore + used(refM, drivenM)
        val km = kmEarlier + kmBefore + (drivenM - refM) / 1000
        if (model < MIN_USED) return@synchronized 1.0
        val seen = ((takenEarlier + refSoc - soc) / model).coerceIn(LOW_SCALE, HIGH_SCALE)
        val trust = if (seen >= 1) minOf(1.0, km / TRUST_UP_KM) else minOf(1.0, km / TRUST_DOWN_KM)
        1 + (seen - 1) * trust
    }

    /** The route changed (the stops moved, a new route from here): the plan goes on on [newTrack] from its start. */
    @Synchronized fun retrack(newTrack: ChargePlanner.Track, drivenM: Double, newChain: List<Charger> = chain) {
        planUsedBefore += used(planM, drivenM); planM = 0.0
        // the new track and the start of its stretch together, so a reader never sees one without the other
        synchronized(scaleLock) { usedBefore += used(refM, drivenM); kmBefore += (drivenM - refM) / 1000; refM = 0.0; track = newTrack }
        chain = newChain; lastCheckM = -1e9
    }

    /**
     * The level at [toM] along the present track, from [soc] at [drivenM] (19.11.2026: the tile above the speed counted
     * with a model of its own - no weather, heights or temperature, nothing learned from the drive - and drifted from
     * the plan: "Wenn die Route sagt, okay, er braucht noch 36% und ich habe 40, dann komme ich mit 4 an"). The plan's
     * model along the way, times what the car really took so far ([scale]).
     */
    fun levelAt(drivenM: Double, soc: Double, toM: Double): Double = synchronized(scaleLock) { soc - used(drivenM, toM) * scale(drivenM, soc) }

    /**
     * How sure [levelAt] is, as ± percent (N-019 step 1, E-029: "Anzeige ‚Ankunft 11 % ± 2' mit schrumpfendem ±"): the
     * car's whole-percent reading ([READING]) and the model's error on what is still to go - [REL_START] of it at the
     * start, down to [REL_LEARNED] once the drive has shown how the car takes it (the same trust as [scale]). Shrinks as
     * the way ahead gets shorter and the drive longer.
     */
    fun spreadAt(drivenM: Double, soc: Double, toM: Double): Double = synchronized(scaleLock) {
        val ahead = maxOf(0.0, used(drivenM, toM) * scale(drivenM, soc))
        val km = kmEarlier + kmBefore + (drivenM - refM) / 1000
        val trust = minOf(1.0, km / TRUST_DOWN_KM)
        // before the drive has taught anything: what earlier drives missed per 100 km (the hit list, E-030), else [REL_START]
        val kmAhead = maxOf(0.0, toM - drivenM) / 1000
        val atStart = learnedMissPer100?.let { it * kmAhead / 100.0 } ?: (ahead * REL_START)
        val err = atStart + (ahead * REL_LEARNED - atStart) * trust
        Math.sqrt(READING * READING + err * err)
    }

    /** The present track's length in metres. */
    val endM: Double get() = track.metres.last()

    /** The model's percent used from [fromM] to [toM] along the present track (a test drive's level is made of it). */
    fun modelUsed(fromM: Double, toM: Double) = used(fromM, toM)

    /** The route over the new stops could not be had: the stops of before stay ([MOVE] undone, no further change for a while). */
    @Synchronized fun undo(old: List<Charger>) { chain = old; moves-- }

    /** Arrived: the last stretch for [ChargeBuffer]. */
    @Synchronized fun arrived(drivenM: Double, soc: Double) = noteStretch(drivenM, soc)

    private fun noteStretch(drivenM: Double, soc: Double) {
        val (model, km) = synchronized(scaleLock) { (usedBefore + used(refM, drivenM)) to (kmBefore + (drivenM - refM) / 1000) }
        stretch((refSoc - soc) - model, km)
    }

    /**
     * The check at [drivenM] along the track with the car's [soc]; null between checks ([force]: now). A level that rose
     * since the last one is a charging: the stretch before it is noted and a new one starts.
     */
    @Synchronized fun check(drivenM: Double, soc: Double, force: Boolean = false): Decision? {
        if (soc > lastSoc + CHARGED_PERCENT) {
            noteStretch(drivenM, lastSoc)
            synchronized(scaleLock) {
                takenEarlier += refSoc - lastSoc; modelEarlier += usedBefore + used(refM, drivenM); kmEarlier += kmBefore + (drivenM - refM) / 1000
                refSoc = soc; refM = drivenM; usedBefore = 0.0; kmBefore = 0.0
            }
            planSoc = soc; planM = drivenM; planUsedBefore = 0.0
        }
        lastSoc = soc
        if (!force && drivenM - lastCheckM < CHECK_M) return null
        lastCheckM = drivenM
        val from = indexAt(drivenM)
        chain = chain.filter { c -> alongM(c) > drivenM + PASSED_M }
        val ownAhead = own.filter { c -> alongM(c) > drivenM + PASSED_M }
        val scale = scale(drivenM, soc)
        // the plan as it is (its stops, each may be left out) and the best of all from here
        val current = onChain(chain, ownAhead, from, soc, scale, rules)
        val best = ChargeChain.plan(track, car, soc, near, rules, from, scale, ownAhead, keep = chain)
        val bestChain = best.links.map { it.cand.charger }.filter { it !in own }
        val expected = planSoc - planUsedBefore - used(planM, drivenM)
        val why = "level %.0f %% instead of the plan's %.0f %%, the car takes x%.2f of the model".format(java.util.Locale.ROOT, soc, expected, scale)
        lastCurrent = current
        // only the next stop decides: the later ones are looked at again on the way, with more known by then
        if (bestChain.firstOrNull() == chain.firstOrNull()) { proposal = null; return Decision(Kind.KEEP, chain, current, why) }
        // the next stop (or the destination) out of reach with its least level: at once; else only when worth it
        // (the buffer is there for the error of the model: half of it may go before the stop has to change at once)
        val urgent = firstShort(current, drivenM) > firstBuffer(current, drivenM) / 2
        if (firstShort(best, drivenM) > ChargeChain.SHORT_OK && best.shortPercent >= current.shortPercent - ChargeChain.SHORT_OK) {
            proposal = null
            return Decision(if (urgent) Kind.LOW else Kind.KEEP, chain, current, why)
        }
        val saves = current.cost - best.cost
        if (!urgent) {
            // a later stop short of its level is looked at once it is the next one (else the stop flaps: 18 % more use
            // made the stop after next fall short, the next one moved for it, and back once that had passed)
            val back = bestChain.firstOrNull()?.let { it in left } == true
            if (saves < (if (back) BACK_MIN else SAVE_MIN) || drivenM - lastMoveM < CALM_M || current.shortPercent > ChargeChain.SHORT_OK) {
                proposal = null; return Decision(Kind.KEEP, chain, current, why) }
            // the other chain must hold with [HOLD] % more than the least levels (it is not taken to be left again soon)
            val held = onChain(bestChain, ownAhead, from, soc, scale, rules.copy(buffer = { km -> rules.buffer(km) + HOLD }))
            if (held.shortPercent > ChargeChain.SHORT_OK) { proposal = null; return Decision(Kind.KEEP, chain, current, why) }
            // and the next check must find the same next stop
            if (proposal != bestChain.firstOrNull()) {
                proposal = bestChain.firstOrNull()
                return Decision(Kind.KEEP, chain, current, why + "; another chain saves %.0f min, waiting for the next check".format(java.util.Locale.ROOT, saves))
            }
        }
        val old = chain
        old.firstOrNull()?.let { if (it !in bestChain) left += it }
        chain = bestChain; proposal = null; lastMoveM = drivenM; moves++
        planSoc = soc; planM = drivenM; planUsedBefore = 0.0
        lastCurrent = best
        return Decision(Kind.MOVE, bestChain, best, "$why; " + (if (urgent) "the next stop falls short by %.1f %%".format(java.util.Locale.ROOT, firstShort(current, drivenM))
            else "saves %.0f min".format(java.util.Locale.ROOT, saves)) +
            "; stops ${old.joinToString { name(it) }.ifEmpty { "none" }} -> ${bestChain.joinToString { name(it) }.ifEmpty { "none" }}", urgent)
    }

    /** The plan over [stops] (and the driver's [ownAhead]) from track index [from]: each stop may be left out. */
    private fun onChain(stops: List<Charger>, ownAhead: List<Charger>, from: Int, soc: Double, scale: Double, r: ChargePlanner.Rules) =
        ChargeChain.solve(track, ChargeCorridor.candidates(track, { _, _, _ -> emptyList() }, r, from, ownAhead, stops), car, soc, r, from, scale)

    /** How much [res] falls short of the least level (with its buffer) at its first stop, or at the destination without one. */
    private fun firstShort(res: ChargeChain.Result, drivenM: Double): Double {
        val l = res.links.firstOrNull()
        return if (l != null) (if (l.cand.mandatory) 0.0 else rules.atCharger + rules.buffer((l.cand.alongM - drivenM) / 1000) - l.arrive)
        else rules.atDestination + rules.buffer((track.metres.last() - drivenM) / 1000) - res.arrivePercent
    }

    /** The buffer at the first stop of [res] (or the destination). */
    private fun firstBuffer(res: ChargeChain.Result, drivenM: Double): Double =
        rules.buffer(((res.links.firstOrNull()?.cand?.alongM ?: track.metres.last()) - drivenM) / 1000)

    /** The plan of the last check (the levels to charge to at the stops ahead, for the card at the charger). */
    var lastCurrent: ChargeChain.Result? = null; private set

    /**
     * What to charge to at [c] standing there at [drivenM] with [soc] (the card at the charger): the plan from here over
     * the stops ahead, this one first, [HOLD] % above the least levels.
     */
    @Synchronized fun leaveAt(c: Charger, drivenM: Double, soc: Double): Double {
        val from = indexAt(drivenM)
        // with [HOLD] % on top: a little more charged now, so that a little more use on the way changes no stop
        val held = rules.copy(buffer = { km -> rules.buffer(km) + HOLD })
        val res = ChargeChain.solve(track, ChargeCorridor.candidates(track, { _, _, _ -> emptyList() }, held, from,
            listOf(c) + own.filter { alongM(it) > drivenM + PASSED_M }, chain.filter { it !== c && alongM(it) > drivenM + PASSED_M }),
            car, soc, held, from, scale(drivenM, soc))
        return res.links.firstOrNull { it.cand.charger === c }?.leave ?: soc
    }

    private fun name(c: Charger) = "%s %d kW at %.0f km".format(java.util.Locale.ROOT, c.network ?: "other", c.kw, alongM(c) / 1000)

    /** Where [c] lies along the present track. */
    fun alongM(c: Charger): Double {
        val t = track
        var best = 0; var bd = Double.MAX_VALUE
        for (i in 0 until t.size) { val d = ChargerIndex.distanceM(t.points[i], c.at); if (d < bd) { bd = d; best = i } }
        return t.metres[best]
    }

    companion object {
        const val CHECK_M = 5_000.0
        const val SAVE_MIN = 5.0
        const val CALM_M = 20_000.0
        /** Minutes another chain must save to go back to a stop given up before. */
        const val BACK_MIN = 15.0
        /** A level this much higher than at the last check: the car charged. */
        const val CHARGED_PERCENT = 3.0
        private const val PASSED_M = 500.0
        /** Percent more than the least levels another chain must keep before it is taken. */
        private const val HOLD = 1.5
        private const val MIN_USED = 2.0
        private const val TRUST_UP_KM = 30.0
        private const val TRUST_DOWN_KM = 60.0
        /** The level is read in whole percent, a few minutes old: about this much either way. */
        const val READING = 0.6
        /** The model's error on the way ahead before the drive has taught anything, and once it has. */
        const val REL_START = 0.12
        const val REL_LEARNED = 0.04
        private const val LOW_SCALE = 0.7
        private const val HIGH_SCALE = 1.5
    }
}
