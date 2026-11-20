package net.prototux.aare.ui.car

import androidx.compose.ui.unit.sp
import android.content.Context
import android.content.res.Resources
import androidx.compose.ui.graphics.Color
import net.prototux.aare.R

/**
 * The colours of Mate, read from res/values/mate_colors.xml: the one place they are set. Nothing in the car
 * screen names a colour value of its own - another theme is another set of these resources.
 */
object Mate {
    /**
     * The ladder of text sizes of Mate's bar and pop-ups (O151): one size for one kind of text, a title larger than
     * the text below it, nothing smaller than [caption]. They are drawn [net.prototux.aare.Settings.SHELL_BASE]
     * times larger and then equal the car screen's own ladder (ui/theme/CarTheme: 18, 22, 24, 28, 36).
     */
    val caption = 14.sp     // a second line, a time, a count
    val body = 17.sp        // names, labels, what one reads and taps
    val heading = 19.sp     // a button's word, a line that leads a card
    val title = 22.sp       // what a card or a page is about
    val display = 28.sp     // the one large thing: what is being typed, a distance

    private var res: Resources? = null
    private val cache = HashMap<Int, Color>()

    /** Call before the first colour is read, and again when the resources change (another theme, night). */
    fun init(context: Context) {
        res = context.applicationContext.resources
        cache.clear()
    }

    private fun c(id: Int): Color = cache.getOrPut(id) { Color(res!!.getColor(id, null)) }

    /**
     * A colour of Mate Navi's map by its key in assets/navi/karte-v4.json ("autobahn", "water" ...): mate_karte_<key>
     * by day, mate_karte_nacht_<key> by night - the block generated from IDmate's map (projekt/werkzeuge/navi-stil.mjs).
     */
    fun karte(context: Context, key: String, night: Boolean): Color? {
        val r = context.resources
        @Suppress("DiscouragedApi")
        val id = r.getIdentifier("mate_karte_" + (if (night) "nacht_" else "") + key, "color", context.packageName)
        return if (id == 0) null else Color(r.getColor(id, null))
    }

    val bg: Color get() = c(R.color.mate_bg)
    val bgLow: Color get() = c(R.color.mate_bg_low)
    val surface: Color get() = c(R.color.mate_surface)
    val surfaceHigh: Color get() = c(R.color.mate_surface_high)
    val line: Color get() = c(R.color.mate_line)
    val text: Color get() = c(R.color.mate_text)
    val textDim: Color get() = c(R.color.mate_text_dim)
    val textSoft: Color get() = c(R.color.mate_text_soft)
    val textFaint: Color get() = c(R.color.mate_text_faint)
    val accent: Color get() = c(R.color.mate_accent)
    val onAccent: Color get() = c(R.color.mate_on_accent)
    val accentContainer: Color get() = c(R.color.mate_accent_container)
    val plate: Color get() = c(R.color.mate_plate)
    val scrim: Color get() = c(R.color.mate_scrim)
    val scrimSwitcher: Color get() = c(R.color.mate_scrim_switcher)
    val scrimNote: Color get() = c(R.color.mate_scrim_note)
    val card: Color get() = c(R.color.mate_card)
    val cardSolid: Color get() = c(R.color.mate_card_solid)
    val handle: Color get() = c(R.color.mate_handle)
    val call: Color get() = c(R.color.mate_call)
    val callEnd: Color get() = c(R.color.mate_call_end)
    val danger: Color get() = c(R.color.mate_danger)
    val mic: Color get() = c(R.color.mate_mic)
    val camera: Color get() = c(R.color.mate_camera)
    val mapLand: Color get() = c(R.color.mate_map_land)
    val mapGreen: Color get() = c(R.color.mate_map_green)
    val mapWater: Color get() = c(R.color.mate_map_water)
    val mapStreet: Color get() = c(R.color.mate_map_street)
    val mapStreetMain: Color get() = c(R.color.mate_map_street_main)
    val routeEdge: Color get() = c(R.color.mate_route_edge)
    val routeHalo: Color get() = c(R.color.mate_route_halo)
    val route: Color get() = c(R.color.mate_route)
    val routeAlt: Color get() = c(R.color.mate_route_alt)
    val floatEdge: Color get() = c(R.color.mate_float_edge)
    val routeAltEdge: Color get() = c(R.color.mate_route_alt_edge)
    val warn: Color get() = c(R.color.mate_warn)
    val logoPlate: Color get() = c(R.color.mate_logo_plate)
    val trafficClosed: Color get() = c(R.color.mate_traffic_closed)
    val trafficGround: Color get() = c(R.color.mate_traffic_ground)
    val signFace: Color get() = c(R.color.mate_sign_face)
    val signRing: Color get() = c(R.color.mate_sign_ring)
    val signText: Color get() = c(R.color.mate_sign_text)
}
