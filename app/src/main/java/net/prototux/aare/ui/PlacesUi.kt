package net.prototux.aare.ui

import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Store
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.prototux.aare.R
import net.prototux.aare.places.Places
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position
import java.util.Locale

/** A category's icon by its name (Places.ICONS). */
internal fun placeIcon(name: String): ImageVector = when (name) {
    "work" -> Icons.Rounded.Work
    "home" -> Icons.Rounded.Home
    "store" -> Icons.Rounded.Store
    "restaurant" -> Icons.Rounded.Restaurant
    "hospital" -> Icons.Rounded.LocalHospital
    "school" -> Icons.Rounded.School
    "sport" -> Icons.Rounded.FitnessCenter
    "charging" -> Icons.Rounded.EvStation
    "favorite" -> Icons.Rounded.Favorite
    else -> Icons.Rounded.Star
}

/**
 * The phone app's places (O169): the categories, in each its places; a category or a place is added here, a place's
 * position chosen on the map under a crosshair. The car's navigation offers them as its destination lists.
 */
@Composable
internal fun PlacesPage() {
    val ctx = LocalContext.current
    var book by remember { mutableStateOf(Places.load(ctx)) }
    var open by remember { mutableStateOf<String?>(null) }
    var addCategory by remember { mutableStateOf(false) }
    var addPlace by remember { mutableStateOf(false) }
    BackHandler(enabled = open != null) { open = null }
    val cat = book.categories.firstOrNull { it.id == open }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (cat == null) {
            Text(stringResource(R.string.places_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // F14 (17.11.2026, "Griff zum Ziehen"): the 6-dot grip moves a list; the row floats with the finger and
            // drops where it is let go (O227: everything movable gets the grip)
            var dragId by remember { mutableStateOf<String?>(null) }
            var dragY by remember { mutableStateOf(0f) }
            var rowPx by remember { mutableStateOf(1) }
            val pitch = with(androidx.compose.ui.platform.LocalDensity.current) { rowPx + 12.dp.toPx() }
            book.categories.forEachIndexed { i, c ->
                val n = book.places.count { it.category == c.id }
                val dragging = dragId == c.id
                Row(Modifier.fillMaxWidth().onSizeChanged { rowPx = it.height }
                    .zIndex(if (dragging) 1f else 0f).graphicsLayer { translationY = if (dragging) dragY else 0f; shadowElevation = if (dragging) 12f else 0f }
                    .clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { open = c.id }.padding(start = 18.dp, top = 18.dp, bottom = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(placeIcon(c.icon), null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.titleMedium)
                        Text(ctx.resources.getQuantityString(R.plurals.places_count, n, n), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // the order of the lists, as the car shows them - by the grip (F14 instead of O203's arrows)
                    if (book.categories.size > 1) {
                        Box(Modifier.size(48.dp).pointerInput(c.id) {
                            detectDragGestures(
                                onDragStart = { dragId = c.id; dragY = 0f },
                                onDrag = { ch, d -> ch.consume(); dragY += d.y },
                                onDragEnd = {
                                    val by = Math.round(dragY / pitch)
                                    if (by != 0) book = Places.moveCategory(ctx, c.id, by)
                                    dragId = null; dragY = 0f
                                },
                                onDragCancel = { dragId = null; dragY = 0f },
                            )
                        }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.DragIndicator, stringResource(R.string.places_move), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            FilledTonalButton(onClick = { addCategory = true }) {
                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.places_add_category))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // one way back is enough (the page's own arrow is above): "Ziele ›" leads back to the lists
                Text(stringResource(R.string.mate_panel_places) + " ›", Modifier.clip(RoundedCornerShape(12.dp)).clickable { open = null }
                    .padding(horizontal = 8.dp, vertical = 6.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                Icon(placeIcon(cat.icon), null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(cat.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { book = Places.removeCategory(ctx, cat.id); open = null }) {
                    Icon(Icons.Rounded.Delete, stringResource(R.string.places_delete_category), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // the list's order, as in the car (Navi L-030): by use, A to Z or by distance - kept for the list
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val orders = listOf(Places.Order.USE to R.string.places_order_use, Places.Order.NAME to R.string.places_order_name,
                    Places.Order.DISTANCE to R.string.places_order_distance)
                orders.forEachIndexed { i, (o, label) ->
                    SegmentedButton(selected = cat.order == o, onClick = { book = Places.setOrder(ctx, cat.id, o) },
                        shape = SegmentedButtonDefaults.itemShape(i, orders.size)) { Text(stringResource(label)) }
                }
            }
            // pinned ones first, as the car shows them (O201)
            val here = remember(cat.order) { if (cat.order == Places.Order.DISTANCE) lastKnown(ctx)?.let { it.latitude to it.longitude } else null }
            val sorted = Places.sorted(book, cat.id, cat.order, here)
            val places = sorted.pinned + sorted.others
            // by distance without a position: by use, and said so
            if (cat.order == Places.Order.DISTANCE && sorted.order != Places.Order.DISTANCE)
                Text(stringResource(R.string.mate_navi_order_no_position), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (places.isEmpty()) Text(stringResource(R.string.places_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
                places.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge)
                            Text(p.address.ifBlank { stringResource(R.string.places_on_map) },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // by distance: how far from here
                        if (sorted.order == Places.Order.DISTANCE && here != null) Text(Places.distanceText(Places.metres(here.first, here.second, p.lat, p.lon) / 1000),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // the home, in the private list only (O205: "zuhause muss bei privat extra gesetzt werden")
                        if (cat.id == HOME_LIST) IconButton(onClick = { book = Places.setHome(ctx, p.id, !p.home) }) {
                            Icon(if (p.home) Icons.Rounded.Home else Icons.Outlined.Home,
                                stringResource(if (p.home) R.string.places_unset_home else R.string.places_set_home),
                                tint = if (p.home) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // pin: always at the top of the list, here and in the car (O201)
                        IconButton(onClick = { book = Places.setPinned(ctx, p.id, !p.pinned) }) {
                            Icon(if (p.pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
                                stringResource(if (p.pinned) R.string.places_unpin else R.string.places_pin),
                                tint = if (p.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { book = Places.removePlace(ctx, p.id) }) {
                            Icon(Icons.Rounded.Delete, stringResource(R.string.places_delete_place), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // not everyone has a charger at home (O205): asked only for the home
                    if (p.home) Row(Modifier.fillMaxWidth().clickable { book = Places.setWallbox(ctx, p.id, !p.wallbox) }
                        .padding(start = 18.dp, end = 18.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.places_wallbox), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.places_wallbox_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.Switch(checked = p.wallbox, onCheckedChange = { book = Places.setWallbox(ctx, p.id, it) })
                    }
                }
            }
            FilledTonalButton(onClick = { addPlace = true }) {
                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.places_add_place))
            }
        }
    }
    if (addCategory) CategoryDialog(onDone = { name, icon -> book = Places.addCategory(ctx, name, icon); addCategory = false }, onCancel = { addCategory = false })
    if (addPlace && cat != null) PlaceDialog(cat.id, onDone = { p -> book = Places.addPlace(ctx, p); addPlace = false }, onCancel = { addPlace = false })
}

@Composable
private fun CategoryDialog(onDone: (String, String) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf(Places.ICONS.first()) }
    AlertDialog(onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.places_add_category)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.places_name)) }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Places.ICONS.forEach { i ->
                        val on = i == icon
                        Box(Modifier.size(44.dp).clip(CircleShape)
                            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable { icon = i }, contentAlignment = Alignment.Center) {
                            Icon(placeIcon(i), null, tint = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onDone(name, icon) }) { Text(stringResource(R.string.common_ok)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) } })
}

@Composable
private fun PlaceDialog(category: String, onDone: (Places.Place) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var at by remember { mutableStateOf<Position?>(null) }
    var picking by remember { mutableStateOf(false) }
    // Navi L-007 / I-002 ("Adresse eingeben → Standort wird ermittelt und eingetragen", "Punkt auf der Karte wählen →
    // Adresse wird ermittelt und eingetragen") through the map server's Photon: a typed address is looked up a moment after
    // the last key; a point chosen on the map gets its address, unless one was typed by hand
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var typed by remember { mutableStateOf(false) }       // the address was typed (not filled in from the map)
    var found by remember { mutableStateOf<String?>(null) }
    var looking by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(address, typed) {
        if (!typed || address.trim().length < 4) return@LaunchedEffect
        kotlinx.coroutines.delay(GEOCODE_WAIT_MS)
        looking = true
        val hit = net.prototux.aare.ui.car.navi.NaviSearch.search(ctx, address.trim(), at)?.firstOrNull()
        // 17.11.2026: "das Adressformat, was er anzeigt und übernimmt, sollte einheitlich bleiben ... wenn ich nur einen
        // Ort eingebe, Chemnitz ... dann steht da trotzdem als Adresse nur Chemnitz. Das darf nicht passieren": the place
        // found is named in Mate's one format ("Ort – Straße Hausnummer", the same the map's points get) and that goes
        // into the field instead of what was typed
        val canonical = hit?.let { net.prototux.aare.ui.car.navi.NaviSearch.address(ctx, it.at) }
        looking = false
        found = hit?.let { listOf(it.name, it.address).filter { s -> s.isNotBlank() }.joinToString(", ") } ?: ctx.getString(R.string.places_address_unknown)
        hit?.let { at = it.at }
        if (canonical != null) { typed = false; address = canonical }
    }
    AlertDialog(onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.places_add_place)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.places_name)) }, singleLine = true)
                OutlinedTextField(address, { address = it; typed = true; found = null }, label = { Text(stringResource(R.string.places_address)) },
                    supportingText = (if (looking) stringResource(R.string.places_address_looking) else found)?.let { t -> { Text(t) } })
                FilledTonalButton(onClick = { picking = true }) {
                    Icon(Icons.Rounded.Map, null); Spacer(Modifier.width(8.dp))
                    Text(at?.let { String.format(Locale.ROOT, "%.5f, %.5f", it.latitude, it.longitude) } ?: stringResource(R.string.places_pick_on_map))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && at != null, onClick = {
                val p = at ?: return@TextButton
                onDone(Places.Place(Places.newId(), category, name.trim(), address.trim(), p.latitude, p.longitude))
            }) { Text(stringResource(R.string.common_ok)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) } })
    if (picking) MapPicker(at, onPick = { p -> at = p; picking = false
        // the point chosen always gets its address in the one format, also over one typed before
        run {
            looking = true
            scope.launch {
                val a = net.prototux.aare.ui.car.navi.NaviSearch.address(ctx, p)
                looking = false
                if (a != null) { address = a; typed = false; found = null } else found = ctx.getString(R.string.places_address_unknown)
            }
        }
    }, onCancel = { picking = false })
}

/** Navi L-007: the pause after the last key before a typed address is looked up. */
private const val GEOCODE_WAIT_MS = 900L

/** The map under a crosshair: move it until the place lies under the cross. */
@Composable
private fun MapPicker(start: Position?, onPick: (Position) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    net.prototux.aare.ui.car.Mate.init(ctx)
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val style = remember(dark) { BaseStyle.Json(net.prototux.aare.ui.car.navi.mapStyle(ctx, dark)) }
    // 17.11.2026: "Wenn ich auf der Map suche, startet er immer in Berlin ... cooler, wenn er da startet, wo ich mich
    // gerade befinde. Und dann ein Suchfeld ... Dortmund, suchen, und er springt nach Dortmund. Oder sogar an die Adresse"
    val here = remember { start ?: lastKnown(ctx) }
    val camera = rememberCameraState(CameraPosition(target = here ?: Position(longitude = 13.3777, latitude = 52.5163), zoom = if (here != null) 15.0 else 11.0))
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searchNote by remember { mutableStateOf<String?>(null) }
    fun go() {
        val q = query.trim(); if (q.isEmpty()) return
        scope.launch {
            val hit = net.prototux.aare.ui.car.navi.NaviSearch.search(ctx, q, camera.position.target)?.firstOrNull()
            if (hit == null) { searchNote = ctx.getString(R.string.places_address_unknown); return@launch }
            searchNote = null
            camera.animateTo(CameraPosition(target = hit.at, zoom = 16.0), kotlin.time.Duration.parse("900ms"))
        }
    }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            MaplibreMap(modifier = Modifier.fillMaxSize(), baseStyle = style, cameraState = camera,
                options = remember { MapOptions(ornamentOptions = OrnamentOptions.AllDisabled) })
            // the crosshair
            Box(Modifier.align(Alignment.Center).size(36.dp)) {
                Box(Modifier.align(Alignment.Center).size(width = 36.dp, height = 3.dp).background(MaterialTheme.colorScheme.primary))
                Box(Modifier.align(Alignment.Center).size(width = 3.dp, height = 36.dp).background(MaterialTheme.colorScheme.primary))
            }
            Column(Modifier.align(Alignment.TopCenter).padding(top = 40.dp, start = 16.dp, end = 16.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true,
                        placeholder = { Text(stringResource(R.string.places_map_search)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { go() }))
                    TextButton(onClick = { go() }) { Text(stringResource(R.string.places_map_go)) }
                }
                Text(searchNote ?: stringResource(R.string.places_pick_hint), Modifier
                    .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.align(Alignment.BottomCenter).padding(32.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
                FilledTonalButton(onClick = { onPick(camera.position.target) }) { Text(stringResource(R.string.places_pick_here)) }
            }
        }
    }
}

/** Where the device was last (its own GPS or network), for the map to start there; null without permission or fix. */
@android.annotation.SuppressLint("MissingPermission")
private fun lastKnown(ctx: android.content.Context): Position? = runCatching {
    val lm = ctx.getSystemService(android.location.LocationManager::class.java)
    lm.getProviders(true).mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }.maxByOrNull { it.time }
        ?.let { Position(longitude = it.longitude, latitude = it.latitude) }
}.getOrNull()

/** The list where the home is set: the private one of the defaults (O205). */
private const val HOME_LIST = "home"
