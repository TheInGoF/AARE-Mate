package net.prototux.aare.ui.car.navi

import net.prototux.aare.host.EnergyModel
import org.maplibre.spatialk.geojson.Position

/**
 * A route with its charging stops from few requests to the map server (Navi L-029 step E, N-016 step 5: "Ein
 * Server-Aufruf für die Route mit Stopps, danach lokal nachrechnen; nur bei Verfehlen korrigieren und einmal neu"). The
 * chain is found on the device ([ChargeChain]) on the route without stops; one request plans the route over its
 * chargers, which is then worked out again on the device. A further request only where it falls short (the way over
 * the chargers takes more than the plain route said) or where a stop is no longer needed on it; at most [maxCalls]
 * requests, the matrix for chargers off the route ([ChargeMatrix]) counted among them. A route the server refuses (400,
 * no answer in time) is tried over other chargers (the refused ones left out, those near them found by the chain as in
 * 4bc6451) - and where none goes, the plan's stops are shown on the route without them: never a long trip without stops.
 *
 * [R] is the route as the server gives it (Ferrostar's, a test's track); [route] plans one over chargers (in order, the
 * driver's own stops are the caller's), [trackOf] makes its track.
 */
internal class ChargeRouting<R>(
    private val route: suspend (List<Charger>) -> R,
    private val trackOf: (R) -> ChargePlanner.Track,
    private val matrix: (suspend (List<ChargeCorridor.Candidate>, ChargePlanner.Track) -> List<ChargeCorridor.Candidate>)? = null,
    private val maxCalls: Int = MAX_CALLS,
) {
    /**
     * What came out: the route to drive ([overStops]: planned over the stops, else the plain one), the plan on it, the
     * requests made, the chain's search (candidates, time on the device) and what happened, for the log.
     */
    class Outcome<R>(val route: R, val plan: ChargePlanner.Plan, val overStops: Boolean, val calls: Int, val candidates: Int,
                     val micros: Long, val notes: List<String>, val first: ChargeChain.Result)

    suspend fun plan(base: R, car: EnergyModel.Car, start: Double, near: (Position, Double, Int) -> List<Charger>,
                     rules: ChargePlanner.Rules, own: List<Charger> = emptyList(), routeIt: Boolean = true): Outcome<R> {
        val notes = ArrayList<String>()
        var calls = 0; var micros = 0L
        val baseTrack = trackOf(base)
        val refused = HashSet<Charger>()
        val exact = HashMap<Charger, ChargeCorridor.Candidate>()
        fun lookup(p: Position, r: Double, kw: Int) = near(p, r, kw).filter { it !in refused }
        fun chain(track: ChargePlanner.Track) = ChargeChain.plan(track, car, start, ::lookup, rules, mandatory = own, exact = exact)
            .also { micros += it.micros }
        var res = chain(baseTrack)
        val first = res
        fun out(r: R, plan: ChargePlanner.Plan, over: Boolean) = Outcome(r, plan, over, calls, first.candidates, micros, notes, first)
        val others = { r: ChargeChain.Result -> r.links.map { it.cand.charger }.filter { it !in own } }
        if (others(res).isEmpty() || !routeIt) return out(base, ChargePlanner.Plan(res.stops(), res.arrivePercent), false)

        // the real way to chargers well off the route, one request for all of the chain's (and asked only once)
        val far = res.links.map { it.cand }.filter { !it.exact && !it.mandatory && it.offM > ChargeCorridor.EXACT_FROM_M }
        val ask = matrix
        if (ask != null && far.isNotEmpty() && calls < maxCalls - 1) {
            calls++
            runCatching { ask(far, baseTrack) }.onSuccess { got ->
                got.filter { it.exact }.forEach { exact[it.charger] = it }
                notes += "matrix: " + got.joinToString { "%s %.1f km %.0f min".format(java.util.Locale.ROOT, it.charger.network ?: "other", it.detourM / 1000, it.detourS / 60) }
                res = chain(baseTrack)
            }.onFailure { notes += "matrix failed: ${it.message}" }
        }

        // the route over the chain's chargers; refused, the chain again without them
        var planned = others(res)
        var over: R? = null
        val baseS = baseTrack.seconds.lastOrNull() ?: 0.0
        // the best route over chargers seen so far, should every try make a long way round
        var best: Pair<R, List<Charger>>? = null; var bestExtra = Double.MAX_VALUE; var bestRes = res
        while (calls < maxCalls) {
            calls++
            over = try { route(planned) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                notes += "route over ${planned.joinToString { NaviTripPlan.chargerName(it) }} refused: ${e.message ?: e}"
                refused += planned
                res = chain(baseTrack)
                planned = others(res)
                if (planned.isEmpty()) break
                null
            }
            val o = over ?: continue
            // System I-093 (23.11.2026: "soll ich jetzt fast 7 Stunden fahren, weil er die komplette Strecke über Leipzig über
            // Land fährt, statt auf der Autobahn … dass er irgendwie nahe der Route bleibt, die er vorberechnet hat"): a way over
            // the chargers far longer than the plain route and the chain's detours together is no way to take - those
            // chargers out, the chain again with others near the route
            val extra = (trackOf(o).seconds.lastOrNull() ?: 0.0) - baseS
            val expected = res.links.filter { !it.cand.mandatory }.sumOf { it.cand.detourS }
            val allowed = maxOf(expected * 2 + DETOUR_SLACK_S, baseS * DETOUR_SHARE + DETOUR_SLACK_S)
            if (extra < bestExtra) { bestExtra = extra; best = o to planned; bestRes = res }
            if (extra <= allowed) break
            notes += "route over ${planned.joinToString { NaviTripPlan.chargerName(it) }} %.0f min longer than without stops (allowed %.0f) - planned again with others".format(
                java.util.Locale.ROOT, extra / 60, allowed / 60)
            refused += planned
            over = null
            res = chain(baseTrack)
            planned = others(res)
            if (planned.isEmpty()) break
        }
        // every try went a long way round: the least of them
        if (over == null && best != null) { over = best.first; planned = best.second; res = bestRes; notes += "kept the least way round (%.0f min longer)".format(java.util.Locale.ROOT, bestExtra / 60) }
        if (over == null) {
            notes += "no route over the chargers - the plan's stops shown on the route without them"
            return out(base, ChargePlanner.Plan(res.stops(), res.arrivePercent), false)
        }

        // worked out again on the route over the stops; once more where it falls short or a stop is not needed on it
        var r: R = over
        var timed = onRoute(trackOf(r), planned, car, start, rules, own)
        if (calls < maxCalls) {
            val short = timed.shortPercent > ChargeChain.SHORT_OK
            val loose = if (short) null else needless(trackOf(r), planned, timed, car, start, rules, own)
            val next: List<Charger>? = when {
                short -> {
                    // the chain again on the route as it really runs (the stops on it, others near it)
                    val again = chain(trackOf(r))
                    notes += "falls short by %.1f %% on the route over the stops, planned again: ".format(java.util.Locale.ROOT, timed.shortPercent) + again.describe()
                    others(again).takeIf { it != planned }
                }
                loose != null -> { notes += "stop at ${NaviTripPlan.chargerName(loose)} not needed on the route over the stops, left out"; planned - loose }
                else -> null
            }
            if (next != null && next.isNotEmpty()) {
                calls++
                runCatching { route(next) }.onSuccess { r2 ->
                    val t2 = onRoute(trackOf(r2), next, car, start, rules, own)
                    // the new one only where it is not worse
                    if (t2.shortPercent <= timed.shortPercent + ChargeChain.SHORT_OK) { r = r2; timed = t2; planned = next }
                }.onFailure { e -> if (e is kotlinx.coroutines.CancellationException) throw e; notes += "second route refused: ${e.message ?: e}" }
            }
        }
        if (timed.shortPercent > ChargeChain.SHORT_OK) notes += "falls short by %.1f %%".format(java.util.Locale.ROOT, timed.shortPercent)
        return out(r, ChargePlanner.Plan(timed.stops(), timed.arrivePercent), true)
    }

    /** The plan on a route planned over [chargers] (and the driver's [own]): each of them a stop, the levels chosen anew. */
    private fun onRoute(track: ChargePlanner.Track, chargers: List<Charger>, car: EnergyModel.Car, start: Double,
                        rules: ChargePlanner.Rules, own: List<Charger>): ChargeChain.Result {
        val all = chargers + own
        val cands = ChargeCorridor.candidates(track, { _, _, _ -> emptyList() }, rules, mandatory = all)
        return ChargeChain.solve(track, cands, car, start, rules)
    }

    /** A stop of [timed] charging only a little that the route can do without (16.11.2026: 13 -> 14 % before the end). */
    private fun needless(track: ChargePlanner.Track, chargers: List<Charger>, timed: ChargeChain.Result, car: EnergyModel.Car,
                         start: Double, rules: ChargePlanner.Rules, own: List<Charger>): Charger? =
        timed.links.filter { it.minutes < TINY_MIN && it.cand.charger in chargers }.firstOrNull { l ->
            onRoute(track, chargers - l.cand.charger, car, start, rules, own).shortPercent <= ChargeChain.SHORT_OK
        }?.cand?.charger

    companion object {
        const val MAX_CALLS = 3
        /** A way over the chargers may take this much more than the plain route beyond their detours (System I-093). */
        const val DETOUR_SLACK_S = 15 * 60.0
        const val DETOUR_SHARE = 0.08

        /**
         * The log line of a plan (L-029 Diagnose): route, km and start, candidates, the chain with arrival and departure at
         * each stop, the end against the least level and its buffer, the weather, the requests, the time on the device.
         */
        fun logLine(kind: String, km: Double, start: Double, plan: ChargePlanner.Plan, out: Outcome<*>, rules: ChargePlanner.Rules,
                    weather: String): String =
            "charging plan %s: %.0f km from %.0f %%, %d candidates, %s; %.0f %% at the end (least %.0f %% + buffer %.1f %%; at a charger %.0f %%, chargers from %d kW); %s; %d request(s)%s; %.1f ms on the device".format(
                java.util.Locale.ROOT, kind, km, start, out.candidates,
                if (plan.stops.isEmpty()) "no stop" else plan.stops.joinToString { "%s %d kW at %.0f km %.0f->%.0f %% %.0f min%s".format(java.util.Locale.ROOT,
                    it.charger.network ?: "other", it.charger.kw, it.atM / 1000, it.arrivePercent, it.leavePercent, it.chargeMinutes, if (it.overLimit) " over the limit" else "") },
                plan.arrivePercent, rules.atDestination, rules.buffer(km), rules.atCharger, rules.minKw,
                weather, out.calls, if (out.overStops) " (route over the stops)" else "", out.micros / 1000.0)
        private const val TINY_MIN = 5.0
    }
}
