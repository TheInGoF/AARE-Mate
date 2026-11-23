package net.prototux.aare.ui.car

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.prototux.aare.R

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFD32F2F)

/**
 * A second call coming in during a call (19.11.2026: "dann brauchen wir wie bei einer anderen Caller-App auch die
 * Möglichkeit zu sagen, okay, Anruf halten und rangehen, Anruf beenden und rangehen oder den anderen Anruf abblocken ...
 * Das wurde mir nicht richtig angezeigt, deswegen habe ich es sogar aus Versehen einfach aufgelegt"): who calls, and the
 * three answers - no plain hang-up here, so the call on is not ended by mistake.
 */
@Composable
internal fun WaitingCard(call: CarCall, actions: CarActions, modifier: Modifier = Modifier) {
    Column(modifier.widthIn(max = 760.dp).clip(RoundedCornerShape(28.dp)).background(Mate.cardSolid).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(Green), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Call, null, tint = Color.White)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.phone_waiting_title, call.name), color = Mate.textDim, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(call.waiting?.ifBlank { null } ?: stringResource(R.string.phone_incoming_call), color = Mate.text,
                    style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill(stringResource(R.string.phone_hold_answer), Green, Modifier.weight(1f).fillMaxHeight()) { actions.holdAndAnswer() }
            if (call.canEndAndAnswer) Pill(stringResource(R.string.phone_end_answer), Color(0xFF8A5A00), Modifier.weight(1f).fillMaxHeight()) { actions.endAndAnswer() }
            Pill(stringResource(R.string.phone_action_decline), Red, Modifier.weight(1f).fillMaxHeight()) { actions.declineWaiting() }
        }
    }
}

@Composable
private fun Pill(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 64.dp).clip(RoundedCornerShape(20.dp)).background(color).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
