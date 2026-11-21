package net.prototux.aare.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.selectable
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
import kotlinx.coroutines.launch
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
        StorageCard(busy = loads.progress.isNotEmpty() || loads.waiting.isNotEmpty()) { version++ }
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

/**
 * Where maps and speech models lie (System L-080): the device's storage or a memory card, with room and file system;
 * choosing another place moves the files there (copied, checked, then the old ones dropped). Shown only when there is
 * a card - or when the chosen one is missing.
 */
@Composable
private fun StorageCard(busy: Boolean, changed: () -> Unit) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val vols = remember(tick) { net.prototux.aare.Storage.volumes(ctx) }
    val chosen = remember(tick) { net.prototux.aare.Storage.chosen(ctx) }
    val missing = remember(tick) { net.prototux.aare.Storage.cardMissing(ctx) }
    var moving by remember { mutableStateOf<Triple<net.prototux.aare.Storage.Phase, Long, Long>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<net.prototux.aare.Storage.Volume?>(null) }
    if (vols.size < 2 && !missing) return
    val size = { b: Long -> Formatter.formatShortFileSize(ctx, b) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.storage_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.storage_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (missing) Text(stringResource(R.string.storage_card_missing), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            vols.forEach { v ->
                val pick = { if (v.id != chosen) { target = v; error = null } }
                val can = moving == null && !busy && !missing
                // the whole line picks the place, not only the small round button
                Row(Modifier.fillMaxWidth().selectable(selected = v.id == chosen, enabled = can, onClick = pick),
                    verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected = v.id == chosen, enabled = can, onClick = pick)
                    Column(Modifier.weight(1f)) {
                        Text(v.label, style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.storage_free, size(v.free), size(v.total)) +
                            (if (v.fat32) " · FAT32" else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (busy && moving == null) Text(stringResource(R.string.storage_busy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val t = target
            if (t != null && moving == null) {
                val problem = remember(t, tick) { net.prototux.aare.Storage.problem(ctx, t) }
                val need = remember(t, tick) { net.prototux.aare.Storage.bytes(ctx, net.prototux.aare.Storage.MAPS) + net.prototux.aare.Storage.bytes(ctx, net.prototux.aare.Storage.SPEECH) }
                when (problem) {
                    is net.prototux.aare.Storage.Problem.TooLarge -> Text(stringResource(R.string.storage_fat32, size(problem.bytes)),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    is net.prototux.aare.Storage.Problem.NoRoom -> Text(stringResource(R.string.storage_no_room, size(problem.need), size(problem.free)),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.storage_move_ask, t.label, size(need)), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { target = null }) { Text(stringResource(android.R.string.cancel)) }
                        FilledTonalButton(enabled = !busy, onClick = {
                            moving = Triple(net.prototux.aare.Storage.Phase.COPY, 0L, need)
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                var shown = 0L
                                val r = runCatching { net.prototux.aare.Storage.move(ctx, t) { ph, d, a ->
                                    val now = android.os.SystemClock.elapsedRealtime()
                                    if (now - shown > 500 || d == a) { shown = now; moving = Triple(ph, d, a) }
                                } }
                                error = r.exceptionOrNull()?.let { e -> ctx.getString(R.string.storage_move_failed, e.message ?: e.javaClass.simpleName) }
                                moving = null; target = null; tick++; changed()
                            }
                        }) { Text(stringResource(R.string.storage_move)) }
                    }
                }
            }
            moving?.let { (ph, d, a) ->
                LinearProgressIndicator(progress = { if (a > 0) d.toFloat() / a else 0f }, Modifier.fillMaxWidth())
                Text(stringResource(if (ph == net.prototux.aare.Storage.Phase.COPY) R.string.storage_copying else R.string.storage_checking, size(d), size(a)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
