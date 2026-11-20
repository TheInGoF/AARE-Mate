package net.prototux.aare.ui.car.navi

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.runtime.remember
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Home
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.EditLocationAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import net.prototux.aare.places.Places
import net.prototux.aare.ui.car.Mate
import net.prototux.aare.ui.placeIcon
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Choosing a destination in the car (O169): "Navi - Ziel - Arbeit - scroll to X - tap". A panel at the driver's side
 * over the map: first the user's lists (and search), then a list's places - the most visited of late on top, all of
 * them alphabetically below - then the route's preview with distance, time, arrival, energy and the battery on arrival,
 * from where the guidance starts. Mate's colours, the car's text sizes, large targets.
 */
@Composable
internal fun NaviPanel(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    // a tap on the panel's own ground stays on it - nothing behind it is hit (Navi I-033)
    Column(modifier.floatEdge(RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(Mate.cardSolid)
        .pointerInput(Unit) { detectTapGestures { } }.padding(20.dp)) {
        content()
    }
}

@Composable
internal fun PanelHeader(title: String, onBack: (() -> Unit)?, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back), 52.dp, onBack)
        if (onBack != null) Spacer(Modifier.width(12.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = Mate.text,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        RoundButton(Icons.Rounded.Close, stringResource(R.string.common_close), 52.dp, onClose)
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, size: Dp, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).background(Mate.plate).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Mate.text, modifier = Modifier.size(size * 0.5f))
    }
}

/** First: the lists as tiles, search above them. */
@Composable
internal fun NaviLists(book: Places.Book, recent: Int, title: String, onSearch: () -> Unit, onOptions: () -> Unit, onRecent: () -> Unit, onList: (Places.Category) -> Unit, onClose: () -> Unit, onPlace: (Places.Place) -> Unit = {}) {
    PanelHeader(title, null, onClose)
    Spacer(Modifier.size(16.dp))
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(18.dp)).background(Mate.surface).clickable(onClick = onSearch)
        .padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Search, null, tint = Mate.textDim, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(14.dp))
        Text(stringResource(R.string.mate_navi_search), style = MaterialTheme.typography.titleMedium, color = Mate.textDim)
    }
    // one tap to the places driven to most (as Google Maps' "Zuhause" and "Arbeit"): home first, then the pinned ones,
    // three at most (16.11.2026, O211 "schnell und unkompliziert")
    val quick = remember(book) { (listOfNotNull(book.places.firstOrNull { it.home }) + book.places.filter { it.pinned && !it.home }).take(3) }
    if (quick.isNotEmpty()) {
        Spacer(Modifier.size(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            quick.forEach { p ->
                Row(Modifier.weight(1f).heightIn(min = 72.dp).clip(RoundedCornerShape(18.dp)).background(Mate.accentContainer)
                    .clickable { onPlace(p) }.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (p.home) Icons.Rounded.Home else Icons.Rounded.PushPin, null, tint = Mate.accent, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(if (p.home) stringResource(R.string.mate_navi_home) else p.name, style = MaterialTheme.typography.titleMedium,
                        color = Mate.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    Spacer(Modifier.size(12.dp))
    RouteOptionsButton(Modifier.fillMaxWidth(), onOptions)
    Spacer(Modifier.size(16.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // the destinations reached of late - their pins show on the map only here (wish of 16.11.2026)
        if (recent > 0) item("recent") {
            Row(Modifier.fillMaxWidth().heightIn(min = 84.dp).clip(RoundedCornerShape(18.dp)).background(Mate.surface).clickable(onClick = onRecent)
                .padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(Mate.accentContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.History, null, tint = Mate.accent, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.mate_navi_recent), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = Mate.text)
                Text("$recent", style = MaterialTheme.typography.titleMedium, color = Mate.textDim)
            }
        }
        items(book.categories, key = { it.id }) { c ->
            val n = book.places.count { it.category == c.id }
            Row(Modifier.fillMaxWidth().heightIn(min = 84.dp).clip(RoundedCornerShape(18.dp)).background(Mate.surface).clickable { onList(c) }
                .padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(Mate.accentContainer), contentAlignment = Alignment.Center) {
                    Icon(placeIcon(c.icon), null, tint = Mate.accent, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.width(16.dp))
                Text(c.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = Mate.text)
                Text("$n", style = MaterialTheme.typography.titleMedium, color = Mate.textDim)
            }
        }
    }
}

/**
 * Then: a list's places (Navi L-030): the pinned ones on top (O201), then the others in the list's order - by use (the
 * last 30 days by how often, then by when last), A to Z, or by distance from the car (the distance on each row). The
 * order is chosen in the row under the title and kept for the list (a holiday list stays by distance).
 */
@Composable
internal fun NaviPlaces(book: Places.Book, category: Places.Category, here: org.maplibre.spatialk.geojson.Position?,
                        onPlace: (Places.Place) -> Unit, onOrder: (Places.Order) -> Unit, onBack: () -> Unit, onClose: () -> Unit) {
    PanelHeader(category.name, onBack, onClose)
    Spacer(Modifier.size(12.dp))
    val at = here?.let { it.latitude to it.longitude }
    val sorted = remember(book, category, at) { Places.sorted(book, category.id, category.order, at) }
    if (sorted.pinned.isEmpty() && sorted.others.isEmpty()) {
        Text(stringResource(R.string.mate_navi_list_empty), style = MaterialTheme.typography.titleMedium, color = Mate.textDim)
        return
    }
    OrderRow(category.order, onOrder)
    // by distance without a position: by use, and said so
    if (category.order == Places.Order.DISTANCE && sorted.order != Places.Order.DISTANCE)
        Text(stringResource(R.string.mate_navi_order_no_position), Modifier.padding(top = 8.dp, start = 4.dp),
            style = MaterialTheme.typography.bodyMedium, color = Mate.textDim)
    Spacer(Modifier.size(8.dp))
    val km = { p: Places.Place -> if (sorted.order == Places.Order.DISTANCE && at != null) Places.metres(at.first, at.second, p.lat, p.lon) / 1000 else null }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sorted.pinned.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.mate_navi_pinned)) }
            items(sorted.pinned, key = { "p" + it.id }) { PlaceRow(it, Mate.accent, km(it)) { onPlace(it) } }
            if (sorted.others.isNotEmpty()) item { SectionLabel(stringResource(R.string.mate_navi_other_places)) }
        }
        items(sorted.others, key = { it.id }) { PlaceRow(it, Mate.text, km(it)) { onPlace(it) } }
    }
}

/** The list's order: three wide buttons in a row, the chosen one lit (at least 56 dp high, as all tap areas in the car). */
@Composable
private fun OrderRow(order: Places.Order, onOrder: (Places.Order) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Triple(Places.Order.USE, Icons.Rounded.History, R.string.places_order_use),
            Triple(Places.Order.NAME, Icons.Rounded.SortByAlpha, R.string.places_order_name),
            Triple(Places.Order.DISTANCE, Icons.Rounded.NearMe, R.string.places_order_distance)).forEach { (o, icon, label) ->
            val on = o == order
            Row(Modifier.weight(1f).heightIn(min = 60.dp).clip(RoundedCornerShape(16.dp)).background(if (on) Mate.accentContainer else Mate.surface)
                .clickable { onOrder(o) }.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center) {
                Icon(icon, null, tint = if (on) Mate.accent else Mate.textDim, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(label), style = MaterialTheme.typography.titleMedium, color = if (on) Mate.text else Mate.textDim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, Modifier.padding(top = 8.dp, bottom = 2.dp, start = 4.dp), style = MaterialTheme.typography.labelLarge, color = Mate.textDim)
}

@Composable
private fun PlaceRow(p: Places.Place, color: Color, km: Double? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).clip(RoundedCornerShape(16.dp)).background(Mate.surface).clickable(onClick = onClick)
        .padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(p.name, style = MaterialTheme.typography.titleLarge, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.address.isNotBlank()) Text(p.address, style = MaterialTheme.typography.bodyMedium, color = Mate.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // by distance: how far, as the crow flies
        if (km != null) Text(Places.distanceText(km),
            Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium, color = Mate.textDim)
    }
}

/** What the planned trip will be, before it starts. */
internal class Preview(val name: String, val km: Double, val minutes: Double, val kwh: Double?, val arrivalPercent: Double?, val needsCharge: Boolean,
                       val stops: List<ChargePlanner.Stop> = emptyList(),
                       /** the route as a list (L-035): own stops, charging stops, destination in order, with times */
                       val plan: List<NaviTripPlan.Row> = emptyList(),
                       /** charging stops the driver took out, to take in again */
                       val removed: List<Charger> = emptyList(),
                       /** the charging stops are still being planned (the route can start already) */
                       val pending: Boolean = false)

@Composable
internal fun androidx.compose.foundation.layout.ColumnScope.NaviPreview(p: Preview?, error: String?, placeName: String, options: List<Pair<NaviSession.Kind, Preview>>, chosen: Int, countdown: Int,
                         onPick: (Int) -> Unit, onStart: () -> Unit, onBack: () -> Unit, onClose: () -> Unit, onCancel: () -> Unit = onClose,
                         onOptions: () -> Unit = {}, alternatives: (Charger) -> List<ChargePlanner.Alternative> = { emptyList() },
                         onStopOpened: () -> Unit = {}, onReplaceStop: (Charger, Charger) -> Unit = { _, _ -> },
                         onRemoveStop: (Charger) -> Unit = {}, onRestoreStop: (Charger) -> Unit = {}, onRemoveAll: () -> Unit = {},
                         onRecalc: () -> Unit = {}, canPlan: Boolean = false, onReorder: (List<Pair<org.maplibre.spatialk.geojson.Position, String>>) -> Unit = {}) {
    PanelHeader(p?.name ?: if (error != null) placeName else stringResource(R.string.mate_navi_planning), onBack, onClose)
    Spacer(Modifier.size(20.dp))
    if (p == null) {
        Text(error ?: stringResource(R.string.mate_navi_planning), style = MaterialTheme.typography.titleMedium,
            color = if (error != null) Mate.warn else Mate.textDim)
        Spacer(Modifier.size(20.dp))
        CancelButton(Modifier.heightIn(min = 72.dp), onClick = onCancel)
        return
    }
    // the middle scrolls when it is taller than the screen allows (with charging stops it is: 16.11.2026 "buttons unten
    // gequetscht. kann man nicht hoch schieben"); the header and the buttons stay where they are
    Column(Modifier.weight(1f, fill = false).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
        // the routes to choose (O180): fast, economical, alternative - the chosen one in the accent
        if (options.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                options.forEachIndexed { i, (kind, o) ->
                    val sel = i == chosen
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(if (sel) Mate.accentContainer else Mate.surface)
                        .clickable { onPick(i) }.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(stringResource(when (kind) { NaviSession.Kind.FAST -> R.string.mate_navi_route_fast; NaviSession.Kind.ECO -> R.string.mate_navi_route_eco
                            NaviSession.Kind.ALT -> R.string.mate_navi_route_alt }), style = MaterialTheme.typography.labelLarge, color = if (sel) Mate.accent else Mate.textDim)
                        val m = o.minutes.roundToInt().coerceAtLeast(1)
                        Text(if (m < 60) "$m min" else String.format(Locale.getDefault(), "%d h %02d", m / 60, m % 60),
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Mate.text)
                        Text(String.format(Locale.getDefault(), "%.0f km", o.km) + (o.arrivalPercent?.let { " · ${it.coerceAtLeast(0.0).roundToInt()} %" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = Mate.textDim)
                    }
                }
            }
            Spacer(Modifier.size(16.dp))
        }
        val l = Locale.getDefault()
        val arrival = net.prototux.aare.ui.Clock.format(System.currentTimeMillis() + (p.minutes * 60_000).toLong())
        val min = p.minutes.roundToInt().coerceAtLeast(1)
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Figure(String.format(l, "%.1f km", p.km), stringResource(R.string.mate_navi_eta_distance), Mate.text)
            Figure(if (min < 60) "$min min" else String.format(l, "%d h %02d", min / 60, min % 60), stringResource(R.string.mate_navi_eta_time), Mate.text)
            Figure(arrival, stringResource(R.string.mate_navi_eta_arrival), Mate.accent)
        }
        Spacer(Modifier.size(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Figure(p.kwh?.let { String.format(l, "%.1f kWh", it) } ?: "–", stringResource(R.string.mate_navi_trip_energy), Mate.text)
            Box {
                Figure(p.arrivalPercent?.let { "${it.coerceAtLeast(0.0).roundToInt()} %" } ?: "–", stringResource(R.string.mate_navi_charge_at_destination),
                    if (p.needsCharge) Mate.warn else Mate.text)
                // a charge is needed on the way: the plus pole, as on the tile while driving
                if (p.needsCharge) PlusPole(Modifier.align(Alignment.TopEnd).padding(start = 8.dp).size(24.dp).offset(x = 26.dp, y = (-6).dp))
            }
        }
        // what is avoided, changeable right here (O188)
        Spacer(Modifier.size(12.dp))
        RouteOptionsButton(Modifier.fillMaxWidth(), onOptions)
        // O213: only the chosen networks - said when they do not reach and others are planned
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val rules = androidx.compose.runtime.remember(p) { ChargePlanner.Rules.of(net.prototux.aare.Settings(ctx)) }
        if (rules.strict && p.stops.any { rules.outside(it.charger) }) {
            Spacer(Modifier.size(10.dp))
            Text(stringResource(R.string.mate_navi_networks_outside, rules.preferred.mapNotNull { ChargeNetworks.name(it) }.sorted().joinToString(", ")),
                style = MaterialTheme.typography.bodyMedium, color = Mate.warn)
        }
        // the route as a list (L-035; before only the charging stops, O182): own stops, chargers, destination in order;
        // a charging stop tapped shows other chargers for it (F3, as ABRP), its X takes it out, + takes it in again
        // the route is there, the charging stops come (19.11.2026: start before they are planned; they follow)
        if (p.pending) {
            Spacer(Modifier.size(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), color = Mate.accent, strokeWidth = 3.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.mate_navi_charge_pending), style = MaterialTheme.typography.bodyMedium, color = Mate.textDim)
            }
        }
        if (p.plan.isNotEmpty()) {
            Spacer(Modifier.size(16.dp))
            NaviRouteList(p.plan, p.removed, canPlan, alternatives, rules.strict, onStopOpened, onReplaceStop, onRemoveStop, onRestoreStop,
                onRemoveAll, onRecalc, onReorder)
        }
    }
    Spacer(Modifier.size(16.dp))
    // cancel beside start, cancel at the driver's side (O179 "dass man routen abbrechen kann")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CancelButton(Modifier.heightIn(min = 80.dp), onClick = onCancel)
        Box(Modifier.weight(1f).heightIn(min = 80.dp).clip(RoundedCornerShape(20.dp)).background(Mate.accent).clickable(onClick = onStart),
            contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.mate_navi_start) + (if (countdown > 0) " · $countdown" else ""),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = Mate.onAccent)
        }
    }
}

/** "Abbrechen": the plan (or the guidance) ends, the panel closes. */
@Composable
internal fun CancelButton(modifier: Modifier, label: Int = R.string.mate_navi_cancel, onClick: () -> Unit) {
    Row(modifier.floatEdge(RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(Mate.cardSolid).clickable(onClick = onClick).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Close, null, tint = Mate.text, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(label), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Mate.text)
    }
}

@Composable
private fun Figure(value: String, label: String, color: Color) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = Mate.textDim)
    }
}

/**
 * "Fortsetzen" (16.11.2026): the trip lost with the connection, offered above "Ziel" - its destination, a tap plans it
 * again from here (the charging anew with the level now), the cross forgets it.
 */
@Composable
internal fun NaviResumeButton(name: String, modifier: Modifier, onGo: () -> Unit, onForget: () -> Unit) {
    Row(modifier.floatEdge(RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(Mate.cardSolid)
        .heightIn(min = 76.dp).widthIn(max = 560.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f, fill = false).clickable(onClick = onGo).heightIn(min = 76.dp).padding(start = 26.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Navigation, null, tint = Mate.accent, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(stringResource(R.string.mate_navi_resume), style = MaterialTheme.typography.labelLarge, color = Mate.textDim)
                Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Mate.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.Rounded.Close, stringResource(R.string.mate_navi_resume_forget), tint = Mate.textDim,
            modifier = Modifier.clickable(onClick = onForget).padding(horizontal = 20.dp, vertical = 22.dp).size(32.dp))
    }
}

/**
 * O217: a place held on the map (17.11.2026: "lange auf das gewünschte Ziel in der Map drücken. Navigieren zu oder
 * Zwischenziel"): its address, "Dahin navigieren", and while guiding "Als Zwischenziel".
 */
@Composable
internal fun NaviMapPick(name: String, guiding: Boolean, onGo: () -> Unit, onStop: () -> Unit, onClose: () -> Unit) {
    PanelHeader(name, null, onClose)
    Spacer(Modifier.size(16.dp))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MenuRow(Icons.Rounded.Navigation, stringResource(R.string.mate_navi_pick_go), Mate.text, onGo)
        if (guiding) MenuRow(Icons.Rounded.AddLocationAlt, stringResource(R.string.mate_navi_pick_stop), Mate.text, onStop)
    }
}

/** The "Ziel" button on the map, at the driver's side above the controls. */
@Composable
internal fun NaviDestinationButton(modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.floatEdge(RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(Mate.cardSolid).clickable(onClick = onClick)
        .heightIn(min = 76.dp).padding(horizontal = 26.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Search, null, tint = Mate.accent, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(14.dp))
        Text(stringResource(R.string.mate_navi_destination), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = Mate.text)
    }
}

/** The menu while guiding (from the tile with what is left): end, another destination, a stop on the way. */
@Composable
internal fun NaviMenu(target: String?, onEnd: () -> Unit, onChange: () -> Unit, onStop: () -> Unit, onClose: () -> Unit, onPlan: (() -> Unit)? = null) {
    PanelHeader(target ?: stringResource(R.string.mate_navi_menu), null, onClose)
    Spacer(Modifier.size(16.dp))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // O209: all stops ahead at a glance, first - only when there are stops besides the destination
        if (onPlan != null) MenuRow(Icons.Rounded.Route, stringResource(R.string.mate_navi_plan), Mate.text, onPlan)
        MenuRow(Icons.Rounded.AddLocationAlt, stringResource(R.string.mate_navi_menu_stop), Mate.text, onStop)
        MenuRow(Icons.Rounded.EditLocationAlt, stringResource(R.string.mate_navi_menu_change), Mate.text, onChange)
        MenuRow(Icons.Rounded.Close, stringResource(R.string.mate_navi_menu_end), Mate.danger, onEnd)
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).clip(RoundedCornerShape(18.dp)).background(Mate.surface).clickable(onClick = onClick)
        .padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.titleLarge, color = color)
    }
}

/** The destinations reached of late, newest first; their pins stand on the map while this list is open. */
@Composable
internal fun NaviRecent(pins: List<NaviPins.Pin>, onPick: (NaviPins.Pin) -> Unit, onBack: () -> Unit, onClose: () -> Unit) {
    PanelHeader(stringResource(R.string.mate_navi_recent), onBack, onClose)
    Spacer(Modifier.size(12.dp))
    // "Heute, 22:24" / "Gestern, 18:35" / "16.11.2026" - quicker to read than a full stamp (O211)
    val today = stringResource(R.string.mate_navi_today); val yesterday = stringResource(R.string.mate_navi_yesterday)
    val l = Locale.getDefault()
    fun whenText(t: Long): String {
        val d = java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault())
        val days = java.time.temporal.ChronoUnit.DAYS.between(d.toLocalDate(), java.time.LocalDate.now())
        val time = d.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm", l))
        return when (days) { 0L -> "$today, $time"; 1L -> "$yesterday, $time"; else -> d.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy", l)) }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // keyed by place in the list: older pins were all saved with the same time (a duplicate key brought Mate down)
        items(pins.size, key = { it }) { i -> val p = pins[i]
            Column(Modifier.fillMaxWidth().heightIn(min = 76.dp).clip(RoundedCornerShape(16.dp)).background(Mate.surface).clickable { onPick(p) }
                .padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center) {
                Text(p.name.ifBlank { stringResource(R.string.places_on_map) }, style = MaterialTheme.typography.titleLarge, color = Mate.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p.time > 0) Text(whenText(p.time), style = MaterialTheme.typography.bodyMedium, color = Mate.textDim)
            }
        }
    }
}
