package net.prototux.aare.launch

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import android.view.Surface
import net.prototux.aare.BuildConfig
import net.prototux.aare.audio.CaptureMethods
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * App-side connector to the privileged [LaunchHostService], over Shizuku (a bound UserService).
 * Mirrors ShizukuSource in the audio code. One instance per launched app.
 *
 * [connect] blocks and must run off the main thread.
 */
class AppLauncher(private val context: Context) {
    @Volatile private var host: ILaunchHost? = null

    private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, LaunchHostService::class.java.name))
        .daemon(false).processNameSuffix("launch").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
    private val bound = CountDownLatch(1)
    private var didBind = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            host = binder?.takeIf { it.pingBinder() }?.let(ILaunchHost.Stub::asInterface)
            bound.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) { host = null }
    }

    /** Connects via Shizuku. Blocks up to a few seconds; false if Shizuku isn't there or the bind fails. */
    fun connect(): Boolean {
        if (!CaptureMethods.shizukuAvailable()) return false
        runCatching { Shizuku.bindUserService(args, connection); didBind = true }
            .onFailure { Log.w(TAG, "Shizuku bind failed", it); return false }
        return bound.await(8, TimeUnit.SECONDS) && host != null
    }

    val connected: Boolean get() = host != null

    /** Create the display into [surface] and launch [pkg] on it. Returns the display id, or -1. */
    fun start(surface: Surface, width: Int, height: Int, dpi: Int, pkg: String): Int =
        runCatching { host?.start(surface, width, height, dpi, pkg) ?: -1 }.onFailure { Log.w(TAG, "start", it) }.getOrDefault(-1)

    /** What the last start did, for the log. */
    fun report(): String = runCatching { host?.report() ?: "" }.getOrDefault("")

    fun resize(surface: Surface, width: Int, height: Int, dpi: Int) { runCatching { host?.resize(surface, width, height, dpi) } }
    fun touch(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float, pointerId: Int) {
        runCatching { host?.touch(downTime, eventTime, action, x, y, pointerId) }
    }
    fun key(eventTime: Long, action: Int, keyCode: Int) { runCatching { host?.key(eventTime, action, keyCode) } }

    /** Ends the display and the app on it; the launcher stays connected for another [start]. */
    fun stop() { runCatching { host?.stop() } }

    fun release() {
        stop()
        if (didBind) runCatching { Shizuku.unbindUserService(args, connection, true) }
        host = null
    }

    companion object {
        private const val TAG = "AareAppLauncher"
        /** Whether Shizuku is available to run the launcher. */
        fun available(): Boolean = CaptureMethods.shizukuAvailable()
    }
}
