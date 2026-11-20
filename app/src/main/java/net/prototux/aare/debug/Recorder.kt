package net.prototux.aare.debug

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.Locale

/**
 * Test recording (wish O98): the developer writes a task into `files/pruef/aufgabe.txt`; the user presses "Record" in the
 * phone app, does it on the car screen, presses "Photo" after each step and "Stop". Recorded with times: every
 * touch on the car screen (where, down/up), keys from the car, what foreign apps call back, and each photo request.
 * Everything goes to `files/pruef/ereignisse.txt`; on the PC a watcher (projekt/werkzeuge/pruef-wache.sh) takes the
 * picture of the car screen for each "FOTO" line.
 */
object Recorder {
    var on by mutableStateOf(false)
        private set
    var photos by mutableIntStateOf(0)
        private set
    private var file: File? = null
    private var t0 = 0L
    private var lastMove = 0L

    fun dir(context: Context) = File(context.getExternalFilesDir(null), "pruef").apply { mkdirs() }

    /** The task written down for the user, or null. */
    fun task(context: Context): String? = File(dir(context), "aufgabe.txt").takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }

    @Synchronized fun start(context: Context) {
        file = File(dir(context), "ereignisse.txt").apply { writeText("") }
        t0 = SystemClock.elapsedRealtime()
        photos = 0
        on = true
        line("START ${java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).format(java.util.Date())}")
    }

    @Synchronized fun stop() {
        if (!on) return
        line("STOP")
        on = false
    }

    fun photo() { if (on) { photos++; line("FOTO $photos") } }

    /** Touches on the car screen (display pixels); moves at most every 100 ms. */
    fun touch(ev: MotionEvent) {
        if (!on) return
        val what = when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> "TIPP_AB"; MotionEvent.ACTION_UP -> "TIPP_AUF"
            MotionEvent.ACTION_POINTER_DOWN -> "FINGER2_AB"; MotionEvent.ACTION_POINTER_UP -> "FINGER2_AUF"
            MotionEvent.ACTION_MOVE -> { val now = SystemClock.elapsedRealtime(); if (now - lastMove < 100) return; lastMove = now; "ZIEHEN" }
            else -> return
        }
        line("$what ${ev.x.toInt()} ${ev.y.toInt()}" + if (ev.pointerCount > 1) " / ${ev.getX(1).toInt()} ${ev.getY(1).toInt()}" else "")
    }

    fun event(text: String) { if (on) line(text) }

    @Synchronized private fun line(text: String) {
        val f = file ?: return
        runCatching { f.appendText("%8.2f %s\n".format(Locale.ROOT, (SystemClock.elapsedRealtime() - t0) / 1000.0, text)) }
    }
}
