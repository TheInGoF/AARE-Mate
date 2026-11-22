package net.prototux.aare.host.projected

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Google Maps' second door for the car: its navigation provider service
 * (`com.google.android.apps.auto.sdk.nav.INavigationProvider`). Android Auto binds it next to the projection
 * and says "started"; only then Maps connects its second client to the car service and registers the sensors
 * - among them the vehicle energy model its charging stops are planned from (O107, [net.prototux.aare.host.CarDataService]).
 *
 * Read from Maps: tx 2 hands over a configuration (a bundle: `version`, `dynamic_configuration`), tx 3 the
 * start with `mode` 1, tx 4 the stop. Maps answers only a caller it takes for the Android Auto app.
 * Stays started while Maps is on the car screen and is stopped with the projection (System I-003, 22.11.2026):
 * Google Maps 26.47 drops the car (lifecycle DISCONNECTED, no energy model, "ChargingNotRequired", routes as for
 * a petrol car) as soon as the provider stops. Until 15.11.2026 Mate stopped it after the setup because routes
 * could not be guided while it was started (B4); [stopAfterSetup] keeps that way for debug builds.
 */
internal class NavigationProvider(private val context: Context) {
    private var connection: ServiceConnection? = null
    private var binder: IBinder? = null

    fun start() {
        current = this
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder = service
                val ok = call(service, 2, reply = true) { it.writeInt(1); it.writeBundle(Bundle().apply { putInt("version", 1); putBundle("dynamic_configuration", Bundle()) }) } &&
                    call(service, 3, reply = false) { it.writeInt(1); it.writeBundle(Bundle().apply { putInt("mode", 1) }) }
                Log.i(TAG, if (ok) "navigation provider started" else "navigation provider refused")
                // Until 15.11.2026 stopped after the setup (B4: routes ended in "an error occurred" while it was started);
                // Maps 26.47 then drops the car and plans no charging stops, so it stays started (debug: stop_after_setup)
                if (ok && stopAfterSetup) handler.postDelayed(stopRunnable, SETUP_MS)
            }
            override fun onServiceDisconnected(name: ComponentName) { binder = null }
        }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(CATEGORY).setComponent(ComponentName(MAPS, SERVICE))
        val bound = runCatching { context.bindService(intent, conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (bound) connection = conn else Log.w(TAG, "could not bind the navigation provider")
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val stopRunnable = Runnable {
        Log.i(TAG, "navigation provider stopped after setup: Maps guides only without it")
        stop()
    }

    fun stop() {
        handler.removeCallbacks(stopRunnable)
        binder?.let { b -> call(b, 4, reply = true) {} }
        binder = null
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    private inline fun call(target: IBinder, code: Int, reply: Boolean, body: (Parcel) -> Unit): Boolean {
        val data = Parcel.obtain()
        val answer = if (reply) Parcel.obtain() else null
        return try {
            data.writeInterfaceToken(INTERFACE)
            body(data)
            target.transact(code, data, answer, if (reply) 0 else IBinder.FLAG_ONEWAY)
            answer?.readException()
            true
        } catch (e: Exception) {
            Log.w(TAG, "tx $code: $e")
            false
        } finally {
            data.recycle()
            answer?.recycle()
        }
    }

    companion object {
        /** Debug builds (tool debug_nav_provider): leave Maps' navigation provider alone, to tell its effects apart (B4). */
        @Volatile var enabled = true
        /** Debug builds (tool debug_nav_provider, "stop_after_setup"): the old way - stop it once the sensors are registered. */
        @Volatile var stopAfterSetup = false
        /** How long the provider stays started: Maps registers the car's sensors within about 2 s of the start. */
        private const val SETUP_MS = 8_000L
        /** The provider started last, for the debug tool. */
        @Volatile var current: NavigationProvider? = null
        private const val TAG = "NavigationProvider"
        const val MAPS = "com.google.android.apps.maps"
        private const val SERVICE = "com.google.android.apps.gmm.car.projected.service.CarNavigationProviderService"
        private const val CATEGORY = "com.google.android.car.category.NAVIGATION_PROVIDER"
        private const val INTERFACE = "com.google.android.apps.auto.sdk.nav.INavigationProvider"
    }
}
