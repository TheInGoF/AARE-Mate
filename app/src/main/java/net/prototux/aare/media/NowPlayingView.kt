package net.prototux.aare.media

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.automirrored.rounded.StarHalf
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.prototux.aare.R
import net.prototux.aare.ui.car.carFocusHighlight
import net.prototux.aare.ui.theme.LocalCarDimens

/**
 * Now playing: big art, title/artist/album, progress (seekable when the app allows it), transport
 * buttons, the app's extra buttons and a queue button. Side by side in landscape, stacked in portrait.
 */
@Composable
internal fun NowPlayingView(hub: DefaultMediaHub, source: PlaybackSource, state: SourceState, setView: (MediaView) -> Unit) {
    val info = state.info
    if (info == null) {
        // right after a tap the app needs a moment to resolve the item: wait before saying "nothing"
        var waited by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(4_000)
            waited = true
        }
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            state.error?.let { ErrorCard(it, Modifier.fillMaxWidth().padding(16.dp)) }
            Placeholder(if (waited) stringResource(R.string.media_nothing_playing) else stringResource(R.string.media_starting), loading = !waited && state.error == null,
                modifier = Modifier.weight(1f), icon = Icons.Rounded.MusicNote)
            if (waited) {
                Pill(stringResource(R.string.media_action_browse), selected = true, onClick = { setView(MediaView.BROWSE) }, icon = Icons.Rounded.LibraryMusic)
                Spacer(Modifier.height(32.dp))
            }
        }
        return
    }
    Box(Modifier.fillMaxSize()) {
        // a soft backdrop of the art, fading into the background
        if (info.art != null) {
            Image(info.art.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.22f)
            // It fades in below the top bar and out towards the bottom: no edge where the bar ends.
            val bg = MaterialTheme.colorScheme.background
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to bg, 0.28f to Color.Transparent, 0.5f to Color.Transparent, 1f to bg)))
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
            val portrait = maxHeight > maxWidth
            val artMax = maxWidth * 0.4f
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (portrait) {
                    ArtBox(info.art, Icons.Rounded.MusicNote, Modifier.weight(1f, fill = false).sizeIn(maxWidth = 420.dp).aspectRatio(1f), RoundedCornerShape(28.dp))
                    Spacer(Modifier.height(20.dp))
                    Column(Modifier.fillMaxWidth()) { Details(state, info, source) }
                } else {
                    Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        // at most 40 % of the width: in a half of the split view the title had no room beside it
                        ArtBox(info.art, Icons.Rounded.MusicNote, Modifier.widthIn(max = artMax).aspectRatio(1f, matchHeightConstraintsFirst = true), RoundedCornerShape(28.dp))
                        Spacer(Modifier.width(28.dp))
                        Column(Modifier.weight(1f)) { Details(state, info, source) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Controls(hub, source, state, setView)
            }
        }
    }
}

@Composable
private fun Details(state: SourceState, info: PlaybackInfo, source: PlaybackSource) {
    state.error?.let {
        ErrorCard(it, Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
    }
    Text(info.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (info.artist.isNotEmpty()) {
        // over the cover's dimmed picture the grey of a second line was hard to read: light, a step below the title
        Text(info.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (info.album.isNotEmpty()) {
        Text(info.album, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    // Five stars, as the collection knows them: a tap sets that many, a tap on the one set takes the rating away.
    val stars = state.halfStars
    if (stars != null && source is MediaConnection) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (n in 1..5) {
                val full = stars >= n * 2
                val half = !full && stars == n * 2 - 1
                Box(
                    Modifier.size(LocalCarDimens.current.touchTarget * 0.9f).clip(CircleShape)
                        .clickable { source.rate(if (stars == n * 2) 0 else n * 2) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (full) Icons.Rounded.Star else if (half) Icons.AutoMirrored.Rounded.StarHalf else Icons.Rounded.StarBorder,
                        stringResource(R.string.media_rating_stars, n),
                        tint = if (full || half) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Progress(info, state.canSeek, source::seekTo)
}

/**
 * The transport row across the full width: previous / play-pause / next in the middle, the app's
 * own buttons split on both sides, the queue at the end. Extra buttons are dropped when narrow.
 */
@Composable
private fun Controls(hub: DefaultMediaHub, source: PlaybackSource, state: SourceState, setView: (MediaView) -> Unit) {
    val touch = LocalCarDimens.current.touchTarget
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val hasQueue = state.queue.isNotEmpty()
        val slots = ((maxWidth - PLAY_SIZE - touch * 2) / touch).toInt() - (if (hasQueue) 1 else 0)
        // Audio books and podcasts are wound, not skipped (O158): -10 s and +30 s, where the app has no such button
        // judged and wound in the source shown here, not in whichever the hub follows
        val wind = state.info?.let { hub.longForm(source, it) } == true && state.canSeek && state.actions.none { WIND.containsMatchIn(it.label) || WIND.containsMatchIn(it.id) }
        val actions = state.actions.take((slots - if (wind) 2 else 0).coerceAtLeast(0))
        val left = actions.take((actions.size + 1) / 2)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            left.forEach { ActionButton(hub, it) }
            if (wind) CircleButton(Icons.Rounded.Replay10, stringResource(R.string.media_action_back10), onClick = { hub.seekBy(source, -10_000) })
            CircleButton(Icons.Rounded.SkipPrevious, stringResource(R.string.media_action_previous), onClick = source::previous, enabled = state.canPrevious)
            CircleButton(
                if (state.showPause) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, stringResource(if (state.showPause) R.string.media_action_pause else R.string.media_action_play),
                onClick = source::playPause, filled = true, size = PLAY_SIZE,
            )
            CircleButton(Icons.Rounded.SkipNext, stringResource(R.string.media_action_next), onClick = source::next, enabled = state.canNext)
            if (wind) CircleButton(Icons.Rounded.Forward30, stringResource(R.string.media_action_forward30), onClick = { hub.seekBy(source, 30_000) })
            actions.drop(left.size).forEach { ActionButton(hub, it) }
            if (hasQueue) CircleButton(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.media_queue_title), onClick = { setView(MediaView.QUEUE) })
        }
    }
}

private val PLAY_SIZE = 96.dp

/** An app's own button for winding (Podimo: "Zurückspulen"): then no second one of ours. */
private val WIND = Regex("rewind|forward|replay|skip_?back|skip_?forward|seek|spul|zurück|vorwärts|\\b10\\b|\\b15\\b|\\b30\\b", RegexOption.IGNORE_CASE)

/** Position extrapolated from the last sample while playing; a slider when seeking is allowed. */
@Composable
private fun Progress(info: PlaybackInfo, canSeek: Boolean, seekTo: (Long) -> Unit) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(info.playing, info.sampledAtMs) {
        now = SystemClock.elapsedRealtime()
        while (info.playing) {
            delay(500)
            now = SystemClock.elapsedRealtime()
        }
    }
    val raw = if (info.playing) info.positionMs + (now - info.sampledAtMs) else info.positionMs
    val pos = if (info.durationMs > 0) raw.coerceIn(0, info.durationMs) else raw.coerceAtLeast(0)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    if (info.durationMs > 0) {
        val fraction = pos.toFloat() / info.durationMs
        if (canSeek) {
            Slider(
                value = if (dragging) dragValue else fraction,
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = {
                    seekTo((dragValue * info.durationMs).toLong())
                    dragging = false
                },
                // the bar drawn as before, grabbed over a full touch height (18.11.2026: 48 dp were 7.6 mm)
                modifier = Modifier.fillMaxWidth().height(LocalCarDimens.current.touchTarget),
            )
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
            Spacer(Modifier.height(8.dp))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        val shown = if (dragging) (dragValue * info.durationMs).toLong() else pos
        Text(formatTime(shown), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
        Spacer(Modifier.weight(1f))
        if (info.durationMs > 0) {
            Text(formatTime(info.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
        }
    }
}

/** An app-defined button: its icon (tinted like ours) or, failing that, a short label. */
@Composable
private fun ActionButton(hub: DefaultMediaHub, action: CustomAction) {
    val d = LocalCarDimens.current
    val icon = rememberIcon(hub.art, action.icon, 96)
    Box(
        Modifier.size(d.touchTarget).clip(CircleShape).carFocusHighlight(CircleShape).clickable { action.run() },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon.asImageBitmap(), action.label, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(d.iconSize))
        } else {
            Text(action.label.take(6), style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** The play queue; tapping an entry jumps to it. */
@Composable
internal fun QueueView(source: PlaybackSource, state: SourceState, done: () -> Unit) {
    if (state.queue.isEmpty()) {
        Placeholder(stringResource(R.string.media_queue_empty), loading = false, icon = Icons.Rounded.LibraryMusic)
        return
    }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = state.queue.indexOfFirst { it.current }.coerceAtLeast(0))
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        // keyed on the position too: buggy apps repeat queue ids, and duplicate keys crash the list
        itemsIndexed(state.queue, key = { i, entry -> "$i:${entry.id}" }) { _, entry ->
            val bg = if (entry.current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            Row(
                Modifier.fillMaxWidth().heightIn(min = LocalCarDimens.current.touchTarget).clip(RoundedCornerShape(20.dp)).background(bg)
                    .carFocusHighlight(RoundedCornerShape(20.dp))
                    .clickable { source.skipTo(entry); done() }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    if (entry.current) Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (entry.subtitle.isNotEmpty()) {
                        Text(entry.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
