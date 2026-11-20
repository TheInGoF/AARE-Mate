package net.prototux.aare.host.templates

import android.content.Context
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarText
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.navigation.model.Lane
import androidx.car.app.navigation.model.LaneDirection
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsBoat
import androidx.compose.material.icons.rounded.ForkLeft
import androidx.compose.material.icons.rounded.ForkRight
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.RampLeft
import androidx.compose.material.icons.rounded.RampRight
import androidx.compose.material.icons.rounded.RoundaboutLeft
import androidx.compose.material.icons.rounded.RoundaboutRight
import androidx.compose.material.icons.rounded.Straight
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material.icons.rounded.TurnLeft
import androidx.compose.material.icons.rounded.TurnRight
import androidx.compose.material.icons.rounded.TurnSharpLeft
import androidx.compose.material.icons.rounded.TurnSharpRight
import androidx.compose.material.icons.rounded.TurnSlightLeft
import androidx.compose.material.icons.rounded.TurnSlightRight
import androidx.compose.material.icons.rounded.UTurnLeft
import androidx.compose.material.icons.rounded.UTurnRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.prototux.aare.R
import net.prototux.aare.ui.theme.LocalCarDimens
import java.time.ZoneOffset
import java.util.Date
import java.util.TimeZone

/**
 * NavigationTemplate: the app's map, the turn card (maneuver, distance, cue, lanes, next step)
 * or a message card top start, the trip estimate bottom start, and the action strips.
 */
@Composable
internal fun NavigationTemplateView(t: NavigationTemplate) {
    MapScaffold(t.actionStrip, t.mapActionStrip, t.panModeDelegate, guiding = t.navigationInfo is RoutingInfo) {
        val info = t.navigationInfo
        val estimate = t.destinationTravelEstimate
        val width = if (narrow) maxWidth else panelWidth()
        // a narrow pane (a quarter or a third of the split view, 18.11.2026): the turn card would run under the app's
        // action strip at the top end - it goes below it then
        val clash = t.actionStrip != null && maxWidth - width < 260.dp
        // and keeps clear of the map's buttons along the end edge
        val top = Modifier.align(Alignment.TopStart).padding(top = if (clash) 92.dp else 0.dp)
            .width(if (clash && t.mapActionStrip != null) (maxWidth - 84.dp).coerceAtLeast(200.dp) else width)
        when (info) {
            is RoutingInfo -> RoutingCard(info, t.backgroundColor, top.obstructsMap())
            is MessageInfo -> MessageInfoCard(info, t.backgroundColor, top.obstructsMap())
        }
        if (estimate != null) {
            // narrow screens: leave room for the map action strip at the bottom end
            val w = if (narrow) maxWidth - 96.dp else width
            TravelEstimateCard(estimate, Modifier.align(Alignment.BottomStart).width(w).obstructsMap())
        }
    }
}

/** Colours of the turn card: the app's background colour, or our accent container. */
@Composable
private fun turnCardColors(background: CarColor?): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return if (background.isSet()) {
        val bg = carColor(background, scheme.primaryContainer)
        bg to contentColorOn(bg)
    } else {
        scheme.primaryContainer to scheme.onPrimaryContainer
    }
}

@Composable
private fun RoutingCard(info: RoutingInfo, background: CarColor?, modifier: Modifier) {
    val (bg, fg) = turnCardColors(background)
    val r = LocalCarDimens.current.cardRadius
    Column(modifier.shadow(8.dp, RoundedCornerShape(r)).clip(RoundedCornerShape(r)).background(bg)) {
        val step = info.currentStep
        if (info.isLoading || step == null) {
            Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(48.dp), color = fg, strokeWidth = 5.dp)
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            ManeuverIcon(step.maneuver, 76.dp, fg)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                info.currentDistance?.let { d ->
                    Text(
                        CarFormat.distance(d.displayDistance, d.displayUnit),
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 42.sp),
                        color = fg,
                        maxLines = 1,
                    )
                }
                val cue = if (!CarText.isNullOrEmpty(step.cue)) step.cue else step.road
                CarTextView(cue, style = MaterialTheme.typography.titleLarge, color = fg, maxLines = 2)
            }
        }
        val dim = Color.Black.copy(alpha = 0.16f)
        LanesStrip(step, fg, dim)
        info.junctionImage?.let { junction ->
            Box(Modifier.fillMaxWidth().background(dim).padding(12.dp), contentAlignment = Alignment.Center) {
                CarIconView(junction, 160.dp, widthFactor = 2f, shape = RoundedCornerShape(16.dp))
            }
        }
        info.nextStep?.let { next -> NextStepStrip(next, fg, dim) }
    }
}

@Composable
private fun LanesStrip(step: Step, fg: Color, bg: Color) {
    val lanesImage = step.lanesImage
    val lanes = step.lanes
    if (lanesImage == null && lanes.isEmpty()) return
    Box(Modifier.fillMaxWidth().background(bg).padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        if (lanesImage != null) {
            CarIconView(lanesImage, 44.dp, widthFactor = 6f, monochromeTint = fg)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { lanes.forEach { LaneIcon(it, fg) } }
        }
    }
}

@Composable
private fun LaneIcon(lane: Lane, fg: Color) {
    val directions = lane.directions
    val recommended = directions.firstOrNull { it.isRecommended }
    val shown = recommended ?: directions.firstOrNull()
    Icon(
        laneVector(shown?.shape ?: LaneDirection.SHAPE_STRAIGHT),
        contentDescription = null,
        tint = if (recommended != null) fg else fg.copy(alpha = 0.35f),
        modifier = Modifier.size(40.dp),
    )
}

@Composable
private fun NextStepStrip(next: Step, fg: Color, bg: Color) {
    Row(
        Modifier.fillMaxWidth().background(bg).heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.nav_next_step_then), style = MaterialTheme.typography.labelLarge, color = fg.copy(alpha = 0.8f))
        Spacer(Modifier.width(12.dp))
        ManeuverIcon(next.maneuver, 32.dp, fg)
        Spacer(Modifier.width(12.dp))
        CarTextView(if (!CarText.isNullOrEmpty(next.cue)) next.cue else next.road, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** The app's maneuver icon, or a Material arrow for the maneuver type (with roundabout exit). */
@Composable
private fun ManeuverIcon(maneuver: Maneuver?, size: Dp, fg: Color) {
    val icon = maneuver?.icon
    if (icon != null) {
        CarIconView(icon, size, monochromeTint = fg)
        return
    }
    Box(Modifier.size(size)) {
        if (!RoundaboutSign(maneuver, size, fg))
            Icon(maneuverVector(maneuver?.type ?: Maneuver.TYPE_UNKNOWN), null, tint = fg, modifier = Modifier.size(size))
        val exit = net.prototux.aare.ui.car.navi.NaviManeuver.shownExit(maneuver?.roundaboutExitNumber ?: 0)
        if (exit > 0 && size >= 48.dp) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(size * 0.38f).clip(CircleShape).background(fg),
                contentAlignment = Alignment.Center,
            ) {
                Text(exit.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = contentColorOn(fg))
            }
        }
    }
}

/** Whether [type] is a roundabout, and whether it is driven clockwise (where one drives on the left). */
internal fun roundaboutType(type: Int): Boolean? = when (type) {
    Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW, Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE,
    Maneuver.TYPE_ROUNDABOUT_ENTER_CW, Maneuver.TYPE_ROUNDABOUT_EXIT_CW -> true
    Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW, Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE,
    Maneuver.TYPE_ROUNDABOUT_ENTER_CCW, Maneuver.TYPE_ROUNDABOUT_EXIT_CCW -> false
    else -> null
}

/**
 * A roundabout with the exit the route takes (18.11.2026: "der Kreisverkehr geht eigentlich geradeaus rüber, aber er
 * zeigt an, dass ich rechts abbiegen soll"): entered from below, driven round the way the traffic goes (to the right
 * first where one drives on the right), left where the route leaves - straight on points up. Every roundabout is drawn
 * so (18.11.2026, again: "im Navi-Bereich oben links ... immer noch dieses Symbol, was links rum um den Kreisverkehr
 * führt, statt rechts rum" - without a known exit angle the Material symbol stood there, drawn for driving on the
 * left); false only where [maneuver] is no roundabout.
 */
@Composable
internal fun RoundaboutSign(maneuver: Maneuver?, size: Dp, fg: Color): Boolean {
    val clockwise = roundaboutType(maneuver?.type ?: return false) ?: return false
    RoundaboutGlyph(clockwise, maneuver.roundaboutExitAngle.takeIf { it in 1..360 }, size, fg)
    return true
}

/**
 * The roundabout drawn: the ring, the way in from below and, with [angle] (1..360, counted the way the traffic goes,
 * 180 straight on), the way out at that very angle (18.11.2026: "wenn wir feststellen, der Kreisverkehrwinkel ist 70 Grad,
 * dann wäre halt cool, wenn wir das ... in dem Kreisverkehr-Symbol wahrnehmen"); without it an arrow on the ring shows
 * which way round it goes.
 */
@Composable
internal fun RoundaboutGlyph(clockwise: Boolean, angle: Int?, size: Dp, fg: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.minDimension
        val c = androidx.compose.ui.geometry.Offset(this.size.width / 2, this.size.height / 2)
        val r = w * 0.20f
        val line = w * 0.085f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(line, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val box = androidx.compose.ui.geometry.Size(r * 2, r * 2)
        val topLeft = androidx.compose.ui.geometry.Offset(c.x - r, c.y - r)
        fun head(tip: androidx.compose.ui.geometry.Offset, dx: Float, dy: Float, len: Float) {
            val back = androidx.compose.ui.geometry.Offset(tip.x - dx * len, tip.y - dy * len)
            drawPath(androidx.compose.ui.graphics.Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(back.x - dy * len * 0.8f, back.y + dx * len * 0.8f)
                lineTo(back.x + dy * len * 0.8f, back.y - dx * len * 0.8f)
                close()
            }, fg)
        }
        // the whole ring quietly, the way in from below in full
        drawCircle(fg.copy(alpha = 0.32f), r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(line))
        drawLine(fg, androidx.compose.ui.geometry.Offset(c.x, c.y + w * 0.47f), androidx.compose.ui.geometry.Offset(c.x, c.y + r), line, androidx.compose.ui.graphics.StrokeCap.Round)
        if (angle == null) {
            // no exit known: a third of the ring the way the traffic goes, with an arrow on it
            val sweep = if (clockwise) 120f else -120f
            drawArc(fg, 90f, sweep, false, topLeft, box, style = stroke)
            val end = Math.toRadians((90f + sweep).toDouble())
            val tip = androidx.compose.ui.geometry.Offset(c.x + kotlin.math.cos(end).toFloat() * r, c.y + kotlin.math.sin(end).toFloat() * r)
            // along the ring: against the clock the angle falls
            val dx = (if (clockwise) -kotlin.math.sin(end) else kotlin.math.sin(end)).toFloat()
            val dy = (if (clockwise) kotlin.math.cos(end) else -kotlin.math.cos(end)).toFloat()
            head(androidx.compose.ui.geometry.Offset(tip.x + dx * w * 0.06f, tip.y + dy * w * 0.06f), dx, dy, w * 0.12f)
            return@Canvas
        }
        val a = net.prototux.aare.ui.car.navi.NaviRoundabout.drawn(angle).toFloat()
        // canvas angles run clockwise from the right: below is 90; driven against the clock they fall, with it they rise
        val sweep = if (clockwise) a else -a
        drawArc(fg, 90f, sweep, false, topLeft, box, style = stroke)
        val out = Math.toRadians((90f + sweep).toDouble())
        val dx = kotlin.math.cos(out).toFloat(); val dy = kotlin.math.sin(out).toFloat()
        val from = androidx.compose.ui.geometry.Offset(c.x + dx * r, c.y + dy * r)
        val tip = androidx.compose.ui.geometry.Offset(c.x + dx * w * 0.46f, c.y + dy * w * 0.46f)
        drawLine(fg, from, androidx.compose.ui.geometry.Offset(c.x + dx * w * 0.36f, c.y + dy * w * 0.36f), line, androidx.compose.ui.graphics.StrokeCap.Round)
        head(tip, dx, dy, w * 0.13f)
    }
}

/** A turn's symbol: Mate's own roundabout for every roundabout ([angle] 0 = exit not known), else Material's arrow. */
@Composable
internal fun TurnGlyph(type: Int, angle: Int, size: Dp, fg: Color) {
    val cw = roundaboutType(type)
    if (cw != null) RoundaboutGlyph(cw, angle.takeIf { it in 1..360 }, size, fg)
    else Icon(maneuverVector(type), null, tint = fg, modifier = Modifier.size(size))
}

internal fun maneuverVector(type: Int): ImageVector = when (type) {
    Maneuver.TYPE_STRAIGHT, Maneuver.TYPE_NAME_CHANGE -> Icons.Rounded.Straight
    Maneuver.TYPE_KEEP_LEFT, Maneuver.TYPE_TURN_SLIGHT_LEFT -> Icons.Rounded.TurnSlightLeft
    Maneuver.TYPE_KEEP_RIGHT, Maneuver.TYPE_TURN_SLIGHT_RIGHT -> Icons.Rounded.TurnSlightRight
    Maneuver.TYPE_TURN_NORMAL_LEFT -> Icons.Rounded.TurnLeft
    Maneuver.TYPE_TURN_NORMAL_RIGHT -> Icons.Rounded.TurnRight
    Maneuver.TYPE_TURN_SHARP_LEFT -> Icons.Rounded.TurnSharpLeft
    Maneuver.TYPE_TURN_SHARP_RIGHT -> Icons.Rounded.TurnSharpRight
    Maneuver.TYPE_U_TURN_LEFT, Maneuver.TYPE_ON_RAMP_U_TURN_LEFT -> Icons.Rounded.UTurnLeft
    Maneuver.TYPE_U_TURN_RIGHT, Maneuver.TYPE_ON_RAMP_U_TURN_RIGHT -> Icons.Rounded.UTurnRight
    // a ramp taken: the road going on faint, the arrow leaving it bold, as the exit sign on the motorway (19.11.2026: "Pfeil
    // zum Abfahren von der Autobahn ... ist nicht richtig" - Material's ramp showed a line joining the arrow from below,
    // which reads as a road merging in, not as leaving)
    Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT, Maneuver.TYPE_ON_RAMP_NORMAL_LEFT, Maneuver.TYPE_ON_RAMP_SHARP_LEFT,
    Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT, Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT,
    -> RAMP_LEFT
    Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT, Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT, Maneuver.TYPE_ON_RAMP_SHARP_RIGHT,
    Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT, Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT,
    -> RAMP_RIGHT
    Maneuver.TYPE_FORK_LEFT -> Icons.Rounded.ForkLeft
    Maneuver.TYPE_FORK_RIGHT -> Icons.Rounded.ForkRight
    Maneuver.TYPE_MERGE_LEFT, Maneuver.TYPE_MERGE_RIGHT, Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED -> Icons.Rounded.Merge
    // clockwise roundabouts are driven on the left (UK, Japan...)
    Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW, Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE,
    Maneuver.TYPE_ROUNDABOUT_ENTER_CW, Maneuver.TYPE_ROUNDABOUT_EXIT_CW,
    -> Icons.Rounded.RoundaboutLeft
    Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW, Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE,
    Maneuver.TYPE_ROUNDABOUT_ENTER_CCW, Maneuver.TYPE_ROUNDABOUT_EXIT_CCW,
    -> Icons.Rounded.RoundaboutRight
    Maneuver.TYPE_FERRY_BOAT, Maneuver.TYPE_FERRY_BOAT_LEFT, Maneuver.TYPE_FERRY_BOAT_RIGHT -> Icons.Rounded.DirectionsBoat
    Maneuver.TYPE_FERRY_TRAIN, Maneuver.TYPE_FERRY_TRAIN_LEFT, Maneuver.TYPE_FERRY_TRAIN_RIGHT -> Icons.Rounded.Train
    Maneuver.TYPE_DESTINATION, Maneuver.TYPE_DESTINATION_STRAIGHT, Maneuver.TYPE_DESTINATION_LEFT,
    Maneuver.TYPE_DESTINATION_RIGHT,
    -> Icons.Rounded.Place
    else -> Icons.Rounded.Navigation // DEPART, UNKNOWN
}

private fun laneVector(shape: Int): ImageVector = when (shape) {
    LaneDirection.SHAPE_SLIGHT_LEFT -> Icons.Rounded.TurnSlightLeft
    LaneDirection.SHAPE_SLIGHT_RIGHT -> Icons.Rounded.TurnSlightRight
    LaneDirection.SHAPE_NORMAL_LEFT -> Icons.Rounded.TurnLeft
    LaneDirection.SHAPE_NORMAL_RIGHT -> Icons.Rounded.TurnRight
    LaneDirection.SHAPE_SHARP_LEFT -> Icons.Rounded.TurnSharpLeft
    LaneDirection.SHAPE_SHARP_RIGHT -> Icons.Rounded.TurnSharpRight
    LaneDirection.SHAPE_U_TURN_LEFT -> Icons.Rounded.UTurnLeft
    LaneDirection.SHAPE_U_TURN_RIGHT -> Icons.Rounded.UTurnRight
    else -> Icons.Rounded.Straight
}

@Composable
private fun MessageInfoCard(info: MessageInfo, background: CarColor?, modifier: Modifier) {
    val (bg, fg) = turnCardColors(background)
    val r = LocalCarDimens.current.cardRadius
    Row(
        modifier.shadow(8.dp, RoundedCornerShape(r)).clip(RoundedCornerShape(r)).background(bg).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        info.image?.let {
            CarIconView(it, 64.dp, monochromeTint = fg)
            Spacer(Modifier.width(18.dp))
        }
        Column(Modifier.weight(1f)) {
            CarTextView(info.title, style = MaterialTheme.typography.titleLarge, color = fg, maxLines = 2)
            CarTextView(info.text, style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.85f), maxLines = 3)
        }
    }
}

/** Arrival time, remaining time and distance, trip text: one calm line of glanceable facts. */
@Composable
private fun TravelEstimateCard(estimate: TravelEstimate, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val r = LocalCarDimens.current.cardRadius
    val arrival = remember(estimate.arrivalTimeAtDestination) { estimate.arrivalTimeAtDestination?.let { arrivalText(it, context) } }
    val remaining = CarFormat.duration(estimate.remainingTimeSeconds)
    val distance = estimate.remainingDistance?.let { CarFormat.distance(it.displayDistance, it.displayUnit) }
    Row(
        modifier.shadow(8.dp, RoundedCornerShape(r)).clip(RoundedCornerShape(r)).background(scheme.surfaceContainer)
            .heightIn(min = LocalCarDimens.current.touchTarget).padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Arrival, time and distance first, in full: they are what the card is for. The app's trip text (ABRP: the next
        // charging stop) takes what room is left and is cut short - it took the whole card before, and the arrival
        // came out as "2" and the time as "1" (seen in the desk window, O154).
        Column(Modifier.width(IntrinsicSize.Max)) {
            if (arrival != null) {
                Text(arrival, style = MaterialTheme.typography.headlineSmall, color = scheme.onSurface, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (remaining != null) {
                    Text(remaining, style = MaterialTheme.typography.titleMedium, color = carColor(estimate.remainingTimeColor, scheme.primary), maxLines = 1)
                }
                if (remaining != null && distance != null) {
                    Text("  ·  ", style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant)
                }
                if (distance != null) {
                    Text(distance, style = MaterialTheme.typography.titleMedium, color = carColor(estimate.remainingDistanceColor, scheme.onSurfaceVariant), maxLines = 1)
                }
            }
        }
        val tripText = estimate.tripText
        val tripIcon = estimate.tripIcon
        if (tripIcon != null || tripText != null) {
            Spacer(Modifier.width(12.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                tripIcon?.let { CarIconView(it, 32.dp, monochromeTint = scheme.onSurfaceVariant) }
                if (tripIcon != null && tripText != null) Spacer(Modifier.width(8.dp))
                CarTextView(tripText, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, maxLines = 2)
            }
        }
    }
}

/** "14:32", or "14:32 CET" when the destination is in another time zone than the phone. */
private fun arrivalText(dt: DateTimeWithZone, context: Context): String {
    val millis = dt.timeSinceEpochMillis
    val zone = TimeZone.getTimeZone(ZoneOffset.ofTotalSeconds(dt.zoneOffsetSeconds))
    val text = net.prototux.aare.ui.Clock.format(millis, zone)
    val local = TimeZone.getDefault().getOffset(millis) == dt.zoneOffsetSeconds * 1000
    return if (local) text else "$text ${dt.zoneShortName}"
}

/**
 * Taking a ramp to the right (an exit, or onto a slip road): the road going straight on, faint, and the bold arrow
 * branching off up to the right from it - as the German exit sign draws it. [RAMP_LEFT] mirrored.
 */
internal val RAMP_RIGHT: ImageVector by lazy { rampVector(false) }
internal val RAMP_LEFT: ImageVector by lazy { rampVector(true) }

private fun rampVector(left: Boolean): ImageVector {
    fun x(v: Float) = if (left) 24f - v else v
    val white = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.White)
    val round = androidx.compose.ui.graphics.StrokeCap.Round
    val join = androidx.compose.ui.graphics.StrokeJoin.Round
    return ImageVector.Builder(if (left) "RampLeftMate" else "RampRightMate", 24.dp, 24.dp, 24f, 24f).apply {
        // the road going on
        addPath(androidx.compose.ui.graphics.vector.PathBuilder().apply { moveTo(x(9f), 13f); lineTo(x(9f), 3f) }.nodes,
            stroke = white, strokeAlpha = 0.45f, strokeLineWidth = 2f, strokeLineCap = round)
        // the way taken: up from below, then off to the side
        addPath(androidx.compose.ui.graphics.vector.PathBuilder().apply {
            moveTo(x(9f), 21f); lineTo(x(9f), 14f); curveTo(x(9f), 11f, x(10.5f), 9f, x(13f), 7.3f); lineTo(x(15.4f), 5.7f) }.nodes,
            stroke = white, strokeLineWidth = 2.6f, strokeLineCap = round, strokeLineJoin = join)
        // its head, pointing on up to the side
        addPath(androidx.compose.ui.graphics.vector.PathBuilder().apply {
            moveTo(x(19f), 3.2f); lineTo(x(17.6f), 8.6f); lineTo(x(13.6f), 3.6f); close() }.nodes, fill = white)
    }.build()
}
