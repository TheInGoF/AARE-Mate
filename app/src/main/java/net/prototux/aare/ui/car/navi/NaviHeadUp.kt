package net.prototux.aare.ui.car.navi

import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import uniffi.ferrostar.TripState
import java.util.TimeZone

/**
 * Mate Navi's guidance to the car's own displays (bauplan step 8, H1: instrument cluster and head-up display - the ID.7
 * shows Android Auto's turn there): the next turns with their distances, the motorway exit's number beside the arrow
 * ("Ausfahrt 25"), the destination with the arrival. Built as the Car App Library's trip, the same the hosted navigation
 * apps send, and given to the car's navigation status by [sink] (CarSession; null while no car is connected).
 */
internal object NaviHeadUp {
    @Volatile var sink: ((Trip?) -> Unit)? = null

    /**
     * The trip for the car: up to [STEPS] turns ahead, each with the way to it; [destination] with the time to it,
     * [extraS] the charging still ahead counted in. [exitWord] makes "Ausfahrt 25" of an exit's number.
     */
    fun trip(t: TripState.Navigating, destination: String, extraS: Double, nowMillis: Long, exitWord: (String) -> String): Trip? {
        val steps = t.remainingSteps
        if (steps.isEmpty()) return null
        val zone = TimeZone.getDefault()
        val b = Trip.Builder()
        var ahead = t.progress.distanceToNextManeuver
        var aheadS = 0.0
        for ((i, s) in steps.take(STEPS).withIndex()) {
            // the current step's banner for where the car is (no flapping between two, see NaviManeuver.banner)
            // 17.11.2026 (Ingo: "Autobahn abfahren, rechts abbiegen - die zwei nächsten Anweisungen ... sollte er nur im
            // Navi-Display anzeigen und nicht im Head-up-Display hin und her wechseln"): a current step without a banner
            // at that moment was left out, and the next turn took its place in the car's displays. Now no trip is sent
            // then - the car keeps the current turn.
            val c = (if (i == 0) NaviManeuver.banner(s, t.progress.distanceToNextManeuver)?.primaryContent ?: t.visualInstruction?.primaryContent else null)
                ?: s.visualInstructions.maxByOrNull { it.triggerDistanceBeforeManeuver }?.primaryContent
                ?: if (i == 0) return null else continue
            // each turn is at the end of its step: the next one after the rest of this step and the whole steps between
            if (i > 0) { ahead += s.distance; aheadS += s.duration }
            val exit = if (i == 0) c.exitNumbers?.firstOrNull { it.isNotBlank() } else s.visualInstructions.firstOrNull()?.primaryContent?.exitNumbers?.firstOrNull { it.isNotBlank() }
            // the road turned into is the next step's (a step's own name is the road driven along it: the car showed
            // "Potsdamer Platz" for the turn into the Ben-Gurion-Straße); the instruction's text names it too
            val into = steps.getOrNull(i + 1)?.roadName?.takeIf { it.isNotBlank() } ?: c.text
            val lanes = if (i == 0) (NaviManeuver.banner(s, t.progress.distanceToNextManeuver) ?: t.visualInstruction)?.subContent?.laneInfo.orEmpty() else emptyList()
            val step = NaviManeuver.step(c, s, exit?.let(exitWord) ?: c.text, into, lanes, steps.drop(i + 1))
            val secs = if (i == 0) stepSeconds(t) else aheadS + stepSeconds(t)
            b.addStep(step, estimate(ahead, secs, nowMillis, zone))
        }
        val destS = t.progress.durationRemaining + extraS
        b.addDestination(Destination.Builder().setName(destination.ifBlank { " " }).build(),
            estimate(t.progress.distanceRemaining, destS, nowMillis, zone))
        steps.firstOrNull()?.roadName?.takeIf { it.isNotBlank() }?.let { b.setCurrentRoad(it) }
        return b.build()
    }

    /** The time to the next turn at the pace of the step driven now. */
    private fun stepSeconds(t: TripState.Navigating): Double {
        val s = t.remainingSteps.firstOrNull() ?: return 0.0
        return if (s.distance > 0) s.duration * (t.progress.distanceToNextManeuver / s.distance) else 0.0
    }

    private fun estimate(metres: Double, seconds: Double, nowMillis: Long, zone: TimeZone): TravelEstimate =
        TravelEstimate.Builder(NaviManeuver.distance(metres), DateTimeWithZone.create(nowMillis + (seconds * 1000).toLong(), zone))
            .setRemainingTimeSeconds(seconds.toLong().coerceAtLeast(0)).build()

    private const val STEPS = 3
}
