package net.prototux.aare.dash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Adjust
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.DirectionsBoat
import androidx.compose.material.icons.rounded.WaterDrop
import net.prototux.aare.ui.car.Mate
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.prototux.aare.R
import net.prototux.aare.ui.car.navi.RegionPacks
import net.prototux.aare.ui.car.navi.mapStyle
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.value.IconRotationAlignment
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position
import kotlin.time.Duration.Companion.milliseconds

/**
 * The dashboard's map, drawn by Mate (System L-079): the region package on the device as the base map, as Mate Navi's
 * offline map (no tiles over the net), and over it what IDmate's page drew from IDmate's data - the range area with the
 * dimmed land out of reach, the wind arrows, the rain radar, the trail and the car. The controls as before (CarStage):
 * the crosshair follows the car, the range button shows the whole area, plus and minus; the start shows the area and
 * glides to the car after 3 s; a long press offers "Navigieren" to that place (Mate Navi or the chosen app).
 */
object DashMapView {
    /** Night on the car's screen: the map's colours as Mate Navi's at night. */
    internal val night = mutableStateOf(false)
    /** The start's overview and glide to the car once per app start, as before (CarStage.sAutoDone). */
    @Volatile internal var introDone = false

    @JvmStatic fun night(on: Boolean) { night.value = on }

    /**
     * The map as a view for the dashboard's stage, at Mate's size on the car screen ([dpi], as the rest of Mate there -
     * the display's own density made the buttons a third smaller); [go] takes "lat,lon" for the car's navigation.
     */
    @JvmStatic fun create(host: Context, dpi: Int, go: java.util.function.Consumer<String>): View =
        ComposeView(host).apply {
            setContent {
                val base = LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides androidx.compose.ui.unit.Density(dpi / 160f, base.fontScale)) {
                    DashMap { at -> go.accept(at) }
                }
            }
        }
}

private const val TAG = "IDmateStage"
private val MAP_BLUE = Color(0xFF0D5FD6)

/**
 * The margin when the range (and the ferries) are fitted into view: on the left past the column of buttons - 12 dp from
 * the edge, 44 dp wide - so they never cover the outline (22.11.2026: "achte drauf das der umkreis im dashboard neben den
 * buttons angezeigt wird und die buttons nicht teile des umkreises auch bei fähren verdeckt").
 */
private val FIT_PADDING = androidx.compose.foundation.layout.PaddingValues(start = 12.dp + 44.dp + 20.dp, top = 32.dp, end = 32.dp, bottom = 32.dp)
/** Following the car: it sits in the middle of the free map right of the buttons, not under their side. */
private val FOLLOW_PADDING = androidx.compose.foundation.layout.PaddingValues(start = 12.dp + 44.dp + 20.dp)
private val CAR_TEAL = Color(0xFF00B3A4)
/** IDmate's rain blues: lighter alone, deeper and more opaque beside the wind arrows (rainStyle). */
private val RAIN_COLS = listOf(Color(0xFF9ECAE1), Color(0xFF4292C6), Color(0xFF08519C))
private val RAIN_COLS_WIND = listOf(Color(0xFF6BAED6), Color(0xFF2171B5), Color(0xFF08306B))
/**
 * At night on the dark map (21.11.2026: "ob die Kontraste für die Wetteroberflächen dann noch zum Nachtmodus passen"):
 * IDmate's deep blues vanished there, heavy rain the least seen - from a dim to a bright blue instead, the heavy the
 * brightest; the ring light blue on a dark edge; out of reach darker.
 */
private val RAIN_COLS_NIGHT = listOf(Color(0xFF2B5C8A), Color(0xFF3D8BD9), Color(0xFF9CD0FF))
private val MAP_BLUE_NIGHT = Color(0xFF58A6FF)
/** IDmate's ferry purple (drawFerries), at night a lighter one on the dark map. */
private val FERRY_PURPLE = Color(0xFF7A3FD6)
private val FERRY_PURPLE_NIGHT = Color(0xFFB392F0)
private val WIND_COLS = listOf(0xFF57606A.toInt(), 0xFF0969DA.toInt(), 0xFFBC4C00.toInt(), 0xFFCF222E.toInt())
private val WIND_SIZE = listOf(17, 21, 25, 25)
/**
 * IDmate's arrow sizes are pixels of its page; Mate's map draws at Mate's density (208 dpi against the screen's 160), so
 * they came a third larger than on IDmate's page (22.11.2026: "überleg ob die wind pfeile nicht zu groß dargestellt
 * werden"): drawn at 160/208 of it, as large as IDmate shows them.
 */
private const val WIND_SCALE = 160f / 208f
private const val NEAR_ZOOM = 13.0
/** The car's place looked at this often; the car drawn 25 times a second in between (I-148, I-150). */
private const val POLL_MS = 1_000L
private const val FRAME_MS = 40L
/** Further than this from the last place: no glide, the car is put there. */
private const val JUMP_KM = 1.0
/** The map draws the symbols at half the size the legend shows (its own pixel ratio): twice, as the legend's arrows. */
private const val ICON_SCALE = 2f
private const val START_ZOOM = 7.0
/** Berlin, Potsdamer Platz (Mate Navi's test start) until the car's place is known. */
private val NOWHERE = Position(longitude = 13.3762, latitude = 52.5096)

@Composable
private fun DashMap(go: (String) -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val night by DashMapView.night

    // the car: IDmate's position (stick or server), looked at every 2 s
    var car by remember { mutableStateOf<Position?>(null) }
    var heading by remember { mutableStateOf<Double?>(null) }
    var rangeKm by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            val lat = DashData.number(context, "lat", Long.MAX_VALUE); val lon = DashData.number(context, "lon", Long.MAX_VALUE)
            if (lat != null && lon != null && (lat != 0.0 || lon != 0.0)) {
                val p = Position(longitude = lon, latitude = lat)
                // System I-087: the exported log said nothing of the dashboard's map - where its car comes from, once
                if (car == null) net.prototux.aare.DiagLog.add("dashboard map: car's place known (from " + (DashData.sourceOf(context, "lat") ?: "?") + ")")
                if (car?.let { it.latitude != lat || it.longitude != lon } != false) car = p
            }
            heading = DashData.number(context, "heading", 10 * 60_000L)
            rangeKm = DashData.rangeKm(context)
            delay(POLL_MS)
        }
    }
    // the base map: the region package around the car (the device's own map), the server's only without one
    val pack = remember(car?.let { (it.longitude * 10).toInt() to (it.latitude * 10).toInt() }) {
        val p = car ?: NOWHERE
        // System I-087 (22.11.2026: "Die Karte wird nur teilweise geladen, aber bleibt hauptsächlich grau"): only a package
        // that holds the car's place - the largest one was taken when none did (Sachsen for a car in Brandenburg), and the
        // map stayed grey outside the packages; then the map server, as Mate Navi does with the net
        RegionPacks.mapFor(context, p.longitude, p.latitude)?.takeIf { car != null && it.covers(p.longitude, p.latitude) }
    }
    LaunchedEffect(pack?.id) { net.prototux.aare.DiagLog.add("dashboard map: base map " + (pack?.let { "package ${it.id} on the device" } ?: "from the map server (no package here)")) }
    val style = remember(night, pack?.file) { BaseStyle.Json(mapStyle(context, night, pack)) }

    // IDmate's data from the store kept while the car is connected (DashMapCache): the last ones at once, a round now
    val area by DashMapCache.area.collectAsState()
    val trail by DashMapCache.trail.collectAsState()
    val wind by DashMapCache.wind.collectAsState()
    val windSeen by DashMapCache.windSeen.collectAsState()
    val rainRaw by DashMapCache.rain.collectAsState()
    val ferries by DashMapCache.ferries.collectAsState()
    val ferryState by DashMapCache.ferryState.collectAsState()
    val ferriesOffered by DashMapCache.ferriesOffered.collectAsState()
    LaunchedEffect(Unit) { DashMapCache.refreshSoon(context) }
    val rainFrame = remember(rainRaw) { DashMapGeo.rainNow(rainRaw) }
    val rain = remember(rainFrame) { DashMapGeo.rain(rainFrame, rainRaw?.optDouble("cell_km", 0.0) ?: 0.0) }
    val rainAt = remember(rainFrame) { rainFrame?.optString("t")?.let(::clock) }
    val rainSrc = remember(rainFrame) { rainFrame?.optJSONArray("src")?.let { a -> (0 until a.length()).mapNotNull { SOURCES[a.optString(it)] }.joinToString(", ") }?.ifBlank { null } }
    // wind and rain switched on and off as on IDmate's page (wind on, rain off at first), kept on the device
    val prefs = remember { context.getSharedPreferences("dash_map", Context.MODE_PRIVATE) }
    var windOn by remember { mutableStateOf(prefs.getBoolean("wind", true)) }
    var rainOn by remember { mutableStateOf(prefs.getBoolean("rain", false)) }

    // the camera: the whole area first, after 3 s to the car (once per start), then following the car while the crosshair is on
    val camera = rememberCameraState(CameraPosition(target = NOWHERE, zoom = START_ZOOM))
    var follow by remember { mutableStateOf(false) }
    // the zoom the following keeps: the car's moves must not undo a zoom on its way (they took the glide in back to 7)
    var followZoom by remember { mutableStateOf(NEAR_ZOOM) }
    var introDone by remember { mutableStateOf(DashMapView.introDone) }
    /** The whole range: the road area, else the circle of the range around the car. */
    fun rangeBox(): DoubleArray? = area?.box ?: run {
        val c = car ?: return null; val km = rangeKm ?: return null
        val dLat = km / 111.2; val dLon = km / (111.2 * Math.cos(Math.toRadians(c.latitude)))
        doubleArrayOf(c.longitude - dLon, c.latitude - dLat, c.longitude + dLon, c.latitude + dLat)
    }
    fun overview() {
        val box = rangeBox() ?: return
        follow = false
        scope.launch { runCatching { camera.animateTo(BoundingBox(box[0], box[1], box[2], box[3]), 0.0, 0.0, FIT_PADDING) } }
    }
    fun toCar(zoom: Double = maxOf(camera.position.zoom, NEAR_ZOOM)) {
        val c = car ?: return
        followZoom = zoom
        follow = true
        scope.launch { runCatching { camera.animateTo(CameraPosition(target = c, zoom = zoom, padding = FOLLOW_PADDING), 900.milliseconds) } }
    }
    fun zoomBy(d: Int) {
        if (follow) { followZoom = (followZoom + d).coerceIn(3.0, 18.0); car?.let { c -> scope.launch { runCatching { camera.animateTo(CameraPosition(target = c, zoom = followZoom, padding = FOLLOW_PADDING), 300.milliseconds) } } } }
        else scope.launch { runCatching { camera.animateTo(camera.position.copy(zoom = (camera.position.zoom + d).coerceIn(3.0, 18.0)), 300.milliseconds) } }
    }
    // after the first time: straight to the car, following it
    LaunchedEffect(car != null) { if (introDone && car != null && !follow) toCar(NEAR_ZOOM) }
    LaunchedEffect(car != null, area != null || rangeKm != null) {
        if (introDone || car == null) return@LaunchedEffect
        if (rangeBox() != null) { overview(); delay(3_000) }
        introDone = true; DashMapView.introDone = true
        toCar(NEAR_ZOOM)
    }
    // the ferries come: range and ferries together in view, held while the button is on (IDmate's drawFerries)
    LaunchedEffect(ferries) {
        val f = ferries?.box ?: return@LaunchedEffect
        val r = rangeBox()
        val b = if (r == null) f else doubleArrayOf(minOf(r[0], f[0]), minOf(r[1], f[1]), maxOf(r[2], f[2]), maxOf(r[3], f[3]))
        follow = false
        runCatching { camera.animateTo(BoundingBox(b[0], b[1], b[2], b[3]), 0.0, 0.0, FIT_PADDING) }
    }
    // nothing to show: a word why, then the button is off again (IDmate's toasts)
    var ferryNote by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(ferryState) {
        ferryNote = when (ferryState) {
            DashMapCache.FERRY_NONE -> R.string.dash_ferry_none
            DashMapCache.FERRY_FORBIDDEN -> R.string.dash_ferry_forbidden
            DashMapCache.FERRY_NO_MODEL -> R.string.dash_ferry_nomodel
            DashMapCache.FERRY_ERROR -> R.string.dash_ferry_error
            else -> null
        }
        if (ferryNote != null) { delay(6_000); ferryNote = null; DashMapCache.ferriesOff() }
    }
    // System I-148, I-150 (27.11.2026: "zeigt dieser Mini-Pfeil die ganze Zeit Richtung Norden, obwohl ich der Autobahn entlang
    // fahre. Es läuft immer nur ein bisschen mit und dann pausiert das wieder. Warum kann das nicht genauso funktionieren wie die
    // Navi-Map"): the car is carried on between two places at its speed and in its direction, some 25 times a second, as the
    // navigation's car is - a new place only corrects it softly; the direction from its moves where the stick gives none
    var shown by remember { mutableStateOf<Position?>(null) }
    var shownHeading by remember { mutableStateOf<Double?>(null) }
    val track = remember { DashDrive() }
    LaunchedEffect(car, heading) { car?.let { track.place(it, heading, android.os.SystemClock.uptimeMillis()) } }
    LaunchedEffect(Unit) {
        var lastFollow: Position? = null
        while (true) {
            val now = android.os.SystemClock.uptimeMillis()
            val (p, h) = track.at(now) ?: run { delay(FRAME_MS); return@run null } ?: continue
            shown = p; shownHeading = h
            if (follow && introDone) {
                // a jump (the first place, or far): the camera there at once; else along with the car each frame
                val far = lastFollow?.let { net.prototux.aare.ui.car.navi.ChargerIndex.distanceM(it, p) > JUMP_KM * 1000 } ?: true
                if (far) runCatching { camera.animateTo(CameraPosition(target = p, zoom = followZoom, padding = FOLLOW_PADDING), 300.milliseconds) }
                else runCatching { camera.position = CameraPosition(target = p, zoom = followZoom, padding = FOLLOW_PADDING, bearing = camera.position.bearing, tilt = camera.position.tilt) }
                lastFollow = p
            } else lastFollow = null
            delay(FRAME_MS)
        }
    }

    var picked by remember { mutableStateOf<Position?>(null) }
    val windImages = remember(density) { List(4) { arrow(it, density) } }
    val carImage = remember(density) { carMark(density) }
    val options = remember { MapOptions(ornamentOptions = OrnamentOptions.AllDisabled) }

    Box(Modifier.fillMaxSize()) {
        // a finger moving the map ends the following (the map stays where it was put); the buttons are not part of it
        MaplibreMap(modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    if (e.changes.any { it.pressed && it.previousPressed && (it.position - it.previousPosition).getDistance() > 6f }) follow = false
                }
            }
        }, baseStyle = style, cameraState = camera, options = options,
            onMapLongClick = { pos, _ -> picked = pos; ClickResult.Consume }) {
            val a = area
            // out of reach dimmed by 10 % (21.11.2026: "normalerweise alles außerhalb der reichweite mit 10% ausgegraut"),
            // around the road area or, until there is one, around the circle
            // the ferries' areas behind long crossings are reachable too: cut out of the same shade (IDmate's shade.setFerry)
            val fe = ferries
            val shade = remember(a, fe, car, rangeKm?.toInt()) {
                val base = a?.rings ?: run { val p = car; val km = rangeKm
                    if (p == null || km == null || km <= 0) emptyList() else listOf(DashMapGeo.circleRing(p.latitude, p.longitude, km)) }
                if (base.isEmpty() && fe == null) DashMapGeo.EMPTY else DashMapGeo.shadeOf(base, fe?.rings ?: emptyList())
            }
            FillLayer(id = "dash-shade", source = rememberGeoJsonSource(remember(shade) { GeoJsonData.JsonString(shade) }),
                color = const(Color.Black), opacity = const(if (night) 0.25f else 0.10f))
            val windShown = windOn && windSeen
            for (g in 0 until 3) {
                FillLayer(id = "dash-rain-$g", source = rememberGeoJsonSource(remember(rain[g], rainOn) { GeoJsonData.JsonString(if (rainOn) rain[g] else DashMapGeo.EMPTY) }),
                    color = const(if (night) RAIN_COLS_NIGHT[g] else if (windShown) RAIN_COLS_WIND[g] else RAIN_COLS[g]),
                    opacity = const(if (night) 0.55f else if (windShown) 0.7f else 0.5f))
            }
            // the one range ring: IDmate's expected range (with wind its outline), white edge under the blue line
            val outer = rememberGeoJsonSource(remember(a?.outer) { GeoJsonData.JsonString(a?.outer ?: DashMapGeo.EMPTY) })
            LineLayer(id = "dash-area-halo", source = outer, color = const(if (night) Color(0xFF0D1117) else Color.White), width = const(6.5.dp),
                opacity = const(if (night) 0.8f else 0.9f), cap = const(LineCap.Round), join = const(LineJoin.Round))
            LineLayer(id = "dash-area", source = outer, color = const(if (night) MAP_BLUE_NIGHT else MAP_BLUE), width = const(3.dp), opacity = const(1f), cap = const(LineCap.Round), join = const(LineJoin.Round))
            // no road area (yet): the range as a thin circle around the car, as IDmate's page
            val circle = remember(a == null, car, rangeKm?.toInt()) {
                val p = car; val km = rangeKm
                if (a != null || p == null || km == null) DashMapGeo.EMPTY else DashMapGeo.circle(p.latitude, p.longitude, km)
            }
            LineLayer(id = "dash-range-circle", source = rememberGeoJsonSource(remember(circle) { GeoJsonData.JsonString(circle) }),
                color = const(if (night) MAP_BLUE_NIGHT else MAP_BLUE), width = const(2.dp), opacity = const(0.8f))
            // long ferries (IDmate's button "Fähren"): purple with a white edge, no fill (their areas are cut out of the shade)
            val ferryCol = if (night) FERRY_PURPLE_NIGHT else FERRY_PURPLE
            val ferryHalo = if (night) Color(0xFF0D1117) else Color.White
            val sea = rememberGeoJsonSource(remember(fe?.sea) { GeoJsonData.JsonString(fe?.sea ?: DashMapGeo.EMPTY) })
            LineLayer(id = "dash-ferry-sea-halo", source = sea, color = const(ferryHalo), width = const(4.5.dp), opacity = const(0.9f), cap = const(LineCap.Round), join = const(LineJoin.Round))
            LineLayer(id = "dash-ferry-sea", source = sea, color = const(ferryCol), width = const(2.dp), opacity = const(0.95f), dasharray = const(listOf(3, 2.5)))
            val ferryLine = rememberGeoJsonSource(remember(fe?.outline) { GeoJsonData.JsonString(fe?.outline ?: DashMapGeo.EMPTY) })
            LineLayer(id = "dash-ferry-halo", source = ferryLine, color = const(ferryHalo), width = const(5.4.dp), opacity = const(0.9f), cap = const(LineCap.Round), join = const(LineJoin.Round))
            LineLayer(id = "dash-ferry", source = ferryLine, color = const(ferryCol), width = const(2.7.dp), opacity = const(0.95f), cap = const(LineCap.Round), join = const(LineJoin.Round))
            CircleLayer(id = "dash-ferry-from", source = rememberGeoJsonSource(remember(fe?.portsFrom) { GeoJsonData.JsonString(fe?.portsFrom ?: DashMapGeo.EMPTY) }),
                color = const(ferryCol), radius = const(2.5.dp), strokeColor = const(ferryHalo), strokeWidth = const(1.dp))
            CircleLayer(id = "dash-ferry-to", source = rememberGeoJsonSource(remember(fe?.portsTo) { GeoJsonData.JsonString(fe?.portsTo ?: DashMapGeo.EMPTY) }),
                color = const(ferryCol), radius = const(3.5.dp), strokeColor = const(ferryHalo), strokeWidth = const(1.5.dp))
            LineLayer(id = "dash-trail", source = rememberGeoJsonSource(remember(trail) { GeoJsonData.JsonString(trail) }),
                color = const(CAR_TEAL), width = const(4.dp), opacity = const(0.8f), cap = const(LineCap.Round), join = const(LineJoin.Round))
            for (k in 0 until 4) {
                SymbolLayer(id = "dash-wind-$k", source = rememberGeoJsonSource(remember(wind[k], windOn) { GeoJsonData.JsonString(if (windOn) wind[k] else DashMapGeo.EMPTY) }), minZoom = 5f,
                    iconImage = image(windImages[k]), iconSize = const(ICON_SCALE), iconRotate = feature.get("rot").asNumber(),
                    iconRotationAlignment = const(IconRotationAlignment.Map), iconAllowOverlap = const(true))
            }
            val c = shown ?: car
            val ch = shownHeading ?: heading
            SymbolLayer(id = "dash-car", source = rememberGeoJsonSource(remember(c, ch) { GeoJsonData.JsonString(c?.let { DashMapGeo.car(it.latitude, it.longitude, ch) } ?: DashMapGeo.EMPTY) }),
                iconImage = image(carImage), iconSize = const(ICON_SCALE), iconRotate = feature.get("head").asNumber(),
                iconRotationAlignment = const(IconRotationAlignment.Map), iconAllowOverlap = const(true))
        }
        // the own controls at the driver's side, with the signs they had (CarStage.MapButton): crosshair, range, wind,
        // rain - as large as the car bar's microphone; zoom with two fingers (21.11.2026: "braucht kein Plus und Minus")
        Column(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SignButton(Sign.CENTER, follow, stringResource(R.string.dash_map_follow)) { toCar() }
            SignButton(Sign.RANGE, false, stringResource(R.string.dash_map_range)) { overview() }
            if (windSeen) SignButton(Sign.WIND, windOn, stringResource(R.string.dash_map_wind)) { windOn = !windOn; prefs.edit().putBoolean("wind", windOn).apply() }
            SignButton(Sign.RAIN, rainOn, stringResource(R.string.dash_map_rain)) { rainOn = !rainOn; prefs.edit().putBoolean("rain", rainOn).apply() }
            // only when IDmate can search them; off at every start, one search per switch-on (as IDmate's page)
            val ferryOn = ferryState == DashMapCache.FERRY_BUSY || ferryState == DashMapCache.FERRY_SHOWN
            if (ferriesOffered || ferryState != DashMapCache.FERRY_OFF) SignButton(Sign.FERRY, ferryOn, stringResource(R.string.dash_map_ferries)) {
                if (ferryOn) { DashMapCache.ferriesOff(); overview() } else DashMapCache.ferriesOn(context)
            }
        }
        // the legends, top right on the map: wind classes and rain grades with time and source (as IDmate's)
        Column(Modifier.align(Alignment.TopEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
            if (windOn && windSeen) Legend(stringResource(R.string.dash_map_wind)) {
                listOf("< 15 km/h", "15–30 km/h", "30–45 km/h", "> 45 km/h").forEachIndexed { k, label ->
                    LegendRow({ androidx.compose.foundation.Image(windImages[k], null, Modifier.size(16.dp)) }, label)
                }
            }
            if (rainOn) Legend(stringResource(R.string.dash_map_rain)) {
                val cols = if (night) RAIN_COLS_NIGHT else if (windOn && windSeen) RAIN_COLS_WIND else RAIN_COLS
                listOf(R.string.dash_rain_light, R.string.dash_rain_moderate, R.string.dash_rain_heavy).forEachIndexed { g, id ->
                    LegendRow({ Box(Modifier.size(18.dp).clip(RoundedCornerShape(3.dp)).background(cols[g])) }, stringResource(id))
                }
                Text(rainAt?.let { stringResource(R.string.dash_rain_now, it) } ?: stringResource(R.string.dash_rain_waiting), color = Mate.textSoft, fontSize = Mate.caption)
            }
        }
        // the map data's credit, as on Mate Navi's map (ODbL)
        // every source on the map named while it is shown, as their terms ask (22.11.2026: "die copyrights erwähnungen für
        // wetterdienst etc fehlen"): OpenStreetMap (ODbL), the wind of Open-Meteo (CC BY 4.0), the radar of the Deutscher
        // Wetterdienst (GeoNutzV: "Quelle: Deutscher Wetterdienst") and RainViewer
        val credit = buildString {
            append(net.prototux.aare.ui.car.navi.OSM_CREDIT)
            if (windOn && windSeen) append(" · Wind: Open-Meteo")
            // only what is drawn (22.11.2026: "in der map auch nur die aktiven gezeigten"): the radar once a frame is
            // there, with the services that made it; the sources not in the legend ("reicht unten in der map")
            if (rainOn) rainSrc?.let { append(" · Radar: ").append(it) }
        }
        // on a light plate (night: a dark one), readable over the busy map
        Text(credit, color = if (night) Color(0xFFB1BAC4) else Color(0xFF57606A), fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 6.dp).clip(RoundedCornerShape(4.dp))
                .background(if (night) Color(0xB30D1117) else Color(0xB3FFFFFF)).padding(horizontal = 5.dp, vertical = 1.dp))
        ferryNote?.let { id ->
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).clip(RoundedCornerShape(14.dp)).background(Mate.cardSolid)
                .border(1.5.dp, Mate.floatEdge, RoundedCornerShape(14.dp)).padding(horizontal = 18.dp, vertical = 12.dp)) {
                Text(stringResource(id), color = Mate.text, fontSize = Mate.body)
            }
        }
        picked?.let { p ->
            LaunchedEffect(p) { delay(8_000); if (picked == p) picked = null }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).clip(RoundedCornerShape(14.dp)).background(Mate.cardSolid)
                .border(1.5.dp, Mate.floatEdge, RoundedCornerShape(14.dp))
                .clickable { picked = null; go("%.6f,%.6f".format(java.util.Locale.ROOT, p.latitude, p.longitude)) }.padding(horizontal = 18.dp, vertical = 12.dp)) {
                Text(stringResource(R.string.dash_pick_navigate), color = Mate.text, fontSize = Mate.heading, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            }
        }
    }
}

private enum class Sign { CENTER, RANGE, WIND, RAIN, FERRY }

/** IDmate's rain sources by the names their terms ask for. */
private val SOURCES = mapOf("dwd" to "Deutscher Wetterdienst", "rainviewer" to "RainViewer")

/** "2026-11-21T11:15:00Z" as the device's clock time, "11:15" (12 or 24 hours as the region writes it); null when it is no time. */
private fun clock(iso: String): String? = runCatching {
    net.prototux.aare.ui.Clock.format(java.time.Instant.parse(iso).toEpochMilli())
}.getOrNull()

/**
 * A map button in Mate's look, as Mate Navi's buttons (22.11.2026: "die dashboard map bedienflächen sind viel größer als
 * der rest der bedienoberfläche … alles aus einem guss ein design"): Mate's card with its light edge, a Material sign
 * (the same as in Mate Navi, the ship as in its trip plan), teal when on; 44 dp at Mate's density, on the car screen
 * as large as the bar's microphone and the reload button beside the map.
 */
@Composable
private fun SignButton(sign: Sign, on: Boolean, label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(Modifier.size(44.dp).clip(shape).background(if (on) Mate.accentContainer else Mate.cardSolid)
        .border(1.5.dp, if (on) Mate.accent else Mate.floatEdge, shape)
        .clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        val icon = when (sign) {
            Sign.CENTER -> Icons.Rounded.MyLocation
            Sign.RANGE -> Icons.Rounded.Adjust
            Sign.WIND -> Icons.Rounded.Air
            Sign.RAIN -> Icons.Rounded.WaterDrop
            Sign.FERRY -> Icons.Rounded.DirectionsBoat
        }
        Icon(icon, contentDescription = label, tint = if (on) Mate.accent else Mate.text, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun Legend(title: String, rows: @Composable () -> Unit) {
    // Mate's card with its light edge, as the buttons (on the dark night map the dark plate would melt into it)
    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(Mate.cardSolid).border(1.5.dp, Mate.floatEdge, RoundedCornerShape(14.dp))
        .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Mate.text, fontSize = Mate.caption, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        rows()
    }
}

@Composable
private fun LegendRow(mark: @Composable () -> Unit, label: String) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        mark(); Text(label, color = Mate.textDim, fontSize = Mate.caption)
    }
}

/** IDmate's wind arrow (windIcon): pointing up, the class's colour with a white edge; turned by the layer. */
private fun arrow(cls: Int, density: Float): ImageBitmap {
    val sz = (WIND_SIZE[cls] * density * WIND_SCALE).toInt().coerceAtLeast(8)
    val b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
    val c = Canvas(b); val s = sz / 22f
    c.scale(s, s); c.translate(1f, 1f)
    val path = Path().apply { moveTo(10f, 1f); lineTo(15.5f, 10f); lineTo(12f, 10f); lineTo(12f, 19f); lineTo(8f, 19f); lineTo(8f, 10f); lineTo(4.5f, 10f); close() }
    c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.2f; color = android.graphics.Color.WHITE; strokeJoin = Paint.Join.ROUND })
    c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = WIND_COLS[cls] })
    return b.asImageBitmap()
}

/** The car: a teal dot with a white edge and a point towards its heading. */
private fun carMark(density: Float): ImageBitmap {
    val sz = (34 * density).toInt()
    val b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
    val c = Canvas(b); val m = sz / 2f; val r = sz * 0.30f
    val teal = android.graphics.Color.rgb(0x00, 0xB3, 0xA4)
    c.drawPath(Path().apply { moveTo(m, 0f); lineTo(m + r * 0.7f, m - r * 0.55f); lineTo(m - r * 0.7f, m - r * 0.55f); close() },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = teal })
    c.drawCircle(m, m, r + 3 * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
    c.drawCircle(m, m, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = teal })
    return b.asImageBitmap()
}
