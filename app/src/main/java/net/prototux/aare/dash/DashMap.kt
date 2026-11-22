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
private const val NEAR_ZOOM = 13.0
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
                if (car?.let { it.latitude != lat || it.longitude != lon } != false) car = p
            }
            heading = DashData.number(context, "heading", 10 * 60_000L)
            rangeKm = DashData.rangeKm(context)
            delay(2_000)
        }
    }
    // the base map: the region package around the car (the device's own map), the server's only without one
    val pack = remember(car?.let { (it.longitude * 10).toInt() to (it.latitude * 10).toInt() }) {
        val p = car ?: NOWHERE
        RegionPacks.mapFor(context, p.longitude, p.latitude)
    }
    LaunchedEffect(pack?.id) { Log.i(TAG, "dashboard map: base map " + (pack?.let { "package ${it.id} on the device" } ?: "from the map server (no package here)")) }
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
        scope.launch { runCatching { camera.animateTo(BoundingBox(box[0], box[1], box[2], box[3]), 0.0, 0.0, androidx.compose.foundation.layout.PaddingValues(32.dp)) } }
    }
    fun toCar(zoom: Double = maxOf(camera.position.zoom, NEAR_ZOOM)) {
        val c = car ?: return
        followZoom = zoom
        follow = true
        scope.launch { runCatching { camera.animateTo(CameraPosition(target = c, zoom = zoom), 900.milliseconds) } }
    }
    fun zoomBy(d: Int) {
        if (follow) { followZoom = (followZoom + d).coerceIn(3.0, 18.0); car?.let { c -> scope.launch { runCatching { camera.animateTo(CameraPosition(target = c, zoom = followZoom), 300.milliseconds) } } } }
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
        runCatching { camera.animateTo(BoundingBox(b[0], b[1], b[2], b[3]), 0.0, 0.0, androidx.compose.foundation.layout.PaddingValues(32.dp)) }
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
    LaunchedEffect(car) { if (follow && introDone) car?.let { c -> runCatching { camera.animateTo(CameraPosition(target = c, zoom = followZoom), 900.milliseconds) } } }

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
            val shade = remember(a?.shade, a == null, car, rangeKm?.toInt()) {
                a?.shade ?: run { val p = car; val km = rangeKm; if (p == null || km == null) DashMapGeo.EMPTY else DashMapGeo.circleShade(p.latitude, p.longitude, km) }
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
            // long ferries (IDmate's button "Fähren"): purple with a white edge, the areas behind them lightly filled
            val fe = ferries
            val ferryCol = if (night) FERRY_PURPLE_NIGHT else FERRY_PURPLE
            val ferryHalo = if (night) Color(0xFF0D1117) else Color.White
            FillLayer(id = "dash-ferry-fill", source = rememberGeoJsonSource(remember(fe?.fill) { GeoJsonData.JsonString(fe?.fill ?: DashMapGeo.EMPTY) }),
                color = const(ferryCol), opacity = const(if (night) 0.16f else 0.12f))
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
            val c = car
            SymbolLayer(id = "dash-car", source = rememberGeoJsonSource(remember(c, heading) { GeoJsonData.JsonString(c?.let { DashMapGeo.car(it.latitude, it.longitude, heading) } ?: DashMapGeo.EMPTY) }),
                iconImage = image(carImage), iconSize = const(ICON_SCALE), iconRotate = feature.get("head").asNumber(),
                iconRotationAlignment = const(IconRotationAlignment.Map), iconAllowOverlap = const(true))
        }
        // the own controls at the driver's side, with the signs they had (CarStage.MapButton): crosshair, range, wind,
        // rain - as large as the car bar's microphone; zoom with two fingers (21.11.2026: "braucht kein Plus und Minus")
        Column(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    LegendRow({ androidx.compose.foundation.Image(windImages[k], null, Modifier.size(20.dp)) }, label)
                }
            }
            if (rainOn) Legend(stringResource(R.string.dash_map_rain)) {
                val cols = if (night) RAIN_COLS_NIGHT else if (windOn && windSeen) RAIN_COLS_WIND else RAIN_COLS
                listOf(R.string.dash_rain_light, R.string.dash_rain_moderate, R.string.dash_rain_heavy).forEachIndexed { g, id ->
                    LegendRow({ Box(Modifier.size(18.dp).clip(RoundedCornerShape(3.dp)).background(cols[g])) }, stringResource(id))
                }
                Text(rainAt?.let { stringResource(R.string.dash_rain_now, it) } ?: stringResource(R.string.dash_rain_waiting), color = Color(0xFFB1BAC4), fontSize = 13.sp)
                rainSrc?.let { Text(stringResource(R.string.dash_rain_source, it), color = Color(0xFF8B949E), fontSize = 12.sp) }
            }
        }
        // the map data's credit, as on Mate Navi's map (ODbL)
        Text(net.prototux.aare.ui.car.navi.OSM_CREDIT, color = Color(0xFF57606A), fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 6.dp))
        ferryNote?.let { id ->
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xE6161B22))
                .padding(horizontal = 22.dp, vertical = 14.dp)) {
                Text(stringResource(id), color = Color.White, fontSize = 18.sp)
            }
        }
        picked?.let { p ->
            LaunchedEffect(p) { delay(8_000); if (picked == p) picked = null }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xE6161B22))
                .clickable { picked = null; go("%.6f,%.6f".format(java.util.Locale.ROOT, p.latitude, p.longitude)) }.padding(horizontal = 22.dp, vertical = 14.dp)) {
                Text(stringResource(R.string.dash_pick_navigate), color = Color.White, fontSize = 20.sp)
            }
        }
    }
}

private enum class Sign { CENTER, RANGE, WIND, RAIN, FERRY }

/** IDmate's rain sources by the names their terms ask for. */
private val SOURCES = mapOf("dwd" to "Deutscher Wetterdienst", "rainviewer" to "RainViewer")

/** "2026-11-21T11:15:00Z" as the device's clock time, "11:15"; null when it is no time. */
private fun clock(iso: String): String? = runCatching {
    java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()).toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
}.getOrNull()

/**
 * A map button as the stage had them (CarStage.MapButton): a glass plate with a rounded edge, the sign drawn, teal when
 * on - 56 dp, the size of the car bar's microphone (21.11.2026: "sonst wird es im Fahrzeug schwierig").
 */
@Composable
private fun SignButton(sign: Sign, on: Boolean, label: String, onClick: () -> Unit) {
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(Color(if (on) 0xF20E3B36 else 0xE6161B22))
        .border(1.2.dp, Color(if (on) 0xFF1DE9B6 else 0xFF57606A), RoundedCornerShape(10.dp))
        .clickable(onClickLabel = label, onClick = onClick)) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val u = size.width / 52f; val cx = size.width / 2; val cy = size.height / 2
            val col = Color(if (on) 0xFF1DE9B6 else 0xFFF0F6FC)
            val st = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.6f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            fun line(x0: Float, y0: Float, x1: Float, y1: Float) = drawLine(col, androidx.compose.ui.geometry.Offset(cx + x0 * u, cy + y0 * u), androidx.compose.ui.geometry.Offset(cx + x1 * u, cy + y1 * u), 2.6f * u, androidx.compose.ui.graphics.StrokeCap.Round)
            fun arc(l: Float, t: Float, r: Float, b: Float, start: Float, sweep: Float) = drawArc(col, start, sweep, false,
                androidx.compose.ui.geometry.Offset(cx + l * u, cy + t * u), androidx.compose.ui.geometry.Size((r - l) * u, (b - t) * u), style = st)
            when (sign) {
                Sign.CENTER -> {      // crosshair
                    drawCircle(col, 8 * u, androidx.compose.ui.geometry.Offset(cx, cy), style = st)
                    line(0f, -15f, 0f, -10f); line(0f, 10f, 0f, 15f); line(-15f, 0f, -10f, 0f); line(10f, 0f, 15f, 0f)
                    drawCircle(col, 2.6f * u, androidx.compose.ui.geometry.Offset(cx, cy))
                }
                Sign.RANGE -> {       // dashed circle round a point
                    drawCircle(col, 13 * u, androidx.compose.ui.geometry.Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(2.6f * u,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5 * u, 5 * u))))
                    drawCircle(col, 3.2f * u, androidx.compose.ui.geometry.Offset(cx, cy))
                }
                Sign.WIND -> {        // three lines, the middle one an arrow
                    line(-12f, -7f, 3f, -7f); line(-12f, 0f, 12f, 0f); line(12f, 0f, 7f, -4.5f); line(12f, 0f, 7f, 4.5f); line(-12f, 7f, 0f, 7f)
                }
                Sign.RAIN -> {        // a cloud, three slanting drops below
                    arc(-12f, -12f, -2f, -2f, 90f, 180f); arc(-6f, -16f, 8f, -2f, 180f, 180f); arc(3f, -11f, 13f, -1f, 270f, 180f)
                    line(-7f, -2f, 8f, -2f); line(-6f, 4f, -8f, 9f); line(0f, 4f, -2f, 9f); line(6f, 4f, 4f, 9f)
                }
                Sign.FERRY -> {       // IDmate's ship: hull, cabin, mast, a wave below
                    line(-15f, 1f, 15f, 1f); line(-15f, 1f, -12f, 8f); line(15f, 1f, 11f, 8f)
                    line(-8f, 1f, -8f, -6f); line(-8f, -6f, 8f, -6f); line(8f, -6f, 8f, 1f); line(0f, -6f, 0f, -13f)
                    arc(-17f, 10f, -9f, 16f, 180f, -180f); arc(-9f, 10f, -1f, 16f, 180f, 180f); arc(-1f, 10f, 7f, 16f, 180f, -180f); arc(7f, 10f, 15f, 16f, 180f, 180f)
                }
            }
        }
    }
}

@Composable
private fun Legend(title: String, rows: @Composable () -> Unit) {
    // a light edge: on the dark night map the dark plate would melt into it
    Column(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xE6161B22)).border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(10.dp))
        .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        rows()
    }
}

@Composable
private fun LegendRow(mark: @Composable () -> Unit, label: String) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        mark(); Text(label, color = Color(0xFFE6EDF3), fontSize = 14.sp)
    }
}

/** IDmate's wind arrow (windIcon): pointing up, the class's colour with a white edge; turned by the layer. */
private fun arrow(cls: Int, density: Float): ImageBitmap {
    val sz = (WIND_SIZE[cls] * density).toInt().coerceAtLeast(8)
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
