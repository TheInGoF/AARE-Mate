package net.prototux.aare.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import net.prototux.aare.ui.car.navi.RegionPacks

/**
 * The phone app's page "Offline-Karten" (server handover 16.11.2026; since Navi E-018 / P-057 one package): the map
 * server builds ONE whole package from the regions chosen at its own page (port 8095), the catalogue holds only that
 * ("gesamt", its regions in "enthaelt"). Shown here as one entry - size, map date, the regions in it, its state on the
 * device - to load (going on after a break, the checksum checked), update or delete. Single region packages loaded
 * before it are dropped once it is complete (RegionPacks.dropSingles); while some are still here, they are named.
 */
@Composable
internal fun RegionsPage() {
    val ctx = LocalContext.current
    var cat by remember { mutableStateOf<RegionPacks.Catalogue?>(null) }
    var failed by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }           // read the states on the device again
    val loads = RegionPacks.Loads
    LaunchedEffect(Unit) { cat = RegionPacks.catalogue(ctx); failed = cat == null }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.regions_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val c = cat
        if (version + loads.finished.intValue < 0) return@Column     // read so that a change of the files on the device draws the state anew
        val singles = RegionPacks.singlesHere(ctx)
        when {
            failed -> Text(stringResource(if (net.prototux.aare.ui.car.navi.NaviServer.rejected.value) R.string.mapserver_rejected else R.string.regions_unreachable),
                color = MaterialTheme.colorScheme.error)
            c == null -> Text(stringResource(R.string.mapserver_checking))
            else -> {
                val r = RegionPacks.whole(c)
                if (r == null || r.files.isEmpty()) Text(stringResource(R.string.regions_none_yet), style = MaterialTheme.typography.bodyMedium)
                else WholePackage(r, c.mapDate) { version++ }
            }
        }
        // left from before the whole package: they go once it is loaded, or here by hand
        if (singles.isNotEmpty()) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.regions_singles, singles.size, singles.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { RegionPacks.dropSingles(ctx); version++ }) { Text(stringResource(R.string.regions_delete_singles)) }
            }
        }
    }
}

@Composable
private fun WholePackage(r: RegionPacks.Region, mapDate: String, changed: () -> Unit) {
    val ctx = LocalContext.current
    val loads = RegionPacks.Loads
    val state = RegionPacks.state(ctx, r)
    val running = loads.progress.containsKey(r.id) || r.id in loads.waiting
    val locale = java.util.Locale.getDefault()
    val dates = java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (r.contains.isEmpty()) r.name else stringResource(R.string.regions_whole, r.contains.size), style = MaterialTheme.typography.titleMedium)
                    Text(Formatter.formatShortFileSize(ctx, r.bytes) + " · " + stringResource(when (state) {
                        RegionPacks.State.NONE -> R.string.regions_state_none
                        RegionPacks.State.PARTLY -> R.string.regions_state_partly
                        RegionPacks.State.CURRENT -> R.string.regions_state_current
                        RegionPacks.State.UPDATE -> R.string.regions_state_update
                    }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!running) {
                    if (state != RegionPacks.State.NONE) TextButton(onClick = { RegionPacks.delete(ctx, r); changed() }) {
                        Text(stringResource(R.string.regions_delete)) }
                    if (state != RegionPacks.State.CURRENT) {
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(onClick = { loads.start(ctx, r) }) { Text(stringResource(when (state) {
                            RegionPacks.State.UPDATE -> R.string.regions_update
                            RegionPacks.State.PARTLY -> R.string.regions_resume
                            else -> R.string.regions_load
                        })) }
                    }
                } else TextButton(onClick = { loads.stop(r.id) }) { Text(stringResource(R.string.regions_pause)) }
            }
            // the map's date on the server (the catalogue sends ISO), and when the one on the device was loaded
            runCatching { java.time.LocalDate.parse(mapDate).format(dates) }.getOrNull()?.let {
                Text(stringResource(R.string.regions_map_date, it), style = MaterialTheme.typography.labelLarge)
            }
            RegionPacks.loadedAt(ctx, r)?.takeIf { state != RegionPacks.State.NONE }?.let { at ->
                Text(stringResource(R.string.regions_loaded_at, java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(dates)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (r.contains.isNotEmpty()) {
                val names = r.contains.sortedWith(java.text.Collator.getInstance(locale))
                Text(stringResource(R.string.regions_contains, names.joinToString(", ")), style = MaterialTheme.typography.bodyMedium)
            }
            loads.progress[r.id]?.let { LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth()) }
            // how fast and how far (O186)
            loads.bytes[r.id]?.let { (done, all) ->
                Text(stringResource(R.string.regions_speed, String.format(locale, "%.1f", loads.speed[r.id] ?: 0.0),
                    Formatter.formatShortFileSize(ctx, done), Formatter.formatShortFileSize(ctx, all)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (r.id in loads.waiting) Text(stringResource(R.string.regions_waiting), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            loads.errors[r.id]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(R.string.regions_choose_at_server), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
