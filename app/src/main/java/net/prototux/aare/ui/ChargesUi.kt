package net.prototux.aare.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.prototux.aare.DiagLog
import net.prototux.aare.R
import net.prototux.aare.Settings
import net.prototux.aare.ShownFailure
import net.prototux.aare.dash.ServerLogbook
import net.prototux.aare.trips.TripStore
import net.prototux.aare.ui.car.navi.ChargeAsk
import net.prototux.aare.ui.car.navi.ChargeList
import net.prototux.aare.ui.car.navi.Charger
import net.prototux.aare.ui.car.navi.ChargerIndex
import net.prototux.aare.ui.car.navi.ChargerVisits
import net.prototux.aare.ui.car.navi.NaviTripPlan
import net.prototux.aare.ui.car.navi.NetworkLogos
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * "Ladungen" in the logbook (System L-113): the charges, newest first, by month as IDmate's charge overview lists them - with
 * the charger each was at and its stars, given right there. Tapping one: power, price, whether all worked, or another charger
 * (the nearest is only a guess where several stand side by side). From IDmate's charge book where there is a server, else
 * the tablet's own.
 */
@Composable
internal fun ChargesView(server: Boolean) {
    val ctx = LocalContext.current
    var entries by remember { mutableStateOf<List<ChargeList.Entry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var index by remember { mutableStateOf<ChargerIndex?>(null) }
    // what came after the list was last looked at stays "neu" while it is open; the badge goes (Navi L-005)
    var seenBefore by remember { mutableStateOf(Long.MAX_VALUE) }
    LaunchedEffect(Unit) {
        seenBefore = ChargerVisits.allSeen(ctx)
        withContext(Dispatchers.IO) {
            index = runCatching { ChargerIndex.get(ctx) }.getOrNull()
            val fromServer = if (server) runCatching { ChargeList.fromServer(ServerLogbook.charges(ctx)) }
                .onFailure { error = ShownFailure.text(ctx, it) }.getOrNull() else null
            entries = fromServer ?: ChargeList.fromTablet(runCatching { TripStore.get(ctx).charges() }.getOrDefault(emptyList()))
        }
    }
    val changes = ChargerVisits.changes.intValue
    val list = entries
    val stations = remember(list, index, changes) {
        val ix = index
        if (list == null) emptyMap() else {
            val learned = ChargeAsk.learned(ctx)
            val visits = ChargerVisits.all(ctx)
            val links = ChargerVisits.links(ctx)
            list.associate { e ->
                e.key to ChargeList.station(e, e.place?.let { p -> ix?.near(p, ChargeAsk.NEAR_M, ChargeAsk.MIN_KW) }.orEmpty(), learned, visits,
                    links[e.key]) { p -> ix?.near(p, 15.0, 0)?.minByOrNull { ChargerIndex.distanceM(it.at, p) } }
            }
        }
    }
    var open by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.charges_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(stringResource(R.string.charges_server_failed, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        when {
            list == null -> Text(stringResource(R.string.charges_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            list.isEmpty() -> Text(stringResource(R.string.charges_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                // an older IDmate says not where the car stood: the chargers cannot be guessed, only those said in the car
                if (server && error == null && list.none { it.place != null || it.home })
                    Text(stringResource(R.string.charges_server_old), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChargeList.byMonth(list).forEach { (ym, month) ->
                    ChargeMonth(ym, month, stations, seenBefore, open) { key -> open = if (open == key) null else key }
                }
            }
        }
    }
}

@Composable
private fun ChargeMonth(ym: Pair<Int, Int>, month: List<ChargeList.Entry>, stations: Map<String, ChargeList.Station>, seenBefore: Long,
                        open: String?, toggle: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val title = if (ym.first == 0) "–" else YearMonth.of(ym.first, ym.second).let { m ->
                m.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) } + " " + m.year }
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            val kwh = month.mapNotNull { it.kwh }.sum()
            val cost = month.mapNotNull { it.cost }.sum()
            Text(listOfNotNull(pluralStringResource(R.plurals.charges_count, month.size, month.size),
                if (kwh > 0) "${logNum(kwh, 1)} kWh" else null, if (cost > 0) "${logNum(cost, 2)} €" else null).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        month.forEachIndexed { i, e ->
            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            ChargeRow(e, stations[e.key], fresh = (e.atMs ?: 0L) > seenBefore, opened = open == e.key) { toggle(e.key) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChargeRow(e: ChargeList.Entry, s: ChargeList.Station?, fresh: Boolean, opened: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val ch = s?.charger
    val visit = ch?.let { ChargerVisits.at(ctx, it.at) }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            ChargerMark(ch?.network, e.home)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val title = when {
                        e.home -> e.name ?: stringResource(R.string.charges_home)
                        ch != null -> NaviTripPlan.chargerName(ch, stringResource(R.string.charges_charger))
                        else -> e.name ?: stringResource(R.string.logbook_place_unknown)
                    }
                    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (ch != null && !s.sure) Pill(stringResource(R.string.charges_guess), MaterialTheme.colorScheme.surfaceContainerHighest,
                        MaterialTheme.colorScheme.onSurfaceVariant)
                    if (fresh) Pill(stringResource(R.string.routing_visits_new), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                }
                val time = e.startMs?.let { st -> "${logDay(st)}, ${logTime(st)}" + (e.endMs?.let { en -> "–" + logTime(en) } ?: "") }
                    ?: e.endMs?.let { "${logDay(it)}, ${logTime(it)}" }
                Text(listOfNotNull(time, when (e.dc) { true -> "DC"; false -> "AC"; null -> null }, e.name.takeIf { ch != null && !e.home })
                    .joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(
                    if (e.socStart != null || e.socEnd != null) "${logNum(e.socStart, 0)} → ${logNum(e.socEnd, 0)} %" else null,
                    e.kwh?.let { "${logNum(it, 1)} kWh" },
                    e.kwAvg?.let { "Ø ${logNum(it, 0)} kW" } ?: e.kwMax?.let { stringResource(R.string.charges_kw_max, logNum(it, 0)) },
                    e.cost?.let { "${logNum(it, 2)} €" }).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                // the stars under it, where the charger is known (none for the wallbox)
                if (ch != null) Stars(visit?.rating ?: 0) { n ->
                    rateAt(ctx, e, ch) { ChargerVisits.rate(ctx, ch.at, if ((visit?.rating ?: 0) == n) 0 else n) }
                    DiagLog.add("charge rated in the list: ${NaviTripPlan.chargerName(ch)} $n of 5", area = "CHARGE")
                }
            }
        }
        if (opened && !e.home && s != null) Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (s.choices.isNotEmpty()) {
                Text(stringResource(R.string.charges_which), style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    s.choices.forEach { c ->
                        val d = e.place?.let { ChargerIndex.distanceM(c.at, it) }
                        FilterChip(ch === c && s.sure, onClick = { pick(ctx, e, s, c) }, label = {
                            Text(NaviTripPlan.chargerName(c, stringResource(R.string.charges_charger)) + (d?.let { " · ${it.toInt()} m" } ?: ""),
                                maxLines = 1) })
                    }
                    FilterChip(ch == null && s.sure, onClick = { pick(ctx, e, s, null) }, label = { Text(stringResource(R.string.charges_none_of_them)) })
                }
            } else Text(stringResource(R.string.charges_no_charger), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (ch != null) ChargerDetails(visit) { power, price, worked -> rateAt(ctx, e, ch) { ChargerVisits.details(ctx, ch.at, power, price, worked) } }
        }
    }
}

/** A judgement given in the list: the charger is the one of this charge (a visit then) - and then [what] is kept. */
private fun rateAt(ctx: android.content.Context, e: ChargeList.Entry, ch: Charger, what: () -> Unit) {
    ChargerVisits.seen(ctx, ch, e.atMs ?: System.currentTimeMillis())
    ChargerVisits.link(ctx, e.key, ch.at)
    what()
}

/** The driver says which charger it was ([c] null: none of these); learned for the place as an answer in the car's tile is. */
private fun pick(ctx: android.content.Context, e: ChargeList.Entry, s: ChargeList.Station, c: Charger?) {
    ChargerVisits.link(ctx, e.key, c?.at)
    if (c == null) return
    val place = e.place
    if (place != null && s.choices.size > 1)
        ChargeAsk.chose(ctx, ChargeAsk.Ask(s.choices, e.socStart ?: 0.0, e.socEnd ?: 0.0, place), c, e.atMs ?: System.currentTimeMillis())
    else ChargerVisits.seen(ctx, c, e.atMs ?: System.currentTimeMillis())
    DiagLog.add("charge put to ${NaviTripPlan.chargerName(c)} in the list", area = "CHARGE")
}

/**
 * "Ladestationen" in the logbook (System L-113, Navi L-004): only the chargers the driver judged, the best first - stars,
 * details, how often charged there; a charger not charged at after all can go. The ratings' sharing is switched here.
 */
@Composable
internal fun ChargersView() {
    val ctx = LocalContext.current
    val changes = ChargerVisits.changes.intValue
    val visits = remember(changes) { ChargerVisits.judged(ctx) }
    var open by remember { mutableStateOf<org.maplibre.spatialk.geojson.Position?>(null) }
    // Navi L-003: the ratings to the map server, anonymous; what waited goes when the page opens
    val settings = remember { Settings(ctx) }
    var share by remember { mutableStateOf(settings.shareRatings) }
    LaunchedEffect(Unit) { net.prototux.aare.ui.car.navi.RatingShare.flush(ctx) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.chargers_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.routing_visits_share), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.routing_visits_share_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(share, { share = it; settings.shareRatings = it; if (it) net.prototux.aare.ui.car.navi.RatingShare.flush(ctx) })
            }
            if (visits.isEmpty()) Text(stringResource(R.string.chargers_none), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            visits.forEach { v ->
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.fillMaxWidth().clickable { open = if (open == v.at) null else v.at }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        ChargerMark(v.network, false)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(v.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.routing_visits_line, v.visits, logDay(v.lastMillis)),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Stars(v.rating) { n -> ChargerVisits.rate(ctx, v.at, if (v.rating == n) 0 else n) }
                        }
                    }
                    if (open == v.at) Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        ChargerDetails(v) { power, price, worked -> ChargerVisits.details(ctx, v.at, power, price, worked) }
                        // not charged there after all (19.11.2026: "bei dem anderen Lader habe ich halt nicht geladen und den möchte
                        // ich dann auch in dem Menü, wo es um die Bewertung gibt, in der App dann auch löschen können")
                        TextButton(onClick = {
                            ChargerVisits.remove(ctx, v.at)
                            DiagLog.add("charge visit removed by the driver: ${v.name}", area = "CHARGE")
                            open = null
                        }, modifier = Modifier.padding(top = 6.dp)) { Text(stringResource(R.string.routing_visit_remove), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

/** Power, price, whether all worked (Navi L-004, E-003) - none is a must; the same answer again takes it off. */
@Composable
private fun ChargerDetails(v: ChargerVisits.Visit?, put: (Int, Int, Int) -> Unit) {
    val power = v?.power ?: 0; val price = v?.price ?: 0; val worked = v?.worked ?: 0
    Choices(stringResource(R.string.routing_visit_power), listOf("1" to stringResource(R.string.routing_visit_power_good),
        "2" to stringResource(R.string.routing_visit_power_mid), "3" to stringResource(R.string.routing_visit_power_poor)),
        power.toString()) { put(if (it == power.toString()) 0 else it.toInt(), price, worked) }
    Spacer(Modifier.height(10.dp))
    Choices(stringResource(R.string.routing_visit_price), listOf("1" to stringResource(R.string.routing_visit_price_good),
        "2" to stringResource(R.string.routing_visit_price_mid), "3" to stringResource(R.string.routing_visit_price_poor)),
        price.toString()) { put(power, if (it == price.toString()) 0 else it.toInt(), worked) }
    Spacer(Modifier.height(10.dp))
    Choices(stringResource(R.string.routing_visit_worked), listOf("1" to stringResource(R.string.routing_visit_worked_yes),
        "2" to stringResource(R.string.routing_visit_worked_no)),
        worked.toString()) { put(power, price, if (it == worked.toString()) 0 else it.toInt()) }
}

/** The network's logo on its plate; a house for the wallbox, a charging sign without a known network. */
@Composable
private fun ChargerMark(network: String?, home: Boolean) {
    val logo = if (home) null else NetworkLogos.of(network)
    if (logo != null) Image(painterResource(logo), null, Modifier.padding(top = 2.dp).height(24.dp).width(40.dp).clip(RoundedCornerShape(4.dp))
        .background(colorResource(R.color.mate_logo_plate)).padding(2.dp))
    else Icon(if (home) Icons.Rounded.Home else Icons.Rounded.EvStation, null, tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 2.dp).width(40.dp).height(24.dp))
}

/** Five stars, each big enough to hit; [onRate] with the star tapped. */
@Composable
private fun Stars(rating: Int, onRate: (Int) -> Unit) = Row(Modifier.padding(top = 2.dp)) {
    (1..5).forEach { n ->
        Icon(if (n <= rating) Icons.Rounded.Star else Icons.Rounded.StarBorder, null,
            tint = if (n <= rating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp).clip(CircleShape).clickable { onRate(n) }.padding(6.dp))
    }
}

@Composable
private fun Pill(text: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color) =
    Text(text, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1,
        modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp))
