package net.prototux.aare.media

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Finds media apps: services answering the media3 MediaLibraryService or the legacy
 * MediaBrowserService action, one per package (media3 preferred). Rescans on package changes.
 */
internal class MediaDiscovery(private val context: Context, private val scope: CoroutineScope) {
    private val _apps = MutableStateFlow<List<MediaApp>>(emptyList())
    val apps: StateFlow<List<MediaApp>> = _apps.asStateFlow()

    private var scan: Job? = null
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = refresh()
    }

    fun start() {
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            // system broadcasts still reach a non-exported receiver
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
        refresh()
    }

    /** Rescans after a short debounce: an app update sends several broadcasts in a row. */
    fun refresh() {
        scan?.cancel()
        scan = scope.launch {
            delay(250)
            _apps.value = withContext(Dispatchers.IO) { scan() }
        }
    }

    fun release() {
        scan?.cancel()
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    private fun scan(): List<MediaApp> {
        val pm = context.packageManager
        val services = LinkedHashMap<String, ResolveInfo>()
        for (action in listOf(ACTION_MEDIA3_LIBRARY, ACTION_LEGACY_BROWSER)) {
            for (ri in pm.services(Intent(action))) {
                val si = ri.serviceInfo ?: continue
                // Others must offer their service to us; our own (the built-in music area) needs no such door.
                if (!si.exported && si.packageName != context.packageName) continue
                services.putIfAbsent(si.packageName, ri)
            }
        }
        return services.values.mapNotNull { ri ->
            val si = ri.serviceInfo
            try {
                val appInfo = pm.appInfo(si.packageName)
                MediaApp(
                    packageName = si.packageName,
                    service = ComponentName(si.packageName, si.name),
                    // Our own service is one part of this app (the built-in music area): its own name and sign, not the app's.
                    label = (if (si.packageName == context.packageName) si.loadLabel(pm) else appInfo.loadLabel(pm)).toString(),
                    icon = runCatching {
                        (if (si.packageName == context.packageName) si.loadIcon(pm) else appInfo.loadIcon(pm)).toBitmap(ICON_PX, ICON_PX)
                    }.getOrNull(),
                )
            } catch (e: Exception) {
                Log.w(TAG, "skipping ${si.packageName}: $e")
                null
            }
        }.sortedBy { it.label.lowercase() }
    }

    private fun PackageManager.services(intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= 33) queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
        else @Suppress("DEPRECATION") queryIntentServices(intent, 0)

    private fun PackageManager.appInfo(pkg: String): ApplicationInfo =
        if (Build.VERSION.SDK_INT >= 33) getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        else @Suppress("DEPRECATION") getApplicationInfo(pkg, PackageManager.GET_META_DATA)

    companion object {
        private const val TAG = "AareMediaApps"
        const val ACTION_MEDIA3_LIBRARY = "androidx.media3.session.MediaLibraryService"
        const val ACTION_LEGACY_BROWSER = "android.media.browse.MediaBrowserService"
        private const val ICON_PX = 128
    }
}
