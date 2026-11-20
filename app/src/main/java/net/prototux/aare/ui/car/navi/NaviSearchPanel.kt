package net.prototux.aare.ui.car.navi

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import net.prototux.aare.R
import net.prototux.aare.ui.car.Mate
import org.maplibre.spatialk.geojson.Position

/**
 * Searching a destination in the car (O169 "Suche (Sprache/Tastatur)"): what is typed goes to the map server's search
 * (Photon) as one types, the places nearest the car first; a tap plans the place like one from the lists. The keyboard
 * below the hits, all at the driver's side. While driving it types in bursts like the other keyboards of Mate - 5 s from
 * the first key, then 5 s of rest (O79, a safety rule: fixed). Navi I-033 (19.11.2026: "wenn man auf das Sperrfeld klickt,
 * bricht die Eingabe ab. Die Eingabe darf erst abbrechen, wenn man die Tastatur mit X beendet ... wäre es auch cool, wenn die
 * Tastatur anzeigen würde, wie lange sie noch offen ist. Nur ein Timer darunter"): while it rests the keyboard stays where it
 * is, dimmed, and takes the taps without doing anything (before, a text took its place and a tap went through the panel);
 * a small count below says how long it is still open or still rests; what was typed stays until X or a place is picked.
 */
@Composable
internal fun androidx.compose.foundation.layout.ColumnScope.NaviSearchPanel(near: Position?, driving: Boolean,
    categories: (NaviCategories.Kind) -> List<NaviCategories.Found> = { emptyList() }, alongRoute: Boolean = false, title: String,
    onPick: (NaviSearch.Hit) -> Unit, onBack: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    // kept over the panel closing and opening (only X and a pick end it)
    var query by remember { mutableStateOf(NaviSearchStart.kept) }
    LaunchedEffect(query) { NaviSearchStart.kept = query }
    // tests: a text given by debug_navi_search, as if typed
    LaunchedEffect(NaviDebug.searchQuery) { NaviDebug.searchQuery?.let { query = it; NaviDebug.searchQuery = null } }
    var hits by remember { mutableStateOf<List<NaviSearch.Hit>?>(emptyList()) }
    var typing by remember { mutableStateOf(false) }
    var rest by remember { mutableIntStateOf(0) }
    // seconds left to type in this burst, counted down below the keyboard
    var open by remember { mutableIntStateOf(0) }
    LaunchedEffect(typing, driving) {
        if (typing && driving) { open = TYPE_S; while (open > 0) { delay(1_000); open-- }; typing = false; rest = REST_S }
    }
    LaunchedEffect(rest > 0) { while (rest > 0) { delay(1_000); rest-- } }
    // as one types, a little after the last key - one question for a word, not one per letter
    var searching by remember { mutableStateOf(false) }
    var corrected by remember { mutableStateOf<String?>(null) }
    // F6 step 3: a category chosen (by its button or its word) - its nearest, found on the device
    var category by remember { mutableStateOf<NaviCategories.Kind?>(null) }
    LaunchedEffect(category) {
        val k = category ?: return@LaunchedEffect
        val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { categories(k) }
        hits = found.map { f -> categoryHit(context, f, alongRoute) }
        android.util.Log.i("MateNavi", "search: category $k, ${found.size} found" + if (alongRoute) " ahead on the route" else " around")
    }
    LaunchedEffect(query) {
        if (query.isBlank()) { if (category == null) hits = emptyList(); searching = false; corrected = null; return@LaunchedEffect }
        // the server's search takes a few seconds (Photon on the NAS, ~3 s): "searching" meanwhile, not "nothing found"
        searching = true
        delay(400)
        // a category asked by its word (F6 step 3): its list instead of the server's
        NaviCategories.asked(query.trim())?.let { kind -> category = kind; searching = false; return@LaunchedEffect }
        category = null
        // the driver's own places first (F6), forgiving typos; then the server's
        val own = NaviSearch.own(context, query.trim())
        if (own.isNotEmpty()) hits = own
        // a mistyped town set right (F6 step 2): its hits first, then those for what was typed - nothing lost
        val fixed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { NaviTowns.correct(context, query.trim()) }
        val server = kotlinx.coroutines.coroutineScope {
            val typed = async { NaviSearch.search(context, query.trim(), near) }
            val right = fixed?.let { f -> async { NaviSearch.search(context, f, near) } }
            val r = right?.await(); val t = typed.await()
            if (r == null) t else r + t.orEmpty().filter { h -> r.none { ChargerIndex.distanceM(it.at, h.at) < 50 } }
        }
        corrected = fixed
        hits = NaviSearch.merged(own, server)
        android.util.Log.i("MateNavi", "search: ${own.size} own, ${(hits?.size ?: 0) - own.size} from the server" + (fixed?.let { ", set right to \"$it\"" } ?: ""))
        searching = false
    }

    // X ends the typing, and so does a pick; back to the lists keeps it
    PanelHeader(title, onBack) { NaviSearchStart.kept = ""; onClose() }
    Spacer(Modifier.size(12.dp))
    // the keyboard folds away once a category is chosen or the words were spoken - the hits get the room, as in Google
    // Maps; a tap on the field brings it back (16.11.2026: the keyboard covered all but two chargers)
    // a text handed over (spoken, another app) shows its hits first, as after speaking
    var keysShown by remember { mutableStateOf(NaviDebug.searchQuery.isNullOrBlank()) }
    // F6: speaking instead of typing - the device's own recognition (as the messenger's dictation), the words become the
    // search; allowed while driving, the typing pauses do not apply to it
    var voice by remember { mutableStateOf(VoiceState.IDLE) }
    val dictation = remember { net.prototux.aare.speech.Dictation(context.applicationContext) }
    DisposableEffect(dictation) { onDispose { dictation.cancel() } }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    fun listen() {
        if (voice != VoiceState.IDLE) { dictation.stop(); return }
        voice = VoiceState.LISTEN
        dictation.run(onThinking = { main.post { voice = VoiceState.THINK } }, onHeard = { heard ->
            main.post {
                voice = VoiceState.IDLE
                android.util.Log.i("MateNavi", "search by voice: " + if (heard.isNullOrBlank()) "nothing" else "\"$heard\"")
                if (!heard.isNullOrBlank()) { query = heard.trim().trimEnd('.', '!', '?'); keysShown = false }
            }
        })
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Mate.surface), verticalAlignment = Alignment.CenterVertically) {
        Text(when (voice) {
                VoiceState.LISTEN -> stringResource(R.string.mate_navi_voice_listen)
                VoiceState.THINK -> stringResource(R.string.mate_navi_voice_think)
                VoiceState.IDLE -> query.ifEmpty { stringResource(R.string.mate_navi_search_hint) }
            }, color = if (query.isEmpty() || voice != VoiceState.IDLE) Mate.textDim else Mate.text,
            fontSize = Mate.display, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clickable { keysShown = true }.padding(start = 20.dp, top = 12.dp, bottom = 12.dp))
        Icon(Icons.Rounded.Mic, stringResource(R.string.mate_navi_voice), tint = if (voice == VoiceState.IDLE) Mate.textDim else Mate.accent,
            modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(12.dp)).clickable { listen() }.padding(12.dp).size(36.dp))
    }
    // a kind of place from a tile menu (O228): its nearest
    LaunchedEffect(Unit) {
        val tag = NaviSearchStart.tag ?: return@LaunchedEffect
        NaviSearchStart.tag = null
        val at = near ?: return@LaunchedEffect
        keysShown = false
        searching = true
        hits = NaviSearch.nearby(context, tag, at)
        searching = false
    }
    // the microphone of a tile menu (O228): listening at once
    LaunchedEffect(Unit) { if (NaviSearchStart.voice) { NaviSearchStart.voice = false; listen() } }
    Spacer(Modifier.size(10.dp))
    // what was searched instead, as Google says it
    corrected?.let { Text(stringResource(R.string.mate_navi_search_for, it), style = MaterialTheme.typography.bodyMedium, color = Mate.textDim,
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)) }
    Box(Modifier.fillMaxWidth().weight(1f)) {
        val list = hits
        when {
            // Navi I-006: with the reason (no map server set / no connection / the server's answer)
            list == null -> Text(if (NaviServer.rejected.value) stringResource(R.string.mate_navi_token_rejected) else stringResource(R.string.mate_navi_search_offline) + "\n" +
                when (NaviSearch.lastFailure) {
                    NaviSearch.Failure.NO_SERVER -> stringResource(R.string.mate_navi_search_no_server)
                    NaviSearch.Failure.SERVER -> stringResource(R.string.mate_navi_search_server_error, NaviSearch.lastCode)
                    else -> stringResource(R.string.mate_navi_search_no_connection)
                }, style = MaterialTheme.typography.titleMedium,
                color = Mate.textDim, modifier = Modifier.padding(8.dp))
            searching && list.isEmpty() -> Text(stringResource(R.string.mate_navi_searching), style = MaterialTheme.typography.titleMedium,
                color = Mate.textDim, modifier = Modifier.padding(8.dp))
            list.isEmpty() && query.isNotBlank() -> Text(stringResource(R.string.mate_navi_search_none), style = MaterialTheme.typography.titleMedium,
                color = Mate.textDim, modifier = Modifier.padding(8.dp))
            list.isEmpty() && query.isBlank() && category == null -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CategoryButton(Icons.Rounded.EvStation, stringResource(R.string.mate_navi_cat_chargers), Modifier.weight(1f)) { category = NaviCategories.Kind.CHARGER; keysShown = false }
                CategoryButton(Icons.Rounded.LocalCafe, stringResource(R.string.mate_navi_cat_rest), Modifier.weight(1f)) { category = NaviCategories.Kind.REST; keysShown = false }
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.name + it.address + it.at.latitude }) { h ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(16.dp)).background(Mate.surface)
                        .clickable { NaviSearchStart.kept = ""; onPick(h) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        // one of the driver's own places: a star, as in the lists
                        // a charger: its network's logo (O210)
                        if (h.charger) NetworkLogo(h.network, 30.dp)
                        else Icon(when { h.own -> Icons.Rounded.Star; category == NaviCategories.Kind.REST -> Icons.Rounded.LocalCafe
                            else -> Icons.Rounded.Place }, null, tint = Mate.accent, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h.name, style = MaterialTheme.typography.titleMedium, color = Mate.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (h.address.isNotBlank()) Text(h.address, style = MaterialTheme.typography.bodySmall, color = Mate.textDim,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    if (!keysShown) return
    Spacer(Modifier.size(10.dp))
    val resting = driving && rest > 0
    Box(Modifier.fillMaxWidth().weight(1.2f)) {
        net.prototux.aare.media.CarKeyboard(
            onText = { query += it; if (driving) typing = true },
            onBackspace = { query = query.dropLast(1) },
            onClear = { query = "" },
            onSearch = { hits?.firstOrNull()?.let { NaviSearchStart.kept = ""; onPick(it) } },
            searchEnabled = !hits.isNullOrEmpty(),
            modifier = Modifier.matchParentSize().alpha(if (resting) 0.3f else 1f),
        )
        // resting: the taps land here and do nothing - neither a key nor anything behind the panel
        if (resting) Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { } })
    }
    // the count below, small: how long it is still open, or still rests
    if (driving && (resting || typing)) Text(if (resting) stringResource(R.string.keyboard_resting_for, rest) else stringResource(R.string.keyboard_open_for, open),
        Modifier.fillMaxWidth().padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, color = Mate.textDim,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

/** The search panel as while driving, for the pictures of the tests (no position, no categories). */
@Composable
internal fun androidx.compose.foundation.layout.ColumnScope.NaviSearchPanelPreview() =
    NaviSearchPanel(null, driving = true, title = "Suche", onPick = {}, onBack = {}, onClose = {})

/** While driving: this long to type from the first key on, then the keyboard rests this long (O79). */
private const val TYPE_S = 5
private const val REST_S = 5

/** A category's button at the driver's side: its sign and word, large enough to hit while the car stands. */
@Composable
private fun CategoryButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.heightIn(min = 72.dp).clip(RoundedCornerShape(16.dp)).background(Mate.surface).clickable(onClick = onClick)
        .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Mate.accent, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = Mate.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A category's find as a hit: who and how strong / what it is called, how far (ahead on the route or around). */
private fun categoryHit(context: android.content.Context, f: NaviCategories.Found, along: Boolean): NaviSearch.Hit {
    val km = if (f.metres < 1000) "${(f.metres / 100).toInt() * 100} m" else String.format(java.util.Locale.getDefault(), "%.1f km", f.metres / 1000)
    val far = context.getString(if (along) R.string.mate_navi_cat_ahead else R.string.mate_navi_cat_away, km)
    val name = f.charger?.let { c -> (ChargeNetworks.name(c.network) ?: c.operator.ifBlank { context.getString(R.string.mate_navi_charger) }) + " · ${c.kw} kW" }
        ?: f.rest?.let { r -> r.name.ifBlank { context.getString(if (r.services) R.string.mate_navi_cat_services else R.string.mate_navi_cat_rest_area) } }
        ?: ""
    return NaviSearch.Hit(name, far, f.at, charger = f.charger != null, network = f.charger?.network)
}

private enum class VoiceState { IDLE, LISTEN, THINK }

/** O228: a tile menu's microphone - the search opens listening. */
internal object NaviSearchStart {
    @Volatile var voice = false; @Volatile var tag: String? = null
    /** What was typed, kept while the search panel is closed some other way than by X (Navi I-033). */
    @Volatile var kept = ""
}
