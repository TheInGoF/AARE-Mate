package net.prototux.aare.ui.car

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.animation.core.animateFloat
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.prototux.aare.R

/** The phone's network and battery, as its status bar would show them. */
data class PhoneStatusInfo(
    /** Mobile signal, 0 to 4 bars; null without a SIM or service. */
    val signal: Int?,
    /** The phone's internet goes through Wi-Fi (not the car's own network, which has none). */
    val wifi: Boolean,
    val battery: Int,
    val charging: Boolean,
) {
    companion object {
        fun read(context: Context): PhoneStatusInfo {
            val bat = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.takeIf { it > 0 } ?: 100
            val plugged = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
            val tm = context.getSystemService(TelephonyManager::class.java)
            // TelephonyManager.signalStrength is Android 9+: no bars before.
            val signal = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching { tm.signalStrength?.level }.getOrNull() else null)
                ?.takeIf { tm.simState == TelephonyManager.SIM_STATE_READY }
            return PhoneStatusInfo(
                signal = signal,
                wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
                battery = if (level >= 0) level * 100 / scale else 0,
                charging = plugged,
            )
        }
    }
}

/** Whether the phone has a working way to the internet (a validated network), followed as it changes. */
@Composable
private fun rememberOnline(): Boolean {
    val context = LocalContext.current
    var online by remember { mutableStateOf(true) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        fun now() = cm?.getNetworkCapabilities(cm.activeNetwork)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true
        online = now()
        // The callback speaks of the default network: lost means there is none now (asking the manager at that
        // moment still names the one going away); its capabilities say whether it reaches the internet.
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onLost(network: android.net.Network) { online = false }
            override fun onCapabilitiesChanged(network: android.net.Network, caps: NetworkCapabilities) {
                online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }
        runCatching { cm?.registerDefaultNetworkCallback(cb) }
        onDispose { runCatching { cm?.unregisterNetworkCallback(cb) } }
    }
    return online
}

/** Signal bars, Wi-Fi and battery, for the rail under the clock. */
@Composable
fun PhoneStatusIcons(offline: Color? = null) {
    val context = LocalContext.current
    val online = rememberOnline()
    var status by remember { mutableStateOf<PhoneStatusInfo?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            status = withContext(Dispatchers.Default) { runCatching { PhoneStatusInfo.read(context) }.getOrNull() }
            delay(15_000)
        }
    }
    val s = status ?: return
    val fg = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        // No way to the internet: said quietly, where the Wi-Fi sign is - a crossed sign that breathes, no banner.
        if (!online) {
            val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "offline").animateFloat(
                initialValue = 0.4f, targetValue = 1f, label = "offline",
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    androidx.compose.animation.core.tween(1600), androidx.compose.animation.core.RepeatMode.Reverse),
            )
            Icon(androidx.compose.material.icons.Icons.Rounded.WifiOff, stringResource(R.string.phone_status_offline),
                tint = (offline ?: MaterialTheme.colorScheme.error).copy(alpha = pulse), modifier = Modifier.size(18.dp))
        } else if (s.wifi) Icon(Icons.Rounded.Wifi, stringResource(R.string.phone_status_wifi), tint = fg, modifier = Modifier.size(16.dp))
        SignalBars(s.signal, fg, dim)
        BatteryGauge(s.battery, s.charging, fg, dim)
        if (s.charging) Icon(Icons.Rounded.Bolt, stringResource(R.string.phone_status_charging), tint = fg, modifier = Modifier.size(14.dp))
        else Text("${s.battery}", style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
    }
}

@Composable
private fun SignalBars(level: Int?, fg: Color, dim: Color) {
    Canvas(Modifier.size(16.dp)) {
        val bar = size.width / 5.5f
        for (i in 0 until 4) {
            val h = size.height * (i + 1) / 4
            drawRoundRect(
                color = if (level != null && i < level) fg else dim,
                topLeft = Offset(i * bar * 1.45f, size.height - h),
                size = Size(bar, h),
                cornerRadius = CornerRadius(bar / 3),
            )
        }
    }
}

@Composable
private fun BatteryGauge(percent: Int, charging: Boolean, fg: Color, dim: Color) {
    val low = !charging && percent <= 15
    val fill = if (low) MaterialTheme.colorScheme.error else fg
    Canvas(Modifier.size(width = 22.dp, height = 12.dp)) {
        val tip = size.width * 0.08f
        val body = Size(size.width - tip, size.height)
        val stroke = 1.5.dp.toPx()
        drawRoundRect(dim, size = body, cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(stroke))
        drawRect(dim, topLeft = Offset(body.width, size.height * 0.3f), size = Size(tip, size.height * 0.4f))
        val inset = stroke * 1.5f
        drawRoundRect(fill, topLeft = Offset(inset, inset),
            size = Size((body.width - 2 * inset) * percent.coerceIn(0, 100) / 100f, body.height - 2 * inset),
            cornerRadius = CornerRadius(1.5.dp.toPx()))
    }
}
