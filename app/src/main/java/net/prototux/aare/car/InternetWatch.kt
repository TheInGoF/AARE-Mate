package net.prototux.aare.car

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import net.prototux.aare.DiagLog
import net.prototux.aare.R

/**
 * Tells the driver why nothing loads (test drive B12, B3, B4: the car's Wi-Fi was connected but
 * waited for its sign-in page, and music, search and routes failed without a word): a notice on the
 * car screen when the phone's network has no internet, and one when it is back. Every change goes
 * to the kept log, to read after a drive which network carried what.
 */
class InternetWatch(private val context: Context, private val notice: (title: String, text: String) -> Unit) {
    private enum class State { ONLINE, PORTAL, WIFI_NO_INTERNET, OFFLINE }

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    /** The state last seen, and the one the driver was last told about. */
    private var seen: State? = null
    private var told = State.ONLINE
    private val tell = Runnable { tellNow() }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = main.post {
            val kind = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                else -> "other"
            }
            change(when {
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) -> State.PORTAL
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> State.ONLINE
                kind == "wifi" -> State.WIFI_NO_INTERNET
                else -> State.OFFLINE
            }, kind)
        }.let {}
        override fun onLost(network: Network) = main.post { change(State.OFFLINE, "none") }.let {}
    }

    fun start() {
        runCatching { cm.registerDefaultNetworkCallback(callback) }
        // No network at all calls nothing back: say so once the callbacks had their time.
        main.postDelayed({ if (seen == null) change(State.OFFLINE, "none") }, 3_000)
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(callback) }
        main.removeCallbacksAndMessages(null)
    }

    private fun change(state: State, kind: String) {
        if (state == seen) return
        seen = state
        DiagLog.add("internet: ${state.name.lowercase().replace('_', ' ')} ($kind)")
        main.removeCallbacks(tell)
        // A new network needs a moment to be checked, and a change of cells is over in one: only
        // what lasts is worth a notice. Good news at once.
        main.postDelayed(tell, if (state == State.ONLINE) 0 else SETTLE_MS)
    }

    private fun tellNow() {
        val state = seen ?: return
        if (state == told) return
        val was = told
        told = state
        val text = when (state) {
            State.ONLINE -> if (was == State.ONLINE) return else R.string.internet_notice_back
            State.PORTAL -> R.string.internet_notice_portal
            State.WIFI_NO_INTERNET -> R.string.internet_notice_wifi
            State.OFFLINE -> R.string.internet_notice_offline
        }
        notice(context.getString(R.string.internet_notice_title), context.getString(text))
    }

    private companion object {
        const val SETTLE_MS = 20_000L
    }
}
