package net.prototux.aare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.prototux.aare.R
import net.prototux.aare.Settings
import net.prototux.aare.trips.TripDetector
import net.prototux.aare.trips.TripStore
import net.prototux.aare.trips.TripWatch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * The trip log and the charge log on the tablet (System L-076/L-077): when a trip ends, the stick's place in the setup,
 * the trips and the charges, newest first.
 */
@Composable
internal fun LogbookPage() {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    var grace by remember { mutableIntStateOf(settings.tripGraceMin) }
    var tab by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var trips by remember { mutableStateOf(emptyList<TripStore.TripRow>()) }
    var charges by remember { mutableStateOf(emptyList<TripStore.ChargeRow>()) }
    var ask by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(reload) {
        withContext(Dispatchers.IO) { TripStore.get(ctx).let { trips = it.trips(); charges = it.charges() } }
        // places still without a name (no net when the trip ended): named now
        if (reload == 0) { withContext(Dispatchers.IO) { runCatching { TripWatch.name(ctx) } }; reload++ }
    }
    ask?.let { yes ->
        AlertDialog(onDismissRequest = { ask = null },
            title = { Text(stringResource(R.string.logbook_delete_title)) },
            confirmButton = { TextButton(onClick = { yes(); ask = null; reload++ }) { Text(stringResource(R.string.logbook_delete)) } },
            dismissButton = { TextButton(onClick = { ask = null }) { Text(stringResource(android.R.string.cancel)) } })
    }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card {
            Text(stringResource(R.string.logbook_how), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.padding(4.dp))
            Text(stringResource(R.string.logbook_grace), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 3, 5, 10).forEach { m ->
                    FilterChip(grace == m, { grace = m; settings.tripGraceMin = m }, label = { Text(stringResource(R.string.logbook_minutes, m)) })
                }
            }
            Text(stringResource(R.string.logbook_stick), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(tab == 0, { tab = 0 }, label = { Text(stringResource(R.string.logbook_trips, trips.size)) })
            FilterChip(tab == 1, { tab = 1 }, label = { Text(stringResource(R.string.logbook_charges, charges.size)) })
        }
        if (tab == 0) {
            if (trips.isEmpty()) Text(stringResource(R.string.logbook_no_trips), color = MaterialTheme.colorScheme.onSurfaceVariant)
            trips.forEach { t -> TripCard(t) { ask = { TripStore.get(ctx).deleteTrip(t.id) } } }
        } else {
            if (charges.isEmpty()) Text(stringResource(R.string.logbook_no_charges), color = MaterialTheme.colorScheme.onSurfaceVariant)
            charges.forEach { c -> ChargeCard(c) { ask = { TripStore.get(ctx).deleteCharge(c.id) } } }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

private fun day(ms: Long) = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(ms))
private fun time(ms: Long) = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date(ms))
private fun num(v: Double?, digits: Int) = v?.let { String.format(Locale.getDefault(), "%.${digits}f", it) } ?: "–"

@Composable
private fun TripCard(t: TripStore.TripRow, onDelete: () -> Unit) {
    val unknown = stringResource(R.string.logbook_place_unknown)
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${day(t.startMs)}, ${time(t.startMs)}–${time(t.endMs)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.logbook_delete), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDelete).padding(6.dp))
        }
        Text("${t.startName ?: unknown} → ${t.endName ?: unknown}", style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.logbook_trip_line, num(t.distanceKm, 1), ((t.endMs - t.startMs) / 60_000).toInt(),
            num(t.consumption, 1), num(t.socStart, 0), num(t.socEnd, 0)),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChargeCard(c: TripStore.ChargeRow, onDelete: () -> Unit) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${day(c.startMs)}, ${time(c.startMs)}–${time(c.endMs)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.logbook_delete), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDelete).padding(6.dp))
        }
        Text(c.name ?: stringResource(R.string.logbook_place_unknown), style = MaterialTheme.typography.bodyLarge)
        Row {
            Text(when (c.dc) { true -> "DC"; false -> "AC"; null -> "" }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (c.dc != null) Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.logbook_charge_line, num(c.socStart, 0), num(c.socEnd, 0), num(c.energyKwh, 1), num(c.maxKw, 0)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            c.source == TripDetector.ChargeSource.LEVEL -> Text(stringResource(R.string.logbook_charge_by_level), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            c.cut -> Text(stringResource(R.string.logbook_charge_cut), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
