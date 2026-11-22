package net.prototux.aare.ui.car.navi

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Traffic
import androidx.compose.material.icons.rounded.SatelliteAlt
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.prototux.aare.ui.car.Mate
import kotlin.math.roundToInt

/**
 * The driver's speed and the speed limit, above Mate's controls at the bottom left (wish of 16.11.2026; N2: both, with
 * the country's signs; specification F-07: "unbekannt" rather than a guess, F-09: the own speed from GPS). The limit is
 * a placeholder for now - the sign stays grey with a dash until the route brings limits.
 */
@Composable
internal fun NaviSpeed(kmh: Double?, limitKmh: Int?, modifier: Modifier, from: NaviSpeedSource.From? = null, clock: () -> Long = { 0L }) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        val over = kmh != null && limitKmh != null && limitKmh > 0 && kmh > limitKmh + 3
        Box(Modifier.widthIn(min = 96.dp).floatEdge(RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(Mate.cardSolid)) {
            Column(Modifier.align(Alignment.Center).padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(kmh?.roundToInt()?.toString() ?: "–", style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold, color = if (over) Mate.warn else Mate.text)
                Text("km/h", style = MaterialTheme.typography.labelSmall, color = Mate.textDim)
            }
            // where the speed comes from (System L-088): a small satellite for the GPS, a small speedometer for the car's
            if (from != null) androidx.compose.material3.Icon(
                if (from == NaviSpeedSource.From.GPS) androidx.compose.material.icons.Icons.Rounded.SatelliteAlt else androidx.compose.material.icons.Icons.Rounded.Speed,
                androidx.compose.ui.res.stringResource(if (from == NaviSpeedSource.From.GPS) net.prototux.aare.R.string.mate_navi_speed_gps else net.prototux.aare.R.string.mate_navi_speed_car),
                tint = Mate.textDim, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 5.dp).size(17.dp))
        }
        // no limit known here: no sign at all rather than a grey placeholder (16.11.2026: "wenn er wirklich mal keine
        // tempo daten hat dann kann das schild auch ausgeblendet werden"); "unlimited" keeps its own sign
        if (limitKmh != null && (limitKmh > 0 || limitKmh == NaviLimits.UNLIMITED)) LimitSign(limitKmh, over, clock)
    }
}

/** Germany's sign: a white face in a red ring, the number black; unknown: the same sign grey with a dash. */
@Composable
private fun LimitSign(limit: Int?, over: Boolean, clock: () -> Long) {
    val known = limit != null
    // over the limit the sign breathes (wish of 16.11.2026 "soll das limit zeichen wabern"): grows a little and back.
    // In the map's own beat ([clock], the glide's time, read only in the layer): an endless animation of its own asked
    // for every display frame - 60 a second over the whole car screen while the map ran at 30 (16.11.2026)
    // ... but only for the first 20 s of an overspeed (O195, 16.11.2026: "nach 20s limit überschreitung sollte auch das
    // zeichen aufhören zu atmen"); a new limit or coming back under starts it afresh
    val since = androidx.compose.runtime.remember(over, limit) { if (over) android.os.SystemClock.elapsedRealtime() else 0L }
    fun scale(): Float {
        if (!over || android.os.SystemClock.elapsedRealtime() - since > BREATHE_MS) return 1f
        val t = (clock() % 1400L) / 1400.0
        return (1.0 + 0.06 * (1 - Math.cos(t * 2 * Math.PI))).toFloat()
    }
    val face = if (known) Mate.signFace else Mate.surface
    val ring = if (known) Mate.signRing else Mate.textFaint
    if (limit == NaviLimits.UNLIMITED) { EndOfLimits(); return }
    Box(Modifier.size(76.dp).graphicsLayer { scaleX = scale(); scaleY = scale() }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(76.dp)) {
            val r = size.minDimension / 2
            drawCircle(face, radius = r)
            if (over) drawCircle(ring.copy(alpha = (scale() - 1f) * 3f), radius = r * 1.08f, style = Stroke(r * 0.12f))
            drawCircle(ring, radius = r - r * 0.1f, style = Stroke(r * 0.2f))
        }
        Text(limit?.toString() ?: "–", fontSize = if ((limit ?: 0) >= 100) 24.sp else 28.sp, fontWeight = FontWeight.Bold,
            color = if (known) Mate.signText else Mate.textDim)
    }
}

/** No limit (German motorway): sign 282 "end of all limits" - white, a thin black ring, five slanting black stripes. */
@Composable
private fun EndOfLimits() {
    Canvas(Modifier.size(76.dp)) {
        val r = size.minDimension / 2
        drawCircle(Mate.signFace, radius = r)
        drawCircle(Mate.signText, radius = r - 1.5f, style = Stroke(3f))
        // the stripes run from bottom left to top right and end on a circle inside the ring, as on the sign: the middle
        // one the longest, the outer ones shorter (they were all as long, the outer ones reaching almost to the edge)
        val c = center
        val inner = r * 0.84f
        val diag = (1 / Math.sqrt(2.0)).toFloat()
        for (k in -2..2) {
            val d = k * r * 0.2f                                  // the stripe's distance from the middle, across it
            val half = Math.sqrt((inner * inner - d * d).toDouble()).toFloat()
            val mx = c.x + d * diag; val my = c.y + d * diag      // across: towards bottom right
            drawLine(Mate.signText, androidx.compose.ui.geometry.Offset(mx - half * diag, my + half * diag),
                androidx.compose.ui.geometry.Offset(mx + half * diag, my - half * diag), strokeWidth = r * 0.07f)
        }
    }
}

/** What is left of the trip: distance, time and the clock time of arrival, on a Mate tile. */
@Composable
internal fun NaviEta(metersLeft: Double, secondsLeft: Double, modifier: Modifier, next: NaviStops.Ahead? = null, nextCharger: Boolean = false,
                     /** O237: inside a jam, the seconds to its end and the metres it still goes */
                     jamLeft: Pair<Double, Double>? = null, onClick: () -> Unit = {}) {
    // a tap opens the guidance menu (end, change, a stop): the small arrow says so
    // as wide as its widest row (the line between them must not stretch the tile over the screen)
    Column(modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max).floatEdge(RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(Mate.cardSolid)
        .clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp)) {
        // O206: the next stop on the way in one slim line above the destination's figures - a bolt for a charging stop,
        // a flag for a stop of the driver's; the same three values, small
        // O237 ("im Stau die Zeit im Stau anzeigen"): in a jam, how long it still lasts, in one slim line on top
        if (jamLeft != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(Icons.Rounded.Traffic, null, tint = Mate.warn, modifier = Modifier.size(20.dp))
                androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                Text(androidx.compose.ui.res.stringResource(net.prototux.aare.R.string.mate_navi_jam_left, duration(jamLeft.first)),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Mate.warn)
                Text(" · " + distance(jamLeft.second), style = MaterialTheme.typography.titleSmall, color = Mate.text)
            }
            androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(1.dp).background(Mate.textFaint.copy(alpha = 0.4f)))
            androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
        }
        if (next != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(if (nextCharger) Icons.Rounded.Bolt else Icons.Rounded.Flag,
                    null, tint = Mate.accent, modifier = Modifier.size(20.dp))
                androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                Text(clock(next.secondsLeft), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Mate.accent)
                Text(" · " + duration(next.secondsLeft) + " · " + distance(next.metresLeft), style = MaterialTheme.typography.titleSmall, color = Mate.text)
            }
            androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(1.dp).background(Mate.textFaint.copy(alpha = 0.4f)))
            androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            EtaValue(clock(secondsLeft), androidx.compose.ui.res.stringResource(net.prototux.aare.R.string.mate_navi_eta_arrival), Mate.accent)
            EtaValue(duration(secondsLeft), androidx.compose.ui.res.stringResource(net.prototux.aare.R.string.mate_navi_eta_time), Mate.text)
            EtaValue(distance(metersLeft), androidx.compose.ui.res.stringResource(net.prototux.aare.R.string.mate_navi_eta_distance), Mate.text)
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.KeyboardArrowUp, null, tint = Mate.textDim)
        }
    }
}

private fun distance(m: Double): String =
    if (m < 1000) "${(m / 10).roundToInt() * 10} m" else String.format(java.util.Locale.getDefault(), "%.1f km", m / 1000)

private fun duration(s: Double): String {
    val min = (s / 60).roundToInt().coerceAtLeast(1)
    return if (min < 60) "$min min" else String.format(java.util.Locale.getDefault(), "%d h %02d min", min / 60, min % 60)
}

private fun clock(s: Double): String = net.prototux.aare.ui.Clock.format(System.currentTimeMillis() + (s * 1000).toLong())

@Composable
private fun EtaValue(value: String, label: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Mate.textDim)
    }
}

/** How long the limit sign breathes in one overspeed (O195). */
private const val BREATHE_MS = 20_000L
