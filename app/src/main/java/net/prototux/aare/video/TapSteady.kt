package net.prototux.aare.video

import android.view.MotionEvent
import kotlin.math.hypot

/**
 * System I-153 (27.11.2026: "dass irgendwelche Tasten nicht reagieren. Ich drücke auf Dashboard 3-4 Mal und es passiert
 * nichts … die Benutzeroberfläche wackelt wenigstens, also man sieht, dass er betätigt wurde"): a finger on the car's
 * screen while driving moves a little - a millimetre or two is more than the few pixels after which a list, the apps'
 * pages or the area switcher take a touch for a swipe, and the button pressed shows it but never gets the tap.
 *
 * The small moves of a resting finger are held back until it has left [radiusPx] around where it came down; a touch that
 * never left it is a tap and ends where it began. Beyond it everything goes on as it came (scrolling, dragging, the
 * split bar). One finger only; a second one passes everything at once.
 */
class TapSteady(private val radiusPx: Float) {
    private var downX = 0f
    private var downY = 0f
    private var steady = false
    private var moved = 0f
    /** Taps kept as taps though the finger moved, and the most it moved in one (px). */
    var steadied = 0; private set
    var mostPx = 0f; private set

    /** What to dispatch for [e]: [e] itself (its place changed for the end of a tap) or null to hold it back. */
    fun filter(e: MotionEvent): MotionEvent? = when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; steady = true; moved = 0f; e }
        MotionEvent.ACTION_MOVE -> {
            val d = hypot(e.x - downX, e.y - downY)
            if (steady && e.pointerCount == 1 && d <= radiusPx) { moved = maxOf(moved, d); null }
            else { steady = false; e }
        }
        MotionEvent.ACTION_UP -> {
            if (steady && e.pointerCount == 1) {
                if (moved > 0f) { steadied++; mostPx = maxOf(mostPx, moved) }
                e.setLocation(downX, downY)
            }
            steady = false; e
        }
        else -> { steady = false; e }
    }
}
