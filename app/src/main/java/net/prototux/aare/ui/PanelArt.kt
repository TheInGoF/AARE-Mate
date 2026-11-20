package net.prototux.aare.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/** What a home panel shows: a plain line drawing (wish O169: "kein icon sondern eine schlichte svg typ grafik"). */
internal enum class PanelArt { PLACES, ROUTING, VIEWS, MUSIC, SOUND, VOICES, LISTEN, DASH, SETTINGS }

/**
 * The line drawings of the phone app's home panels, in one colour with a quieter second one, drawn on a 120 x 80 grid
 * and scaled to the panel - like a small SVG, but following the theme (light/dark) and Mate's accent.
 */
@Composable
internal fun PanelArtwork(art: PanelArt, line: Color, soft: Color, modifier: Modifier) {
    Canvas(modifier) {
        val k = minOf(size.width / 120f, size.height / 80f)
        val ox = (size.width - 120f * k) / 2; val oy = (size.height - 80f * k) / 2
        fun p(x: Float, y: Float) = Offset(ox + x * k, oy + y * k)
        val w = 3.2f * k
        val stroke = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val thin = Stroke(w * 0.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun path(vararg pts: Pair<Float, Float>) = Path().apply { pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(p(x, y).x, p(x, y).y) else lineTo(p(x, y).x, p(x, y).y) } }
        when (art) {
            PanelArt.PLACES -> {
                // a winding route to a pin, two small places beside it
                val route = Path().apply {
                    moveTo(p(12f, 70f).x, p(12f, 70f).y)
                    cubicTo(p(40f, 70f).x, p(40f, 70f).y, p(30f, 40f).x, p(30f, 40f).y, p(58f, 42f).x, p(58f, 42f).y)
                    cubicTo(p(80f, 44f).x, p(80f, 44f).y, p(72f, 62f).x, p(72f, 62f).y, p(92f, 58f).x, p(92f, 58f).y)
                }
                drawPath(route, soft, style = thin)
                pin(p(92f, 50f), 13f * k, line, stroke)
                drawCircle(soft, 3.5f * k, p(30f, 22f)); drawCircle(soft, 3.5f * k, p(108f, 18f))
                drawCircle(line, 3f * k, p(12f, 70f))
            }
            PanelArt.ROUTING -> {
                // a route from a dot to a flag over two charging stops (rings with a bolt), a gauge line below
                val route = Path().apply {
                    moveTo(p(12f, 58f).x, p(12f, 58f).y)
                    cubicTo(p(30f, 58f).x, p(30f, 58f).y, p(34f, 30f).x, p(34f, 30f).y, p(56f, 30f).x, p(56f, 30f).y)
                    cubicTo(p(78f, 30f).x, p(78f, 30f).y, p(80f, 52f).x, p(80f, 52f).y, p(104f, 50f).x, p(104f, 50f).y)
                }
                drawPath(route, soft, style = thin)
                drawCircle(line, 3f * k, p(12f, 58f))
                listOf(36f to 38f, 80f to 44f).forEach { (x, y) ->
                    drawCircle(line, 8f * k, p(x, y), style = stroke)
                    drawPath(path(x + 1.5f to y - 5f, x - 2.5f to y + 0.5f, x + 2.5f to y + 0.5f, x - 1.5f to y + 5f), line, style = thin)
                }
                drawPath(path(104f to 50f, 104f to 30f, 114f to 34f, 104f to 38f), line, style = stroke)
                drawLine(soft, p(12f, 72f), p(108f, 72f), w * 0.6f, StrokeCap.Round)
                drawLine(line, p(12f, 72f), p(86f, 72f), w, StrokeCap.Round)
            }
            PanelArt.VIEWS -> {
                // three screens stacked, the front one with a map line
                drawRoundRect(soft, p(30f, 10f), Size(64f * k, 40f * k), androidx.compose.ui.geometry.CornerRadius(6f * k), style = thin)
                drawRoundRect(soft, p(22f, 18f), Size(64f * k, 40f * k), androidx.compose.ui.geometry.CornerRadius(6f * k), style = thin)
                drawRoundRect(line, p(14f, 26f), Size(64f * k, 40f * k), androidx.compose.ui.geometry.CornerRadius(6f * k), style = stroke)
                drawPath(path(22f to 56f, 36f to 44f, 48f to 50f, 66f to 36f), line, style = thin)
            }
            PanelArt.SOUND -> {
                // equaliser: sliders with knobs, a soft curve over them
                listOf(20f to 44f, 38f to 28f, 56f to 52f, 74f to 22f, 92f to 38f).forEach { (x, y) ->
                    drawLine(soft, p(x, 12f), p(x, 70f), w * 0.6f, StrokeCap.Round)
                    drawCircle(line, 5f * k, p(x, y), style = stroke)
                }
                val c = Path().apply {
                    moveTo(p(10f, 44f).x, p(10f, 44f).y)
                    cubicTo(p(28f, 20f).x, p(28f, 20f).y, p(48f, 60f).x, p(48f, 60f).y, p(64f, 34f).x, p(64f, 34f).y)
                    cubicTo(p(78f, 12f).x, p(78f, 12f).y, p(96f, 46f).x, p(96f, 46f).y, p(110f, 36f).x, p(110f, 36f).y)
                }
                drawPath(c, line.copy(alpha = 0.35f), style = thin)
            }
            PanelArt.MUSIC -> {
                // a record with a beamed pair of notes beside it, a download arrow under them (offline lists)
                drawCircle(line, 26f * k, p(40f, 40f), style = stroke)
                drawCircle(soft, 16f * k, p(40f, 40f), style = thin)
                drawCircle(line, 4f * k, p(40f, 40f))
                drawLine(line, p(84f, 16f), p(84f, 46f), w, StrokeCap.Round)
                drawLine(line, p(104f, 12f), p(104f, 42f), w, StrokeCap.Round)
                drawLine(line, p(84f, 16f), p(104f, 12f), w * 1.4f, StrokeCap.Round)
                drawCircle(line, 5f * k, p(79f, 47f)); drawCircle(line, 5f * k, p(99f, 43f))
                drawLine(soft, p(92f, 56f), p(92f, 72f), w * 0.8f, StrokeCap.Round)
                drawPath(path(85f to 66f, 92f to 73f, 99f to 66f), soft, style = thin)
            }
            PanelArt.LISTEN -> {
                // a microphone, sound waves going into it from both sides
                drawRoundRect(line, p(50f, 8f), Size(20f * k, 38f * k), androidx.compose.ui.geometry.CornerRadius(10f * k), style = stroke)
                drawArc(line, 0f, 180f, false, p(40f, 22f), Size(40f * k, 34f * k), style = stroke)
                drawLine(line, p(60f, 56f), p(60f, 70f), w, StrokeCap.Round)
                drawLine(line, p(50f, 70f), p(70f, 70f), w, StrokeCap.Round)
                listOf(14f to 10f, 24f to 18f, 96f to 18f, 106f to 10f).forEach { (x, h) ->
                    drawLine(soft, p(x, 34f - h), p(x, 34f + h), w * 0.8f, StrokeCap.Round)
                }
            }
            PanelArt.DASH -> {
                // the car screen: a wide screen with a map line on one side and a gauge on the other
                drawRoundRect(line, p(10f, 10f), Size(100f * k, 56f * k), androidx.compose.ui.geometry.CornerRadius(8f * k), style = stroke)
                drawPath(path(18f to 56f, 32f to 40f, 44f to 46f, 58f to 26f), line, style = thin)
                drawArc(line, 160f, 220f, false, p(70f, 22f), Size(30f * k, 30f * k), style = stroke)
                drawLine(line, p(85f, 37f), p(93f, 29f), w, StrokeCap.Round)
                drawLine(line, p(40f, 74f), p(80f, 74f), w, StrokeCap.Round)
            }
            PanelArt.VOICES -> {
                // a speech bubble with a voice's wave
                val b = Path().apply {
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(p(14f, 12f).x, p(14f, 12f).y, p(106f, 58f).x, p(106f, 58f).y,
                        androidx.compose.ui.geometry.CornerRadius(12f * k)))
                }
                drawPath(b, line, style = stroke)
                drawPath(path(34f to 58f, 28f to 72f, 46f to 58f), line, style = stroke)
                listOf(30f to 6f, 42f to 14f, 54f to 20f, 66f to 12f, 78f to 18f, 90f to 8f).forEach { (x, h) ->
                    drawLine(if (h > 12f) line else soft, p(x, 35f - h), p(x, 35f + h), w * 0.8f, StrokeCap.Round)
                }
            }
            PanelArt.SETTINGS -> {
                // three switches, one on
                listOf(18f to true, 40f to false, 62f to false).forEach { (y, on) ->
                    drawLine(soft, p(14f, y), p(70f, y), w * 0.6f, StrokeCap.Round)
                    drawRoundRect(if (on) line else soft, p(80f, y - 7f), Size(28f * k, 14f * k), androidx.compose.ui.geometry.CornerRadius(7f * k), style = if (on) stroke else thin)
                    drawCircle(if (on) line else soft, 4.5f * k, p(if (on) 101f else 87f, y))
                }
            }
        }
    }
}

private fun DrawScope.pin(tip: Offset, r: Float, color: Color, stroke: Stroke) {
    val c = Offset(tip.x, tip.y - r * 1.6f)
    val drop = Path().apply {
        moveTo(tip.x, tip.y)
        cubicTo(tip.x - r * 0.4f, tip.y - r * 0.5f, c.x - r, c.y + r * 0.5f, c.x - r, c.y)
        arcTo(androidx.compose.ui.geometry.Rect(c.x - r, c.y - r, c.x + r, c.y + r), 180f, 180f, false)
        cubicTo(c.x + r, c.y + r * 0.5f, tip.x + r * 0.4f, tip.y - r * 0.5f, tip.x, tip.y)
        close()
    }
    drawPath(drop, color, style = stroke)
    drawCircle(color, r * 0.32f, c)
}
