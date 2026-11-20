package net.prototux.aare.transport.wireless

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import net.prototux.aare.DiagLog
import net.prototux.aare.aap.wireless.WifiCredentials
import net.prototux.aare.aap.wireless.WifiSecurity

/**
 * The car's internet for the tablet (18.11.2026: "Und jetzt müssen wir hinbekommen, dass er die Fahrzeug-Internet-
 * Verbindung nutzt und Wireless Android Auto"). For wireless Android Auto Mate joins the car's Wi-Fi as a network of its
 * own, which Android never routes the internet over. Here the same network (the name and password the car hands over at
 * the wireless start) is suggested to Android as an ordinary Wi-Fi: from then on the tablet joins it by itself, and where
 * the car's Wi-Fi carries internet Android uses it instead of mobile data - where it does not, mobile data stays.
 * Android asks once whether Mate may suggest networks. Switched off, the suggestion is taken back.
 */
object CarInternet {
    private fun prefs(c: Context) = c.getSharedPreferences("car_internet", Context.MODE_PRIVATE)

    /** Suggests the car's network (once per network name) when the driver allows it. */
    fun suggest(context: Context, c: WifiCredentials) {
        if (Build.VERSION.SDK_INT < 29 || !net.prototux.aare.Settings(context).carInternet) return
        if (prefs(context).getString("ssid", null) == c.ssid) return
        val s = runCatching { build(c) }.getOrNull() ?: run { DiagLog.add("car internet: the car's Wi-Fi cannot be suggested (${c.security})"); return }
        val wm = context.getSystemService(WifiManager::class.java) ?: return
        val status = runCatching { wm.addNetworkSuggestions(listOf(s)) }.getOrElse { DiagLog.add("car internet: ${it.javaClass.simpleName}"); return }
        when (status) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS, WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE -> {
                prefs(context).edit().putString("ssid", c.ssid).putString("pass", c.password).putString("sec", c.security.name).apply()
                DiagLog.add("car internet: \"${c.ssid}\" suggested to Android - the tablet joins it from now on and takes its internet where it has some (Android may ask once to allow Mate's suggestions)")
                approval(wm)
            }
            else -> DiagLog.add("car internet: Android refused the suggestion (status $status)")
        }
    }

    /**
     * Whether Android lets Mate's suggestion count (18.11.2026: the car named its Wi-Fi, it was suggested, the tablet did not
     * join): Android asks once with a notification; not allowed, the suggestion does nothing.
     */
    private fun approval(wm: WifiManager) {
        if (Build.VERSION.SDK_INT < 31) return
        val status = runCatching { wm.javaClass.getMethod("getNetworkSuggestionUserApprovalStatus").invoke(wm) as Int }.getOrNull() ?: return
        DiagLog.add("car internet: Android " + when (status) {
            1 -> "has not asked yet whether Mate may suggest networks"
            2 -> "refused Mate's suggestions - allow them in the tablet's settings (apps, Mate, Wi-Fi control)"
            3, 4 -> "allows Mate's suggestions"
            else -> "tells approval $status for Mate's suggestions"
        })
    }

    /** Called once per connection: is the suggestion allowed now? */
    fun checkApproval(context: Context) {
        if (prefs(context).getString("ssid", null) == null) return
        context.getSystemService(WifiManager::class.java)?.let { approval(it) }
    }

    /** Takes the suggestion back (the switch turned off). */
    fun withdraw(context: Context) {
        if (Build.VERSION.SDK_INT < 29) return
        val p = prefs(context)
        val ssid = p.getString("ssid", null) ?: return
        val c = WifiCredentials(ssid, p.getString("pass", "") ?: "", null,
            runCatching { WifiSecurity.valueOf(p.getString("sec", "") ?: "") }.getOrDefault(WifiSecurity.WPA2_PERSONAL), 0, null)
        runCatching { build(c)?.let { context.getSystemService(WifiManager::class.java)?.removeNetworkSuggestions(listOf(it)) } }
        p.edit().clear().apply()
        DiagLog.add("car internet: the suggestion of \"$ssid\" was taken back")
    }

    /**
     * What the tablet's internet goes over now, for the log after a wireless start; true when a Wi-Fi asks for its login
     * page first (18.11.2026: "Wenn ich normalerweise über das WLAN ins Internet gehe, ist dort aber eine Anmeldeseite") -
     * Android then keeps mobile data and shows its own "sign in to the network" notification, one tap to the page.
     */
    fun report(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        val login = runCatching { cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) } == true } }
            .getOrDefault(false)
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        DiagLog.add("car internet: " + when {
            login -> "the car's Wi-Fi asks for its login page first - tap Android's notification \"sign in to the network\" on the tablet once; until then mobile data stays"
            caps == null -> "no internet at all right now"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> "the tablet's internet goes over Wi-Fi (the car's, when it is joined)"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "the tablet's internet goes over mobile data (the car's Wi-Fi gives none, or is not joined as a normal network yet)"
            else -> "the tablet's internet goes another way"
        })
        return login
    }

    private fun build(c: WifiCredentials): WifiNetworkSuggestion? {
        if (Build.VERSION.SDK_INT < 29) return null
        val b = WifiNetworkSuggestion.Builder().setSsid(c.ssid).setIsAppInteractionRequired(false)
        when (c.security) {
            WifiSecurity.OPEN -> Unit
            WifiSecurity.WPA2_PERSONAL, WifiSecurity.WPA2_WPA3_PERSONAL -> b.setWpa2Passphrase(c.password)
            WifiSecurity.WPA3_PERSONAL -> b.setWpa3Passphrase(c.password)
            WifiSecurity.UNSUPPORTED -> return null
        }
        return b.build()
    }
}
