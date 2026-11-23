package net.prototux.aare.ui.car

import android.graphics.SurfaceTexture
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import net.prototux.aare.R
import net.prototux.aare.host.CarRuntime
import net.prototux.aare.launch.AppLauncher
import net.prototux.aare.media.Placeholder

/**
 * Hosts an ordinary phone app on the car screen (NewPipe, an emulator, a browser app…). A
 * [TextureView] gives its surface to the privileged [AppLauncher], which puts a virtual display
 * there, launches [packageName] on it, and receives the car's touches we forward.
 *
 * Not an Android Auto app, so — like the phone-screen mirror — it is paused while driving. The
 * privileged core is device-tested; here it just wires the surface, touches and lifecycle.
 */
@Composable
internal fun StandaloneAppScreen(runtime: CarRuntime, driving: Boolean, packageName: String, modifier: Modifier) {
    val context = LocalContext.current
    val launcher = remember(packageName) { AppLauncher(context) }
    var started by remember(packageName) { mutableStateOf(false) }
    // left (onDispose): a connect still waiting in its thread must not start a display nobody stops
    val cancelled = remember(packageName) { java.util.concurrent.atomic.AtomicBoolean(false) }

    DisposableEffect(packageName) {
        // Hardware / head-unit Back goes to the launched app.
        val back: () -> Boolean = {
            val t = android.os.SystemClock.uptimeMillis()
            launcher.key(t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
            launcher.key(t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)
            launcher.connected
        }
        runtime.screenBack = back
        // the tablet kept awake while the cinema shows an app (22.11.2026: "Wenn das Tablet ausgeht, bricht auch die
        // Verbindung zur YouTube-App ab"): its screen going dark puts every display to sleep, the app's own too - dimmed,
        // not off; only while parked, the cinema's condition anyway
        @Suppress("DEPRECATION")
        val awake = (context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager)
            .newWakeLock(android.os.PowerManager.SCREEN_DIM_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "mate:cinema")
            .apply { setReferenceCounted(false); runCatching { acquire(4 * 3600_000L) } }
        net.prototux.aare.DiagLog.add("cinema: $packageName - the tablet kept awake while it shows", area = "CAR")
        onDispose {
            if (runtime.screenBack === back) runtime.screenBack = null
            runCatching { if (awake.isHeld) awake.release() }
            cancelled.set(true)
            launcher.release()
        }
    }

    // the app's picture ends above Mate's controls (22.11.2026: "dass die Bedienleiste der YouTube-App unter dem
    // Bedienfeld unserer Mate-App ist"): its display is made the height left over, its own bar stays in sight
    val covered = net.prototux.aare.host.templates.LocalCarBottomInset.current
    Box(modifier.background(Color.Black)) {
        if (!driving) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        // the surface on this view's texture, one for its whole life; gone with the texture
                        var surface: Surface? = null
                        // one flag per texture: a start still waiting for an earlier texture of this view stays stopped
                        var alive = java.util.concurrent.atomic.AtomicBoolean(false)
                        val main = android.os.Handler(android.os.Looper.getMainLooper())
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                val sf = Surface(st).also { surface = it }
                                val live = java.util.concurrent.atomic.AtomicBoolean(true).also { alive = it }
                                // connect() blocks (binds Shizuku / spawns su), so do it off the main thread.
                                Thread {
                                    if (launcher.connect() && live.get() && !cancelled.get()) {
                                        val id = launcher.start(sf, w, h, cinemaDpi(runtime.appDensityDpi), packageName)
                                        // gone while it started: stopped at once
                                        if (id >= 0 && (!live.get() || cancelled.get())) { launcher.stop(); return@Thread }
                                        main.post { started = id >= 0 }
                                        net.prototux.aare.DiagLog.add("cinema: $packageName - " + (if (id >= 0) launcher.report() else "not started: " + launcher.report().ifEmpty { "no display" }), area = "CAR")
                                    }
                                }.apply { isDaemon = true }.start()
                            }
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                                val sf = surface ?: Surface(st).also { surface = it }
                                launcher.resize(sf, w, h, cinemaDpi(runtime.appDensityDpi))
                            }
                            // the view gone (driving, another area): its display stopped, not left drawing into a dead surface
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                alive.set(false)
                                launcher.stop()
                                started = false
                                surface?.release()
                                surface = null
                                return true
                            }
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                        }
                    }
                },
                modifier = Modifier.fillMaxSize().padding(bottom = covered),
            )
            // Forward the car's touches to the launched app's display (single pointer for now).
            Box(Modifier.fillMaxSize().padding(bottom = covered).pointerInput(packageName) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val t0 = down.uptimeMillis
                    launcher.touch(t0, t0, MotionEvent.ACTION_DOWN, down.position.x, down.position.y, 0)
                    down.consume()
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (change.pressed) {
                            launcher.touch(t0, change.uptimeMillis, MotionEvent.ACTION_MOVE, change.position.x, change.position.y, 0)
                        } else {
                            launcher.touch(t0, change.uptimeMillis, MotionEvent.ACTION_UP, change.position.x, change.position.y, 0)
                            change.consume()
                            break
                        }
                        change.consume()
                    }
                }
            })
            // On-screen keys (19.11.2026: "weil ich habe ja keine On-Screen-Tasten"): back in the app, and the cinema's app menu
            androidx.compose.foundation.layout.Row(Modifier.align(Alignment.TopStart).padding(12.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                CinemaKey(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back)) {
                    val t = android.os.SystemClock.uptimeMillis()
                    launcher.key(t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
                    launcher.key(t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)
                }
                LocalCinemaMenu.current?.let { menu ->
                    CinemaKey(androidx.compose.material.icons.Icons.Rounded.SpaceDashboard, stringResource(R.string.cinema_apps), menu)
                }
            }
            if (!started) Placeholder(stringResource(R.string.standalone_starting), loading = true)
        } else {
            Placeholder(stringResource(R.string.standalone_paused_driving), loading = false, icon = Icons.Rounded.PauseCircle)
        }
    }
}

/** The cinema's app menu, given by the shell that shows the app (null: no menu). */
internal val LocalCinemaMenu = androidx.compose.runtime.compositionLocalOf<(() -> Unit)?> { null }

/** A round key over the app: half see-through, so the app stays visible beneath. */
@Composable
private fun CinemaKey(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(64.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.Black.copy(alpha = 0.55f))
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Icon(icon, label, tint = Color.White, modifier = Modifier.size(32.dp))
    }
}


/**
 * The cinema's apps larger than the car's own (22.11.2026: "dass die Bedienung sehr schwierig ist in dem Kinomodus … ob
 * wir die Auflösung dafür ändern müssen"): at the car's 183 dpi YouTube saw a 1678 dp wide tablet and drew everything
 * small; 1.4 times the density gives it about 1200 dp - its phone-and-tablet layout with larger controls.
 */
private fun cinemaDpi(appDpi: Int): Int = (appDpi * 1.4f).toInt()
