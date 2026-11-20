package net.prototux.aare.ui.car

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.prototux.aare.DiagLog
import net.prototux.aare.R
import net.prototux.aare.audio.AudioTrials

/**
 * Mate Debug, an app in the car's app overview (19.11.2026: "dass wir eine Debug-App bauen, die ich in den Apps öffnen
 * kann, und dann kann ich zum Beispiel in der Rubrik Audio verschiedene Tests fahren"). Audio for now:
 * - the navigation channel: test sounds in several forms (rate, format, a sweep, the processor busy) and a sentence in
 *   Mate Navi's voice; what was heard marked with a tap - all into the log (AUDIO), next to the channel's own figures;
 * - the music after a prompt: the way it is asked back, chosen here and kept for the drives, tried with a test sound.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DebugScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    var last by remember { mutableStateOf<String?>(null) }
    var way by remember { mutableStateOf(AudioTrials.musicBack) }
    var note by remember { mutableStateOf<String?>(null) }
    LazyColumn(modifier.background(Mate.bg), contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 24.dp,
        bottom = net.prototux.aare.host.templates.LocalCarBottomInset.current + 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text(stringResource(R.string.debug_app_label), style = MaterialTheme.typography.headlineSmall, color = Mate.text)
            Text(stringResource(R.string.debug_audio), style = MaterialTheme.typography.titleMedium, color = Mate.accent, modifier = Modifier.padding(top = 4.dp))
        }
        // the navigation channel's crackle
        item { Heading(stringResource(R.string.debug_prompt_title), stringResource(R.string.debug_prompt_hint)) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AudioTrials.Tone.values().forEach { t ->
                    Tile(toneLabel(t), last == t.name) { last = t.name; note = null; AudioTrials.play(ctx, t) }
                }
                // played 15 s later: time to switch the tablet's screen off (is it the standby that crackles?)
                Tile(stringResource(R.string.debug_tone_later), last == "LATER") {
                    last = "LATER"; note = null
                    DiagLog.add("audio test: TONE_48K in 15 s, the screen to be switched off meanwhile", area = "AUDIO")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ AudioTrials.play(ctx, AudioTrials.Tone.TONE_48K, 5.0) }, 15_000)
                }
                val sentence = stringResource(R.string.debug_voice_sentence)
                val missing = stringResource(R.string.debug_voice_missing)
                Tile(stringResource(R.string.debug_voice), last == "VOICE") {
                    last = "VOICE"; note = null
                    if (net.prototux.aare.ui.car.navi.NaviKeep.sayTest(sentence)) DiagLog.add("audio test: VOICE - a sentence in Mate Navi's voice", area = "AUDIO")
                    else note = missing
                }
            }
        }
        item {
            val heard = stringResource(R.string.debug_heard_crackle); val clean = stringResource(R.string.debug_heard_clean)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(heard, false, warn = true) { last?.let { DiagLog.error("audio test: crackle heard with $it", "AUDIO"); note = heard + " · " + it } }
                Tile(clean, false) { last?.let { DiagLog.add("audio test: clean with $it", area = "AUDIO"); note = clean + " · " + it } }
            }
        }
        // the music after a prompt
        item { Heading(stringResource(R.string.debug_music_title), stringResource(R.string.debug_music_hint)) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AudioTrials.MusicBack.values().forEach { m ->
                    Choice(musicBackLabel(m), way == m) { way = m; AudioTrials.setMusicBack(ctx, m) }
                }
            }
        }
        item {
            val back = stringResource(R.string.debug_music_came_back); val low = stringResource(R.string.debug_music_stayed_low)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(stringResource(R.string.debug_music_try), false) { last = "MUSIC_BACK"; note = null; AudioTrials.play(ctx, AudioTrials.Tone.TONE_48K, 1.5) }
                Tile(back, false) { DiagLog.add("audio test: the music came back (${AudioTrials.musicBack.name})", area = "AUDIO"); note = back }
                Tile(low, false, warn = true) { DiagLog.error("audio test: the music stayed low (${AudioTrials.musicBack.name})", "AUDIO"); note = low }
            }
        }
        note?.let { item { Text(it, style = MaterialTheme.typography.bodyLarge, color = Mate.textDim) } }
    }
}

@Composable
private fun Heading(title: String, hint: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Mate.text, fontWeight = FontWeight.SemiBold)
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = Mate.textDim, modifier = Modifier.padding(top = 4.dp).widthIn(max = 900.dp))
    }
}

@Composable
private fun Tile(label: String, chosen: Boolean, warn: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.widthIn(min = 200.dp).heightIn(min = 76.dp).clip(RoundedCornerShape(18.dp))
        .background(if (chosen) Mate.accentContainer else if (warn) Color(0xFF4A2A1A) else Mate.surface)
        .clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.Center) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = if (chosen) Mate.accent else Mate.text)
    }
}

@Composable
private fun Choice(label: String, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).clip(RoundedCornerShape(16.dp)).background(if (on) Mate.accentContainer else Mate.surface)
        .clickable(onClick = onClick).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(if (on) Mate.accent else Mate.textDim.copy(alpha = 0.4f)))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = Mate.text)
    }
}

@Composable
private fun toneLabel(t: AudioTrials.Tone): String = stringResource(when (t) {
    AudioTrials.Tone.TONE_48K -> R.string.debug_tone_48k
    AudioTrials.Tone.TONE_48K_16BIT -> R.string.debug_tone_48k_16bit
    AudioTrials.Tone.TONE_24K -> R.string.debug_tone_24k
    AudioTrials.Tone.TONE_22K -> R.string.debug_tone_22k
    AudioTrials.Tone.TONE_16K -> R.string.debug_tone_16k
    AudioTrials.Tone.SWEEP_48K -> R.string.debug_sweep_48k
    AudioTrials.Tone.TONE_48K_LOAD -> R.string.debug_tone_48k_load
    AudioTrials.Tone.SWEEP_48K_LOAD -> R.string.debug_sweep_48k_load
})

@Composable
private fun musicBackLabel(m: AudioTrials.MusicBack): String = stringResource(when (m) {
    AudioTrials.MusicBack.GAIN -> R.string.debug_music_gain
    AudioTrials.MusicBack.RELEASE_FIRST -> R.string.debug_music_release_first
    AudioTrials.MusicBack.RESTART_MEDIA -> R.string.debug_music_restart
    AudioTrials.MusicBack.GAIN_TWICE -> R.string.debug_music_twice
})
