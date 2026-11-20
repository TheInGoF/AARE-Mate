package net.prototux.aare.ui.car.navi

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import net.prototux.aare.R
import net.prototux.aare.host.templates.CarFormat
import net.prototux.aare.host.templates.maneuverVector

/**
 * Mate Navi's turn card (step 4, N3): looks like the turn card of the template navigation apps
 * (host/templates/NavigationUi.kt) - this turn large with its distance, the one after it small and quiet below - but
 * with plain text, outside the template host.
 */
@Composable
internal fun NaviTurnCard(info: RoutingInfo, nextGap: androidx.car.app.model.Distance?, modifier: Modifier,
                           exit: String? = null, lanes: List<NaviManeuver.LaneHint> = emptyList()) {
    val scheme = MaterialTheme.colorScheme
    val bg = scheme.primaryContainer
    val fg = scheme.onPrimaryContainer
    val step = info.currentStep ?: return
    Column(modifier.floatEdge(RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(bg)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            ManeuverArrow(step, 76.dp, fg)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    info.currentDistance?.let { d ->
                        Text(CarFormat.distance(d.displayDistance, d.displayUnit),
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 42.sp),
                            color = fg, maxLines = 1)
                    }
                    // the exit's number as on the sign at the motorway (F4), beside the distance so the sign's text keeps its width
                    exit?.let { n ->
                        Spacer(Modifier.width(14.dp))
                        Text(stringResource(R.string.mate_navi_exit, n), Modifier.border(2.dp, fg, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = fg, maxLines = 1)
                    }
                }
                Text(text(step), style = MaterialTheme.typography.titleLarge, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        // F4: the lanes, left to right as on the road; the ones to take bright, the others faint (as Google shows them)
        if (lanes.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.10f)).padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                lanes.forEachIndexed { i, l ->
                    if (i > 0) Box(Modifier.padding(horizontal = 4.dp).size(2.dp, 36.dp).background(fg.copy(alpha = 0.25f)))
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        l.types.distinct().forEach { t ->
                            val on = l.take && (l.takeType == null || l.takeType == t)
                            Icon(maneuverVector(t), null, tint = if (on) fg else fg.copy(alpha = 0.3f), modifier = Modifier.size(34.dp))
                        }
                    }
                }
            }
        }
        // N3: the turn after this one, small and quiet below the first
        info.nextStep?.let { next ->
            Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.16f)).heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val then = stringResource(R.string.nav_next_step_then)
                val lead = nextGap?.let { stringResource(R.string.mate_navi_next_in, then, CarFormat.distance(it.displayDistance, it.displayUnit)) } ?: then
                Text(lead, style = MaterialTheme.typography.labelLarge, color = fg.copy(alpha = 0.8f))
                Spacer(Modifier.width(12.dp))
                ManeuverArrow(next, 32.dp, fg)
                Spacer(Modifier.width(12.dp))
                // a long name wraps to a second line rather than losing its end (bauplan step 7: "abgeschnittener Text der
                // zweiten Abbiegung")
                Text(text(next), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun text(s: Step): String = s.cue?.toString()?.takeIf { it.isNotBlank() } ?: s.road?.toString().orEmpty()

@Composable
private fun ManeuverArrow(step: Step, size: Dp, fg: Color) {
    val m = step.maneuver
    Box(Modifier.size(size)) {
        if (!net.prototux.aare.host.templates.RoundaboutSign(m, size, fg))
            Icon(maneuverVector(m?.type ?: 0), null, tint = fg, modifier = Modifier.size(size))
        val exit = NaviManeuver.shownExit(m?.roundaboutExitNumber ?: 0)
        if (exit > 0 && size >= 48.dp) {
            Box(Modifier.align(Alignment.BottomEnd).size(size * 0.38f).clip(CircleShape).background(fg), contentAlignment = Alignment.Center) {
                Text(exit.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primaryContainer)
            }
        }
    }
}
