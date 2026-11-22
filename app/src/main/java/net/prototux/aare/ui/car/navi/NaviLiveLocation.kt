package net.prototux.aare.ui.car.navi

import android.content.Context
import android.location.Location
import android.os.SystemClock
import com.stadiamaps.ferrostar.core.location.AndroidLocationProvider
import com.stadiamaps.ferrostar.core.location.NavigationLocationProviding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach

/**
 * The car's own GPS as the projection sends it (CarSession), for Mate Navi (F1: "echte Position (GPS/Auto) statt
 * Simulation"). Kept apart from the phone's providers: those may carry the car's fix too (PhoneLocationFeed) or not.
 */
internal object CarFixes {
    private val _last = MutableStateFlow<Location?>(null)
    val last: StateFlow<Location?> get() = _last
    @Volatile private var atMs = 0L

    @Volatile private var prev: Location? = null
    @Volatile private var prevAtMs = 0L

    /** A fix from the car (any thread). */
    fun push(l: Location) { prev = _last.value; prevAtMs = atMs; atMs = SystemClock.elapsedRealtime(); _last.value = Location(l) }

    /**
     * The GPS speed of the last fix in m/s: the car's own when it sends one, else the way between the last two fixes over
     * their time (0.3-5 s apart); null without.
     */
    fun gpsSpeedMs(): Double? {
        val l = _last.value ?: return null
        if (l.hasSpeed()) return l.speed.toDouble()
        val p = prev ?: return null
        val dt = (atMs - prevAtMs) / 1000.0
        if (dt < 0.3 || dt > 5.0) return null
        return p.distanceTo(l) / dt
    }

    /** Milliseconds since the car's last fix; very large when it never sent one. */
    fun ageMs(): Long = if (atMs == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - atMs
}

/**
 * Where the car really is, as Ferrostar asks for it: the car's GPS first (its antenna, what the car's own navigation
 * uses), the phone's own position only while the car has sent nothing for [CAR_STALE_MS] - in a tunnel the car keeps
 * sending, the phone loses it; at the desk or without the car the phone is all there is. Which one is used is logged
 * once per change ("position source: car" / "phone"), never the position itself.
 */
internal class NaviLiveLocation(context: Context) : NavigationLocationProviding {
    private val phone = runCatching { AndroidLocationProvider(context) }.getOrNull()
    @Volatile var source: String? = null; private set

    /** The next fix logs its source again (after a test drive the last line said "simulated"). */
    fun reset() { source = null }

    private fun used(s: String) {
        if (source == s) return
        source = s
        android.util.Log.i("MateNavi", "position source: $s")
    }

    override suspend fun lastLocation(): Location? =
        CarFixes.last.value?.takeIf { CarFixes.ageMs() < CAR_STALE_MS }
            ?: runCatching { phone?.lastLocation() }.getOrNull()

    override fun locationUpdates(intervalMillis: Long): Flow<Location> {
        val car = CarFixes.last.filterNotNull().onEach { used("car") }
        // the phone's position needs the location permission; without it only the car's
        val own = phone?.locationUpdates(intervalMillis)?.catch { android.util.Log.w("MateNavi", "phone position: $it") } ?: emptyFlow()
        return merge(car, own.filter { CarFixes.ageMs() > CAR_STALE_MS }.onEach { used("phone") })
    }

    companion object {
        const val CAR_STALE_MS = 3_000L
    }
}
