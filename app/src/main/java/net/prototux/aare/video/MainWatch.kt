package net.prototux.aare.video

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * System I-153: whether the screen's thread (Android's main thread: every tap, every frame of the car's screen) is held up,
 * how long, and where - for the log. A watcher pings it every [EVERY_MS]; an answer later than [STALL_MS] is a stall: its
 * length and the lines of Mate it stood in go into the log, at most every [LOG_EVERY_MS]. No places, no data.
 */
internal object MainWatch {
    @Volatile private var running = false
    private val main = Handler(Looper.getMainLooper())
    private var lastLogMs = 0L
    private var stalls = 0
    private var longestMs = 0L

    fun start() {
        if (running) return
        running = true
        Thread({ loop() }, "mate-main-watch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    fun stop() { running = false }

    private fun loop() {
        while (running) {
            val sent = SystemClock.uptimeMillis()
            val answered = java.util.concurrent.atomic.AtomicLong(0L)
            main.post { answered.set(SystemClock.uptimeMillis()) }
            Thread.sleep(STALL_MS)
            if (answered.get() == 0L) {
                // where it stands now: Mate's own lines first, else the top of the stack
                val stack = Looper.getMainLooper().thread.stackTrace
                val ours = stack.filter { it.className.startsWith("net.prototux") }.take(4)
                val at = (ours.ifEmpty { stack.take(3).toList() }).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                var waited = 0L
                while (answered.get() == 0L && waited < 20_000 && running) { Thread.sleep(50); waited += 50 }
                val held = (if (answered.get() != 0L) answered.get() else SystemClock.uptimeMillis()) - sent
                stalls++; longestMs = maxOf(longestMs, held)
                val now = SystemClock.uptimeMillis()
                if (now - lastLogMs > LOG_EVERY_MS) {
                    lastLogMs = now
                    net.prototux.aare.DiagLog.add("ui: the screen's thread held %d ms (%d stall(s) since the last line, longest %d ms) - at %s"
                        .format(java.util.Locale.ROOT, held, stalls, longestMs, at), net.prototux.aare.DiagLog.Level.WARN, area = "CAR")
                    stalls = 0; longestMs = 0
                }
            } else Thread.sleep(EVERY_MS)
        }
    }

    private const val EVERY_MS = 300L
    private const val STALL_MS = 700L
    private const val LOG_EVERY_MS = 30_000L
}
