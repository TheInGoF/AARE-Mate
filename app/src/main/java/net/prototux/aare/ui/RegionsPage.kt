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
 * The phone app's page "Offline-Karten": the map server builds a package per region chosen at its own page (port 8095;
 * 21.11.2026 single regions again, after a time with one whole package, Navi E-018). Each region is an entry - size,
 * map date, state on the device - to load (going on after a break, the checksum checked), update or delete on its own;
 * "update all" for those with a newer map. Mate draws from all of them at once (OfflineTiles). While the server still
 * offers the old whole package it is shown too; folders on the device the catalogue no longer has can be deleted.
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
        when {
            failed -> Text(stringResource(if (net.prototux.aare.ui.car.navi.NaviServer.rejected.value) R.string.mapserver_rejected else R.string.regions_unreachable),
                color = MaterialTheme.colorScheme.error)
            c == null -> Text(stringResource(R.string.mapserver_checking))
            else -> {
                val singles = RegionPacks.singles(c)
                // once the server offers single regions the old whole package is no longer shown (21.11.2026: its file was
                // already gone at the server while the catalogue still named it); a begun one counts as no longer offered
                val whole = RegionPacks.whole(c)?.takeIf { it.files.isNotEmpty() && singles.isEmpty() }
                if (singles.isEmpty() && whole == null) Text(stringResource(R.string.regions_none_yet), style = MaterialTheme.typography.bodyMedium)
                if (singles.isNotEmpty()) RegionList(singles, c.mapDate, version) { version++ }
                whole?.let { WholePackage(it, c.mapDate) { version++ } }
                // on the device but no longer offered (the old whole package once the server builds regions, regions dropped there)
                val offered = if (singles.isEmpty()) c else RegionPacks.Catalogue(c.mapDate, c.regions.filter { it.id != RegionPacks.GESAMT })
                val orphans = RegionPacks.orphans(ctx, offered).filter { it !in loads.progress && it !in loads.waiting }
                if (orphans.isNotEmpty()) Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val size = orphans.sumOf { RegionPacks.dir(ctx, it).walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() } }
                        Text(stringResource(R.string.regions_orphans, orphans.joinToString(", ") { if (it == RegionPacks.GESAMT) ctx.getString(R.string.regions_old_whole) else it },
                            Formatter.formatShortFileSize(ctx, size)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { orphans.forEach { RegionPacks.deleteDir(ctx, it) }; version++ }) { Text(stringResource(R.string.regions_delete_singles)) }
                    }
                }
            }
        }
    }
}

/**
 * The regions as a list with a box each (21.11.2026: "am besten die regionen auswählbar machen und er lädt die dann nach
 * und nach"): ticked = wanted on this device - ticking queues the load (one after the other, RegionPacks.Loads), unticking
 * deletes after asking. Grouped by where they lie ("Europe › Germany"), with a box for the whole group.
 */
@Composable
private fun RegionList(singles: List<RegionPacks.Region>, mapDate: String, version: Int, changed: () -> Unit) {
    val ctx = LocalContext.current
    val loads = RegionPacks.Loads
    val size = { b: Long -> Formatter.formatShortFileSize(ctx, b) }
    var ask by remember { mutableStateOf<List<RegionPacks.Region>>(emptyList()) }
    fun running(r: RegionPacks.Region) = loads.progress.containsKey(r.id) || r.id in loads.waiting
    // the states on the device are read anew after each finished load and each change here (version)
    val states = remember(singles, version, loads.finished.intValue) { singles.associateWith { RegionPacks.state(ctx, it) } }
    fun wanted(r: RegionPacks.Region) = states[r] != RegionPacks.State.NONE || running(r)
    val here = singles.filter { states[it] != RegionPacks.State.NONE }
    val updates = singles.filter { states[it] == RegionPacks.State.UPDATE && !running(it) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.regions_on_device, here.size, singles.size, size(here.sumOf { it.bytes })),
            Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        if (updates.isNotEmpty()) FilledTonalButton(onClick = { updates.forEach { loads.start(ctx, it) } }) {
            Text(stringResource(R.string.regions_update_all, size(updates.sumOf { it.bytes })))
        }
    }
    val collator = java.text.Collator.getInstance(java.util.Locale.getDefault())
    singles.groupBy { it.path.joinToString(" › ") }.toSortedMap(collator).forEach { (path, group) ->
        val rs = group.sortedWith(compareBy(collator) { it.name })
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                val on = rs.count { wanted(it) }
                Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.TriStateCheckbox(
                        state = when (on) { 0 -> androidx.compose.ui.state.ToggleableState.Off; rs.size -> androidx.compose.ui.state.ToggleableState.On
                            else -> androidx.compose.ui.state.ToggleableState.Indeterminate },
                        onClick = { if (on == rs.size) ask = rs.filter { wanted(it) } else rs.filter { !wanted(it) }.forEach { loads.start(ctx, it) } })
                    Text(path.ifEmpty { stringResource(R.string.regions_title) }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.regions_group, on, rs.size, size(rs.filter { wanted(it) }.sumOf { it.bytes })),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                rs.forEach { r -> RegionRow(r, states[r] ?: RegionPacks.State.NONE, running(r), wanted(r), onTick = { t -> if (t) loads.start(ctx, r) else ask = listOf(r) }) }
            }
        }
    }
    Text(stringResource(R.string.regions_choose_at_server), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (ask.isNotEmpty()) androidx.compose.material3.AlertDialog(
        onDismissRequest = { ask = emptyList() },
        title = { Text(stringResource(R.string.regions_delete_title)) },
        text = { Text(stringResource(R.string.regions_delete_ask, ask.joinToString(", ") { it.name }, size(ask.sumOf { it.bytes }))) },
        confirmButton = { TextButton(onClick = {
            ask.forEach { r -> if (running(r)) loads.stop(r.id); RegionPacks.delete(ctx, r) }
            ask = emptyList(); changed()
        }) { Text(stringResource(R.string.regions_delete)) } },
        dismissButton = { TextButton(onClick = { ask = emptyList() }) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable
private fun RegionRow(r: RegionPacks.Region, state: RegionPacks.State, running: Boolean, wanted: Boolean, onTick: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val loads = RegionPacks.Loads
    val locale = java.util.Locale.getDefault()
    Column(Modifier.fillMaxWidth().selectable(selected = wanted, onClick = { onTick(!wanted) }).padding(end = 16.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(checked = wanted, onCheckedChange = onTick)
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.bodyLarge)
                val what = when {
                    r.id in loads.waiting -> stringResource(R.string.regions_waiting)
                    loads.bytes[r.id] != null -> loads.bytes[r.id]!!.let { (done, all) -> stringResource(R.string.regions_speed,
                        String.format(locale, "%.1f", loads.speed[r.id] ?: 0.0), Formatter.formatShortFileSize(ctx, done), Formatter.formatShortFileSize(ctx, all)) }
                    else -> stringResource(when (state) {
                        RegionPacks.State.NONE -> R.string.regions_state_none
                        RegionPacks.State.PARTLY -> R.string.regions_state_partly
                        RegionPacks.State.CURRENT -> R.string.regions_state_current
                        RegionPacks.State.UPDATE -> R.string.regions_state_update
                    })
                }
                Text(Formatter.formatShortFileSize(ctx, r.bytes) + " · " + what, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                running -> TextButton(onClick = { loads.stop(r.id) }) { Text(stringResource(R.string.regions_pause)) }
                state == RegionPacks.State.UPDATE -> TextButton(onClick = { loads.start(ctx, r) }) { Text(stringResource(R.string.regions_update)) }
                state == RegionPacks.State.PARTLY -> TextButton(onClick = { loads.start(ctx, r) }) { Text(stringResource(R.string.regions_resume)) }
            }
        }
        loads.progress[r.id]?.let { LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth().padding(start = 48.dp)) }
        loads.errors[r.id]?.let { Text(it, Modifier.padding(start = 48.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun WholePackage(r: RegionPacks.Region, mapDate: String, compact: Boolean = false, changed: () -> Unit) {
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
            if (!compact) Text(stringResource(R.string.regions_choose_at_server), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Where maps and speech models lie (System L-080): the device's storage or a memory card, with room and file system;
 * choosing another place moves the files there (copied, checked, then the old ones dropped). Shown only when there is
 * a card - or when the chosen one is missing.
 */
@Composable
internal fun StorageCard(busy: Boolean, kinds: List<String> = net.prototux.aare.Storage.MAP_KINDS, intro: Int = R.string.storage_intro,
                         moveAsk: Int = R.string.storage_move_ask, changed: () -> Unit) {
    val ctx = LocalContext.current
    val tick = StorageMove.finished.intValue
    val vols = remember(tick) { net.prototux.aare.Storage.volumes(ctx) }
    val chosen = remember(tick) { net.prototux.aare.Storage.chosen(ctx, kinds.first()) }
    val missing = remember(tick) { net.prototux.aare.Storage.cardMissing(ctx, kinds.first()) }
    // held outside the card: still shown after scrolling away and back (StorageMove)
    val moving = StorageMove.progress.value
    var error by StorageMove.error
    var target by remember { mutableStateOf<net.prototux.aare.Storage.Volume?>(null) }
    if (vols.size < 2 && !missing) return
    val size = { b: Long -> Formatter.formatShortFileSize(ctx, b) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.storage_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (missing) Text(stringResource(R.string.storage_card_missing), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            vols.forEach { v ->
                val pick = { if (v.id != chosen) { target = v; error = null } }
                // with the chosen card gone another place can still be chosen: there is nothing to move, the choice just changes
                val can = moving == null && !busy
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
                val problem = remember(t, tick) { net.prototux.aare.Storage.problem(ctx, t, kinds) }
                val need = remember(t, tick) { kinds.sumOf { net.prototux.aare.Storage.bytes(ctx, it) } }
                when (problem) {
                    is net.prototux.aare.Storage.Problem.TooLarge -> Text(stringResource(R.string.storage_fat32, size(problem.bytes)),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    is net.prototux.aare.Storage.Problem.NoRoom -> Text(stringResource(R.string.storage_no_room, size(problem.need), size(problem.free)),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(moveAsk, t.label, size(need)), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { target = null }) { Text(stringResource(android.R.string.cancel)) }
                        FilledTonalButton(enabled = !busy, onClick = {
                            target = null
                            StorageMove.start(need, { e -> ctx.getString(R.string.storage_move_failed, e.message ?: e.javaClass.simpleName) }, changed) { p ->
                                net.prototux.aare.Storage.move(ctx, t, kinds, p)
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
