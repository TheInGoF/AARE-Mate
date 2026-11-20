package net.prototux.aare.ui.car.navi

import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import net.prototux.aare.ui.car.Mate

/**
 * The edge of a card floating over the map (O207, 16.11.2026: "an der navigationsanzeige oben links flackert die karte am
 * rand stärker"): Mate's own picture had clean edges, the flicker came from the video - a soft shadow over the moving,
 * turning map costs the encoder most of its bits at every frame. A thin solid contour keeps the card apart as well.
 */
internal fun Modifier.floatEdge(shape: Shape): Modifier = border(1.5.dp, Mate.floatEdge, shape)
