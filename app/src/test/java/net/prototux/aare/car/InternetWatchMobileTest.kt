package net.prototux.aare.car

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** 23.11.2026: the car's Wi-Fi without internet in the way - Mate asks for mobile data for itself, and lets go again. */
@RunWith(RobolectricTestRunner::class)
class InternetWatchMobileTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)

    private fun change(w: InternetWatch, state: String, kind: String) {
        val st = Class.forName("net.prototux.aare.car.InternetWatch\$State").enumConstants.first { (it as Enum<*>).name == state }
        InternetWatch::class.java.getDeclaredMethod("change", st.javaClass, String::class.java).apply { isAccessible = true }.invoke(w, st, kind)
    }

    @Test fun stuckWifiAsksForMobileAndLetsGo() {
        val w = InternetWatch(ctx) { _, _ -> }
        val before = shadowOf(cm).networkCallbacks.size
        change(w, "WIFI_STUCK", "wifi")
        assertEquals(before + 1, shadowOf(cm).networkCallbacks.size)
        change(w, "ONLINE", "wifi")
        assertEquals(before, shadowOf(cm).networkCallbacks.size)
        // mobile data itself stuck: nothing to go over
        change(w, "MOBILE_STUCK", "mobile")
        assertEquals(before, shadowOf(cm).networkCallbacks.size)
    }
}
