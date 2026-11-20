package net.prototux.aare.ui.car.navi

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.prototux.aare.ui.car.Mate
import kotlin.math.roundToInt

/**
 * Mate Navi's share of the screen's width, set by the split view (1 = the whole screen). Below 0.3 the route strip replaces
 * the map; from 0.6 on a slim strip without labels runs along the map's edge (18.11.2026: "Die Routenlinie bekommt auch die
 * Navi-App in voller Ansicht und 3/4, 2/3 Ansicht, aber ohne Beschriftungen").
 */
val LocalNaviShare = staticCompositionLocalOf { 1f }

/**
 * The route as a strip (18.11.2026, split view at a quarter: "1/4 Navi zeigt dann nur noch einen Streckenverlauf an. Unten
 * aktuell, nach oben hin ist die Linie die Route symbolisch dargestellt. Mit Zwischenstopps, Ladestationen auf der Linie,
 * alles mit Kreisen und Symbolen. Rechts km dorthin, links die Zeit dorthin. Bei Staus und Verzögerungen ist die Linie an
 * der Stelle nicht türkis, sondern gelb, orange, rot, dunkelrot. Am Ziel eine Zielflagge mit Ziel-SoC, Uhrzeit und Dauer.
 * Ladestationen bekommen auch den Ankunfts-SoC und Laden-bis-SoC.")
 *
 * The line is to scale: the car at the bottom, the destination at the top, every stop where it lies on the way.
 * [rows] the stops ahead and the destination (NaviTripPlan), [jams] stretches ahead as metres from here with a level
 * 0..3 (yellow, orange, red, dark red), [leftM]/[leftS] the way and time to the destination (charging included).
 */
@Composable
internal fun NaviRouteStrip(rows: List<NaviTripPlan.Row>, jams: List<Triple<Double, Double, Int>>, leftM: Double, leftS: Double,
                            modifier: Modifier = Modifier, labels: Boolean = true, totalM: Double = leftM,
                            /** road works, closures, accidents ahead: metres from here and their kind (TomTom's) */
                            incidents: List<Pair<Double, Int>> = emptyList()) {
    if (!labels) { SlimStrip(rows, jams, leftM, totalM.coerceAtLeast(leftM), modifier, incidents); return }
    val now = System.currentTimeMillis()
    val dest = rows.lastOrNull { it.kind == NaviTripPlan.Kind.DESTINATION }
    BoxWithConstraints(modifier.fillMaxSize().background(Mate.mapLand)) {
        val top: Dp = 150.dp
        val bottom: Dp = net.prototux.aare.host.templates.LocalCarBottomInset.current + 40.dp
        val lineH = (maxHeight - top - bottom).coerceAtLeast(40.dp)
        val cx = maxWidth / 2
        fun yOf(m: Double): Dp = top + lineH * (1f - (m / leftM.coerceAtLeast(1.0)).coerceIn(0.0, 1.0).toFloat())
        val teal = Mate.route
        val levels = listOf(Color(0xFFFFD54F), Color(0xFFFF9800), Color(0xFFE53935), Color(0xFF8E0000))
        Canvas(Modifier.fillMaxSize()) {
            val x = cx.toPx()
            val y0 = yOf(0.0).toPx()
            val y1 = yOf(leftM).toPx()
            val w = 10.dp.toPx()
            drawLine(Color(0xFF0B2E2A), Offset(x, y0), Offset(x, y1), w + 6.dp.toPx(), StrokeCap.Round)
            drawLine(teal, Offset(x, y0), Offset(x, y1), w, StrokeCap.Round)
            for ((from, to, level) in jams) {
                if (to <= 0 || from >= leftM) continue
                // at least 8 dp long: a jam of a few hundred metres on a long trip would be a hairline
                val ya = yOf(from.coerceAtLeast(0.0)).toPx(); val yb = yOf(to.coerceAtMost(leftM)).toPx()
                val grow = ((8.dp.toPx() - (ya - yb)) / 2).coerceAtLeast(0f)
                drawLine(levels[level.coerceIn(0, 3)], Offset(x, ya + grow), Offset(x, yb - grow), w, StrokeCap.Butt)
            }
            // the car: an arrow at the bottom end
            val a = 14.dp.toPx()
            val p = Path().apply {
                moveTo(x, y0 - a); lineTo(x + a * 0.8f, y0 + a * 0.9f); lineTo(x, y0 + a * 0.45f); lineTo(x - a * 0.8f, y0 + a * 0.9f); close()
            }
            drawPath(p, Color.White)
        }
        // road works and the like on the line, their sign beside it (18.11.2026: "Baustelle in 3,1 Kilometern ... wird das
        // nicht auf dem Routenfortschritt dargestellt")
        incidents.filter { it.first in 0.0..leftM }.forEach { (m, kind) -> IncidentMark(kind, 34.dp, Modifier.offset(cx + 12.dp, yOf(m) - 17.dp)) }
        // the stops on the line, with the time (left) and the kilometres (right)
        rows.filter { it.kind != NaviTripPlan.Kind.DESTINATION }.forEach { r ->
            val y = yOf(r.metresLeft)
            Box(Modifier.offset(cx - 20.dp, y - 20.dp).size(40.dp).clip(CircleShape)
                .background(if (r.kind == NaviTripPlan.Kind.CHARGE) Color(0xFF0E3B36) else Mate.cardSolid), contentAlignment = Alignment.Center) {
                Icon(if (r.kind == NaviTripPlan.Kind.CHARGE) Icons.Rounded.EvStation else Icons.Rounded.Place, null,
                    tint = if (r.kind == NaviTripPlan.Kind.CHARGE) teal else Color.White, modifier = Modifier.size(24.dp))
            }
            Side(Modifier.offset(0.dp, y - 22.dp).width(cx - 28.dp), TextAlign.End,
                duration((r.arriveAtMillis - now) / 1000.0), net.prototux.aare.ui.Clock.format(r.arriveAtMillis))
            val soc = if (r.arrivePercent != null && r.leavePercent != null)
                "${r.arrivePercent.roundToInt()} → ${r.leavePercent.roundToInt()} %" else null
            Side(Modifier.offset(cx + 28.dp, y - 22.dp).width(cx - 28.dp), TextAlign.Start, km(r.metresLeft), soc)
        }
        // the destination: a flag at the top end, its level, the clock time and how long until then
        Box(Modifier.offset(cx - 22.dp, yOf(leftM) - 22.dp).size(44.dp).clip(CircleShape).background(Mate.cardSolid), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Flag, null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Column(Modifier.align(Alignment.TopCenter).padding(top = 12.dp, start = 8.dp, end = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val at = dest?.arriveAtMillis ?: (now + (leftS * 1000).toLong())
            Text(net.prototux.aare.ui.Clock.format(at), color = teal, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(duration(leftS) + " · " + km(leftM), color = Color.White, fontSize = 20.sp, maxLines = 1)
            dest?.arrivePercent?.let { Text("${it.roundToInt()} % am Ziel", color = Mate.textDim, fontSize = 18.sp, maxLines = 1) }
        }
    }
}

/**
 * The strip beside the map: the line, the stops, the jams, the flag - no words, no numbers. [modifier] gives its place.
 * The whole way from the start ([totalM]) to the destination, the car moving up along it (18.11.2026: "Der
 * Fortschrittsbalken ... sollte nicht mit reinzoomen. Also wenn ich 100 Kilometer fahre, soll er nach 50 Kilometern quasi
 * mich auf der Mitte anzeigen") - it showed only what was left and so zoomed in with every kilometre.
 */
@Composable
private fun SlimStrip(rows: List<NaviTripPlan.Row>, jams: List<Triple<Double, Double, Int>>, leftM: Double, totalM: Double, modifier: Modifier,
                      incidents: List<Pair<Double, Int>> = emptyList()) {
    BoxWithConstraints(modifier.width(40.dp)) {
        val top = 16.dp
        val lineH = (maxHeight - top - 18.dp).coerceAtLeast(40.dp)
        val cx = maxWidth / 2
        val total = totalM.coerceAtLeast(leftM).coerceAtLeast(1.0)
        val done = total - leftM
        // [m] metres ahead of the car, placed on the whole way: the start at the bottom, the destination at the top
        fun yOf(m: Double): Dp = top + lineH * (1f - ((done + m) / total).coerceIn(0.0, 1.0).toFloat())
        val teal = Mate.route
        val levels = listOf(Color(0xFFFFD54F), Color(0xFFFF9800), Color(0xFFE53935), Color(0xFF8E0000))
        Canvas(Modifier.fillMaxSize()) {
            val x = cx.toPx()
            val y0 = yOf(0.0).toPx(); val y1 = yOf(leftM).toPx(); val yStart = yOf(-done).toPx()
            val w = 6.dp.toPx()
            drawLine(Color(0xCC0B2E2A), Offset(x, yStart), Offset(x, y1), w + 5.dp.toPx(), StrokeCap.Round)
            // the way driven, faint; the way ahead in the route's colour
            if (done > 0) drawLine(Color.White.copy(alpha = 0.35f), Offset(x, yStart), Offset(x, y0), w, StrokeCap.Round)
            drawLine(teal, Offset(x, y0), Offset(x, y1), w, StrokeCap.Round)
            for ((from, to, level) in jams) {
                if (to <= 0 || from >= leftM) continue
                val ya = yOf(from.coerceAtLeast(0.0)).toPx(); val yb = yOf(to.coerceAtMost(leftM)).toPx()
                val grow = ((6.dp.toPx() - (ya - yb)) / 2).coerceAtLeast(0f)
                drawLine(levels[level.coerceIn(0, 3)], Offset(x, ya + grow), Offset(x, yb - grow), w, StrokeCap.Butt)
            }
            val a = 9.dp.toPx()
            drawPath(Path().apply { moveTo(x, y0 - a); lineTo(x + a * 0.8f, y0 + a * 0.9f); lineTo(x, y0 + a * 0.45f); lineTo(x - a * 0.8f, y0 + a * 0.9f); close() }, Color.White)
        }
        incidents.filter { it.first in 0.0..leftM }.forEach { (m, kind) -> IncidentMark(kind, 24.dp, Modifier.offset(cx - 12.dp, yOf(m) - 12.dp)) }
        rows.forEach { r ->
            val y = yOf(r.metresLeft)
            val charge = r.kind == NaviTripPlan.Kind.CHARGE
            val icon = when (r.kind) { NaviTripPlan.Kind.CHARGE -> Icons.Rounded.EvStation; NaviTripPlan.Kind.OWN -> Icons.Rounded.Place; else -> Icons.Rounded.Flag }
            Box(Modifier.offset(cx - 14.dp, y - 14.dp).size(28.dp).clip(CircleShape).background(if (charge) Color(0xFF0E3B36) else Mate.cardSolid),
                contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = if (charge) teal else Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun Side(modifier: Modifier, align: TextAlign, first: String, second: String?) {
    Column(modifier, horizontalAlignment = if (align == TextAlign.End) Alignment.End else Alignment.Start) {
        Text(first, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, textAlign = align)
        if (second != null) Text(second, color = Mate.textDim, fontSize = 16.sp, maxLines = 1, textAlign = align)
    }
}

private fun km(m: Double): String = if (m < 10_000) "%.1f km".format(m / 1000) else "${(m / 1000).roundToInt()} km"

private fun duration(s: Double): String {
    val min = (s / 60).roundToInt().coerceAtLeast(0)
    return if (min < 60) "$min min" else "${min / 60} h ${"%02d".format(min % 60)}"
}

/** An incident on the strip: its road sign, as on the hint card. */
@Composable
private fun IncidentMark(kind: Int, size: Dp, modifier: Modifier) = TrafficSign(kind, size, modifier)
