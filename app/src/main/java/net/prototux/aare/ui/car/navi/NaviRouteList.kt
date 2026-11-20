package net.prototux.aare.ui.car.navi

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import net.prototux.aare.R
import net.prototux.aare.ui.car.Mate
import org.maplibre.spatialk.geojson.Position
import kotlin.math.roundToInt

/**
 * The route as a list in the preview (Navi L-035, 19.11.2026: "eine Routenübersicht ... dass man sehen kann, okay, er plant
 * jetzt über die und die Lader ... auf der Liste müsste man dann auch sagen können ... Ladestops entfernen und Ladestops neu
 * berechnen. Also man kann die rausnehmen und wieder reinnehmen"): the driver's own stops, the charging stops and the
 * destination in the order they are driven, each with the clock time of arrival and the way there.
 * - own stops and the destination: with an own stop each has the grip - held (long press) and drawn up or down it goes
 *   where it is let go; what stands last then is the destination ([onReorder] gets them all in the new order, the
 *   destination last; planned anew from the first place that changed);
 * - a charging stop: tapped, the other chargers near it ([alternatives], [onReplace]); its X takes it out ([onRemove]);
 * - taken out: below, dimmed, each with + to take it in again ([onRestore]);
 * - below all: "Ladestopps entfernen" ([onRemoveAll]) and "Ladestopps neu berechnen" ([onRecalc], the plan of the device again).
 */
@Composable
internal fun NaviRouteList(rows: List<NaviTripPlan.Row>, removed: List<Charger>, canPlan: Boolean,
                           alternatives: (Charger) -> List<ChargePlanner.Alternative>, strict: Boolean,
                           onOpened: () -> Unit, onReplace: (Charger, Charger) -> Unit, onRemove: (Charger) -> Unit,
                           onRestore: (Charger) -> Unit, onRemoveAll: () -> Unit, onRecalc: () -> Unit,
                           onReorder: (List<Pair<Position, String>>) -> Unit) {
    var open by remember(rows) { mutableStateOf<Charger?>(null) }
    var list by remember(rows) { mutableStateOf(rows) }
    var dragKey by remember { mutableStateOf<Int?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    // each row's height while laid out, to know when a held row has passed the next one
    val heights = remember(rows) { mutableStateMapOf<Int, Int>() }
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.toPx() }
    // a key per row that stays while rows move: its place in the list as planned
    val keys = remember(rows) { java.util.IdentityHashMap<NaviTripPlan.Row, Int>().apply { rows.forEachIndexed { i, r -> put(r, i) } } }
    fun key(r: NaviTripPlan.Row) = keys[r] ?: -1
    // the destination too (19.11.2026: "das Ziel müsste man ja wohl auch greifen können und verschieben können, dass dann
    // das Zwischenziel zum Endziel wird"): whatever stands last after the move is the destination
    fun movable(r: NaviTripPlan.Row) = (r.kind == NaviTripPlan.Kind.OWN || r.kind == NaviTripPlan.Kind.DESTINATION) && r.at != null
    val sortable = rows.count(::movable) >= 2

    Text(stringResource(R.string.mate_navi_route_list), style = MaterialTheme.typography.labelLarge, color = Mate.textDim)
    Spacer(Modifier.size(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        list.forEach { r ->
            val k = key(r)
            val dragging = dragKey == k
            val canDrag = sortable && movable(r)
            Column(Modifier.zIndex(if (dragging) 1f else 0f)
                .graphicsLayer { translationY = if (dragging) dragY else 0f; val sc = if (dragging) 1.03f else 1f; scaleX = sc; scaleY = sc }
                .onSizeChanged { heights[k] = it.height }) {
                Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (dragging) Mate.surfaceHigh else Mate.surface)
                    .then(if (r.kind == NaviTripPlan.Kind.CHARGE && r.charger != null)
                        Modifier.clickable { open = if (open === r.charger) null else r.charger; onOpened() } else Modifier)
                    .then(if (!canDrag) Modifier else Modifier.pointerInput(k) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragKey = k; dragY = 0f; onOpened() },
                            onDrag = { ch, d ->
                                ch.consume()
                                dragY += d.y
                                val cur = list.indexOfFirst { key(it) == k }
                                // past half of the next own stop that way: it takes that one's place (charging stops are passed over)
                                val target = if (dragY > 0) (cur + 1 until list.size).firstOrNull { movable(list[it]) }
                                    else (cur - 1 downTo 0).firstOrNull { movable(list[it]) }
                                if (target != null) {
                                    // the rows it passes (charging stops between, then the own stop it takes the place of)
                                    val passed = if (target > cur) (cur + 1..target) else (target until cur)
                                    fun h(i: Int) = (heights[key(list[i])] ?: 0) + gap
                                    val shift = passed.sumOf { h(it).toDouble() }.toFloat()
                                    val half = shift - h(target) + (heights[key(list[target])] ?: 0) / 2f
                                    if ((target > cur && dragY > half) || (target < cur && -dragY > half)) {
                                        list = list.toMutableList().apply { add(target, removeAt(cur)) }
                                        dragY += if (target > cur) -shift else shift
                                    }
                                }
                            },
                            onDragEnd = {
                                dragKey = null; dragY = 0f
                                val order = list.filter(::movable).map { it.at!! to it.name }
                                if (order != rows.filter(::movable).map { it.at!! to it.name }) onReorder(order)
                            },
                            onDragCancel = { dragKey = null; dragY = 0f; list = rows })
                    })
                    .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (r.kind == NaviTripPlan.Kind.CHARGE) NetworkLogo(r.network, 30.dp, boltTint = Mate.warn)
                    else Box(Modifier.size(30.dp * 1.6f, 30.dp), contentAlignment = Alignment.Center) {
                        Icon(if (r.kind == NaviTripPlan.Kind.OWN) Icons.Rounded.Place else Icons.Rounded.Flag, null, tint = Mate.accent, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.name.ifBlank { stringResource(if (r.kind == NaviTripPlan.Kind.CHARGE) R.string.mate_navi_charger else R.string.mate_navi_plan_stop) },
                            style = MaterialTheme.typography.titleMedium, color = Mate.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val detail = when (r.kind) {
                            NaviTripPlan.Kind.CHARGE -> stringResource(R.string.mate_navi_charge_stop, pct(r.arrivePercent), pct(r.leavePercent),
                                (r.chargeMinutes ?: 0.0).roundToInt().coerceAtLeast(1))
                            NaviTripPlan.Kind.DESTINATION -> r.arrivePercent?.let { stringResource(R.string.mate_navi_plan_destination, "${pct(it)} %") }
                            NaviTripPlan.Kind.OWN -> null
                        }
                        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall,
                            color = if (r.kind == NaviTripPlan.Kind.DESTINATION && (r.arrivePercent ?: 100.0) < NaviCharge.RESERVE_PERCENT) Mate.warn else Mate.textDim, maxLines = 2)
                        if (r.overLimit) Text(stringResource(R.string.mate_navi_charge_over, net.prototux.aare.Settings(androidx.compose.ui.platform.LocalContext.current).chargeMaxLevel),
                            style = MaterialTheme.typography.bodySmall, color = Mate.warn)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(net.prototux.aare.ui.Clock.format(r.arriveAtMillis), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold, color = Mate.accent)
                        Text(km(r.metresLeft), style = MaterialTheme.typography.bodySmall, color = Mate.textDim)
                    }
                    when {
                        r.kind == NaviTripPlan.Kind.CHARGE && r.charger != null ->
                            RowButton(Icons.Rounded.Close, stringResource(R.string.mate_navi_stop_remove)) { open = null; onRemove(r.charger) }
                        canDrag -> Icon(Icons.Rounded.DragIndicator, stringResource(R.string.mate_navi_plan_grip), tint = Mate.textDim,
                            modifier = Modifier.padding(horizontal = 8.dp).size(width = 22.dp, height = 32.dp))
                        else -> Spacer(Modifier.width(8.dp))
                    }
                }
                // the other chargers near this stop (F3, as ABRP); one chosen plans the route over it
                if (r.kind == NaviTripPlan.Kind.CHARGE && r.charger != null && open === r.charger) {
                    val alts = remember(r.charger, rows) { alternatives(r.charger) }
                    if (alts.isEmpty()) Text(stringResource(if (strict) R.string.mate_navi_alt_none_chosen else R.string.mate_navi_alt_none),
                        style = MaterialTheme.typography.bodySmall, color = Mate.textDim, modifier = Modifier.padding(start = 32.dp, top = 6.dp))
                    alts.forEach { a ->
                        Spacer(Modifier.size(6.dp))
                        Row(Modifier.padding(start = 32.dp).fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(12.dp)).background(Mate.surface)
                            .clickable { open = null; onReplace(r.charger, a.charger) }.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            NetworkLogo(a.charger.network, 26.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(NaviTripPlan.chargerName(a.charger, stringResource(R.string.mate_navi_charger)), style = MaterialTheme.typography.titleSmall,
                                    color = Mate.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val km = String.format(java.util.Locale.getDefault(), "%.0f", Math.abs(a.shiftM) / 1000)
                                Text(stringResource(if (a.shiftM < 0) R.string.mate_navi_alt_earlier else R.string.mate_navi_alt_later, km),
                                    style = MaterialTheme.typography.bodySmall, color = Mate.textDim)
                            }
                        }
                    }
                }
            }
        }
        // taken out by the driver: still there to take in again
        if (removed.isNotEmpty()) {
            Text(stringResource(R.string.mate_navi_stops_removed), style = MaterialTheme.typography.labelLarge, color = Mate.textDim,
                modifier = Modifier.padding(top = 6.dp))
            removed.forEach { c ->
                Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).clip(RoundedCornerShape(16.dp)).background(Mate.surface)
                    .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).alpha(0.5f), verticalAlignment = Alignment.CenterVertically) {
                        NetworkLogo(c.network, 26.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(NaviTripPlan.chargerName(c, stringResource(R.string.mate_navi_charger)), style = MaterialTheme.typography.titleSmall,
                            color = Mate.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    RowButton(Icons.Rounded.Add, stringResource(R.string.mate_navi_stop_restore)) { onRestore(c) }
                }
            }
        }
    }
    // the plan as a whole: no charging stops, or the device's plan again
    val anyCharge = rows.any { it.kind == NaviTripPlan.Kind.CHARGE && it.charger != null }
    if (anyCharge || (canPlan && (removed.isNotEmpty() || rows.isNotEmpty()))) {
        Spacer(Modifier.size(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (anyCharge) WideButton(stringResource(R.string.mate_navi_stops_remove_all), Modifier.weight(1f), onRemoveAll)
            if (canPlan) WideButton(stringResource(R.string.mate_navi_stops_recalc), Modifier.weight(1f), onRecalc)
        }
    }
}

@Composable
private fun RowButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(56.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Mate.text, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun WideButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp)).background(Mate.surface).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = Mate.text, maxLines = 2)
    }
}

private fun pct(p: Double?) = (p ?: 0.0).coerceAtLeast(0.0).roundToInt()
private fun km(m: Double) = if (m < 1000) "${(m / 10).roundToInt() * 10} m"
    else String.format(java.util.Locale.getDefault(), "%.1f km", m / 1000)
