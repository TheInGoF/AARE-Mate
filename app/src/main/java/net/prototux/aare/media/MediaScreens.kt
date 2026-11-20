package net.prototux.aare.media

import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import net.prototux.aare.R
import net.prototux.aare.ui.car.carFocusHighlight
import net.prototux.aare.ui.theme.LocalCarDimens

/** Lets a shell open a media app straight on its now-playing view: raise [count], then open the app. */
internal object NowPlayingRequest {
    var count by androidx.compose.runtime.mutableIntStateOf(0)
    var served = 0
}

/** What plays, for the lists to mark it (see ItemRow). */
internal class PlayingNow(val id: String?, val key: String?, val playing: Boolean) {
    fun isIt(item: MediaItem): Boolean =
        id != null && id == item.mediaId || key != null && key == item.mediaMetadata.extras?.getString("plex_key")
}

internal val LocalPlayingNow = androidx.compose.runtime.compositionLocalOf { PlayingNow(null, null, false) }

/** How many pictures of a tab not yet shown are fetched ahead: about what its first screen holds. */
private const val WARM_PICTURES = 18

/**
 * A shell with controls of its own at the driver's corner can take over what the top bar offers on its far
 * side (search, now playing, back to the lists): it sets [hosted] and draws [HostedMediaControls] there.
 * The media screen then drops those buttons, and its app badge and name with them.
 */
internal object MediaControls {
    var hosted by androidx.compose.runtime.mutableStateOf(false)
    var search by androidx.compose.runtime.mutableStateOf<(() -> Unit)?>(null)
    /** The search by voice (System L-056), offered with [search]. */
    var searchVoice by androidx.compose.runtime.mutableStateOf<(() -> Unit)?>(null)
    var nowPlaying by androidx.compose.runtime.mutableStateOf<(() -> Unit)?>(null)
    var browse by androidx.compose.runtime.mutableStateOf<(() -> Unit)?>(null)
    /** The player itself is on screen (cover, title, progress): what plays is there to read. */
    var playerShown by androidx.compose.runtime.mutableStateOf(false)
    var art by androidx.compose.runtime.mutableStateOf<android.graphics.Bitmap?>(null)
    var playing by androidx.compose.runtime.mutableStateOf(false)
    /** Mate's own music is on screen: music by voice is offered beside the search (O56). */
    var voice by androidx.compose.runtime.mutableStateOf(false)

    fun clear() { voice = false; search = null; searchVoice = null; nowPlaying = null; browse = null; art = null; playing = false; playerShown = false }
}

/** The media screen's search and now-playing buttons for a shell's own controls (see [MediaControls]). */
@Composable
internal fun HostedMediaControls(sizeDp: Int = 40) {
    val plate = net.prototux.aare.ui.car.Mate.plate
    // each hit over the bar's whole height and at least 56 dp wide, as the bar's own buttons (10 mm in the car at 183 dpi;
    // the 40 dp faces alone were 8 mm); the look stays
    @Composable fun Hit(go: () -> Unit, content: @Composable () -> Unit) =
        Box(Modifier.fillMaxHeight().widthIn(min = 56.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = go),
            contentAlignment = Alignment.Center) { content() }
    Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        // the search by typing or by voice, one pill as in Mate Navi (System L-056)
        MediaControls.search?.let { go ->
            Hit(go) { Row(Modifier.height(sizeDp.dp).clip(RoundedCornerShape(sizeDp.dp / 2)).background(plate), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.fillMaxHeight().widthIn(min = 56.dp).clickable(onClick = go), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Keyboard, stringResource(R.string.media_action_search), tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(22.dp))
                }
                MediaControls.searchVoice?.let { v ->
                    Box(Modifier.fillMaxHeight().widthIn(min = 56.dp).clickable(onClick = v), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Mic, stringResource(R.string.media_action_search_voice), tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(22.dp))
                    }
                }
            } }
        }
        MediaControls.nowPlaying?.let { go ->
            Hit(go) { Row(
                Modifier.height(sizeDp.dp).clip(RoundedCornerShape(sizeDp.dp / 2)).background(plate).clickable(onClick = go).padding(start = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtBox(MediaControls.art, Icons.Rounded.MusicNote, Modifier.size((sizeDp - 8).dp), CircleShape)
                Spacer(Modifier.width(8.dp))
                Icon(if (MediaControls.playing) Icons.Rounded.GraphicEq else Icons.Rounded.PlayArrow, stringResource(R.string.media_now_playing_label),
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            } }
        }
        MediaControls.browse?.let { go ->
            Hit(go) { Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(plate).clickable(onClick = go), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.LibraryMusic, stringResource(R.string.media_action_browse), tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(22.dp))
            } }
        }
    }
}

/**
 * Full-screen UI of one media app: a top bar (back, title or tabs, search, now playing) over the
 * browse tree, the now-playing view, the queue or the search keyboard.
 */
@Composable
internal fun MediaAppScreen(hub: DefaultMediaHub, app: MediaApp, driving: Boolean, modifier: Modifier) {
    LaunchedEffect(app.service) { hub.select(app) }
    val current by hub.connection.collectAsState()
    val connection = current?.takeIf { it.app.service == app.service }
    if (connection == null) {
        Placeholder(stringResource(R.string.media_connecting_to, app.label), loading = true, modifier = modifier.background(MaterialTheme.colorScheme.background))
        return
    }
    val ui = remember(connection) { hub.uiFor(connection) }
    val status by connection.status.collectAsState()
    val source by connection.state.collectAsState()

    LaunchedEffect(status) { (status as? MediaConnection.Status.Ready)?.let(ui::onReady) }
    // subscribe to the levels the user can see, so lists refresh when the app changes them
    val watched = ui.watched
    LaunchedEffect(connection, watched) { connection.watch(watched) }
    LaunchedEffect(ui) {
        if (!ui.opened) {
            ui.opened = true
            if (connection.state.value.info?.playing == true) ui.view = MediaView.NOW_PLAYING
        }
    }
    // a shell asked for what is playing (see NowPlayingRequest)
    val wanted = NowPlayingRequest.count
    LaunchedEffect(ui, wanted) { if (wanted != NowPlayingRequest.served) { NowPlayingRequest.served = wanted; ui.view = MediaView.NOW_PLAYING } }
    BackHandler(enabled = ui.canGoBack) { ui.back() }
    // What a shell shows at the driver's corner instead of the top bar's far side.
    val ready = status as? MediaConnection.Status.Ready
    val info = source.info
    androidx.compose.runtime.SideEffect {
        MediaControls.voice = app.service.packageName == net.prototux.aare.BuildConfig.APPLICATION_ID
        // the search wherever the app offers one, also while the player is shown (System L-056, 19.11.2026: "In der
        // Musik-App fehlt eine Suche per Hand ... so ein Tastatur-Mikrofon-Symbol") - before, only in the app's lists
        val searchable = ready?.searchSupported == true && ui.view != MediaView.SEARCH
        MediaControls.search = if (searchable) ({ ui.listenOnOpen = false; ui.view = MediaView.SEARCH }) else null
        MediaControls.searchVoice = if (searchable) ({ ui.listenOnOpen = true; ui.view = MediaView.SEARCH }) else null
        MediaControls.nowPlaying = if (ui.view == MediaView.BROWSE && info != null) ({ ui.view = MediaView.NOW_PLAYING }) else null
        MediaControls.browse = if (ui.view == MediaView.NOW_PLAYING) ({ ui.view = MediaView.BROWSE }) else null
        MediaControls.playerShown = ui.view == MediaView.NOW_PLAYING
        MediaControls.art = info?.art
        MediaControls.playing = info?.playing == true
    }
    androidx.compose.runtime.DisposableEffect(connection) { onDispose { MediaControls.clear() } }
    // Kept ready (our own music area only - other apps are not asked for what nobody opened): the other tabs with
    // their first page, and the pictures of what a tab shows first. A change of tab then has nothing to wait for.
    if (connection.canEnqueue) {
        val tabCount = ui.tabs?.size ?: 0
        val warm = androidx.compose.runtime.remember(ui, tabCount) { if (tabCount > 1) ui.warmTabs() else emptyList() }
        warm.forEach { level ->
            val first = level.loader.items.take(WARM_PICTURES)
            LaunchedEffect(level.id, first.size) {
                first.forEach { item ->
                    val md = item.mediaMetadata
                    if (md.artworkUri != null || md.artworkData != null) hub.art.art(md.artworkUri, md.artworkData, connection.packageName, 384)
                }
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalPlayingNow provides PlayingNow(source.currentId, source.currentKey, source.info?.playing == true)) {
    Column(modifier.background(MaterialTheme.colorScheme.background)) {
        TopBar(app, ui, status, source)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(
                targetState = ui.view,
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                label = "media-view",
            ) { view ->
                when (view) {
                    MediaView.BROWSE -> BrowseBody(hub, ui, status, source)
                    MediaView.NOW_PLAYING -> NowPlayingView(hub, connection, source) { ui.view = it }
                    MediaView.QUEUE -> QueueView(connection, source) { ui.view = MediaView.NOW_PLAYING }
                    MediaView.SEARCH -> SearchView(ui, driving)
                }
            }
        }
    }
    }
}

@Composable
private fun TopBar(app: MediaApp, ui: MediaUi, status: MediaConnection.Status, source: SourceState) {
    val d = LocalCarDimens.current
    // The bar is there in every view, at one height, with one place for the sign and one for the title: nothing
    // below it moves when the view changes. A shell that hosts the buttons (MediaControls) only takes those away.
    val hosted = MediaControls.hosted
    Row(
        Modifier.fillMaxWidth().height(d.touchTarget + 16.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ui.canGoBack) {
            CircleButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back), onClick = ui::back)
        } else {
            Box(Modifier.size(d.touchTarget), contentAlignment = Alignment.Center) { AppBadge(app, 48) }
        }
        Spacer(Modifier.width(12.dp))
        val tabs = ui.tabs
        Box(Modifier.weight(1f)) {
            when {
                ui.view == MediaView.NOW_PLAYING -> Title(if (hosted) stringResource(R.string.media_now_playing_label) else app.label)
                ui.view == MediaView.QUEUE -> Title(stringResource(R.string.media_queue_title))
                ui.view == MediaView.SEARCH -> Title(ui.query.ifEmpty { stringResource(R.string.media_search_app, app.label) })
                ui.stack.isNotEmpty() -> Title(ui.stack.last().title)
                !tabs.isNullOrEmpty() -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    tabs.forEachIndexed { i, item -> Pill(item.titleText, selected = i == ui.tab, onClick = { ui.selectTab(i) }) }
                }
                else -> Title(app.label)
            }
        }
        if (hosted) return@Row
        val ready = status as? MediaConnection.Status.Ready
        if (ready?.searchSupported == true && ui.view != MediaView.SEARCH) {
            CircleButton(Icons.Rounded.Keyboard, stringResource(R.string.media_action_search), onClick = { ui.listenOnOpen = false; ui.view = MediaView.SEARCH })
            Spacer(Modifier.width(8.dp))
            CircleButton(Icons.Rounded.Mic, stringResource(R.string.media_action_search_voice), onClick = { ui.listenOnOpen = true; ui.view = MediaView.SEARCH })
            Spacer(Modifier.width(8.dp))
        }
        if (ui.view == MediaView.NOW_PLAYING) {
            CircleButton(Icons.Rounded.LibraryMusic, stringResource(R.string.media_action_browse), onClick = { ui.view = MediaView.BROWSE })
        } else if (ui.view == MediaView.BROWSE && source.info != null) {
            NowPlayingChip(source) { ui.view = MediaView.NOW_PLAYING }
        }
    }
}

@Composable
private fun Title(text: String) {
    // hosted: the calmer size, a heading and not a banner
    Text(text, style = if (MediaControls.hosted) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun AppBadge(app: MediaApp, sizeDp: Int) {
    if (app.icon != null) {
        Image(app.icon.asImageBitmap(), app.label, Modifier.size(sizeDp.dp).clip(CircleShape))
    } else {
        Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text(app.label.take(1), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/** Small "now playing" shortcut: the art with an equalizer badge. */
@Composable
private fun NowPlayingChip(source: SourceState, onClick: () -> Unit) {
    val info = source.info ?: return
    Row(
        Modifier.height(72.dp).clip(RoundedCornerShape(36.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
            .carFocusHighlight(RoundedCornerShape(32.dp)).clickable(onClick = onClick).padding(start = 8.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtBox(info.art, Icons.Rounded.MusicNote, Modifier.size(48.dp), CircleShape)
        Spacer(Modifier.width(12.dp))
        Icon(
            if (info.playing) Icons.Rounded.GraphicEq else Icons.Rounded.PlayArrow, stringResource(R.string.media_now_playing_label),
            tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(32.dp),
        )
    }
}

// ---------------------------------------------------------------- browse

@Composable
private fun BrowseBody(hub: DefaultMediaHub, ui: MediaUi, status: MediaConnection.Status, source: SourceState) {
    val level = ui.level
    Column(Modifier.fillMaxSize()) {
        val error = (status as? MediaConnection.Status.Failed)?.error ?: source.error
        if (error != null) {
            ErrorCard(
                error,
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                onRetry = if (status is MediaConnection.Status.Failed) ui.connection::retry else null,
            )
        }
        when {
            level != null -> BrowseLevel(hub, ui, level)
            status is MediaConnection.Status.Connecting -> Placeholder(stringResource(R.string.media_connecting_to, ui.connection.app.label), loading = true)
            status is MediaConnection.Status.Failed -> Unit
            else -> Placeholder(stringResource(R.string.common_loading), loading = true)
        }
    }
}

/**
 * One level as a single lazy grid: grid sections take one cell per item, list rows and headers
 * span the whole line, so mixed levels (albums grid, then tracks list) scroll as one.
 */
@Composable
private fun BrowseLevel(hub: DefaultMediaHub, ui: MediaUi, level: Level) {
    val loader = level.loader
    LaunchedEffect(level) {
        if (loader.items.isEmpty() && !loader.end) loader.loadMore()
    }
    val items = loader.items
    if (items.isEmpty()) {
        when {
            loader.error != null -> ErrorCard(loader.error!!, Modifier.fillMaxWidth().padding(16.dp), onRetry = loader::retry)
            loader.loading || !loader.end -> Placeholder(stringResource(R.string.common_loading), loading = true)
            else -> Placeholder(stringResource(R.string.media_nothing_here), loading = false, icon = Icons.Rounded.Folder)
        }
        return
    }
    val sections = remember(items, level.hints) { sections(items.map { it.entryHints() }, level.hints) }
    // load the next page when the user nears the end
    LaunchedEffect(level, loader) {
        snapshotFlow { (level.grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) to level.grid.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (last >= total - 10) loader.loadMore() }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(170.dp),
        state = level.grid,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sections.forEach { section ->
            if (section.title != null) {
                item(span = { GridItemSpan(maxLineSpan) }, contentType = "header") { SectionTitle(section.title) }
            }
            section.indices.forEach { i ->
                val item = items[i]
                if (section.style.isGrid) {
                    item(key = "${i}_${item.mediaId}", contentType = "tile") { Tile(hub, item, section.style) { ui.open(item) } }
                } else {
                    item(key = "${i}_${item.mediaId}", span = { GridItemSpan(maxLineSpan) }, contentType = "row") {
                        ItemRow(hub, item, section.style) { ui.open(item) }
                    }
                }
            }
        }
        if (loader.loading) {
            item(span = { GridItemSpan(maxLineSpan) }) { Placeholder("", loading = true, modifier = Modifier.height(96.dp)) }
        }
        // a later page failed: say so at the end, retrying only that page
        loader.error?.let { e ->
            item(span = { GridItemSpan(maxLineSpan) }, contentType = "more-error") {
                ErrorCard(
                    e.copy(message = stringResource(R.string.media_couldnt_load_more)),
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    onRetry = loader::retryMore,
                )
            }
        }
    }
}

private fun placeholderFor(item: MediaItem) = when {
    item.isBrowsableItem -> Icons.Rounded.Album
    else -> Icons.Rounded.MusicNote
}

@Composable
private fun ItemRow(hub: DefaultMediaHub, item: MediaItem, style: ContentStyle, onClick: () -> Unit) {
    val md = item.mediaMetadata
    val art = rememberArt(hub.art, md.artworkUri, md.artworkData, hub.connection.value?.packageName, 192)
    // The track that plays is marked in every list it appears in.
    val now = LocalPlayingNow.current
    val playingThis = item.isPlayableItem && !item.isBrowsableItem && now.isIt(item)
    Row(
        Modifier.fillMaxWidth().heightIn(min = LocalCarDimens.current.touchTarget).clip(RoundedCornerShape(20.dp))
            .then(if (playingThis) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
            .carFocusHighlight(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (style.isCategory) {
            // category icons are monochrome glyphs: tint them instead of framing them like art
            val tint = MaterialTheme.colorScheme.primary
            if (art != null) Icon(art.asImageBitmap(), null, tint = tint, modifier = Modifier.size(40.dp))
            else Icon(Icons.Rounded.Folder, null, tint = tint, modifier = Modifier.size(40.dp))
        } else {
            ArtBox(art, placeholderFor(item), Modifier.size(64.dp), RoundedCornerShape(12.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.isExplicit) ExplicitBadge()
                if (playingThis) {
                    Icon(if (now.playing) Icons.Rounded.GraphicEq else Icons.Rounded.Pause, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(item.titleText, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (playingThis) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Unspecified)
            }
            val sub = item.subtitleText
            if (sub.isNotEmpty() && !style.isCategory) {
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            item.completion?.let { done ->
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { done }, modifier = Modifier.width(120.dp).height(4.dp).clip(RoundedCornerShape(2.dp)))
            }
        }
        if (item.isBrowsableItem) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
        } else if (item.isPlayableItem && item.mediaId.let { !it.startsWith("sh:") && !it.startsWith("sa:") && !it.startsWith("sg:") }) {
            // A track of our own music area: behind what plays, without leaving the list.
            val c = hub.connection.value
            if (c != null && c.canEnqueue) {
                var queued by androidx.compose.runtime.remember(item.mediaId) { androidx.compose.runtime.mutableStateOf(false) }
                LaunchedEffect(queued) { if (queued) { kotlinx.coroutines.delay(1_800); queued = false } }
                Box(
                    Modifier.size(LocalCarDimens.current.touchTarget).clip(CircleShape).clickable { c.enqueue(item); queued = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (queued) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.media_action_enqueue),
                        tint = if (queued) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Tile(hub: DefaultMediaHub, item: MediaItem, style: ContentStyle, onClick: () -> Unit) {
    val md = item.mediaMetadata
    val art = rememberArt(hub.art, md.artworkUri, md.artworkData, hub.connection.value?.packageName, 384)
    if (style.isCategory) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                .carFocusHighlight(RoundedCornerShape(24.dp)).clickable(onClick = onClick).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val tint = MaterialTheme.colorScheme.primary
            if (art != null) Icon(art.asImageBitmap(), null, tint = tint, modifier = Modifier.size(48.dp))
            else Icon(Icons.Rounded.Folder, null, tint = tint, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(10.dp))
            Text(item.titleText, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        return
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .carFocusHighlight(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(bottom = 8.dp),
    ) {
        ArtBox(art, placeholderFor(item), Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(20.dp))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            if (item.isExplicit) ExplicitBadge()
            Text(item.titleText, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val sub = item.subtitleText
        if (sub.isNotEmpty()) {
            Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun ExplicitBadge() {
    Box(
        Modifier.padding(end = 8.dp).size(24.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.onSurfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text("E", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.surface)
    }
}
