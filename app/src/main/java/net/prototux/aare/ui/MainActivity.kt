package net.prototux.aare.ui

import net.prototux.aare.audio.CarSound.Kind as SoundKind
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.rounded.OfflinePin
import kotlinx.coroutines.Dispatchers
import androidx.compose.material.icons.rounded.Map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.prototux.aare.DiagLog
import net.prototux.aare.R
import net.prototux.aare.SessionAapLog
import net.prototux.aare.Settings
import net.prototux.aare.UpdateChecker
import net.prototux.aare.audio.CaptureMethod
import net.prototux.aare.audio.CaptureMethods
import net.prototux.aare.car.DrivingMode
import net.prototux.aare.car.PhoneLocationFeed
import net.prototux.aare.cert.CertificateDownload
import net.prototux.aare.cert.CertificateStore
import net.prototux.aare.host.AndroidAutoApps
import net.prototux.aare.host.AssistantApp
import net.prototux.aare.host.Assistants
import net.prototux.aare.host.CarAppCatalog
import net.prototux.aare.host.CarAppInfo
import net.prototux.aare.host.CarRuntime
import net.prototux.aare.host.PhoneApp
import net.prototux.aare.host.UnsupportedCarApp
import net.prototux.aare.mirror.MirrorTouchService
import androidx.core.graphics.drawable.toBitmap
import net.prototux.aare.launch.AppLauncher
import net.prototux.aare.car.CarData
import net.prototux.aare.service.ProjectionService
import net.prototux.aare.ui.car.WALLPAPER_NAMES
import net.prototux.aare.ui.theme.PhoneTheme
import rikka.shizuku.Shizuku
import java.text.DateFormat
import kotlin.math.roundToInt

/** The phone side: what's connected, what's set up, and the settings. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyDebugExtras(intent)
        setContent { PhoneTheme(dark = isSystemInDarkTheme()) { Surface(Modifier.fillMaxSize()) { Home() } } }
    }

    // singleTask: when AARE is already open, ./headunit.sh's intent arrives here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (applyDebugExtras(intent)) {
            ProjectionService.startIdleIfEnabled(this)
            recreate() // the settings screen shows the new state
        }
    }

    /** Debug builds only: lets ./headunit.sh switch the head unit server on over adb. Returns true if applied. */
    private fun applyDebugExtras(intent: Intent?): Boolean {
        if (!net.prototux.aare.BuildConfig.DEBUG || intent?.hasExtra("hu_server") != true) return false
        Settings(this).headUnitServer = intent.getBooleanExtra("hu_server", false)
        return true
    }
}

/** The settings pages, Android Auto style: one per topic, opened from the home page. */
private enum class Page(val titleRes: Int, val subtitleRes: Int, val icon: ImageVector, val mate: Boolean = false) {
    // Mate: the areas of the car screen and what is chosen for them. First on the home page, under their own heading.
    // the user's own places for the navigation (O169): categories with their addresses
    PLACES(R.string.mate_panel_places, R.string.mate_panel_places_sub, androidx.compose.material.icons.Icons.Rounded.Navigation, mate = true),
    // route settings and charging on the way (O205): what is avoided, the charge levels, the charging networks
    ROUTING(R.string.mate_panel_routing, R.string.mate_panel_routing_sub, Icons.Rounded.EvStation, mate = true),
    DASHBOARD(R.string.mate_page_dashboard, R.string.mate_page_dashboard_sub, Icons.Rounded.Dashboard, mate = true),
    NAVI(R.string.mate_page_navi, R.string.mate_page_navi_sub, androidx.compose.material.icons.Icons.Rounded.Navigation, mate = true),
    MUSIC(R.string.mate_page_music, R.string.mate_page_music_sub, androidx.compose.material.icons.Icons.Rounded.MusicNote, mate = true),
    SOUND(R.string.mate_page_sound, R.string.mate_page_sound_sub, Icons.Rounded.GraphicEq, mate = true),
    MESSAGES(R.string.msg_settings_title, R.string.msg_page_subtitle, Icons.Rounded.Forum, mate = true),
    // every voice in one place (17.11.2026: "Ich finde das Menü ständig nicht, weil wir drei Sprachmenüs haben und dieses
    // heißt leider nicht so" - the voices were on the Messenger page): Mate's voice, the phone's, and for all apps
    VOICES(R.string.mate_page_voices, R.string.mate_page_voices_sub, Icons.Rounded.RecordVoiceOver, mate = true),
    SPEECH(R.string.mate_page_speech, R.string.mate_page_speech_sub, Icons.Rounded.KeyboardVoice, mate = true),
    VIEWS(R.string.mate_page_views, R.string.mate_page_views_sub, Icons.Rounded.Bookmarks, mate = true),
    BAR(R.string.mate_page_bar, R.string.mate_page_bar_sub, Icons.Rounded.Tune, mate = true),
    // The system underneath (AARE): connection, sound, which apps show.
    CAR_SCREEN(R.string.setup_page_car_screen_title, R.string.setup_page_car_screen_subtitle, Icons.Rounded.Dashboard),
    APPS(R.string.setup_page_apps_title, R.string.setup_page_apps_subtitle, Icons.Rounded.Apps),
    ASSISTANT(R.string.assistant_settings_title, R.string.assistant_page_subtitle, Icons.Rounded.Mic),
    AUDIO(R.string.audio_settings_title, R.string.audio_page_subtitle, Icons.AutoMirrored.Rounded.VolumeUp),
    // the map server for Mate Navi: its Tailscale address and the gate's token (wish of 16.11.2026)
    MAP_SERVER(R.string.mapserver_title, R.string.mapserver_sub, androidx.compose.material.icons.Icons.Rounded.Map),
    // the map server's region packages for driving without the net (server handover 16.11.2026)
    REGIONS(R.string.regions_title, R.string.regions_sub, Icons.Rounded.OfflinePin),
    CONNECTION(R.string.setup_page_connection_title, R.string.setup_page_connection_subtitle, Icons.Rounded.Bluetooth),
    CAR_DATA(R.string.setup_page_car_data_title, R.string.setup_page_car_data_subtitle, Icons.Rounded.DirectionsCar),
    ADVANCED(R.string.setup_page_advanced_title, R.string.setup_page_advanced_subtitle, Icons.Rounded.Tune),
    // all settings into one file and back (17.11.2026: the first release comes with a new key - Mate installed afresh)
    TRANSFER(R.string.transfer_title, R.string.transfer_sub, Icons.Rounded.Bookmarks),
    // 18.11.2026 ("Untermenüs wie Musik sollen Audio heißen, darunter Musik und Klang; Spracherkennung ins Menü mit TTS"):
    // two pages of tiles on the home page, each opening its own pages
    HUB_AUDIO(R.string.mate_hub_audio, R.string.mate_hub_audio_sub, Icons.Rounded.GraphicEq, mate = true),
    HUB_SPEECH(R.string.mate_hub_speech, R.string.mate_hub_speech_sub, Icons.Rounded.RecordVoiceOver, mate = true),
}

/** The pages under a page of tiles: back from one goes to its tiles. */
private val HUB_OF = mapOf(Page.MUSIC to Page.HUB_AUDIO, Page.SOUND to Page.HUB_AUDIO,
    Page.VOICES to Page.HUB_SPEECH, Page.SPEECH to Page.HUB_SPEECH)

@Composable
private fun Home() {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val certs = remember { CertificateStore(ctx) }
    val state by ProjectionService.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    // the settings of Mate and the system behind one panel (O169); a page opened from there goes back to that list
    var inSettings by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { ProjectionService.startIdleIfEnabled(ctx); ProjectionService.probeUsb(ctx); ProjectionService.probeWireless(ctx); UpdateChecker.refresh(ctx) }
    // Re-check permissions and capture methods whenever the user comes back to the app.
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    // no back arrows on the pages (18.11.2026): the system's back goes up one level - a page to its tiles or the list
    BackHandler(enabled = page != null || inSettings) { if (page != null) page = HUB_OF[page] else inSettings = false }

    AnimatedContent(page, label = "page", transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) }) { p ->
        if (p == null) {
            LazyColumn(
                Modifier.fillMaxSize().systemBarsPadding(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item { Header() }
                item { FirstStartCard() }
                item { UpdateBanner() }
                item { RecorderCard() }
                // connected (or a car to trust): the card at the top; not connected only a pill at the bottom (18.11.2026)
                if (state.connected || state.pendingTrust != null) item { StatusCard(state) }
                item { Setup(state, refresh, { refresh++ }) { page = it } }
                if (!inSettings) {
                    // the home page: panels for what the user makes their own, one panel for all settings (O169)
                    item { HomePanels(connected = state.connected, open = { page = it }, openSettings = { inSettings = true }) }
                } else {
                    item { Text(stringResource(R.string.mate_panel_settings), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp)) }
                    // Two groups: what Mate shows in the car and what is chosen for it - then the system underneath.
                    listOf(R.string.mate_group_mate to true, R.string.mate_group_system to false).forEach { (title, mate) ->
                        item {
                            Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp, bottom = 0.dp))
                        }
                        // smaller tiles than the home page's (18.11.2026: "Einstellungen wird auch in Kacheln dargestellt, aber kleinere")
                        item { SettingsTiles(Page.entries.filter { it.mate == mate && it !in PANEL_PAGES && it !in HUB_OF && !it.name.startsWith("HUB_") }) { page = it } }
                    }
                }
                // the sound to the car with the screen off (19.11.2026, crackle in standby): Mate out of the battery optimisation
                item { BatteryOptimisationCard() }
                if (!state.connected && state.pendingTrust == null) item { NotConnectedPill(state) }
                item { About() }
            }
        } else {
            // Navi L-005: the routing page opens with the visited chargers while charges wait to be looked at (fixed per opening)
            val visitsFirst = remember(p) { p == Page.ROUTING && runCatching { net.prototux.aare.ui.car.navi.ChargerVisits.freshCount(ctx) > 0 }.getOrDefault(false) }
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 8.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(p.titleRes), style = MaterialTheme.typography.titleLarge)
                }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (p) {
                        Page.CAR_SCREEN -> item { CarScreenSettings(settings, refresh) }
                        Page.PLACES -> item { PlacesPage() }
                        Page.ROUTING -> {
                            if (visitsFirst) item { VisitedChargers() }
                            // O181: what Mate Navi's routes avoid (Valhalla's costing options), read when a route is planned
                            item { Section(stringResource(R.string.navi_avoid_title)) {
                                var ferries by remember { mutableStateOf(settings.avoidFerries) }
                                var motorways by remember { mutableStateOf(settings.avoidMotorways) }
                                var tolls by remember { mutableStateOf(settings.avoidTolls) }
                                var borders by remember { mutableStateOf(settings.avoidBorders) }
                                Toggle(stringResource(R.string.navi_avoid_ferries), stringResource(R.string.navi_avoid_ferries_sub), ferries) { ferries = it; settings.avoidFerries = it }
                                Toggle(stringResource(R.string.navi_avoid_motorways), stringResource(R.string.navi_avoid_motorways_sub), motorways) { motorways = it; settings.avoidMotorways = it }
                                Toggle(stringResource(R.string.navi_avoid_tolls), stringResource(R.string.navi_avoid_tolls_sub), tolls) { tolls = it; settings.avoidTolls = it }
                                Toggle(stringResource(R.string.navi_avoid_borders), stringResource(R.string.navi_avoid_borders_sub), borders) { borders = it; settings.avoidBorders = it }
                            } }
                            // O205: the charge levels of the charging plan
                            item { Section(stringResource(R.string.routing_charge_title)) {
                                var atCharger by remember { mutableStateOf(settings.chargeMinAtCharger.toString()) }
                                var atDest by remember { mutableStateOf(settings.chargeMinAtDestination.toString()) }
                                var atHome by remember { mutableStateOf(settings.chargeMinAtHome.toString()) }
                                var max by remember { mutableStateOf(settings.chargeMaxLevel.toString()) }
                                val low = listOf(5, 10, 15, 20).map { "$it" to "$it %" }
                                Choices(stringResource(R.string.routing_min_charger), low, atCharger) { atCharger = it; settings.chargeMinAtCharger = it.toInt() }
                                Spacer(Modifier.height(12.dp))
                                Choices(stringResource(R.string.routing_min_destination), listOf(5, 10, 15, 20, 30).map { "$it" to "$it %" }, atDest) { atDest = it; settings.chargeMinAtDestination = it.toInt() }
                                Spacer(Modifier.height(12.dp))
                                Choices(stringResource(R.string.routing_min_home), low, atHome) { atHome = it; settings.chargeMinAtHome = it.toInt() }
                                Text(stringResource(R.string.routing_min_home_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(12.dp))
                                Choices(stringResource(R.string.routing_max_level), listOf(70, 80, 90, 100).map { "$it" to "$it %" }, max) { max = it; settings.chargeMaxLevel = it.toInt() }
                                Text(stringResource(R.string.routing_max_level_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } }
                            // O226: the chargers charged at, to rate - the planner prefers the well rated among the allowed ones
                            // (first on the page while charges wait to be looked at: the badge led here, Navi L-005)
                            if (!visitsFirst) item { VisitedChargers() }
                            // O205/O213: the networks to charge at - only these (others only where they do not reach) or preferred
                            item { Section(stringResource(R.string.routing_networks_title)) {
                                var chosen by remember { mutableStateOf(settings.chargeNetworks) }
                                Text(stringResource(R.string.routing_networks_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(10.dp))
                                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    net.prototux.aare.ui.car.navi.ChargeNetworks.ALL.forEach { n ->
                                        val on = n.id in chosen
                                        // O210: the network's logo in the chip, on its light plate
                                        val logo = net.prototux.aare.ui.car.navi.NetworkLogos.of(n.id)
                                        androidx.compose.material3.FilterChip(selected = on, label = { Text(n.name) },
                                            leadingIcon = if (logo == null) null else { { androidx.compose.foundation.Image(
                                                androidx.compose.ui.res.painterResource(logo), null,
                                                Modifier.height(20.dp).width(36.dp).clip(RoundedCornerShape(4.dp))
                                                    .background(androidx.compose.ui.res.colorResource(R.color.mate_logo_plate)).padding(2.dp)) } },
                                            // chosen ones keep a tick: the logo took the place of the chip's own (16.11.2026)
                                            trailingIcon = if (on) { { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Check, null,
                                                Modifier.size(18.dp)) } } else null,
                                            onClick = {
                                            chosen = if (on) chosen - n.id else chosen + n.id; settings.chargeNetworks = chosen })
                                    }
                                }
                                // O213: only these, or preferred
                                var only by remember { mutableStateOf(settings.chargeNetworksOnly) }
                                Spacer(Modifier.height(12.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                                        Text(stringResource(R.string.routing_networks_only), style = MaterialTheme.typography.bodyLarge)
                                        Text(stringResource(R.string.routing_networks_only_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    androidx.compose.material3.Switch(only, { only = it; settings.chargeNetworksOnly = it }, enabled = chosen.isNotEmpty())
                                }
                            } }
                        }
                        Page.MAP_SERVER -> item { MapServerPage(settings) }
                        Page.REGIONS -> item { RegionsPage() }
                        Page.TRANSFER -> item { TransferPage() }
                        Page.DASHBOARD -> {
                            item { CombustionCard(settings) }
                            item { DashboardCard(refresh) }
                            item { TileCacheCard() }
                        }
                        Page.NAVI -> {
                            item { NaviChoiceCard() }
                            item { Section(stringResource(R.string.setup_section_look)) { SizeChoice(settings); Spacer(Modifier.height(12.dp)); AppTrimChoice(settings) } }
                            item { Section(stringResource(R.string.setup_section_ev_route)) { EvRouteChoice(settings) } }
                            // O194: the optional speed limit warning - off by default, how far over, how often, tone, volume
                            item { Section(stringResource(R.string.speed_warn_title)) {
                                var on by remember { mutableStateOf(settings.speedWarn) }
                                Toggle(stringResource(R.string.speed_warn_on), stringResource(R.string.speed_warn_on_sub), on) { on = it; settings.speedWarn = it }
                                if (on) {
                                    var over by remember { mutableStateOf(settings.speedWarnOver.toString()) }
                                    var times by remember { mutableStateOf(settings.speedWarnTimes.toString()) }
                                    var tone by remember { mutableStateOf(settings.speedWarnTone) }
                                    var vol by remember { mutableStateOf(settings.speedWarnVolume.toString()) }
                                    fun sample() = net.prototux.aare.ui.car.navi.NaviSpeedAlert.play(net.prototux.aare.ui.car.navi.NaviSpeedAlert.tone(tone), vol.toInt() / 100f, times.toInt())
                                    Spacer(Modifier.height(12.dp))
                                    Choices(stringResource(R.string.speed_warn_over), listOf(3, 5, 10, 15, 20).map { "$it" to "+$it" }, over) { over = it; settings.speedWarnOver = it.toInt() }
                                    Spacer(Modifier.height(12.dp))
                                    Choices(stringResource(R.string.speed_warn_times), listOf("1", "2", "3").map { it to it + "×" }, times) { times = it; settings.speedWarnTimes = it.toInt(); sample() }
                                    Spacer(Modifier.height(12.dp))
                                    Choices(stringResource(R.string.speed_warn_tone), listOf("gong" to stringResource(R.string.speed_warn_tone_gong), "beep" to stringResource(R.string.speed_warn_tone_beep),
                                        "double" to stringResource(R.string.speed_warn_tone_double), "bell" to stringResource(R.string.speed_warn_tone_bell)), tone) { tone = it; settings.speedWarnTone = it; sample() }
                                    Spacer(Modifier.height(12.dp))
                                    Choices(stringResource(R.string.speed_warn_volume), listOf("25", "50", "70", "100").map { it to "$it %" }, vol) { vol = it; settings.speedWarnVolume = it.toInt(); sample() }
                                    Spacer(Modifier.height(6.dp))
                                    Text(stringResource(R.string.speed_warn_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } }
                            // O193: speed cameras - off by default, with the legal note
                            item { Section(stringResource(R.string.camera_warn_title)) {
                                var cams by remember { mutableStateOf(settings.cameraWarn) }
                                Toggle(stringResource(R.string.camera_warn_on), stringResource(R.string.camera_warn_on_sub), cams) { cams = it; settings.cameraWarn = it }
                                Spacer(Modifier.height(6.dp))
                                Text(stringResource(R.string.camera_warn_law), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            } }
                            // O174: Mate Navi's frame rate, an even 30 by default, the video's rate for tests
                            item { Section(stringResource(R.string.mate_navi_title)) {
                                var fps by remember { mutableStateOf(settings.naviFps) }
                                Choices(stringResource(R.string.navi_fps_title), listOf("30" to stringResource(R.string.navi_fps_30), "video" to stringResource(R.string.navi_fps_video)), fps) {
                                    fps = it; settings.naviFps = it }
                                Text(stringResource(R.string.navi_fps_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } }
                        }
                        Page.HUB_AUDIO -> {
                            item { HubTiles(listOf(
                                HomePanel(PanelArt.MUSIC, R.string.mate_page_music, R.string.mate_page_music_sub, Page.MUSIC),
                                HomePanel(PanelArt.SOUND, R.string.mate_page_sound, R.string.mate_page_sound_sub, Page.SOUND))) { page = it } }
                            // the ring in the car while a call comes in (18.11.2026: "verschiedene Klingeltöne, die wir in der App einstellen können")
                            item { RingtoneSection(settings) }
                        }
                        Page.HUB_SPEECH -> item { HubTiles(listOf(
                            HomePanel(PanelArt.VOICES, R.string.mate_page_voices, R.string.mate_page_voices_sub, Page.VOICES),
                            HomePanel(PanelArt.LISTEN, R.string.mate_page_speech, R.string.mate_page_speech_sub, Page.SPEECH))) { page = it } }
                        Page.MUSIC -> {
                            item { MusicCard(refresh) }
                            item { MusicOfflineCard(refresh) }
                            item { MusicChoiceCard() }
                        }
                        Page.BAR -> {
                            item { BarSettings(settings) }
                            item { DashTitleCard(settings) }
                        }
                        Page.SOUND -> item { SoundCard(settings) }
                        Page.VIEWS -> item { ViewsCard() }
                        Page.SPEECH -> item { SpeechCard() }
                        Page.APPS -> item { AppsCard(settings) }
                        Page.ASSISTANT -> item { AssistantCard(settings) }
                        Page.MESSAGES -> item { MessageSettings(settings, refresh) }
                        Page.VOICES -> item { VoiceCard(settings) }
                        Page.AUDIO -> item { AudioSettings(settings, state, refresh) { refresh++ } }
                        Page.CONNECTION -> item { ConnectionSettings(settings, refresh) }
                        Page.CAR_DATA -> item { CarDataCard() }
                        Page.ADVANCED -> {
                            item { HeadUnitServerSettings(settings) }
                            item { CertificatesCard(certs, state) }
                            item { VehicleOverridesCard() }
                            item { LogCard() }
                        }
                    }
                }
            }
        }
    }
}

/** The pages that are panels on the home page (not in the settings list). */
// 18.11.2026 ("die Musik-App in der App eine extra Menü-Kachel; man könnte überlegen, ob eine andere dafür zurück in die
// Einstellungen verschwindet"): Musik took the place of Ansichten, which is set once and rarely after - now under Einstellungen
// 18.11.2026: Audio (Musik, Klang) and Sprache (Stimmen, Spracherkennung) as pages of tiles
private val PANEL_PAGES = setOf(Page.PLACES, Page.ROUTING, Page.HUB_AUDIO, Page.HUB_SPEECH)

private class HomePanel(val art: PanelArt, val title: Int, val subtitle: Int, val page: Page?, val accent: Boolean = false, val action: (() -> Unit)? = null,
    /** A count shown at the corner (Navi L-005: charges to look at); 0 = none. */
    val badge: Int = 0)

/** The home page's panels: a plain line drawing, a title and a line under it; two or three in a row. */
@Composable
private fun HomePanels(connected: Boolean, open: (Page) -> Unit, openSettings: () -> Unit) {
    val ctx = LocalContext.current
    val panels = listOfNotNull(
        // the car screen on the phone, while no car is connected: first, in Mate's colour (18.11.2026: "Autobildschirm-
        // Vorschau wird zu Dashboard-starten-Kachel, in Türkis, negativ zu den anderen, oben links die erste Kachel")
        if (!connected) HomePanel(PanelArt.DASH, R.string.mate_panel_dash_start, R.string.mate_panel_dash_start_sub, null, accent = true,
            action = { ctx.startActivity(Intent(ctx, CarPreviewActivity::class.java)) }) else null,
        HomePanel(PanelArt.PLACES, R.string.mate_panel_places, R.string.mate_panel_places_sub, Page.PLACES),
        HomePanel(PanelArt.ROUTING, R.string.mate_panel_routing, R.string.mate_panel_routing_sub, Page.ROUTING,
            badge = net.prototux.aare.ui.car.navi.ChargerVisits.changes.intValue.let { runCatching { net.prototux.aare.ui.car.navi.ChargerVisits.freshCount(ctx) }.getOrDefault(0) }),
        HomePanel(PanelArt.MUSIC, R.string.mate_hub_audio, R.string.mate_hub_audio_sub, Page.HUB_AUDIO),
        HomePanel(PanelArt.VOICES, R.string.mate_hub_speech, R.string.mate_hub_speech_sub, Page.HUB_SPEECH),
        HomePanel(PanelArt.SETTINGS, R.string.mate_panel_settings, R.string.mate_panel_settings_sub, null),
    )
    PanelGrid(panels) { p -> when { p.action != null -> p.action.invoke(); p.page != null -> open(p.page); else -> openSettings() } }
}

/** The tiles of Audio or Sprache: as large as the home page's. */
@Composable
private fun HubTiles(panels: List<HomePanel>, open: (Page) -> Unit) = PanelGrid(panels) { p -> p.page?.let(open) }

@Composable
private fun PanelGrid(panels: List<HomePanel>, onClick: (HomePanel) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth > 840.dp) 3 else 2
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            panels.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Max)) {
                    row.forEach { p -> PanelCard(p, Modifier.weight(1f).then(Modifier.fillMaxSize())) { onClick(p) } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun PanelCard(p: HomePanel, modifier: Modifier, onClick: () -> Unit) {
    // the accent tile the other way round: Mate's colour as its ground, the drawing and words dark on it
    val line = if (p.accent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    val ground = if (p.accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer
    val text = if (p.accent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val sub = if (p.accent) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(ground).clickable(onClick = onClick)
        .padding(20.dp)) {
        Box(Modifier.fillMaxWidth()) {
            PanelArtwork(p.art, line, line.copy(alpha = 0.38f), Modifier.fillMaxWidth().height(96.dp))
            // Navi L-005: a small count, out of the way, that does not urge
            if (p.badge > 0) Text(p.badge.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 8.dp, vertical = 2.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(stringResource(p.title), style = MaterialTheme.typography.titleMedium, color = text)
        Text(stringResource(p.subtitle), style = MaterialTheme.typography.bodySmall, color = sub, maxLines = 2)
    }
}

/**
 * The first start (18.11.2026: "Beim Einrichten der App, wenn sie vorher nicht installiert war, erfragen, ob eine alte App-
 * Datenbank wiederhergestellt werden soll ... alles andere wie Stimmen und Spracherkennung müsste er beim Erststart nachladen,
 * und das angezeigt wird: es werden noch Pakete heruntergeladen, damit die App in ihren Grundfunktionen funktioniert"):
 * on a fresh install first the question for a backup (the ZIP of "Einstellungen übertragen"), then the base packages - the
 * speech recognition and the voice - loaded with their progress. The base packages card also shows when one is missing later.
 */
@Composable
private fun FirstStartCard() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("mate_first_start", android.content.Context.MODE_PRIVATE) }
    val fresh = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).let { it.firstInstallTime == it.lastUpdateTime } }.getOrDefault(false)
    }
    var restoreAsked by remember { mutableStateOf(prefs.getBoolean("restore_asked", !fresh)) }
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val load = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = ""
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { net.prototux.aare.MateTransfer.import(ctx, uri, {}) { w -> scope.launch { busy = w } } } }
            busy = null
            note = r.fold({ ctx.getString(R.string.transfer_loaded, it) }, { ctx.getString(R.string.first_restore_failed, it.message ?: it.javaClass.simpleName) })
            restoreAsked = true; prefs.edit().putBoolean("restore_asked", true).apply()
        }
    }
    // which car (18.11.2026: "beim Einrichten gleich fragen, ob man ein EV oder einen Verbrenner fährt - vielleicht Spaceship
    // oder Dinosaurier - mit dem Hinweis, dass sich Dashboard und Navigation daraufhin anpassen"), then the note on the
    // car's connection (an OBD2 adapter that lets the car sleep)
    var carAsked by remember { mutableStateOf(prefs.getBoolean("car_asked", !fresh)) }
    var obdShown by remember { mutableStateOf(prefs.getBoolean("obd_shown", !fresh)) }
    if (restoreAsked && !carAsked) {
        val st = remember { Settings(ctx) }
        Section(stringResource(R.string.first_car_title)) {
            Text(stringResource(R.string.first_car_hint), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(false to stringResource(R.string.first_car_ev), true to stringResource(R.string.first_car_ice)).forEach { (ice, label) ->
                    androidx.compose.material3.OutlinedButton(onClick = {
                        st.combustion = ice
                        if (ice) st.evModelSource = "off" else if (st.evModelSource == "off") st.evModelSource = "mate"
                        carAsked = true; prefs.edit().putBoolean("car_asked", true).apply()
                    }, modifier = Modifier.weight(1f).height(72.dp)) { Text(label, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.first_car_later), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    if (restoreAsked && carAsked && !obdShown) {
        Section(stringResource(R.string.first_obd_title)) {
            Text(stringResource(R.string.first_obd_hint), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.first_obd_warning), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.Button(onClick = { obdShown = true; prefs.edit().putBoolean("obd_shown", true).apply() }) {
                Text(stringResource(R.string.first_obd_ok))
            }
        }
        return
    }
    if (!restoreAsked) {
        Section(stringResource(R.string.first_restore_title)) {
            Text(stringResource(R.string.first_restore_hint), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            val b = busy
            if (b != null) {
                Text(stringResource(R.string.transfer_now, b), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(onClick = { load.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text(stringResource(R.string.first_restore_pick)) }
                androidx.compose.material3.OutlinedButton(onClick = { restoreAsked = true; prefs.edit().putBoolean("restore_asked", true).apply() }) {
                    Text(stringResource(R.string.first_restore_new))
                }
            }
        }
        return
    }
    // the base packages: Whisper base and the voice Thorsten. 18.11.2026 (asked: "nur im WLAN oder nach Rückfrage?" -
    // "Nach Rückfrage"): Mate asks before it loads them - it loaded them by itself at every start, over mobile data too,
    // and a voice deleted on purpose came back. "Nicht laden" is kept: no more asking, they can be loaded under Sprache.
    val progress = net.prototux.aare.speech.ModelDownload.progress
    val asrKey = net.prototux.aare.speech.LocalAsr.STANDARD
    val voiceKey = net.prototux.aare.speech.ModelDownload.VOICE
    val asrThere = asrKey in net.prototux.aare.speech.LocalAsr.installed(ctx) || progress[asrKey] == 1f
    val voice = net.prototux.aare.speech.Voices.byId("piper")!!
    // any voice of the device will do, not only Thorsten (18.11.2026: "ich habe eine Sprache installiert, das reicht mir
    // vollkommen aus")
    val voiceThere = net.prototux.aare.speech.Voices.ALL.any { runCatching { net.prototux.aare.speech.Voices.available(ctx, it) }.getOrDefault(false) } ||
        net.prototux.aare.speech.Voices.available(ctx, voice) || progress[voiceKey] == 1f
    var declined by remember { mutableStateOf(prefs.getStringSet("packages_declined", emptySet())?.toSet() ?: emptySet()) }
    fun load(key: String) { if (key == voiceKey) net.prototux.aare.speech.ModelDownload.startVoice(ctx) else net.prototux.aare.speech.ModelDownload.start(ctx, key) }
    val packages = listOf(Triple(stringResource(R.string.first_packages_asr), asrKey, asrThere), Triple(stringResource(R.string.first_packages_voice), voiceKey, voiceThere))
    val missing = packages.filter { !it.third }
    // to ask for: missing, not refused, not loading (or failed) in this run
    // asked only while setting up a fresh install (18.11.2026: "das soll er eigentlich nur beim Einrichten anzeigen und
    // nicht dauerhaft darauf hinweisen"); later the packages are loaded under Sprache
    val toAsk = if (!fresh) emptyList() else missing.filter { it.second !in declined && progress[it.second] == null }
    val running = missing.filter { progress[it.second] != null }
    if (toAsk.isEmpty() && running.isEmpty() && note == null) return
    Section(stringResource(if (running.isNotEmpty()) R.string.first_packages_title else if (toAsk.isNotEmpty()) R.string.first_packages_ask_title else R.string.transfer_title)) {
        note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(8.dp)) }
        if (toAsk.isNotEmpty()) {
            Text(stringResource(R.string.first_packages_ask), style = MaterialTheme.typography.bodyMedium)
            toAsk.forEach { (label, _, _) -> Text("• $label", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(onClick = { toAsk.forEach { load(it.second) } }) { Text(stringResource(R.string.first_packages_load)) }
                androidx.compose.material3.OutlinedButton(onClick = {
                    declined = declined + toAsk.map { it.second }
                    prefs.edit().putStringSet("packages_declined", declined).apply()
                }) { Text(stringResource(R.string.first_packages_skip)) }
            }
        }
        running.forEach { (label, key, _) ->
            val p = progress[key]
            Spacer(Modifier.height(10.dp))
            Text(label + " – " + when { p == -1f -> stringResource(R.string.speech_model_failed); p != null -> "${(p * 100).toInt()} %"; else -> stringResource(R.string.first_packages_waiting) },
                style = MaterialTheme.typography.bodyLarge)
            if (p != null && p >= 0f) androidx.compose.material3.LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            if (p == -1f) androidx.compose.material3.TextButton(onClick = { load(key) }) { Text(stringResource(R.string.first_packages_retry)) }
        }
        if (toAsk.isEmpty() && running.isEmpty()) androidx.compose.material3.TextButton(onClick = { note = null }) { Text(stringResource(R.string.first_packages_ok)) }
    }
}

/** The settings as small tiles: the page's sign, its name and one line; three or four in a row. */
@Composable
private fun SettingsTiles(pages: List<Page>, open: (Page) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth > 840.dp) 4 else 3
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            pages.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Max)) {
                    row.forEach { pg ->
                        Column(Modifier.weight(1f).then(Modifier.fillMaxSize()).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable { open(pg) }.padding(14.dp)) {
                            Icon(pg.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(10.dp))
                            Text(stringResource(pg.titleRes), style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            Text(stringResource(pg.subtitleRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Not connected: one wide pill at the bottom of the page, the hint and an error if there was one (18.11.2026). */
@Composable
private fun NotConnectedPill(state: ProjectionService.State) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .padding(horizontal = 24.dp, vertical = 14.dp)) {
        Text(stringResource(R.string.notif_status_not_connected), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.setup_status_disconnected_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.lastError?.let { Text(stringResource(R.string.setup_status_last_error, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

/** The map server: Tailscale address and token, and a check that tries a tile through the gate. */
@Composable
private fun MapServerPage(settings: Settings) {
    val ctx = LocalContext.current
    var address by remember { mutableStateOf(settings.mapServer) }
    var token by remember { mutableStateOf(settings.mapToken) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.mapserver_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.material3.OutlinedTextField(address, { address = it; settings.mapServer = it; result = null }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.mapserver_address)) }, placeholder = { Text("http://nas.tailnet.ts.net:8090") }, singleLine = true)
        val rejected by net.prototux.aare.ui.car.navi.NaviServer.rejected
        androidx.compose.material3.OutlinedTextField(token, { token = it; settings.mapToken = it; result = null
                net.prototux.aare.ui.car.navi.NaviServer.rejected.value = false }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.mapserver_token)) }, singleLine = true, isError = rejected,
            supportingText = if (rejected) {{ Text(stringResource(R.string.mapserver_rejected)) }} else null,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        // pairing (handover v0.2): a one-time code from the gate's QR code or typed, traded for this device's own token
        var device by remember { mutableStateOf(settings.mapDevice) }
        var code by remember { mutableStateOf("") }
        fun pairWith(base: String, c: String) {
            result = ctx.getString(R.string.mapserver_pairing)
            scope.launch {
                when (val r = net.prototux.aare.ui.car.navi.NaviServer.pair(ctx, base, c)) {
                    is net.prototux.aare.ui.car.navi.NaviServer.PairResult.Paired -> {
                        address = settings.mapServer; token = settings.mapToken; device = r.device; code = ""
                        result = ctx.getString(R.string.mapserver_paired, r.device)
                    }
                    is net.prototux.aare.ui.car.navi.NaviServer.PairResult.Failed -> result = ctx.getString(r.message)
                }
            }
        }
        if (device.isNotBlank()) Text(stringResource(R.string.mapserver_device, device), style = MaterialTheme.typography.titleSmall)
        // the QR code: pairing ({"v":1,"u","c"}), or address and token as before (O173)
        val scan = androidx.activity.compose.rememberLauncherForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { r ->
            val text = r.contents ?: return@rememberLauncherForActivityResult
            net.prototux.aare.ui.car.navi.NaviServer.pairingFromQr(text)?.let { (base, c) ->
                address = base; settings.mapServer = base; pairWith(base, c); return@rememberLauncherForActivityResult }
            val got = net.prototux.aare.ui.car.navi.NaviServer.fromQr(text)
            if (got == null) { result = ctx.getString(R.string.mapserver_qr_unknown); return@rememberLauncherForActivityResult }
            got.first?.let { address = it; settings.mapServer = it }
            got.second?.let { token = it; settings.mapToken = it; net.prototux.aare.ui.car.navi.NaviServer.rejected.value = false }
            result = ctx.getString(R.string.mapserver_qr_taken)
        }
        androidx.compose.material3.Button(onClick = {
            scan.launch(com.journeyapps.barcodescanner.ScanOptions().setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                .setPrompt(ctx.getString(R.string.mapserver_qr_prompt)).setBeepEnabled(false).setOrientationLocked(false)
                .setCaptureActivity(net.prototux.aare.dash.ScanActivity::class.java))
        }) { Text(stringResource(R.string.mapserver_qr)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(code, { code = it.uppercase() }, Modifier.weight(1f),
                label = { Text(stringResource(R.string.mapserver_code)) }, placeholder = { Text("XXXX-XXXX-XXXX") }, singleLine = true)
            Spacer(Modifier.width(12.dp))
            val c = net.prototux.aare.ui.car.navi.NaviServer.normaliseCode(code)
            androidx.compose.material3.FilledTonalButton(enabled = c != null && address.isNotBlank(), onClick = { pairWith(address.trim().trimEnd('/'), c!!) }) {
                Text(stringResource(R.string.mapserver_pair)) }
        }
        androidx.compose.material3.FilledTonalButton(enabled = address.isNotBlank(), onClick = {
            result = ctx.getString(R.string.mapserver_checking)
            scope.launch {
                result = withContext(Dispatchers.IO) {
                    val url = net.prototux.aare.ui.car.navi.NaviServer.tiles(ctx).replace("{z}", "0").replace("{x}", "0").replace("{y}", "0")
                    runCatching {
                        net.prototux.aare.ui.car.navi.NaviServer.client(ctx).newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                            if (r.isSuccessful) ctx.getString(R.string.mapserver_ok, (r.body?.contentLength() ?: -1L).coerceAtLeast(0))
                            else if (r.code == 401) ctx.getString(R.string.mapserver_rejected) else ctx.getString(R.string.mapserver_http, r.code)
                        }
                    }.getOrElse { ctx.getString(R.string.mapserver_failed, it.message ?: it.javaClass.simpleName) }
                }
            }
        }) { Text(stringResource(R.string.mapserver_check)) }
        result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // the app's own sign (the launcher icon's two layers)
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(androidx.compose.ui.res.colorResource(R.color.mate_on_accent)), contentAlignment = Alignment.Center) {
            Image(androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground), null,
                Modifier.requiredSize(78.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.setup_tagline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/**
 * Test recording (O98): shown when a task has been left (files/pruef/aufgabe.txt) or a recording runs. The user
 * records what the task asks for on the car screen, with a photo after each step.
 */
@Composable
private fun RecorderCard() {
    val ctx = LocalContext.current
    var task by remember { mutableStateOf(net.prototux.aare.debug.Recorder.task(ctx)) }
    LaunchedEffect(Unit) { while (true) { task = net.prototux.aare.debug.Recorder.task(ctx); kotlinx.coroutines.delay(2000) } }
    val on = net.prototux.aare.debug.Recorder.on
    val t = task ?: if (on) "" else return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (on) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.4f)))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(if (on) R.string.rec_title_on else R.string.rec_title), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        if (t.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(t, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!on) androidx.compose.material3.Button(onClick = { net.prototux.aare.debug.Recorder.start(ctx) }) { Text(stringResource(R.string.rec_start)) }
            else {
                androidx.compose.material3.Button(onClick = { net.prototux.aare.debug.Recorder.photo() }) {
                    Text(stringResource(R.string.rec_photo, net.prototux.aare.debug.Recorder.photos + 1))
                }
                androidx.compose.material3.OutlinedButton(onClick = { net.prototux.aare.debug.Recorder.stop() }) { Text(stringResource(R.string.rec_stop)) }
            }
        }
        Text(stringResource(R.string.rec_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f), modifier = Modifier.padding(top = 8.dp))
    }
}

/** Combustion car (O99): the dashboard (data of electric cars) goes away, Mate keeps its basic areas. */
@Composable
private fun CombustionCard(settings: Settings) {
    var on by remember { mutableStateOf(settings.combustion) }
    Section(stringResource(R.string.combustion_section)) {
        Toggle(stringResource(R.string.combustion_title), stringResource(R.string.combustion_subtitle), on) { on = it; settings.combustion = it }
    }
}

/** A quiet warning when a newer release is on GitHub: tap to view it, or dismiss it. No popup. */
@Composable
private fun UpdateBanner() {
    val ctx = LocalContext.current
    val update by UpdateChecker.available.collectAsState()
    update?.let { u ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.tertiaryContainer)
                .clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.url))) }
                .padding(start = 18.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.SystemUpdate, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.update_available_title, u.version), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(stringResource(R.string.update_available_subtitle, net.prototux.aare.BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            IconButton(onClick = { UpdateChecker.dismiss(ctx) }) {
                Icon(Icons.Rounded.Close, stringResource(R.string.common_dismiss), tint = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}

@Composable
private fun StatusCard(state: ProjectionService.State) {
    val ctx = LocalContext.current
    val bg = if (state.connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(bg).padding(24.dp)) {
        Text(if (state.connected) state.status else stringResource(R.string.notif_status_not_connected), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        val certLabel = stringResource(R.string.setup_status_certificate, state.certificate)
        val disconnectedHint = stringResource(R.string.setup_status_disconnected_hint)
        val detail = when {
            state.connected -> listOf(state.transport, state.video, certLabel).filter { it.isNotBlank() }.joinToString("\n")
            else -> disconnectedHint
        }
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.lastError?.let {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.setup_status_last_error, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        // The same Trust as the notification's, for when notifications are off.
        state.pendingTrust?.let { p ->
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.notif_trust_reconnect_suffix, stringResource(R.string.notif_trust_warning)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            val trustLabel = stringResource(if (p.changed) R.string.setup_trust_new_cert else R.string.setup_trust_head_unit)
            Pill(stringResource(R.string.setup_trust_fingerprint_suffix, trustLabel, p.fingerprint.take(16)), null) { ProjectionService.trust(ctx, p.peerKey, p.fingerprint) }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.connected) Pill(stringResource(R.string.setup_disconnect), null) { ProjectionService.stop(ctx) }
        }
    }
}

@Composable
private fun Pill(label: String, icon: ImageVector?, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primary).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(label, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
    }
}

// ---------------------------------------------------------------- setup checklist

@Composable
private fun Setup(state: ProjectionService.State, refresh: Int, recheck: () -> Unit, open: (Page) -> Unit) {
    val ctx = LocalContext.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    val activity = ctx as? android.app.Activity
    val settings = remember { Settings(ctx) }
    fun rationale(p: String) = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, p)
    // The rationale of each permission just before the request, and the ones asked for in an earlier one.
    var rationaleBefore by remember { mutableStateOf(emptySet<String>()) }
    var askedBefore by remember { mutableStateOf(emptySet<String>()) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        recheck()
        // Denied twice, a permission is no longer asked for: the request answers at once and the row
        // would seem dead, so open AARE's system settings to grant it there.
        val blocked = activity != null && result.filterValues { !it }.keys.any {
            permissionBlocked(it in rationaleBefore, rationale(it), it in askedBefore)
        }
        if (blocked) ctx.startSafely(appDetails(ctx))
    }
    // Only the permissions this Android version has: a newer one reads as denied on older releases and
    // can't be requested, so setup would never finish. Before 12, Bluetooth and Wi-Fi scans need location.
    // Android 12+ ignores a request for fine location without coarse; precise stays required (wireless
    // before 13, car apps' location).
    val runtimePerms = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_COARSE_LOCATION); add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { add(Manifest.permission.BLUETOOTH_CONNECT); add(Manifest.permission.BLUETOOTH_SCAN) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { add(Manifest.permission.POST_NOTIFICATIONS); add(Manifest.permission.NEARBY_WIFI_DEVICES) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        addAll(listOf(Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALENDAR))
    }
    @Suppress("UNUSED_EXPRESSION") refresh
    val perms = runtimePerms.all(::granted)
    val notificationAccess = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    val audio = CaptureMethods.audioPolicyAvailable(ctx) || CaptureMethods.shizukuAvailable() || CaptureMethods.rootAvailable(ctx) || state.audioCapture
    if (perms && notificationAccess && audio) return

    Section(stringResource(R.string.setup_finish_title)) {
        Check(stringResource(R.string.setup_check_permissions_title), stringResource(R.string.setup_check_permissions_subtitle), perms) {
            rationaleBefore = runtimePerms.filter(::rationale).toSet()
            askedBefore = settings.requestedPermissions
            settings.requestedPermissions = askedBefore + runtimePerms
            permissions.launch(runtimePerms.toTypedArray())
        }
        Check(stringResource(R.string.setup_check_notification_access_title), stringResource(R.string.setup_check_notification_access_subtitle), notificationAccess) {
            ctx.startSafely(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS), appDetails(ctx))
        }
        Check(stringResource(R.string.audio_settings_title), stringResource(R.string.setup_check_audio_subtitle), audio) { open(Page.AUDIO) }
    }
}

@Composable
private fun Check(title: String, subtitle: String, done: Boolean, changeLabel: String? = null, onClick: () -> Unit) {
    // a done row stays tappable when it can be changed (changeLabel)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(enabled = !done || changeLabel != null, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!done) Text(stringResource(R.string.setup_action_set_up), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        else if (changeLabel != null) Text(changeLabel, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Whether a permission the request just denied can no longer be asked for (main setup row). Since Android 11,
 * backing out of the dialog also denies with no rationale, as before any request: so it counts as refused for
 * good only if the rationale went from shown to gone (refused a second time), or it was asked for in an earlier
 * request and still has none (blocked back then).
 */
internal fun permissionBlocked(rationaleBefore: Boolean, rationaleAfter: Boolean, askedBefore: Boolean): Boolean =
    !rationaleAfter && (rationaleBefore || askedBefore)

/** AARE's page in the system settings, where every permission and access can be granted by hand. */
/**
 * Says whether Google's Android Auto can take the car from Mate (test drive B11: both answered the
 * car, a red error screen, Android Auto to be pushed away twice). With a system that lets one choose
 * the app for the car (the Panel ROM: settings, "Android Auto") the row reads the choice; elsewhere it
 * can only say what to do, since Android tells no app who gets a USB accessory. Nothing without Android Auto.
 */
@Composable
private fun AutoRivalCheck() {
    val ctx = LocalContext.current
    val auto = "com.google.android.projection.gearhead"
    val installed = remember { runCatching { ctx.packageManager.getApplicationInfo(auto, 0).enabled }.getOrDefault(false) }
    if (!installed) return
    // Such a system also gives a head unit app the sound routing right; nothing else tells it apart.
    val chooser = CaptureMethods.audioPolicyAvailable(ctx)
    val chosen = remember { runCatching { android.provider.Settings.Global.getString(ctx.contentResolver, "panel_auto_default") }.getOrNull().orEmpty() }
    when {
        chooser && (chosen.isEmpty() || chosen == ctx.packageName) ->
            Check(stringResource(R.string.setup_check_rival_ok_title), stringResource(R.string.setup_check_rival_ok_subtitle), true) {}
        chooser ->
            Check(stringResource(R.string.setup_check_rival_title), stringResource(R.string.setup_check_rival_chosen_subtitle), false) {
                ctx.startSafely(Intent(AndroidSettings.ACTION_SETTINGS), appDetails(ctx))
            }
        else ->
            Check(stringResource(R.string.setup_check_rival_title), stringResource(R.string.setup_check_rival_subtitle), false) {
                ctx.startSafely(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", auto, null)), appDetails(ctx))
            }
    }
}

private fun appDetails(ctx: Context) =
    Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))

/**
 * Opens a settings screen or a link. Some phones lack the screen (Go builds and OEM ROMs drop some)
 * or have no browser: then [fallback] opens instead, or a short message, rather than a crash. No
 * resolveActivity() check first: package visibility hides browsers from it without a <queries> entry.
 */
private fun Context.startSafely(intent: Intent, fallback: Intent? = null) {
    val opened = listOfNotNull(intent, fallback).any { runCatching { startActivity(it) }.isSuccess }
    if (!opened) Toast.makeText(this, R.string.setup_nothing_to_open, Toast.LENGTH_SHORT).show()
}

// ---------------------------------------------------------------- settings

@Composable
private fun ConnectionSettings(settings: Settings, refresh: Int) {
    var wireless by remember { mutableStateOf(settings.wireless) }
    var checkUpdates by remember { mutableStateOf(settings.checkUpdates) }
    var fps60 by remember { mutableStateOf(settings.prefer60fps) }
    var maxH by remember { mutableIntStateOf(settings.maxVideoHeight) }
    var forceH264 by remember { mutableStateOf(settings.forceH264) }
    var resume by remember { mutableStateOf(settings.resumeMedia) }
    var cars by remember { mutableStateOf(settings.knownCars) }
    var carGps by remember { mutableStateOf(settings.carGpsForPhone) }
    var btAddr by remember { mutableStateOf(settings.phoneBluetoothAddress) }
    val ctx = LocalContext.current
    val pinned = remember { net.prototux.aare.PinnedHeadUnits(ctx) }
    var pins by remember { mutableStateOf(pinned.all) }
    // A head unit trusted from the notification while this screen is open shows up right away.
    DisposableEffect(pinned) { onDispose(pinned.listen { pins = pinned.all }) }
    // Read here, not in the Section's lambda, so coming back from Developer options re-checks it.
    @Suppress("UNUSED_EXPRESSION") refresh
    val mockAllowed = PhoneLocationFeed.allowed(ctx)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Section(stringResource(R.string.setup_section_connecting)) {
            AutoRivalCheck()
            Toggle(stringResource(R.string.setup_toggle_wireless_title), stringResource(R.string.setup_toggle_wireless_subtitle), wireless) { wireless = it; settings.wireless = it; if (it) ProjectionService.probeWireless(ctx) }
            // the car's internet over its Wi-Fi (18.11.2026: "dass er die Fahrzeug-Internet-Verbindung nutzt")
            if (wireless) {
                var carNet by remember { mutableStateOf(settings.carInternet) }
                Toggle(stringResource(R.string.car_internet_title), stringResource(R.string.car_internet_sub), carNet) {
                    carNet = it; settings.carInternet = it
                    if (!it) net.prototux.aare.transport.wireless.CarInternet.withdraw(ctx)
                }
            }
            Toggle(stringResource(R.string.setup_toggle_resume_music_title), stringResource(R.string.setup_toggle_resume_music_subtitle), resume) { resume = it; settings.resumeMedia = it }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(btAddr, { btAddr = it; settings.phoneBluetoothAddress = it.trim() }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.setup_bt_address_label)) },
                supportingText = { Text(stringResource(R.string.setup_bt_address_hint)) },
                singleLine = true)
        }
        // no update check of AARE's own: Mate's updates come through the Panel-Store (O223, 17.11.2026)
        Section(stringResource(R.string.setup_section_location)) {
            Toggle(stringResource(R.string.setup_toggle_car_gps_title), stringResource(R.string.setup_toggle_car_gps_subtitle), carGps) {
                carGps = it; settings.carGpsForPhone = it
            }
            if (carGps) Check(stringResource(R.string.setup_check_mock_location_title), stringResource(R.string.setup_check_mock_location_subtitle), mockAllowed) {
                ctx.startSafely(Intent(AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), appDetails(ctx))
            }
        }
        Section(stringResource(R.string.setup_section_video)) {
            Toggle(stringResource(R.string.setup_toggle_60fps_title), stringResource(R.string.setup_toggle_60fps_subtitle), fps60) { fps60 = it; settings.prefer60fps = it }
            Choices(stringResource(R.string.setup_choice_max_resolution), listOf(480, 720, 1080, 1440).map { "$it" to "${it}p" }, "$maxH") {
                maxH = it.toInt(); settings.maxVideoHeight = maxH
            }
            Text(stringResource(R.string.setup_hint_lower_resolution), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Toggle(stringResource(R.string.setup_toggle_force_h264_title), stringResource(R.string.setup_toggle_force_h264_subtitle), forceH264) { forceH264 = it; settings.forceH264 = it }
        }
        Section(stringResource(R.string.setup_section_your_cars)) {
            if (cars.isEmpty()) Text(stringResource(R.string.setup_cars_empty), style = MaterialTheme.typography.bodyMedium)
            cars.entries.sortedByDescending { it.value }.forEach { (name, time) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.DirectionsCar, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.setup_car_last_connected, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(time)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { settings.forgetCar(name); cars = settings.knownCars }) { Text(stringResource(R.string.setup_action_forget)) }
                }
            }
        }
        Section(stringResource(R.string.setup_section_trusted_head_units)) {
            Text(stringResource(R.string.setup_trusted_head_units_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (pins.isEmpty()) Text(stringResource(R.string.setup_trusted_head_units_empty), style = MaterialTheme.typography.bodyMedium)
            pins.entries.sortedBy { it.key }.forEach { (key, shas) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(headUnitLabel(ctx, key), style = MaterialTheme.typography.bodyLarge)
                        // stringResource can't run inside joinToString's lambda: get the template once, format each.
                        val fpTemplate = stringResource(R.string.setup_sha256_fingerprint)
                        Text(shas.joinToString { fpTemplate.format(it.take(16)) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { pinned.forget(key); pins = pinned.all }) { Text(stringResource(R.string.setup_action_forget)) }
                }
            }
        }
    }
}

/** A trusted head unit's key as the user knows it: the paired car's name, the USB accessory, or the developer server. */
@android.annotation.SuppressLint("MissingPermission") // wrapped: without BLUETOOTH_CONNECT the address is shown
private fun headUnitLabel(ctx: android.content.Context, key: String): String = when {
    key == "tcp" -> ctx.getString(R.string.huserver_label)
    key.startsWith("usb:") -> key.removePrefix("usb:").replace('|', ' ').trim().ifBlank { key }
    else -> runCatching { ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.getRemoteDevice(key)?.name }.getOrNull() ?: key
}

/** Android Auto's developer "head unit server": for the desktop head unit and other test tools. */
@Composable
private fun HeadUnitServerSettings(settings: Settings) {
    val ctx = LocalContext.current
    var huServer by remember { mutableStateOf(settings.headUnitServer) }
    var lan by remember { mutableStateOf(settings.headUnitServerLan) }
    // The service switches the LAN option off when a session ends: pick that up.
    val connected = ProjectionService.state.collectAsState().value.connected
    LaunchedEffect(connected) { lan = settings.headUnitServerLan }
    var lanOnce by remember { mutableStateOf(settings.headUnitServerLanOnce) }
    var mirror by remember { mutableStateOf(settings.headUnitServerMirror) }
    var localhost by remember { mutableStateOf(settings.headUnitServerLocalhost) }
    Section(stringResource(R.string.huserver_label)) {
        Toggle(stringResource(R.string.huserver_label), stringResource(R.string.huserver_toggle_subtitle), huServer) {
            huServer = it; settings.headUnitServer = it
            // Off only closes the listener: the service stays for a session, a wireless attempt or the
            // audio consent, and goes away by itself when it has nothing left.
            ProjectionService.startIdleIfEnabled(ctx)
        }
        if (huServer) Toggle(stringResource(R.string.huserver_toggle_network_title), stringResource(R.string.huserver_toggle_network_subtitle), lan) {
            lan = it; settings.headUnitServerLan = it
            ProjectionService.startIdleIfEnabled(ctx)
        }
        if (huServer) Toggle(stringResource(R.string.huserver_toggle_lanonce_title), stringResource(R.string.huserver_toggle_lanonce_subtitle), lanOnce) {
            lanOnce = it; settings.headUnitServerLanOnce = it
        }
        if (huServer) Toggle(stringResource(R.string.huserver_toggle_mirror_title), stringResource(R.string.huserver_toggle_mirror_subtitle), mirror) {
            mirror = it; settings.headUnitServerMirror = it
        }
        if (huServer) Toggle(stringResource(R.string.huserver_toggle_localhost_title), stringResource(R.string.huserver_toggle_localhost_subtitle), localhost) {
            localhost = it; settings.headUnitServerLocalhost = it
            ProjectionService.startIdleIfEnabled(ctx)
        }
    }
}

/**
 * The voice messages are read with: the voices the system's speech engine has for this language, best first.
 * Some are computed on the device, some by the engine's server (those need the internet). A tap on "Probe" lets
 * one be heard here on the device.
 */
@Composable
private fun VoiceCard(settings: Settings) {
    val ctx = LocalContext.current
    PiperChoice(settings)
    var voices by remember { mutableStateOf<List<android.speech.tts.Voice>>(emptyList()) }
    var chosen by remember { mutableStateOf(settings.ttsVoice) }
    val engine = remember { arrayOfNulls<android.speech.tts.TextToSpeech>(1) }
    DisposableEffect(Unit) {
        var t: android.speech.tts.TextToSpeech? = null
        t = android.speech.tts.TextToSpeech(ctx) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                val lang = java.util.Locale.getDefault().language
                voices = runCatching {
                    t!!.voices.orEmpty().filter { it.locale.language == lang && "notInstalled" !in it.features.orEmpty() }
                        .sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenBy { it.isNetworkConnectionRequired }.thenBy { it.name })
                }.getOrDefault(emptyList())
            }
        }
        engine[0] = t
        onDispose { runCatching { t.shutdown() } }
    }
    var chosenLocal by remember { mutableStateOf(settings.ttsVoiceLocal) }
    @Composable
    fun list(title: String, all: List<android.speech.tts.Voice>, picked: String?, pick: (String?) -> Unit) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 14.dp))
        Row(Modifier.fillMaxWidth().clickable { pick(null) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.RadioButton(selected = picked == null, onClick = { pick(null) })
            Text(stringResource(R.string.voice_default), style = MaterialTheme.typography.bodyLarge)
        }
        all.forEach { v ->
            Row(Modifier.fillMaxWidth().clickable { pick(v.name) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(selected = picked == v.name, onClick = { pick(v.name) })
                Column(Modifier.weight(1f)) {
                    // the same number in both lists: a voice keeps its name
                    Text(stringResource(R.string.voice_number, voices.indexOf(v) + 1), style = MaterialTheme.typography.bodyLarge)
                    val where = stringResource(if (v.isNetworkConnectionRequired) R.string.voice_needs_internet else R.string.voice_on_device)
                    Text("$where · ${v.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.TextButton(onClick = {
                    engine[0]?.let { t ->
                        runCatching { t.voice = v }
                        t.speak(ctx.getString(R.string.voice_sample), android.speech.tts.TextToSpeech.QUEUE_FLUSH, android.os.Bundle(), "probe")
                    }
                }) { Text(stringResource(R.string.voice_try)) }
            }
        }
    }
    Section(stringResource(R.string.voice_title)) {
        Text(stringResource(R.string.voice_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        list(stringResource(R.string.voice_online), voices, chosen) { chosen = it; settings.ttsVoice = it }
        list(stringResource(R.string.voice_offline), voices.filter { !it.isNetworkConnectionRequired }, chosenLocal) { chosenLocal = it; settings.ttsVoiceLocal = it }
    }
}

private const val CHOICE_PREFS = "idmate_choice"   // the car screen reads the same (ui/car/IdmateShell.kt, AreaChoice)
private val NO_MUSIC = setOf("com.google.android.projection.gearhead", "com.android.bluetooth", "com.google.android.googlequicksearchbox")

private class PickApp(val pkg: String, val label: String, val icon: android.graphics.Bitmap?)

/** One app of a choice: a tap makes it the default; [pinned], where given, keeps it shown beside the default. */
@Composable
private fun PickRow(app: PickApp, selected: Boolean, pinned: Boolean?, onPin: (Boolean) -> Unit, onPick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onPick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = onPick)
        if (app.icon != null) Image(app.icon.asImageBitmap(), null, Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)))
        else Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text(app.label.take(1), color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Text(app.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (pinned != null) androidx.compose.material3.Checkbox(checked = pinned, onCheckedChange = onPin)
    }
}

/** The apps that can fill the navigation area, Mate Navi first (the navigation page and the saved views). */
private fun naviPicks(ctx: android.content.Context): List<PickApp> =
            net.prototux.aare.host.CarAppCatalog.query(ctx)
                .filter { it.isNavigation && it.packageName != ctx.packageName && it.packageName !in NO_MUSIC && it.packageName != net.prototux.aare.ui.car.IDMATE_PACKAGE }
                .distinctBy { it.packageName }
                .map { PickApp(it.packageName, runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(it.packageName, 0)).toString() }.getOrDefault(it.label), it.icon) }
                // and the apps with a private car screen of their own (Google Maps), which the car's navigation area takes too
                .plus(net.prototux.aare.host.projected.ProjectedAppCatalog.query(ctx).map { PickApp(it.packageName, it.label, it.icon) })
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
                // Mate's own navigation first (bauplan: "in der Tablet-App als Navi wählbar") - it was left out with
                // Mate's own package, so a chosen Mate Navi showed no choice at all
                .let { listOf(PickApp(ctx.packageName, "Mate Navi",
                    runCatching<android.graphics.Bitmap?> { ctx.packageManager.getApplicationIcon(ctx.packageName).let { d ->
                        android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
                            val c = android.graphics.Canvas(b); d.setBounds(0, 0, 96, 96); d.draw(c) } } }.getOrNull())) + it }

/** The music apps, Mate Musik first (the music page and the saved views). */
private fun musicPicks(ctx: android.content.Context): List<PickApp> {
            val pm = ctx.packageManager
            val pkgs = LinkedHashSet<String>()
            listOf("android.media.browse.MediaBrowserService", "androidx.media3.session.MediaLibraryService").forEach { action ->
                runCatching { pm.queryIntentServices(Intent(action), 0) }.getOrDefault(emptyList()).forEach { pkgs += it.serviceInfo.packageName }
            }
            return pkgs.filter { it !in NO_MUSIC }.map { pkg ->
                if (pkg == ctx.packageName) PickApp(pkg, ctx.getString(R.string.music_name),
                    runCatching { androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.music_ic_app)!!.toBitmap(96, 96) }.getOrNull())
                else PickApp(pkg, runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg),
                    runCatching { pm.getApplicationIcon(pkg).toBitmap(96, 96) }.getOrNull())
            }.sortedWith(compareByDescending<PickApp> { it.pkg == ctx.packageName }.thenBy { it.label.lowercase() })
}

/** Which navigation app fills the navigation area. The same choice as the long press on "Navi" in the car. */
@Composable
private fun NaviChoiceCard() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(CHOICE_PREFS, android.content.Context.MODE_PRIVATE) }
    var chosen by remember { mutableStateOf(prefs.getString("navi", null)) }
    val apps by produceState(emptyList<PickApp>()) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { naviPicks(ctx) } }
    Section(stringResource(R.string.mate_navi_title)) {
        Text(stringResource(R.string.mate_navi_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        if (apps.isEmpty()) Text(stringResource(R.string.mate_choice_none), style = MaterialTheme.typography.bodyMedium)
        // without a choice the car screen takes Google Maps, else ABRP, else the first that works
        val effective = chosen ?: apps.firstOrNull { it.pkg == "com.google.android.apps.maps" }?.pkg
            ?: apps.firstOrNull { it.pkg == "com.iternio.abrpapp" }?.pkg ?: apps.firstOrNull()?.pkg
        apps.forEach { app ->
            PickRow(app, app.pkg == effective, null, {}) { chosen = app.pkg; prefs.edit().putString("navi", app.pkg).apply() }
        }
    }
}

/**
 * Charging stops in Google Maps (O107): the battery's size is chosen here, and what Maps is told is shown -
 * the state of charge and the consumption come from the dashboard.
 */
@Composable
private fun EvRouteChoice(settings: Settings) {
    val ctx = LocalContext.current
    var variant by remember { mutableStateOf(settings.evVariant) }
    var car by remember { mutableStateOf(net.prototux.aare.host.CarDataService.car(ctx)) }
    LaunchedEffect(variant) { while (true) { car = net.prototux.aare.host.CarDataService.car(ctx); kotlinx.coroutines.delay(5000) } }
    var source by remember { mutableStateOf(settings.evModelSource) }
    Choices(stringResource(R.string.setup_choice_ev_source), listOf("google" to stringResource(R.string.setup_ev_source_google),
        "mate" to stringResource(R.string.setup_ev_source_mate), "off" to stringResource(R.string.setup_ev_source_off)), source) {
        source = it; settings.evModelSource = it
    }
    val on = android.provider.Settings.Global.getInt(ctx.contentResolver, "panel_auto_ev", 0) == 1
    if (!on) Text(stringResource(R.string.setup_ev_switch_off), style = MaterialTheme.typography.bodyMedium)
    when (source) {
        "google" -> Text(stringResource(R.string.setup_ev_google_hint), style = MaterialTheme.typography.bodyMedium)
        "mate" -> {
            Choices(stringResource(R.string.setup_choice_ev_battery), Settings.EV_VARIANTS, variant) {
                variant = it; settings.evVariant = it; car = net.prototux.aare.host.CarDataService.car(ctx)
            }
            // System I-003: with or without IDmate's consumption curve (Maps planned stops in the test without it)
            var curve by remember { mutableStateOf(settings.evUseIdmateCurve) }
            Toggle(stringResource(R.string.setup_ev_curve_title), stringResource(R.string.setup_ev_curve_sub), curve) {
                curve = it; settings.evUseIdmateCurve = it; car = net.prototux.aare.host.CarDataService.car(ctx)
            }
            val c = car
            Text(when {
                c == null -> stringResource(R.string.setup_ev_no_soc)
                c.curve != null -> stringResource(R.string.setup_ev_state_idmate, "%.1f".format(c.capacityWh / 1000.0), c.socPercent.toInt(),
                    "%.1f".format(net.prototux.aare.host.EnergyModel.powerW(100.0, c) / 1000), "%.1f".format(net.prototux.aare.host.EnergyModel.powerW(130.0, c) / 1300),
                    c.outsideCelsius.toInt())
                c.consumptionKwh100 == null -> stringResource(R.string.setup_ev_state_no_trips, c.capacityWh / 1000, c.socPercent.toInt())
                else -> stringResource(R.string.setup_ev_state, c.capacityWh / 1000, c.socPercent.toInt(), "%.1f".format(c.consumptionKwh100))
            }, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.setup_ev_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // System I-003: what Google Maps last did with Mate's car - whether it asks at all and what it got
            val steps = remember(car) { net.prototux.aare.host.CarDataService.steps(ctx) }
            val fmt = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
            val names = mapOf("bind" to R.string.setup_ev_step_bind, "car" to R.string.setup_ev_step_car, "refused" to R.string.setup_ev_step_refused,
                "sensor" to R.string.setup_ev_step_sensor, "sent" to R.string.setup_ev_step_sent)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.setup_ev_steps), style = MaterialTheme.typography.bodyMedium)
            steps.forEach { (step, at, what) ->
                if (step == "refused" && at == 0L) return@forEach
                Text("• " + stringResource(names.getValue(step)) + ": " + (if (at == 0L) stringResource(R.string.setup_ev_step_never) else fmt.format(java.util.Date(at))) +
                    if (what.isNotEmpty()) " ($what)" else "", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * The one size of the car screen (O134), for the car connected last - each car keeps its own. Shown on the car
 * screen page, with the bar and with the navigation choice.
 */
/**
 * The bar in one place (overall look and feel review, 18.11.2026): where it sits, dark or not, the "Fehler markieren"
 * button in its switcher - before, its place and colour were under "Autobildschirm" and the size twice on both pages.
 */
/** The built-in backgrounds by name in the app's language (review 18.11.2026: "Dusk", "Forest", "Ocean" stood in English). */
@Composable
private fun wallpaperName(key: String): String = when (key) {
    "dusk" -> stringResource(R.string.setup_wallpaper_dusk)
    "forest" -> stringResource(R.string.setup_wallpaper_forest)
    "ocean" -> stringResource(R.string.setup_wallpaper_ocean)
    else -> key.replaceFirstChar(Char::uppercase)
}

@Composable
private fun BarSettings(settings: Settings) {
    var rail by remember { mutableStateOf(settings.railPosition) }
    var darkBar by remember { mutableStateOf(settings.darkBar) }
    var mark by remember { mutableStateOf(settings.faultMarkButton) }
    Section(stringResource(R.string.setup_section_look)) {
        Choices(stringResource(R.string.setup_choice_bar), listOf(Settings.RAIL_AUTO to stringResource(R.string.setup_choice_bar_auto), Settings.RAIL_LEFT to stringResource(R.string.setup_choice_left), Settings.RAIL_RIGHT to stringResource(R.string.setup_choice_right), Settings.RAIL_BOTTOM to stringResource(R.string.setup_choice_bottom)), rail) {
            rail = it; settings.railPosition = it
        }
        Toggle(stringResource(R.string.setup_toggle_dark_bar_title), stringResource(R.string.setup_toggle_dark_bar_subtitle), darkBar) { darkBar = it; settings.darkBar = it }
        Toggle(stringResource(R.string.setup_toggle_fault_mark_title), stringResource(R.string.setup_toggle_fault_mark_subtitle), mark) {
            mark = it; settings.faultMarkButton = it
        }
    }
}

@Composable
private fun SizeChoice(settings: Settings) {
    val scaleCar = remember { settings.knownCars.maxByOrNull { it.value }?.key.orEmpty() }
    var size by remember { mutableStateOf(settings.size(scaleCar)) }
    Choices(stringResource(R.string.setup_choice_size), Settings.APP_SCALES.map { "$it" to "$it %" }, "$size") {
        size = it.toInt(); settings.setSize(scaleCar, size)
    }
    Text(if (scaleCar.isBlank()) stringResource(R.string.setup_choice_app_scale_hint_any)
        else stringResource(R.string.setup_choice_app_scale_hint, scaleCar),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Foreign apps against Mate, for the same car: the fine tuning beside the one size. */
@Composable
private fun AppTrimChoice(settings: Settings) {
    val scaleCar = remember { settings.knownCars.maxByOrNull { it.value }?.key.orEmpty() }
    var trim by remember { mutableStateOf(settings.appTrim(scaleCar)) }
    val names = listOf(R.string.setup_choice_trim_smaller2, R.string.setup_choice_trim_smaller1, R.string.setup_choice_trim_same,
        R.string.setup_choice_trim_larger1, R.string.setup_choice_trim_larger2)
    Choices(stringResource(R.string.setup_choice_app_trim), (-2..2).map { "$it" to stringResource(names[it + 2]) }, "$trim") {
        trim = it.toInt(); settings.setAppTrim(scaleCar, trim)
    }
    Text(stringResource(R.string.setup_choice_app_trim_hint),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Mate's sound for the car, in two layers (System L-007, L-010: "klar getrennt und beschriftet, was die Einmessung ist
 * und was nur ein Klang-Preset ist"): 1. the measurement of the car's speakers, kept per car, several to choose from
 * (L-006); 2. the preset for the taste by the kind of app that plays (L-008, L-009). A preset never changes the
 * measurement (I-011). Works on everything Mate sends to the car's media channel, not on navigation prompts.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SoundCard(settings: Settings) {
    val ctx = LocalContext.current
    val car = remember { settings.knownCars.maxByOrNull { it.value }?.key.orEmpty() }
    val S = net.prototux.aare.audio.CarSound
    var on by remember { mutableStateOf(settings.eqOn(car)) }
    var version by remember { mutableStateOf(0) }           // counts every change, for what is read anew
    fun changed() { version++; S.apply(ctx, car) }
    val measures = remember(version) { S.measures(ctx, car) }
    val active = remember(version) { S.activeMeasure(ctx, car) }
    var kind by remember { mutableStateOf(SoundKind.MUSIC) }
    val presets = remember(version) { S.presets(ctx) }
    val presetKey = remember(version, kind) { S.presetKey(ctx, kind) }
    // the sliders: the chosen preset, or what the driver is shaping from it (saved as an own preset)
    var draft by remember(version, kind) { mutableStateOf<FloatArray?>(null) }
    val shown = draft ?: S.preset(ctx, presetKey).gains
    var naming by remember { mutableStateOf(false) }
    val presetNames = mapOf("flat" to R.string.sound_preset_flat, "bass" to R.string.sound_preset_bass, "treble" to R.string.sound_preset_treble,
        "voice" to R.string.sound_preset_voice, "jazz" to R.string.sound_preset_jazz, "rock" to R.string.sound_preset_rock,
        "pop" to R.string.sound_preset_pop, "classic" to R.string.sound_preset_classic, "book" to R.string.sound_preset_book)
    @Composable fun presetName(p: net.prototux.aare.audio.CarSound.Preset) = p.name?.takeIf { p.own } ?: stringResource(presetNames[p.key] ?: R.string.sound_preset_flat)
    val dateFmt = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Section(stringResource(R.string.sound_section_filter)) {
            Toggle(stringResource(R.string.sound_toggle_title), stringResource(R.string.sound_toggle_subtitle), on) {
                on = it; settings.setEq(car, on, settings.eqGains(car)); changed() }
            Text(if (car.isBlank()) stringResource(R.string.sound_car_hint_any) else stringResource(R.string.sound_car_hint, car),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.sound_total), style = MaterialTheme.typography.bodyLarge)
            EqCurve(S.combined(S.correction(ctx, car), shown), on)
            Text(stringResource(R.string.sound_layers_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // 1. the measurement: what corrects the car's speakers
        Section(stringResource(R.string.sound_section_measure)) {
            Text(stringResource(R.string.sound_measure_layer_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            @Composable fun row(id: Long, title: String, sub: String?, onDelete: (() -> Unit)?) {
                Row(Modifier.fillMaxWidth().clickable { S.useMeasure(ctx, car, id); changed() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected = active == id, onClick = { S.useMeasure(ctx, car, id); changed() })
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.bodyLarge)
                        sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    onDelete?.let { d -> androidx.compose.material3.TextButton(onClick = d) { Text(stringResource(R.string.sound_measure_delete)) } }
                }
            }
            row(0, stringResource(R.string.sound_measure_none), null, null)
            val targets = mapOf("neutral" to R.string.sound_target_neutral, "warm" to R.string.sound_target_warm, "bass" to R.string.sound_target_bass)
            measures.forEach { m ->
                row(m.id, m.name.ifBlank { dateFmt.format(java.util.Date(m.id)) },
                    stringResource(R.string.sound_measure_row, stringResource(targets[m.target] ?: R.string.sound_target_neutral),
                        m.gains.joinToString(" ") { val v = it.toInt(); if (v > 0) "+$v" else "$v" })) { S.deleteMeasure(ctx, car, m.id); changed() }
            }
            if (measures.size > 1) Text(stringResource(R.string.sound_measure_compare), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            MeasureSection { measured, target -> S.addMeasure(ctx, car, measured, target); if (!on) { on = true; settings.setEq(car, true, settings.eqGains(car)) }; changed() }
        }
        // 2. the preset: the taste, by the kind of app
        Section(stringResource(R.string.sound_section_preset)) {
            Text(stringResource(R.string.sound_preset_layer_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Choices(stringResource(R.string.sound_kind_for), listOf(SoundKind.MUSIC.key to stringResource(R.string.sound_kind_music), SoundKind.BOOK.key to stringResource(R.string.sound_kind_book)), kind.key) {
                kind = SoundKind.of(it) ?: SoundKind.MUSIC }
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                presets.forEach { p ->
                    androidx.compose.material3.FilterChip(selected = draft == null && p.key == presetKey, onClick = { S.setPreset(ctx, kind, p.key); changed() },
                        label = { Text(presetName(p)) })
                }
            }
            Spacer(Modifier.height(8.dp))
            net.prototux.aare.audio.CarEq.BANDS.forEachIndexed { i, f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (f >= 1000) "${f / 1000} kHz" else "$f Hz", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(64.dp))
                    androidx.compose.material3.Slider(
                        value = shown[i], enabled = on,
                        onValueChange = { v -> draft = shown.copyOf().also { it[i] = Math.round(v).toFloat() } },
                        valueRange = net.prototux.aare.audio.CarEq.MIN_DB.toFloat()..net.prototux.aare.audio.CarEq.MAX_DB.toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                    val g = shown[i].toInt()
                    Text(if (g > 0) "+$g dB" else "$g dB", style = MaterialTheme.typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.width(56.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (draft != null) {
                    androidx.compose.material3.Button(onClick = { naming = true }) { Text(stringResource(R.string.sound_preset_save)) }
                    androidx.compose.material3.OutlinedButton(onClick = { draft = null }) { Text(stringResource(R.string.sound_measure_discard)) }
                } else presets.firstOrNull { it.key == presetKey && it.own }?.let { p ->
                    androidx.compose.material3.OutlinedButton(onClick = { S.deleteOwn(ctx, p.key); changed() }) { Text(stringResource(R.string.sound_preset_delete)) }
                }
            }
            Text(stringResource(R.string.sound_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // which kind each app is (E-006): found by Mate, changeable by hand
        Section(stringResource(R.string.sound_section_kinds)) {
            Text(stringResource(R.string.sound_kinds_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val apps by produceState<List<net.prototux.aare.host.PhoneApp>?>(null) {
                value = withContext(Dispatchers.IO) { net.prototux.aare.host.AndroidAutoApps.mediaApps(ctx) }
            }
            val list = apps
            if (list == null) Text(stringResource(R.string.setup_app_list_loading), style = MaterialTheme.typography.bodyMedium)
            else remember(list, version) { list.groupBy { S.kind(ctx, it.packageName) } }.toSortedMap(compareBy { it.ordinal }).forEach { (k, inKind) ->
                Spacer(Modifier.height(8.dp))
                Text(stringResource(when (k) { SoundKind.MUSIC -> R.string.sound_kind_music; SoundKind.BOOK -> R.string.sound_kind_book; SoundKind.NAVI -> R.string.sound_kind_navi }),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                inKind.sortedBy { it.label.lowercase() }.forEach { app ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(app.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        val auto = S.autoKind(ctx, app.packageName)
                        listOf(SoundKind.MUSIC to R.string.sound_kind_music, SoundKind.BOOK to R.string.sound_kind_book, SoundKind.NAVI to R.string.sound_kind_navi).forEach { (kk, label) ->
                            androidx.compose.material3.FilterChip(selected = k == kk, modifier = Modifier.padding(start = 4.dp),
                                onClick = { S.setKind(ctx, app.packageName, if (kk == auto) null else kk); changed() },
                                label = { Text(stringResource(label) + if (kk == auto) " ·A" else "") })
                        }
                    }
                }
            }
            Text(stringResource(R.string.sound_kinds_auto), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(onDismissRequest = { naming = false },
            title = { Text(stringResource(R.string.sound_preset_save)) },
            text = { androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.sound_preset_name)) }, singleLine = true) },
            confirmButton = { androidx.compose.material3.TextButton(enabled = name.isNotBlank(), onClick = {
                draft?.let { d -> val key = S.saveOwn(ctx, name, d); S.setPreset(ctx, kind, key) }
                naming = false; changed() }) { Text(stringResource(R.string.common_ok)) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { naming = false }) { Text(stringResource(R.string.common_cancel)) } })
    }
}

/** Measuring the car with the phone's microphone (O50, stage 2): pink noise through the car, the bands from what was heard. */
@Composable
private fun MeasureSection(apply: (FloatArray, String) -> Unit) {
    val ctx = LocalContext.current
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var phase by remember { mutableStateOf<net.prototux.aare.audio.CarEqMeasure.Phase?>(null) }
    var result by remember { mutableStateOf<net.prototux.aare.audio.CarEqAnalysis.Result?>(null) }
    var run by remember { mutableStateOf<net.prototux.aare.audio.CarEqMeasure?>(null) }
    var linked by remember { mutableStateOf(net.prototux.aare.audio.CarEq.mediaLinked) }
    var driving by remember { mutableStateOf(net.prototux.aare.audio.CarEq.carDriving) }
    val targetPrefs = remember { ctx.getSharedPreferences("mate_sound", android.content.Context.MODE_PRIVATE) }
    var target by remember { mutableStateOf(targetPrefs.getString("target", "neutral") ?: "neutral") }
    LaunchedEffect(Unit) {
        while (true) { linked = net.prototux.aare.audio.CarEq.mediaLinked; driving = net.prototux.aare.audio.CarEq.carDriving; kotlinx.coroutines.delay(1000) }
    }
    DisposableEffect(Unit) { onDispose { run?.cancel() } }
    fun start() {
        result = null
        run = net.prototux.aare.audio.CarEqMeasure(ctx.applicationContext).also { m ->
            m.run(net.prototux.aare.audio.CarEqAnalysis.TARGETS.firstOrNull { it.first == target }?.second ?: FloatArray(10), { p -> main.post { phase = p } }) { r -> main.post { result = r; phase = null; run = null } }
        }
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) start() }
    Section(stringResource(R.string.sound_measure_title)) {
        Text(stringResource(R.string.sound_measure_steps), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.sound_measure_mic), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        val r = result
        val g = r?.gains
        when {
            run != null -> {
                Text(stringResource(when (phase) { net.prototux.aare.audio.CarEqMeasure.Phase.NOISE -> R.string.sound_measure_noise; net.prototux.aare.audio.CarEqMeasure.Phase.SWEEP -> R.string.sound_measure_sweep; else -> R.string.sound_measure_quiet }),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                androidx.compose.material3.OutlinedButton(onClick = { run?.cancel() }) { Text(stringResource(R.string.sound_measure_cancel)) }
            }
            g != null -> {
                Text(stringResource(R.string.sound_measure_result), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                EqCurve(g, true)
                Text(g.joinToString("  ") { val v = it.toInt(); if (v > 0) "+$v" else "$v" } + " dB",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.Button(onClick = { apply(g, target); result = null }) { Text(stringResource(R.string.sound_measure_apply)) }
                    androidx.compose.material3.OutlinedButton(onClick = { result = null }) { Text(stringResource(R.string.sound_measure_discard)) }
                }
            }
            else -> {
                if (r != null) Text(stringResource(if (r.error == "quiet") R.string.sound_measure_too_quiet else R.string.sound_measure_failed),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                val why = when { driving -> R.string.sound_measure_parked_only; !linked -> R.string.sound_measure_no_car; else -> null }
                val targetNames = mapOf("neutral" to R.string.sound_target_neutral, "warm" to R.string.sound_target_warm, "bass" to R.string.sound_target_bass)
                Choices(stringResource(R.string.sound_target), net.prototux.aare.audio.CarEqAnalysis.TARGETS.map { it.first to stringResource(targetNames.getValue(it.first)) }, target) {
                    target = it; targetPrefs.edit().putString("target", it).apply()
                }
                Text(stringResource(R.string.sound_target_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                if (why != null) Text(stringResource(why), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.Button(enabled = why == null, onClick = {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) start()
                    else mic.launch(android.Manifest.permission.RECORD_AUDIO)
                }) { Text(stringResource(R.string.sound_measure_start)) }
            }
        }
    }
}

/** The filter's effect from 20 Hz to 20 kHz (log scale), with the 0 dB line; dimmed while the filter is off. */
@Composable
private fun EqCurve(gains: FloatArray, on: Boolean) {
    val line = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val grid = MaterialTheme.colorScheme.outlineVariant
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val lo = net.prototux.aare.audio.CarEq.MIN_DB - 6f; val hi = net.prototux.aare.audio.CarEq.MAX_DB + 6f
        fun y(dB: Double) = (size.height * (hi - dB) / (hi - lo)).toFloat().coerceIn(0f, size.height)
        drawLine(grid, androidx.compose.ui.geometry.Offset(0f, y(0.0)), androidx.compose.ui.geometry.Offset(size.width, y(0.0)), strokeWidth = 2f)
        for (f in net.prototux.aare.audio.CarEq.BANDS) {
            val x = (size.width * Math.log10(f / 20.0) / 3).toFloat()
            drawLine(grid, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = 1f)
        }
        val path = androidx.compose.ui.graphics.Path()
        val n = 120
        for (k in 0..n) {
            val f = 20.0 * Math.pow(1000.0, k / n.toDouble())
            val px = size.width * k / n
            val py = y(net.prototux.aare.audio.CarEq.responseDb(gains, f))
            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, line, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

/**
 * Saved views (O6): what the car screen starts with - the area in front and the navigation and music app - under a
 * name. Chosen here, read by the car screen when a car connects.
 */
@Composable
private fun ViewsCard() {
    val ctx = LocalContext.current
    val views = remember { net.prototux.aare.ui.car.MateViews(ctx) }
    val choicePrefs = remember { ctx.getSharedPreferences(CHOICE_PREFS, android.content.Context.MODE_PRIVATE) }
    var list by remember { mutableStateOf(views.all) }
    var active by remember { mutableStateOf(views.active?.name) }
    var editing by remember { mutableStateOf<net.prototux.aare.ui.car.MateViews.View?>(null) }
    fun label(pkg: String?): String = pkg?.let { p ->
        if (p == ctx.packageName) return@let ctx.getString(R.string.music_name)
        runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
    } ?: ctx.getString(R.string.views_app_auto)
    val startNames = mapOf(
        net.prototux.aare.ui.car.MateViews.START_DASHBOARD to stringResource(R.string.idmate_tab_dashboard),
        net.prototux.aare.ui.car.MateViews.START_NAVI to stringResource(R.string.idmate_tab_navigation),
        net.prototux.aare.ui.car.MateViews.START_MUSIC to stringResource(R.string.idmate_tab_music),
        net.prototux.aare.ui.car.MateViews.START_MESSAGES to stringResource(R.string.idmate_tab_messages),
        net.prototux.aare.ui.car.MateViews.START_SPLIT to stringResource(R.string.views_start_split),
    )
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Section(stringResource(R.string.views_section_active)) {
            Text(stringResource(R.string.views_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            ViewRow(stringResource(R.string.views_plain), stringResource(R.string.views_plain_sub), active == null, null) {
                active = null; views.activate(null)
            }
            list.forEach { v ->
                val shareName = listOf(0.25f to "¼", 1f / 3 to "⅓", 0.5f to "½", 2f / 3 to "⅔", 0.75f to "¾").minByOrNull { kotlin.math.abs(it.first - v.share) }!!.second
                val sub = stringResource(R.string.views_summary, startNames[v.start] ?: v.start, label(v.navi), label(v.music)) +
                    if (v.start == net.prototux.aare.ui.car.MateViews.START_SPLIT)
                        " · " + stringResource(if (v.naviRight) R.string.views_side_right else R.string.views_side_left) + " " + shareName else ""
                ViewRow(v.name, sub, active == v.name, onEdit = { editing = v }, onDelete = { views.delete(v.name); list = views.all; active = views.active?.name }) {
                    active = v.name; views.activate(v.name)
                }
            }
        }
        Section(stringResource(R.string.views_section_new)) {
            var name by remember { mutableStateOf("") }
            var start by remember { mutableStateOf(net.prototux.aare.ui.car.MateViews.START_NAVI) }
            // 18.11.2026: the view's own apps, and for a split the navigation's side and share
            var naviPick by remember { mutableStateOf("") }
            var musicPick by remember { mutableStateOf("") }
            var right by remember { mutableStateOf(true) }
            var share by remember { mutableStateOf(0.5f) }
            val naviApps by produceState(emptyList<PickApp>()) { value = withContext(Dispatchers.IO) { naviPicks(ctx) } }
            val musicApps by produceState(emptyList<PickApp>()) { value = withContext(Dispatchers.IO) { musicPicks(ctx) } }
            editing?.let { v -> LaunchedEffect(v) { name = v.name; start = v.start; naviPick = v.navi ?: ""; musicPick = v.music ?: ""
                right = v.naviRight; share = v.share; editing = null } }
            OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, label = { Text(stringResource(R.string.views_name)) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Choices(stringResource(R.string.views_start), net.prototux.aare.ui.car.MateViews.STARTS.map { it to (startNames[it] ?: it) }, start) { start = it }
            if (start == net.prototux.aare.ui.car.MateViews.START_SPLIT) {
                Spacer(Modifier.height(12.dp))
                Choices(stringResource(R.string.views_split_side), listOf("l" to stringResource(R.string.views_side_left), "r" to stringResource(R.string.views_side_right)),
                    if (right) "r" else "l") { right = it == "r" }
                Spacer(Modifier.height(12.dp))
                val shares = listOf(0.25f to "¼", 1f / 3 to "⅓", 0.5f to "½", 2f / 3 to "⅔", 0.75f to "¾")
                Choices(stringResource(R.string.views_split_share), shares.map { it.second to it.second },
                    shares.minByOrNull { kotlin.math.abs(it.first - share) }!!.second) { c -> share = shares.first { it.second == c }.first }
            }
            val current = PickApp("", stringResource(R.string.views_app_current), null)
            if (start != net.prototux.aare.ui.car.MateViews.START_MUSIC && start != net.prototux.aare.ui.car.MateViews.START_MESSAGES) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.views_navi_app), style = MaterialTheme.typography.bodyLarge)
                (listOf(current) + naviApps).forEach { app -> PickRow(app, app.pkg == naviPick, null, {}) { naviPick = app.pkg } }
            }
            if (start != net.prototux.aare.ui.car.MateViews.START_NAVI && start != net.prototux.aare.ui.car.MateViews.START_MESSAGES) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.views_music_app), style = MaterialTheme.typography.bodyLarge)
                (listOf(current) + musicApps).forEach { app -> PickRow(app, app.pkg == musicPick, null, {}) { musicPick = app.pkg } }
            }
            Spacer(Modifier.height(8.dp))
            val navi = naviPick.ifBlank { null } ?: choicePrefs.getString("navi", null)
            val music = musicPick.ifBlank { null } ?: choicePrefs.getString("music", null)
            androidx.compose.material3.Button(enabled = name.isNotBlank(), onClick = {
                views.save(net.prototux.aare.ui.car.MateViews.View(name.trim(), start, navi, music, right, share))
                list = views.all; active = views.active?.name; name = ""; naviPick = ""; musicPick = ""
            }) { Text(stringResource(R.string.views_save)) }
        }
    }
}

@Composable
private fun ViewRow(title: String, subtitle: String, selected: Boolean, onDelete: (() -> Unit)?, onEdit: (() -> Unit)? = null, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onSelect).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // into the form below, to change it and save it under the same name
        if (onEdit != null) IconButton(onClick = onEdit) {
            Icon(Icons.Rounded.Tune, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onDelete != null) IconButton(onClick = onDelete) {
            Icon(Icons.Rounded.Close, stringResource(R.string.views_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Speech recognition on the device (O56): which Whisper sizes are there, how fast they are here, and a try with your own voice. */
@Composable
private fun SpeechCard() {
    val ctx = LocalContext.current
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    // read again whenever a download moves on or ends
    val sizes = remember(net.prototux.aare.speech.ModelDownload.progress.values.toList()) { net.prototux.aare.speech.LocalAsr.installed(ctx) }
    var busy by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf<List<net.prototux.aare.speech.SpeechBench.Row>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Section(stringResource(R.string.speech_section_models)) {
            Text(if (sizes.isEmpty()) stringResource(R.string.speech_no_models) else stringResource(R.string.speech_models, sizes.joinToString(", ") { "Whisper $it" }),
                style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.speech_models_where), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // load a model here instead of copying it over (O56)
            net.prototux.aare.speech.ModelDownload.OFFERED.forEach { (size, mb) ->
                val p = net.prototux.aare.speech.ModelDownload.progress[size]
                val there = (p == 1f) || size in net.prototux.aare.speech.LocalAsr.installed(ctx)
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.speech_model_row, size, mb), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(when { there -> R.string.speech_model_there; p == -1f -> R.string.speech_model_failed
                            p != null -> R.string.speech_model_loading; else -> R.string.speech_model_absent }),
                            style = MaterialTheme.typography.bodySmall, color = if (p == -1f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (p != null && p in 0f..0.999f) androidx.compose.material3.LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    if (!there && (p == null || p == -1f)) androidx.compose.material3.OutlinedButton(onClick = { net.prototux.aare.speech.ModelDownload.start(ctx, size) }) {
                        Text(stringResource(R.string.speech_model_load))
                    }
                }
            }
        }
        // 18.11.2026: Mate's recognition offered to the system - chosen there as the voice input
        Section(stringResource(R.string.speech_system_title)) {
            Text(stringResource(R.string.speech_system_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.FilledTonalButton(onClick = {
                ctx.startSafely(Intent(AndroidSettings.ACTION_VOICE_INPUT_SETTINGS), Intent(AndroidSettings.ACTION_SETTINGS))
            }) { Text(stringResource(R.string.speech_system_open)) }
        }
        Section(stringResource(R.string.speech_section_bench)) {
            Text(stringResource(R.string.speech_bench_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            val b = busy
            if (b != null) {
                Text(b, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            } else androidx.compose.material3.Button(enabled = sizes.isNotEmpty(), onClick = {
                busy = ""; failed = false
                Thread {
                    val r = runCatching { net.prototux.aare.speech.SpeechBench.run(ctx.applicationContext) { p -> main.post { busy = p } } }
                    main.post { busy = null; r.onSuccess { rows = it }.onFailure { failed = true; android.util.Log.w("MateAsr", "bench failed", it) } }
                }.start()
            }) { Text(stringResource(R.string.speech_bench_start)) }
            if (failed) Text(stringResource(R.string.speech_bench_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            rows.groupBy { it.size to it.threads }.forEach { (key, list) ->
                Spacer(Modifier.height(10.dp))
                val avg = list.sumOf { it.ms } / 1000.0 / list.sumOf { it.seconds }
                Text(stringResource(R.string.speech_bench_row, key.first, key.second, "%.2f".format(avg), "%.1f".format(list.first().loadMs / 1000.0)),
                    style = MaterialTheme.typography.bodyLarge)
                list.forEach { r ->
                    Text("„${r.text}“  (${"%.1f".format(r.ms / 1000.0)} s)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (sizes.isNotEmpty()) SpeechTry(sizes)
    }
}

/** Five seconds of your own voice, recognized with the chosen size. */
@Composable
private fun SpeechTry(sizes: List<String>) {
    val ctx = LocalContext.current
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var size by remember { mutableStateOf(sizes.first()) }
    var state by remember { mutableStateOf<String?>(null) }
    var heard by remember { mutableStateOf<String?>(null) }
    fun go() {
        state = ctx.getString(R.string.speech_try_listening); heard = null
        Thread {
            val r = runCatching {
                val rate = 16000
                val min = android.media.AudioRecord.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
                @android.annotation.SuppressLint("MissingPermission")
                val rec = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                    android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
                val pcm = ShortArray(rate * 5)
                try {
                    rec.startRecording()
                    var got = 0
                    while (got < pcm.size) { val n = rec.read(pcm, got, pcm.size - got); if (n <= 0) error("mic $n"); got += n }
                } finally { rec.release() }
                main.post { state = ctx.getString(R.string.speech_try_thinking) }
                val asr = net.prototux.aare.speech.LocalAsr.load(ctx, size, 4)
                try {
                    val t = android.os.SystemClock.elapsedRealtime()
                    val text = net.prototux.aare.speech.LocalAsr.recognize(asr, FloatArray(pcm.size) { pcm[it] / 32768f }, rate)
                    "„$text“  (${"%.1f".format((android.os.SystemClock.elapsedRealtime() - t) / 1000.0)} s)"
                } finally { asr.release() }
            }
            main.post { state = null; heard = r.getOrElse { ctx.getString(R.string.speech_bench_failed) } }
        }.start()
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) go() }
    Section(stringResource(R.string.speech_section_try)) {
        if (sizes.size > 1) Choices(stringResource(R.string.speech_try_size), sizes.map { it to it }, size) { size = it }
        Spacer(Modifier.height(8.dp))
        val s = state
        if (s != null) {
            Text(s, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
        } else androidx.compose.material3.Button(onClick = {
            if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) go()
            else mic.launch(android.Manifest.permission.RECORD_AUDIO)
        }) { Text(stringResource(R.string.speech_try_start)) }
        heard?.let { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp)) }
    }
}

/**
 * The voice Mate reads with (O110, O224): the system's, or one computed on the device - Thorsten (Piper) or the German
 * Kokoro voices. Each can be loaded here, heard and chosen.
 */
@Composable
private fun PiperChoice(settings: Settings) {
    val ctx = LocalContext.current
    var engine by remember { mutableStateOf(settings.ttsEngine) }
    val voices = net.prototux.aare.speech.Voices.offered(ctx)
    Section(stringResource(R.string.voice_engine_section)) {
        Text(stringResource(R.string.voice_engine_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // the chosen voice for every app (ABRP, Google Maps ...): Mate as the tablet's preferred speech engine
        Text(stringResource(R.string.voice_all_apps_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp))
        androidx.compose.material3.OutlinedButton(modifier = Modifier.padding(top = 4.dp), onClick = {
            ctx.startSafely(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text(stringResource(R.string.voice_all_apps_open)) }
        // the system's voice
        Row(Modifier.fillMaxWidth().padding(top = 6.dp).clickable { engine = "system"; settings.ttsEngine = "system" }, verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.RadioButton(engine == "system", { engine = "system"; settings.ttsEngine = "system" })
            Text(stringResource(R.string.voice_engine_system), style = MaterialTheme.typography.bodyLarge)
        }
        voices.forEach { v ->
            val loading = net.prototux.aare.speech.ModelDownload.progress[v.id]
            val there = remember(loading) { net.prototux.aare.speech.Voices.available(ctx, v) }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(engine == v.id, { if (there) { engine = v.id; settings.ttsEngine = v.id } }, enabled = there)
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    Text(v.name, style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(if (v.female) R.string.voice_female else R.string.voice_male) + " · " +
                        (if (v.kind == net.prototux.aare.speech.Voices.Kind.KOKORO) "Kokoro" else "Piper") + " · ${v.sizeMb} MB",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    loading != null && loading in 0f..0.999f -> androidx.compose.material3.CircularProgressIndicator(progress = { loading }, modifier = Modifier.size(28.dp))
                    !there -> androidx.compose.material3.OutlinedButton(onClick = {
                        if (v.id == "piper") net.prototux.aare.speech.ModelDownload.startVoice(ctx)
                        else net.prototux.aare.speech.ModelDownload.startKokoro(ctx, v)
                    }) { Text(stringResource(if (loading == -1f) R.string.voice_engine_retry else R.string.voice_engine_load)) }
                    else -> Row {
                        androidx.compose.material3.TextButton(onClick = {
                            // heard in this voice; the chosen one stays as it is
                            net.prototux.aare.speech.LocalTts.sample(ctx, ctx.getString(R.string.voice_engine_sample, v.name), v)
                        }) { Text(stringResource(R.string.voice_engine_try)) }
                        // frees its space; one that cannot be downloaded again only from a saved settings file
                        androidx.compose.material3.TextButton(onClick = {
                            net.prototux.aare.speech.Voices.delete(ctx, v); engine = settings.ttsEngine
                            net.prototux.aare.speech.ModelDownload.progress[v.id] = -2f
                        }) { Text(stringResource(R.string.voice_engine_delete)) }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
}

/** The default music app and the apps shown beside it. The same choice as the long press on "Musik" in the car. */
@Composable
private fun MusicChoiceCard() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(CHOICE_PREFS, android.content.Context.MODE_PRIVATE) }
    var chosen by remember { mutableStateOf(prefs.getString("music", null)) }
    var extras by remember { mutableStateOf(prefs.getStringSet("music_extra", emptySet())?.toSet() ?: emptySet()) }
    val apps by produceState(emptyList<PickApp>()) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { musicPicks(ctx) } }
    Section(stringResource(R.string.mate_music_title)) {
        Text(stringResource(R.string.mate_music_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mate_music_col_default), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.mate_music_col_pinned), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val effective = chosen ?: ctx.packageName
        apps.forEach { app ->
            PickRow(app, app.pkg == effective, app.pkg in extras, { on ->
                extras = if (on) extras + app.pkg else extras - app.pkg
                prefs.edit().putStringSet("music_extra", HashSet(extras)).apply()
            }) { chosen = app.pkg; prefs.edit().putString("music", app.pkg).apply() }
        }
    }
}

/** The built-in dashboard: where its server and the stick are connected. */
@Composable
private fun DashboardCard(refresh: Int = 0) {
    val ctx = LocalContext.current
    // read again whenever the app comes back (after the setup page) - it was always "not connected" (17.11.2026)
    val server = remember(refresh) { net.prototux.aare.dash.DashStatus.server(ctx) }
    val stick = remember(refresh) { net.prototux.aare.dash.DashStatus.stick(ctx) }
    Section(stringResource(R.string.dashboard_card_title)) {
        Check(stringResource(R.string.dashboard_card_open),
            if (server == null) stringResource(R.string.dashboard_card_hint)
            else stringResource(if (stick) R.string.dashboard_card_connected_stick else R.string.dashboard_card_connected, server),
            server != null, changeLabel = stringResource(R.string.music_change)) {
            ctx.startActivity(Intent(ctx, net.prototux.aare.dash.SetupActivity::class.java))
        }
    }
}

/** The dashboard map's tiles kept on the device (O109): how much, how much is used, empty it. */
@Composable
private fun TileCacheCard() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(net.prototux.aare.dash.TileCache.PREFS, android.content.Context.MODE_PRIVATE) }
    var mb by remember { mutableStateOf(net.prototux.aare.dash.TileCache.limitMb(ctx)) }
    var used by remember { mutableStateOf(-1L) }
    LaunchedEffect(Unit) { while (true) { used = withContext(Dispatchers.IO) { net.prototux.aare.dash.TileCache.sizeBytes(ctx) }; kotlinx.coroutines.delay(3000) } }
    Section(stringResource(R.string.tilecache_section)) {
        Choices(stringResource(R.string.tilecache_size), net.prototux.aare.dash.TileCache.CHOICES_MB.map { v ->
            "$v" to when { v == 0 -> ctx.getString(R.string.tilecache_off); v >= 1024 -> "${v / 1024} GB"; else -> "$v MB" }
        }, "$mb") { mb = it.toInt(); prefs.edit().putInt(net.prototux.aare.dash.TileCache.KEY_MB, mb).apply() }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.tilecache_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (used < 0) "…" else stringResource(R.string.tilecache_used, "%.1f".format(used / 1048576.0)),
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            androidx.compose.material3.OutlinedButton(onClick = { net.prototux.aare.dash.TileCache.clear(ctx); used = 0 }) { Text(stringResource(R.string.tilecache_clear)) }
        }
    }
}

/** Which kinds of audio the dashboard names beside the controls while they play. */
@Composable
private fun DashTitleCard(settings: Settings) {
    var music by remember { mutableStateOf(settings.dashTitleMusic) }
    var books by remember { mutableStateOf(settings.dashTitleBooks) }
    var podcasts by remember { mutableStateOf(settings.dashTitlePodcasts) }
    Section(stringResource(R.string.dash_title_section)) {
        Text(stringResource(R.string.dash_title_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Toggle(stringResource(R.string.dash_title_music), stringResource(R.string.dash_title_music_sub), music) { music = it; settings.dashTitleMusic = it }
        Toggle(stringResource(R.string.dash_title_books), stringResource(R.string.dash_title_books_sub), books) { books = it; settings.dashTitleBooks = it }
        Toggle(stringResource(R.string.dash_title_podcasts), stringResource(R.string.dash_title_podcasts_sub), podcasts) { podcasts = it; settings.dashTitlePodcasts = it }
    }
}

/**
 * Playlists kept on the device (18.11.2026: "Auch Playlisten abonnieren, dass die immer aktuell offline gehalten werden"):
 * a switch per list of the server, how far each is loaded, Wi-Fi only, sync now, the space used and emptying it.
 */
@Composable
private fun MusicOfflineCard(refresh: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lists by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var subs by remember { mutableStateOf(net.prototux.aare.music.Offline.lists(ctx)) }
    var wifi by remember { mutableStateOf(net.prototux.aare.music.Offline.wifiOnly(ctx)) }
    var progress by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        val r = withContext(Dispatchers.IO) { net.prototux.aare.music.Offline.serverLists(ctx)?.map { it[0] to it[1] } }
        failed = r == null
        lists = r ?: emptyList()
    }
    // the numbers under each list and the space used, read again every few seconds while a sync runs
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(3000); tick++ } }
    val states = produceState(emptyMap<String, IntArray>(), subs, tick, lists) {
        value = withContext(Dispatchers.IO) { subs.associateWith { net.prototux.aare.music.Offline.state(ctx, it) } }
    }.value
    val bytes = produceState(0L, tick) { value = withContext(Dispatchers.IO) { net.prototux.aare.music.Offline.bytes(ctx) } }.value
    fun sync() {
        scope.launch(Dispatchers.IO) {
            progress = null
            val n = net.prototux.aare.music.Offline.sync(ctx) { t -> progress = t }
            if (n < 0) progress = ctx.getString(R.string.music_offline_no_net)
            tick++
        }
    }
    Section(stringResource(R.string.music_offline_title)) {
        Text(stringResource(R.string.music_offline_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            lists == null -> Text(stringResource(R.string.music_offline_loading), style = MaterialTheme.typography.bodyMedium)
            failed && subs.isEmpty() -> Text(stringResource(R.string.music_offline_no_server), style = MaterialTheme.typography.bodyMedium)
        }
        // the server's lists; subscribed lists the server no longer answers for stay listed under their last name
        val shown = (lists ?: emptyList()).toMutableList()
        subs.filter { s -> shown.none { it.first == s } }.forEach { shown += it to stringResource(R.string.music_offline_unknown) }
        shown.forEach { (key, title) ->
            val st = states[key]
            val sub = when {
                key !in subs -> stringResource(R.string.music_offline_off)
                st == null || st[0] == 0 -> stringResource(R.string.music_offline_waiting)
                else -> stringResource(R.string.music_offline_state, st[1], st[0])
            }
            Toggle(title, sub, key in subs) { on ->
                net.prototux.aare.music.Offline.subscribe(ctx, key, on)
                subs = net.prototux.aare.music.Offline.lists(ctx)
                net.prototux.aare.music.MusicSyncJob.schedule(ctx)
                // switched on: its titles are loaded; switched off: what no list holds any more is deleted now -
                // the last list switched off ended the regular sync too, and its files stayed on the device
                sync()
            }
        }
        Toggle(stringResource(R.string.music_offline_wifi), stringResource(R.string.music_offline_wifi_hint), wifi) {
            wifi = it; net.prototux.aare.music.Offline.setWifiOnly(ctx, it); net.prototux.aare.music.MusicSyncJob.schedule(ctx)
        }
        val last = net.prototux.aare.music.Offline.lastSync(ctx)
        Text(
            progress ?: if (last == 0L) stringResource(R.string.music_offline_never)
            else stringResource(R.string.music_offline_last, android.text.format.DateUtils.getRelativeTimeSpanString(last).toString(),
                android.text.format.Formatter.formatShortFileSize(ctx, bytes)),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.FilledTonalButton(onClick = { sync() }, enabled = subs.isNotEmpty() && (tick >= 0 && !net.prototux.aare.music.Offline.running())) {
                Text(stringResource(R.string.music_offline_sync))
            }
            androidx.compose.material3.TextButton(onClick = {
                scope.launch(Dispatchers.IO) { net.prototux.aare.music.Offline.clear(ctx); subs = emptySet(); net.prototux.aare.music.MusicSyncJob.schedule(ctx); tick++ }
            }, enabled = bytes > 0 || subs.isNotEmpty()) { Text(stringResource(R.string.music_offline_clear)) }
        }
    }
}

/** The built-in music area: where its Plex server is connected. */
@Composable
private fun MusicCard(refresh: Int) {
    val ctx = LocalContext.current
    // read again whenever the app comes back (after the setup page)
    val connected = remember(refresh) { net.prototux.aare.music.MusicSetupActivity.isSetUp(ctx) }
    Section(stringResource(R.string.music_name)) {
        Check(stringResource(R.string.music_title), stringResource(if (connected) R.string.music_hint_connected else R.string.music_hint), connected,
            changeLabel = stringResource(R.string.music_change)) {
            ctx.startActivity(Intent(ctx, net.prototux.aare.music.MusicSetupActivity::class.java))
        }
        val settings = remember { Settings(ctx) }
        var level by remember { mutableStateOf(settings.musicLevel) }
        Toggle(stringResource(R.string.music_level_title), stringResource(R.string.music_level_hint), level) { level = it; settings.musicLevel = it }
        if (level) {
            var boost by remember { mutableStateOf(settings.musicLevelBoostDb) }
            Text(stringResource(R.string.music_level_boost, -18 + boost, boost), style = MaterialTheme.typography.bodyLarge)
            androidx.compose.material3.Slider(
                value = boost.toFloat(), valueRange = 0f..10f, steps = 9,
                onValueChange = { boost = Math.round(it) }, onValueChangeFinished = { settings.musicLevelBoostDb = boost },
            )
            Text(stringResource(R.string.music_level_boost_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // 18.11.2026 ("alle anderen System-Apps sollten dann auch leiser gemacht werden und dem Zielpegel von der
            // Musik-App angepasst sein"): the same target for audio books, podcasts, other music apps and prompts
            var all by remember { mutableStateOf(settings.levelAll) }
            Toggle(stringResource(R.string.music_level_all), stringResource(R.string.music_level_all_hint), all) { all = it; settings.levelAll = it }
        }
    }
}

@Composable
private fun MessageSettings(settings: Settings, refresh: Int) {
    val ctx = LocalContext.current
    var banners by remember { mutableStateOf(settings.messageBanners) }
    var bannerSeconds by remember { mutableStateOf(settings.messageBannerSeconds) }
    var preview by remember { mutableStateOf(settings.messagePreview) }
    var duck by remember { mutableStateOf(settings.voiceDuckPercent) }
    var groups by remember { mutableStateOf(settings.groupMessages) }
    var aloud by remember { mutableStateOf(settings.readMessagesAloud) }
    var dnd by remember { mutableStateOf(settings.dndWhileDriving) }
    var autoReply by remember { mutableStateOf(settings.autoReply) }
    var replyText by remember { mutableStateOf(settings.autoReplyText) }
    @Suppress("UNUSED_EXPRESSION") refresh
    val access = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Section(stringResource(R.string.msg_settings_title)) {
        if (!access) Check(stringResource(R.string.setup_check_notification_access_title), stringResource(R.string.msg_check_notification_access_subtitle), false) {
            ctx.startSafely(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS), appDetails(ctx))
        }
        Toggle(stringResource(R.string.msg_toggle_banners_title), stringResource(R.string.msg_toggle_banners_subtitle), banners) { banners = it; settings.messageBanners = it }
        if (banners) {
            Text(stringResource(R.string.msg_banner_seconds, bannerSeconds), style = MaterialTheme.typography.bodyLarge)
            androidx.compose.material3.Slider(
                value = bannerSeconds.toFloat(), valueRange = 1f..15f, steps = 13,
                onValueChange = { bannerSeconds = Math.round(it) }, onValueChangeFinished = { settings.messageBannerSeconds = bannerSeconds },
            )
            Toggle(stringResource(R.string.msg_toggle_preview_title), stringResource(R.string.msg_toggle_preview_subtitle), preview) { preview = it; settings.messagePreview = it }
        }
        Toggle(stringResource(R.string.msg_toggle_group_title), stringResource(R.string.msg_toggle_group_subtitle), groups) { groups = it; settings.groupMessages = it }
        Toggle(stringResource(R.string.msg_toggle_read_aloud_title), stringResource(R.string.msg_toggle_read_aloud_subtitle), aloud) { aloud = it; settings.readMessagesAloud = it }
        // How far the music steps back for the voice (also when "read aloud" is tapped in the car).
        Text(stringResource(R.string.msg_duck_percent, duck), style = MaterialTheme.typography.bodyLarge)
        androidx.compose.material3.Slider(
            value = duck.toFloat(), valueRange = 0f..20f, steps = 3,
            onValueChange = { duck = (Math.round(it / 5f) * 5) }, onValueChangeFinished = { settings.voiceDuckPercent = duck },
        )
        Text(stringResource(R.string.msg_duck_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.msg_hint_apps),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Section(stringResource(R.string.msg_section_while_driving)) {
        Toggle(stringResource(R.string.msg_toggle_dnd_title), stringResource(R.string.msg_toggle_dnd_subtitle), dnd) {
            dnd = it; settings.dndWhileDriving = it
        }
        if (dnd) Check(stringResource(R.string.msg_check_dnd_access_title), stringResource(R.string.msg_check_dnd_access_subtitle), DrivingMode.accessGranted(ctx)) {
            ctx.startSafely(Intent(AndroidSettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS), appDetails(ctx))
        }
        Toggle(stringResource(R.string.msg_toggle_autoreply_title), stringResource(R.string.msg_toggle_autoreply_subtitle), autoReply) {
            autoReply = it; settings.autoReply = it
        }
        if (autoReply) OutlinedTextField(replyText, { replyText = it; settings.autoReplyText = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text(stringResource(R.string.msg_autoreply_field_label)) }, singleLine = true)
    }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun CarScreenSettings(settings: Settings, refresh: Int) {
    val ctx = LocalContext.current
    var theme by remember { mutableStateOf(settings.carTheme) }
    var phoneScreen by remember { mutableStateOf(settings.phoneScreen) }
    var passenger by remember { mutableStateOf(settings.ignoreDriving) }
    var weather by remember { mutableStateOf(settings.showWeather) }
    var wallpaper by remember { mutableStateOf(settings.wallpaper) }
    val scope = rememberCoroutineScope()
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { saveWallpaper(ctx, uri) }
            if (ok) {
                // Through "none", so the car reloads the photo even if one was already set.
                settings.wallpaper = "none"; settings.wallpaper = Settings.WALLPAPER_CUSTOM
                wallpaper = Settings.WALLPAPER_CUSTOM
            } else {
                Toast.makeText(ctx, R.string.setup_wallpaper_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
    var calendar by remember { mutableStateOf(settings.showCalendar) }
    @Suppress("UNUSED_EXPRESSION") refresh
    val touch = AndroidSettings.Secure.getString(ctx.contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        .split(':').any { ComponentName.unflattenFromString(it) == ComponentName(ctx, MirrorTouchService::class.java) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Section(stringResource(R.string.setup_section_look)) {
            Choices(stringResource(R.string.setup_choice_colors), listOf(Settings.THEME_AUTO to stringResource(R.string.setup_choice_colors_auto), Settings.THEME_DARK to stringResource(R.string.setup_choice_dark), Settings.THEME_LIGHT to stringResource(R.string.setup_choice_light)), theme) {
                theme = it; settings.carTheme = it
            }
            Spacer(Modifier.height(12.dp))
            SizeChoice(settings)
            Spacer(Modifier.height(12.dp))
            AppTrimChoice(settings)
            // the car screen's animations (18.11.2026), from "Ansichten" to the look of the screen, where they are looked for
            var anim by remember { mutableStateOf(settings.carAnimations) }
            Toggle(stringResource(R.string.views_animations), stringResource(R.string.views_animations_sub), anim) { anim = it; settings.carAnimations = it }
        }
        Section(stringResource(R.string.setup_section_dashboard)) {
            Choices(stringResource(R.string.setup_choice_wallpaper), listOf("none" to stringResource(R.string.setup_wallpaper_none)) + WALLPAPER_NAMES.map { it to wallpaperName(it) } +
                listOf(Settings.WALLPAPER_CUSTOM to stringResource(R.string.setup_wallpaper_photo)), wallpaper) { choice ->
                if (choice == Settings.WALLPAPER_CUSTOM) photoPicker.launch("image/*")
                else { wallpaper = choice; settings.wallpaper = choice }
            }
            Spacer(Modifier.height(8.dp))
            Toggle(stringResource(R.string.setup_toggle_weather_title), stringResource(R.string.setup_toggle_weather_subtitle), weather) {
                weather = it; settings.showWeather = it
            }
            Toggle(stringResource(R.string.setup_toggle_calendar_title), stringResource(R.string.setup_toggle_calendar_subtitle), calendar) {
                calendar = it; settings.showCalendar = it
            }
        }
        Section(stringResource(R.string.setup_section_driving)) {
            Toggle(stringResource(R.string.setup_toggle_passenger_title), stringResource(R.string.setup_toggle_passenger_subtitle), passenger) {
                passenger = it; settings.ignoreDriving = it
            }
        }
        Section(stringResource(R.string.phone_section_title)) {
            Toggle(stringResource(R.string.phone_toggle_enable_title), stringResource(R.string.phone_toggle_enable_subtitle), phoneScreen) {
                phoneScreen = it; settings.phoneScreen = it
            }
            if (phoneScreen) {
                Check(stringResource(R.string.phone_check_touch_title), stringResource(R.string.phone_check_touch_subtitle), touch) {
                    ctx.startSafely(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS), appDetails(ctx))
                }
                Text(stringResource(R.string.phone_hint_touch_capture),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Copies the chosen photo into AARE's files, at most 1920 pixels on its longest side, as the car's wallpaper. */
private fun saveWallpaper(context: android.content.Context, uri: Uri): Boolean {
    // Written to a temporary file and renamed over the current one only once complete, so a photo
    // that can't be read or written (a full disk) leaves the current wallpaper alone.
    val tmp = java.io.File(context.filesDir, Settings.WALLPAPER_FILE + ".tmp")
    val ok = runCatching {
        val bmp = decodeWallpaper(context, uri, 1920)
        tmp.outputStream().use { check(bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        check(tmp.renameTo(java.io.File(context.filesDir, Settings.WALLPAPER_FILE)))
    }.isSuccess
    if (!ok) tmp.delete()
    return ok
}

/**
 * Decodes [uri] upright (cameras store portrait photos sideways with an EXIF orientation tag) and
 * already scaled down towards [maxPx], so a 200 MP photo is never decoded at full size.
 */
private fun decodeWallpaper(context: android.content.Context, uri: Uri, maxPx: Int): Bitmap {
    val resolver = context.contentResolver
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // ImageDecoder applies the EXIF orientation itself.
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // compress() needs a software bitmap
            val (w, h) = info.size.width to info.size.height
            val scale = maxPx.toFloat() / maxOf(w, h)
            if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
        }
    }
    // Android 8: a power-of-two sample size, then the exact scale and the EXIF rotation in one matrix.
    // Each pass opens its own stream: a content stream can be read only once.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
    val src = resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        ?: error("can't decode the photo")
    val orientation = runCatching {
        resolver.openInputStream(uri)!!.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val scale = minOf(1f, maxPx.toFloat() / maxOf(src.width, src.height))
    val m = Matrix().apply {
        postScale(scale, scale)
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(-90f)
        }
    }
    val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    if (out !== src) src.recycle()
    return out
}

/** Car audio: the capture methods, best first, and what each needs. */
@Composable
private fun AudioSettings(settings: Settings, state: ProjectionService.State, refresh: Int, recheck: () -> Unit) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(settings.carAudio) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK && r.data != null) ProjectionService.giveAudioConsent(ctx, r.resultCode, r.data!!)
        recheck()
    }
    DisposableEffect(Unit) {
        val l = Shizuku.OnRequestPermissionResultListener { _, _ -> recheck() }
        runCatching { Shizuku.addRequestPermissionResultListener(l) }
        onDispose { runCatching { Shizuku.removeRequestPermissionResultListener(l) } }
    }
    @Suppress("UNUSED_EXPRESSION") refresh
    // what the system way needs, explained here instead of on a web page
    var policyInfo by remember { mutableStateOf(false) }
    if (policyInfo) AlertDialog(
        onDismissRequest = { policyInfo = false },
        title = { Text(stringResource(R.string.audio_policy_info_title)) },
        text = { Text(stringResource(R.string.audio_policy_info_text)) },
        confirmButton = { TextButton(onClick = { policyInfo = false }) { Text(stringResource(android.R.string.ok)) } },
    )
    val policy = CaptureMethods.audioPolicyAvailable(ctx)
    val shizuku = CaptureMethods.shizukuAvailable()
    val shizukuAsk = CaptureMethods.shizukuNeedsPermission()
    val root = CaptureMethods.rootAvailable(ctx)
    val best = when {
        policy -> CaptureMethod.AUDIO_POLICY
        shizuku -> CaptureMethod.SHIZUKU_POLICY
        root -> CaptureMethod.SHIZUKU
        state.audioCapture -> CaptureMethod.PLAYBACK_CAPTURE
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Section(stringResource(R.string.audio_settings_title)) {
            Toggle(stringResource(R.string.audio_toggle_enable_title), stringResource(R.string.audio_toggle_enable_subtitle), enabled) {
                enabled = it; settings.carAudio = it
            }
            Spacer(Modifier.height(4.dp))
            Text(best?.let { stringResource(R.string.audio_using_method, methodLabel(it)) } ?: stringResource(R.string.audio_no_method),
                style = MaterialTheme.typography.bodyMedium, color = if (best == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        // Test drive B14: the prompts came in a plain robot voice. Which speech engine apps (ABRP, Mate) speak with,
        // and a way to the system's speech settings, where a natural German voice is chosen and downloaded.
        Section(stringResource(R.string.tts_section)) {
            val engine = remember { android.provider.Settings.Secure.getString(ctx.contentResolver, "tts_default_synth") }
            val google = remember { runCatching { ctx.packageManager.getApplicationInfo("com.google.android.tts", 0).enabled }.getOrDefault(false) }
            // Google's voice, or Mate's own voices as the device's engine (MateTtsService)
            val good = engine == "com.google.android.tts" || engine == ctx.packageName || (engine.isNullOrEmpty() && google)
            Check(stringResource(R.string.tts_check_title),
                // Mate's own voices as the device's engine: nothing more to load (18.11.2026: "nicht dauerhaft darauf hinweisen")
                stringResource(if (engine == ctx.packageName) R.string.tts_check_mate else if (good) R.string.tts_check_google else if (google) R.string.tts_check_other else R.string.tts_check_missing),
                good, changeLabel = stringResource(R.string.tts_check_open)) {
                if (!good && !google) ctx.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.tts")), appDetails(ctx))
                else ctx.startSafely(Intent("com.android.settings.TTS_SETTINGS"), appDetails(ctx))
            }
        }
        Section(stringResource(R.string.audio_section_methods)) {
            Check(methodLabel(CaptureMethod.AUDIO_POLICY),
                if (policy) stringResource(R.string.audio_policy_ready)
                else stringResource(R.string.audio_policy_not_ready),
                policy) { policyInfo = true }
            Check(methodLabel(if (shizuku) CaptureMethod.SHIZUKU_POLICY else CaptureMethod.SHIZUKU),
                when {
                    shizuku -> stringResource(R.string.audio_shizuku_ready)
                    root -> stringResource(R.string.audio_root_ready)
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> stringResource(R.string.audio_shizuku_needs_android12)
                    shizukuAsk -> stringResource(R.string.audio_shizuku_tap_allow)
                    CaptureMethods.shizukuInstalled(ctx) -> stringResource(R.string.audio_shizuku_not_running)
                    else -> stringResource(R.string.audio_shizuku_or_root_hint)
                },
                shizuku || root) {
                when {
                    shizukuAsk -> runCatching { Shizuku.requestPermission(SHIZUKU_REQUEST) }
                    shizuku || root -> Unit
                    // Check for root (prompts su on a rooted phone); otherwise point to Shizuku.
                    else -> CaptureMethods.checkRoot { granted ->
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if (granted) { settings.rootAudio = true; recheck() }
                            // installed but not started (after every restart): open Shizuku to start it - the download
                            // page only when it is missing (17.11.2026: "öffnet immer den Browser, obwohl installiert")
                            else if (CaptureMethods.shizukuInstalled(ctx))
                                ctx.packageManager.getLaunchIntentForPackage(CaptureMethods.SHIZUKU_PACKAGE)?.let { ctx.startSafely(it) }
                            else ctx.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                        }
                    }
                }
            }
            Check(methodLabel(CaptureMethod.PLAYBACK_CAPTURE),
                if (state.audioCapture) stringResource(R.string.audio_playback_ready)
                else stringResource(R.string.audio_playback_not_ready),
                state.audioCapture) {
                val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
                consent.launch(if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                    else mpm.createScreenCaptureIntent())
            }
        }
    }
}

private const val SHIZUKU_REQUEST = 42

/** The name of a way to bring the phone's sound to the car, in the phone's language. */
@Composable
private fun methodLabel(m: CaptureMethod): String = stringResource(when (m) {
    CaptureMethod.AUDIO_POLICY -> R.string.audio_method_policy
    CaptureMethod.SHIZUKU_POLICY -> R.string.audio_method_shizuku_policy
    CaptureMethod.SHIZUKU -> R.string.audio_method_shizuku
    CaptureMethod.PLAYBACK_CAPTURE -> R.string.audio_method_playback
})

/** A labelled row of mutually exclusive options. */
@Composable
private fun Choices(title: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    // without a title: a second row of the choice above
    if (title.isNotEmpty()) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(6.dp))
    }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(selected = value == selected, onClick = { onSelect(value) }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) {
                Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// ---------------------------------------------------------------- apps

/** Everything the car's launcher lists, as it groups it. */
private class LauncherLists(val carApps: List<CarAppInfo>, val media: List<PhoneApp>, val messaging: List<PhoneApp>)

@Composable
private fun AppsCard(settings: Settings) {
    val ctx = LocalContext.current
    var hidden by remember { mutableStateOf(settings.hiddenApps) }
    var phoneScreen by remember { mutableStateOf(settings.phoneScreen) }
    var browser by remember { mutableStateOf(settings.browser) }
    var browserUrl by remember { mutableStateOf(settings.browserUrl) }
    // Same rules as the car's launcher (CarRuntime.applyApps): an app with a car app shows once, as that.
    val lists by produceState<LauncherLists?>(null) {
        value = withContext(Dispatchers.IO) {
            val car = CarAppCatalog.query(ctx)
            val carPkgs = car.mapTo(HashSet()) { it.packageName }
            val media = AndroidAutoApps.mediaApps(ctx).filter { it.packageName !in carPkgs }
            val shown = carPkgs + media.map { it.packageName }
            LauncherLists(car, media, AndroidAutoApps.messagingApps(ctx).filter { it.packageName !in shown })
        }
    }
    fun toggle(pkg: String, visible: Boolean) {
        hidden = if (visible) hidden - pkg else hidden + pkg
        settings.hiddenApps = hidden
    }
    val l = lists
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Section(stringResource(R.string.setup_section_app_list)) {
            Text(stringResource(R.string.setup_app_list_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (l == null) {
                Text(stringResource(R.string.setup_app_list_loading), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp))
                return@Section
            }
            AppGroup(stringResource(R.string.setup_app_group_nav))
            if (l.carApps.isEmpty()) Text(stringResource(R.string.setup_app_list_no_nav), style = MaterialTheme.typography.bodyMedium)
            val navigationKind = stringResource(R.string.setup_app_kind_navigation)
            val genericKind = stringResource(R.string.setup_app_kind_generic)
            val refused = remember(l) { net.prototux.aare.host.HostRejections.refused(ctx) }
            val refusedNote = stringResource(R.string.aa_refused_note)
            l.carApps.forEach { app ->
                val kind = if (app.isNavigation) navigationKind
                    else app.categories.firstOrNull()?.let { carCategory(it) } ?: genericKind
                // I-013: an app that refused Mate in the car says so here
                AppRow(app.icon, app.label, if (app.packageName in refused) "$kind · $refusedNote" else kind, app.packageName !in hidden) { toggle(app.packageName, it) }
            }
            if (l.media.isNotEmpty()) {
                AppGroup(stringResource(R.string.setup_app_group_media))
                val mediaKind = stringResource(R.string.setup_app_kind_media)
                l.media.forEach { app -> AppRow(app.icon, app.label, mediaKind, app.packageName !in hidden) { toggle(app.packageName, it) } }
            }
            if (l.messaging.isNotEmpty()) {
                AppGroup(stringResource(R.string.setup_app_group_messaging))
                val messagingKind = stringResource(R.string.setup_app_kind_messaging)
                l.messaging.forEach { app -> AppRow(app.icon, app.label, messagingKind, app.packageName !in hidden) { toggle(app.packageName, it) } }
            }
            AppGroup(stringResource(R.string.setup_app_group_aare))
            AppRow(null, stringResource(R.string.phone_section_title), stringResource(R.string.phone_app_row_subtitle), phoneScreen) { phoneScreen = it; settings.phoneScreen = it }
            AppRow(null, stringResource(R.string.browser_app_label), stringResource(R.string.browser_app_row_subtitle), browser) { browser = it; settings.browser = it }
            if (browser) {
                OutlinedTextField(
                    value = browserUrl, onValueChange = { browserUrl = it; settings.browserUrl = it },
                    label = { Text(stringResource(R.string.browser_url_label)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(stringResource(R.string.browser_hint_parked), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (l != null) AppOrder(settings, l, hidden, phoneScreen, browser)
        StandaloneAppsSection(settings, ctx)
        val unsupported by produceState(emptyList<UnsupportedCarApp>(), l) {
            if (l != null) value = withContext(Dispatchers.IO) { AndroidAutoApps.unsupported(ctx, l.carApps.map { it.packageName }) }
        }
        if (unsupported.isNotEmpty()) {
            Section(stringResource(R.string.setup_section_unsupported_apps)) {
                unsupported.forEach { UnsupportedRow(it) }
            }
        }
        Text(stringResource(R.string.setup_hint_allowlist),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The order of the car's app list: the visible apps, with up and down buttons. */
@Composable
private fun AppOrder(settings: Settings, l: LauncherLists, hidden: Set<String>, phoneScreen: Boolean, browser: Boolean) {
    // Keys as CarRuntime.orderKey: packages, or the built-in apps' ids.
    val all = buildList {
        l.carApps.forEach { add(Triple(it.packageName, it.label, it.icon)) }
        l.media.forEach { add(Triple(it.packageName, it.label, it.icon)) }
        l.messaging.forEach { add(Triple(it.packageName, it.label, it.icon)) }
    }.filter { it.first !in hidden } + listOfNotNull(
        Triple(CarRuntime.PHONE_ID, stringResource(R.string.setup_app_entry_phone), null),
        Triple(CarRuntime.SETTINGS_ID, stringResource(R.string.setup_app_entry_settings), null),
        Triple(CarRuntime.NOTIFICATIONS_ID, stringResource(R.string.setup_app_entry_notifications), null),
        if (phoneScreen) Triple(CarRuntime.PHONE_SCREEN_ID, stringResource(R.string.phone_section_title), null) else null,
        if (browser) Triple(CarRuntime.BROWSER_ID, stringResource(R.string.browser_app_label), null) else null,
    )
    var order by remember { mutableStateOf(settings.appOrder) }
    val index = order.withIndex().associate { (i, k) -> k to i }
    val sorted = all.sortedWith(compareBy({ index[it.first] ?: Int.MAX_VALUE }, { it.second.lowercase() }))
    fun move(i: Int, by: Int) {
        val keys = sorted.map { it.first }.toMutableList()
        val j = (i + by).coerceIn(0, keys.lastIndex)
        keys.add(j, keys.removeAt(i))
        // Hidden apps keep their saved slots, so showing one again puts it back where it was:
        // the shown keys fill their old slots in the new order, new ones go at the end.
        val queue = ArrayDeque(keys)
        val shown = keys.toSet()
        val merged = order.mapNotNull { if (it in shown) queue.removeFirstOrNull() else it } + queue
        order = merged; settings.appOrder = merged
    }
    Section(stringResource(R.string.setup_section_app_order)) {
        sorted.forEachIndexed { i, (_, label, icon) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) Image(icon.asImageBitmap(), null, Modifier.size(32.dp).clip(CircleShape))
                else Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer))
                Spacer(Modifier.width(12.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { move(i, -1) }, enabled = i > 0) { Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.common_up)) }
                IconButton(onClick = { move(i, 1) }, enabled = i < sorted.lastIndex) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.common_down)) }
            }
        }
    }
}

@Composable
private fun AppGroup(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

/** Opt-in: launch ordinary phone apps on the car screen (parked only; needs root or Shizuku). */
@Composable
private fun StandaloneAppsSection(settings: Settings, ctx: android.content.Context) {
    var enabled by remember { mutableStateOf(settings.standaloneApps) }
    var allowed by remember { mutableStateOf(settings.standaloneAppPackages) }
    val available = remember { AppLauncher.available(ctx) }
    Section(stringResource(R.string.standalone_section_title)) {
        Text(stringResource(R.string.standalone_section_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
        Toggle(stringResource(R.string.standalone_toggle_title), stringResource(R.string.standalone_toggle_subtitle), enabled) {
            enabled = it; settings.standaloneApps = it
        }
        if (enabled && !available) {
            Text(stringResource(R.string.standalone_need_root), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        if (enabled) {
            val apps by produceState<List<Triple<String, String, android.graphics.Bitmap?>>?>(null) {
                value = withContext(Dispatchers.IO) { launchableApps(ctx) }
            }
            val list = apps
            if (list == null) {
                Text(stringResource(R.string.setup_app_list_loading), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp))
            } else {
                Spacer(Modifier.height(8.dp))
                list.forEach { (pkg, label, icon) ->
                    AppRow(icon, label, pkg, pkg in allowed) { on ->
                        allowed = if (on) allowed + pkg else allowed - pkg
                        settings.standaloneAppPackages = allowed
                    }
                }
            }
        }
    }
}

/** Every app with a launcher icon, except AARE itself, for the standalone allow-list. */
private fun launchableApps(ctx: android.content.Context): List<Triple<String, String, android.graphics.Bitmap?>> {
    val pm = ctx.packageManager
    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .mapNotNull { it.activityInfo?.packageName }
        .filter { it != ctx.packageName }
        .distinct()
        .mapNotNull { pkg ->
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
            val icon = runCatching { pm.getApplicationIcon(info).toBitmap(96, 96) }.getOrNull()
            Triple(pkg, pm.getApplicationLabel(info).toString(), icon)
        }.sortedBy { it.second.lowercase() }
}

@Composable
private fun AppRow(icon: android.graphics.Bitmap?, label: String, kind: String, visible: Boolean, onVisible: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onVisible(!visible) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Image(icon.asImageBitmap(), null, Modifier.size(40.dp).clip(CircleShape))
        else Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text(label.take(1), color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(kind, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = visible, onCheckedChange = onVisible)
    }
}

@Composable
private fun UnsupportedRow(app: UnsupportedCarApp) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        app.icon?.let { Image(it.asImageBitmap(), null, Modifier.size(40.dp).clip(CircleShape)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge)
            Text(app.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- assistant

/** Which assistant the steering-wheel voice button opens: the phone's default, or one picked here. */
@Composable
private fun AssistantCard(settings: Settings) {
    val ctx = LocalContext.current
    val choices = remember { Assistants.query(ctx, CarAppCatalog.query(ctx)) }
    val phoneDefault = remember { Assistants.phoneDefault(ctx) }
    var chosen by remember { mutableStateOf(settings.assistantPackage) }
    fun choose(pkg: String?) { chosen = pkg; settings.assistantPackage = pkg }
    Section(stringResource(R.string.assistant_settings_title)) {
        // 18.11.2026: where the bar's microphone goes - a tap Mate's own voice control, a long press the assistant chosen here
        Text(stringResource(R.string.assistant_mic_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        val defaultLabel = phoneDefault?.let { pkg -> choices.firstOrNull { it.packageName == pkg }?.label ?: Assistants.label(ctx, pkg) ?: pkg }
        AssistantRow(
            title = stringResource(R.string.assistant_same_as_phone),
            subtitle = defaultLabel ?: stringResource(R.string.assistant_no_default),
            app = null, selected = chosen == null, onSelect = { choose(null) },
        )
        val hasCarAppSubtitle = stringResource(R.string.assistant_has_car_app_subtitle)
        val opensOnPhoneSubtitle = stringResource(R.string.assistant_opens_on_phone)
        choices.forEach { app ->
            AssistantRow(
                title = app.label,
                subtitle = if (app.hasCarApp) hasCarAppSubtitle else opensOnPhoneSubtitle,
                app = app, selected = chosen == app.packageName, onSelect = { choose(app.packageName) },
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.assistant_hint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AssistantRow(title: String, subtitle: String, app: AssistantApp?, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        app?.icon?.let {
            Image(it.asImageBitmap(), null, Modifier.size(40.dp).clip(CircleShape))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- certificates

@Composable
private fun CertificatesCard(certs: CertificateStore, state: ProjectionService.State) {
    val ctx = LocalContext.current
    var entries by remember { mutableStateOf(certs.entries()) }
    // Saveable: the system picker can outlive the activity, and the key picker's result needs the certificate.
    var pickCert by rememberSaveable { mutableStateOf<Uri?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var p12 by rememberSaveable { mutableStateOf<Uri?>(null) }
    var downloading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { keyUri ->
        val certUri = pickCert ?: return@rememberLauncherForActivityResult
        if (keyUri == null) return@rememberLauncherForActivityResult
        error = runCatching {
            val cert = ctx.contentResolver.openInputStream(certUri)!!.use { it.readBytes().decodeToString() }
            val key = ctx.contentResolver.openInputStream(keyUri)!!.use { it.readBytes().decodeToString() }
            certs.importPem(displayName(ctx, certUri), cert, key)
            entries = certs.entries()
        }.exceptionOrNull()?.message
    }
    val certPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Document URIs (msf:1234) carry no extension: ask the provider for the file name and type.
        val name = displayName(ctx, uri)
        val type = ctx.contentResolver.getType(uri)
        if (name.endsWith(".p12", true) || name.endsWith(".pfx", true) || type == "application/x-pkcs12") p12 = uri
        else { pickCert = uri; keyPicker.launch(arrayOf("*/*")) }
    }
    Section(stringResource(R.string.cert_section_title)) {
        Text(stringResource(R.string.cert_hint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        entries.forEach { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.name, style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.cert_entry_subtitle, e.subject.take(60), DateFormat.getDateInstance().format(e.notAfter)), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (e.removable) TextButton(onClick = { certs.remove(e); entries = certs.entries() }) { Text(stringResource(R.string.cert_action_remove)) }
            }
        }
        Row {
            TextButton(onClick = { certPicker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.cert_action_import)) }
            TextButton(enabled = !downloading, onClick = {
                downloading = true
                error = null
                scope.launch {
                    error = runCatching {
                        val d = withContext(Dispatchers.IO) { CertificateDownload.fetch() }
                        certs.importDownloaded(d)
                        entries = certs.entries()
                    }.exceptionOrNull()?.let { downloadError(ctx, it) }
                    downloading = false
                }
            }) { Text(stringResource(if (downloading) R.string.cert_action_downloading else R.string.cert_action_download)) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    p12?.let { uri ->
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { p12 = null },
            title = { Text(stringResource(R.string.cert_p12_dialog_title)) },
            text = { OutlinedTextField(password, { password = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    error = runCatching { certs.importPkcs12(displayName(ctx, uri), uri, password.toCharArray()); entries = certs.entries() }
                        .exceptionOrNull()?.message
                    p12 = null
                }) { Text(stringResource(R.string.cert_action_import_confirm)) }
            },
            dismissButton = { TextButton(onClick = { p12 = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** What to tell the user when Download certificate fails. */
private fun downloadError(ctx: Context, e: Throwable): String = when (e) {
    is CertificateDownload.Unavailable -> ctx.getString(R.string.cert_download_unavailable, CertificateDownload.SOURCE)
    is CertificateDownload.Invalid -> ctx.getString(R.string.cert_download_invalid, e.message)
    is java.io.IOException -> ctx.getString(R.string.cert_download_network, e.message ?: e.toString())
    else -> e.message ?: e.toString()
}

/** The picked file's name as the provider shows it, for naming an imported certificate. */
private fun displayName(ctx: Context, uri: Uri): String =
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: ctx.getString(R.string.cert_imported_fallback)

/** One label/value line; renders nothing when the car hasn't reported this value yet. */
@Composable
private fun DataRow(label: String, value: String?) {
    if (value == null) return
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
    }
}

/**
 * A read-only view of everything the head unit tells AARE about the car: its identity and the live
 * value of every sensor it reports (an "OBD-ish" viewer, but for the AAP sensor stream). All values
 * come from [CarData.state] and [ProjectionService.state], which update themselves.
 */
@Composable
private fun CarDataCard() {
    val car by CarData.state.collectAsState()
    val proj by ProjectionService.state.collectAsState()
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    var inCar by remember { mutableStateOf(settings.carDataApp) }
    Section(stringResource(R.string.setup_page_car_data_title)) {
        Toggle(stringResource(R.string.car_data_show_in_car_title), stringResource(R.string.car_data_show_in_car_subtitle), inCar) { inCar = it; settings.carDataApp = it }
        // the detailed log here too, before a car is connected (17.11.2026: "In den Fahrzeugeinstellungen kann man den
        // erweiterten Log gar nicht einstellen ... würde ich gerne auch vorher einstellen können"); the same switch as
        // under Erweitert, Verbindungsprotokoll
        var verbose by remember { mutableStateOf(settings.verboseProtocolLog) }
        Toggle(stringResource(R.string.setup_toggle_verbose_log_title), stringResource(R.string.setup_toggle_verbose_log_subtitle), verbose) {
            verbose = it; settings.verboseProtocolLog = it; SessionAapLog.verbose = it
        }
        if (!car.connected) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.car_data_not_connected), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (!car.connected) return
    Spacer(Modifier.height(16.dp))
    val on = stringResource(R.string.car_data_on); val off = stringResource(R.string.car_data_off)
    val yes = stringResource(R.string.car_data_yes); val no = stringResource(R.string.car_data_no)
    fun onOff(b: Boolean?) = b?.let { if (it) on else off }
    fun yesNo(b: Boolean?) = b?.let { if (it) yes else no }

    Section(stringResource(R.string.car_data_section_car)) {
        val id = car.identity
        DataRow(stringResource(R.string.car_data_identity),
            id?.let { listOfNotNull(it.make.ifBlank { null }, it.model.ifBlank { null }, it.year?.toString()).joinToString(" ").ifBlank { null } })
        DataRow(stringResource(R.string.car_data_head_unit), proj.headUnit.ifBlank { null })
        DataRow(stringResource(R.string.car_data_connection), proj.transport.ifBlank { null })
        DataRow(stringResource(R.string.car_data_video), proj.video.ifBlank { null })
        if (car.advertised.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.car_data_advertised), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(car.advertised.map { it.name.removePrefix("SENSOR_") }.sorted().joinToString(", "),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(16.dp))
    Section(stringResource(R.string.car_data_section_live)) {
        DataRow(stringResource(R.string.car_data_driving), car.drivingStatus?.let { if (it.value == 0) stringResource(R.string.car_data_parked) else stringResource(R.string.car_data_moving) })
        DataRow(stringResource(R.string.car_data_night), car.nightMode?.let { if (it.value) stringResource(R.string.car_data_night_on) else stringResource(R.string.car_data_night_off) })
        DataRow(stringResource(R.string.car_data_speed), car.speed?.let { "%.0f km/h".format(it.value.metersPerSecond * 3.6f) })
        DataRow(stringResource(R.string.car_data_cruise), car.speed?.value?.cruiseSetSpeed?.let { "$it" })
        DataRow(stringResource(R.string.car_data_rpm), car.rpm?.let { "%.0f".format(it.value) })
        DataRow(stringResource(R.string.car_data_gear), car.gearName)
        DataRow(stringResource(R.string.car_data_parking_brake), onOff(car.parkingBrake?.value))
        DataRow(stringResource(R.string.car_data_odometer), car.odometer?.let { o ->
            "%.0f km".format(o.value.km) + (o.value.tripKm?.let { " · trip %.0f km".format(it) } ?: "") })
        DataRow(stringResource(R.string.car_data_fuel), car.fuel?.let { f ->
            listOfNotNull(f.value.levelPercent?.let { "$it%" }, f.value.rangeKm?.let { "$it km" }, if (f.value.low == true) stringResource(R.string.car_data_low) else null)
                .joinToString(" · ").ifBlank { null } })
        DataRow(stringResource(R.string.car_data_compass), car.compass?.let { "%.0f°".format(it.value.bearingDeg) })
        DataRow(stringResource(R.string.car_data_satellites), car.satellites?.let { s -> "${s.value.inUse}" + (s.value.inView?.let { " / $it" } ?: "") })
        DataRow(stringResource(R.string.car_data_environment), car.environment?.let { e ->
            listOfNotNull(e.value.temperatureC?.let { "%.1f °C".format(it) }, e.value.pressureKpa?.let { "%.0f kPa".format(it) }, e.value.rain?.let { if (it > 0) stringResource(R.string.car_data_rain) else null })
                .joinToString(" · ").ifBlank { null } })
        DataRow(stringResource(R.string.car_data_climate), car.hvac?.let { h ->
            listOfNotNull(h.value.targetC?.let { "→ %.0f °C".format(it) }, h.value.currentC?.let { "%.0f °C".format(it) }).joinToString(" · ").ifBlank { null } })
        DataRow(stringResource(R.string.car_data_steering), car.deadReckoning?.value?.steeringAngleDeg?.let { "%.0f°".format(it) })
        DataRow(stringResource(R.string.car_data_tyres), car.tirePressuresKpa?.let { t -> t.value.joinToString(" · ") { "%.0f".format(it) } + " kPa" })
        DataRow(stringResource(R.string.car_data_doors), car.doors?.let { d ->
            val open = buildList {
                if (d.value.hoodOpen == true) add(stringResource(R.string.car_data_hood))
                if (d.value.trunkOpen == true) add(stringResource(R.string.car_data_trunk))
                repeat(d.value.doorsOpen.count { it }) { add(stringResource(R.string.car_data_door)) }
            }
            if (open.isEmpty()) stringResource(R.string.car_data_closed) else open.joinToString(", ") })
        DataRow(stringResource(R.string.car_data_lights), car.lights?.let { l ->
            listOfNotNull(l.value.headLights?.name?.lowercase(), l.value.turn?.takeIf { it != CarData.Turn.NONE }?.name?.lowercase(),
                if (l.value.hazard == true) stringResource(R.string.car_data_hazard) else null).joinToString(" · ").ifBlank { null } })
        DataRow(stringResource(R.string.car_data_passenger), yesNo(car.passengerPresent?.value))
        DataRow(stringResource(R.string.car_data_toll), yesNo(car.tollCardPresent?.value))
        DataRow(stringResource(R.string.car_data_accelerometer), car.accelerometer?.let { a -> xyz(a.value) }?.let { "$it m/s²" })
        DataRow(stringResource(R.string.car_data_gyroscope), car.gyroscope?.let { g -> xyz(g.value) }?.let { "$it rad/s" })
        DataRow(stringResource(R.string.car_data_dtc), car.diagnostics?.value?.takeIf { it.isNotEmpty() }?.joinToString(" ") { "%02x".format(it) })
    }
}

private fun xyz(v: CarData.Vector3): String? =
    listOf(v.x, v.y, v.z).let { if (it.all { c -> c == null }) null else it.joinToString(", ") { c -> c?.let { "%.2f".format(it) } ?: "–" } }

@Composable
private fun VehicleOverridesCard() {
    val ctx = LocalContext.current
    val file = remember { net.prototux.aare.car.VehicleOverrides.file(ctx) }
    var count by remember { mutableIntStateOf(net.prototux.aare.car.VehicleOverrides.ruleCount(ctx)) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
        }.onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_SHORT).show() }
        count = net.prototux.aare.car.VehicleOverrides.ruleCount(ctx)
    }
    Section(stringResource(R.string.vehicles_section_title)) {
        Text(stringResource(R.string.vehicles_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.vehicles_rule_count, count), style = MaterialTheme.typography.bodyMedium)
        Text(file.absolutePath, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        Row {
            TextButton(onClick = { import.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text(stringResource(R.string.vehicles_import)) }
            TextButton(onClick = {
                runCatching { file.parentFile?.mkdirs(); file.writeText(net.prototux.aare.car.VehicleOverrides.template()) }
                    .onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_SHORT).show() }
                count = net.prototux.aare.car.VehicleOverrides.ruleCount(ctx)
                Toast.makeText(ctx, file.absolutePath, Toast.LENGTH_LONG).show()
            }) { Text(stringResource(R.string.vehicles_template)) }
        }
    }
}

@Composable
private fun LogCard() {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val log by DiagLog.lines.collectAsState()
    val title = stringResource(R.string.setup_section_connection_log)
    // Saves the log as a file the user picks (20.11.2026: "Die teilen Funktion für den log kann raus ... bei android kann
    // man darüber gar nicht kopieren. Nicht mal speichern ... der herkömmliche Weg über speichern. Dann kann Kopie ganz
    // raus."): the version for reading through (DiagLog.digest); the issue log is the same without private data (LogScrub)
    var issue by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val lines = DiagLog.lines.value
            val text = if (issue) {
                val known = privateValues(ctx)
                DiagLog.digest(lines, net.prototux.aare.BuildConfig.VERSION_NAME) { net.prototux.aare.debug.LogScrub.scrub(it, known) }
            } else DiagLog.digest(lines, net.prototux.aare.BuildConfig.VERSION_NAME)
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        }.onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_SHORT).show() }
    }
    fun saveAs(asIssue: Boolean) {
        issue = asIssue
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date())
        save.launch(if (asIssue) "mate-issue-log-$stamp.txt" else "mate-log-$stamp.txt")
    }
    Section(title) {
        // Always shown, so the trace can be turned on before connecting.
        var verbose by remember { mutableStateOf(settings.verboseProtocolLog) }
        Toggle(stringResource(R.string.setup_toggle_verbose_log_title), stringResource(R.string.setup_toggle_verbose_log_subtitle), verbose) {
            verbose = it; settings.verboseProtocolLog = it; SessionAapLog.verbose = it
        }
        if (log.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            // by level and area (19.11.2026: "... damit man die besser überblicken kann ... zum Filtern nach Problemen ...
            // Audio-Tags, Navi-Tags, damit man danach suchen kann"): all, the problems (warnings and errors), or one area
            val entries = remember(log) { log.map(DiagLog::parse) }
            val areas = remember(entries) { entries.map { it.area }.distinct().sorted() }
            var filter by remember { mutableStateOf<String?>(null) }   // null: all, "!": problems, else an area
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.material3.FilterChip(filter == null, { filter = null }, { Text(stringResource(R.string.setup_log_filter_all)) })
                androidx.compose.material3.FilterChip(filter == "!", { filter = "!" }, { Text(stringResource(R.string.setup_log_filter_problems,
                    entries.count { it.level != DiagLog.Level.INFO })) })
                areas.forEach { a -> androidx.compose.material3.FilterChip(filter == a, { filter = a }, { Text(a) }) }
            }
            val shown = entries.filter { e -> when (filter) { null -> true; "!" -> e.level != DiagLog.Level.INFO; else -> e.area == filter } }
            shown.takeLast(60).forEach { e ->
                Text("${e.stamp}${(e.level ?: DiagLog.Level.INFO).tag} ${e.area.padEnd(6)} ${e.text}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    color = when (e.level) { DiagLog.Level.ERROR -> MaterialTheme.colorScheme.error
                        DiagLog.Level.WARN -> androidx.compose.ui.graphics.Color(0xFFB26A00)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant })
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilledTonalButton(onClick = { saveAs(false) }) { Text(stringResource(R.string.setup_log_save_file)) }
                androidx.compose.material3.OutlinedButton(onClick = { saveAs(true) }) { Text(stringResource(R.string.setup_log_save_issue)) }
            }
            Text(stringResource(R.string.setup_log_save_issue_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Row {
                TextButton(onClick = { DiagLog.clear() }) { Text(stringResource(R.string.common_clear)) }
            }
        }
    }
}

/** Values Mate knows to be private, taken out of the issue log word for word (LogScrub). */
private fun privateValues(ctx: android.content.Context): List<String> {
    val s = Settings(ctx)
    val own = listOf(s.mapServer, s.mapToken, s.mapDevice, s.phoneBluetoothAddress, s.browserUrl, s.autoReplyText, s.instanceId)
    val places = runCatching { net.prototux.aare.places.Places.load(ctx).places.flatMap { listOf(it.name, it.address) } }.getOrDefault(emptyList())
    return (own + places + places.flatMap { it.split(',') }.map { it.trim() }).filter { it.isNotBlank() }.distinct()
}

@Composable
private fun About() {
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        // both versions (18.11.2026: "unten die AARE-Version und unsere Version. Beide in der App unten angezeigt. Dass man
        // dann auch, wenn AARE eine neue Version rausbringt, das irgendwie auf einen Stand hält")
        Text(stringResource(R.string.setup_about_versions, net.prototux.aare.BuildConfig.VERSION_NAME,
            net.prototux.aare.BuildConfig.AARE_BASE, net.prototux.aare.BuildConfig.AARE_SEEN),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.setup_about_text, net.prototux.aare.BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Einstellungen übertragen: save all of Mate's settings to a file and take them in again (MateTransfer). */
@Composable
private fun TransferPage() {
    val ctx = LocalContext.current
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    // what is restored right now (17.11.2026: "Wäre gut zu sehen, was er gerade entpackt")
    var current by remember { mutableStateOf<String?>(null) }
    val mapsBytes = remember { net.prototux.aare.MateTransfer.mapsBytes(ctx) }
    var withMaps by remember { mutableStateOf(mapsBytes > 0) }
    val voicesBytes = remember { net.prototux.aare.MateTransfer.voicesBytes(ctx) }
    var withVoices by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun mb(b: Long) = "%.0f MB".format(b / 1_048_576.0)
    // on a worker thread with the bytes so far: the offline maps are gigabytes
    fun run(work: ((Long) -> Unit) -> Int, done: (Int) -> String) {
        busy = ""; note = null
        scope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { work { b -> scope.launch { busy = mb(b) } } }
            }
            busy = null; current = null
            note = r.map(done).getOrElse { ctx.getString(R.string.transfer_failed, it.message ?: it.toString()) }
        }
    }
    val save = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) run({ p -> net.prototux.aare.MateTransfer.export(ctx, uri, withMaps, p, withVoices) }) { ctx.getString(R.string.transfer_saved, it) }
    }
    val load = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run({ p -> net.prototux.aare.MateTransfer.import(ctx, uri, p) { w -> scope.launch { current = w } } }) { ctx.getString(R.string.transfer_loaded, it) }
    }
    androidx.compose.foundation.layout.Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.transfer_intro), style = MaterialTheme.typography.bodyMedium)
        // the offline maps too (the user's wish of 17.11.2026: "Offline Karten wäre auch gut")
        if (mapsBytes > 0) Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(withMaps, { withMaps = it })
            Text(stringResource(R.string.transfer_maps, mb(mapsBytes)), style = MaterialTheme.typography.bodyLarge)
        }
        if (voicesBytes > 0) Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(withVoices, { withVoices = it })
            Text(stringResource(R.string.transfer_voices, mb(voicesBytes)), style = MaterialTheme.typography.bodyLarge)
        }
        androidx.compose.material3.Button(enabled = busy == null, onClick = { save.launch("mate-einstellungen-" + java.time.LocalDate.now() + ".zip") }) { Text(stringResource(R.string.transfer_save)) }
        busy?.let { Text(stringResource(R.string.transfer_busy, it), style = MaterialTheme.typography.bodyLarge) }
        current?.let { Text(stringResource(R.string.transfer_now, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        androidx.compose.material3.OutlinedButton(enabled = busy == null, onClick = { load.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text(stringResource(R.string.transfer_load)) }
        Text(stringResource(R.string.transfer_secret_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        note?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary) }
        if (note != null && note!!.startsWith(ctx.getString(R.string.transfer_loaded_prefix))) androidx.compose.material3.Button(onClick = {
            // the settings are read when the app starts: started again
            ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { i -> ctx.startActivity(android.content.Intent.makeRestartActivityTask(i.component)) }
            Runtime.getRuntime().exit(0)
        }) { Text(stringResource(R.string.transfer_restart)) }
    }
}

/** O211 (17.11.2026): an Android Auto category as a word - the list showed "Category_projection" and "Settings". */
@Composable
private fun carCategory(raw: String): String {
    val c = raw.substringAfterLast('.').lowercase().removePrefix("category_")
    return when (c) {
        "projection" -> stringResource(R.string.car_category_projection)
        "settings" -> stringResource(R.string.car_category_settings)
        "poi" -> stringResource(R.string.car_category_poi)
        "charging" -> stringResource(R.string.car_category_charging)
        "parking" -> stringResource(R.string.car_category_parking)
        "iot" -> stringResource(R.string.car_category_iot)
        "weather" -> stringResource(R.string.car_category_weather)
        "calling" -> stringResource(R.string.car_category_calling)
        "messaging" -> stringResource(R.string.car_category_messaging)
        "video" -> stringResource(R.string.car_category_video)
        "feature_cluster" -> stringResource(R.string.car_category_feature_cluster)
        "navigation" -> stringResource(R.string.setup_app_kind_navigation)
        "media", "audio" -> stringResource(R.string.car_category_media)
        else -> c.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

/**
 * O226, Navi L-004, L-005: the chargers charged at - stars, the details at leisure, "neu" for charges not looked at yet.
 */
@Composable
private fun VisitedChargers() = Section(stringResource(R.string.routing_visits_title)) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var visits by remember { mutableStateOf(net.prototux.aare.ui.car.navi.ChargerVisits.all(ctx)) }
    // Navi L-005: what was new when the page opened stays marked while it is open; the badge goes
    val fresh = remember { visits.filter { it.fresh }.map { it.at }.toSet() }
    LaunchedEffect(Unit) { net.prototux.aare.ui.car.navi.ChargerVisits.allSeen(ctx) }
    var open by remember { mutableStateOf<org.maplibre.spatialk.geojson.Position?>(null) }
    Text(stringResource(R.string.routing_visits_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (visits.isEmpty()) Text(stringResource(R.string.routing_visits_none), style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 10.dp))
    visits.forEach { v ->
        Row(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(8.dp))
            .clickable { open = if (open == v.at) null else v.at }, verticalAlignment = Alignment.CenterVertically) {
            net.prototux.aare.ui.car.navi.NetworkLogos.of(v.network)?.let { logo ->
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(logo), null,
                    Modifier.height(24.dp).width(40.dp).clip(RoundedCornerShape(4.dp))
                        .background(androidx.compose.ui.res.colorResource(R.color.mate_logo_plate)).padding(2.dp))
                Spacer(Modifier.width(10.dp))
            }
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(v.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    if (v.at in fresh) Text(stringResource(R.string.routing_visits_new), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(start = 8.dp)
                            .clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 8.dp, vertical = 2.dp))
                }
                Text(stringResource(R.string.routing_visits_line, v.visits,
                    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(v.lastMillis))),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // five stars; the same star again takes the rating off
            (1..5).forEach { n ->
                androidx.compose.material3.Icon(if (n <= v.rating) androidx.compose.material.icons.Icons.Rounded.Star else androidx.compose.material.icons.Icons.Rounded.StarBorder,
                    null, tint = if (n <= v.rating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable {
                        net.prototux.aare.ui.car.navi.ChargerVisits.rate(ctx, v.at, if (v.rating == n) 0 else n)
                        visits = net.prototux.aare.ui.car.navi.ChargerVisits.all(ctx) }.padding(4.dp))
            }
        }
        // Navi L-004, E-003: the details, at leisure - power, price, whether all worked; none is a must
        if (open == v.at) androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, bottom = 4.dp)) {
            fun put(power: Int = v.power, price: Int = v.price, worked: Int = v.worked) {
                net.prototux.aare.ui.car.navi.ChargerVisits.details(ctx, v.at, power, price, worked)
                visits = net.prototux.aare.ui.car.navi.ChargerVisits.all(ctx)
            }
            Choices(stringResource(R.string.routing_visit_power), listOf("1" to stringResource(R.string.routing_visit_power_good),
                "2" to stringResource(R.string.routing_visit_power_mid), "3" to stringResource(R.string.routing_visit_power_poor)),
                v.power.toString()) { put(power = if (it == v.power.toString()) 0 else it.toInt()) }
            Spacer(Modifier.height(10.dp))
            Choices(stringResource(R.string.routing_visit_price), listOf("1" to stringResource(R.string.routing_visit_price_good),
                "2" to stringResource(R.string.routing_visit_price_mid), "3" to stringResource(R.string.routing_visit_price_poor)),
                v.price.toString()) { put(price = if (it == v.price.toString()) 0 else it.toInt()) }
            Spacer(Modifier.height(10.dp))
            Choices(stringResource(R.string.routing_visit_worked), listOf("1" to stringResource(R.string.routing_visit_worked_yes),
                "2" to stringResource(R.string.routing_visit_worked_no)),
                v.worked.toString()) { put(worked = if (it == v.worked.toString()) 0 else it.toInt()) }
            // not charged there after all (19.11.2026: "bei dem anderen Lader habe ich halt nicht geladen und den möchte
            // ich dann auch in dem Menü, wo es um die Bewertung gibt, in der App dann auch löschen können")
            TextButton(onClick = {
                net.prototux.aare.ui.car.navi.ChargerVisits.remove(ctx, v.at)
                net.prototux.aare.DiagLog.add("charge visit removed by the driver: ${v.name}", area = "CHARGE")
                open = null; visits = net.prototux.aare.ui.car.navi.ChargerVisits.all(ctx)
            }, modifier = Modifier.padding(top = 6.dp)) { Text(stringResource(R.string.routing_visit_remove), color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** "Klingelton im Auto": which ring Mate plays in the car while a call comes in, how loud; a tap plays it briefly. */
@Composable
private fun RingtoneSection(settings: net.prototux.aare.Settings) = Section(stringResource(R.string.ringtone_title)) {
    val ctx = LocalContext.current
    var tone by remember { mutableStateOf(settings.carRingtone) }
    var vol by remember { mutableStateOf(settings.carRingtoneVolume.toString()) }
    fun sample() = net.prototux.aare.car.CarRingtone.start(ctx, net.prototux.aare.car.CarRingtone.tone(tone), vol.toInt() / 100f, sample = true)
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { net.prototux.aare.car.CarRingtone.stop(null) } }
    Text(stringResource(R.string.ringtone_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(10.dp))
    Choices(stringResource(R.string.ringtone_tone), listOf("marimba" to stringResource(R.string.ringtone_marimba), "classic" to stringResource(R.string.ringtone_classic),
        "bell" to stringResource(R.string.ringtone_bell), "soft" to stringResource(R.string.ringtone_soft)), tone) { tone = it; settings.carRingtone = it; sample() }
    Spacer(Modifier.height(8.dp))
    Choices("", listOf("tablet" to stringResource(R.string.ringtone_tablet), "off" to stringResource(R.string.ringtone_off)), tone) {
        tone = it; settings.carRingtone = it; if (it == "off") net.prototux.aare.car.CarRingtone.stop(null) else sample() }
    if (tone != "off") {
        Spacer(Modifier.height(12.dp))
        Choices(stringResource(R.string.ringtone_volume), listOf("40", "70", "100").map { it to "$it %" }, vol) { vol = it; settings.carRingtoneVolume = it.toInt(); sample() }
    }
}

/**
 * Asks once to keep Mate out of Android's battery optimisation, shown only while it is in it: with the screen off
 * Android may slow an optimised app down, and the sound to the car crackles (19.11.2026: "dass wenn die Mate-App im
 * Hintergrund läuft und das Gerät in Standby geht, dass das Telefon dann nicht einfach drosseln darf").
 */
@Composable
private fun BatteryOptimisationCard() {
    val ctx = LocalContext.current
    var ignoring by remember { mutableStateOf(net.prototux.aare.service.PowerState.ignoringOptimisation(ctx)) }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) ignoring = net.prototux.aare.service.PowerState.ignoringOptimisation(ctx) }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    if (ignoring) return
    androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.power_opt_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.power_opt_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.material3.FilledTonalButton(onClick = {
                runCatching {
                    @android.annotation.SuppressLint("BatteryLife")
                    val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + ctx.packageName))
                    ctx.startActivity(i)
                }.onFailure { runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
            }) { Text(stringResource(R.string.power_opt_button)) }
        }
    }
}

