package net.prototux.aare.ui.car.navi

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import net.prototux.aare.ui.car.Mate

/**
 * A full-screen menu of large tiles over Mate Navi's map (O228, 17.11.2026: "wie bei Google oder wie es bei der Route
 * Planner App früher war ... ein paar große Kacheln ... verschiedene von diesen Fullscreen-Menüs, damit man im Auto
 * schnell und unverkachelt alles findet ... in dem Panel-Design, statt vieler kleiner Ausklapper"). The title in the
 * middle; back, keyboard and microphone together at the driver's side (Navi L-032, 19.11.2026: "die Tastatureingabe und
 * Mikrofoneingabe nach links rutschen, damit man als Fahrer da besser dran kommt ... verknüpfen mit der Fahrersitzposition")
 * - on the right where the driver sits on the right. Before, the search stood at the top right, as Google has it.
 */
internal class HubTile(val icon: ImageVector, val label: String, val tint: Color = Mate.accent, val onClick: () -> Unit)

@Composable
internal fun NaviHub(title: String, tiles: List<HubTile>, modifier: Modifier, onBack: (() -> Unit)?, onClose: () -> Unit,
                     onKeyboard: (() -> Unit)? = null, onMic: (() -> Unit)? = null, driverOnRight: Boolean = false) {
    Column(modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(Mate.cardSolid.copy(alpha = 0.97f)).padding(20.dp)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.Center) {
            // the title in the middle, clear of the buttons on either side
            Text(title, Modifier.fillMaxWidth().padding(horizontal = HEADER_SIDE), style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold, color = Mate.text, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.align(if (driverOnRight) Alignment.CenterEnd else Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val back: @Composable () -> Unit = {
                    HubRound(if (onBack != null) Icons.AutoMirrored.Rounded.ArrowBack else Icons.Rounded.Close,
                        stringResource(if (onBack != null) R.string.common_back else R.string.common_close), onBack ?: onClose)
                }
                // the search by typing or by voice, together in one pill, next to back at the driver's side
                val search: @Composable () -> Unit = {
                    if (onKeyboard != null || onMic != null) Row(Modifier.clip(RoundedCornerShape(28.dp)).background(Mate.plate),
                        verticalAlignment = Alignment.CenterVertically) {
                        onKeyboard?.let { HubPillIcon(Icons.Rounded.Keyboard, stringResource(R.string.mate_navi_search), it) }
                        onMic?.let { HubPillIcon(Icons.Rounded.Mic, stringResource(R.string.mate_navi_voice), it) }
                    }
                }
                // back stays at the outer edge, the search beside it
                if (driverOnRight) { search(); back() } else { back(); search() }
            }
        }
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            // centred like Google's tiles; scrolls when there are many
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                tiles.forEach { t -> HubTileView(t) }
            }
        }
    }
}

@Composable
private fun HubTileView(t: HubTile) {
    Column(Modifier.clip(RoundedCornerShape(22.dp)).clickable(onClick = t.onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(112.dp).clip(RoundedCornerShape(26.dp)).background(Mate.surface), contentAlignment = Alignment.Center) {
            Icon(t.icon, null, tint = t.tint, modifier = Modifier.size(60.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(t.label, style = MaterialTheme.typography.titleMedium, color = Mate.text, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(170.dp))
    }
}

@Composable
private fun HubRound(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(56.dp).clip(CircleShape).background(Mate.plate).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Mate.text, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun HubPillIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Mate.text, modifier = Modifier.size(30.dp))
    }
}

/** Room kept beside the centred title for back and the search pill. */
private val HEADER_SIDE = 220.dp
