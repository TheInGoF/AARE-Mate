package net.prototux.aare.ui.car.navi

import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import uniffi.ferrostar.DrivingSide
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverModifier.LEFT
import uniffi.ferrostar.ManeuverModifier.RIGHT
import uniffi.ferrostar.ManeuverModifier.SHARP_LEFT
import uniffi.ferrostar.ManeuverModifier.SHARP_RIGHT
import uniffi.ferrostar.ManeuverModifier.SLIGHT_LEFT
import uniffi.ferrostar.ManeuverModifier.SLIGHT_RIGHT
import uniffi.ferrostar.ManeuverModifier.STRAIGHT
import uniffi.ferrostar.ManeuverModifier.U_TURN
import uniffi.ferrostar.ManeuverType
import uniffi.ferrostar.RouteStep
import uniffi.ferrostar.TripState
import uniffi.ferrostar.VisualInstructionContent

/**
 * Ferrostar's guidance in the Car App Library's terms (step 4 of projekt/navi-app_v0.1.md): the same maneuver types
 * Mate's template navigation draws (host/templates/NavigationUi.kt: one turn card for every navigation app) and hands
 * to the car's cluster and head-up display (car/NavStatusBridge.kt, step 8). The first turn large, the one after it
 * small below (N3).
 */
internal object NaviManeuver {

    /** The turn card: this turn with its distance, the turn after it, and how far that one lies behind this one. */
    class Card(val info: RoutingInfo, val nextGap: Distance?, val exit: String? = null, val lanes: List<LaneHint> = emptyList())

    /** One lane before a junction (F4): the arrows painted on it, and whether it is one of the lanes to take. */
    class LaneHint(val types: List<Int>, val take: Boolean, val takeType: Int?)

    /**
     * N3 (16.11.2026): the turn after this one only while this one is at most 5 km away; and when it follows within
     * 500 m, with that distance ("Danach in 300 m").
     */
    fun card(trip: TripState.Navigating): Card? = runCatching { cardOrThrow(trip) }
        .onFailure { android.util.Log.w("MateNavi", "turn card: $it") }.getOrNull()

    private fun cardOrThrow(trip: TripState.Navigating): Card? {
        val far = trip.progress.distanceToNextManeuver > NEXT_HIDDEN_BEYOND_M
        val info = routingInfo(trip, withNext = !far) ?: return null
        val gap = trip.remainingSteps.getOrNull(1)?.distance
        // F4 like Google: the motorway exit's number beside the road, and the lanes as the junction comes near (Valhalla
        // sends them in the instruction's sub-content shortly before it)
        val vi = trip.visualInstruction
        val exit = vi?.primaryContent?.exitNumbers?.firstOrNull { it.isNotBlank() }
        // only where there is a choice (as Google): two lanes at least, not all of them to take - one lane, or every lane,
        // would only repeat the turn's own arrow (16.11.2026: "links ist doch doppelt oder ?")
        val lanes = vi?.subContent?.laneInfo.orEmpty().map { l ->
            LaneHint(l.directions.map(::laneType), l.active, l.activeDirection?.let(::laneType))
        }.takeIf { it.size >= 2 && it.any { l -> !l.take } }.orEmpty()
        return Card(info, if (!far && gap != null && gap <= NEXT_GAP_SHOWN_UP_TO_M) distance(gap) else null, exit, lanes)
    }

    /** A lane arrow's words as the Car App Library's lane shape (for the car's displays). */
    fun laneShape(d: String): Int = when (d.trim().lowercase()) {
        "left" -> androidx.car.app.navigation.model.LaneDirection.SHAPE_NORMAL_LEFT
        "right" -> androidx.car.app.navigation.model.LaneDirection.SHAPE_NORMAL_RIGHT
        "slight left" -> androidx.car.app.navigation.model.LaneDirection.SHAPE_SLIGHT_LEFT
        "slight right" -> androidx.car.app.navigation.model.LaneDirection.SHAPE_SLIGHT_RIGHT
        "sharp left" -> androidx.car.app.navigation.model.LaneDirection.SHAPE_SHARP_LEFT
        "sharp right" -> androidx.car.app.navigation.model.LaneDirection.SHAPE_SHARP_RIGHT
        "uturn" -> androidx.car.app.navigation.model.LaneDirection.SHAPE_U_TURN_LEFT
        else -> androidx.car.app.navigation.model.LaneDirection.SHAPE_STRAIGHT
    }

    /** A lane arrow's words (OSRM: "left", "slight right", "straight", "uturn" …) as a maneuver type for its picture. */
    fun laneType(d: String): Int = when (d.trim().lowercase()) {
        "left" -> Maneuver.TYPE_TURN_NORMAL_LEFT
        "right" -> Maneuver.TYPE_TURN_NORMAL_RIGHT
        "slight left" -> Maneuver.TYPE_TURN_SLIGHT_LEFT
        "slight right" -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
        "sharp left" -> Maneuver.TYPE_TURN_SHARP_LEFT
        "sharp right" -> Maneuver.TYPE_TURN_SHARP_RIGHT
        "uturn" -> Maneuver.TYPE_U_TURN_LEFT
        else -> Maneuver.TYPE_STRAIGHT
    }

    private const val NEXT_HIDDEN_BEYOND_M = 5000.0
    private const val NEXT_GAP_SHOWN_UP_TO_M = 500.0

    /**
     * The banner of a step for [toManeuverM] metres before its manoeuvre (17.11.2026, the car's log: for ten minutes on
     * the A 4 the head-up display got "Ausfahrt 62" and "Eisenach" by turns, every second): Valhalla gives a step several
     * banners, each from its distance on; Ferrostar's own choice came and went. Now it is the banner whose distance has
     * been reached last - the same answer for the same place, nothing flapping. Before the first one: the farthest.
     */
    fun banner(step: uniffi.ferrostar.RouteStep, toManeuverM: Double): uniffi.ferrostar.VisualInstruction? {
        val all = step.visualInstructions
        if (all.isEmpty()) return null
        return all.filter { it.triggerDistanceBeforeManeuver >= toManeuverM }.minByOrNull { it.triggerDistanceBeforeManeuver }
            ?: all.maxByOrNull { it.triggerDistanceBeforeManeuver }
    }

    /** The turn card's content: this step with the distance to it, and the step after it. */
    fun routingInfo(trip: TripState.Navigating, withNext: Boolean = true): RoutingInfo? {
        val steps = trip.remainingSteps
        val current = steps.firstOrNull() ?: return null
        val now = banner(current, trip.progress.distanceToNextManeuver)?.primaryContent ?: trip.visualInstruction?.primaryContent ?: return null
        val b = RoutingInfo.Builder().setCurrentStep(step(now, current, after = steps.drop(1)), distance(trip.progress.distanceToNextManeuver))
        // the turn after this one: the next step's first instruction (N3: small, below the first)
        if (withNext) steps.getOrNull(1)?.let { next ->
            next.visualInstructions.firstOrNull()?.primaryContent?.let { b.setNextStep(step(it, next, after = steps.drop(2))) }
        }
        return b.build()
    }

    /** [cue]: what the car shows beside the arrow where the road is not named (the head-up display: "Ausfahrt 25"). */
    fun step(c: VisualInstructionContent, s: RouteStep, cue: String = c.text, road: String? = s.roadName,
             lanes: List<uniffi.ferrostar.LaneInfo> = emptyList(), after: List<RouteStep> = emptyList()): Step {
        // the exit's number: Ferrostar's field, else from Valhalla's words ("die 6. Ausfahrt nehmen", "take the 2nd exit"),
        // written or spoken
        val known = s.roundaboutExitNumber?.toInt()?.takeIf { it > 0 } ?: exitFromText(c.text) ?: exitFromText(s.instruction)
            ?: s.spokenInstructions.firstNotNullOfOrNull { exitFromText(it.text) } ?: 0
        var t = type(c, s.drivingSide)
        val round = t == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW || t == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW
        // where the roundabout is left, from the route's own way ([after]: the steps behind [s] - the roundabout itself,
        // then the road away from it): the symbol and the car's displays show that exit, not always the one to the right
        val angle = if (round) {
            fun pts(r: RouteStep?) = r?.geometry.orEmpty().map { it.lat to it.lng }
            NaviRoundabout.exitAngle(pts(s), pts(after.getOrNull(0)), pts(after.getOrNull(1)), t == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW)
        } else null
        // 18.11.2026 (I-023, "Kreisverkehr im Head-up-Display nicht gezeigt"; the drive's log: sent as "enter the roundabout",
        // type 45, without an exit): where no exit number is told, the roundabout went to the car as a mere "enter" and
        // the car had nothing to draw. With the exit's angle known it is told as a roundabout with that exit all the same,
        // its number marked as not known ([EXIT_UNKNOWN]; the library wants one) and shown nowhere.
        val exit = if (known > 0) known else if (round && angle != null) EXIT_UNKNOWN else 0
        // "enter and exit" needs the exit's number (the library throws without it): without one, just "enter"
        if (exit <= 0) t = when (t) {
            Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW -> Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
            Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW -> Maneuver.TYPE_ROUNDABOUT_ENTER_CW
            else -> t
        }
        if (angle != null && exit > 0) t = if (t == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW) Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE
            else Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE
        val m = Maneuver.Builder(t)
        if (exit > 0 && isRoundabout(c.maneuverType)) m.setRoundaboutExitNumber(exit)
        if (angle != null && exit > 0) m.setRoundaboutExitAngle(angle)
        val b = Step.Builder(cue).setManeuver(m.build())
        road?.takeIf { it.isNotBlank() && it != cue }?.let { b.setRoad(it) }
        // the lanes for the car's displays (step 8), only where there is a choice, as on the turn card; the library wants
        // an image with them - the car draws its own arrows from the lanes
        if (lanes.size >= 2 && lanes.any { !it.active }) {
            lanes.forEach { l ->
                val lane = androidx.car.app.navigation.model.Lane.Builder()
                l.directions.forEach { d -> lane.addDirection(androidx.car.app.navigation.model.LaneDirection.create(laneShape(d), l.active && d == (l.activeDirection ?: d))) }
                if (l.directions.isEmpty()) lane.addDirection(androidx.car.app.navigation.model.LaneDirection.create(androidx.car.app.navigation.model.LaneDirection.SHAPE_STRAIGHT, l.active))
                b.addLane(lane.build())
            }
            b.setLanesImage(androidx.car.app.model.CarIcon.APP_ICON)
        }
        return b.build()
    }

    /** A roundabout's exit number that is not known (the route's words name none): never shown or told to the car. */
    const val EXIT_UNKNOWN = 99

    /** [n] as an exit number to show, or 0 for none or [EXIT_UNKNOWN]. */
    fun shownExit(n: Int): Int = if (n in 1..20) n else 0

    private val EXIT_WORDS = Regex("(\\d+)\\.?\\s*(?:st|nd|rd|th)?\\s*(?:Ausfahrt|exit)", RegexOption.IGNORE_CASE)

    /** The exit number in an instruction, e.g. 6 in "Auf Großer Stern fahren und die 6. Ausfahrt nehmen." */
    fun exitFromText(text: String?): Int? = text?.let { EXIT_WORDS.find(it)?.groupValues?.get(1)?.toIntOrNull() }?.takeIf { it in 1..20 }

    private fun isRoundabout(t: ManeuverType?) =
        t == ManeuverType.ROUNDABOUT || t == ManeuverType.ROTARY || t == ManeuverType.ROUNDABOUT_TURN

    /** Ferrostar's type and modifier (OSRM's) as one Car App maneuver type; roundabouts by the side one drives on. */
    fun type(c: VisualInstructionContent, side: DrivingSide?): Int {
        val mod = c.maneuverModifier
        val left = side == DrivingSide.LEFT
        return when (c.maneuverType) {
            ManeuverType.DEPART -> Maneuver.TYPE_DEPART
            ManeuverType.ARRIVE -> when (mod) {
                LEFT, SLIGHT_LEFT, SHARP_LEFT -> Maneuver.TYPE_DESTINATION_LEFT
                RIGHT, SLIGHT_RIGHT, SHARP_RIGHT -> Maneuver.TYPE_DESTINATION_RIGHT
                else -> Maneuver.TYPE_DESTINATION
            }
            ManeuverType.MERGE -> when (mod) {
                LEFT, SLIGHT_LEFT, SHARP_LEFT -> Maneuver.TYPE_MERGE_LEFT
                RIGHT, SLIGHT_RIGHT, SHARP_RIGHT -> Maneuver.TYPE_MERGE_RIGHT
                else -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
            }
            ManeuverType.ON_RAMP -> when (mod) {
                SLIGHT_LEFT -> Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT
                LEFT -> Maneuver.TYPE_ON_RAMP_NORMAL_LEFT
                SHARP_LEFT -> Maneuver.TYPE_ON_RAMP_SHARP_LEFT
                SLIGHT_RIGHT -> Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT
                SHARP_RIGHT -> Maneuver.TYPE_ON_RAMP_SHARP_RIGHT
                U_TURN -> if (left) Maneuver.TYPE_ON_RAMP_U_TURN_RIGHT else Maneuver.TYPE_ON_RAMP_U_TURN_LEFT
                else -> Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
            }
            ManeuverType.OFF_RAMP -> when (mod) {
                SLIGHT_LEFT -> Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT
                LEFT, SHARP_LEFT -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
                SLIGHT_RIGHT -> Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT
                else -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
            }
            // a fork taken slightly is "keep left/right" - the lane choice ("einordnen") the head-up display can show
            ManeuverType.FORK -> when (mod) {
                SLIGHT_LEFT -> Maneuver.TYPE_KEEP_LEFT
                SLIGHT_RIGHT -> Maneuver.TYPE_KEEP_RIGHT
                LEFT, SHARP_LEFT -> Maneuver.TYPE_FORK_LEFT
                else -> Maneuver.TYPE_FORK_RIGHT
            }
            ManeuverType.ROUNDABOUT, ManeuverType.ROTARY, ManeuverType.ROUNDABOUT_TURN ->
                if (left) Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW else Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
            ManeuverType.EXIT_ROUNDABOUT, ManeuverType.EXIT_ROTARY ->
                if (left) Maneuver.TYPE_ROUNDABOUT_EXIT_CW else Maneuver.TYPE_ROUNDABOUT_EXIT_CCW
            ManeuverType.NEW_NAME -> Maneuver.TYPE_NAME_CHANGE
            else -> turn(mod, left)  // TURN, CONTINUE, END_OF_ROAD, NOTIFICATION, unknown
        }
    }

    private fun turn(mod: ManeuverModifier?, left: Boolean) = when (mod) {
        SLIGHT_LEFT -> Maneuver.TYPE_TURN_SLIGHT_LEFT
        LEFT -> Maneuver.TYPE_TURN_NORMAL_LEFT
        SHARP_LEFT -> Maneuver.TYPE_TURN_SHARP_LEFT
        SLIGHT_RIGHT -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
        RIGHT -> Maneuver.TYPE_TURN_NORMAL_RIGHT
        SHARP_RIGHT -> Maneuver.TYPE_TURN_SHARP_RIGHT
        U_TURN -> if (left) Maneuver.TYPE_U_TURN_RIGHT else Maneuver.TYPE_U_TURN_LEFT
        STRAIGHT, null -> Maneuver.TYPE_STRAIGHT
    }

    /** Distances as one reads them while driving: 10 m steps near, 50 m further, km with a decimal below 10 km. */
    fun distance(meters: Double): Distance = when {
        meters < 300 -> Distance.create((Math.round(meters / 10) * 10).toDouble(), Distance.UNIT_METERS)
        meters < 1000 -> Distance.create((Math.round(meters / 50) * 50).toDouble(), Distance.UNIT_METERS)
        meters < 10_000 -> Distance.create(Math.round(meters / 100) / 10.0, Distance.UNIT_KILOMETERS_P1)
        else -> Distance.create(Math.round(meters / 1000).toDouble(), Distance.UNIT_KILOMETERS)
    }
}
