package net.prototux.aare.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.location.Location
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.navigation.model.Trip
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.prototux.aare.DiagLog
import net.prototux.aare.R
import net.prototux.aare.Settings
import net.prototux.aare.car.Calls
import net.prototux.aare.car.DashboardInfo
import net.prototux.aare.media.MediaHub
import net.prototux.aare.mirror.MirrorScreen
import net.prototux.aare.notify.CarNotificationListener
import net.prototux.aare.notify.MessagingScreen
import net.prototux.aare.launch.AppLauncher
import net.prototux.aare.notify.PhoneGuidance
import net.prototux.aare.ui.car.BrowserScreen
import net.prototux.aare.ui.car.CarActions
import net.prototux.aare.ui.car.CarAppEntry
import net.prototux.aare.ui.car.CarAppKind
import net.prototux.aare.ui.car.CarCall
import net.prototux.aare.ui.car.CarNotice
import net.prototux.aare.ui.car.CarScreen
import net.prototux.aare.ui.car.CarSettingsScreen
import net.prototux.aare.ui.car.CarShell
import net.prototux.aare.ui.car.CarUiState
import net.prototux.aare.ui.car.NotificationsScreen
import net.prototux.aare.ui.car.NowPlaying
import net.prototux.aare.ui.car.CarDataScreen
import net.prototux.aare.ui.car.PhoneScreen
import net.prototux.aare.ui.car.StandaloneAppScreen
import net.prototux.aare.ui.theme.CarTheme
import net.prototux.aare.ui.theme.LocalCarDimens

/**
 * Everything on the car screen that doesn't depend on how it reaches the car: the app list,
 * hosted car apps, media, notices and the root composable. A projection session (real head unit)
 * and the on-phone preview both drive one of these.
 *
 * Main thread only, except the properties documented as thread-safe for [HostEnvironment].
 */
class CarRuntime(
    val context: Context,
    private val scope: CoroutineScope,
    val settings: Settings,
    val media: MediaHub,
    /** Release [media] in [stop]: true when this runtime created it (the preview), false when shared. */
    private val ownsMedia: Boolean = true,
    /** The car session's runtime (false for the on-phone preview): it takes [active] over a preview. */
    private val primary: Boolean = true,
    /** Offer the "Phone screen" entry at all (off for sessions from the developer TCP server unless opted in). */
    private val phoneScreenAllowed: Boolean = true,
) : HostEnvironment {

    val ui = CarUiState()
    val icons = IconLoader(context, this)
    private val main = Handler(Looper.getMainLooper())

    /** Hosted car app connections by entry id. */
    private val connections = mutableStateMapOf<String, CarAppConnection>()
    /** Apps that finished themselves: only an explicit open ([connection]) starts them again, not the composition. */
    private val finished = HashSet<String>()
    private val carApps = HashMap<String, CarAppInfo>()
    private val visibleCount = HashMap<CarAppConnection, Int>()

    /** Hooks the projection session sets: navigation status to the cluster, microphone, etc. */
    var onTrip: ((CarAppConnection, Trip?) -> Unit)? = null
    var microphone: ((sink: (ByteArray, Int, Int) -> Unit, onStopped: () -> Unit) -> MicHandle?)? = null
    var onAssistant: (() -> Unit)? = null
    /** The screen-capture consent (car audio), which also lets the phone screen be mirrored. */
    var projection: () -> MediaProjection? = { null }
    /** Guidance from a navigation app on the phone (Google Maps, Waze…) changed, or ended (null). */
    var onPhoneGuidance: ((PhoneGuidance?) -> Unit)? = null
    /** Hands the screen back to the car's own UI; null (no "Exit to car" entry) without a head unit. Set before [start]. */
    var exitToCar: (() -> Unit)? = null

    /** Debug builds: a tap on the car screen at these pixels, as if from the car's touch screen (see CarTools "debug_tap"). */
    var debugTap: ((Float, Float, Long) -> Unit)? = null
    /** Debug builds: a finger drawn from one point to another over the given time. */
    var debugSwipe: ((Float, Float, Float, Float, Long) -> Unit)? = null
    /** Debug builds: two fingers spread (or pinched) around a point, their distance going from one value to another. */
    var debugPinch: ((Float, Float, Float, Float, Long) -> Unit)? = null

    // ------------------------------------------------------------------ HostEnvironment

    override val driving: Boolean get() = ui.drivingRestricted
    override val night: Boolean get() = ui.night
    override var screenWidthDp by mutableIntStateOf(1280)
    override var screenHeightDp by mutableIntStateOf(720)
    override var densityDpi by mutableIntStateOf(160)
    @Volatile override var carLocation: Location? = null
    @Volatile override var carHeading: Float? = null

    override fun contentLimit(type: Int): Int = when (type) {
        // Android Auto's usual limits: generous when parked, shorter lists while driving.
        ConstraintManager.CONTENT_LIMIT_TYPE_LIST -> if (ui.drivingRestricted) 6 else 100
        ConstraintManager.CONTENT_LIMIT_TYPE_GRID -> if (ui.drivingRestricted) 6 else 100
        ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST -> 6
        ConstraintManager.CONTENT_LIMIT_TYPE_ROUTE_LIST -> 3
        ConstraintManager.CONTENT_LIMIT_TYPE_PANE -> 4
        else -> 6
    }

    override fun toast(app: CarAppConnection, text: CharSequence, long: Boolean) {
        notice(app.info.label, text.toString(), app.info.icon, if (long) 6_000 else 3_500)
    }

    override fun startCarApp(from: CarAppConnection, intent: Intent) {
        val action = intent.action
        // CarContext.startCarApp's documented phone call. Like Android Auto, the host places it for any car app
        // (they use ACTION_DIAL precisely because they don't hold CALL_PHONE). Vanity letters (1-800-FLOWERS) become
        // digits, then only ASCII digits and '+' are kept, so spacing doesn't count toward the length cap. A '*' or
        // '#' makes it a USSD/MMI code ("**21*...#" call forwarding), not a number: refused, as stripping them would
        // dial a different number.
        if ((action == Intent.ACTION_DIAL || action == Intent.ACTION_CALL) && intent.data?.scheme == "tel") {
            val raw = PhoneNumberUtils.convertKeypadLettersToDigits(
                intent.data?.schemeSpecificPart.orEmpty().substringBefore(',').substringBefore(';'),
            )
            // An unescaped '#' starts the URI's fragment, so it isn't in the scheme-specific part: check that too.
            val code = raw.any { it == '*' || it == '#' } || intent.data?.encodedFragment != null
            val number = if (code) "" else raw.filter { it in '0'..'9' || it == '+' }.take(20)
            val id = idOf(from)
            // Car apps stay connected in the background, and AARE hosts any car app (not only Play-approved
            // ones): only the app on screen, or in the assistant bar, calls straight away; any other is asked.
            val onScreen = id != null && ((ui.screen as? CarScreen.App)?.appId == id || ui.assistantAppId == id)
            when {
                code -> {
                    notice(context.getString(R.string.notif_not_calling_title), context.getString(R.string.notif_not_calling_text, from.info.label))
                    if (onScreen) ui.screen = CarScreen.App(PHONE_ID)
                }
                number.isBlank() -> if (onScreen) ui.screen = CarScreen.App(PHONE_ID)
                onScreen -> {
                    Calls.place(context, number)
                    notice(context.getString(R.string.notif_calling_title, number), context.getString(R.string.notif_asked_by, from.info.label))
                    ui.screen = CarScreen.App(PHONE_ID)
                }
                else -> notice(context.getString(R.string.notif_call_confirm_title, number), context.getString(R.string.notif_asked_by, from.info.label), durationMs = 10_000, actions = listOf(context.getString(R.string.common_call) to {
                    Calls.place(context, number)
                    ui.screen = CarScreen.App(PHONE_ID)
                }))
            }
            return
        }
        val isNavigate = action == CarContext.ACTION_NAVIGATE || intent.data?.scheme == "geo" || intent.data?.scheme == "google.navigation"
        // As in CarContext.startCarApp's contract, an explicit component may only start the calling app itself:
        // otherwise any app could hand another a crafted intent and take over the screen. A navigation intent
        // naming another app goes to the driver's navigation app instead.
        val own = intent.component?.takeIf { it.packageName == from.info.packageName }
        // a destination for the driver's navigation app: Mate Navi takes it itself when it is chosen or on the screen
        if (isNavigate && own == null && mateNaviFirst()) {
            val r = net.prototux.aare.ui.car.navi.NaviRequests.fromUri(intent.data?.toString())
            if (r != null) { toMateNavi(r); return }
        }
        val target = when {
            own != null -> connections.values.firstOrNull { it.info.service == own }
                ?: carApps.entries.firstOrNull { it.value.service == own && it.value.packageName !in settings.hiddenApps }?.let { connection(it.key) }
            isNavigate -> navConnection()
            else -> null
        }
        if (target == null) {
            notice(from.info.label, if (isNavigate) context.getString(R.string.notif_no_nav_app) else context.getString(R.string.notif_cant_open))
            return
        }
        val navIntent = if (isNavigate && intent.action != CarContext.ACTION_NAVIGATE) Intent(CarContext.ACTION_NAVIGATE, intent.data) else intent
        target.connect(navIntent)
        show(target)
    }

    override fun finish(app: CarAppConnection) {
        val id = idOf(app) ?: return
        if ((ui.screen as? CarScreen.App)?.appId == id) ui.screen = CarScreen.Dashboard
        if (ui.assistantAppId == id) ui.assistantAppId = null
        // The dashboard's map card would start it again at once. Don't start another one in its
        // place either: two apps that both finish on start would take turns forever.
        if (ui.activeNavAppId == id) ui.activeNavAppId = null
        if (navigatingApp === app) navigationEnded(app)
        visibleCount.remove(app)
        app.release()
        // Before the removal: the screen fading out recomposes on it and would otherwise bind the app again.
        finished += id
        connections.remove(id)
    }

    /** The app whose trip the dashboard and the cluster show. */
    private var navigatingApp: CarAppConnection? = null
    private var lastPhoneGuidance: net.prototux.aare.notify.PhoneGuidance? = null

    override fun navigationStarted(app: CarAppConnection) {
        if (app.cluster) return
        // One navigation at a time, as in Android Auto: the other app is told to stop.
        connections.values.filter { it !== app && it.navigating }.forEach { it.stopNavigation() }
        navigatingApp = app
        idOf(app)?.let { ui.activeNavAppId = it; settings.lastNavApp = app.info.packageName }
    }

    override fun navigationEnded(app: CarAppConnection) {
        // An app we stopped may end after the new one started: keep the new one's trip.
        if (app.cluster || navigatingApp !== app) return
        navigatingApp = null
        ui.trip = null
        ui.tripPackage = null
        onTrip?.invoke(app, null)
    }

    override fun tripUpdated(app: CarAppConnection, trip: Trip) {
        if (app.cluster) return // the main session reports the same trip
        if (navigatingApp != null && navigatingApp !== app) return
        ui.trip = trip
        ui.tripPackage = app.info.packageName
        onTrip?.invoke(app, trip)
    }

    /** The instrument cluster's session of [nav], if the app has one (FEATURE_CLUSTER). */
    private val clusterConnections = HashMap<String, CarAppConnection>()

    fun clusterConnection(nav: CarAppEntry): CarAppConnection? {
        clusterConnections[nav.id]?.let { return it }
        val info = carApps[nav.id]?.takeIf { CarAppInfo.CATEGORY_CLUSTER in it.categories } ?: return null
        return CarAppConnection(context, info, this, cluster = true).also { clusterConnections[nav.id] = it }
    }

    override fun openMicrophone(app: CarAppConnection, sink: (ByteArray, Int, Int) -> Unit, onStopped: () -> Unit): MicHandle? {
        // Binder thread. Car apps stay bound in the background: only one the driver sees (on screen, on the
        // dashboard's map card, in the assistant bar) or the assistant just asked to listen may open the mic.
        if (!app.shown && ui.assistantAppId?.let { connections[it] } !== app) {
            Log.w(TAG, "${app.info.packageName}: microphone refused, not on screen")
            return null
        }
        // The rail's mic and the assistant bar show when an app listens.
        val handle = microphone?.invoke(sink) { main.post { ui.listening = false }; onStopped() } ?: return null
        main.post { ui.listening = true }
        return MicHandle { handle.close(); main.post { ui.listening = false } }
    }

    // ------------------------------------------------------------------ car screen settings

    /** The car's own day/night signal, if it sent one. */
    private var carNight: Boolean? = null
    /** The head unit's driver position. */
    var rightHandDrive = false
        set(v) { field = v; applyScreenSettings() }

    /** The one size of this car's screen, in percent, and foreign apps against Mate in steps (settings kept per car). */
    var size by mutableIntStateOf(100)
        private set
    var appTrim by mutableIntStateOf(0)
        private set
    private val trim: Float get() = Settings.TRIMS[(appTrim + 2).coerceIn(0, Settings.TRIMS.size - 1)]

    /** How much larger than the car's density Mate's own screen is drawn. */
    val shellScale: Float get() = size / 100f * Settings.SHELL_BASE
    /** The cards and lists Mate draws for a foreign app, against the car's own measure: they belong to the app (O146). */
    val templateScale: Float get() = trim

    /** The density Mate's own surfaces (the dashboard) are drawn with. */
    val mateDensityDpi: Int get() = (densityDpi * shellScale).toInt().coerceAtLeast(80)
    /** The density an app that draws itself (Google Maps, an app on its own display) is given. */
    val appDensityDpi: Int get() = (densityDpi * size / 100f * trim * Settings.APP_BASE).toInt().coerceAtLeast(80)
    /**
     * The density of a foreign app's map under Mate's cards: more than the car's own ([Settings.MAP_BASE]), not a
     * lowered one - what the app paints on its map (battery, speed) was tiny beside the cards (test drive U2, O152).
     */
    val mapDensityDpi: Int get() = (densityDpi * size / 100f * trim * Settings.MAP_BASE).toInt().coerceAtLeast(80)
    /** The density for [packageName]'s map surface. */
    fun surfaceDensityDpi(packageName: String): Int = if (packageName == context.packageName) mateDensityDpi else mapDensityDpi

    /** The car's driving signal (parked or not), before the passenger-mode setting. */
    private var carDriving = false

    /** The car says it's moving (true) or parked. */
    fun carDriving(driving: Boolean) {
        carDriving = driving
        net.prototux.aare.audio.CarEq.carDriving = driving
        applyScreenSettings()
    }

    /** The car's day/night signal changed. */
    fun carNightMode(night: Boolean) {
        carNight = night
        applyScreenSettings()
    }

    /** Applies the theme and bar position settings (also when they change during a drive). */
    private fun applyScreenSettings() {
        val night = when (settings.carTheme) {
            Settings.THEME_DARK -> true
            Settings.THEME_LIGHT -> false
            else -> carNight ?: true
        }
        if (ui.night != night) {
            ui.night = night
            configurationChanged()
        }
        net.prototux.aare.host.projected.ProjectedHost.night = night
        size = settings.size(ui.headUnitName)
        appTrim = settings.appTrim(ui.headUnitName)
        // two layers (System L-007): the car's measurement and the preset of what plays
        net.prototux.aare.audio.CarSound.apply(context, ui.headUnitName)
        val position = settings.railPosition
        // Passenger mode lifts the driving restrictions (keyboard, list lengths, phone screen).
        ui.drivingRestricted = carDriving && !settings.ignoreDriving
        ui.carPlayStyle = settings.carStyle == Settings.STYLE_CARPLAY
        ui.idmateStyle = settings.carStyle == Settings.STYLE_IDMATE
        ui.darkBar = settings.darkBar
        val wallpaper = settings.wallpaper
        if (wallpaper != ui.wallpaper || (wallpaper == Settings.WALLPAPER_CUSTOM && ui.wallpaperImage == null)) {
            ui.wallpaper = wallpaper
            ui.wallpaperImage = null
            if (wallpaper == Settings.WALLPAPER_CUSTOM) scope.launch {
                ui.wallpaperImage = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { android.graphics.BitmapFactory.decodeFile(java.io.File(context.filesDir, Settings.WALLPAPER_FILE).path) }.getOrNull()
                }
            }
        }
        ui.railBottom = position == Settings.RAIL_BOTTOM
        ui.driverOnRight = rightHandDrive
        ui.railOnRight = when (position) {
            Settings.RAIL_LEFT -> false
            Settings.RAIL_RIGHT -> true
            else -> rightHandDrive
        }
    }

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "car_theme", "rail_position", "car_style", "dark_bar", "ignore_driving" -> main.post { applyScreenSettings() }
            "size", "app_trim", "eq_on", "eq_gains" -> main.post { applyScreenSettings() }
            "wallpaper" -> main.post { ui.wallpaper = ""; applyScreenSettings() }
            // Apps shown or hidden from the phone: update the car's app list now.
            "hidden_apps", "phone_screen", "assistant_pkg", "app_order" -> main.post { refreshApps() }
        }
    }

    // ------------------------------------------------------------------ apps

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshApps()
    }

    fun start() {
        running += this
        // IDmate's values (the level for the navigation's arrival) fetched the whole connection long, not only while the
        // dashboard is on the screen (19.11.2026)
        net.prototux.aare.dash.DashView.carStart(context)
        // Shizuku started by Mate itself when it is not running (System L-063)
        net.prototux.aare.adb.SelfAdb.ensureShizuku(context, "car connected")
        // Messages, directions and now playing all need the listener bound: make sure it is.
        CarNotificationListener.rebind(context)
        applyScreenSettings()
        settings.registerListener(settingsListener)
        refreshApps()
        // a trip lost with the connection (switched off at a charging stop): Mate Navi comes to the front and goes on with it
        net.prototux.aare.ui.car.navi.NaviResume.load(context)?.takeIf { System.currentTimeMillis() - it.atMillis < 3 * 60 * 60_000L &&
            ui.apps.any { a -> a.kind == CarAppKind.MATE_NAVI } }?.let {
            ui.activeNavAppId = MATE_NAVI_ID
            ui.screen = CarScreen.App(MATE_NAVI_ID)
            DiagLog.add("navigation: a trip of ${(System.currentTimeMillis() - it.atMillis) / 60_000} min ago goes on - Mate Navi in front", area = "NAVI")
        }
        context.registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        scope.launch { media.apps.collectLatest { refreshApps() } }
        scope.launch { watchCalls() }
        scope.launch { watchDashboardInfo() }
        scope.launch { watchCharge() }
        scope.launch { watchParked() }
        scope.launch {
            CarNotificationListener.guidance.collect { g ->
                // A navigation app hosted on the car screen shows its own guidance - but only while it really guides
                // there. System I-009 ("Bei der Google-Navigation bekommt das Fahrzeug keine Navigationsanweisungen fürs
                // Head-up-Display"): an app merely listed with a car screen (Google Maps announces one that lets only
                // Google's host in) silenced its phone guidance for the cluster and the head-up display for good.
                val shown = g?.takeUnless { p -> navigatingApp?.info?.packageName == p.packageName }
                ui.phoneGuidance = shown
                onPhoneGuidance?.invoke(shown)
                if ((g != null) != (lastPhoneGuidance != null)) Log.i(TAG, "phone guidance ${if (g == null) "ended" else "from ${g.packageName}" + if (shown == null) " (hosted app guides itself)" else ""}")
                lastPhoneGuidance = g
            }
        }
        scope.launch {
            media.playback.collectLatest { p ->
                ui.nowPlaying = p?.let {
                    NowPlaying(mediaId(it.packageName), it.title, listOf(it.artist, it.album).filter { s -> s.isNotBlank() }.joinToString(" · "),
                        it.art, it.playing, it.positionMs, it.durationMs, it.sampledAtMs)
                }
                if (p != null && ui.activeMediaAppId == null) ui.activeMediaAppId = mediaId(p.packageName)
            }
        }
    }

    fun stop() {
        running.remove(this)
        net.prototux.aare.dash.DashView.carStop(context)
        runCatching { context.unregisterReceiver(packageReceiver) }
        settings.unregisterListener(settingsListener)
        connections.values.forEach { it.release() }
        connections.clear()
        finished.clear()
        clusterConnections.values.forEach { it.release() }
        clusterConnections.clear()
        if (ownsMedia) media.release()
    }

    /** Rescans car apps (package manager work and icons on a background thread). */
    fun refreshApps() {
        scope.launch {
            val (found, messaging) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                CarAppCatalog.query(context) to AndroidAutoApps.messagingApps(context)
            }
            applyApps(found, messaging)
            // I-013: which apps with Android Auto support are missing in the list and why - into the diagnosis log,
            // once and again when it changes (a new app, an app that refused Mate)
            val listed = ui.apps.map { it.packageName }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val lines = AndroidAutoApps.report(context, listed)
                    val prefs = context.getSharedPreferences("mate_apps", Context.MODE_PRIVATE)
                    val all = lines.joinToString("\n")
                    if (all != prefs.getString("report", "")) {
                        prefs.edit().putString("report", all).apply()
                        if (lines.isEmpty()) net.prototux.aare.DiagLog.add("apps: every app with Android Auto support is in the list")
                        else lines.forEach { net.prototux.aare.DiagLog.add(it) }
                    }
                }.onFailure { android.util.Log.w("MateApps", "report failed", it) }
            }
        }
    }

    private fun applyApps(found: List<CarAppInfo>, messaging: List<PhoneApp>) {
        val hidden = settings.hiddenApps
        val assistantPkg = assistantPackage()
        carApps.clear()
        val entries = ArrayList<CarAppEntry>()
        for (app in found) {
            val id = carId(app)
            carApps[id] = app
            if (app.packageName in hidden) continue
            val kind = when {
                app.isNavigation -> CarAppKind.NAVIGATION
                app.packageName == assistantPkg -> CarAppKind.ASSISTANT
                else -> CarAppKind.TEMPLATED
            }
            entries += CarAppEntry(id, app.packageName, app.label, app.icon, kind)
        }
        for (m in media.apps.value) {
            if (m.packageName in hidden) continue
            // An app that has a car UI of its own is shown once. A navigation app as that; a music app (Spotify,
            // YouTube Music bring a templated car screen besides their media service) as a media app: the media
            // service is the way that works with this host, and it gets this app's own music screens.
            // (Not our own package: the built-in music and the built-in dashboard are two things of one app.)
            val twin = if (m.packageName == context.packageName) -1 else entries.indexOfFirst { it.packageName == m.packageName }
            if (twin >= 0) {
                if (entries[twin].kind != CarAppKind.TEMPLATED) continue
                entries.removeAt(twin)
            }
            entries += CarAppEntry(mediaId(m.packageName), m.packageName, m.label, m.icon, CarAppKind.MEDIA)
        }
        // Built in, like Android Auto's: calls and the car-side settings.
        entries += CarAppEntry(PHONE_ID, context.packageName, context.getString(R.string.setup_app_entry_phone), builtinIcon(R.drawable.ic_car_phone), CarAppKind.PHONE)
        entries += CarAppEntry(SETTINGS_ID, context.packageName, context.getString(R.string.setup_app_entry_settings), builtinIcon(R.drawable.ic_car_settings), CarAppKind.SETTINGS)
        entries += CarAppEntry(NOTIFICATIONS_ID, context.packageName, context.getString(R.string.setup_app_entry_notifications), builtinIcon(R.drawable.ic_car_notifications), CarAppKind.NOTIFICATIONS)
        // tests in the car (19.11.2026: "dass wir eine Debug-App bauen, die ich in den Apps öffnen kann"): audio first
        entries += CarAppEntry(DEBUG_ID, context.packageName, context.getString(R.string.debug_app_label), builtinIcon(R.drawable.ic_car_debug), CarAppKind.DEBUG)
        if (settings.phoneScreen && phoneScreenAllowed) {
            entries += CarAppEntry(PHONE_SCREEN_ID, context.packageName, context.getString(R.string.phone_section_title), builtinIcon(R.drawable.ic_phone_screen), CarAppKind.PHONE_SCREEN)
        }
        // Apps that draw their own car screen through Google's private projection (Google Maps): a navigation
        // app like the others. Works where the system lets this host pass the app's check (see docs/maps-projection.md).
        val projected = net.prototux.aare.host.projected.ProjectedAppCatalog.query(context).filter { it.packageName !in hidden }
        for (p in projected) entries += CarAppEntry(projectedId(p.packageName), p.packageName, p.label, p.icon, CarAppKind.PROJECTED)
        // Ordinary phone apps the user allowed onto the car screen (parked only; needs root/Shizuku).
        // why the cinema has none, in the log once per change (19.11.2026: "ich sehe auch nicht die Kino-App")
        // which of Shizuku's steps is missing (19.11.2026: parked for sure, "cinema: no apps - Shizuku or root not available")
        val shizuku = net.prototux.aare.audio.CaptureMethods
        val (cinemaWhy, cinemaText) = when {
            !settings.standaloneApps -> "phone apps on the car screen are switched off" to R.string.cinema_missing_off
            settings.standaloneAppPackages.isEmpty() -> "no phone app chosen for the car screen" to R.string.cinema_missing_none
            AppLauncher.available(context) -> null to 0
            shizuku.shizukuNeedsPermission() -> "Shizuku runs but has not allowed Mate" to R.string.cinema_missing_permission
            shizuku.shizukuInstalled(context) -> "Shizuku is installed but not started (it has to be started after every restart of the tablet)" to R.string.cinema_missing_start
            else -> "Shizuku is not installed and root not allowed" to R.string.cinema_missing_install
        }
        ui.cinemaMissing = if (cinemaText != 0) context.getString(cinemaText) else null
        if (cinemaWhy != lastCinemaWhy) {
            lastCinemaWhy = cinemaWhy
            net.prototux.aare.DiagLog.add("cinema: " + (cinemaWhy?.let { "no apps - $it" } ?: "${settings.standaloneAppPackages.size} phone app(s) chosen, shown while parked"),
                if (cinemaWhy != null && cinemaText != R.string.cinema_missing_off) net.prototux.aare.DiagLog.Level.WARN else null, "CAR")
        }
        if (settings.standaloneApps && AppLauncher.available(context)) {
            for (pkg in settings.standaloneAppPackages) {
                if (pkg in hidden || projected.any { it.packageName == pkg }) continue
                val info = runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.getOrNull() ?: continue
                val label = context.packageManager.getApplicationLabel(info).toString()
                val icon = runCatching { context.packageManager.getApplicationIcon(info).toBitmap(128, 128) }.getOrNull()
                entries += CarAppEntry(standaloneId(pkg), pkg, label, icon, CarAppKind.STANDALONE)
            }
        }
        if (settings.browser) {
            entries += CarAppEntry(BROWSER_ID, context.packageName, context.getString(R.string.browser_app_label), builtinIcon(R.drawable.ic_car_browser), CarAppKind.BROWSER)
        }
        // Mate's own navigation (O34), built in like the music: a navigation app the user picks in the navigation area.
        entries += CarAppEntry(MATE_NAVI_ID, context.packageName, context.getString(R.string.mate_navi_label), builtinIcon(R.drawable.ic_car_mate_navi), CarAppKind.MATE_NAVI)
        if (settings.carDataApp) {
            entries += CarAppEntry(CAR_DATA_ID, context.packageName, context.getString(R.string.car_data_app_label), builtinIcon(R.drawable.ic_car_data), CarAppKind.CAR_DATA)
        }
        for (m in messaging) {
            if (m.packageName in hidden || entries.any { it.packageName == m.packageName }) continue
            entries += CarAppEntry(messagingId(m.packageName), m.packageName, m.label, m.icon, CarAppKind.MESSAGING)
        }
        // The order chosen on the phone first, then alphabetical.
        val order = settings.appOrder.withIndex().associate { (i, k) -> k to i }
        entries.sortWith(compareBy<CarAppEntry>({ order[orderKey(it)] ?: Int.MAX_VALUE }, { it.label.lowercase() }))
        // Not an app, so outside the user's order: always last, like Android Auto's "Exit".
        if (exitToCar != null) {
            entries += CarAppEntry(EXIT_ID, context.packageName, context.getString(R.string.dash_menu_exit_to_car),
                builtinIcon(R.drawable.ic_car_exit), CarAppKind.EXIT)
        }
        ui.apps.clear()
        ui.apps.addAll(entries)
        // Restore the last navigation and media apps, so the dashboard isn't empty. Not a navigation app that
        // finished itself: its map card would stay blank instead of offering the picker.
        if (ui.activeNavAppId == null) {
            val nav = entries.filter { it.kind == CarAppKind.NAVIGATION && it.id !in finished }
            ui.activeNavAppId = (nav.firstOrNull { it.packageName == settings.lastNavApp } ?: nav.singleOrNull())?.id
        }
        if (ui.activeMediaAppId == null) {
            ui.activeMediaAppId = entries.firstOrNull { it.kind == CarAppKind.MEDIA && it.packageName == settings.lastMediaApp }?.id
        }
    }

    /**
     * Navi L-001: the level, the place and the speed every 10 s while Mate runs in the car, so that a charge is noticed
     * afterwards and asked about when the car drives off ([net.prototux.aare.ui.car.navi.ChargeAsk]). Not for a
     * combustion car, not on the desk. The level may be old: after a charge IDmate's new one comes late.
     */
    private suspend fun watchCharge() {
        while (true) {
            kotlinx.coroutines.delay(10_000)
            // the tablet's memory, looked at in the same beat (Memory): tight - Mate gives back what it does not show
            runCatching { net.prototux.aare.Memory.check(context) }
            runCatching {
                if (settings.combustion || net.prototux.aare.ui.car.navi.NaviKeep.testDrive.value == true) return@runCatching
                val soc = net.prototux.aare.dash.DashData.socWithin(context, 24 * 3600_000L) ?: return@runCatching
                val fromCar = carLocation?.takeIf { System.currentTimeMillis() - it.time < 30_000L }
                val fix = fromCar ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DashboardInfo.phoneLocation(context) }
                    ?.takeIf { System.currentTimeMillis() - it.time < 60_000L } ?: return@runCatching
                val sensor = net.prototux.aare.car.CarData.state.value.speed?.takeIf { System.currentTimeMillis() - it.atMillis < 15_000L }?.value?.metersPerSecond?.toDouble()
                val speed = sensor ?: fix.takeIf { it.hasSpeed() }?.speed?.toDouble()
                val at = org.maplibre.spatialk.geojson.Position(longitude = fix.longitude, latitude = fix.latitude)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    net.prototux.aare.ui.car.navi.ChargeAsk.reading(context, soc, at, speed)
                }
            }.onFailure { android.util.Log.w("MateNavi", "charge watch", it) }
        }
    }

    /**
     * Films and videos only while the car stands parked for sure (18.11.2026, "Parken und Ladezeit, Amüsement"): looked
     * at every second ([net.prototux.aare.car.ParkedGate]). Closed, a phone app on the car screen goes at once - the
     * dashboard comes in front, the app's display is taken away and what plays is paused.
     */
    private var lastCinemaWhy: String? = "-"

    private suspend fun watchParked() {
        val gate = net.prototux.aare.car.ParkedGate()
        var was = false
        var lastWhy: String? = "start"
        var whySince = System.currentTimeMillis()
        var loggedAt = 0L
        var unlogged = 0
        while (true) {
            // the phone's position off the main thread, as elsewhere
            val loc = carLocation ?: runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DashboardInfo.phoneLocation(context) } }.getOrNull()
            val reading = runCatching {
                val s = net.prototux.aare.car.CarData.state.value
                net.prototux.aare.car.ParkedGate.Reading(System.currentTimeMillis(), s.gear?.value, s.gear?.atMillis ?: 0L,
                    s.speed?.value?.metersPerSecond?.toDouble()?.times(3.6), s.speed?.atMillis ?: 0L, s.parkingBrake?.value,
                    loc?.let { l -> net.prototux.aare.car.ParkedGate.Fix(l.latitude, l.longitude, if (l.hasSpeed()) l.speed * 3.6 else null, l.time) })
            }.getOrNull()
            val (open, why) = reading?.let { runCatching { gate.check(it) }.getOrNull() } ?: (false to "error")
            ui.parkedForFilms = open
            ui.parkedWhy = why
            // every change of the verdict or of its reason, with all readings and how long the last one lasted - to see
            // values that jump or go missing (18.11.2026), before time limits are set for them
            if (why != lastWhy && why != "settling") {
                val now = System.currentTimeMillis()
                // a value jumping every second: one line in 10 s, saying how often it changed in between
                if (now - loggedAt < 10_000L) unlogged++
                else {
                    net.prototux.aare.DiagLog.add("parked for videos: " + (if (open) "yes" else "no ($why)") +
                        " after ${(now - whySince) / 1000} s of ${lastWhy ?: "yes"}" + (if (unlogged > 0) ", $unlogged changes before unlogged" else "") +
                        " - " + (reading?.let { gate.describe(it) } ?: "no readings"))
                    loggedAt = now; unlogged = 0
                }
                lastWhy = why; whySince = now
            }
            // a change kept back by the 10 s limit is said once the limit has passed - before, the last of a quick run of
            // changes was never written (19.11.2026: the log said "no gear from the car" at every start and nothing after,
            // though the car told P a few seconds later)
            val now2 = System.currentTimeMillis()
            if (unlogged > 0 && now2 - loggedAt >= 10_000L && why != "settling") {
                net.prototux.aare.DiagLog.add("parked for videos: " + (if (open) "yes" else "no ($why)") + " - now, after $unlogged change(s) unlogged - " +
                    (reading?.let { gate.describe(it) } ?: "no readings"))
                loggedAt = now2; unlogged = 0
            }
            if (open != was) {
                if (was && !open) leaveStandalone()
                was = open
            }
            kotlinx.coroutines.delay(1_000)
        }
    }

    /** A phone app on the car screen when the car is no longer parked: away, and its sound paused. */
    private fun leaveStandalone() {
        val app = (ui.screen as? CarScreen.App)?.let { s -> ui.apps.firstOrNull { it.id == s.appId } } ?: return
        if (app.kind != CarAppKind.STANDALONE) return
        ui.screen = CarScreen.Dashboard
        runCatching {
            val am = context.getSystemService(android.media.AudioManager::class.java)
            val t = android.os.SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
        }
        notice(context.getString(R.string.cinema_title), context.getString(R.string.cinema_closed), durationMs = 6_000)
    }

    /** The weather (every 30 minutes) and the next calendar event (every 5), for the rail and the dashboard. */
    private suspend fun watchDashboardInfo() {
        var lastWeather = 0L
        while (true) {
            val now = System.currentTimeMillis()
            if (!settings.showWeather) ui.weather = null
            else if (now - lastWeather > 30 * 60_000) {
                val where = carLocation ?: DashboardInfo.phoneLocation(context)
                if (where != null) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DashboardInfo.weather(where) }?.let { ui.weather = it; lastWeather = now }
                }
            }
            ui.nextEvent = if (settings.showCalendar)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DashboardInfo.nextEvent(context) } else null
            kotlinx.coroutines.delay(5 * 60_000)
        }
    }

    /**
     * Whether a destination goes to Mate Navi: it is on the screen, or no other navigation app is and Mate Navi is the
     * one chosen for the navigation area (16.11.2026: chosen, it never got a spoken destination).
     */
    private fun mateNaviFirst(): Boolean {
        val front = (ui.screen as? CarScreen.App)?.appId
        val frontApp = front?.let { id -> ui.apps.firstOrNull { it.id == id } }
        if (frontApp?.kind == CarAppKind.MATE_NAVI) return true
        if (frontApp != null && (frontApp.kind == CarAppKind.NAVIGATION || frontApp.kind == CarAppKind.PROJECTED) && !net.prototux.aare.ui.car.isDash(frontApp)) return false
        val chosen = context.getSharedPreferences("idmate_choice", Context.MODE_PRIVATE).getString("navi", null)
        return chosen == context.packageName && ui.apps.any { it.kind == CarAppKind.MATE_NAVI }
    }

    private fun toMateNavi(r: net.prototux.aare.ui.car.navi.NaviRequests.Request) {
        net.prototux.aare.ui.car.navi.NaviRequests.ask(r)
        ui.activeNavAppId = MATE_NAVI_ID
        ui.screen = CarScreen.App(MATE_NAVI_ID)
        android.util.Log.i("MateNavi", "destination handed over: " + if (r.at != null) "a place" else "a search")
    }

    /** Navigates to [place] in the car's navigation app (geo: query), or false without one. */
    fun navigateTo(place: String): Boolean {
        if (mateNaviFirst()) { toMateNavi(net.prototux.aare.ui.car.navi.NaviRequests.fromUri("geo:0,0?q=" + android.net.Uri.encode(place))
            ?: net.prototux.aare.ui.car.navi.NaviRequests.Request(place, null)); return true }
        // The app chosen for the navigation area comes first (the dashboard is a navigation app to the car too, and
        // was taken for the place whenever it had been on the screen last).
        val chosen = context.getSharedPreferences("idmate_choice", Context.MODE_PRIVATE).getString("navi", null)
        val navis = ui.apps.filter { (it.kind == CarAppKind.NAVIGATION || it.kind == CarAppKind.PROJECTED) && !net.prototux.aare.ui.car.isDash(it) }
        // the navigation app on screen first (O103: a destination said while ABRP is in front belongs to ABRP)
        val front = (ui.screen as? CarScreen.App)?.let { s -> navis.firstOrNull { it.id == s.appId } }
        val nav = front ?: navis.firstOrNull { it.packageName == chosen } ?: navis.firstOrNull { it.id == ui.activeNavAppId }
            ?: navis.firstOrNull { it.kind == CarAppKind.NAVIGATION } ?: navis.firstOrNull() ?: return false
        if (nav.kind == CarAppKind.PROJECTED) {
            // Google Maps' own car screen takes the place as an ordinary view intent
            net.prototux.aare.host.projected.ProjectedHost.deliver(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(PROJECTED_PLACE + android.net.Uri.encode(place))))
            ui.activeNavAppId = nav.id
            ui.screen = CarScreen.App(nav.id)
            return true
        }
        val conn = connection(nav.id) ?: return false
        conn.connect(Intent(CarContext.ACTION_NAVIGATE).setData(android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode(place))))
        ui.activeNavAppId = nav.id
        ui.screen = CarScreen.App(nav.id)
        return true
    }

    /** Phone calls for the car's call card: polled, as no telephony listener needs fewer permissions. */
    private suspend fun watchCalls() {
        var since = 0L
        var last = TelephonyManager.CALL_STATE_IDLE
        while (true) {
            val state = Calls.state(context)
            if (state != last) { since = System.currentTimeMillis(); last = state }
            val name = CarNotificationListener.caller.value
            val call = when (state) {
                TelephonyManager.CALL_STATE_RINGING -> CarCall(ringing = true, name = name ?: context.getString(R.string.phone_incoming_call), sinceMs = since)
                TelephonyManager.CALL_STATE_OFFHOOK -> CarNotificationListener.waiting.value.let { w ->
                    CarCall(ringing = false, name = name ?: context.getString(R.string.phone_call_fallback_name), sinceMs = since,
                        waiting = w?.name, canEndAndAnswer = w?.find(net.prototux.aare.notify.WaitingCall.END_ANSWER) != null) }
                else -> null
            }
            if (call != ui.call) {
                // A call cuts into an audio book or a podcast: afterwards it goes on half a minute earlier.
                if (ui.call == null && call != null) media.cutIn()
                if (ui.call != null && call == null) media.cutOut(30_000)
                ui.call = call
            }
            kotlinx.coroutines.delay(if (state == TelephonyManager.CALL_STATE_IDLE) 1_500 else 500)
        }
    }

    /** Starts the last media app playing again (the "resume music" setting). */
    fun resumeMedia() {
        scope.launch {
            // The app list and the media apps come in asynchronously: give them a moment.
            kotlinx.coroutines.delay(1_500)
            val app = media.apps.value.firstOrNull { it.packageName == settings.lastMediaApp } ?: return@launch
            media.select(app)
            // A play before the app is connected is dropped (playPause then only connects).
            val waitMs = 10_000L
            if (!media.awaitConnection(waitMs)) {
                val line = "resume music: ${app.label} wasn't ready, not playing (waited up to ${waitMs / 1_000} s)"
                Log.w(TAG, line)
                DiagLog.add(line)
                return@launch
            }
            // Paused when the car was last connected: stays paused, the app is only made ready.
            if (!settings.mediaWasPlaying) {
                DiagLog.add("resume music: was paused last time, stays paused")
                return@launch
            }
            if (media.playback.value?.playing != true) media.playPause()
        }
    }

    /** How [Settings.appOrder] names an entry: its package, or its id for AARE's own apps. */
    private fun orderKey(e: CarAppEntry) = if (e.packageName == context.packageName) e.id else e.packageName

    private fun builtinIcon(res: Int) = ContextCompat.getDrawable(context, res)?.toBitmap(128, 128)

    /** Packages of the navigation apps hosted on the car screen. */
    fun navigationPackages(): Set<String> = carApps.values.filter { it.isNavigation }.mapTo(HashSet()) { it.packageName }

    /** The connection for a car app entry, created on first use. */
    fun connection(id: String): CarAppConnection? {
        finished.remove(id)
        connections[id]?.let { return it }
        val info = carApps[id] ?: return null
        return CarAppConnection(context, info, this).also { connections[id] = it }
    }

    /** The connection the car screen draws: like [connection], but doesn't start an app that finished itself again. */
    private fun shownConnection(id: String): CarAppConnection? = if (id in finished) connections[id] else connection(id)

    private fun navConnection(): CarAppConnection? {
        val id = ui.activeNavAppId ?: ui.apps.firstOrNull { it.kind == CarAppKind.NAVIGATION }?.id ?: return null
        ui.activeNavAppId = id
        return connection(id)
    }

    private fun idOf(conn: CarAppConnection): String? = connections.entries.firstOrNull { it.value === conn }?.key

    private fun show(conn: CarAppConnection) {
        idOf(conn)?.let { ui.screen = CarScreen.App(it) }
    }

    fun acquireVisible(conn: CarAppConnection) {
        val n = (visibleCount[conn] ?: 0) + 1
        visibleCount[conn] = n
        if (n == 1) conn.setVisible(true)
    }

    fun releaseVisible(conn: CarAppConnection) {
        val n = (visibleCount[conn] ?: 1) - 1
        if (n <= 0) {
            visibleCount.remove(conn)
            // Let the other copy (dashboard card ↔ full screen) take over before stopping the app.
            main.postDelayed({ if ((visibleCount[conn] ?: 0) == 0) conn.setVisible(false) }, 500)
        } else visibleCount[conn] = n
    }

    /** Pushes car GPS/compass to subscribed apps. Any thread. */
    fun carSensorsChanged() = main.post { connections.values.forEach { it.carSensorsChanged() } }

    /** Night mode or screen size changed: tell running apps. */
    fun configurationChanged() {
        connections.values.forEach { it.configurationChanged() }
        clusterConnections.values.forEach { it.configurationChanged() }
    }

    // ------------------------------------------------------------------ notices

    /** Notice keys (LazyColumn keys): two notices in the same millisecond must not collide. */
    private var noticeSeq = 0L

    /** Keeps a notification (a message) for the notification centre. */
    fun remember(title: String, text: String, icon: Bitmap?, actions: List<Pair<String, () -> Unit>>, reply: ((String) -> Boolean)? = null) {
        ui.history.add(0, CarNotice("h${++noticeSeq}", title, text, icon, actions, reply = reply))
        while (ui.history.size > 30) ui.history.removeAt(ui.history.lastIndex)
        if ((ui.screen as? CarScreen.App)?.appId != NOTIFICATIONS_ID) ui.unseen++
    }

    fun notice(title: String, text: String, icon: Bitmap? = null, durationMs: Long = 4_000, actions: List<Pair<String, () -> Unit>> = emptyList(), message: Boolean = false) {
        val n = CarNotice("n${++noticeSeq}", title, text, icon, actions, message = message)
        ui.notices.add(n)
        main.postDelayed({ ui.notices.remove(n) }, durationMs)
    }

    // ------------------------------------------------------------------ actions from the car UI

    val actions = object : CarActions {
        override fun openApp(app: CarAppEntry) {
            // No screen of its own and not a recent app: the projection stays as it is until the car takes focus.
            if (app.kind == CarAppKind.EXIT) { exitToCar?.invoke(); return }
            when {
                // Mate's own dashboard is drawn by Mate itself: no car app to start (19.11.2026)
                net.prototux.aare.ui.car.isDash(app) -> Unit
                else -> when (app.kind) {
                CarAppKind.MEDIA -> {
                    ui.activeMediaAppId = app.id
                    settings.lastMediaApp = app.packageName
                    media.apps.value.firstOrNull { it.packageName == app.packageName }?.let { media.select(it) }
                }
                CarAppKind.NAVIGATION -> {
                    ui.activeNavAppId = app.id
                    settings.lastNavApp = app.packageName
                    connection(app.id)?.connect()
                }
                // draws by itself once its surface is on the screen
                CarAppKind.PROJECTED -> settings.lastNavApp = app.packageName
                else -> connection(app.id)?.connect()
                }
            }
            ui.screen = CarScreen.App(app.id)
            ui.recentAppIds.remove(app.id)
            ui.recentAppIds.add(0, app.id)
            while (ui.recentAppIds.size > 6) ui.recentAppIds.removeAt(ui.recentAppIds.lastIndex)
        }

        override fun goHome() { ui.screen = CarScreen.Dashboard }
        override fun openLauncher() { ui.screen = CarScreen.Launcher }
        override fun assistant() { this@CarRuntime.assistant() }
        override fun faultNote(text: String) {
            net.prototux.aare.debug.FaultNote.save(text)
            notice(context.getString(R.string.fault_mark_title), context.getString(R.string.fault_note_saved), durationMs = 4_000)
        }
        override fun markFault() {
            val state = runCatching { CarTools.run(this@CarRuntime, "car_status", org.json.JSONObject()).toString(2) }.getOrDefault("{}")
            net.prototux.aare.debug.FaultNote.begin()
            net.prototux.aare.debug.FaultMark.capture(context, state) { dir ->
                net.prototux.aare.debug.FaultNote.marked(dir)
                main.post {
                    notice(context.getString(R.string.fault_mark_title),
                        context.getString(if (dir != null) R.string.fault_mark_done else R.string.fault_mark_failed), durationMs = 5_000)
                }
            }
        }
        override fun mediaPlayPause() = media.playPause()
        override fun mediaNext() = media.next()
        override fun mediaSeekBy(deltaMs: Long) = media.seekBy(deltaMs)
        override fun mediaPrevious() = media.previous()
        override fun dismissNotice(notice: CarNotice) { ui.notices.remove(notice) }
        override fun answerCall() = Calls.answer(context)
        override fun endCall() {
            if (Calls.state(context) != TelephonyManager.CALL_STATE_IDLE) { Calls.end(context); return }
            // hang up in the car with no phone call (19.11.2026: "Wenn ich im Fahrzeug auf Auflegen drücke, dann passiert
            // nichts ... der Anruf, der bleibt weiterhin bestehen"): an app holds the call channel (voice messages, a
            // messenger) - the playing app is asked to stop, which most let go of the channel with
            val am = context.getSystemService(android.media.AudioManager::class.java)
            DiagLog.add("call: hang up from the car without a phone call - audio mode ${am?.mode}; the playing app asked to stop", area = "PHONE")
            runCatching {
                for (code in intArrayOf(android.view.KeyEvent.KEYCODE_MEDIA_STOP, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)) {
                    am?.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
                    am?.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
                }
            }
        }
        // the second call: the phone app's own answer where it has one (its words matched), else Android's telephony -
        // answering a ringing call holds the one on, ending with a call ringing turns the ringing one away
        override fun holdAndAnswer() {
            val w = CarNotificationListener.waiting.value
            val a = w?.find(net.prototux.aare.notify.WaitingCall.HOLD) ?: w?.find(net.prototux.aare.notify.WaitingCall.ANSWER, net.prototux.aare.notify.WaitingCall.END_ANSWER)
            net.prototux.aare.DiagLog.add("call: hold and answer" + (a?.let { " - the phone app's \"${it.title}\"" } ?: " - by the telephony"), area = "PHONE")
            if (a?.intent != null) runCatching { a.intent.send() }.onFailure { Calls.answer(context) } else Calls.answer(context)
        }
        override fun endAndAnswer() {
            val a = CarNotificationListener.waiting.value?.find(net.prototux.aare.notify.WaitingCall.END_ANSWER) ?: return
            net.prototux.aare.DiagLog.add("call: end and answer - the phone app's \"${a.title}\"", area = "PHONE")
            runCatching { a.intent?.send() }
        }
        override fun declineWaiting() {
            val a = CarNotificationListener.waiting.value?.find(net.prototux.aare.notify.WaitingCall.DECLINE)
            net.prototux.aare.DiagLog.add("call: the waiting call declined" + (a?.let { " - the phone app's \"${it.title}\"" } ?: " - by the telephony"), area = "PHONE")
            if (a?.intent != null) runCatching { a.intent.send() }.onFailure { Calls.end(context) } else Calls.end(context)
        }
        override fun navigateTo(place: String) {
            if (!this@CarRuntime.navigateTo(place)) notice(context.getString(R.string.notif_navigation_title), context.getString(R.string.notif_navigation_no_app_text))
        }
    }

    /** The chosen assistant's package, or the phone's default assistant. */
    private fun assistantPackage(): String? = settings.assistantPackage ?: Assistants.phoneDefault(context)

    /**
     * Steering-wheel voice button or the rail's mic. An assistant with a car app (Lapin, for example)
     * opens on the car screen and is asked to listen with ACTION_VOICE_COMMAND; any other assistant
     * opens on the phone.
     */
    fun assistant() {
        onAssistant?.invoke()
        val pkg = assistantPackage()
        if (pkg == null) {
            notice(context.getString(R.string.notif_assistant_title), context.getString(R.string.notif_assistant_choose_text))
            return
        }
        val entry = ui.apps.firstOrNull { it.packageName == pkg && it.kind != CarAppKind.MEDIA && it.kind != CarAppKind.NAVIGATION }
        val conn = entry?.let { connection(it.id) }
        if (entry != null && conn != null) {
            // Like Android Auto's assistant: a bar over whatever is on screen, not the app full screen. Set before
            // the intent goes out: the app may open the microphone before the bar is drawn (see openMicrophone).
            if ((ui.screen as? CarScreen.App)?.appId != entry.id) ui.assistantAppId = entry.id
            conn.connect(Intent(Intent.ACTION_VOICE_COMMAND).setComponent(conn.info.service))
            return
        }
        val label = Assistants.label(context, pkg) ?: pkg
        if (Assistants.startOnPhone(context, pkg)) {
            notice(context.getString(R.string.notif_assistant_title), context.getString(R.string.notif_assistant_no_car_screen, label))
        } else {
            notice(context.getString(R.string.notif_assistant_title), context.getString(R.string.notif_assistant_cant_open, label))
        }
    }

    /** Set by a screen with its own back stack (a conversation in a messaging app): true if it handled back. */
    var screenBack: (() -> Boolean)? = null

    /** Back key from the car. */
    fun back() {
        if (ui.assistantAppId != null) { ui.assistantAppId = null; return }
        if (screenBack?.invoke() == true) return
        when (val s = ui.screen) {
            is CarScreen.App -> {
                val conn = connections[s.appId]
                // An app still connecting or that failed has no screen to go back in: leave it.
                if (conn != null && conn.status == CarAppConnection.Status.READY) conn.back() else ui.screen = CarScreen.Dashboard
            }
            CarScreen.Launcher -> ui.screen = CarScreen.Dashboard
            CarScreen.Dashboard -> Unit
        }
    }

    // ------------------------------------------------------------------ UI

    /** The whole car screen. */
    @Composable
    fun Root(modifier: Modifier = Modifier) {
        val mediaApps by media.apps.collectAsState()
        // Mate's own size for this car: everything measured in dp and sp grows or shrinks with it
        val base = androidx.compose.ui.platform.LocalDensity.current
        val scaled = androidx.compose.ui.unit.Density(base.density * shellScale, base.fontScale)
        CarTheme(night = ui.night) {
            CompositionLocalProvider(LocalCarDimens provides LocalCarDimens.current, androidx.compose.ui.platform.LocalDensity provides scaled) {
                CarShell(
                    state = ui,
                    actions = actions,
                    appContent = { app, outer ->
                        // One ladder of text sizes for everything on the car screen (O151). The screens in an area are
                        // written in the car's own measure (ui/theme/CarTheme: text 22, headings 24, titles 28), Mate's
                        // bar and pop-ups in a smaller one that is drawn larger ([Settings.SHELL_BASE]) - so the area's
                        // content is taken back by that factor, and both come out alike. A foreign app's cards and
                        // lists follow the fine tuning besides, with its map (O146). The dashboard keeps Mate's measure.
                        val shell = androidx.compose.ui.platform.LocalDensity.current
                        val conn = if (net.prototux.aare.ui.car.isDash(app)) null else when (app.kind) {
                            CarAppKind.NAVIGATION, CarAppKind.TEMPLATED, CarAppKind.ASSISTANT -> shownConnection(app.id)
                            else -> null
                        }
                        val factor = when {
                            conn == null -> 1f / Settings.SHELL_BASE
                            conn.info.packageName == context.packageName -> 1f
                            else -> templateScale / Settings.SHELL_BASE
                        }
                        // the room the area leaves for the bar is measured out here, in the shell's measure
                        androidx.compose.foundation.layout.Box(outer) {
                            val inset = net.prototux.aare.host.templates.LocalCarBottomInset.current
                            val mod: Modifier = Modifier.fillMaxSize()
                            CompositionLocalProvider(
                                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(shell.density * factor, shell.fontScale),
                                net.prototux.aare.host.templates.LocalCarBottomInset provides inset / factor,
                            ) {
                                // Mate's own dashboard: its views straight in the car screen, no car app in between (19.11.2026)
                                if (net.prototux.aare.ui.car.isDash(app)) net.prototux.aare.ui.car.DashArea(this@CarRuntime, mod)
                                else when (app.kind) {
                                    CarAppKind.MEDIA -> {
                                        val m = mediaApps.firstOrNull { it.packageName == app.packageName }
                                        if (m != null) media.Screen(m, ui.drivingRestricted, mod)
                                    }
                                    CarAppKind.MESSAGING -> MessagingScreen(app, ui.drivingRestricted, this@CarRuntime, mod)
                                    CarAppKind.PHONE_SCREEN -> MirrorScreen(this@CarRuntime, ui.drivingRestricted, mod)
                                    CarAppKind.STANDALONE -> StandaloneAppScreen(this@CarRuntime, ui.drivingRestricted || !ui.parkedForFilms, app.packageName, mod)
                                    CarAppKind.PROJECTED -> net.prototux.aare.host.projected.ProjectedAppCatalog.find(context, app.packageName)?.let { p ->
                                        // a new size starts the app afresh at that size
                                        androidx.compose.runtime.key(appDensityDpi) {
                                            net.prototux.aare.host.projected.ProjectedAppScreen(p, appDensityDpi, { info ->
                                                net.prototux.aare.host.projected.ProjectedHost(context, info.service, object : net.prototux.aare.host.projected.ProjectedHost.Listener {})
                                            }, mod)
                                        }
                                    }
                                    CarAppKind.BROWSER -> BrowserScreen(this@CarRuntime, ui.drivingRestricted, settings.browserUrl, mod)
                                    CarAppKind.CAR_DATA -> CarDataScreen(mod)
                                    CarAppKind.MATE_NAVI -> net.prototux.aare.ui.car.navi.MateNaviScreen(mod, ui.drivingRestricted, ui.headUnitName, ui.driverOnRight)
                                    CarAppKind.PHONE -> PhoneScreen(this@CarRuntime, mod)
                                    CarAppKind.SETTINGS -> CarSettingsScreen(settings, mod)
                                    CarAppKind.NOTIFICATIONS -> NotificationsScreen(ui, mod)
                                    CarAppKind.DEBUG -> net.prototux.aare.ui.car.DebugScreen(mod)
                                    else -> shownConnection(app.id)?.let { CarAppScreen(it, this@CarRuntime, mod) }
                                }
                            }
                        }
                    },
                    navMap = { app, mod -> shownConnection(app.id)?.let { CarAppScreen(it, this, mod, compact = true) } },
                    overlay = { mod ->
                        val entry = ui.app(ui.assistantAppId)
                        val conn = entry?.let { shownConnection(it.id) }
                        if (entry != null && conn != null) {
                            AssistantBar(entry, conn, this, mod,
                                onClose = { ui.assistantAppId = null },
                                onExpand = { ui.assistantAppId = null; ui.screen = CarScreen.App(entry.id) })
                        }
                    },
                    modifier = modifier,
                )
            }
        }
    }

    companion object {
        private const val TAG = "AareRuntime"
        /** How a place is handed to Google Maps on the car screen. */
        private const val PROJECTED_PLACE = "geo:0,0?q="
        fun carId(app: CarAppInfo) = "car:${app.service.flattenToShortString()}"
        fun mediaId(pkg: String) = "media:$pkg"
        fun messagingId(pkg: String) = "messages:$pkg"
        fun standaloneId(pkg: String) = "standalone:$pkg"
        fun projectedId(pkg: String) = "projected:$pkg"
        const val PHONE_SCREEN_ID = "phone-screen"
        const val BROWSER_ID = "browser"
        const val CAR_DATA_ID = "car-data"
        const val MATE_NAVI_ID = "mate-navi"
        const val PHONE_ID = "phone"
        const val SETTINGS_ID = "car-settings"
        const val NOTIFICATIONS_ID = "notifications"
        const val DEBUG_ID = "mate-debug"
        const val EXIT_ID = "exit-to-car"

        /** Started runtimes, oldest first. Main thread. */
        private val running = ArrayList<CarRuntime>()

        /**
         * The car screen being driven now, for the voice assistant's car tools: the car session's
         * runtime, or the preview's when no car is connected (opening or closing the preview
         * during a drive doesn't take the car away). Main thread.
         */
        val active: CarRuntime? get() = running.lastOrNull { it.primary } ?: running.lastOrNull()
    }
}
