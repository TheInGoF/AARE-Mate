package net.prototux.aare.media

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Creates the media hub for a car session. Call on the main thread; [scope] bounds its lifetime
 * (cancelling it stops background work), but call [MediaHub.release] to disconnect cleanly.
 */
fun MediaHub(context: Context, scope: CoroutineScope): MediaHub = DefaultMediaHub(context, scope)

/**
 * The [MediaHub]: [MediaDiscovery] for the app list, one [MediaConnection] to the selected app,
 * a [SessionTracker] for every other session, and [pickSource] to decide what "now playing" and
 * the transport buttons follow.
 */
class DefaultMediaHub(context: Context, scope: CoroutineScope) : MediaHub {
    private val context = context.applicationContext
    private val scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Main.immediate)

    internal val art = ArtLoader(this.context)
    private val discovery = MediaDiscovery(this.context, this.scope)
    private val tracker = SessionTracker(this.context, this.scope, art)

    private val _connection = MutableStateFlow<MediaConnection?>(null)
    internal val connection: StateFlow<MediaConnection?> = _connection.asStateFlow()

    /** Browse/now-playing UI state of the current connection, kept while the screen is away. */
    private var ui: MediaUi? = null

    private val _current = MutableStateFlow<PlaybackSource?>(null)
    private val _playback = MutableStateFlow<PlaybackInfo?>(null)

    override val apps: StateFlow<List<MediaApp>> = discovery.apps

    /** Apps declaring `<uses name="media"/>` for Android Auto; [apps] also has the others. */
    val carMediaApps: StateFlow<List<MediaApp>> = discovery.carMediaApps

    /** False while we lack notification access, i.e. only our own connection is tracked. */
    val sessionAccess: StateFlow<Boolean> = tracker.available

    override val playback: StateFlow<PlaybackInfo?> = _playback.asStateFlow()

    init {
        discovery.start()
        tracker.start()
        scope.launch { followSources() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun followSources() {
        val ours = _connection.flatMapLatest { c -> c?.state?.map { listOf<Pair<PlaybackSource, SourceState>>(c to it) } ?: flowOf(emptyList()) }
        val others = tracker.sources.flatMapLatest { list ->
            if (list.isEmpty()) flowOf(emptyList())
            else combine(list.map { s -> s.state.map { s as PlaybackSource to it } }) { it.toList() }
        }
        combine(ours, others) { a, b -> a + b }.collect { all ->
            val key = pickSource(all.map { (s, st) -> st.candidate(s) }, _current.value?.key)
            val pick = all.firstOrNull { it.first.key == key }
            _current.value = pick?.first
            _playback.value = pick?.second?.info
            // the sound preset of the kind of app that plays (System L-009)
            net.prototux.aare.audio.CarSound.follow(context, pick?.first?.packageName)
        }
    }

    override fun select(app: MediaApp) {
        val c = _connection.value
        if (c != null && c.app.service == app.service) {
            c.connect()
            return
        }
        // one connection at a time; the old app keeps playing and shows up through the tracker
        c?.release()
        ui = null
        _connection.value = MediaConnection(context, scope, app, art).also { it.connect() }
    }

    override suspend fun awaitConnection(timeoutMs: Long): Boolean {
        val c = _connection.value ?: return false
        return awaitSettled(c.status, timeoutMs) || c.connected
    }

    internal fun uiFor(c: MediaConnection): MediaUi = ui?.takeIf { it.connection === c } ?: MediaUi(c, scope).also { ui = it }

    /** Transport goes to what is shown as playing, else to our connection. */
    private fun target(): PlaybackSource? = _current.value ?: _connection.value

    // What cutIn paused, and where it was.
    private var held: Pair<PlaybackSource, Long>? = null

    override val cutInHeld: Boolean get() = held != null

    override fun cutIn() {
        if (held != null) return
        val s = target() ?: return
        val info = s.state.value.info ?: return
        if (!info.playing || !longForm(s, info)) return
        held = s to info.positionMs + (android.os.SystemClock.elapsedRealtime() - info.sampledAtMs).coerceAtLeast(0)
        s.pause()
    }

    override fun cutOut(backMs: Long) {
        val (s, at) = held ?: return
        held = null
        s.seekTo((at - backMs).coerceAtLeast(0))
        // the app may have resumed by itself when it got the audio focus back
        if (s.state.value.info?.playing != true) s.playPause()
    }

    /** An audio book or a podcast: by the app, or by a piece that runs for a quarter of an hour and more. */
    private fun longForm(s: PlaybackSource, info: PlaybackInfo) =
        info.durationMs >= 15 * 60_000L || LONG_FORM_APPS.containsMatchIn(s.packageName)

    override fun playPause() {
        target()?.playPause()
    }

    override fun next() {
        target()?.next()
    }

    override fun seekBy(deltaMs: Long) {
        val s = target() ?: return
        val st = s.state.value
        val info = st.info ?: return
        if (!st.canSeek) return
        val now = info.positionMs + if (info.playing) (android.os.SystemClock.elapsedRealtime() - info.sampledAtMs).coerceAtLeast(0) else 0
        val end = if (info.durationMs > 0) info.durationMs else Long.MAX_VALUE
        s.seekTo((now + deltaMs).coerceIn(0, end))
    }

    override val longForm: Boolean
        get() = target()?.let { s -> s.state.value.info?.let { longForm(s, it) } } == true

    override fun previous() {
        target()?.previous()
    }

    override fun pause() {
        target()?.pause()
    }

    @Composable
    override fun Screen(app: MediaApp, driving: Boolean, modifier: Modifier) = MediaAppScreen(this, app, driving, modifier)

    override fun release() {
        discovery.release()
        tracker.release()
        _connection.value?.release()
        _connection.value = null
        ui = null
        _current.value = null
        _playback.value = null
        scope.cancel()
    }
}

/** True once [status] is [MediaConnection.Status.Ready]; false if it fails first or stays connecting for [timeoutMs]. */
internal suspend fun awaitSettled(status: Flow<MediaConnection.Status>, timeoutMs: Long): Boolean =
    withTimeoutOrNull(timeoutMs) { status.firstOrNull { it !is MediaConnection.Status.Connecting } } is MediaConnection.Status.Ready

private val LONG_FORM_APPS = Regex("audible|audiobook|podcast|podimo|pocketcast|antennapod|castbox|storytel|bookbeat|librivox", RegexOption.IGNORE_CASE)
