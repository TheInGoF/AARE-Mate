package net.prototux.aare.ui

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import net.prototux.aare.Settings
import net.prototux.aare.audio.MicRouter
import net.prototux.aare.host.CarRuntime
import net.prototux.aare.media.MediaHub
import net.prototux.aare.ui.car.CarScreen

/**
 * The car screen on the phone itself, without a car: to try apps, check the layout, or use
 * the phone as a car display. Hosted apps get the phone's microphone.
 *
 * Never left running (System I-135, 26.11.2026: "morgens fast voll und nachmittags leer"; log: this screen built at 03:14 and
 * 06:36 with no car, its dashboard, a route and the stick's search with it, the screen kept on): it ends as soon as it is
 * out of sight (screen off, another app), and keeps the screen on only for [AWAKE_MS] after the last touch.
 */
class CarPreviewActivity : ComponentActivity() {
    private lateinit var runtime: CarRuntime
    private val mic = MicRouter({ null }, {})
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val sleepy = Runnable { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    private var openedAt = 0L

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        main.removeCallbacks(sleepy); main.postDelayed(sleepy, AWAKE_MS)
        return super.dispatchTouchEvent(ev)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        main.postDelayed(sleepy, AWAKE_MS)
        openedAt = android.os.SystemClock.elapsedRealtime()
        net.prototux.aare.DiagLog.add("car screen on the tablet: opened" + (if (savedInstanceState != null) " again by Android" else ""), area = "POWER")
        runtime = CarRuntime(this, lifecycleScope, Settings(this), MediaHub(this, lifecycleScope), primary = false)
        val dm = resources.displayMetrics
        runtime.densityDpi = dm.densityDpi
        runtime.screenWidthDp = (dm.widthPixels / dm.density).toInt()
        runtime.screenHeightDp = (dm.heightPixels / dm.density).toInt()
        runtime.microphone = { sink, stopped -> mic.open(sink, stopped) }
        runtime.start()
        setContent { runtime.Root(Modifier.fillMaxSize().systemBarsPadding()) }
        // Through the dispatcher: with predictive back (targetSdk 36+ on Android 16) onBackPressed
        // is no longer called. Back walks the car screen back to the dashboard, then leaves.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (runtime.ui.screen == CarScreen.Dashboard) finish() else runtime.back()
            }
        })
    }

    override fun onDestroy() {
        main.removeCallbacks(sleepy)
        mic.shutdown()
        runtime.stop()
        net.prototux.aare.DiagLog.add("car screen on the tablet: closed after ${(android.os.SystemClock.elapsedRealtime() - openedAt) / 60_000} min", area = "POWER")
        super.onDestroy()
    }

    private companion object {
        const val AWAKE_MS = 5 * 60_000L
    }
}
