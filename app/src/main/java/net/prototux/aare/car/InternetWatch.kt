package net.prototux.aare.car

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import net.prototux.aare.DiagLog

/**
 * Shows the driver why nothing loads (test drive B12, B3, B4: the car's Wi-Fi was connected but waited for its sign-in
 * page, and music, search and routes failed without a word). Since System L-103 (24.11.2026: "dezenter als Symbol in dem
 * Bedienfeld") no notice any more: the settled state goes to [shown], and the car's bar draws it as a small sign beside
 * reception and battery. Every change goes to the kept log, to read after a drive which network carried what.
 */
class InternetWatch(private val context: Context) {
    enum class State { ONLINE, PORTAL, WIFI_NO_INTERNET, WIFI_STUCK, MOBILE_STUCK, OFFLINE }

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    /** The state last seen; the one shown is [shown], set once a state lasted. */
    private var seen: State? = null
    /** The probe's failures in a row; reset when Android finds the network working again (I-121). */
    @Volatile private var failed = 0
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
        // 25.11.2026 (Q-065: "dann verbindet er sich direkt automatisch mit dem VW-WLAN, ohne Nachfrage"): which one Android
        // uses (default) and whether the Wi-Fi joined is the car's - still without its name
        val active = runCatching { cm.activeNetwork }.getOrNull()
        val carWifi = runCatching { net.prototux.aare.transport.wireless.CarInternet.joinedCar(context) }.getOrNull()
        val text = all.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { c ->
            val wifi = c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            (when { wifi -> "wifi"; c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"; else -> "other" }) +
                (if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) " (no internet asked - e.g. Mate's own link to the car)"
                 else if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) " (login page)"
                 else if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) " (internet checked)" else " (internet not checked/none)") +
                (if (wifi && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && carWifi != null) (if (carWifi) " [the car's]" else " [not the car's]") else "") +
                (if (n == active) " [used]" else "")
        } }.sorted().joinToString(", ").ifEmpty { "none" }
        // a Wi-Fi asking for internet that has none (yet): its login page confirmed by Mate (PortalLogin)
        all.forEach { n -> cm.getNetworkCapabilities(n)?.let { c ->
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)))
                Thread { PortalLogin.confirm(context, n) }.apply { isDaemon = true }.start()
        } }
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
    private var bound: Network?
        get() = mateBound
        set(v) { mateBound = v }
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
        while (running) {
            runCatching { Thread.sleep(PROBE_MS) }
            if (!running) break
            val n = lastNetwork
            if ((seen != State.ONLINE && seen != State.WIFI_STUCK && seen != State.MOBILE_STUCK) || n == null) { failed = 0; continue }
            // only a Wi-Fi is probed (the car's said "online" while nothing came through); over mobile data Android's own
            // check counts - Mate's probe failed there every two minutes while everything worked (System I-121, log 24.11.2026)
            if (lastKind != "wifi") { failed = 0; if (seen == State.MOBILE_STUCK) main.post { change(State.ONLINE, lastKind) }; continue }
            // two answer points of different companies: one that cannot be reached alone is no network fault (I-121)
            val ok = PROBES.any { url -> runCatching {
                val c = n.openConnection(java.net.URL(url)) as java.net.HttpURLConnection
                c.connectTimeout = 6_000; c.readTimeout = 6_000; c.instanceFollowRedirects = false
                try { c.responseCode == 204 } finally { c.disconnect() }
            }.getOrDefault(false) }
            failed = if (ok) 0 else failed + 1
            // the Wi-Fi in use gives nothing: perhaps its login page wants confirming
            if (!ok && lastKind == "wifi") PortalLogin.confirm(context, n)
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
        shown.value = null
    }

    private fun change(state: State, kind: String) {
        if (state == seen) return
        seen = state
        // working again by Android's word: the probe starts counting anew, one failure later is not "stuck" (I-121)
        if (state == State.ONLINE) failed = 0
        DiagLog.add("internet: ${state.name.lowercase().replace('_', ' ')} ($kind)")
        // a Wi-Fi in the way without internet: Mate goes over mobile data itself; a Wi-Fi with internet again: back
        overMobile(kind == "wifi" && state != State.ONLINE)
        main.removeCallbacks(tell)
        // A new network needs a moment to be checked, and a change of cells is over in one: only
        // what lasts is shown. Good news at once.
        main.postDelayed(tell, if (state == State.ONLINE) 0 else SETTLE_MS)
    }

    private fun tellNow() {
        shown.value = seen ?: return
    }

    companion object {
        /** The settled state for the car's bar (System L-103), null while no car is connected. */
        val shown = androidx.compose.runtime.mutableStateOf<State?>(null)
        private val PROBES = listOf("https://connectivitycheck.gstatic.com/generate_204", "https://cp.cloudflare.com/generate_204")
        /** The network Mate's own traffic is bound to (mobile data while the Wi-Fi gives none), null: Android's choice. */
        @Volatile var mateBound: Network? = null
        private const val SETTLE_MS = 20_000L
        private const val PROBE_MS = 60_000L
    }
}
