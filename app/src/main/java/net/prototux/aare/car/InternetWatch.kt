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
    private enum class State { ONLINE, PORTAL, WIFI_NO_INTERNET, WIFI_STUCK, MOBILE_STUCK, OFFLINE }

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    /** The state last seen, and the one the driver was last told about. */
    private var seen: State? = null
    private var told = State.ONLINE
    private val tell = Runnable { tellNow() }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = main.post {
            if (network == bound) return@post
            val kind = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                else -> "other"
            }
            lastKind = kind; lastNetwork = network
            networks()
            change(when {
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) -> State.PORTAL
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> State.ONLINE
                kind == "wifi" -> State.WIFI_NO_INTERNET
                else -> State.OFFLINE
            }, kind)
        }.let {}
        override fun onLost(network: Network) = main.post { networks(); change(State.OFFLINE, "none") }.let {}
    }

    /**
     * Every network the tablet holds and what Android says of it, in the log when that changes (23.11.2026: "dass er das
     * Wi-Fi des Fahrzeugs verbinden wollte, aber im Hintergrund kein Internet darüber hatte" - the log only named the default
     * one). No names: Wi-Fi or mobile, internet checked, login page, Mate's own link to the car.
     */
    private var networksSaid: String? = null
    @Suppress("DEPRECATION")
    private fun networks() {
        val all = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())
        val text = all.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { c ->
            (when { c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"; c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"; else -> "other" }) +
                (if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) " (no internet asked - e.g. Mate's own link to the car)"
                 else if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) " (login page)"
                 else if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) " (internet checked)" else " (internet not checked/none)")
        } }.sorted().joinToString(", ").ifEmpty { "none" }
        if (text == networksSaid) return
        networksSaid = text
        DiagLog.add("networks: $text" + if (bound != null) " - Mate itself over mobile data" else "", area = "NET")
    }

    /**
     * Mate's own traffic over mobile data while the default Wi-Fi gives no internet (23.11.2026: no music in the morning,
     * "er das Wi-Fi des Fahrzeugs verbinden wollte, aber im Hintergrund kein Internet darüber hatte"): mobile data is
     * asked to stay up and Mate's process is bound to it - music, routes, search and weather load again, whatever Android
     * thinks of the Wi-Fi. The link to the car is not touched (its sockets are made on the car's network itself). Undone as
     * soon as the Wi-Fi carries internet again, or the session ends.
     */
    @Volatile private var bound: Network? = null
    private var mobileAsked = false
    private val mobile = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = main.post {
            if (!mobileAsked) return@post
            if (runCatching { cm.bindProcessToNetwork(network) }.getOrDefault(false)) {
                bound = network
                DiagLog.add("internet: the Wi-Fi gives none - Mate's own traffic now goes over mobile data", area = "NET")
            }
        }.let {}
        override fun onLost(network: Network) = main.post { if (bound == network) { bound = null; runCatching { cm.bindProcessToNetwork(null) } } }.let {}
    }

    private fun overMobile(on: Boolean) {
        if (on == mobileAsked) return
        mobileAsked = on
        if (on) runCatching {
            cm.requestNetwork(android.net.NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), mobile)
        }.onFailure { DiagLog.add("internet: mobile data could not be asked for (${it.javaClass.simpleName})", area = "NET") }
        else {
            runCatching { cm.unregisterNetworkCallback(mobile) }
            if (bound != null) { runCatching { cm.bindProcessToNetwork(null) }; bound = null
                DiagLog.add("internet: Mate's own traffic follows Android's choice again", area = "NET") }
        }
    }

    @Volatile private var lastKind = "none"
    @Volatile private var lastNetwork: Network? = null
    @Volatile private var running = false

    /**
     * Android's word is not enough (22.11.2026, Shizuku: "Dadurch war er im Hintergrund noch mit dem Fahrzeug-WLAN
     * verbunden ... Und dadurch hat er YouTube nicht geladen"): the car's Wi-Fi counted as one with internet while nothing
     * came through it. While the internet goes over Wi-Fi, a small request every minute; two failed in a row and the
     * driver is told to take the tablet off that Wi-Fi (Shizuku, once started, stays).
     */
    private val probe = Thread {
        var failed = 0
        while (running) {
            runCatching { Thread.sleep(PROBE_MS) }
            if (!running) break
            val n = lastNetwork
            if ((seen != State.ONLINE && seen != State.WIFI_STUCK && seen != State.MOBILE_STUCK) || n == null) { failed = 0; continue }
            val ok = runCatching {
                val c = n.openConnection(java.net.URL("https://connectivitycheck.gstatic.com/generate_204")) as java.net.HttpURLConnection
                c.connectTimeout = 6_000; c.readTimeout = 6_000; c.instanceFollowRedirects = false
                try { c.responseCode == 204 } finally { c.disconnect() }
            }.getOrDefault(false)
            failed = if (ok) 0 else failed + 1
            val kind = lastKind
            main.post {
                if (ok && (seen == State.WIFI_STUCK || seen == State.MOBILE_STUCK)) change(State.ONLINE, kind)
                else if (failed >= 2) change(if (kind == "wifi") State.WIFI_STUCK else State.MOBILE_STUCK, kind)
            }
        }
    }.apply { isDaemon = true; name = "internet-probe" }

    fun start() {
        running = true
        runCatching { probe.start() }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
        // No network at all calls nothing back: say so once the callbacks had their time.
        main.postDelayed({ if (seen == null) change(State.OFFLINE, "none") }, 3_000)
    }

    fun stop() {
        running = false
        runCatching { cm.unregisterNetworkCallback(callback) }
        mobileAsked = true; overMobile(false)
        main.removeCallbacksAndMessages(null)
    }

    private fun change(state: State, kind: String) {
        if (state == seen) return
        seen = state
        DiagLog.add("internet: ${state.name.lowercase().replace('_', ' ')} ($kind)")
        // a Wi-Fi in the way without internet: Mate goes over mobile data itself; a Wi-Fi with internet again: back
        overMobile(kind == "wifi" && state != State.ONLINE)
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
            State.WIFI_STUCK -> R.string.internet_notice_wifi_stuck
            State.MOBILE_STUCK -> R.string.internet_notice_mobile_stuck
            State.OFFLINE -> R.string.internet_notice_offline
        }
        notice(context.getString(R.string.internet_notice_title), context.getString(text))
    }

    private companion object {
        const val SETTLE_MS = 20_000L
        const val PROBE_MS = 60_000L
    }
}
