package net.prototux.aare.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import net.prototux.aare.dash.ServerLogbook
import net.prototux.aare.ui.car.navi.RegionPacks
import net.prototux.aare.ui.car.navi.mapStyle
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/**
 * The way a trip went, on Mate's own map (System L-110, 26.11.2026: "eine Map-Ansicht von A nach B … die Kartenansicht kommt
 * aus unserer App"): the base map as Mate Navi's - the region package on the device where it holds the trip, else the
 * map server - with the way from IDmate's server, its start (green) and its end (red), the whole way in view.
 */
@Composable
internal fun TripRouteMap(route: ServerLogbook.Route, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    net.prototux.aare.ui.car.Mate.init(ctx)
    // the map server's token on MapLibre's downloads (System I-140, 26.11.2026: "die Map im Fahrtenbuch irgendwie verpixelt"):
    // in the phone app only Mate Navi had set it up - without it the server refused the fine tiles and coarse ones were
    // stretched over the map
    remember { net.prototux.aare.ui.car.navi.NaviServer.install(ctx) }
    val dark = isSystemInDarkTheme()
    val box = route.box
    val mid = box?.let { Position(longitude = (it[0] + it[2]) / 2, latitude = (it[1] + it[3]) / 2) } ?: Position(longitude = 10.45, latitude = 51.16)
    val pack = remember(route.id) { RegionPacks.mapFor(ctx, mid.longitude, mid.latitude)?.takeIf { it.covers(mid.longitude, mid.latitude) } }
    val style = remember(dark, pack?.file) { BaseStyle.Json(mapStyle(ctx, dark, pack)) }
    val camera = rememberCameraState(CameraPosition(target = mid, zoom = if (box == null) 5.0 else 11.0))
    LaunchedEffect(route.id) {
        val b = box ?: return@LaunchedEffect
        // a way of a few metres (or only its ends at one place): the place close up, else the whole way
        if (b[2] - b[0] < 0.002 && b[3] - b[1] < 0.002) runCatching { camera.animateTo(CameraPosition(target = mid, zoom = 15.0)) }
        else runCatching { camera.animateTo(BoundingBox(b[0], b[1], b[2], b[3]), 0.0, 0.0, PaddingValues(28.dp)) }
    }
    // drawn as a texture: part of the page (rounded corners, scrolling, the dialog) instead of a surface of its own beneath it
    MaplibreMap(modifier = modifier, baseStyle = style, cameraState = camera,
        options = remember { MapOptions(renderOptions = RenderOptions.Standard.copy(renderMode = RenderOptions.RenderMode.TextureView),
            ornamentOptions = OrnamentOptions.AllDisabled) }) {
        val line = rememberGeoJsonSource(remember(route.id) { GeoJsonData.JsonString(route.lineJson()) })
        LineLayer(id = "trip-way-halo", source = line, color = const(if (dark) Color(0xFF0D1117) else Color.White), width = const(7.dp),
            cap = const(LineCap.Round), join = const(LineJoin.Round))
        LineLayer(id = "trip-way", source = line, color = const(if (dark) WAY_NIGHT else WAY), width = const(4.dp),
            cap = const(LineCap.Round), join = const(LineJoin.Round))
        CircleLayer(id = "trip-start", source = rememberGeoJsonSource(remember(route.id) { GeoJsonData.JsonString(route.startJson()) }),
            color = const(START), radius = const(7.dp), strokeColor = const(Color.White), strokeWidth = const(2.dp))
        CircleLayer(id = "trip-end", source = rememberGeoJsonSource(remember(route.id) { GeoJsonData.JsonString(route.endJson()) }),
            color = const(END), radius = const(7.dp), strokeColor = const(Color.White), strokeWidth = const(2.dp))
    }
}

private val WAY = Color(0xFF0D5FD6)
private val WAY_NIGHT = Color(0xFF58A6FF)
private val START = Color(0xFF2DA44E)
private val END = Color(0xFFCF222E)
