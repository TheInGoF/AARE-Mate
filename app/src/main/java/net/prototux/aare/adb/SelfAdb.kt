package net.prototux.aare.adb

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AdbMdns
import net.prototux.aare.DiagLog
import net.prototux.aare.R
import net.prototux.aare.aap.cert.SelfSignedCertificate
import net.prototux.aare.audio.CaptureMethods
import java.io.File
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * System L-063 (Ingo, 19.11.2026: "Gibt es keine Möglichkeit, Shizuku mit der Mate-App zu starten? … Ohne Root, ohne
 * Custom-ROM-Anpassung"; "Ich würde wünschen, dass wir das Prinzip von Shizuku nachbauen"), first step: Mate pairs once with
 * the tablet's own wireless debugging - the pairing code typed into a notification, as Shizuku does it - and from then on
 * starts Shizuku itself (its starter over an adb shell of its own), whenever Shizuku is not running: when the car connects
 * and when the phone app opens. At the first connection Mate gives itself WRITE_SECURE_SETTINGS, so it can switch wireless
 * debugging on by itself afterwards. Wireless debugging needs a Wi-Fi network (any, it need not have the internet).
 * The second step - Mate's own shell service instead of Shizuku - can build on this connection.
 */
object SelfAdb {
    private const val TAG = "SelfAdb"
    private const val PREFS = "self_adb"
    private const val CHANNEL = "self_adb"
    private const val NOTE_ID = 0x5adb
    const val ACTION_CODE = "net.prototux.aare.adb.PAIR_CODE"
    const val EXTRA_CODE = "code"
    private const val ADB_WIFI = "adb_wifi_enabled"

    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "self-adb").apply { isDaemon = true } }
    @Volatile private var pairingPort = 0
    @Volatile private var mdns: AdbMdns? = null
    @Volatile private var lastTry = 0L
    private val main = Handler(Looper.getMainLooper())
    /** The search for the pairing service ends after this long without a pairing; starting the pairing again restarts it. */
    private const val SEARCH_MS = 5 * 60_000L
    private val endSearch = Runnable { stopSearch("no pairing within 5 minutes") }

    private fun stopSearch(why: String) {
        main.removeCallbacks(endSearch)
        val m = mdns ?: return
        mdns = null
        runCatching { m.stop() }
        DiagLog.add("shizuku by Mate: pairing search stopped ($why)")
    }

    /** Whether Mate has paired with the tablet's wireless debugging once. */
    fun paired(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("paired", false)

    /** Whether Mate may switch wireless debugging itself (given at the first connection). */
    fun maySwitch(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * Starts the pairing: looks for the pairing service the tablet offers while "Pair device with pairing code" is open in
     * the developer options, and shows the notification to type the code into (the dialog must stay open meanwhile).
     */
    fun startPairing(context: Context) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { DiagLog.add("shizuku by Mate: needs Android 11 (wireless debugging)"); return }
        pairingPort = 0
        main.removeCallbacks(endSearch)
        mdns?.stop()
        mdns = AdbMdns(app, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { _, port ->
            if (port > 0 && port != pairingPort) {
                pairingPort = port
                DiagLog.add("shizuku by Mate: pairing service found")
                note(app, app.getString(R.string.self_adb_pair_found), ask = true)
            }
        }.also { it.start() }
        main.postDelayed(endSearch, SEARCH_MS)
        note(app, app.getString(R.string.self_adb_pair_open), ask = true)
        DiagLog.add("shizuku by Mate: pairing started, waiting for the code")
    }

    /** The code typed into the notification. */
    fun onCode(context: Context, code: String) {
        val app = context.applicationContext
        val digits = code.filter(Char::isDigit)
        work.execute {
            val port = pairingPort
            if (port <= 0) {
                if (mdns == null) startPairing(app) // the search had ended: look again
                note(app, app.getString(R.string.self_adb_pair_no_service), ask = true); return@execute
            }
            note(app, app.getString(R.string.self_adb_pairing), ask = false)
            val ok = runCatching { Manager(app).use { it.pair("127.0.0.1", port, digits) } }
                .onFailure { DiagLog.add("shizuku by Mate: pairing failed - ${it.javaClass.simpleName}: ${it.message}") }.getOrDefault(false)
            if (!ok) { stopSearch("pairing failed"); note(app, app.getString(R.string.self_adb_pair_failed), ask = true); return@execute }
            stopSearch("paired")
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("paired", true).apply()
            DiagLog.add("shizuku by Mate: paired")
            val started = startNow(app, "after pairing")
            note(app, app.getString(if (started) R.string.self_adb_ready else R.string.self_adb_paired_not_started), ask = false)
        }
    }

    /**
     * Shizuku started by Mate when it is not running and Mate has paired: in the background, at most once a minute.
     * [why] for the log (car connected, phone app opened). [done] runs once the try is over (or nothing was to do).
     */
    fun ensureShizuku(context: Context, why: String, done: () -> Unit = {}) {
        val app = context.applicationContext
        if (CaptureMethods.shizukuAvailable() || !paired(app) || !CaptureMethods.shizukuInstalled(app)) { done(); return }
        val now = System.currentTimeMillis()
        if (now - lastTry < 60_000) { done(); return }
        lastTry = now
        work.execute { try { startNow(app, why) } finally { done() } }
    }

    @Volatile private var wifiWatched = false

    /**
     * E-045: a Wi-Fi network that comes only later (the car's Wi-Fi Mate joins itself, a hotspot) gets another try at starting
     * Shizuku - before, Mate tried at boot, at the car's connection and when the app opened, and nothing after. Registered once
     * per process from [net.prototux.aare.AareApp] with the application context; it only listens (no request of its own), so
     * Mate's own joining of the car's Wi-Fi is not touched. A Wi-Fi network that goes away ends nothing: Shizuku runs on.
     * Wireless debugging needs Android 11.
     */
    fun watchWifi(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val app = context.applicationContext
        // only Mate's main process (the others have no use for it)
        if (android.app.Application.getProcessName() != app.packageName) return
        synchronized(this) { if (wifiWatched) return; wifiWatched = true }
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val request = android.net.NetworkRequest.Builder()
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            // any Wi-Fi will do, the car's has no internet
            .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching {
            cm.registerNetworkCallback(request, object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    if (CaptureMethods.shizukuAvailable() || !paired(app) || !CaptureMethods.shizukuInstalled(app)) return
                    DiagLog.add("shizuku by Mate (Wi-Fi came): Shizuku not running, trying to start it")
                    ensureShizuku(app, "Wi-Fi came")
                }
            })
        }.onFailure {
            wifiWatched = false
            DiagLog.add("shizuku by Mate (Wi-Fi came): watching Wi-Fi failed - ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** Started from the phone app's button: without the once-a-minute limit. */
    fun startByHand(context: Context) {
        val app = context.applicationContext
        lastTry = System.currentTimeMillis()
        work.execute {
            val ok = startNow(app, "by hand")
            note(app, app.getString(if (ok) R.string.self_adb_ready else R.string.self_adb_paired_not_started), ask = false)
        }
    }

    /** Connects to the tablet's own adb and runs Shizuku's starter; true when Shizuku answers afterwards. Worker thread. */
    private fun startNow(app: Context, why: String): Boolean {
        val starter = runCatching { app.packageManager.getApplicationInfo(CaptureMethods.SHIZUKU_PACKAGE, 0).nativeLibraryDir + "/libshizuku.so" }
            .getOrNull() ?: run { DiagLog.add("shizuku by Mate ($why): Shizuku not installed"); return false }
        val cr = app.contentResolver
        if (Settings.Global.getInt(cr, ADB_WIFI, 0) != 1) {
            if (!maySwitch(app)) { DiagLog.add("shizuku by Mate ($why): wireless debugging is off and Mate may not switch it"); return false }
            if (!onWifi(app)) { DiagLog.add("shizuku by Mate ($why): no Wi-Fi - wireless debugging needs one"); return false }
            runCatching { Settings.Global.putInt(cr, ADB_WIFI, 1) }
                .onFailure { DiagLog.add("shizuku by Mate ($why): switching wireless debugging on failed - ${it.message}"); return false }
            DiagLog.add("shizuku by Mate ($why): wireless debugging switched on")
        }
        return runCatching {
            Manager(app).use { m ->
                if (!m.connectTls(app, 10_000)) { DiagLog.add("shizuku by Mate ($why): no adb connection on the tablet"); return@use false }
                if (!maySwitch(app)) shell(m, "pm grant ${app.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}")
                val out = shell(m, starter)
                DiagLog.add("shizuku by Mate ($why): starter ran - " + out.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty().take(120))
                // Shizuku's server comes up within a few seconds
                repeat(20) { if (CaptureMethods.shizukuAvailable()) { DiagLog.add("shizuku by Mate ($why): Shizuku runs"); return@use true }; Thread.sleep(500) }
                DiagLog.add("shizuku by Mate ($why): Shizuku did not answer after the start")
                false
            }
        }.onFailure { DiagLog.add("shizuku by Mate ($why): failed - ${it.javaClass.simpleName}: ${it.message}") }.getOrDefault(false)
    }

    private fun shell(m: Manager, cmd: String): String =
        m.openStream("shell:$cmd").use { s -> s.openInputStream().bufferedReader().readText() }

    private fun onWifi(app: Context): Boolean {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    /** The notification: what to do now, with a field for the code when [ask]. */
    private fun note(app: Context, text: String, ask: Boolean) {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, app.getString(R.string.self_adb_channel), NotificationManager.IMPORTANCE_HIGH))
        val b = android.app.Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(app.getString(R.string.self_adb_title))
            .setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
        if (ask) {
            val input = android.app.RemoteInput.Builder(EXTRA_CODE).setLabel(app.getString(R.string.self_adb_code)).build()
            val pi = PendingIntent.getBroadcast(app, 1, Intent(app, SelfAdbCodeReceiver::class.java).setAction(ACTION_CODE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            b.addAction(android.app.Notification.Action.Builder(null, app.getString(R.string.self_adb_code), pi).addRemoteInput(input).build())
        }
        runCatching { nm.notify(NOTE_ID, b.build()) }
    }

    /** The key Mate pairs with, made once and kept in its own files. */
    private class Manager(private val app: Context) : AbsAdbConnectionManager() {
        private val dir = File(app.filesDir, "self-adb")
        private val key: PrivateKey
        private val cert: Certificate

        init {
            setApi(Build.VERSION.SDK_INT)
            setTimeout(10, TimeUnit.SECONDS)
            val (k, c) = selfAdbKey(dir)
            key = k; cert = c
        }

        override fun getPrivateKey(): PrivateKey = key
        override fun getCertificate(): Certificate = cert
        override fun getDeviceName(): String = "Mate"
    }
}

/** The key and certificate Mate pairs with: made once into [dir] (RSA 2048, self-signed "CN=Mate"), read from there after. */
internal fun selfAdbKey(dir: File): Pair<PrivateKey, Certificate> {
    val keyFile = File(dir, "key.pk8"); val certFile = File(dir, "cert.der")
    if (!keyFile.exists() || !certFile.exists()) {
        dir.mkdirs()
        val gen = SelfSignedCertificate.generate("Mate")
        val pair = gen.keyPair
        val c = gen.certificate
        keyFile.writeBytes(pair.private.encoded)
        certFile.writeBytes(c.encoded)
    }
    val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
    val cert = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
    return key to cert
}

/** The code typed into the pairing notification. */
class SelfAdbCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = android.app.RemoteInput.getResultsFromIntent(intent)?.getCharSequence(SelfAdb.EXTRA_CODE)?.toString() ?: return
        SelfAdb.onCode(context, code)
    }
}
