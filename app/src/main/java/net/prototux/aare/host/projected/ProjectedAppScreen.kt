package net.prototux.aare.host.projected

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Shows a projected app on the car screen. A [SurfaceView] provides the render target; its surface
 * is handed to a [ProjectedHost], which binds the app and asks it to draw there. Touch on the view
 * is forwarded to the app in the surface's own pixel space (the SurfaceView fills this slot, so view
 * coordinates already match the projected display's coordinates).
 *
 * The host lives for as long as this composable is on screen: it starts when the surface is created
 * and stops when the surface is destroyed or the composable leaves.
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
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        if (w <= 0 || h <= 0) return
                        st.setDefaultBufferSize(w, h)
                        surface = Surface(st)
                        Log.i("ProjectedHost", "surface ${w}x$h")
                        host = hostFactory(app).also { it.insetBottom = insetPx; it.start(surface!!, w, h, densityDpi) }
                    }
                    // 18.11.2026 ("Was passiert, wenn man von 2/3 auf 3/4 wechselt oder auf Vollbild? Das müsste man ausprobieren,
                    // dass die App dann nicht abstürzt"): the picture follows the new size, and once it has settled (an animated
                    // share changes it every frame) the app is told; if no picture comes within 3 s it is started afresh at that size
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                        if (w <= 0 || h <= 0) return
                        st.setDefaultBufferSize(w, h)
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
                        host?.stop(); host = null
                        surface?.release(); surface = null
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

    // Nothing to clean up here directly; the SurfaceHolder callback owns the host's lifetime.
    DisposableEffect(app.service) { onDispose { } }
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
