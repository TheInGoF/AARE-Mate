package net.prototux.aare.audio

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Podcasts and audio books pause for a prompt instead of being lowered under it (22.11.2026: "während Podcast oder
 * Hörbücher laufen, der Ton des Navis dann ausgegeben wird und … knistert. Das Überlagern von zwei Geräuschen, das
 * funktioniert so nicht. Wir müssen den Podcast anhalten, dann den Ton abspielen … und dann den Podcast wieder starten.
 * Im Idealfall sogar noch 3 Sekunden zurückspringen"): when a prompt starts ([hold]) and the app playing now is of the
 * kind "book" (CarSound.kind: audio books, podcasts), Mate pauses it; when the prompt is over ([letGo]) it goes back
 * [BACK_MS] and plays on. Prompts close together keep it paused through all of them. Music stays as it is (lowered).
 */
object SpokenPause {
    private const val TAG = "MateSpoken"
    /** How far back the paused podcast starts again. */
    const val BACK_MS = 3_000L
    /** After the prompt: a moment of quiet before the podcast speaks again (a next prompt keeps it paused). */
    private const val RESUME_AFTER_MS = 700L
    /** Never paused longer than this, whatever happens to the prompt. */
    private const val MAX_PAUSE_MS = 60_000L

    @Volatile private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private var paused: MediaController? = null
    private var pausedAt = 0L
    private val resume = Runnable { goOn() }

    /** Once, with any context (the car session). */
    fun init(context: Context) { app = context.applicationContext }

    /** A prompt starts (any thread). */
    fun hold() = main.post {
        main.removeCallbacks(resume)
        if (paused != null) { main.postDelayed(resume, MAX_PAUSE_MS); return@post }
        val c = app ?: return@post
        val pkg = CarSound.playing ?: return@post
        if (CarSound.kind(c, pkg) != CarSound.Kind.BOOK) return@post
        val ctl = runCatching {
            c.getSystemService(MediaSessionManager::class.java)
                .getActiveSessions(ComponentName(c, "net.prototux.aare.notify.CarNotificationListener"))
                .firstOrNull { it.packageName == pkg && it.playbackState?.state == PlaybackState.STATE_PLAYING }
        }.getOrNull() ?: return@post
        runCatching { ctl.transportControls.pause() }.onFailure { return@post }
        paused = ctl; pausedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "paused $pkg for a prompt")
        main.postDelayed(resume, MAX_PAUSE_MS)
    }

    /** The prompt is over (any thread): the podcast goes on shortly, [BACK_MS] earlier. */
    fun letGo() = main.post {
        if (paused == null) return@post
        main.removeCallbacks(resume)
        main.postDelayed(resume, RESUME_AFTER_MS)
    }

    private fun goOn() {
        val ctl = paused ?: return
        paused = null
        main.removeCallbacks(resume)
        runCatching {
            val st = ctl.playbackState
            // the driver paused or changed it meanwhile: left as it is
            if (st != null && st.state != PlaybackState.STATE_PAUSED) { Log.i(TAG, "${ctl.packageName} changed meanwhile - left"); return }
            if (st != null && st.actions and PlaybackState.ACTION_SEEK_TO != 0L && st.position >= 0)
                ctl.transportControls.seekTo((st.position - BACK_MS).coerceAtLeast(0))
            ctl.transportControls.play()
            Log.i(TAG, "${ctl.packageName} on again, ${BACK_MS / 1000} s back, paused ${(SystemClock.elapsedRealtime() - pausedAt) / 1000} s")
        }
    }
}
