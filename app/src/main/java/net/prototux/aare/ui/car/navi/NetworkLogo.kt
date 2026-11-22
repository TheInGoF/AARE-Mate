package net.prototux.aare.ui.car.navi

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import net.prototux.aare.ui.car.Mate

/**
 * The charging networks' own logos (O210, 16.11.2026: "versuche die ladebetreiber icons mit einzubauen"; decision: the
 * real logos), from Wikimedia Commons - sources in projekt/unterlagen/logos/QUELLEN.md. Each on a light plate of one
 * size, as ABRP shows them, so wide word marks and square signs line up; a network without one gets the bolt.
 */
internal object NetworkLogos {
    private val LOGOS = mapOf(
        "aldi" to R.drawable.net_logo_aldi,
        "allego" to R.drawable.net_logo_allego,
        "aral" to R.drawable.net_logo_aral,
        "circlek" to R.drawable.net_logo_circlek,
        "citywatt" to R.drawable.net_logo_citywatt,
        "enbw" to R.drawable.net_logo_enbw,
        "enercity" to R.drawable.net_logo_enercity,
        "eon" to R.drawable.net_logo_eon,
        "ewe" to R.drawable.net_logo_ewe,
        "fastned" to R.drawable.net_logo_fastned,
        "ionity" to R.drawable.net_logo_ionity,
        "jet" to R.drawable.net_logo_jet,
        "lidl" to R.drawable.net_logo_lidl,
        "norlys" to R.drawable.net_logo_norlys,
        "pfalzwerke" to R.drawable.net_logo_pfalzwerke,
        "shell" to R.drawable.net_logo_shell,
        "teag" to R.drawable.net_logo_teag,
        "tesla" to R.drawable.net_logo_tesla,
        "total" to R.drawable.net_logo_total,
        "vattenfall" to R.drawable.net_logo_vattenfall,
    )

    fun of(network: String?): Int? = network?.let { LOGOS[it] }

    /**
     * The logo on its light plate as a picture for the map (22.11.2026: "statt graue Symbole die Ladestation
     * Anbietersymbole"), [heightPx] high and 1.6 times as wide, a dark edge round it; null for a network without one.
     */
    fun bitmap(context: android.content.Context, network: String?, heightPx: Int, plate: Int, edge: Int): androidx.compose.ui.graphics.ImageBitmap? {
        val res = of(network) ?: return null
        val d = androidx.core.content.ContextCompat.getDrawable(context, res) ?: return null
        val w = (heightPx * 1.6f).toInt(); val h = heightPx
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val r = h / 4f; val e = h * 0.08f
        p.color = edge; c.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), r, r, p)
        p.color = plate; c.drawRoundRect(e, e, w - e, h - e, r, r, p)
        // the mark fitted into the plate, its sides kept
        val pad = h / 6f; val bw = w - 2 * pad; val bh = h - 2 * pad
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: 1; val ih = d.intrinsicHeight.takeIf { it > 0 } ?: 1
        val s = minOf(bw / iw, bh / ih)
        val dw = iw * s; val dh = ih * s
        d.setBounds(((w - dw) / 2).toInt(), ((h - dh) / 2).toInt(), ((w + dw) / 2).toInt(), ((h + dh) / 2).toInt())
        d.draw(c)
        return bmp.asImageBitmap()
    }
}

/** The logo of [network] on its plate, [height] high and 1.6 times as wide; the bolt in [boltTint] without one. */
@Composable
internal fun NetworkLogo(network: String?, height: Dp, modifier: Modifier = Modifier, boltTint: Color = Mate.accent) {
    val res = NetworkLogos.of(network)
    Box(modifier.size(height * 1.6f, height), contentAlignment = Alignment.Center) {
        if (res == null) Icon(Icons.Rounded.Bolt, null, tint = boltTint, modifier = Modifier.size(height * 0.8f))
        else Box(Modifier.size(height * 1.6f, height).clip(RoundedCornerShape(height / 4)).background(Mate.logoPlate)
            .padding(horizontal = height / 8, vertical = height / 8), contentAlignment = Alignment.Center) {
            Image(painterResource(res), null, contentScale = ContentScale.Fit)
        }
    }
}
