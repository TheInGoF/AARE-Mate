package net.prototux.aare.ui.car.navi

import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import net.prototux.aare.ui.car.Mate
import kotlin.math.roundToInt

/**
 * The charging plan's state while driving (F3, O189), for the hint at the top right: the rolling planning
 * (ChargeRolling, Navi L-029 step F) finds the battery too low for the plan ([Verdict.TOO_LOW]) or has moved the stops
 * ([Verdict.MOVED]).
 */
internal object NaviChargeWatch {
    /** [MOVED]: the rolling planning (Navi L-029 step F) moved the stops; said once, no "Neu planen". */
    enum class Verdict { TOO_LOW, MOVED }

    /** What the check found and with how much the car would reach the next stop (or the destination). */
    class Result(val verdict: Verdict, val arrivePercent: Double, val text: String? = null)
}

/**
 * The hint of [NaviChargeWatch] at the top right, as ABRP and Google show it: what changed, "Neu planen", and a cross to
 * leave it. Slim, one line of text.
 */
@androidx.compose.runtime.Composable
internal fun NaviChargeHint(result: NaviChargeWatch.Result, modifier: androidx.compose.ui.Modifier, onReplan: () -> Unit, onDismiss: () -> Unit) {
    val low = result.verdict == NaviChargeWatch.Verdict.TOO_LOW
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    androidx.compose.foundation.layout.Row(modifier.floatEdge(shape).clip(shape).background(Mate.cardSolid)
        .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.EvStation, null,
            tint = if (low) Mate.warn else Mate.accent, modifier = androidx.compose.ui.Modifier.size(28.dp))
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(12.dp))
        androidx.compose.material3.Text(result.text ?: androidx.compose.ui.res.stringResource(
            net.prototux.aare.R.string.mate_navi_charge_low, result.arrivePercent.roundToInt().coerceAtLeast(0)),
            style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = Mate.text,
            modifier = androidx.compose.ui.Modifier.weight(1f, fill = false))
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(12.dp))
        if (result.verdict != NaviChargeWatch.Verdict.MOVED) androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(net.prototux.aare.R.string.mate_navi_replan),
            style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = Mate.onAccent,
            modifier = androidx.compose.ui.Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).background(Mate.accent)
                .clickable(onClick = onReplan).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 14.dp))
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Close, null, tint = Mate.textDim,
            modifier = androidx.compose.ui.Modifier.clip(androidx.compose.foundation.shape.CircleShape).clickable(onClick = onDismiss).padding(16.dp).size(26.dp))
    }
}

/** N-019 step 1: "Batterie jetzt vorheizen" before a fast charger - how long and about what it costs; a cross to leave it. */
@androidx.compose.runtime.Composable
internal fun NaviPreheatHint(advice: NaviPreheat.Advice, modifier: androidx.compose.ui.Modifier, onDismiss: () -> Unit) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    // three blinks as it comes (22.11.2026: "wenn das Batterie-Pop-Up vielleicht 3-mal blinken würde")
    val flash = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        repeat(3) {
            flash.animateTo(1f, androidx.compose.animation.core.tween(250))
            flash.animateTo(0f, androidx.compose.animation.core.tween(400))
        }
    }
    androidx.compose.foundation.layout.Row(modifier.floatEdge(shape).clip(shape)
        .background(androidx.compose.ui.graphics.lerp(Mate.cardSolid, Mate.accent, 0.55f * flash.value))
        .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Thermostat, null,
            tint = Mate.accent, modifier = androidx.compose.ui.Modifier.size(28.dp))
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(12.dp))
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.weight(1f, fill = false)) {
            androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(net.prototux.aare.R.string.mate_navi_preheat_title),
                style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = Mate.text)
            androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(net.prototux.aare.R.string.mate_navi_preheat_detail,
                advice.fromC.roundToInt(), advice.minutes.roundToInt(), String.format(java.util.Locale.getDefault(), "%.1f", advice.kwh), advice.targetC.roundToInt()),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = Mate.textDim)
        }
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(12.dp))
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Close, null, tint = Mate.textDim,
            modifier = androidx.compose.ui.Modifier.clip(androidx.compose.foundation.shape.CircleShape).clickable(onClick = onDismiss).padding(16.dp).size(26.dp))
    }
}

/**
 * At a planned charging stop (as ABRP shows it): the network's logo and name, what to charge to and about how long, and
 * what is left after it. Top right, a cross to leave it; it goes by itself once the car drives on.
 */
@androidx.compose.runtime.Composable
internal fun NaviAtCharger(stop: ChargePlanner.Stop, after: String?, modifier: androidx.compose.ui.Modifier, onDismiss: () -> Unit) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    androidx.compose.foundation.layout.Row(modifier.floatEdge(shape).clip(shape).background(Mate.cardSolid)
        .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        NetworkLogo(stop.charger.network, 36.dp)
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(14.dp))
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.weight(1f, fill = false)) {
            androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(net.prototux.aare.R.string.mate_navi_at_charger,
                stop.leavePercent.roundToInt(), stop.chargeMinutes.roundToInt().coerceAtLeast(1)),
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = Mate.text)
            androidx.compose.material3.Text(NaviTripPlan.chargerName(stop.charger) + (after?.let { " · $it" } ?: ""),
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = Mate.textDim, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Close, null, tint = Mate.textDim,
            modifier = androidx.compose.ui.Modifier.clip(androidx.compose.foundation.shape.CircleShape).clickable(onClick = onDismiss).padding(16.dp).size(26.dp))
    }
}

/**
 * F2 step 2: the next incident on the route within 10 km at the top right, as Google shows it: the warning sign, "Stau in
 * 3,2 km" with what it costs ("+6 min"), TomTom's words below. Goes by itself once passed.
 */
@androidx.compose.runtime.Composable
internal fun NaviTrafficHint(a: NaviTraffic.Ahead, metres: Double, modifier: androidx.compose.ui.Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    androidx.compose.foundation.layout.Row(modifier.floatEdge(shape).clip(shape).background(Mate.cardSolid)
        .padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        // its sign: road works as road works, a hazard as "!" (18.11.2026)
        TrafficSign(a.incident.kind, 36.dp)
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(14.dp))
        androidx.compose.foundation.layout.Column {
            val where = if (metres >= 1000) "%.1f km".format(metres / 1000) else "${(Math.round(metres / 50.0) * 50).coerceAtLeast(50)} m"
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(net.prototux.aare.R.string.traffic_hint, NaviTraffic.word(ctx, a.incident.kind), where),
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = Mate.text)
                a.incident.delayS?.takeIf { it >= 60 }?.let { d ->
                    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(10.dp))
                    androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(net.prototux.aare.R.string.traffic_hint_delay, Math.round(d / 60.0).toInt()),
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = Mate.warn)
                }
            }
            // TomTom's words only where they add something ("Gesperrt" under "Straße gesperrt" said it twice)
            val word = NaviTraffic.word(ctx, a.incident.kind).lowercase()
            val extra = a.incident.text.takeIf { t -> t.isNotBlank() && !word.contains(t.lowercase()) && !t.lowercase().contains(word) }
            if (extra != null) androidx.compose.material3.Text(extra, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = Mate.textDim, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

/**
 * F2: a faster route round a jam (O218), as Google offers it at the top right: "Schnellere Route · 7 min schneller",
 * "Wechseln", and a cross to stay on the current one.
 */
@androidx.compose.runtime.Composable
internal fun NaviFasterHint(minutes: Int, modifier: androidx.compose.ui.Modifier, onSwitch: () -> Unit, onDismiss: () -> Unit, closure: Boolean = false) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    androidx.compose.foundation.layout.Row(modifier.floatEdge(shape).clip(shape).background(Mate.cardSolid)
        .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Warning, null, tint = Mate.accent, modifier = androidx.compose.ui.Modifier.size(28.dp))
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(12.dp))
        androidx.compose.material3.Text(if (closure) androidx.compose.ui.res.stringResource(net.prototux.aare.R.string.traffic_closure_route)
            else androidx.compose.ui.res.stringResource(net.prototux.aare.R.string.traffic_faster, minutes),
            style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = Mate.text, modifier = androidx.compose.ui.Modifier.weight(1f, fill = false))
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(12.dp))
        androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(net.prototux.aare.R.string.traffic_switch),
            style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = Mate.onAccent,
            modifier = androidx.compose.ui.Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).background(Mate.accent)
                .clickable(onClick = onSwitch).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 14.dp))
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Close, null, tint = Mate.textDim,
            modifier = androidx.compose.ui.Modifier.clip(androidx.compose.foundation.shape.CircleShape).clickable(onClick = onDismiss).padding(16.dp).size(26.dp))
    }
}

/**
 * Off the route and no new one yet (17.11.2026, as Google: "Route wird neu berechnet"); when the map server cannot be
 * reached (no net) it says so - the old turn card would otherwise point the wrong way without a word.
 */
@androidx.compose.runtime.Composable
internal fun NaviRerouteHint(failing: Boolean, modifier: androidx.compose.ui.Modifier) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    androidx.compose.foundation.layout.Row(modifier.floatEdge(shape).clip(shape).background(Mate.cardSolid)
        .padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.material3.Icon(if (failing) androidx.compose.material.icons.Icons.Rounded.CloudOff else androidx.compose.material.icons.Icons.Rounded.Sync,
            null, tint = if (failing) Mate.warn else Mate.accent, modifier = androidx.compose.ui.Modifier.size(30.dp))
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(14.dp))
        androidx.compose.foundation.layout.Column {
            androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(if (failing) net.prototux.aare.R.string.mate_navi_reroute_offline else net.prototux.aare.R.string.mate_navi_reroute),
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = Mate.text)
            if (failing) androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(net.prototux.aare.R.string.mate_navi_reroute_offline_more),
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = Mate.textDim)
        }
    }
}
