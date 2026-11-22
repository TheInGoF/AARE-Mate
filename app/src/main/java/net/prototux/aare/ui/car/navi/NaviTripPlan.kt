package net.prototux.aare.ui.car.navi

import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.EditLocationAlt
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsBoat
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import net.prototux.aare.ui.car.Mate
import org.maplibre.spatialk.geojson.Position
import kotlin.math.roundToInt

/**
 * The trip plan (O209, 16.11.2026: "zwischenhalte und ladestops wie beim tesla überblicken ... wo man überall anhält und
 * das auch ich während der navigation den plan wieder auf machen kann"): every stop still ahead in order - charging stops
 * with the level on arrival, the minutes charging and the level leaving, the driver's own stops - and the destination
 * with its level, each with the clock time of arrival and the way from here. Opened from the menu while guiding.
 */
internal object NaviTripPlan {
    enum class Kind { CHARGE, OWN, DESTINATION, FERRY }

    /** One line: [arriveAtMillis] clock time of arrival, [metresLeft] the way from here; levels only for a charging stop. */
    class Row(val kind: Kind, val name: String, val arriveAtMillis: Long, val metresLeft: Double,
              val arrivePercent: Double? = null, val leavePercent: Double? = null, val chargeMinutes: Double? = null,
              val network: String? = null, val at: Position? = null, val charger: Charger? = null,
              val overLimit: Boolean = false,
              /** a ferry: the minutes over the sea (the clock time is when the car reaches its port) */
              val ferryMinutes: Double? = null, val seaM: Double? = null)

    /** Another charger for a stop of the running trip (System L-092): [shiftM] before (-) or after it, the level on arrival there. */
    class Option(val charger: Charger, val shiftM: Double, val arrivePercent: Double?)

    /** A ferry on the route ([NaviFerries]): where it starts along the route (metres, seconds from the start), its ride. */
    class Ferry(val name: String, val metres: Double, val seconds: Double, val rideSeconds: Double, val seaM: Double)

    /** The ferries of a route from its steps: one per run of ferry steps, named after the first. */
    fun ferries(steps: List<Pair<Double, Double>>, isFerry: List<Boolean>, names: List<String?>): List<Ferry> {
        val out = ArrayList<Ferry>()
        var m = 0.0; var t = 0.0; var i = 0
        while (i < steps.size) {
            if (!isFerry[i]) { m += steps[i].first; t += steps[i].second; i++; continue }
            val m0 = m; val t0 = t; val name = names[i].orEmpty()
            while (i < steps.size && isFerry[i]) { m += steps[i].first; t += steps[i].second; i++ }
            out += Ferry(name, m0, t0, t - t0, m - m0)
        }
        return out
    }

    fun ferries(route: uniffi.ferrostar.Route): List<Ferry> =
        ferries(route.steps.map { it.distance to it.duration }, route.steps.map { NaviFerries.isFerry(it) }, route.steps.map { it.roadName })

    /**
     * [marks] the stops before the destination along the guided route (NaviStops), [charges] the planned charging stops,
     * [own] the driver's own stops with their names, [drivenM]/[drivenS] how far and how long the car has come on the
     * route, [routeM]/[routeS] the whole route, [destinationPercent] the level at the destination when known.
     * The charging of the stops before one counts into its arrival, as the preview counts it.
     */
    fun rows(marks: List<NaviStops.Mark>, charges: List<ChargePlanner.Stop>, own: List<Pair<Position, String>>,
             drivenM: Double, drivenS: Double, routeM: Double, routeS: Double, nowMillis: Long,
             destination: String, destinationPercent: Double?, destinationAt: Position? = null, ferries: List<Ferry> = emptyList()): List<Row> {
        val out = ArrayList<Row>()
        var chargedS = 0.0
        val ahead = ferries.filter { it.metres + it.seaM > drivenM + PASSED_M }.toMutableList()
        /** the ferries before [metres] (all left with null) into the plan, in their place */
        fun ferriesBefore(metres: Double?) {
            while (ahead.isNotEmpty() && (metres == null || ahead[0].metres < metres)) {
                val f = ahead.removeAt(0)
                out += Row(Kind.FERRY, f.name, nowMillis + (((f.seconds - drivenS).coerceAtLeast(0.0) + chargedS) * 1000).toLong(),
                    (f.metres - drivenM).coerceAtLeast(0.0), ferryMinutes = f.rideSeconds / 60, seaM = f.seaM)
            }
        }
        for (m in marks) {
            if (m.metres <= drivenM + PASSED_M) continue
            ferriesBefore(m.metres)
            val arrive = nowMillis + (((m.seconds - drivenS).coerceAtLeast(0.0) + chargedS) * 1000).toLong()
            val c = charges.firstOrNull { ChargerIndex.distanceM(it.charger.at, m.at) < SAME_M }
            if (c != null) {
                out += Row(Kind.CHARGE, chargerName(c.charger), arrive, m.metres - drivenM, c.arrivePercent, c.leavePercent, c.chargeMinutes, c.charger.network,
                    charger = c.charger, overLimit = c.overLimit)
                chargedS += c.stayMinutes * 60
            } else {
                val mine = own.minByOrNull { ChargerIndex.distanceM(it.first, m.at) }?.takeIf { ChargerIndex.distanceM(it.first, m.at) < OWN_SNAP_M }
                // the stop as the driver chose it (its place and name), for moving it in the plan
                out += Row(Kind.OWN, mine?.second.orEmpty(), arrive, m.metres - drivenM, at = mine?.first ?: m.at)
            }
        }
        ferriesBefore(null)
        out += Row(Kind.DESTINATION, destination, nowMillis + (((routeS - drivenS).coerceAtLeast(0.0) + chargedS) * 1000).toLong(),
            (routeM - drivenM).coerceAtLeast(0.0), arrivePercent = destinationPercent, at = destinationAt)
        return out
    }

    /** The network's name ("TotalEnergies", not its id "total"), else the operator's. */
    fun chargerName(c: Charger, unnamed: String = ""): String = "${(ChargeNetworks.name(c.network) ?: c.operator).ifBlank { unnamed }} · ${c.kw} kW"

    private const val PASSED_M = 30.0
    /** A waypoint and a stop this close are the same place. */
    private const val SAME_M = 150.0
}

/**
 * The trip plan in the panel at the driver's side. With two or more of the driver's own stops each has a grip: held
 * (long press) and drawn up or down, it goes where it is let go - the others make way, animated - and [onReorder] plans
 * the trip in the new order (O222, 17.11.2026: "Lange drücken greifen und darein sortieren wo man mag ... Es braucht einen
 * Greifer damit der User sieht dass das geht"). Charging stops and the destination stay; the charging is planned anew.
 */
@Composable
internal fun androidx.compose.foundation.layout.ColumnScope.NaviTripPlanPanel(rows: List<NaviTripPlan.Row>, onBack: (() -> Unit)?, onClose: () -> Unit,
                               onReorder: (List<Pair<Position, String>>) -> Unit = {}, onEdit: ((List<Pair<Position, String>>) -> Unit)? = null,
                               /** a charging stop taken out during the trip, null: all of them (19.11.2026) */
                               onDropCharge: ((Charger?) -> Unit)? = null,
                               /** the battery now, for the start of the plan (System L-089) */
                               nowPercent: Double? = null,
                               /** the stop shown on the map now, and a stop tapped: the map flies there, the plan stays open (L-089) */
                               selected: Position? = null, onPick: ((NaviTripPlan.Row) -> Unit)? = null,
                               /** System L-092: other chargers for a stop, one shown on the map, one taken instead (only this stop changes) */
                               /** the stop whose alternatives are on the map now, how many, the one tapped on the map */
                               altFor: Charger? = null, altCount: Int = 0, altPicked: NaviTripPlan.Option? = null,
                               /** the stations on the map as a list too, nearest to the stop first (22.11.2026: "dass man dann auch eine Liste bekommt") */
                               altList: List<NaviTripPlan.Option> = emptyList(), onAltPick: (NaviTripPlan.Option) -> Unit = {},
                               onAlternatives: ((Charger) -> Unit)? = null, onAltClose: () -> Unit = {},
                               onSwap: ((Charger, Charger) -> Unit)? = null,
                               /** System L-097: the plan is the first thing while guiding - a stop, another destination, the menu */
                               onAddStop: (() -> Unit)? = null, onChangeDestination: (() -> Unit)? = null, onMenu: (() -> Unit)? = null) {
    PanelHeader(stringResource(R.string.mate_navi_plan), onBack, onClose)
    // System L-097 (22.11.2026: "wenn wir unten auf das Ziel und das Zwischenziel drücken, dann geht erst der Routenplaner auf
    // und von dem Routenplaner aus kommen wir auf den Auswahlpanel … wie bei Tesla"): the usual steps right at the top
    if (onAddStop != null || onChangeDestination != null || onMenu != null) {
        Spacer(Modifier.size(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            @Composable fun Act(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, go: () -> Unit) =
                Row(modifier.heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp)).background(Mate.surface).clickable(onClick = go)
                    .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(icon, null, tint = Mate.accent, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.titleSmall, color = Mate.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            onAddStop?.let { Act(Icons.Rounded.AddLocationAlt, stringResource(R.string.mate_navi_plan_add_stop), Modifier.weight(1f), it) }
            onChangeDestination?.let { Act(Icons.Rounded.EditLocationAlt, stringResource(R.string.mate_navi_plan_change), Modifier.weight(1f), it) }
            onMenu?.let { Act(Icons.Rounded.Apps, stringResource(R.string.mate_navi_plan_more), Modifier, it) }
        }
    }
    Spacer(Modifier.size(12.dp))
    // System L-089 (22.11.2026: "dass wir angezeigt bekommen, wie viele Kilometer fahren wir gerade, dann kommt ein
    // Ladestopp, wie viele Kilometer fahren wir dazwischen … bis zum Ziel. Dass wir wirklich einen Reiseplan sehen"):
    // a line from here through each stop to the destination, each stretch with its kilometres and driving time
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(Modifier.size(32.dp * 1.6f, 28.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.layout.Box(Modifier.size(18.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Mate.accent))
        }
        Spacer(Modifier.width(16.dp))
        Text(stringResource(R.string.mate_navi_plan_start), style = MaterialTheme.typography.titleMedium, color = Mate.text, modifier = Modifier.weight(1f))
        if (nowPercent != null) Text(pct(nowPercent), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Mate.text)
    }
    var list by androidx.compose.runtime.remember(rows) { androidx.compose.runtime.mutableStateOf(rows) }
    var dragKey by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var dragY by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val sortable = rows.count { it.kind == NaviTripPlan.Kind.OWN && it.at != null } >= 2
    val stepPx = with(androidx.compose.ui.platform.LocalDensity.current) { (88.dp + LEG_H + 4.dp).toPx() }
    // one key per row of the plan, kept while rows move (two charging stops of one network have the same name - the
    // same key brought Mate down, 17.11.2026)
    val keys = androidx.compose.runtime.remember(rows) { java.util.IdentityHashMap<NaviTripPlan.Row, String>().apply { rows.forEachIndexed { i, r -> put(r, "$i") } } }
    fun key(r: NaviTripPlan.Row) = keys[r] ?: r.hashCode().toString()
    val startedAt = androidx.compose.runtime.remember(rows) { System.currentTimeMillis() }
    LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(list, key = { key(it) }) { r ->
            val dragging = dragKey == key(r)
            val canDrag = sortable && r.kind == NaviTripPlan.Kind.OWN && r.at != null
            val place = r.charger?.at ?: r.at
            val isSelected = selected != null && place != null && ChargerIndex.distanceM(selected, place) < 30
            Column(Modifier.then(if (dragging) Modifier else Modifier.animateItem()).zIndex(if (dragging) 1f else 0f)
                .graphicsLayer { translationY = if (dragging) dragY else 0f }) {
            // the stretch before this stop: from the previous one (or from here) - its kilometres and the driving time
            val i = list.indexOfFirst { key(it) == key(r) }
            val prev = list.getOrNull(i - 1)
            val legM = (r.metresLeft - (prev?.metresLeft ?: 0.0)).coerceAtLeast(0.0)
            val leftAt = prev?.let { it.arriveAtMillis + (((it.chargeMinutes ?: 0.0) + (it.ferryMinutes ?: 0.0)) * 60_000).toLong() } ?: startedAt
            val legMin = ((r.arriveAtMillis - leftAt) / 60_000.0).coerceAtLeast(0.0)
            Row(Modifier.fillMaxWidth().height(LEG_H).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Box(Modifier.size(32.dp * 1.6f, LEG_H), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.layout.Box(Modifier.size(4.dp, LEG_H).clip(RoundedCornerShape(2.dp)).background(Mate.textFaint))
                }
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.mate_navi_plan_leg, if (legM >= 10_000) "%d km".format((legM / 1000).roundToInt()) else km(legM), duration(legMin)), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, color = Mate.textDim)
            }
            Row(Modifier.graphicsLayer { val sc = if (dragging) 1.03f else 1f; scaleX = sc; scaleY = sc }
                .fillMaxWidth().heightIn(min = 88.dp).clip(RoundedCornerShape(18.dp)).background(if (dragging || isSelected) Mate.surfaceHigh else Mate.surface)
                .then(if (isSelected) Modifier.border(2.dp, Mate.accent, RoundedCornerShape(18.dp)) else Modifier)
                .then(if (onPick != null && place != null) Modifier.clickable { onPick(r) } else Modifier)
                .then(if (!canDrag) Modifier else Modifier.pointerInput(key(r)) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragKey = key(r); dragY = 0f },
                        onDrag = { ch, d ->
                            ch.consume()
                            dragY += d.y
                            val cur = list.indexOfFirst { key(it) == key(r) }
                            // past half a row: it takes the place of the next own stop that way (charging stops are passed over)
                            val target = when {
                                dragY > stepPx / 2 -> (cur + 1 until list.size).firstOrNull { list[it].kind == NaviTripPlan.Kind.OWN && list[it].at != null }
                                dragY < -stepPx / 2 -> (cur - 1 downTo 0).firstOrNull { list[it].kind == NaviTripPlan.Kind.OWN && list[it].at != null }
                                else -> null
                            }
                            if (target != null) {
                                list = list.toMutableList().apply { add(target, removeAt(cur)) }
                                dragY -= stepPx * (target - cur)
                            }
                        },
                        onDragEnd = {
                            dragKey = null; dragY = 0f
                            val order = list.filter { it.kind == NaviTripPlan.Kind.OWN && it.at != null }.map { it.at!! to it.name }
                            val before = rows.filter { it.kind == NaviTripPlan.Kind.OWN && it.at != null }.map { it.at!! to it.name }
                            if (order != before) onReorder(order)
                        },
                        onDragCancel = { dragKey = null; dragY = 0f; list = rows })
                })
                .padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                // a charging stop: its network's logo (O210); the others in a slot of the same width, so the names line up
                if (r.kind == NaviTripPlan.Kind.CHARGE) NetworkLogo(r.network, 32.dp)
                else androidx.compose.foundation.layout.Box(Modifier.size(32.dp * 1.6f, 32.dp), contentAlignment = Alignment.Center) {
                    Icon(when (r.kind) { NaviTripPlan.Kind.OWN -> Icons.Rounded.Flag; NaviTripPlan.Kind.FERRY -> Icons.Rounded.DirectionsBoat; else -> Icons.Rounded.Place },
                        null, tint = Mate.accent, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.name.ifBlank { stringResource(R.string.mate_navi_plan_stop) }, style = MaterialTheme.typography.titleMedium,
                        color = Mate.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val detail = when (r.kind) {
                        NaviTripPlan.Kind.CHARGE -> stringResource(R.string.mate_navi_plan_charge, pct(r.arrivePercent), (r.chargeMinutes ?: 0.0).roundToInt(), pct(r.leavePercent))
                        NaviTripPlan.Kind.DESTINATION -> r.arrivePercent?.let { stringResource(R.string.mate_navi_plan_destination, pct(it)) }
                        NaviTripPlan.Kind.OWN -> null
                        NaviTripPlan.Kind.FERRY -> ferryDetail(r)
                    }
                    if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = Mate.textDim, maxLines = 1)
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(net.prototux.aare.ui.Clock.format(r.arriveAtMillis), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, color = Mate.accent)
                    Text(km(r.metresLeft), style = MaterialTheme.typography.bodyMedium, color = Mate.textDim)
                }
                // 19.11.2026 ("Im Reiseplan der Navi-App möchte ich Ladestationen entfernen, damit ich auch ohne festgelegte
                // Ladestops reisen kann"): a charging stop taken out right here, the route goes on without it
                if (r.kind == NaviTripPlan.Kind.CHARGE && r.charger != null && onDropCharge != null) {
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.foundation.layout.Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Mate.surfaceHigh)
                        .clickable { onDropCharge(r.charger) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.mate_navi_plan_remove_charge), tint = Mate.text, modifier = Modifier.size(28.dp))
                    }
                }
                // the grip: here a stop can be held and moved
                // the grip (O227: six dots for what can be moved, narrow - it takes little room)
                if (canDrag) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Rounded.DragIndicator, stringResource(R.string.mate_navi_plan_grip), tint = Mate.textDim,
                        modifier = Modifier.size(width = 22.dp, height = 32.dp))
                }
            }
            // System L-092 (22.11.2026: "wenn ich auf die Ladesäule drücke … dass ich dann noch sagen kann, Alternativen anzeigen
            // … andere aus meinem Filter zugelassene Ladesäulen in der Nähe, die ich noch erreichen kann … und dann will ich
            // diese Alternative vielleicht auswählen"; "nur um die Alternative für diese eine Station … ohne neu zu planen")
            val ch = r.charger
            // L-092, second form (22.11.2026: "nur einblenden, wenn ich sage, ich möchte eine Alternative … einfach einen Knopf
            // Alternativen anzeigen. Und dann öffnet er auf der Map alle Stationen, die in der Nähe sind, so dass ich dann da
            // reinzoomen könnte und sagen kann, ja, die passt mir eher"): the button, then the stations on the map, one tapped
            // there comes here with "Nehmen"
            if (r.kind == NaviTripPlan.Kind.CHARGE && ch != null && onAlternatives != null && onSwap != null && (isSelected || altFor === ch)) {
                Spacer(Modifier.size(6.dp))
                if (altFor !== ch) {
                    androidx.compose.foundation.layout.Box(Modifier.padding(start = 40.dp).fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(14.dp))
                        .background(Mate.surface).clickable { onAlternatives(ch) }, contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.mate_navi_plan_alternatives), style = MaterialTheme.typography.titleSmall, color = Mate.accent)
                    }
                } else {
                    Row(Modifier.padding(start = 40.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (altCount == 0) stringResource(R.string.mate_navi_alt_none_chosen)
                            else androidx.compose.ui.res.pluralStringResource(R.plurals.mate_navi_alt_on_map, altCount, altCount),
                            style = MaterialTheme.typography.bodyMedium, color = Mate.textDim, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.mate_navi_alt_hide), style = MaterialTheme.typography.titleSmall, color = Mate.accent,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onAltClose() }.heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 14.dp))
                    }
                    // the list beside the map: the nearest first, the picked one framed with "Nehmen" (the map's logos say the same)
                    (altList.sortedBy { Math.abs(it.shiftM) }.take(ALT_LIST_MAX).let { l -> if (altPicked != null && altPicked !in l) listOf(altPicked) + l else l }).forEach { o ->
                        val picked = o === altPicked
                        Spacer(Modifier.size(6.dp))
                        Row(Modifier.padding(start = 40.dp).fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (picked) Mate.surfaceHigh else Mate.surface)
                            .then(if (picked) Modifier.border(2.dp, Mate.accent, RoundedCornerShape(14.dp)) else Modifier)
                            .clickable { onAltPick(o) }
                            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            NetworkLogo(o.charger.network, 26.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(NaviTripPlan.chargerName(o.charger, stringResource(R.string.mate_navi_charger)), style = MaterialTheme.typography.titleSmall,
                                    color = Mate.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val kmText = String.format(java.util.Locale.getDefault(), "%.0f", Math.abs(o.shiftM) / 1000)
                                val where = stringResource(if (o.shiftM < 0) R.string.mate_navi_alt_earlier else R.string.mate_navi_alt_later, kmText)
                                Text(o.arrivePercent?.let { stringResource(R.string.mate_navi_alt_arrive, where, pct(it)) } ?: where,
                                    style = MaterialTheme.typography.bodySmall, color = Mate.textDim, maxLines = 1)
                            }
                            if (picked) Text(stringResource(R.string.mate_navi_alt_take), style = MaterialTheme.typography.titleSmall, color = Mate.onAccent,
                                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Mate.accent).clickable { onSwap(ch, o.charger) }
                                    .heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 14.dp))
                        }
                    }
                }
            }
            }
        }
    }
    // all charging stops out at once: on to the destination on what the battery holds
    if (onDropCharge != null && rows.count { it.kind == NaviTripPlan.Kind.CHARGE } >= 1) {
        Spacer(Modifier.size(16.dp))
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(18.dp)).background(Mate.surface)
            .clickable { onDropCharge(null) }, contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.mate_navi_plan_no_charging), style = MaterialTheme.typography.titleMedium, color = Mate.text)
        }
    }
    // L-035: take charging stops out or in, or plan them anew - in the preview of the route from here
    if (onEdit != null) {
        Spacer(Modifier.size(16.dp))
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(18.dp)).background(Mate.surface)
            .clickable { onEdit(rows.filter { it.kind == NaviTripPlan.Kind.OWN && it.at != null }.map { it.at!! to it.name }) },
            contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.mate_navi_plan_edit), style = MaterialTheme.typography.titleMedium, color = Mate.text)
        }
    }
}

private fun pct(p: Double?) = "${(p ?: 0.0).coerceAtLeast(0.0).roundToInt()} %"
/** How many alternatives the list shows (the map shows all of them). */
private const val ALT_LIST_MAX = 8
/** The height of a stretch's line between two stops. */
private val LEG_H = 40.dp
private fun duration(min: Double): String { val m = min.roundToInt(); return if (m >= 60) "%d h %02d min".format(m / 60, m % 60) else "$m min" }
private fun km(m: Double) = if (m < 1000) "${(m / 10).roundToInt() * 10} m" else "%.1f km".format(m / 1000)

/** A ferry's line in the plan: the sea and the ride, without energy (as IDmate counts it). */
@Composable
internal fun ferryDetail(r: NaviTripPlan.Row): String {
    val min = (r.ferryMinutes ?: 0.0).roundToInt()
    val ride = if (min >= 60) "%d h %02d".format(min / 60, min % 60) else "$min min"
    return stringResource(R.string.mate_navi_plan_ferry, ((r.seaM ?: 0.0) / 1000).roundToInt(), ride)
}
