package net.prototux.aare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.prototux.aare.R
import net.prototux.aare.ShownFailure
import net.prototux.aare.dash.ServerLogbook
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The IDmate server's logbook in Mate's own look (System L-102): the month as the server's logbook builds it - business
 * trips with purpose, partner and occasion, private trips in a row as one line, trips without a purpose marked, gaps in
 * the odometer, the month's totals. Every trip opens (System L-110, L-111, 26.11.2026: "wie die Strecke verlief, also
 * quasi eine Map-Ansicht von A nach B … Verbräuche etc. wie in IDMate. Fahrtenbuchzuweisung wie in IDMate. Wenn ich was in
 * unserer App ändere, muss das in IDMate gespiegelt sein"): its way on Mate's map, its values as IDmate's trip page shows
 * them, and - where the server allows it - its assignment, saved straight on the server (IDmate stays the owner).
 */
@Composable
internal fun ServerLogbookView() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var ym by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var month by remember { mutableStateOf<ServerLogbook.Month?>(null) }
    var options by remember { mutableStateOf<ServerLogbook.Options?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var opened by remember { mutableStateOf<ServerLogbook.Item?>(null) }
    // assigning as IDmate's trip list does it (System L-112, 26.11.2026: "genauso funktionieren wie in der IDMate-Server-App.
    // Das ist ein bewährtes System"): ticks on trips, then at the bottom the main purpose's button or purpose, destination
    // and visit reason picked from lists with the values used most in the last 30 days on top, and "Anwenden"
    var selected by remember(ym) { mutableStateOf(setOf<Long>()) }
    var onlyOpen by remember { mutableStateOf(false) }
    var assignNote by remember { mutableStateOf<String?>(null) }
    var assigning by remember { mutableStateOf(false) }
    fun assign(ids: Collection<Long>, fields: Map<String, String>) {
        if (ids.isEmpty() || fields.isEmpty()) return
        assigning = true; assignNote = null
        scope.launch {
            var failed = 0; var why: String? = null
            withContext(Dispatchers.IO) {
                ids.forEach { id -> runCatching { ServerLogbook.edit(ctx, id, fields) }.onFailure { failed++; if (why == null) why = ShownFailure.text(ctx, it) } }
            }
            assignNote = if (failed == 0) ctx.resources.getQuantityString(R.plurals.logbook_assign_done, ids.size, ids.size)
                else ctx.getString(R.string.logbook_assign_failed, failed, why.orEmpty())
            selected = emptySet(); assigning = false; reload++
            // the counts of the lists follow what was just used
            options = withContext(Dispatchers.IO) { runCatching { ServerLogbook.options(ctx) }.getOrNull() } ?: options
        }
    }
    LaunchedEffect(ym, reload) {
        loading = true
        withContext(Dispatchers.IO) { runCatching { ServerLogbook.month(ctx, ym?.first, ym?.second) } }
            .onSuccess { month = it; error = null }
            .onFailure { e -> error = ShownFailure.text(ctx, e); if (ym == null) month = null }
        loading = false
    }
    LaunchedEffect(month != null) {
        if (month != null && options == null)
            options = withContext(Dispatchers.IO) { runCatching { ServerLogbook.options(ctx) }.getOrNull() }
    }
    opened?.let { item ->
        TripDetail(item, month?.edit == true, options, onClose = { opened = null }) { fields, done ->
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { ServerLogbook.edit(ctx, item.id, fields) } }
                done(r.getOrNull(), r.exceptionOrNull()?.let { ShownFailure.text(ctx, it) })
                if (r.isSuccess) {
                    reload++
                    options = withContext(Dispatchers.IO) { runCatching { ServerLogbook.options(ctx) }.getOrNull() } ?: options
                }
            }
        }
    }
    val m = month
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (m != null) MonthBar(m) { ym = it }
        error?.let { e ->
            LogCard {
                Text(stringResource(R.string.logbook_server_failed, e), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { reload++ }) { Text(stringResource(R.string.logbook_server_retry)) }
            }
        }
        if (m == null) {
            if (loading) Text(stringResource(R.string.logbook_server_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        if (m.open > 0) Text(pluralStringResource(R.plurals.logbook_server_open, m.open, m.open),
            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
        if (!m.edit) LogCard {
            Text(stringResource(R.string.logbook_edit_off_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.logbook_server_edit_off), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(stringResource(R.string.logbook_assign_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // all of the month (or of the trips without purpose), as IDmate's tick in the table's head
            val shownIds = m.rows.filter { !onlyOpen || it.kind == ServerLogbook.OPEN }.filter { it.kind != ServerLogbook.GAP }.flatMap { it.trips }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable { selected = if (selected.containsAll(shownIds)) emptySet() else shownIds.toSet() }
                    .padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(shownIds.isNotEmpty() && selected.containsAll(shownIds), null)
                    Text(stringResource(R.string.logbook_select_all))
                }
                if (m.open > 0 || onlyOpen) FilterChip(onlyOpen, { onlyOpen = !onlyOpen; selected = emptySet() },
                    label = { Text(stringResource(R.string.logbook_assign_only_open, m.open)) })
            }
        }
        assignNote?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
        val pick = if (m.edit) Pick(selected, { id -> selected = if (id in selected) selected - id else selected + id }) else null
        ServerLogbookMonth(m, options, pick, onlyOpen) { id -> m.item(id)?.let { opened = it } }
        // room below the last trip for the bar
        if (m.edit && selected.isNotEmpty()) androidx.compose.foundation.layout.Spacer(Modifier.height(180.dp))
    }
    if (month?.edit == true && selected.isNotEmpty())
        BatchBar(selected.size, options, assigning, onClear = { selected = emptySet() }) { assign(selected, it) }
}

/** Trips ticked to assign. */
internal class Pick(val selected: Set<Long>, val toggle: (Long) -> Unit)

/**
 * The rows and totals of [m]; [open] a trip (by id) to see it. With [pick] (the server allows editing) a tick on each trip,
 * [onlyOpen] only the trips without purpose; [options] colour the purposes as IDmate does. Without loading, for the page
 * and its preview.
 */
@Composable
internal fun ServerLogbookMonth(m: ServerLogbook.Month, options: ServerLogbook.Options? = null, pick: Pick? = null, onlyOpen: Boolean = false,
                                open: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (m.rows.isEmpty()) Text(stringResource(R.string.logbook_server_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        // newest at the top (System I-139, 26.11.2026: "Fahrtenbuch, neueste immer oben"); a gap stays between its two rows
        m.rows.asReversed().filter { !onlyOpen || it.kind == ServerLogbook.OPEN }.forEach { r ->
            when (r.kind) {
                ServerLogbook.GAP -> GapRow(r)
                ServerLogbook.PRIVATE -> PrivateRow(r, m, options, pick, open)
                else -> TripRow(r, options, pick) { r.trips.firstOrNull()?.let(open) }
            }
        }
        if (m.rows.isNotEmpty() && !onlyOpen) TotalsCard(m.totals)
    }
}

/** IDmate's "clear" in a picker; null there means "don't change". */
private const val CLEAR = "\u0000clear"

/**
 * IDmate's bar under its trip list, fixed at the bottom: how many are ticked, the main purpose's button (applies at once),
 * the pickers for purpose, destination and visit reason, "Anwenden" for what was picked.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BatchBar(count: Int, options: ServerLogbook.Options?, busy: Boolean, onClear: () -> Unit, apply: (Map<String, String>) -> Unit) {
    var purpose by remember { mutableStateOf<String?>(null) }
    var destination by remember { mutableStateOf<String?>(null) }
    var reason by remember { mutableStateOf<String?>(null) }
    androidx.compose.ui.window.Popup(alignment = Alignment.BottomCenter,
        properties = androidx.compose.ui.window.PopupProperties(focusable = false)) {
        Column(Modifier.padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.logbook_batch_selected, count), style = MaterialTheme.typography.titleMedium)
                options?.main?.let { main ->
                    val c = main.color?.let { androidx.compose.ui.graphics.Color(it) } ?: MaterialTheme.colorScheme.primary
                    androidx.compose.material3.Button(enabled = !busy, onClick = { apply(mapOf(ServerLogbook.PURPOSE to main.name)) },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = c,
                            contentColor = if (androidx.core.graphics.ColorUtils.calculateLuminance(c.toArgbInt()) > 0.5) androidx.compose.ui.graphics.Color.Black
                                else androidx.compose.ui.graphics.Color.White)) { Text(main.name) }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = onClear) { Text(stringResource(R.string.logbook_assign_clear)) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val o = options
                Picker(stringResource(R.string.logbook_pick_purpose), o?.choices(ServerLogbook.PURPOSE), purpose, o) { purpose = it }
                Picker(stringResource(R.string.logbook_pick_destination), o?.choices(ServerLogbook.DESTINATION), destination, null) { destination = it }
                Picker(stringResource(R.string.logbook_pick_reason), o?.choices(ServerLogbook.REASON), reason, null) { reason = it }
                androidx.compose.material3.Button(enabled = !busy && (purpose != null || destination != null || reason != null), onClick = {
                    apply(linkedMapOf<String, String>().apply {
                        purpose?.let { put(ServerLogbook.PURPOSE, if (it == CLEAR) "" else it) }
                        destination?.let { put(ServerLogbook.DESTINATION, if (it == CLEAR) "" else it) }
                        reason?.let { put(ServerLogbook.REASON, if (it == CLEAR) "" else it) }
                    })
                }) { Text(stringResource(R.string.logbook_batch_apply)) }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())

/**
 * IDmate's picker: a button with the choice ([placeholder] while it stays unchanged), opening a list - "nicht ändern" and
 * "löschen" on top, then the values used most lately with their count, a line, the rest alphabetically; purposes with
 * their colour ([colors]).
 */
@Composable
private fun Picker(placeholder: String, choices: ServerLogbook.Choices?, value: String?, colors: ServerLogbook.Options?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        androidx.compose.material3.OutlinedButton(onClick = { open = true }) {
            Text(when (value) { null -> placeholder; CLEAR -> stringResource(R.string.logbook_pick_clear_set, placeholder.trimStart('…')); else -> value },
                maxLines = 1)
        }
        androidx.compose.material3.DropdownMenu(open, { open = false }, Modifier.heightIn(max = 520.dp)) {
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { onPick(null); open = false }) { Text(stringResource(R.string.logbook_pick_keep)) }
                TextButton(onClick = { onPick(CLEAR); open = false }) { Text(stringResource(R.string.logbook_pick_clear)) }
            }
            androidx.compose.material3.HorizontalDivider()
            PickerItems(choices, colors) { onPick(it); open = false }
        }
    }
}

/** The values of a picker: used lately (with count) first, a line, then the rest. */
@Composable
private fun PickerItems(choices: ServerLogbook.Choices?, colors: ServerLogbook.Options?, pick: (String) -> Unit) {
    @Composable fun item(v: String, n: Int?) = androidx.compose.material3.DropdownMenuItem(onClick = { pick(v) }, text = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(n?.let { "$it×" }.orEmpty(), Modifier.widthIn(min = 32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium)
            colors?.color(v)?.let { c -> androidx.compose.foundation.layout.Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(androidx.compose.ui.graphics.Color(c))) }
            Text(v)
        }
    })
    val c = choices ?: return
    c.used.forEach { (v, n) -> item(v, n) }
    if (c.used.isNotEmpty() && c.rest.isNotEmpty()) androidx.compose.material3.HorizontalDivider()
    c.rest.forEach { item(it, null) }
}

@Composable
private fun MonthBar(m: ServerLogbook.Month, pick: (Pair<Int, Int>) -> Unit) {
    // the months with trips, newest first; this month is shown even without trips
    val key = "%04d-%02d".format(Locale.ROOT, m.year, m.month)
    val list = (m.months + key).distinct().sortedDescending()
    val i = list.indexOf(key)
    fun go(k: String) = pick(k.substring(0, 4).toInt() to k.substring(5, 7).toInt())
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { list.getOrNull(i + 1)?.let(::go) }, enabled = i + 1 < list.size) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, stringResource(R.string.logbook_month_prev))
        }
        Text(YearMonth.of(m.year, m.month).format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())),
            style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = { list.getOrNull(i - 1)?.let(::go) }, enabled = i > 0) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.logbook_month_next))
        }
    }
}

@Composable
private fun LogCard(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

private fun km(v: Double?) = logNum(v, 1)
/** The odometer as the server's logbook shows it: whole kilometres. */
private fun odo(v: Double?) = logNum(v, 0)

@Composable
private fun TripRow(r: ServerLogbook.Row, options: ServerLogbook.Options?, pick: Pick?, onClick: () -> Unit) {
    val unknown = stringResource(R.string.logbook_place_unknown)
    val id = r.trips.firstOrNull()
    LogCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (pick != null && id != null) androidx.compose.material3.Checkbox(id in pick.selected, { pick.toggle(id) })
            Text(r.startMs?.let { "${logDay(it)}, ${logTime(it)}" + (r.endMs?.let { e -> "–" + logTime(e) } ?: "") }.orEmpty(),
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.logbook_server_km, km(r.distance)), style = MaterialTheme.typography.titleSmall)
        }
        Text("${r.from ?: unknown} → ${r.to ?: unknown}", style = MaterialTheme.typography.bodyLarge)
        if (r.kind == ServerLogbook.OPEN) Text(stringResource(R.string.logbook_server_no_purpose), color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        // the purpose in its colour, as IDmate's trip list shows it
        else Text(listOfNotNull(r.purpose, r.destination, r.visitReason).joinToString(" · "),
            color = options?.color(r.purpose)?.let { androidx.compose.ui.graphics.Color(it) } ?: MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        r.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic) }
        Text(stringResource(R.string.logbook_server_odo, odo(r.odoStart), odo(r.odoEnd)), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PrivateRow(r: ServerLogbook.Row, m: ServerLogbook.Month, options: ServerLogbook.Options?, pick: Pick?, open: (Long) -> Unit) {
    var shown by remember(r.trips) { mutableStateOf(false) }
    LogCard(onClick = { shown = !shown }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(listOfNotNull(r.startMs?.let(::logDay), r.endMs?.let(::logDay)).distinct().joinToString(" – "),
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.logbook_server_km, km(r.distance)), style = MaterialTheme.typography.titleSmall)
        }
        Text(stringResource(R.string.logbook_server_private_rows, r.count), style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.logbook_server_odo, odo(r.odoStart), odo(r.odoEnd)), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        // the single trips, e.g. to make one of them a business trip
        if (shown) r.trips.mapNotNull(m::item).sortedByDescending { it.startMs ?: 0L }.forEach { t ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                .clickable { open(t.id) }.padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (pick != null) androidx.compose.material3.Checkbox(t.id in pick.selected, { pick.toggle(t.id) })
                Text(t.startMs?.let { "${logDay(it)}, ${logTime(it)}" }.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(t.purpose, style = MaterialTheme.typography.bodyMedium,
                    color = options?.color(t.purpose)?.let { androidx.compose.ui.graphics.Color(it) } ?: MaterialTheme.colorScheme.onSurface)
                Text(" · " + stringResource(R.string.logbook_server_km, km(t.distance)), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun GapRow(r: ServerLogbook.Row) {
    Text(stringResource(R.string.logbook_server_gap, km(r.distance)) + " (" + odo(r.odoStart) + " → " + odo(r.odoEnd) + ")",
        style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun TotalsCard(t: ServerLogbook.Totals) {
    @Composable fun line(label: String, value: String, bold: Boolean = false, error: Boolean = false) = Row {
        val w = if (bold) FontWeight.Bold else FontWeight.Normal
        val c = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        Text(label, Modifier.weight(1f), fontWeight = w, color = c)
        Text(value, fontWeight = w, color = c)
    }
    val kmOf = { v: Double -> logNum(v, 1) + " km" }
    LogCard {
        Text(stringResource(R.string.logbook_server_totals), style = MaterialTheme.typography.titleMedium)
        line(stringResource(R.string.logbook_server_odo_month_start), logNum(t.odoStart, 0))
        line(stringResource(R.string.logbook_server_odo_month_end), logNum(t.odoEnd, 0))
        line(stringResource(R.string.logbook_server_trips), t.trips.toString())
        t.purposes.forEach { (p, v) -> line(p, kmOf(v)) }
        if (t.open > 0) line(stringResource(R.string.logbook_server_no_purpose), kmOf(t.open), error = true)
        line(stringResource(R.string.logbook_server_private), kmOf(t.private))
        if (t.gaps > 0) line(stringResource(R.string.logbook_server_gaps), kmOf(t.gaps))
        line(stringResource(R.string.logbook_server_total), kmOf(t.total), bold = true)
    }
}

/**
 * One trip as IDmate's trip page shows it, in Mate's look: its way on the map, date, time, duration, distance, odometer,
 * charge level, energy, consumption, outside temperature - and its assignment (purpose, partner, occasion, note), changed
 * on the server where it allows it. [save] gets the changed fields and answers the trip as the server keeps it now, or an
 * error.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TripDetail(item0: ServerLogbook.Item, edit: Boolean, options: ServerLogbook.Options?, onClose: () -> Unit,
                       save: (Map<String, String>, (ServerLogbook.Item?, String?) -> Unit) -> Unit) {
    val ctx = LocalContext.current
    var item by remember(item0.id) { mutableStateOf(item0) }
    var route by remember(item0.id) { mutableStateOf<ServerLogbook.Route?>(null) }
    var routeError by remember(item0.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(item0.id) {
        withContext(Dispatchers.IO) { runCatching { ServerLogbook.route(ctx, item0.id) } }
            .onSuccess { route = it }.onFailure { routeError = ShownFailure.text(ctx, it) }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val unknown = stringResource(R.string.logbook_place_unknown)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.startMs?.let { logDay(it) + ", " + logTime(it) + (item.endMs?.let { e -> "–" + logTime(e) } ?: "") }.orEmpty(),
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text(stringResource(R.string.logbook_detail_close)) }
                }
                Text("${item.from ?: unknown} → ${item.to ?: unknown}", style = MaterialTheme.typography.bodyLarge)
                var big by remember(item0.id) { mutableStateOf(false) }
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(if (big) 640.dp else 380.dp).clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                    val r = route
                    when {
                        r != null && (r.points.size >= 2 || r.start != null || r.end != null) -> TripRouteMap(r, Modifier.fillMaxSize())
                        routeError != null -> Text(stringResource(R.string.logbook_route_failed, routeError!!), Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.error)
                        r != null -> Text(stringResource(R.string.logbook_route_none), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> Text(stringResource(R.string.logbook_route_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (route != null) androidx.compose.material3.FilledTonalButton(onClick = { big = !big },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                        Text(stringResource(if (big) R.string.logbook_map_smaller else R.string.logbook_map_bigger))
                    }
                }
                TripValues(item)
                LogCard {
                    Text(stringResource(R.string.logbook_detail_assign), style = MaterialTheme.typography.titleMedium)
                    if (edit) AssignFields(item, options, save) { item = it }
                    else {
                        Text(listOfNotNull(item.purpose.ifEmpty { null }, item.destination.ifEmpty { null }, item.visitReason.ifEmpty { null })
                            .joinToString(" · ").ifEmpty { stringResource(R.string.logbook_server_no_purpose) }, style = MaterialTheme.typography.bodyLarge)
                        if (item.note.isNotEmpty()) Text(item.note, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
                        Text(stringResource(R.string.logbook_server_edit_off), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** The trip's values as IDmate's trip page lists them. */
@Composable
internal fun TripValues(item: ServerLogbook.Item) {
    @Composable fun line(label: String, value: String) = Row {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
    LogCard {
        Text(stringResource(R.string.logbook_detail_values), style = MaterialTheme.typography.titleMedium)
        val min = if (item.startMs != null && item.endMs != null) ((item.endMs - item.startMs) / 60_000).toInt() else null
        line(stringResource(R.string.logbook_detail_duration), min?.let { if (it >= 60) "%d:%02d h".format(Locale.ROOT, it / 60, it % 60) else "$it min" } ?: "–")
        line(stringResource(R.string.logbook_detail_distance), logNum(item.distance, 1) + " km")
        line(stringResource(R.string.logbook_detail_odo), logNum(item.odoStart, 0) + " – " + logNum(item.odoEnd, 0))
        line(stringResource(R.string.logbook_detail_soc), logNum(item.socStart, 0) + " → " + logNum(item.socEnd, 0) + " %")
        line(stringResource(R.string.logbook_detail_energy), logNum(item.energy, 2) + " kWh")
        line(stringResource(R.string.logbook_detail_consumption), logNum(item.consumption, 1) + " kWh/100 km")
        item.tExt?.let { line(stringResource(R.string.logbook_detail_outside), logNum(it, 0) + " °C") }
    }
}

/**
 * Purpose, destination, visit reason and note of [item] as IDmate's trip page edits them: each value tapped opens its list
 * (used lately first, then the rest; for destination and reason "Neu …" too) and the choice is saved at once; "×" clears a
 * field after asking; the note is saved when it is left. [saved] gets the trip as the server keeps it now.
 */
@Composable
private fun AssignFields(item: ServerLogbook.Item, options: ServerLogbook.Options?,
                         save: (Map<String, String>, (ServerLogbook.Item?, String?) -> Unit) -> Unit, saved: (ServerLogbook.Item) -> Unit) {
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<Pair<String, String>?>(null) }      // field, its label: clear it?
    var typing by remember { mutableStateOf<Pair<String, String>?>(null) }      // field, its label: a new value
    fun put(field: String, value: String) {
        busy = true; failed = null; done = false
        save(mapOf(field to value)) { now, e -> busy = false; failed = e; if (now != null) { done = true; saved(now) } }
    }
    @Composable fun field(field: String, label: String, value: String, canAdd: Boolean) {
        var open by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(0.4f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.layout.Box(Modifier.weight(0.6f)) {
                Text(value.ifEmpty { "--" }, Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !busy) { open = true }
                    .padding(horizontal = 8.dp, vertical = 10.dp), fontWeight = FontWeight.SemiBold,
                    color = (if (field == ServerLogbook.PURPOSE) options?.color(value) else null)?.let { androidx.compose.ui.graphics.Color(it) }
                        ?: MaterialTheme.colorScheme.primary)
                androidx.compose.material3.DropdownMenu(open, { open = false }, Modifier.heightIn(max = 520.dp)) {
                    PickerItems(options?.choices(field), if (field == ServerLogbook.PURPOSE) options else null) { open = false; if (it != value) put(field, it) }
                    if (canAdd) {
                        androidx.compose.material3.HorizontalDivider()
                        androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.logbook_pick_new)) },
                            onClick = { open = false; typing = field to label })
                    }
                }
            }
            if (value.isNotEmpty()) IconButton(enabled = !busy, onClick = { asking = field to label }) {
                Icon(Icons.Rounded.Close, stringResource(R.string.logbook_clear_confirm, label))
            }
        }
    }
    field(ServerLogbook.PURPOSE, stringResource(R.string.logbook_field_purpose), item.purpose, false)
    field(ServerLogbook.DESTINATION, stringResource(R.string.logbook_field_destination), item.destination, true)
    field(ServerLogbook.REASON, stringResource(R.string.logbook_field_reason), item.visitReason, true)
    var note by remember(item.id) { mutableStateOf(item.note) }
    OutlinedTextField(note, { note = it; done = false }, label = { Text(stringResource(R.string.logbook_edit_note)) },
        modifier = Modifier.fillMaxWidth().onFocusChanged { f -> if (!f.isFocused && note.trim() != item.note) put("note", note.trim()) },
        minLines = 2)
    failed?.let { Text(stringResource(R.string.logbook_edit_failed, it), color = MaterialTheme.colorScheme.error) }
    if (done) Text(stringResource(R.string.logbook_detail_saved), color = MaterialTheme.colorScheme.primary)
    asking?.let { (f, label) ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { asking = null },
            title = { Text(stringResource(R.string.logbook_clear_confirm, label)) },
            confirmButton = { TextButton(onClick = { asking = null; put(f, "") }) { Text(stringResource(R.string.logbook_pick_clear)) } },
            dismissButton = { TextButton(onClick = { asking = null }) { Text(stringResource(android.R.string.cancel)) } })
    }
    typing?.let { (f, label) ->
        var text by remember(f) { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(onDismissRequest = { typing = null },
            title = { Text(label) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { typing = null; put(f, text.trim()) }) { Text(stringResource(R.string.logbook_edit_save)) } },
            dismissButton = { TextButton(onClick = { typing = null }) { Text(stringResource(android.R.string.cancel)) } })
    }
}
