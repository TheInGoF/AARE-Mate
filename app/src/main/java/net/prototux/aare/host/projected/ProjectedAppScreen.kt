package net.prototux.aare.host.projected

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Shows a projected app on the car screen. A [TextureView] provides the render target; a surface on
 * its texture is handed to a [ProjectedHost], which binds the app and asks it to draw there. Touch on
 * the view is forwarded to the app in the surface's own pixel space (the TextureView fills this slot,
 * so view coordinates already match the projected display's coordinates).
 *
 * The host starts when the texture is available. When the view leaves the screen (another area in
 * front) the host is paused and parked with its texture in [ProjectedKeep], and resumed when the app
 * comes back; without a picture yet it is stopped and its surface released.
 */
@Composable
fun ProjectedAppScreen(
    app: ProjectedAppInfo,
    densityDpi: Int,
    hostFactory: (ProjectedAppInfo) -> ProjectedHost,
    modifier: Modifier = Modifier,
) {
    // A TextureView, like the other foreign apps on the car screen: its picture is part of this window, so it
    // reaches the car with everything else (a SurfaceView's separate layer did not show there).
    // the part of the bottom edge the car screen's controls cover: Maps draws its map there, its buttons above (O101)
    val insetPx = with(androidx.compose.ui.platform.LocalDensity.current) { net.prototux.aare.host.templates.LocalCarBottomInset.current.roundToPx() }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            TextureView(context).apply {
                var host: ProjectedHost? = null
                var surface: Surface? = null
                var frames = 0
                var size = 0 to 0
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                // I-081: the app kept from its last time on screen (paused, its picture's surface kept): it goes on where it
                // was - the route, the place found - instead of starting afresh
                val key = ProjectedKeep.key(app, densityDpi)
                ProjectedKeep.take(key)?.let { k ->
                    host = k.host; surface = k.surface; size = k.width to k.height
                    setSurfaceTexture(k.texture)
                    Log.i("ProjectedHost", "back on screen: kept app resumed (${k.width}x${k.height})")
                    k.host.resume()
                    // no picture within 3 s: started afresh on the same surface, as before I-081 - never worse than that
                    handler.postDelayed({
                        if (frames == 0 && host === k.host && surface != null) {
                            Log.w("ProjectedHost", "kept app showed no picture after resuming: started afresh")
                            k.host.stop()
                            host = hostFactory(app).also { it.insetBottom = insetPx; it.start(surface!!, size.first, size.second, densityDpi) }
                        }
                    }, 3000)
                    // another size than when it was left (split, full screen): told as a resize
                    addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
                        val w = r - l; val h = b - t
                        if (w > 0 && h > 0 && (w to h) != size) { size = w to h; k.texture.setDefaultBufferSize(w, h); host?.resize(w, h) }
                    }
                }
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        if (w <= 0 || h <= 0) return
                        st.setDefaultBufferSize(w, h)
                        surface = Surface(st)
                        size = w to h
                        Log.i("ProjectedHost", "surface ${w}x$h")
                        // the same app kept at another size (the view was made anew for a new density): let go before
                        // this one binds - stopped later it would stop the app's navigation under the running host
                        ProjectedKeep.releaseService(app.service, key, "started afresh at another size")
                        host = hostFactory(app).also { it.insetBottom = insetPx; it.start(surface!!, w, h, densityDpi) }
                    }
                    // 18.11.2026 ("Was passiert, wenn man von 2/3 auf 3/4 wechselt oder auf Vollbild? Das müsste man ausprobieren,
                    // dass die App dann nicht abstürzt"): the picture follows the new size, and once it has settled (an animated
                    // share changes it every frame) the app is told; if no picture comes within 3 s it is started afresh at that size
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                        if (w <= 0 || h <= 0) return
                        st.setDefaultBufferSize(w, h)
                        size = w to h
                        handler.removeCallbacksAndMessages(null)
                        handler.postDelayed({
                            val hst = host ?: return@postDelayed
                            val before = frames
                            // back at the size the app has (a share changed and changed back): nothing to tell, and a
                            // still picture is no reason to start the app afresh
                            if (!hst.resize(w, h)) return@postDelayed
                            handler.postDelayed({
                                if (frames == before && host === hst && surface != null) {
                                    Log.w("ProjectedHost", "no picture after resizing to ${w}x$h: started afresh")
                                    hst.stop()
                                    host = hostFactory(app).also { it.insetBottom = insetPx; it.start(surface!!, w, h, densityDpi) }
                                }
                            }, 3000)
                        }, 450)
                    }
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        handler.removeCallbacksAndMessages(null)
                        val h = host; val sf = surface
                        host = null; surface = null
                        // another area in front (I-081): the app paused and kept with its surface, as Android Auto keeps the
                        // navigation running behind another app - the texture is not let go (false)
                        if (h != null && sf != null && size.first > 0) {
                            ProjectedKeep.park(ProjectedKeep.Kept(key, h, st, sf, size.first, size.second))
                            return false
                        }
                        h?.stop(); sf?.release()
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                        frames++
                        if (frames == 1 || frames == 30 || frames % 600 == 0) {
                            Log.i("ProjectedHost", "pictures shown: $frames")
                            if (net.prototux.aare.BuildConfig.DEBUG) Log.i("ProjectedHost", "picture $frames: " + describe(this@apply))
                        }
                    }
                }
                // Forward touches to the projected app; coordinates are already in surface space.
                setOnTouchListener { _: View, event -> host?.sendMotion(event); true }
            }
        },
    )
}

/**
 * What a picture holds, in numbers: the map shows where the car is, so nobody looks at it — whether it is
 * empty, one colour or a real picture can be told from the count of colours and how bright it is.
 */
private fun describe(view: TextureView): String = runCatching {
    val small = view.getBitmap(96, 48) ?: return "none"
    val px = IntArray(96 * 48).also { small.getPixels(it, 0, 96, 0, 0, 96, 48) }
    small.recycle()
    val colours = px.toHashSet().size
    val clear = px.count { (it ushr 24) < 16 }
    val bright = px.sumOf { ((it shr 16 and 255) * 3 + (it shr 8 and 255) * 6 + (it and 255)) / 10 } / px.size
    "$colours colours, brightness $bright of 255, ${clear * 100 / px.size} % see-through"
}.getOrElse { "unreadable (${it.javaClass.simpleName})" }

/**
 * I-081 (22.11.2026: "wenn man Google Maps verlässt und dann auf eine Musik-App geht und wieder zurück auf Google Maps,
 * dann lädt er die komplette Route neu ... wäre irgendwie cool, wenn die Navigation im Hintergrund weiterlaufen würde,
 * wie auch bei der Navi-App"): one projected app is kept while another area is in front - paused (onProjectionPause),
 * bound, its picture's surface kept - and resumed on the same surface when it comes back. Let go when another app is
 * kept, after [KEEP_MS] in the background, and when the car's connection ends ([releaseAll]).
 */
internal object ProjectedKeep {
    class Kept(val key: String, val host: ProjectedHost, val texture: SurfaceTexture, val surface: Surface, val width: Int, val height: Int)

    private var kept: Kept? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val expire = Runnable { releaseAll("in the background too long") }

    fun key(app: ProjectedAppInfo, densityDpi: Int) = app.service.flattenToString() + "@" + densityDpi

    fun park(k: Kept) {
        if (kept != null && kept !== k) releaseAll("another app kept")
        kept = k
        k.host.pause()
        handler.removeCallbacks(expire)
        handler.postDelayed(expire, KEEP_MS)
        Log.i("ProjectedHost", "left the screen: app paused and kept (${k.key})")
    }

    fun take(key: String): Kept? {
        val k = kept?.takeIf { it.key == key } ?: return null
        kept = null
        handler.removeCallbacks(expire)
        return k
    }

    /** Lets go of a kept host of [service] that is not kept under [except]. */
    fun releaseService(service: android.content.ComponentName, except: String, why: String) {
        val k = kept ?: return
        if (k.key != except && k.key.startsWith(service.flattenToString() + "@")) releaseAll(why)
    }

    fun releaseAll(why: String) {
        val k = kept ?: return
        kept = null
        handler.removeCallbacks(expire)
        Log.i("ProjectedHost", "kept app let go: $why")
        runCatching { k.host.stop() }
        runCatching { k.surface.release() }
        runCatching { k.texture.release() }
    }

    /** As long as a drive's break: a longer pause starts the app afresh when it comes back. */
    private const val KEEP_MS = 3 * 3600_000L
}
