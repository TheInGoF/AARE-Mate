package net.prototux.aare.media

import android.content.ComponentName
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/** A media app found on the phone (media3 MediaLibraryService or legacy MediaBrowserService). */
data class MediaApp(
    val packageName: String,
    val service: ComponentName,
    val label: String,
    val icon: Bitmap?,
)

/** What is playing, from whichever media app is active. */
data class PlaybackInfo(
    val packageName: String,
    val title: String,
    val artist: String,
    val album: String,
    val art: Bitmap?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** SystemClock.elapsedRealtime() when positionMs was sampled, to extrapolate progress. */
    val sampledAtMs: Long,
)

/**
 * Media side of the car: discovery, one connection to the active media app, transport controls,
 * and the full-screen browse/now-playing UI.
 *
 * Implemented in this package. All methods are called on the main thread.
 */
interface MediaHub {
    /** Media apps installed on the phone; refreshed on package changes. */
    val apps: StateFlow<List<MediaApp>>

    /** The current playback, from the connected app or any active media session. */
    val playback: StateFlow<PlaybackInfo?>

    /** Connects to [app] (if not already) and makes it the active one. */
    fun select(app: MediaApp)

    /**
     * Waits until the connection to the selected app is ready, at most [timeoutMs]. Also true when the app is
     * connected but its library did not load in time; false if it couldn't connect, there is none, or it timed out.
     * The default, for hubs without a connection to wait for, is false.
     */
    suspend fun awaitConnection(timeoutMs: Long): Boolean = false

    /**
     * Something cuts in (a message read aloud, a call). Music is left to duck or pause by itself; long-form audio -
     * an audio book, a podcast - is paused here and remembered, so that [cutOut] can go back a little: one loses
     * the thread of a story, not of a song.
     */
    fun cutIn() {}

    /** Whether [cutIn] holds something paused right now (a pause that is not the driver's). */
    val cutInHeld: Boolean get() = false

    /** The cut-in is over: what [cutIn] paused goes on, [backMs] before where it stopped. */
    fun cutOut(backMs: Long) {}

    fun playPause()
    /** Back or forward in what plays by [deltaMs] (audio books and podcasts: -10 s, +30 s, O158); nothing if it can't seek. */
    fun seekBy(deltaMs: Long) {}
    /** An audio book or a podcast plays (or played last): long-form audio, which is wound rather than skipped. */
    val longForm: Boolean get() = false
    fun next()
    fun previous()
    fun pause()

    /** Full-screen UI for an app: browse tree (list/grid per content style), search, now playing. */
    @Composable
    fun Screen(app: MediaApp, driving: Boolean, modifier: Modifier)

    /** Disconnects everything. */
    fun release()
}
