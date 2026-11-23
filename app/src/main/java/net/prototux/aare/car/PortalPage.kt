package net.prototux.aare.car

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import net.prototux.aare.R

/**
 * The car Wi-Fi's login page to tap when [PortalLogin] could not confirm it alone (a page made of scripts): opened over
 * that Wi-Fi itself (Mate's process bound to it while the page shows), since Android's own sign-in rarely or never came
 * (23.11.2026: "Nur die Bestätigungsseite kommt halt selten oder nie. Ich weiß nicht, wie man sich denn da einloggen soll").
 */
class PortalPage : Activity() {
    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (network, url) = PortalLogin.pending ?: run { finish(); return }
        val cm = getSystemService(ConnectivityManager::class.java)
        runCatching { cm.bindProcessToNetwork(network) }
        web = WebView(this).apply {
            @Suppress("SetJavaScriptEnabled")
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            loadUrl(url)
        }
        setContentView(web)
        title = getString(R.string.portal_title)
    }

    override fun onDestroy() {
        web?.destroy()
        // back to what Mate's traffic went over before (mobile data while the Wi-Fi gave none, else Android's choice)
        runCatching { getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(InternetWatch.mateBound) }
        PortalLogin.pending?.let { (n, _) -> Thread { PortalLogin.recheck(applicationContext, n) }.start() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "car_wifi_login"
        private const val ID = 7341

        /** A notification on the tablet that opens the page. */
        fun offer(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            runCatching {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.portal_title), NotificationManager.IMPORTANCE_HIGH))
                val pi = PendingIntent.getActivity(context, 0, Intent(context, PortalPage::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                nm.notify(ID, android.app.Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle(context.getString(R.string.portal_title)).setContentText(context.getString(R.string.portal_text))
                    .setContentIntent(pi).setAutoCancel(true).build())
            }
        }

        fun withdraw(context: Context) { runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(ID) } }
    }
}
