package net.prototux.aare.media

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import net.prototux.aare.ui.car.carFocusHighlight

/**
 * Search entry. The car screen is a virtual display where the phone's IME does not appear, so
 * we draw our own keyboard; like Android Auto, it is locked while driving.
 */
@Composable
internal fun SearchView(ui: MediaUi, driving: Boolean) {
    // A shell that hosts the media buttons (MediaControls) lets the driver type too, in short bursts: after a few
    // keys the keyboard rests for some seconds - eyes back on the road - and what was typed stays.
    val bursts = MediaControls.hosted
    // by voice (System L-056): opened by the microphone it listens at once; the words become the search (allowed while
    // driving, the typing pauses do not apply to it)
    val context = androidx.compose.ui.platform.LocalContext.current
    var hearing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }   // 0 idle, 1 listening, 2 recognizing
    val dictation = androidx.compose.runtime.remember { net.prototux.aare.speech.Dictation(context.applicationContext) }
    androidx.compose.runtime.DisposableEffect(dictation) { onDispose { dictation.cancel() } }
    androidx.compose.runtime.LaunchedEffect(ui.listenOnOpen) {
        if (!ui.listenOnOpen) return@LaunchedEffect
        ui.listenOnOpen = false
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        hearing = 1
        dictation.run(onThinking = { main.post { hearing = 2 } }, onHeard = { heard ->
            main.post {
                hearing = 0
                android.util.Log.i("MateMusic", "search by voice: " + if (heard.isNullOrBlank()) "nothing" else "\"$heard\"")
                if (!heard.isNullOrBlank()) { ui.query = heard.trim().trimEnd('.', '!', '?'); ui.search(ui.query) }
            }
        })
    }
    if (hearing > 0) {
        Placeholder(stringResource(if (hearing == 1) R.string.media_search_listening else R.string.media_search_thinking), loading = hearing == 2,
            icon = Icons.Rounded.Search)
        return
    }
    if (driving && !bursts) {
        Placeholder(stringResource(R.string.search_parked_only), loading = false, icon = Icons.Rounded.Search)
        return
    }
    // typing = a burst is running (it started with the first key); open = its seconds left; rest = seconds it still rests
    var typing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var rest by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(typing, driving) {
        if (typing && driving) {
            open = ATTENTION_TYPE_S
            while (open > 0) { kotlinx.coroutines.delay(1_000); open-- }
            typing = false; rest = ATTENTION_REST_S
        }
    }
    androidx.compose.runtime.LaunchedEffect(rest > 0) {
        while (rest > 0) { kotlinx.coroutines.delay(1_000); rest-- }
    }
    // resting the keyboard stays, dimmed, and takes taps without doing anything; a small count below (as Mate Navi's,
    // Navi I-033: a tap on the text that took its place went through and the typing was lost)
    val resting = driving && rest > 0
    val key = { if (driving) typing = true }
    Column(Modifier.fillMaxHeight().fillMaxWidth(KEYBOARD_SHARE).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CarKeyboard(
                onText = { ui.query += it; key() },
                onBackspace = { ui.query = ui.query.dropLast(1) },
                onClear = { ui.query = "" },
                onSearch = { ui.search(ui.query) },
                searchEnabled = ui.query.isNotBlank(),
                // compact, over the driver's half only (test drive U1): less reaching across, less looking away
                modifier = Modifier.matchParentSize().alpha(if (resting) 0.3f else 1f),
            )
            if (resting) Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { } })
        }
        if (driving && (resting || typing)) Text(if (resting) stringResource(R.string.keyboard_resting_for, rest) else stringResource(R.string.keyboard_open_for, open),
            Modifier.fillMaxWidth().padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** How much of the screen's width a keyboard takes, from the driver's side. */
internal const val KEYBOARD_SHARE = 0.62f

/** While driving: this long to type from the first key on, then the keyboard rests this long. */
private const val ATTENTION_TYPE_S = 5
private const val ATTENTION_REST_S = 5

private val ROWS_QWERTY = listOf("1234567890", "qwertyuiop", "asdfghjkl'", "zxcvbnm-")
/** German: QWERTZ with the umlauts and ß, as names of bands and songs need them. */
private val ROWS_DE = listOf("1234567890", "qwertzuiopü", "asdfghjklöä", "yxcvbnmß-'")
private val ROWS get() = if (java.util.Locale.getDefault().language == "de") ROWS_DE else ROWS_QWERTY

/** A big-key QWERTY keyboard whose rows share the available height. */
@Composable
internal fun CarKeyboard(
    onText: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    searchEnabled: Boolean,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
        ROWS.forEachIndexed { r, keys ->
            KeyRow(Modifier.weight(1f)) {
                // ß has no capital of its own on a key ("SS" would read as two letters)
                keys.forEach { c -> Key(if (c == 'ß') "ß" else c.uppercase(), Modifier.weight(1f)) { onText(c.toString()) } }
                if (r == ROWS.lastIndex) Key(null, Modifier.weight(2f), Icons.AutoMirrored.Rounded.Backspace, onClick = onBackspace)
            }
        }
        KeyRow(Modifier.weight(1f)) {
            Key(null, Modifier.weight(1.5f), Icons.Rounded.Clear, onClick = onClear)
            Key(null, Modifier.weight(5f), Icons.Rounded.SpaceBar) { onText(" ") }
            Key(stringResource(R.string.media_action_search), Modifier.weight(3f), Icons.Rounded.Search, primary = true, enabled = searchEnabled, onClick = onSearch)
        }
    }
}

@Composable
private fun KeyRow(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(max = 76.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun Key(
    label: String?,
    modifier: Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = if (primary && enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (primary && enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier.fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(bg)
            .carFocusHighlight(RoundedCornerShape(16.dp)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, label, tint = fg, modifier = Modifier.size(30.dp))
            if (label != null) {
                if (icon != null) Box(Modifier.size(8.dp))
                Text(label, style = MaterialTheme.typography.titleMedium, color = fg)
            }
        }
    }
}
