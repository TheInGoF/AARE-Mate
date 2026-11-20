package net.prototux.aare.ui.car.navi

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import net.prototux.aare.ui.car.Mate
import kotlin.math.cos

/**
 * The car on Mate Navi's map: design C of projekt/navi-pfeil-optionen ("navi pfeil C", 16.11.2026) - a dark disc with a
 * light rim and an arrow in the accent inside it (no shadow beneath, O171); squashed by the camera's tilt so it lies on the
 * road. It stands out on any route line. The camera aims at the car's place on the line, so it sits at the camera's
 * focus; it turns by its own heading (the line 10 m behind to 10 m ahead) against the calmer map.
 */
@Composable
internal fun NaviArrow(tiltDegrees: Double, turn: () -> Float, modifier: Modifier) {
    val disc = Mate.surface
    val rim = Mate.text
    val arrow = Mate.accent
    val squash = cos(Math.toRadians(tiltDegrees)).toFloat().coerceIn(0.45f, 1f)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val r = w * 0.40f
        val c = Offset(w / 2, h / 2)
        // its own heading on the ground: turned in the road's plane, then laid down with the tilt
        withTransform({ scale(1f, squash, pivot = c); rotate(turn(), pivot = c) }) {
            drawCircle(disc, radius = r, center = c)
            drawCircle(rim, radius = r, center = c, style = Stroke(width = w * 0.045f))
            val a = r * 0.66f
            val path = Path().apply {
                moveTo(c.x, c.y - a)
                lineTo(c.x + a * 0.52f, c.y + a * 0.55f)
                lineTo(c.x, c.y + a * 0.28f)
                lineTo(c.x - a * 0.52f, c.y + a * 0.55f)
                close()
            }
            drawPath(path, arrow)
            drawPath(path, arrow, style = Stroke(width = w * 0.03f, join = StrokeJoin.Round))
        }
    }
}
