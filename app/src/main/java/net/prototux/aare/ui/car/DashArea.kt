package net.prototux.aare.ui.car

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import net.prototux.aare.R
import net.prototux.aare.dash.DashView
import net.prototux.aare.host.CarRuntime

/**
 * Mate's own dashboard on the car screen (19.11.2026: "Also muss Mate auch selber das Dashboard zeichnen"): its views
 * straight in the car screen - before, a car app Mate hosted itself, which got a surface handed over and laid a display
 * of its own on it. Kept while another area is in front; "Neu laden" top right builds the page anew.
 */
@Composable
fun DashArea(runtime: CarRuntime, modifier: Modifier = Modifier) {
    val dpi = runtime.mateDensityDpi
    // the map dark at night, like Mate Navi's (System L-074)
    val night = net.prototux.aare.ui.theme.LocalCarNight.current
    LaunchedEffect(night) { DashView.night(night) }
    // the car's own values reach the dashboard from CarRuntime, the whole connection long
    Box(modifier) {
        key(dpi) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        addView(DashView.show(ctx, dpi) { at -> runtime.navigateTo(at) },
                            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    }
                },
            )
            DisposableEffect(Unit) { onDispose { DashView.hide() } }
        }
        // in Mate's look as the map's buttons beside it (22.11.2026: "alles aus einem guss ein design"): this screen's
        // density is the display's, the map's Mate's (208/160) - 56 dp here are the map's 44
        Box(
            Modifier.align(Alignment.TopEnd).padding(16.dp).size(56.dp).clip(RoundedCornerShape(18.dp))
                .background(Mate.cardSolid).border(2.dp, Mate.floatEdge, RoundedCornerShape(18.dp)).clickable { DashView.reload() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Refresh, stringResource(R.string.dash_reload), tint = Mate.text, modifier = Modifier.size(30.dp))
        }
    }
}
