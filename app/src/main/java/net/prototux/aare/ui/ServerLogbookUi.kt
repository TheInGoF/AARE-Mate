package net.prototux.aare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
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
 * the odometer, the month's totals - and, where the server allows it, a trip opened to change its logbook fields.
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
    var editing by remember { mutableStateOf<ServerLogbook.Item?>(null) }
    LaunchedEffect(ym, reload) {
        loading = true
        withContext(Dispatchers.IO) { runCatching { ServerLogbook.month(ctx, ym?.first, ym?.second) } }
            .onSuccess { month = it; error = null }
            .onFailure { e -> error = ShownFailure.text(ctx, e); if (ym == null) month = null }
        loading = false
    }
    LaunchedEffect(month?.edit) {
        if (month?.edit == true && options == null)
            options = withContext(Dispatchers.IO) { runCatching { ServerLogbook.options(ctx) }.getOrNull() }
    }
    editing?.let { item ->
        TripEditDialog(item, options, onDismiss = { editing = null }) { fields, done ->
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { ServerLogbook.edit(ctx, item.id, fields) } }
                done(r.exceptionOrNull()?.let { ShownFailure.text(ctx, it) })
                if (r.isSuccess) { editing = null; reload++ }
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
        if (!m.edit) Text(stringResource(R.string.logbook_server_edit_off), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ServerLogbookMonth(m) { id -> m.item(id)?.takeIf { m.edit }?.let { editing = it } }
    }
}

/** The rows and totals of [m]; [open] a trip (by id) to edit it. Without loading, for the page and its preview. */
@Composable
internal fun ServerLogbookMonth(m: ServerLogbook.Month, open: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (m.rows.isEmpty()) Text(stringResource(R.string.logbook_server_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        m.rows.forEach { r ->
            when (r.kind) {
                ServerLogbook.GAP -> GapRow(r)
                ServerLogbook.PRIVATE -> PrivateRow(r, m, open)
                else -> TripRow(r, m.edit) { r.trips.firstOrNull()?.let(open) }
            }
        }
        if (m.rows.isNotEmpty()) TotalsCard(m.totals)
    }
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
private fun TripRow(r: ServerLogbook.Row, edit: Boolean, onClick: () -> Unit) {
    val unknown = stringResource(R.string.logbook_place_unknown)
    LogCard(onClick = if (edit) onClick else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.startMs?.let { "${logDay(it)}, ${logTime(it)}" + (r.endMs?.let { e -> "–" + logTime(e) } ?: "") }.orEmpty(),
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.logbook_server_km, km(r.distance)), style = MaterialTheme.typography.titleSmall)
        }
        Text("${r.from ?: unknown} → ${r.to ?: unknown}", style = MaterialTheme.typography.bodyLarge)
        if (r.kind == ServerLogbook.OPEN) Text(stringResource(R.string.logbook_server_no_purpose), color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        else Text(listOfNotNull(r.purpose, r.destination, r.visitReason).joinToString(" · "), color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium)
        r.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic) }
        Text(stringResource(R.string.logbook_server_odo, odo(r.odoStart), odo(r.odoEnd)), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PrivateRow(r: ServerLogbook.Row, m: ServerLogbook.Month, open: (Long) -> Unit) {
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
        if (shown) r.trips.mapNotNull(m::item).forEach { t ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                .then(if (m.edit) Modifier.clickable { open(t.id) } else Modifier).padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(t.startMs?.let { "${logDay(it)}, ${logTime(it)}" }.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(t.purpose + " · " + stringResource(R.string.logbook_server_km, km(t.distance)), style = MaterialTheme.typography.bodyMedium)
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

/** Purpose, partner, occasion and note of [item]; [save] gets the changed fields and reports an error (null: saved). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TripEditDialog(item: ServerLogbook.Item, options: ServerLogbook.Options?, onDismiss: () -> Unit,
                           save: (Map<String, String>, (String?) -> Unit) -> Unit) {
    var purpose by remember(item.id) { mutableStateOf(item.purpose) }
    var partner by remember(item.id) { mutableStateOf(item.destination) }
    var occasion by remember(item.id) { mutableStateOf(item.visitReason) }
    var note by remember(item.id) { mutableStateOf(item.note) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    val private = options?.purposes?.firstOrNull { it.name == purpose }?.private ?: item.private
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.logbook_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val unknown = stringResource(R.string.logbook_place_unknown)
                Text("${item.startMs?.let { logDay(it) + ", " + logTime(it) }.orEmpty()} · ${item.from ?: unknown} → ${item.to ?: unknown}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.logbook_edit_purpose), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val names = (options?.purposes?.map { it.name }.orEmpty() + item.purpose).filter { it.isNotEmpty() }.distinct()
                    names.forEach { n -> FilterChip(purpose == n, { purpose = if (purpose == n) "" else n }, label = { Text(n) }) }
                }
                if (private) Text(stringResource(R.string.logbook_edit_private_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    SuggestField(stringResource(R.string.logbook_edit_partner), partner, options?.destinations.orEmpty()) { partner = it }
                    SuggestField(stringResource(R.string.logbook_edit_occasion), occasion, options?.visitReasons.orEmpty()) { occasion = it }
                }
                OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.logbook_edit_note)) }, modifier = Modifier.fillMaxWidth(),
                    minLines = 2)
                failed?.let { Text(stringResource(R.string.logbook_edit_failed, it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val fields = linkedMapOf<String, String>()
                if (purpose != item.purpose) fields["purpose"] = purpose
                if (partner.trim() != item.destination) fields["destination"] = partner.trim()
                if (occasion.trim() != item.visitReason) fields["visit_reason"] = occasion.trim()
                if (note.trim() != item.note) fields["note"] = note.trim()
                if (fields.isEmpty()) { onDismiss(); return@TextButton }
                busy = true; failed = null
                save(fields) { e -> busy = false; failed = e }
            }) { Text(stringResource(R.string.logbook_edit_save)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } })
}

/** A text field with the server's earlier values that fit what is typed, as chips to take. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestField(label: String, value: String, all: List<String>, set: (String) -> Unit) {
    OutlinedTextField(value, set, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    val fit = all.filter { it != value && it.contains(value.trim(), ignoreCase = true) }.take(6)
    if (fit.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        fit.forEach { s -> SuggestionChip(onClick = { set(s) }, label = { Text(s) }) }
    }
}
