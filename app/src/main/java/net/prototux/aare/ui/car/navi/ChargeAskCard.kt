package net.prototux.aare.ui.car.navi

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import net.prototux.aare.ui.car.Mate

/** How long the tile stays without a touch (Navi L-001: "verschwindet nach 10 Sekunden von selbst"). */
private const val ASK_MS = 10_000L

/**
 * The tile "Wie war die Ladung?" (Navi L-001, E-003): the charger's name, five stars and an X; gone by itself after
 * 10 s. Where chargers of several networks stand side by side it asks which it was first (L-002) - the nearest is
 * taken unless another is tapped. Only the stars here; the rest is for the phone app, at rest.
 */
@Composable
internal fun ChargeAskCard(ask: ChargeAsk.Ask, modifier: Modifier, close: () -> Unit) {
    val ctx = LocalContext.current
    var chosen by remember(ask) { mutableIntStateOf(0) }
    var given by remember(ask) { mutableIntStateOf(0) }
    var touched by remember(ask) { mutableStateOf(0) }
    // 10 s from the last touch; after the stars a moment of thanks
    LaunchedEffect(ask, touched, given) {
        kotlinx.coroutines.delay(if (given > 0) 1400L else ASK_MS)
        close()
    }
    val charger = ask.candidates[chosen.coerceIn(0, ask.candidates.lastIndex)]
    Column(modifier.widthIn(max = 620.dp).clip(RoundedCornerShape(18.dp)).background(Mate.cardSolid)
        .pointerInput(Unit) { detectTapGestures { } }.padding(start = 18.dp, top = 12.dp, end = 12.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (given > 0) R.string.charge_ask_thanks else R.string.charge_ask_title), color = Mate.textDim, fontSize = Mate.body)
                Text(NaviTripPlan.chargerName(charger), color = Color.White, fontSize = Mate.title, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(72.dp).clip(CircleShape).background(Mate.plate).clickable { close() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, stringResource(R.string.idmate_dictate_cancel), tint = Color.White, modifier = Modifier.size(34.dp))
            }
        }
        if (ask.candidates.size > 1 && given == 0) {
            Text(stringResource(R.string.charge_ask_which), color = Mate.textDim, fontSize = Mate.body, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ask.candidates.forEachIndexed { i, c ->
                    val on = i == chosen
                    Box(Modifier.height(64.dp).weight(1f, fill = false).clip(RoundedCornerShape(16.dp)).background(if (on) Mate.accent else Mate.plate)
                        .clickable { chosen = i; touched++ }.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                        Text((ChargeNetworks.name(c.network) ?: c.operator).ifBlank { "${c.kw} kW" }, color = if (on) Mate.bg else Color.White,
                            fontSize = Mate.heading, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (n in 1..5) {
                Box(Modifier.size(80.dp).clip(CircleShape).clickable(enabled = given == 0) {
                    given = n
                    // the network said (L-002): learned once the stars confirm it, a mistaken tap before them is not kept
                    if (ask.candidates.size > 1) ChargeAsk.chose(ctx.applicationContext, ask, charger)
                    ChargeAsk.rate(ctx.applicationContext, charger, n)
                }, contentAlignment = Alignment.Center) {
                    Icon(if (n <= given) Icons.Rounded.Star else Icons.Rounded.StarBorder, stringResource(R.string.media_rating_stars, n),
                        tint = if (n <= given) Mate.accent else Color.White, modifier = Modifier.size(56.dp))
                }
            }
        }
    }
}
