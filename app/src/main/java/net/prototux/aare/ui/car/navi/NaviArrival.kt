package net.prototux.aare.ui.car.navi

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.prototux.aare.R
import net.prototux.aare.ui.car.Mate
import java.util.Locale
import kotlin.math.roundToInt

/** What the finished trip took: shown in the arrival card. */
internal class TripDone(val minutes: Double, val km: Double, val kwh: Double?, val estimated: Boolean, val name: String? = null) {
    val avgKmh get() = if (minutes > 0) km / (minutes / 60.0) else 0.0
    val kwh100 get() = kwh?.let { if (km > 0) it / km * 100 else null }
}

/**
 * The litres of each fuel that hold the energy [kwh] (each its own energy per litre: Super 95 about 8.8, Super Plus 98
 * about 8.9, Diesel about 9.8 kWh) - 19.11.2026: "(gleicher Energieinhalt)" behind the three read as if they held the same.
 */
internal fun fuelLitres(kwh: Double) = Triple(kwh / 8.8, kwh / 8.9, kwh / 9.8)

/**
 * The end of a guided trip (wish of 16.11.2026: "fahrt beendet promt animation soll aufmuntern und einem die fahrt
 * dauer und km anzeigen ... halte dich am mate design"): a card in Mate's colours springs up in the middle, a ring in the
 * accent draws itself round and a tick pops in; a cheerful line, then how long the trip took and how far it went.
 * Stays until it is tapped.
 */
@Composable
internal fun NaviArrival(done: TripDone?, onClose: () -> Unit, modifier: Modifier) {
    // stays until a tap beside it (18.11.2026: "das Pop-up, was erscheinen soll, dann eben auch stehen bleiben"); it went
    // by itself after 30 s before
    // shown a moment after arriving, not the instant the car stops (wish of 16.11.2026: "nach 3s")
    AnimatedVisibility(
        visible = done != null,
        modifier = modifier,
        // calm (wish of 16.11.2026 "nicht plopp und es ist da"): a slow fade with a gentle rise, no spring
        enter = fadeIn(tween(700)) + androidx.compose.animation.slideInVertically(tween(700, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { it / 10 },
        exit = fadeOut(tween(260)) + scaleOut(tween(260), targetScale = 0.92f),
    ) {
        val trip = done ?: return@AnimatedVisibility
        val lines = stringArrayResource(R.array.mate_navi_arrived_cheers)
        val cheer = remember(trip) { lines.random() }
        var details by androidx.compose.runtime.remember(trip) { androidx.compose.runtime.mutableStateOf(false) }
        var fuel by androidx.compose.runtime.remember(trip) { androidx.compose.runtime.mutableStateOf(false) }
        Column(
            Modifier.widthIn(min = 420.dp, max = 560.dp).floatEdge(RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp))
                .background(Mate.cardSolid)
                // taps on the card stay on it (a missed info button closed it); a tap beside it closes it (MateNaviScreen)
                .pointerInput(Unit) { detectTapGestures { } }.padding(horizontal = 36.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ArrivedMark()
            Spacer(Modifier.height(18.dp))
            // where, as Google says it - "Angekommen" above the cheer said the same twice (O211); the word without a name
            Text(trip.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.mate_navi_arrived), style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold, color = Mate.text, textAlign = TextAlign.Center, maxLines = 2)
            Spacer(Modifier.height(6.dp))
            Text(cheer, style = MaterialTheme.typography.titleMedium, color = Mate.textDim, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Figure(minutesText(trip.minutes), stringResource(R.string.mate_navi_trip_time))
                Figure(String.format(Locale.getDefault(), "%.1f km", trip.km), stringResource(R.string.mate_navi_trip_distance))
                InfoButton(28.dp) { details = !details }
            }
            // more about the trip behind the "i" (wish of 16.11.2026)
            AnimatedVisibility(details, enter = androidx.compose.animation.expandVertically(tween(320)) + fadeIn(tween(320)),
                exit = androidx.compose.animation.shrinkVertically(tween(260)) + fadeOut(tween(200))) {
                Column(Modifier.padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val l = Locale.getDefault()
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Small(trip.kwh100?.let { String.format(l, "%.1f kWh/100 km", it) } ?: "–", stringResource(R.string.mate_navi_trip_consumption))
                        Small(String.format(l, "%.0f km/h", trip.avgKmh), stringResource(R.string.mate_navi_trip_speed))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Small(trip.kwh?.let { String.format(l, "%.1f kWh", it) } ?: "–", stringResource(R.string.mate_navi_trip_energy))
                            if (trip.kwh != null) { Spacer(Modifier.width(8.dp)); InfoButton(20.dp) { fuel = !fuel } }
                        }
                    }
                    AnimatedVisibility(fuel && trip.kwh != null) {
                        val (s95, s98, d) = fuelLitres(trip.kwh ?: 0.0)
                        Text(stringResource(R.string.mate_navi_trip_fuel, String.format(l, "%.1f", s95), String.format(l, "%.1f", s98), String.format(l, "%.1f", d)),
                            Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge, color = Mate.textDim, textAlign = TextAlign.Center)
                    }
                    if (trip.estimated) Text(stringResource(R.string.mate_navi_trip_estimated), Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.labelSmall, color = Mate.textFaint)
                }
            }
        }
    }
}

/** The ring draws itself round, then the tick pops in. */
@Composable
private fun ArrivedMark() {
    val sweep = remember { Animatable(0f) }
    val tick = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        sweep.animateTo(360f, tween(700))
        tick.animateTo(1f, tween(400, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    val accent = Mate.accent
    val halo = Mate.routeHalo
    Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(96.dp)) {
            val w = 7.dp.toPx()
            drawCircle(halo, radius = size.minDimension / 2 - w / 2, style = Stroke(w))
            drawArc(accent, -90f, sweep.value, false, style = Stroke(w, cap = StrokeCap.Round))
        }
        Icon(Icons.Rounded.Check, null, tint = accent, modifier = Modifier.size(52.dp).scale(tick.value))
    }
}

@Composable
private fun Figure(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, color = Mate.accent)
        Text(label, style = MaterialTheme.typography.labelLarge, color = Mate.textDim)
    }
}

/** The "i" in a circle. */
@Composable
private fun InfoButton(size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(Modifier.size(size + 16.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(androidx.compose.material.icons.Icons.Outlined.Info, stringResource(R.string.mate_navi_trip_more), tint = Mate.textDim, modifier = Modifier.size(size))
    }
}

@Composable
private fun Small(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Mate.text)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Mate.textDim)
    }
}

internal fun minutesText(min: Double): String {
    val m = min.roundToInt().coerceAtLeast(1)
    return if (m < 60) "$m min" else "%d h %02d min".format(m / 60, m % 60)
}

