package net.prototux.aare.ui.car

import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.delay
import net.prototux.aare.R

/** The separate IDmate app (github.com/IDmate-app), a dashboard for any Android Auto host: no part of this app, never taken for navigation. */
internal const val IDMATE_PACKAGE = "de.fredersdorf.idmate.auto"

/**
 * The built-in dashboard: the car app this app hosts itself (net.prototux.aare.dash). It fills the dashboard area.
 * Its id is the service's short component name: "net.prototux.aare/.dash.IdmateCarService" while the app id was the
 * code's package, "app.idmate.mate/net.prototux.aare.dash.IdmateCarService" since 2.1 (own app id) - only the first was
 * known, so 2.1 found no dashboard and showed AARE's own start view instead, without the car's data and the map
 * (21.11.2026: "Was ist mit dem Dashboard passiert?").
 */
private const val DASH_SERVICE = "net.prototux.aare.dash.IdmateCarService"
internal fun isDash(app: CarAppEntry): Boolean = isDashId(app.id)
internal fun isDashId(id: String): Boolean {
    val component = id.removePrefix("car:")
    return component.substringAfter('/') == DASH_SERVICE || component == "net.prototux.aare/.dash.IdmateCarService"
}

/** Google's Android Auto announces car apps of its own (settings, games…): under AARE they lead nowhere. */
private const val GOOGLE_AUTO_PACKAGE = "com.google.android.projection.gearhead"

/** A Better Routeplanner: preferred for the navigation area. */
private const val ABRP_PACKAGE = "com.iternio.abrpapp"
private const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"


/** Media services that are no music apps: the Bluetooth sink and the assistant's news. */
private val NO_MUSIC_PACKAGES = setOf(GOOGLE_AUTO_PACKAGE, "com.android.bluetooth", "com.google.android.googlequicksearchbox")

/** The floating controls: as little of the screen as a finger still hits safely. */
private val ControlsHeight = 56.dp
private val ControlsMargin = 10.dp

/** The patch behind the controls: black at 70 %. */
private val Dim get() = Mate.scrim

// The colours come from one place: Mate (res/values/mate_colors.xml). These are short names for use in this file.
private val Ink get() = Mate.bg
private val Panel get() = Mate.surface
private val PanelHigh get() = Mate.surfaceHigh
private val Line get() = Mate.line
private val TextDim get() = Mate.textDim
private val Teal get() = Mate.accent

private fun idmateColors() = darkColorScheme(
    background = Ink,
    surface = Panel,
    surfaceContainer = Panel,
    surfaceContainerHigh = PanelHigh,
    surfaceContainerHighest = Line,
    surfaceContainerLowest = Mate.bgLow,
    primary = Teal,
    onPrimary = Mate.onAccent,
    primaryContainer = Mate.accentContainer,
    onPrimaryContainer = Teal,
    secondaryContainer = PanelHigh,
    onSecondaryContainer = Mate.text,
    onSurface = Mate.text,
    onSurfaceVariant = TextDim,
    outline = Line,
)

/**
 * The car screen in the IDmate style (the only one since E-044): the apps get the whole display. The
 * controls float over the content in the bottom corner next to the driver, on a patch dimmed to
 * 70 % so that they stay readable over any app: the voice button, one button that names the area
 * in front and opens the switcher, the time and the phone's status. With the driver on the right
 * the patch sits in the right corner and its order is mirrored.
 *
 * The switcher ("Bereiche") shows the areas (dashboard, navigation, audio, messenger, cinema) and the entry to the app
 * overview as tiles above the controls. An area only shows when there is something behind it; the
 * dashboard opens the IDmate car app when it is installed, AARE's own dashboard otherwise.
 *
 * The app overview shows the apps as coloured tiles with their names, nothing else: no filter, no
 * versions. The most used come first, what is rarely used moves to the pages behind by itself.
 */
@Composable
internal fun IdmateShell(
    state: CarUiState,
    actions: CarActions,
    appContent: @Composable (CarAppEntry, Modifier) -> Unit,
    navMap: @Composable (CarAppEntry, Modifier) -> Unit,
    overlay: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    remember { Mate.init(context) }
    val usage = remember { AppUsage(context) }
    val choice = remember { AreaChoice(context) }
    // Combustion car (O99): no dashboard area, the start goes to navigation
    val combustion by produceCombustion(context)
    // state.apps is one list that fills while the apps are found: read it anew, never remembered by its instance.
    val naviList = naviApps(state, context.packageManager)
    val musicList = musicApps(state, context.packageName, context.packageManager)
    var switcher by remember { mutableStateOf(false) }
    // The areas this shell draws itself (see Inner); null = whatever state.screen says.
    var inner by remember { mutableStateOf<Inner?>(null) }
    // the cinema: the app shown in it (null: its app menu)
    var cinemaApp by remember { mutableStateOf<CarAppEntry?>(null) }
    // Split screen: navigation beside what is playing. Which side and how wide is remembered for the drive.
    var split by remember { mutableStateOf(false) }
    var naviRightState by remember { mutableStateOf<Boolean?>(null) }
    // the navigation's share of the width (18.11.2026: "Die Splitansicht sollte auch 1/3, 1/4 beherrschen"), kept on the device
    val splitPrefs = remember { context.getSharedPreferences("mate_split", android.content.Context.MODE_PRIVATE) }
    var naviShare by remember { mutableStateOf(splitPrefs.getFloat("navi_share", 0.5f)) }
    // which side the navigation is on, kept as well
    val naviRight = naviRightState ?: splitPrefs.getBoolean("navi_right", true)
    fun swapSides() { naviRightState = !naviRight; splitPrefs.edit().putBoolean("navi_right", !naviRight).apply() }

    // the saved view called last from the switcher, marked in its list
    var lastView by remember { mutableStateOf<String?>(null) }
    // a fault was marked: the card that takes its description (18.11.2026)
    var faultNote by remember { mutableStateOf(false) }
    // the music app of the split, when one was laid in or a view named one (else what plays)
    var splitMusic by remember { mutableStateOf<String?>(null) }
    // it belongs to the split it was laid into: once that is closed, the next split shows what plays again (it kept
    // the app of an earlier split or view beside the navigation, whatever was playing by then)
    LaunchedEffect(split) { if (!split) splitMusic = null }
    // an app in the hand (18.11.2026: "mit langem Druck auf eine App greift man die und zieht sie nach links oder rechts")
    var dragged by remember { mutableStateOf<CarAppEntry?>(null) }
    var dragAt by remember { mutableStateOf(Offset.Zero) }
    var shellOrigin by remember { mutableStateOf(Offset.Zero) }
    val settingsAnim = remember { net.prototux.aare.Settings(context) }
    var applyView: (MateViews.View) -> Unit = {}
    // The card of a call that is on: shown only when asked for (the green sign in the controls).
    var callOpen by remember { mutableStateOf(false) }
    if (state.call == null && callOpen) callOpen = false
    // The media screen's search and now-playing buttons sit in this shell's controls, at the driver's corner.
    androidx.compose.runtime.DisposableEffect(Unit) {
        net.prototux.aare.media.MediaControls.hosted = true
        onDispose { net.prototux.aare.media.MediaControls.hosted = false }
    }
    val open: (CarAppEntry) -> Unit = { app ->
        usage.count(app.id)
        switcher = false
        split = false
        inner = null
        actions.openApp(app)
    }
    MaterialTheme(colorScheme = remember { idmateColors() }, typography = MaterialTheme.typography) {
        // Text without a colour of its own takes the content colour: light, as everything here is on dark.
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Mate.text) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Ink).then(modifier).onGloballyPositioned { shellOrigin = it.positionInRoot() }) {
            val anim = remember(switcher, split, dragged) { settingsAnim.carAnimations }
            val idmate = state.apps.firstOrNull { isDash(it) }
            // One dashboard, not two: wherever AARE would show its own start view, the IDmate app comes up instead.
            val atStart = inner == null && state.screen == CarScreen.Dashboard
            // the navigation app is keyed as well: state.apps may still be empty when the car connects
            val startNaviApp = naviApp(naviList, choice)
            LaunchedEffect(atStart, idmate?.id, combustion, startNaviApp?.id) {
                if (!atStart) return@LaunchedEffect
                if (combustion) startNaviApp?.let { actions.openApp(it) }
                else if (idmate != null) actions.openApp(idmate)
            }
            // The navigation app in front refused this host: the next one that works takes its place by itself.
            val refused = net.prototux.aare.host.HostRejections.packages
            val front = (state.screen as? CarScreen.App)?.let { state.app(it.appId) }
            val instead = if (inner == null && front != null && front.packageName in refused && naviList.any { it.id == front.id })
                naviApp(naviList, choice)?.takeIf { it.packageName !in refused } else null
            LaunchedEffect(instead?.id) { instead?.let { actions.openApp(it) } }
            val areas = areas(state, actions, open, inner, naviList, musicList, choice) { inner = it; switcher = false; split = false }
                .let { all -> if (combustion) all.filter { it.icon != Icons.Rounded.SpaceDashboard } else all }
            val player = splitMusic?.let { id -> musicList.firstOrNull { it.id == id } }
                ?: state.app(state.activeMediaAppId)?.takeIf { it.packageName != GOOGLE_AUTO_PACKAGE }
                ?: musicList.firstOrNull()
            val splitOn = split && player != null
            val overview = inner == null && state.screen == CarScreen.Launcher
            // Saved view (O6): once per connection, the area it names comes to the front, after the dashboard has started.
            val startView = remember { MateViews(context).active }
            var started by remember { mutableStateOf(startView == null || startView.start == MateViews.START_DASHBOARD) }
            val startNavi = naviApp(naviList, choice)
            val startMusic = musicList.firstOrNull { it.packageName == choice.music } ?: musicList.firstOrNull()
            applyView = { v ->
                MateViews(context).apply(v)
                v.navi?.let { choice.pickNavi(it) }
                val navi = naviList.firstOrNull { it.packageName == v.navi } ?: naviApp(naviList, choice)
                val music = musicList.firstOrNull { it.packageName == v.music } ?: startMusic
                android.util.Log.i("IdmateShell", "view from bar: ${v.name} -> ${v.start}")
                when (v.start) {
                    MateViews.START_NAVI -> navi?.let(open)
                    MateViews.START_MUSIC -> music?.let(open)
                    MateViews.START_MESSAGES -> { inner = Inner.MESSAGES; split = false }
                    MateViews.START_SPLIT -> {
                        // the music app first, so it is the one playing beside; then the navigation in front, split
                        music?.let { actions.openApp(it); splitMusic = it.id }
                        naviShare = v.share; naviRightState = v.naviRight
                        navi?.let(open); split = true
                    }
                    else -> { inner = null; split = false; idmate?.let { actions.openApp(it) } ?: actions.goHome() }
                }
            }
            val startReady = when (startView?.start) {
                MateViews.START_NAVI -> startNavi != null
                MateViews.START_MUSIC -> startMusic != null
                MateViews.START_SPLIT -> startNavi != null && player != null
                else -> true
            }
            LaunchedEffect(Unit) { kotlinx.coroutines.delay(10_000); started = true } // its apps never came: the drive goes on as it is
            LaunchedEffect(startReady, started) {
                if (started || !startReady) return@LaunchedEffect
                kotlinx.coroutines.delay(1200)
                if (started) return@LaunchedEffect
                started = true
                when (startView?.start) {
                    MateViews.START_NAVI -> startNavi?.let(open)
                    MateViews.START_MUSIC -> startMusic?.let(open)
                    MateViews.START_MESSAGES -> { inner = Inner.MESSAGES }
                    MateViews.START_SPLIT -> { startMusic?.let { actions.openApp(it) }; startNavi?.let(open); split = true }
                }
                android.util.Log.i("IdmateShell", "start view: ${startView?.name} -> ${startView?.start}")
            }
            val side = if (state.driverOnRight) Alignment.BottomEnd else Alignment.BottomStart
            // The content has the whole display; nothing is reserved for a bar.
            if (splitOn && player != null) {
                val clear = ControlsHeight + ControlsMargin * 2
                val music: @Composable (Modifier) -> Unit = { m ->
                    // 18.11.2026 (split views tested at every share): the controls reach into the music pane on the far side
                    // as well when the navigation is narrow - the music always keeps clear of them
                    Box(m.fillMaxHeight()) { appContent(player, Modifier.fillMaxSize().padding(bottom = clear)) }
                }
                val naviEntry = naviApp(naviList, choice)
                val navi: @Composable (Modifier) -> Unit = { m ->
                    Box(m.fillMaxHeight().clip(RoundedCornerShape(0.dp))) {
                        // 18.11.2026 ("Die Navigation ist in der Split-Ansicht hinter dem Bedienfeld"): the navigation is told
                        // what the controls cover, as on the full screen - its panels stay above them
                        androidx.compose.runtime.CompositionLocalProvider(
                            net.prototux.aare.host.templates.LocalCarBottomInset provides clear,
                            net.prototux.aare.ui.car.navi.LocalNaviShare provides naviShare,
                        ) {
                            if (naviEntry != null) appContent(naviEntry, Modifier.fillMaxSize())
                        }
                    }
                }
                // animated (switchable): a new split opens from the navigation's full width, a new share glides there
                // 18.11.2026 ("andere Apps, z. B. Google Navi, brauchen mindestens eine 2/3-Ansicht"): a navigation app that is
                // not Mate's own gets two thirds at least - its own layout is made for a wide screen. The music pane is drawn by
                // Mate for every music app, so it may be narrow.
                val minShare = if (naviEntry != null && naviEntry.kind != CarAppKind.MATE_NAVI) FOREIGN_MIN_SHARE else 0.2f
                val target = naviShare.coerceIn(minShare, 0.8f)
                val glide by androidx.compose.animation.core.animateFloatAsState(target,
                    if (anim) androidx.compose.animation.core.tween(380) else androidx.compose.animation.core.snap(), label = "share")
                val open01 = remember { androidx.compose.animation.core.Animatable(if (anim) 0f else 1f) }
                LaunchedEffect(Unit) { if (anim) open01.animateTo(1f, androidx.compose.animation.core.tween(450)) else open01.snapTo(1f) }
                val share = (1f + (glide - 1f) * open01.value).coerceIn(0.001f, 0.999f)
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    // 18.11.2026 ("Der Ziehbalken kann schmaler dargestellt werden, die Hitbox kann gleich bleiben"): a narrow
                    // gap between the panes; the bar to touch lies over it as wide as before
                    val leftShare = if (naviRight) 1f - share else share
                    val leftW = (maxWidth - SplitGap) * leftShare
                    Row(Modifier.fillMaxSize()) {
                        if (naviRight) music(Modifier.weight(1f - share)) else navi(Modifier.weight(share))
                        Box(Modifier.fillMaxHeight().width(SplitGap).background(Ink))
                        if (naviRight) navi(Modifier.weight(share)) else music(Modifier.weight(1f - share))
                    }
                    // 18.11.2026 ("Sollte eigentlich der Verschiebe-Slider funktionieren, dass der das live verschiebt, wenn man's
                    // anfasst und greift, dass man dann jede Stufe direkt festlegen kann. Da müssen sich die Apps ja nicht sofort
                    // ändern, aber vielleicht wieder dieses Overlay mit den türkisen Flächen wie beim App-Reinschieben"): the bar
                    // taken (held or drawn) shows both areas as they would be, at the step nearest to the finger; let go, the
                    // split takes that share - the apps change once, then
                    var grabAt by remember { mutableStateOf<Float?>(null) }
                    val widthPx = constraints.maxWidth.toFloat()
                    val barPx = with(androidx.compose.ui.platform.LocalDensity.current) { (leftW + SplitGap / 2).toPx() }
                    fun shareAt(x: Float): Float {
                        val left = (x / widthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                        val want = if (naviRight) 1f - left else left
                        return SHARES.filter { it >= minShare - 0.01f }.minByOrNull { kotlin.math.abs(it - want) } ?: target
                    }
                    grabAt?.let { x ->
                        SplitPreview(shareAt(x), naviRight, naviEntry?.label ?: stringResource(R.string.idmate_tab_navigation), player.label, anim)
                    }
                    SplitBar(Modifier.offset(x = leftW + SplitGap / 2 - SplitTouch / 2), swap = { swapSides() },
                        grab = { grabAt = barPx }, move = { dx -> grabAt = grabAt?.plus(dx) },
                        letGo = { take ->
                            val x = grabAt
                            grabAt = null
                            if (take && x != null) { val s = shareAt(x); naviShare = s; splitPrefs.edit().putFloat("navi_share", s).apply() }
                        })
                }
            } else Box(Modifier.fillMaxSize()) {
                when (inner) {
                    Inner.MESSAGES -> Partners(state)
                    // parked, but no phone app can be shown: why, and what to do
                    Inner.CINEMA_MISSING -> Box(Modifier.fillMaxSize().padding(bottom = ControlsHeight + ControlsMargin * 2), contentAlignment = Alignment.Center) {
                        Column(Modifier.widthIn(max = 720.dp).clip(RoundedCornerShape(24.dp)).background(Mate.cardSolid).padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.Movie, null, tint = Teal, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(16.dp))
                            Text(stringResource(R.string.cinema_title), color = Color.White, fontSize = Mate.title, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Text(state.cinemaMissing ?: "", color = TextDim, fontSize = Mate.body, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                    Inner.CINEMA -> {
                        val pm = LocalContext.current.packageManager
                        // the apps allowed for the drive (Claude, System L-114) are no films: in the Apps, not in the cinema
                        val chosen = state.apps.filter { it.kind == CarAppKind.STANDALONE && !net.prototux.aare.host.DrivingApps.allowed(it.packageName) && runCatching {
                            pm.getApplicationInfo(it.packageName, 0).category != android.content.pm.ApplicationInfo.CATEGORY_MAPS }.getOrDefault(true) }
                        val app = cinemaApp
                        if (app == null || !state.parkedForFilms) CinemaMenu(chosen) { cinemaApp = it }
                        else androidx.compose.runtime.CompositionLocalProvider(LocalCinemaMenu provides { cinemaApp = null }) {
                            androidx.compose.runtime.key(app.packageName) { appContent(app, Modifier.fillMaxSize()) }
                        }
                    }
                    null -> when (val screen = state.screen) {
                        // With the IDmate app installed its dashboard is the start view; it opens by itself (below).
                        CarScreen.Dashboard -> if (idmate == null) Dashboard(state, actions, navMap, Modifier.fillMaxSize().padding(10.dp))
                        CarScreen.Launcher -> Drawer(state, usage, open)
                        is CarScreen.App -> state.app(screen.appId)?.let { app ->
                            // Screens AARE draws for a media app (lists, now playing) keep clear of the controls:
                            // their own buttons reach into the bottom corners.
                            // The dashboard too: its map shall not lie half hidden behind the controls.
                            // Every other screen as well (phone, settings, browser...). Only car apps with templates keep the
                            // whole display - they are told what the controls cover instead (below).
                            val templated = !isDash(app) && (app.kind == CarAppKind.NAVIGATION || app.kind == CarAppKind.TEMPLATED || app.kind == CarAppKind.PROJECTED || app.kind == CarAppKind.MATE_NAVI)
                            val clear = if (templated) 0.dp else ControlsHeight + ControlsMargin * 2
                            // Car apps with templates (navigation and others) keep the whole display for their map and are
                            // told what the controls cover: their panels and what matters on the map stay out of there.
                            val told = if (templated) ControlsHeight + ControlsMargin * 2 else 0.dp
                            androidx.compose.runtime.CompositionLocalProvider(net.prototux.aare.host.templates.LocalCarBottomInset provides told) {
                                appContent(app, Modifier.fillMaxSize().padding(bottom = clear))
                            }
                        } ?: Drawer(state, usage, open)
                    }
                }
            }
            // an app let go over the screen: a new split 50:50 with it on that side, or in an open split it takes the place
            // of the app on that side (18.11.2026)
            // the split an app let go would make (System I-062): the open split's widths as shown, a new one 50:50; the side
            // keeps its width, only a navigation app of another maker takes two thirds at least
            fun dropAt(app: CarAppEntry, right: Boolean?): DropLayout {
                val isNavi = naviList.any { it.id == app.id }
                val current = naviApp(naviList, choice)
                return dropLayout(splitOn, naviShare, naviRight, right, isNavi,
                    foreign = (if (isNavi) app else current)?.let { it.kind != CarAppKind.MATE_NAVI } == true,
                    currentForeign = current?.let { it.kind != CarAppKind.MATE_NAVI } == true)
            }
            // an app let go over the screen: a new split 50:50 with it on that side, or in an open split it takes the place
            // of the app on that side, in that side's width (18.11.2026, I-062)
            fun drop(app: CarAppEntry, x: Float, width: Float) {
                val isNavi = naviList.any { it.id == app.id }
                val isMusic = musicList.any { it.id == app.id }
                if (!isNavi && !isMusic) return
                val wasSplit = splitOn
                val right = x > dropAt(app, null).boundary * width
                val lay = dropAt(app, right)
                naviRightState = lay.naviRight
                naviShare = lay.naviShare
                splitPrefs.edit().putBoolean("navi_right", lay.naviRight).putFloat("navi_share", lay.naviShare).apply()
                if (isNavi) choice.pickNavi(app.packageName) else { choice.pickMusic(app.packageName); splitMusic = app.id; actions.openApp(app) }
                val navi = if (isNavi) app else naviApp(naviList, choice)
                android.util.Log.i("IdmateShell", "app dropped: ${app.label} ${if (right) "right" else "left"}" + (if (wasSplit) " (replaced)" else " (new split)") +
                    String.format(java.util.Locale.ROOT, ", navi share %.2f", lay.naviShare))
                navi?.let(open)
                split = true
            }
            val appDrag = AppDrag(
                start = { app, at -> dragged = app; dragAt = at - shellOrigin },
                move = { d -> dragAt += d },
                end = { val a = dragged; dragged = null; switcher = false; if (a != null) drop(a, dragAt.x, constraints.maxWidth.toFloat()) },
                cancel = { dragged = null },
            )
            // the switcher folds in and out animated (switchable, 18.11.2026)
            androidx.compose.animation.AnimatedVisibility(visible = switcher,
                enter = if (anim) androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)) +
                    androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(260)) { it / 8 } else androidx.compose.animation.EnterTransition.None,
                exit = if (anim) androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)) +
                    androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(200)) { it / 8 } else androidx.compose.animation.ExitTransition.None) {
                val settings = remember { net.prototux.aare.Settings(context) }
                // "Fehler markieren" (test drive T1, O159): in the switcher, at its bottom right, if the phone app shows it
                val markFault = if (settings.faultMarkButton) ({ switcher = false; actions.markFault(); faultNote = true }) else null
                // the saved views (18.11.2026: "im Bedienfeld eine Möglichkeit, gespeicherte Ansichten aufzurufen"): a tile whose
                // list holds them; one tapped takes its apps and its start, a split view with its side and share
                val saved = remember(switcher) { MateViews(context).all }
                val viewArea = if (saved.isEmpty()) emptyList() else listOf(Area(androidx.compose.material.icons.Icons.Rounded.Bookmarks, stringResource(R.string.idmate_views), false,
                    choices = saved.map { v -> Choice(CarAppEntry("view:" + v.name, "", v.name, null, CarAppKind.TEMPLATED), v.name == lastView, draggable = false) {
                        switcher = false; lastView = v.name; applyView(v) } }, tapShowsList = true, open = {}))
                Switcher(areas + viewArea, overview, side, close = { switcher = false }, markFault = markFault, drag = appDrag, hidden = dragged != null) {
                    switcher = false
                    inner = null
                    // the split view ends here as it does for every other area (19.11.2026: "Im Bedienfeld steht Apps, aber er hat
                    // immer noch die geteilte Ansicht auf von Navi und Musik" - with the split as the saved start view the
                    // overview never came, so the phone and the other apps could not be reached)
                    split = false
                    actions.openLauncher()
                }
            }
            // A media app fills the screen: its own buttons join the controls, the sign for what plays would be double.
            val mediaFront = !splitOn && inner == null && (state.screen as? CarScreen.App)?.let { state.app(it.appId)?.kind } == CarAppKind.MEDIA
            // The controls grow a slim strip that names what plays - wherever that cannot be read otherwise: in every
            // area, and in the music lists too; only not where the player itself is on screen (cover, title, progress).
            // For the kinds of audio chosen in the settings.
            val settings = remember { net.prototux.aare.Settings(context) }
            val heard = state.nowPlaying?.takeIf { it.playing }
            val musicInFront = splitOn || inner == null && net.prototux.aare.media.MediaControls.playerShown &&
                (state.screen as? CarScreen.App)?.let { s -> state.app(s.appId)?.kind } == CarAppKind.MEDIA
            val onDashboard = !musicInFront
            val playingTitle = if (musicInFront || heard == null) null else {
                val pkg = state.app(heard.appId)?.packageName.orEmpty()
                val wanted = when {
                    AUDIO_BOOK_APPS.containsMatchIn(pkg) -> settings.dashTitleBooks
                    PODCAST_APPS.containsMatchIn(pkg) -> settings.dashTitlePodcasts
                    heard.durationMs >= 15 * 60_000L -> settings.dashTitleBooks
                    else -> settings.dashTitleMusic
                }
                // artist · album · title: the subtitle carries the first two, as the app gives them
                if (wanted) listOf(heard.subtitle, heard.title).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null } else null
            }
            androidx.compose.runtime.SideEffect { ShellDebug.onDashboard = onDashboard; ShellDebug.title = playingTitle; ShellDebug.titleShown = playingTitle != null }
            // The navigation app is in front (O154): no clock, message sign, reception or battery - the map shows the
            // arrival and the car the time; the music as everywhere else (O191). Not only while a route runs: Google Maps tells
            // no host that it guides (no notification, no call), and the time and reception are the car's anyway.
            val naviFront = !splitOn && inner == null && (state.screen as? CarScreen.App)?.let { s -> naviList.any { it.id == s.appId } } == true
            // A projected app's text field asked for a keyboard (Maps' search, O97): ours, German, in bursts while driving
            if (naviFront && net.prototux.aare.host.projected.ProjectedInput.hint != null) ProjectedKeyboard(state.drivingRestricted, Modifier.align(if (state.driverOnRight) Alignment.TopEnd else Alignment.TopStart).fillMaxWidth(net.prototux.aare.media.KEYBOARD_SHARE))
            else if (net.prototux.aare.host.projected.ProjectedInput.hint != null && !naviFront) net.prototux.aare.host.projected.ProjectedInput.close()
            // what the voice heard and did, above the controls at the driver's side; a tap closes it
            androidx.compose.runtime.DisposableEffect(Unit) {
                net.prototux.aare.speech.VoiceAssistant.host = object : net.prototux.aare.speech.VoiceAssistant.Host {
                    override val apps get() = state.apps.toList()
                    override val playing get() = state.nowPlaying?.playing == true
                    override fun open(app: CarAppEntry) = open(app)
                    override fun navigateTo(place: String) = actions.navigateTo(place)
                    override fun playPause() = actions.mediaPlayPause()
                    override fun next() = actions.mediaNext()
                    override fun previous() = actions.mediaPrevious()
                }
                onDispose { net.prototux.aare.speech.VoiceAssistant.host = null }
            }
            val vp = net.prototux.aare.speech.VoiceAssistant.phase
            if (vp != net.prototux.aare.speech.VoiceAssistant.Phase.IDLE) {
                val va = net.prototux.aare.speech.VoiceAssistant
                Column(Modifier.align(side).padding(start = ControlsMargin, end = ControlsMargin, bottom = ControlsHeight + ControlsMargin * 2)
                    .widthIn(max = 620.dp).clip(RoundedCornerShape(18.dp)).background(Mate.cardSolid).clickable { va.cancel() }.padding(16.dp)) {
                    val head = when (vp) {
                        net.prototux.aare.speech.VoiceAssistant.Phase.LISTEN -> stringResource(R.string.voice_listening)
                        net.prototux.aare.speech.VoiceAssistant.Phase.THINK -> stringResource(R.string.voice_thinking)
                        net.prototux.aare.speech.VoiceAssistant.Phase.ASK -> va.answer
                        else -> if (va.heard.isNotBlank()) stringResource(R.string.quoted, va.heard.trim()) else ""
                    }
                    if (vp == net.prototux.aare.speech.VoiceAssistant.Phase.CONFIRM) {
                        // a call waits: the contact's own name, the seconds left, X and "Jetzt anrufen" (18.11.2026)
                        Text(stringResource(R.string.voice_call_confirm, va.answer), color = Color.White, fontSize = Mate.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (va.secondsLeft > 0) stringResource(R.string.voice_call_in, va.secondsLeft) else stringResource(R.string.voice_call_cancel_hint),
                            color = TextDim, fontSize = Mate.body, modifier = Modifier.padding(top = 4.dp))
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(72.dp).clip(CircleShape).background(Mate.callEnd).clickable { va.cancel() }, contentAlignment = Alignment.Center) {
                                Icon(androidx.compose.material.icons.Icons.Rounded.Close, stringResource(R.string.idmate_dictate_cancel), tint = Color.White, modifier = Modifier.size(36.dp))
                            }
                            Box(Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(16.dp)).background(Teal).clickable { va.now() }, contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.voice_call_now), color = Ink, fontSize = Mate.heading, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    } else if (head.isNotBlank()) Text(head, color = Color.White, fontSize = Mate.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if ((vp == net.prototux.aare.speech.VoiceAssistant.Phase.DONE || vp == net.prototux.aare.speech.VoiceAssistant.Phase.FAILED) && va.answer.isNotBlank())
                        Text(va.answer, color = if (vp == net.prototux.aare.speech.VoiceAssistant.Phase.DONE) Teal else TextDim, fontSize = Mate.body, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
            }
            // Navi L-001: "Wie war die Ladung?" after a charge noticed, over the controls at the driver's side; not over
            // the fault's card
            val chargeAsk = net.prototux.aare.ui.car.navi.ChargeAsk.pending.value
            if (chargeAsk != null && !faultNote) net.prototux.aare.ui.car.navi.ChargeAskCard(chargeAsk,
                Modifier.align(side).padding(start = ControlsMargin, end = ControlsMargin, bottom = ControlsHeight + ControlsMargin * 2)) {
                net.prototux.aare.ui.car.navi.ChargeAsk.pending.value = null }
            if (faultNote) FaultNoteCard(Modifier.align(side).padding(start = ControlsMargin, end = ControlsMargin, bottom = ControlsHeight + ControlsMargin * 2),
                send = { actions.faultNote(it) }, close = { faultNote = false })
            Controls(state, actions, areas, overview, switcher, Modifier.align(side), mediaFront, playingTitle,
                playing = if (player != null && state.nowPlaying != null) splitOn else null, toggleSplit = { split = !split; switcher = false },
                showPlaying = { player?.let { net.prototux.aare.media.NowPlayingRequest.count++; if (!splitOn) open(it) } },
                call = if (state.call?.ringing == false) ({ callOpen = !callOpen; switcher = false }) else null, naviFront = naviFront) { switcher = !switcher }
            overlay(Modifier.align(Alignment.BottomCenter).padding(bottom = ControlsHeight + ControlsMargin * 2))
            NoticeOverlay(state, actions, Modifier.align(Alignment.TopCenter))
            // Calls never lie over the middle of the map: a small card right above the controls.
            CallCorner(state, actions, callOpen, Modifier.align(side).padding(start = ControlsMargin, end = ControlsMargin, bottom = ControlsHeight + ControlsMargin * 2)) { callOpen = false }
            dragged?.let { app -> DragLayer(app, dragAt, naviList.any { it.id == app.id } || musicList.any { it.id == app.id }, splitOn, anim) { r -> dropAt(app, r) } }
        }
        }
    }
}

/**
 * The keyboard for a projected app's text field (O97): Maps' search, typed here and handed to Maps as a search.
 * While driving it works in bursts like the music search - 5 s of typing from the first key, then 5 s of rest.
 */
@Composable
private fun ProjectedKeyboard(driving: Boolean, modifier: Modifier) {
    val input = net.prototux.aare.host.projected.ProjectedInput
    var typing by remember { mutableStateOf(false) }
    var rest by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(typing, driving) { if (typing && driving) { delay(5_000); typing = false; rest = 5 } }
    LaunchedEffect(rest > 0) { while (rest > 0) { delay(1_000); rest-- } }
    // the width comes from the caller: the driver's part of the screen (test drive U1)
    Column(modifier.fillMaxHeight(0.88f).padding(16.dp).clip(RoundedCornerShape(22.dp)).background(Ink).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(Mate.plate).clickable { input.close() }, contentAlignment = Alignment.Center) {
                Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.idmate_dictate_cancel), tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(16.dp))
            Text(input.text.ifEmpty { input.hint.orEmpty() }, color = if (input.text.isEmpty()) TextDim else Color.White, fontSize = Mate.display, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Mate.plate).padding(horizontal = 20.dp, vertical = 12.dp))
        }
        Spacer(Modifier.height(12.dp))
        if (driving && rest > 0) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.search_attention, rest), color = TextDim, fontSize = Mate.title)
        } else net.prototux.aare.media.CarKeyboard(
            onText = { input.text += it; if (driving) typing = true },
            onBackspace = { input.text = input.text.dropLast(1) },
            onClear = { input.text = "" },
            onSearch = { input.submit() },
            searchEnabled = input.text.isNotBlank(),
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

/** The combustion switch of the phone app (O99), followed while the car screen is on. */
@Composable
private fun produceCombustion(context: Context): androidx.compose.runtime.State<Boolean> {
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    val state = remember { mutableStateOf(prefs.getBoolean("combustion", false)) }
    androidx.compose.runtime.DisposableEffect(prefs) {
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, k -> if (k == "combustion") state.value = p.getBoolean(k, false) }
        prefs.registerOnSharedPreferenceChangeListener(l)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(l) }
    }
    return state
}

/** How often each app was opened from this shell, kept across drives: the order of the app overview. */
private class AppUsage(context: Context) {
    private val prefs = context.getSharedPreferences("idmate_usage", Context.MODE_PRIVATE)
    var version by mutableStateOf(0)
        private set

    fun of(id: String): Int = prefs.getInt(id, 0)

    fun count(id: String) {
        prefs.edit().putInt(id, of(id) + 1).apply()
        version++
    }
}

/** One of several apps that can fill an area: offered above the area's tile in the switcher. */
private class Choice(val app: CarAppEntry, val selected: Boolean, val pinned: Boolean? = null, val togglePin: () -> Unit = {},
                     val draggable: Boolean = true, val pick: () -> Unit)

/**
 * An area of the switcher. [choices] always stand above its tile (the apps pinned beside the default one);
 * [all] every app that can fill the area; a swipe up on the tile shows the pinned ones, or all of them.
 */
private class Area(
    val icon: ImageVector, val label: String, val selected: Boolean,
    val choices: List<Choice> = emptyList(), val all: List<Choice> = emptyList(),
    /** The logo of the app filling the area (music: the service in use, O95), shown instead of [icon] when known. */
    val logo: Bitmap? = null,
    /** A tap shows the list above the tile instead of opening something (the saved views). */
    val tapShowsList: Boolean = false,
    /** The app a long press takes in the hand, to lay it into a split (the navigation's or the music's). */
    val dragApp: CarAppEntry? = null,
    /** A second half of the tile with its own tap (System L-094: messenger | phone); the swipe up then shows [people]. */
    val half: Area? = null,
    /** People to call from the swipe up of a split tile: name and the call. */
    val people: List<Pair<String, () -> Unit>> = emptyList(),
    val open: () -> Unit,
)

/** An app held in the hand from the switcher (18.11.2026): where it is, in the shell's own coordinates. */
private class AppDrag(val start: (CarAppEntry, Offset) -> Unit, val move: (Offset) -> Unit, val end: () -> Unit, val cancel: () -> Unit)

/** A long press takes [app] in the hand; then it follows the finger until let go. */
private fun Modifier.dragApp(app: CarAppEntry?, h: AppDrag?): Modifier = if (app == null || h == null) this else composed {
    var origin by remember { mutableStateOf(Offset.Zero) }
    this.onGloballyPositioned { origin = it.positionInRoot() }
        .pointerInput(app.id) {
            detectDragGesturesAfterLongPress(
                onDragStart = { h.start(app, origin + it) }, onDragEnd = h.end, onDragCancel = h.cancel,
            ) { c, d -> c.consume(); h.move(d) }
        }
}

/** Which app fills the navigation and the music area when several are installed; kept across drives. */
private class AreaChoice(context: Context) {
    private val prefs = context.getSharedPreferences("idmate_choice", Context.MODE_PRIVATE)
    var navi by mutableStateOf(prefs.getString("navi", null))
        private set
    var music by mutableStateOf(prefs.getString("music", null))
        private set

    // The same choice can be made in the app on the phone (ui/MainActivity.kt): what changes there shows here at once.
    private val watch = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, _ ->
        navi = p.getString("navi", null)
        music = p.getString("music", null)
        extras = p.getStringSet("music_extra", emptySet())?.toSet() ?: emptySet()
    }

    init { prefs.registerOnSharedPreferenceChangeListener(watch) }

    fun pickNavi(pkg: String) {
        navi = pkg
        prefs.edit().putString("navi", pkg).apply()
    }

    /** The music app that played last, kept across restarts (B8): the music button opens it when nothing plays now. */
    var lastPlayed by mutableStateOf(prefs.getString("music_last", null))
        private set

    fun played(pkg: String) {
        if (pkg == lastPlayed) return
        lastPlayed = pkg
        prefs.edit().putString("music_last", pkg).apply()
    }

    fun pickMusic(pkg: String) {
        music = pkg
        prefs.edit().putString("music", pkg).apply()
    }

    /** The apps shown beside the default music app at all times: an audio book app, a podcast app. */
    var extras by mutableStateOf<Set<String>>(prefs.getStringSet("music_extra", emptySet())?.toSet() ?: emptySet())
        private set

    fun toggleExtra(pkg: String) {
        extras = if (pkg in extras) extras - pkg else extras + pkg
        prefs.edit().putStringSet("music_extra", HashSet(extras)).apply()
    }
}

/**
 * What can fill the navigation area: the navigation apps with a car screen of their own and, of the phone apps
 * allowed onto the car screen, the map apps (Google Maps has no car screen for other hosts than Google's).
 */
private fun naviApps(state: CarUiState, pm: android.content.pm.PackageManager): List<CarAppEntry> = state.apps.filter {
    it.packageName != GOOGLE_AUTO_PACKAGE && it.packageName != IDMATE_PACKAGE && !isDash(it) && when (it.kind) {
        CarAppKind.NAVIGATION, CarAppKind.PROJECTED, CarAppKind.MATE_NAVI -> true
        CarAppKind.STANDALONE -> runCatching {
            pm.getApplicationInfo(it.packageName, 0).category == android.content.pm.ApplicationInfo.CATEGORY_MAPS
        }.getOrDefault(false)
        else -> false
    }
}

/**
 * The app of the navigation area: the one chosen, else Google Maps, else ABRP when installed, else any other - among the apps that
 * take this host. An app that refused it (HostRejections) is stepped over while another one works.
 */
private fun naviApp(list: List<CarAppEntry>, choice: AreaChoice): CarAppEntry? {
    val refused = net.prototux.aare.host.HostRejections.packages
    val working = list.filter { it.packageName !in refused }.ifEmpty { list }
    return working.firstOrNull { it.packageName == choice.navi } ?: working.firstOrNull { it.packageName == GOOGLE_MAPS_PACKAGE }
        ?: working.firstOrNull { it.packageName == ABRP_PACKAGE } ?: working.firstOrNull()
}

/**
 * What can fill the music area: the media apps, the built-in music (the own Plex server, package [own]) first.
 * A music app may come with a car screen of its own (Spotify, YouTube Music) and is then listed as a car app, not
 * as a media app: it still belongs here - told by the media service it offers besides.
 */
private fun musicApps(state: CarUiState, own: String, pm: android.content.pm.PackageManager): List<CarAppEntry> =
    state.apps.filter {
        it.packageName !in NO_MUSIC_PACKAGES &&
            (it.kind == CarAppKind.MEDIA || it.kind == CarAppKind.TEMPLATED && offersMedia(pm, it.packageName))
    }.distinctBy { it.packageName }.map { app ->
        // A car screen's entry carries the name of its service ("SETTINGS" for YouTube Music): here the app's own name counts.
        if (app.kind != CarAppKind.TEMPLATED) app else {
            val name = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(app.packageName, 0)).toString() }.getOrNull()
            if (name.isNullOrBlank() || name == app.label) app else CarAppEntry(app.id, app.packageName, name, app.icon, app.kind)
        }
    }.sortedByDescending { it.packageName == own }

private val mediaOffer = HashMap<String, Boolean>()

/** Whether [pkg] has a media browser or media library service (asked once per package). */
private fun offersMedia(pm: android.content.pm.PackageManager, pkg: String): Boolean = mediaOffer.getOrPut(pkg) {
    listOf("android.media.browse.MediaBrowserService", "androidx.media3.session.MediaLibraryService").any { action ->
        runCatching { pm.queryIntentServices(android.content.Intent(action).setPackage(pkg), 0).isNotEmpty() }.getOrDefault(false)
    }
}

/** The areas this shell draws itself, not an app's screen. */
private enum class Inner { MESSAGES, CINEMA_MISSING, CINEMA }

/**
 * The areas of this style, in their fixed order: dashboard, navigation, music, messages.
 * The dashboard is the IDmate car app when installed (AARE's own dashboard otherwise) and music is the
 * media app in use; navigation and messages are drawn by this shell. Music is left out without a media app.
 */
@Composable
private fun areas(
    state: CarUiState, actions: CarActions, open: (CarAppEntry) -> Unit, inner: Inner?,
    naviList: List<CarAppEntry>, musicList: List<CarAppEntry>, choice: AreaChoice, show: (Inner?) -> Unit,
): List<Area> {
    val shown = if (inner == null) (state.screen as? CarScreen.App)?.appId else null
    val idmate = state.apps.firstOrNull { isDash(it) }
    // Music: the default app chosen in the switcher, IDmate Musik without a choice.
    val media = musicList.firstOrNull { it.packageName == choice.music } ?: musicList.firstOrNull()
    val out = ArrayList<Area>(5)
    // The dashboard is the start view: the IDmate app when installed (see IdmateShell), AARE's own otherwise.
    out += Area(Icons.Rounded.SpaceDashboard, stringResource(R.string.idmate_tab_dashboard),
        inner == null && (if (idmate != null) shown == idmate.id else state.screen == CarScreen.Dashboard)) {
        show(null)
        if (idmate != null) open(idmate) else actions.goHome()
    }
    // Navigation: the chosen navigation app; Mate Navi is built in, so the area is there without any other one.
    val navi = naviApp(naviList, choice)
    // One navigation app wins. With several installed, a swipe up on the tile shows the choice.
    val naviAll = if (naviList.size < 2) emptyList() else naviList.map { app ->
        Choice(app, app.id == navi?.id) { choice.pickNavi(app.packageName); open(app) }
    }
    if (navi != null) out += Area(Icons.Rounded.Navigation, stringResource(R.string.idmate_tab_navigation), naviList.any { it.id == shown }, all = naviAll, dragApp = navi) { open(navi) }
    // Music has one default app too, and beside it the apps pinned in the large choice (audio books, podcasts).
    val musicPinned = musicList.filter { it.id != media?.id && it.packageName in choice.extras }.map { app ->
        Choice(app, shown == app.id) { open(app) }
    }.let { pinned ->
        // the service in use stands with the others (O95): no app of the row seems to be missing
        if (pinned.isEmpty() || media == null) pinned else listOf(Choice(media, shown == media.id) { open(media) }) + pinned
    }
    // Its logo on the tile and the area button: the music app in front, else the one playing, else the default.
    // One source for logo and tap (B8): in front, playing now, played last (kept across restarts), the default.
    val playingNow = musicList.firstOrNull { it.id == state.activeMediaAppId }
    if (playingNow != null && state.nowPlaying?.playing == true) choice.played(playingNow.packageName)
    val musicNow = musicList.firstOrNull { it.id == shown } ?: playingNow
        ?: musicList.firstOrNull { it.packageName == choice.lastPlayed } ?: media
    val musicAll = if (musicList.size < 2) emptyList() else musicList.map { app ->
        Choice(app, app.id == media?.id, pinned = app.packageName in choice.extras, togglePin = { choice.toggleExtra(app.packageName) }) {
            choice.pickMusic(app.packageName); open(app)
        }
    }
    if (media != null) out += Area(Icons.Rounded.MusicNote, stringResource(R.string.idmate_tab_music), musicList.any { it.id == shown }, musicPinned, musicAll, musicNow?.icon, dragApp = musicNow ?: media) { open(musicNow ?: media) }
    // System L-094 (22.11.2026: "den Button Messenger teilen in Messenger und Telefon, damit ich nicht jedes Mal auf Apps
    // gehen muss … Wenn ich dann nach oben wische … zeigt er mir meine Favoriten an. Maria, anrufen"): one tile, two halves
    val ctx = LocalContext.current
    val phoneApp = state.apps.firstOrNull { it.kind == CarAppKind.PHONE }
    // read off the main thread, and only with the rights to read them (a missing right left the list empty unseen)
    val people by androidx.compose.runtime.produceState(emptyList<Pair<String, String>>(), phoneApp != null) {
        val allowed = listOf(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.READ_CALL_LOG)
            .any { ctx.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        value = if (phoneApp == null || !allowed) emptyList()
            else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { phoneFavourites(ctx) }
    }
    out += Area(Icons.Rounded.Chat, stringResource(R.string.idmate_tab_messages), inner == Inner.MESSAGES,
        half = phoneApp?.let { p -> Area(Icons.Rounded.Call, stringResource(R.string.idmate_tab_phone), shown == p.id) { show(null); open(p) } },
        people = people.map { (name, number) -> name to { actions.placeCall(number) } }) { show(Inner.MESSAGES) }
    // Kino (18.11.2026: "dass ich die YouTube-App dann quasi einfasse in das Mate-System"): the phone apps allowed onto the
    // car screen that are no map apps - shown only while the car stands parked for sure (ParkedGate, CarRuntime)
    val pm = LocalContext.current.packageManager
    val cinema = state.apps.filter { it.kind == CarAppKind.STANDALONE && !net.prototux.aare.host.DrivingApps.allowed(it.packageName) && runCatching {
        pm.getApplicationInfo(it.packageName, 0).category != android.content.pm.ApplicationInfo.CATEGORY_MAPS }.getOrDefault(true) }
    // the tile only while the car stands parked for sure (18.11.2026: "Der Bereich Kino, der sollte nur im Stand angezeigt
    // werden, wenn alle Bedingungen erfüllt sind")
    // parked, but no app can come (Shizuku not started, 19.11.2026): the area all the same, saying why
    val available = net.prototux.aare.launch.AppLauncher.available()
    val missing = cinema.isEmpty() && state.parkedForFilms && state.cinemaMissing != null && !available
    if (missing)
        out += Area(Icons.Rounded.Movie, stringResource(R.string.cinema_title), inner == Inner.CINEMA_MISSING) { show(Inner.CINEMA_MISSING) }
    // parked and a way to show phone apps there: the cinema's app menu (any app of the tablet, the chosen ones first)
    val canShow = cinema.isNotEmpty() || (state.parkedForFilms && state.cinemaMissing == null) || available
    if (state.parkedForFilms && canShow && !missing)
        out += Area(Icons.Rounded.Movie, stringResource(R.string.cinema_title), inner == Inner.CINEMA || cinema.any { it.id == shown }) { show(Inner.CINEMA) }
    return out
}

/** The controls on their dimmed patch. Left-hand drive: voice, area, time, status from the left; mirrored otherwise. */
@Composable
private fun Controls(
    state: CarUiState, actions: CarActions, areas: List<Area>, overview: Boolean, switcher: Boolean, modifier: Modifier,
    mediaFront: Boolean, title: String?, playing: Boolean?, toggleSplit: () -> Unit, showPlaying: () -> Unit, call: (() -> Unit)?,
    naviFront: Boolean = false, toggle: () -> Unit,
) {
    val current = if (overview) null else areas.firstOrNull { it.selected }
    // The one microphone (O103): what it listens for follows the area in front - a destination for the navigation
    // app on screen, a music wish in Mate Musik; elsewhere, and on a long press, the phone's assistant.
    val voice: @Composable () -> Unit = {
        val ctx = LocalContext.current
        val va = net.prototux.aare.speech.VoiceAssistant.phase
        val thinking = va == net.prototux.aare.speech.VoiceAssistant.Phase.THINK
        val on = state.listening || va in setOf(net.prototux.aare.speech.VoiceAssistant.Phase.THINK,
            net.prototux.aare.speech.VoiceAssistant.Phase.LISTEN, net.prototux.aare.speech.VoiceAssistant.Phase.ASK)
        val musicFront = mediaFront && net.prototux.aare.media.MediaControls.voice
        // the face stays 40 dp; it is hit over the bar's whole height and 56 dp wide (18.11.2026: 8 mm were too small)
        Box(Modifier.fillMaxHeight().widthIn(min = 56.dp)
            .pointerInput(naviFront, musicFront) {
                detectTapGestures(
                    // 18.11.2026 ("ich möchte mich von Googles Spracherkennung trennen"): Whisper and Mate's commands; what is
                    // said without a command word goes where the screen is (a place, music). A long press: the phone's assistant.
                    onTap = {
                        net.prototux.aare.speech.VoiceAssistant.tap(ctx, when {
                            naviFront -> net.prototux.aare.speech.VoiceAssistant.Hint.NAVI
                            musicFront -> net.prototux.aare.speech.VoiceAssistant.Hint.MUSIC
                            else -> net.prototux.aare.speech.VoiceAssistant.Hint.NONE
                        })
                    },
                    onLongPress = { actions.assistant() },
                )
            }, contentAlignment = Alignment.Center) {
          Box(Modifier.size(40.dp).clip(CircleShape).background(if (on) Teal else PanelHigh), contentAlignment = Alignment.Center) {
            if (thinking) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), color = Ink, strokeWidth = 2.5.dp)
            else Icon(Icons.Rounded.Mic, stringResource(R.string.idmate_voice), tint = if (on) Ink else Color.White, modifier = Modifier.size(20.dp))
          }
        }
    }
    // The one button: names what is in front, opens the switcher.
    val areaButton: @Composable () -> Unit = {
        BarHit(toggle) { Row(
            Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(if (switcher) PanelHigh else Mate.plate)
                .clickable(onClick = toggle).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The app that fills the area, by its own name and logo - "Podimo", not "Musik" (O163); the area's name
            // where none is in front (dashboard, messenger, the overview).
            val front = (state.screen as? CarScreen.App)?.let { state.app(it.appId) }
                ?.takeIf { current != null && (current.icon == Icons.Rounded.Navigation || current.icon == Icons.Rounded.MusicNote) }
            val logo = front?.icon ?: current?.logo
            if (logo != null) Image(logo.asImageBitmap(), null, Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)))
            else Icon(current?.icon ?: if (overview) Icons.Rounded.Apps else Icons.Rounded.SpaceDashboard, null, tint = Teal, modifier = Modifier.size(20.dp))
            Text(front?.label ?: current?.label ?: stringResource(if (overview) R.string.idmate_apps else R.string.idmate_areas), color = Color.White, fontSize = Mate.body, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
            Icon(Icons.Rounded.ExpandLess, null, tint = Mate.textSoft, modifier = Modifier.size(18.dp))
        } }
    }
    // Something is playing: its sign in the controls. A tap opens what is playing, a long press puts it next to the navigation.
    val nowPlaying: @Composable () -> Unit = {
        val on = playing == true
        Box(Modifier.fillMaxHeight().widthIn(min = 56.dp)
            .pointerInput(Unit) { detectTapGestures(onTap = { showPlaying() }, onLongPress = { toggleSplit() }) }, contentAlignment = Alignment.Center) {
          Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Teal else Mate.plate), contentAlignment = Alignment.Center) {
            Icon(if (state.nowPlaying?.playing == true) Icons.Rounded.GraphicEq else Icons.Rounded.MusicNote,
                stringResource(R.string.idmate_split), tint = if (on) Ink else Teal, modifier = Modifier.size(22.dp))
          }
        }
    }
    // A call is on: its green sign; a tap shows the call card.
    val onCall: @Composable () -> Unit = {
        BarHit(call ?: {}) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(CallGreen).clickable(onClick = call ?: {}), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Call, stringResource(R.string.idmate_call_on), tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
    val rhd = state.driverOnRight
    val gap: @Composable () -> Unit = { Spacer(Modifier.width(12.dp)) }
    // Play/pause and next always at the driver's corner, on every screen: no reaching across a wide display.
    // They stay while something plays and after a pause too (B9: one tap on play goes on after a call or a few
    // kilometres); they shrink to nothing only when the app has ended its session.
    val np = state.nowPlaying
    var mediaShown by remember { mutableStateOf(false) }
    LaunchedEffect(playing != null, np?.playing) {
        when {
            playing == null || np == null -> mediaShown = false
            else -> mediaShown = true
        }
    }
    val playPause: @Composable () -> Unit = {
        val on = np?.playing == true
        BarHit({ actions.mediaPlayPause() }) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Teal).clickable { actions.mediaPlayPause() }, contentAlignment = Alignment.Center) {
                Icon(if (on) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, stringResource(if (on) R.string.media_action_pause else R.string.media_action_play),
                    tint = Ink, modifier = Modifier.size(26.dp))
            }
        }
    }
    val next: @Composable () -> Unit = {
        BarHit({ actions.mediaNext() }) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Mate.plate).clickable { actions.mediaNext() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.SkipNext, stringResource(R.string.media_action_next), tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
    // audio books and podcasts are wound, not skipped (O158): -10 s before, +30 s after play/pause
    val longForm = np != null && (np.durationMs >= 15 * 60_000L || state.app(np.appId)?.packageName?.let { pkg ->
        AUDIO_BOOK_APPS.containsMatchIn(pkg) || PODCAST_APPS.containsMatchIn(pkg) } == true)
    val back10: @Composable () -> Unit = {
        BarHit({ actions.mediaSeekBy(-10_000) }) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Mate.plate).clickable { actions.mediaSeekBy(-10_000) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Replay10, stringResource(R.string.media_action_back10), tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
    val fwd30: @Composable () -> Unit = {
        BarHit({ actions.mediaSeekBy(30_000) }) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Mate.plate).clickable { actions.mediaSeekBy(30_000) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Forward30, stringResource(R.string.media_action_forward30), tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
    val media: @Composable () -> Unit = {
        androidx.compose.animation.AnimatedVisibility(
            visible = mediaShown,
            // in: the bar makes room, then the buttons grow; out: the buttons shrink to a point, then the bar closes
            enter = androidx.compose.animation.expandHorizontally(androidx.compose.animation.core.tween(280)) +
                androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(240, delayMillis = 240), initialScale = 0.02f),
            exit = androidx.compose.animation.scaleOut(androidx.compose.animation.core.tween(260), targetScale = 0.02f) +
                androidx.compose.animation.shrinkHorizontally(androidx.compose.animation.core.tween(320, delayMillis = 260)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val inner = when {
                    longForm && mediaFront -> listOf(back10, gap, playPause, gap, fwd30)
                    longForm -> listOf(back10, gap, playPause, gap, fwd30, gap, nowPlaying)
                    mediaFront -> listOf(playPause, gap, next)
                    else -> listOf(playPause, gap, next, gap, nowPlaying)
                }
                if (!rhd) gap()
                (if (rhd) inner.reversed() else inner).forEach { it() }
                if (rhd) gap()
            }
        }
    }
    // from the driver's edge inwards; the gaps are parts of their own so that nothing is left behind by a part that is gone
    val parts = ArrayList<@Composable () -> Unit>()
    parts += voice
    parts += gap
    parts += areaButton
    // Mate Navi guiding behind another area: the next turn in the bar, as Android Auto shows it; a tap brings the map
    // read only with another area in front: the bar is not redrawn for every 10 m while the map shows the turn itself
    val turn = if (naviFront || !net.prototux.aare.ui.car.navi.NaviKeep.guiding.value) null else net.prototux.aare.ui.car.navi.NaviKeep.nextTurn.value
    // System L-004 ("Das soll für alle Navi-Apps gelten, insbesondere für die Google-Navigation"): another navigation
    // app's next turn the same way - a hosted app's trip (ABRP, Waze), else the phone app's guidance read from its
    // notification (Google Maps): its own arrow and the distance
    val hostedTurn = if (naviFront || turn != null) null else state.trip?.let { t ->
        val m = t.steps.firstOrNull()?.maneuver
        val d = t.stepTravelEstimates.firstOrNull()?.remainingDistance
        if (m != null && d != null) net.prototux.aare.ui.car.navi.NaviKeep.NextTurn(m.type, m.roundaboutExitAngle, d) else null
    }
    val phoneTurn = if (naviFront || turn != null || hostedTurn != null) null else state.phoneGuidance
    if (hostedTurn != null || phoneTurn != null) {
        val navi = areas.firstOrNull { it.icon == Icons.Rounded.Navigation }
        parts += gap
        parts += {
          // the whole height of the bar is hit, as with its buttons (10 mm in the car; the 40 dp chip alone was 8 mm)
          BarHit({ navi?.open?.invoke() }) {
            Row(Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(Mate.plate).clickable { navi?.open?.invoke() }
                .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hostedTurn != null) {
                    net.prototux.aare.host.templates.TurnGlyph(hostedTurn.type, hostedTurn.angle, 24.dp, Teal)
                    Text(net.prototux.aare.host.templates.CarFormat.distance(hostedTurn.distance.displayDistance, hostedTurn.distance.displayUnit),
                        color = Color.White, fontSize = Mate.body, fontWeight = FontWeight.SemiBold, maxLines = 1)
                } else phoneTurn?.let { g ->
                    g.arrow?.let { a -> androidx.compose.foundation.Image(remember(a) { a.asImageBitmap() }, null, Modifier.size(24.dp),
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Teal)) }
                    Text(g.distance?.let { d -> d.shown + " " + d.unit.short } ?: g.primary.take(24),
                        color = Color.White, fontSize = Mate.body, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
          }
        }
    }
    if (turn != null) {
        val navi = areas.firstOrNull { it.icon == Icons.Rounded.Navigation }
        parts += gap
        parts += {
          BarHit({ navi?.open?.invoke() }) {
            Row(Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(Mate.plate).clickable { navi?.open?.invoke() }
                .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // with a roundabout's exit angle, as the map and the head-up display show it
                net.prototux.aare.host.templates.TurnGlyph(turn.type, turn.angle, 24.dp, Teal)
                Text(net.prototux.aare.host.templates.CarFormat.distance(turn.distance.displayDistance, turn.distance.displayUnit),
                    color = Color.White, fontSize = Mate.body, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
          }
        }
    }
    val mc = net.prototux.aare.media.MediaControls
    if (mediaFront && (mc.search != null || mc.nowPlaying != null || mc.browse != null)) { parts += gap; parts += { net.prototux.aare.media.HostedMediaControls() } }
    if (call != null) { parts += gap; parts += onCall }
    // The player itself is on screen with its own play/pause and next: no second pair in the bar (O154). With the
    // navigation in front the music looks as everywhere else - buttons, sign and title (O191, decision of 16.11.2026)
    if (!(mediaFront && mc.playerShown)) parts += media
    // While a route is driven the car shows the time and the map the arrival: no clock, no message sign, no
    // reception and battery here (O154). What listens or watches stays - that is never hidden.
    if (!naviFront) {
        parts += gap
        parts += { Time() }
    }
    // Messages wait unread in the background: a teal sign beside the phone's status; a tap opens them.
    if (state.unseen > 0 && !naviFront) {
        val messages = areas.firstOrNull { it.icon == Icons.Rounded.Chat }
        parts += gap
        parts += {
            BarHit({ messages?.open?.invoke() }) {
                Box(Modifier.size(40.dp).clip(CircleShape).clickable { messages?.open?.invoke() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Chat, stringResource(R.string.idmate_tab_messages), tint = Teal, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
    parts += gap
    // beside reception and battery: what is listening or watching right now
    parts += { InUseDots() }
    if (!naviFront) parts += { PhoneStatusIcons(offline = Mate.warn) }
    // a film in front (Kino, 18.11.2026: "Ich hätte dann unten links eigentlich nur noch den Mikrofonknopf und das
    // Mate-Bedienfeld ohne die ganzen Zusatzinfos. Alles andere wäre aus"): the microphone and the area button, nothing else
    val cinemaFront = (state.screen as? CarScreen.App)?.let { sc -> state.app(sc.appId)?.kind == CarAppKind.STANDALONE } == true
    if (cinemaFront) { parts.clear(); parts += voice; parts += gap; parts += areaButton }
    // The bar, and - when a title is to be named - a slim strip that runs on from its lower edge, away from the
    // driver: the same dimmed patch, the bar's corner there left square and the step between them rounded.
    val r = 18.dp
    val barShape = when {
        title == null -> RoundedCornerShape(r)
        rhd -> RoundedCornerShape(topStart = r, topEnd = r, bottomEnd = r, bottomStart = 0.dp)
        else -> RoundedCornerShape(topStart = r, topEnd = r, bottomEnd = 0.dp, bottomStart = r)
    }
    val bar: @Composable () -> Unit = {
        Row(
            Modifier.height(ControlsHeight).clip(barShape).background(Dim).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            (if (rhd) parts.reversed() else parts).forEach { it() }
        }
    }
    val strip: @Composable () -> Unit = {
        if (title != null) Column(horizontalAlignment = if (rhd) Alignment.End else Alignment.Start) {
            val dim = Dim
            // the rounded step: what lies between the bar's side and the strip's top, less a quarter circle
            androidx.compose.foundation.Canvas(Modifier.size(TitleStep).then(if (rhd) Modifier.graphicsLayer { scaleX = -1f } else Modifier)) {
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(0f, 0f); lineTo(0f, size.height); lineTo(size.width, size.height)
                    arcTo(androidx.compose.ui.geometry.Rect(0f, -size.height, size.width * 2, size.height), 90f, 90f, false)
                    close()
                }
                drawPath(p, dim)
            }
            Row(
                Modifier.height(TitleHeight).widthIn(max = 560.dp)
                    .clip(if (rhd) RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp) else RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp))
                    .background(dim).padding(start = 12.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.GraphicEq, null, tint = Teal, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, color = Mate.text, fontSize = Mate.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Row(modifier.padding(ControlsMargin), verticalAlignment = Alignment.Bottom) {
        if (rhd) { strip(); bar() } else { bar(); strip() }
    }
}

/** For tests (CarTools "debug_ui"): what this shell decided to show, where a picture of the screen may not be taken. */
internal object ShellDebug {
    @Volatile var onDashboard = false
    @Volatile var title: String? = null
    /** The title strip runs along the bottom of the screen: pages keep their own bottom corner clear of it (O191). */
    var titleShown by androidx.compose.runtime.mutableStateOf(false)
}

/** The switcher's tiles: square, of a size a hand reaches (O196); an area's app list is wider for the names. */
private val SwitcherTile = 128.dp
private val SwitcherList = 230.dp

/** The strip that names what plays, beside the controls on the dashboard: its height and the rounded step up to the bar. */
private val TitleHeight = 30.dp
private val TitleStep = 10.dp
private val AUDIO_BOOK_APPS = Regex("audible|audiobook|storytel|bookbeat|librivox", RegexOption.IGNORE_CASE)
private val PODCAST_APPS = Regex("podcast|podimo|pocketcast|antennapod|castbox", RegexOption.IGNORE_CASE)

/**
 * The switcher: the areas and the entry to the app overview as large tiles over the content, just
 * above the controls. A tap beside them closes it.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Switcher(areas: List<Area>, inOverview: Boolean, side: Alignment, close: () -> Unit, markFault: (() -> Unit)? = null,
                     drag: AppDrag? = null, hidden: Boolean = false, openOverview: () -> Unit) {
    BoxWithConstraints(
        // while an app is held the switcher stays (its finger is on it) but is not seen: the screen is where it goes
        Modifier.fillMaxSize().graphicsLayer { alpha = if (hidden) 0f else 1f }.background(Mate.scrimSwitcher)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = close),
        contentAlignment = side,
    ) {
        val tiles = areas.map { Area(it.icon, it.label, it.selected && !inOverview, it.choices, it.all, it.logo, it.tapShowsList, it.dragApp,
            it.half?.let { h -> Area(h.icon, h.label, h.selected && !inOverview, open = h.open) }, it.people, it.open) } +
            Area(Icons.Rounded.Apps, stringResource(R.string.idmate_apps), inOverview, open = openOverview)
        // What a list of apps may take of the height: the screen less the controls, the tile and the hint. Longer lists scroll.
        val listMax = (maxHeight - ControlsHeight - ControlsMargin * 2 - 200.dp).coerceAtLeast(120.dp)
        if (markFault != null) {
            val end = if (side == Alignment.BottomEnd) Alignment.BottomStart else Alignment.BottomEnd
            Row(
                Modifier.align(end).padding(ControlsMargin).height(ControlsHeight).clip(RoundedCornerShape(18.dp)).background(Panel)
                    .clickable(onClick = markFault).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Rounded.BugReport, null, tint = TextDim, modifier = Modifier.size(22.dp))
                Text(stringResource(R.string.fault_mark_action), color = TextDim, fontSize = Mate.body)
            }
        }
        // The area whose apps are out (swiped up on its tile).
        var large by remember { mutableStateOf<String?>(null) }
        Row(
            Modifier.padding(start = ControlsMargin, end = ControlsMargin, bottom = ControlsHeight + ControlsMargin * 2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            (if (side == Alignment.BottomEnd) tiles.reversed() else tiles).forEach { tile ->
                // The apps of an area come out with a swipe up on its tile - marked by a grabber, as on a phone's
                // home bar - and go back with a swipe down; no long press, nothing open all the time (O164). They are
                // the apps chosen for the area in the phone app (its pins), all of them when none is chosen; the
                // pinning itself is done there, not here.
                val list = (tile.choices.ifEmpty { tile.all }).map { Choice(it.app, it.selected, pick = it.pick, draggable = it.draggable) }
                val split = tile.half
                val hasList = list.isNotEmpty() || (split != null && tile.people.isNotEmpty())
                val open = large == tile.label && hasList
                val density = androidx.compose.ui.platform.LocalDensity.current
                val swipe = with(density) { 36.dp.toPx() }
                // one width, open or not: the other tiles stay where they are. Square and of a reachable size, lined up
                // from the driver's side - not stretched over the whole width of ever wider car screens (O196)
                Column(Modifier.width(if (split != null) SwitcherTile * 1.5f else SwitcherTile), verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = if (side == Alignment.BottomEnd) Alignment.End else Alignment.Start) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = open,
                        // The animated box itself as wide as the list, laid from the tile's driver-side edge (18.11.2026, again:
                        // "dann geht erst die Liste auf und dann klappt sie nach rechts aus ... Habe ich schon mal angemerkt"):
                        // the box was held to the tile's width, and a wider list in it is set in its middle - half of it
                        // stood left of the tile while it opened, and jumped to its place at the end. Not cut either (P-063).
                        modifier = Modifier.wrapContentWidth(if (side == Alignment.BottomEnd) Alignment.End else Alignment.Start, unbounded = true)
                            .requiredWidth(SwitcherList),
                        enter = androidx.compose.animation.expandVertically(expandFrom = Alignment.Bottom, clip = false) + androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Bottom, clip = false) + androidx.compose.animation.fadeOut(),
                    ) {
                        // the list may be wider than its tile, so the apps' names can be read; it reaches over the neighbours
                        Column(
                            Modifier.fillMaxWidth().heightIn(max = listMax).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            list.forEach { c -> ChoiceChip(c, if (c.draggable) drag else null) }
                            if (split != null) tile.people.forEach { (name, call) -> PersonChip(name) { large = null; close(); call() } }
                        }
                    }
                    Box {
                        if (split != null) Column(
                            Modifier.size(SwitcherTile * 1.5f, SwitcherTile).clip(RoundedCornerShape(18.dp)).background(if (open) PanelHigh else Panel)
                                .then(if (!hasList) Modifier else Modifier.pointerInput(tile.label) {
                                    var dy = 0f
                                    detectVerticalDragGestures(
                                        onDragStart = { dy = 0f },
                                        onDragEnd = {
                                            if (dy < -swipe) large = tile.label
                                            else if (dy > swipe && large == tile.label) large = null
                                        },
                                    ) { change, amount -> change.consume(); dy += amount }
                                }).padding(top = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // one grabber over both halves: the swipe up is the tile's as a whole (the favourites)
                            Box(Modifier.width(36.dp).height(5.dp).clip(RoundedCornerShape(3.dp))
                                .background(if (!hasList) Color.Transparent else if (open) Teal else TextDim))
                            Row(Modifier.fillMaxWidth().weight(1f)) {
                                listOf(tile, split).forEachIndexed { i, part ->
                                    if (i == 1) Box(Modifier.padding(vertical = 14.dp).width(1.dp).fillMaxHeight().background(Line))
                                    Column(Modifier.weight(1f).fillMaxHeight().background(if (part.selected) PanelHigh else Color.Transparent)
                                        .clickable(onClick = part.open).padding(top = 10.dp, bottom = 18.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Top) {
                                        Icon(part.icon, null, tint = if (part.selected) Teal else Color.White, modifier = Modifier.size(34.dp))
                                        Spacer(Modifier.height(10.dp))
                                        Text(part.label, color = Color.White, fontSize = Mate.body, fontWeight = if (part.selected) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
                                    }
                                }
                            }
                        } else Column(
                            Modifier.size(SwitcherTile).dragApp(tile.dragApp, drag).clip(RoundedCornerShape(18.dp)).background(if (tile.selected || open) PanelHigh else Panel)
                                .then(if (list.isEmpty()) Modifier else Modifier.pointerInput(tile.label) {
                                    var dy = 0f
                                    detectVerticalDragGestures(
                                        onDragStart = { dy = 0f },
                                        onDragEnd = {
                                            if (dy < -swipe) large = tile.label
                                            else if (dy > swipe && large == tile.label) large = null
                                        },
                                    ) { change, amount -> change.consume(); dy += amount }
                                })
                                .clickable(onClick = if (tile.tapShowsList) ({ large = if (large == tile.label) null else tile.label }) else tile.open)
                                .padding(top = 10.dp, bottom = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // the grabber: where the area has more apps to swipe up; a quiet space where it has none
                            Box(Modifier.width(36.dp).height(5.dp).clip(RoundedCornerShape(3.dp))
                                .background(if (list.isEmpty()) Color.Transparent else if (open) Teal else TextDim))
                            Spacer(Modifier.height(10.dp))
                            val logo = tile.logo
                            if (logo != null) Image(logo.asImageBitmap(), null, Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)))
                            else Icon(tile.icon, null, tint = if (tile.selected) Teal else Color.White, modifier = Modifier.size(34.dp))
                            Spacer(Modifier.height(10.dp))
                            Text(tile.label, color = Color.White, fontSize = Mate.body, fontWeight = if (tile.selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

/** A favourite of the phone half (System L-094): the name and a green call button over the whole row. */
@Composable
private fun PersonChip(name: String, call: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().height(52.dp).clip(shape).background(Panel).clickable(onClick = call).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = Color.White, fontSize = Mate.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Box(Modifier.size(40.dp).clip(CircleShape).background(CallGreen), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Call, stringResource(R.string.idmate_call), tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

/** One app of an area's choice: its icon and name, as high as a finger needs; in the large choice with the pin. */
@Composable
private fun ChoiceChip(c: Choice, drag: AppDrag? = null) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().height(52.dp).dragApp(c.app, drag).clip(shape).background(if (c.selected) PanelHigh else Panel)
            .then(if (c.selected) Modifier.border(1.5.dp, Teal, shape) else Modifier)
            .clickable(onClick = c.pick).padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = c.app.icon
        if (icon != null) Image(icon.asImageBitmap(), null, Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)))
        else Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(Line), contentAlignment = Alignment.Center) {
            Text(c.app.label.take(1), color = Color.White, fontSize = Mate.body, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(8.dp))
        // an app that refused this host stays in the list, dimmed: it may work on another system
        Text(c.app.label, color = if (c.app.packageName in net.prototux.aare.host.HostRejections.packages) TextDim else Color.White,
            fontSize = Mate.body, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (c.selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (c.pinned != null) Box(Modifier.size(52.dp).clickable(onClick = c.togglePin), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PushPin, null, tint = if (c.pinned) Teal else Line, modifier = Modifier.size(22.dp))
        } else Spacer(Modifier.width(10.dp))
    }
}

@Composable
private fun Time() {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000)
        }
    }
    // the one rule for clock times (ui/Clock): 24 hours unless the device is set to 12
    Text(net.prototux.aare.ui.Clock.short(now), color = Color.White, fontSize = Mate.body, fontWeight = FontWeight.Medium)
}

/** The colour of a tile: the app icon's own colour, darkened so that white text stays readable. */
private fun tileColor(icon: Bitmap?): Color {
    if (icon == null || icon.width == 0 || icon.height == 0) return Panel
    var r = 0L; var g = 0L; var b = 0L; var n = 0L
    val stepX = maxOf(1, icon.width / 16); val stepY = maxOf(1, icon.height / 16)
    var y = 0
    while (y < icon.height) {
        var x = 0
        while (x < icon.width) {
            val p = icon.getPixel(x, y)
            val a = p ushr 24
            val pr = (p shr 16) and 0xff; val pg = (p shr 8) and 0xff; val pb = p and 0xff
            val max = maxOf(pr, pg, pb); val min = minOf(pr, pg, pb)
            // coloured pixels only: transparent, white, grey and black say nothing about the icon's colour
            if (a > 200 && max - min > 40) { r += pr; g += pg; b += pb; n++ }
            x += stepX
        }
        y += stepY
    }
    if (n == 0L) return PanelHigh
    val f = 0.42f / 255f
    return Color(r / n * f + 0.04f, g / n * f + 0.05f, b / n * f + 0.07f)
}

/**
 * The app overview: coloured tiles with the app's name, in pages. Sorted by how often an app was
 * opened here, then by how recently; the rest follows in alphabetical order on the pages behind.
 */
@Composable
private fun Drawer(state: CarUiState, usage: AppUsage, open: (CarAppEntry) -> Unit) {
    val version = usage.version
    val apps = remember(state.apps.toList(), state.recentAppIds.toList(), version) {
        state.apps
            .filter { it.kind != CarAppKind.EXIT && it.packageName != GOOGLE_AUTO_PACKAGE }
            .sortedWith(
                compareByDescending<CarAppEntry> { usage.of(it.id) }
                    .thenBy { state.recentAppIds.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                    .thenBy { it.label.lowercase() },
            )
    }
    AppGrid(apps, open)
}

/** Apps as tiles, page by page, clear of the controls (the overview, the cinema's menu). */
@Composable
private fun AppGrid(apps: List<CarAppEntry>, open: (CarAppEntry) -> Unit) {
    // The overview is the one screen of this shell itself: it keeps clear of the controls.
    BoxWithConstraints(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = ControlsHeight + ControlsMargin * 2)) {
        val gap = 12.dp
        val columns = maxOf(3, ((maxWidth + gap) / (168.dp + gap)).toInt())
        val rows = maxOf(1, ((maxHeight - 18.dp + gap) / (132.dp + gap)).toInt())
        val perPage = columns * rows
        val pages = maxOf(1, (apps.size + perPage - 1) / perPage)
        val pager = rememberPagerState { pages }
        Column(Modifier.fillMaxSize()) {
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), pageSpacing = gap) { page ->
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    apps.drop(page * perPage).take(perPage).chunked(columns).forEach { row ->
                        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                            row.forEach { app -> Tile(app, Modifier.weight(1f).fillMaxHeight()) { open(app) } }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    repeat(rows - ((apps.size - page * perPage).coerceIn(0, perPage) + columns - 1) / columns) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (pages > 1) {
                Row(Modifier.fillMaxWidth().height(18.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                    repeat(pages) { i ->
                        Box(Modifier.padding(horizontal = 4.dp).size(8.dp).clip(CircleShape)
                            .background(if (i == pager.currentPage) Teal else Line))
                    }
                }
            }
        }
    }
}

/**
 * The cinema's app menu (19.11.2026: "ob man die Apps direkt anzeigen kann über ein App-Starter-Menü im Bereich Kino"):
 * the apps chosen for the cinema first, then every app of the tablet with a start icon; one opens on its own display at
 * the car's size.
 */
@Composable
private fun CinemaMenu(chosen: List<CarAppEntry>, pick: (CarAppEntry) -> Unit) {
    val context = LocalContext.current
    var all by remember { mutableStateOf<List<CarAppEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        all = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val pm = context.packageManager
            val main = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            val px = (64 * context.resources.displayMetrics.density).toInt().coerceAtLeast(48)
            pm.queryIntentActivities(main, 0).map { it.activityInfo.packageName }.distinct()
                .filter { it != context.packageName && it !in chosen.map { c -> c.packageName } }
                .mapNotNull { pkg -> runCatching {
                    val info = pm.getApplicationInfo(pkg, 0)
                    val icon = runCatching { pm.getApplicationIcon(info).let { d ->
                        android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
                            val c = android.graphics.Canvas(b); d.setBounds(0, 0, px, px); d.draw(c) } } }.getOrNull()
                    CarAppEntry("cinema:$pkg", pkg, pm.getApplicationLabel(info).toString(), icon, CarAppKind.STANDALONE)
                }.getOrNull() }
                .sortedBy { it.label.lowercase() }
        }
    }
    AppGrid(chosen + all, pick)
}

@Composable
private fun Tile(app: CarAppEntry, modifier: Modifier, onClick: () -> Unit) {
    val icon = app.icon
    val color = remember(app.id, icon) { tileColor(icon) }
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(color).clickable(onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) Image(icon.asImageBitmap(), null, Modifier.size(60.dp).clip(RoundedCornerShape(16.dp)))
        else Box(Modifier.size(60.dp).clip(RoundedCornerShape(16.dp)).background(PanelHigh), contentAlignment = Alignment.Center) {
            Text(app.label.take(1), color = Color.White, fontSize = Mate.display, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Text(app.label, color = Color.White, fontSize = Mate.body, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/**
 * Messages, reduced to what works at the wheel (System L-057, 19.11.2026: "die Chats aus allen Apps ... die Konversationen,
 * die Personen, keine Inhalte ... auch eine Antwortfunktion ... mit Textvoransicht, wie beim Debug Report"): every
 * conversation the phone shows now, of any app, and those of the drive ([net.prototux.aare.notify.MessagePeople]) as large
 * tiles - who, from which app, how long ago; no texts. A tap selects a person and offers what the conversation allows:
 * answer (said, with the words shown as they are worked out, as the fault note), read aloud, mark read. Without access to
 * the notifications the area says so - then no message can arrive.
 */
@Composable
private fun Partners(state: CarUiState) {
    // Looking at the messages is having seen them, those too that come in meanwhile.
    LaunchedEffect(state.history.size) { state.unseen = 0 }
    val context = LocalContext.current
    val listener = net.prototux.aare.notify.CarNotificationListener.instance
    val changes by net.prototux.aare.notify.CarNotificationListener.changes.collectAsState()
    val people = remember(changes, state.history.toList(), listener) {
        val pm = context.packageManager
        fun appName(pkg: String) = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        net.prototux.aare.notify.MessagePeople.of(listener?.conversations().orEmpty(),
            state.history.map { net.prototux.aare.notify.MessagePeople.Seen(it.key, it.title, it.icon, it.timeMs, it.reply, it.actions) }, ::appName)
    }
    var chosen by remember { mutableStateOf<String?>(null) }
    val person = people.firstOrNull { it.key == chosen }
    var answering by remember { mutableStateOf<net.prototux.aare.notify.MessagePeople.Person?>(null) }
    LaunchedEffect(chosen) { answering = null }
    fun say(text: String) = net.prototux.aare.notify.CarNotificationListener.instance?.speak(text)
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = ControlsHeight + ControlsMargin * 2)) {
        if (people.isEmpty()) {
            // an empty page says what will come here, with its sign (O211: one small grey line on black looked broken);
            // without notification access nothing can come - said so
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Rounded.Chat, null, tint = Teal, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(16.dp))
                Text(stringResource(if (listener == null) R.string.idmate_messages_no_access else R.string.idmate_messages_empty), color = Mate.text, fontSize = Mate.title)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(if (listener == null) R.string.idmate_messages_no_access_hint else R.string.idmate_messages_empty_hint), color = TextDim,
                    fontSize = Mate.body, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            return@Column
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val gap = 12.dp
            val columns = maxOf(2, ((maxWidth + gap) / (220.dp + gap)).toInt())
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Fixed(columns),
                horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
                items(people.size, key = { people[it].key }) { i ->
                    val n = people[i]
                    val on = n.key == chosen
                    Column(
                        Modifier.fillMaxWidth().height(148.dp).clip(RoundedCornerShape(18.dp))
                            .background(if (on) Mate.accentContainer else Panel)
                            .clickable { chosen = if (on) null else n.key }.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        val icon = n.icon
                        if (icon != null) Image(icon.asImageBitmap(), null, Modifier.size(56.dp).clip(CircleShape))
                        else Box(Modifier.size(56.dp).clip(CircleShape).background(PanelHigh), contentAlignment = Alignment.Center) {
                            Text(n.name.take(1).uppercase(), color = Color.White, fontSize = Mate.title, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(n.name, color = if (on) Teal else Color.White, fontSize = Mate.body, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        // from which app and when - never what was written
                        val ago = android.text.format.DateUtils.getRelativeTimeSpanString(n.timeMs, System.currentTimeMillis(),
                            android.text.format.DateUtils.MINUTE_IN_MILLIS, android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE).toString()
                        Text(listOf(n.app, ago).filter { it.isNotBlank() }.joinToString(" · "), color = TextDim, fontSize = Mate.caption,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        // what the chosen person's conversation allows, as buttons one cannot miss; answering first, at the driver's side
        if (person != null && answering == null) {
            val c = person.conversation
            val actions: List<Pair<String, () -> Unit>> = if (c != null) buildList {
                add(stringResource(R.string.msg_action_read_aloud) to { listener?.readAloud(c); Unit })
                c.markRead?.let { pi -> add(stringResource(R.string.msg_action_mark_read) to { runCatching { pi.send() }; Unit }) }
            } else person.actions
            if (person.canReply || actions.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (person.canReply) Row(
                    Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(18.dp)).background(Teal).clickable { answering = person },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Mic, null, tint = Ink, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.idmate_dictate_answer), color = Ink, fontSize = Mate.heading, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                actions.forEach { (label, act) ->
                    Box(Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(18.dp)).background(PanelHigh).clickable(onClick = act),
                        contentAlignment = Alignment.Center) {
                        Text(label, color = Color.White, fontSize = Mate.heading, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
    }
    // the answer: said to Mate, the words shown as they are worked out, sent with "Senden" (as the fault note)
    answering?.let { p ->
        DictateCard(Modifier.align(Alignment.BottomStart).padding(start = ControlsMargin, end = ControlsMargin, bottom = ControlsHeight + ControlsMargin * 2),
            stringResource(R.string.idmate_messages_reply_to, p.name), send = { text ->
                val ok = p.conversation?.let { c -> listener?.reply(c, text) } ?: p.replyTo?.invoke(text) ?: false
                android.util.Log.i("MateMessages", "answer to a conversation of ${p.app.ifBlank { "the drive" }}: " + if (ok) "sent" else "not sent")
                say(context.getString(if (ok) R.string.idmate_dictate_sent else R.string.idmate_dictate_not_sent))
            }, close = { answering = null })
    }
    }
}

/**
 * An app in the hand: the two halves of the screen to lay it into (the one under the finger lit), and the app's card
 * under the finger - lifted with a small grow when animations are on. An app that cannot go into a split (neither a
 * navigation nor a music app) says so.
 */
@Composable
private fun BoxWithConstraintsScope.DragLayer(app: CarAppEntry, at: Offset, fits: Boolean, splitOn: Boolean, anim: Boolean,
                                              layout: (Boolean?) -> DropLayout) {
    // the zones as the split will be (I-062): the side under the finger by the split's line, the widths of the result
    val right = at.x > layout(null).boundary * constraints.maxWidth
    val leftShare = layout(right).leftShare
    val lift = remember { androidx.compose.animation.core.Animatable(if (anim) 0.6f else 1f) }
    LaunchedEffect(app.id) { if (anim) lift.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 500f)) }
    Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(false, true).forEach { r ->
            val on = fits && r == right
            val glow by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0f,
                if (anim) androidx.compose.animation.core.tween(160) else androidx.compose.animation.core.snap(), label = "zone")
            Box(Modifier.weight(if (r) 1f - leftShare else leftShare).fillMaxHeight().clip(RoundedCornerShape(24.dp))
                .background(Teal.copy(alpha = 0.08f + 0.22f * glow))
                .border((2 + 2 * glow).dp, Teal.copy(alpha = 0.35f + 0.65f * glow), RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center) {
                Text(stringResource(when { !fits -> R.string.idmate_drop_no; splitOn -> R.string.idmate_drop_replace; else -> R.string.idmate_drop_split }),
                    color = Color.White.copy(alpha = 0.55f + 0.45f * glow), fontSize = Mate.heading, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val w = 220.dp; val h = 64.dp
    Row(Modifier.offset { androidx.compose.ui.unit.IntOffset((at.x - with(density) { w.toPx() } / 2).toInt(), (at.y - with(density) { h.toPx() }).toInt()) }
        .graphicsLayer { scaleX = lift.value; scaleY = lift.value; shadowElevation = 24f }
        .width(w).height(h).clip(RoundedCornerShape(18.dp)).background(PanelHigh).border(2.dp, Teal, RoundedCornerShape(18.dp)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val icon = app.icon
        if (icon != null) Image(icon.asImageBitmap(), null, Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)))
        else Icon(Icons.Rounded.Apps, null, tint = Teal, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(10.dp))
        Text(app.label, color = Color.White, fontSize = Mate.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The least share of the width a navigation app that is not Mate's own gets in a split. */
private const val FOREIGN_MIN_SHARE = 2f / 3

/** Where an app let go lands (System I-062): the line between the sides now, and the split it makes. */
internal data class DropLayout(val boundary: Float, val naviRight: Boolean, val naviShare: Float) {
    val leftShare: Float get() = if (naviRight) 1f - naviShare else naviShare
}

/**
 * The split an app let go makes. [right] the side it is let go on (null: only the line between the sides). An open split
 * keeps its widths - the app takes the width of the side it goes to; a new split is 50:50. A navigation app of another
 * maker ([foreign]) gets two thirds at least, as the split shows it ([FOREIGN_MIN_SHARE]).
 */
internal fun dropLayout(splitOn: Boolean, naviShare: Float, naviRight: Boolean, right: Boolean?, isNavi: Boolean,
                        foreign: Boolean, currentForeign: Boolean): DropLayout {
    val shown = naviShare.coerceIn(if (currentForeign) FOREIGN_MIN_SHARE else 0.2f, 0.8f)
    val curLeft = if (!splitOn) 0.5f else if (naviRight) 1f - shown else shown
    if (right == null) return DropLayout(curLeft, naviRight, shown)
    val sideNavi = if (isNavi) right else !right
    val width = if (sideNavi) 1f - curLeft else curLeft
    return DropLayout(curLeft, sideNavi, width.coerceIn(if (foreign) FOREIGN_MIN_SHARE else 0.2f, 0.8f))
}

/**
 * What the fault just marked is, said to Mate (18.11.2026: "ein Textfeld, mit dem ich eingeben kann, was der Fehler ist.
 * Das muss ich dann wieder löschen können, wenn er es falsch verstanden hat, und neu eingeben können und dann irgendwann
 * auf Senden"): Mate listens at once (Whisper on the device) and shows what it heard; the microphone adds to it,
 * "Löschen" empties it and listens anew, "Senden" keeps it with the mark. The X leaves the mark without words.
 */
@Composable
private fun FaultNoteCard(modifier: Modifier, send: (String) -> Unit, close: () -> Unit) =
    DictateCard(modifier, stringResource(R.string.fault_note_title), send, close, keepOnLeave = true)

/**
 * Words said to Mate, shown as they are worked out (the fault note's card, 18.11.2026): Mate listens at once until the
 * driver ends it, the text stands above ("Textvoransicht"), the microphone adds to it, "Löschen" listens anew, "Senden"
 * hands it to [send]; the X leaves without words. Also the answer to a message (System L-057).
 */
@Composable
private fun DictateCard(modifier: Modifier, title: String, send: (String) -> Unit, close: () -> Unit,
                        /** The fault note: words already worked out are kept when the card goes without "Senden" (I-124). */
                        keepOnLeave: Boolean = false) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    // Mate listens until the driver ends it (18.11.2026: "ich möchte die Aufnahme manuell beenden und nicht, wenn er
    // denkt, dass ich genug gesprochen habe"); a pause only ends a piece, which is worked out beside the listening
    var listening by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf(0) }       // pieces said and not yet worked out
    var failed by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val running = remember { arrayOfNulls<net.prototux.aare.speech.LongDictation>(1) }
    // all words worked out, also beside a card already gone (I-124)
    val words = remember { java.util.concurrent.atomic.AtomicReference("") }
    val left = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val dismissed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var sent by remember { mutableStateOf(false) }
    fun finish() { sent = true; send(text); close() }
    fun listen() {
        running[0]?.cancel()
        failed = false; waiting = 0; listening = true
        val d = net.prototux.aare.speech.LongDictation(ctx.applicationContext)
        running[0] = d
        d.run(
            onPiece = { piece ->
                words.updateAndGet { (it + " " + piece.trim()).trim() }
                main.post { if (running[0] === d) text = (text + " " + piece.trim()).trim() } },
            onWaiting = { n -> main.post { if (running[0] === d) waiting = n } },
            onEnd = { ok -> main.post {
                // the card went meanwhile (a fault note): what was said is saved all the same (I-124)
                if (left.get() && !sent) { sent = true; words.get().takeIf { it.isNotBlank() }?.let { net.prototux.aare.debug.FaultNote.save(it) }; return@post }
                if (running[0] !== d) return@post
                running[0] = null; listening = false; waiting = 0; failed = !ok
                if (sending) { if (text.isNotBlank()) finish() else sending = false }
            } })
    }
    // System I-124 (24.11.2026, 19:30: "ich habe die Fehlerbeschreibung aufgenommen und gesendet" - it never arrived): the
    // last words were still being worked out after "Senden" when the connection to the car broke at parking, and the card
    // went with them. A fault note keeps what was worked out by then.
    // went with them. A fault note keeps all of it: the pieces still in work are finished beside the gone card and saved.
    androidx.compose.runtime.DisposableEffect(Unit) { listen(); onDispose {
        val d = running[0]; running[0] = null
        // the X without "Senden" leaves without words; after "Senden", or when the card just goes (the car gone), they are kept
        if (!keepOnLeave || sent || dismissed.get()) { d?.cancel(); return@onDispose }
        left.set(true)
        if (d != null) d.stop()                   // its onEnd saves what came by then
        else if (words.get().isNotBlank()) { sent = true; net.prototux.aare.debug.FaultNote.save(words.get()) }
    } }
    // "Senden" while the last words are still worked out waits for them - not for ever
    LaunchedEffect(sending) { if (sending) { kotlinx.coroutines.delay(45_000); if (text.isNotBlank()) finish() else sending = false } }
    Column(modifier.widthIn(max = 760.dp).clip(RoundedCornerShape(18.dp)).background(Mate.cardSolid)
        .pointerInput(Unit) { detectTapGestures { } }.padding(16.dp)) {
        Text(title, color = TextDim, fontSize = Mate.body)
        if (text.isNotBlank()) Text(stringResource(R.string.quoted, text), color = Color.White, fontSize = Mate.title, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        val now = when {
            sending -> stringResource(R.string.fault_note_sending)
            listening -> stringResource(if (waiting > 0) R.string.fault_note_listening_working else R.string.fault_note_listening)
            waiting > 0 -> stringResource(R.string.voice_thinking)
            failed -> stringResource(R.string.fault_note_failed)
            text.isBlank() -> stringResource(R.string.fault_note_empty)
            else -> stringResource(R.string.fault_note_more)
        }
        Text(now, color = if (listening || waiting > 0 || sending) Teal else TextDim, fontSize = if (text.isBlank()) Mate.title else Mate.body, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(Mate.plate).clickable { if (!sending) dismissed.set(true); close() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, stringResource(R.string.idmate_dictate_cancel), tint = Color.White, modifier = Modifier.size(34.dp))
            }
            // the microphone: on while Mate listens - tapped, the listening ends (what was said is still worked out);
            // tapped again, it goes on and adds to the words
            Box(Modifier.size(72.dp).clip(CircleShape).background(if (listening) Teal else Mate.plate)
                .clickable(enabled = !sending) { if (listening) { listening = false; running[0]?.stop() } else listen() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Mic, stringResource(R.string.voice_listening), tint = if (listening) Ink else Teal, modifier = Modifier.size(34.dp))
            }
            val clearable = (text.isNotBlank() || waiting > 0) && !sending
            Box(Modifier.height(72.dp).clip(RoundedCornerShape(16.dp)).background(Mate.plate)
                .clickable(enabled = clearable) { text = ""; words.set(""); listen() }.padding(horizontal = 26.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.fault_note_clear), color = if (clearable) Color.White else TextDim, fontSize = Mate.heading, fontWeight = FontWeight.SemiBold)
            }
            // "Senden" ends the listening too and takes along what is still being worked out
            val sendable = (text.isNotBlank() || waiting > 0 || listening) && !sending
            Box(Modifier.height(72.dp).clip(RoundedCornerShape(16.dp)).background(if (sendable) Teal else Mate.plate)
                .clickable(enabled = sendable) {
                    if (running[0] == null) { if (text.isNotBlank()) finish() }
                    else { sending = true; listening = false; running[0]?.stop() }
                }.padding(horizontal = 30.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.fault_note_send), color = if (sendable) Ink else TextDim, fontSize = Mate.heading, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** The navigation's shares of the split screen to choose from. */
private val SHARES = listOf(0.25f, 1f / 3, 0.5f, 2f / 3, 0.75f)
private val SHARE_NAMES = listOf("¼", "⅓", "½", "⅔", "¾")

/**
 * The split as it would be while its bar is held (18.11.2026): both areas in teal with their app and share, as the
 * areas an app is laid into. Only drawn - the apps keep their size until the bar is let go.
 */
@Composable
private fun SplitPreview(share: Float, naviRight: Boolean, navi: String, music: String, anim: Boolean) {
    val s by androidx.compose.animation.core.animateFloatAsState(share,
        if (anim) androidx.compose.animation.core.tween(140) else androidx.compose.animation.core.snap(), label = "preview")
    val i = SHARES.indices.minByOrNull { kotlin.math.abs(SHARES[it] - share) } ?: 2
    val naviZone = Triple(s, navi, SHARE_NAMES[i])
    val musicZone = Triple(1f - s, music, SHARE_NAMES[SHARES.size - 1 - i])
    Row(Modifier.fillMaxSize().background(Color(0xB3000000)).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        (if (naviRight) listOf(musicZone, naviZone) else listOf(naviZone, musicZone)).forEach { (w, app, part) ->
            Column(Modifier.weight(w.coerceIn(0.05f, 0.95f)).fillMaxHeight().clip(RoundedCornerShape(24.dp))
                .background(Teal.copy(alpha = 0.26f)).border(3.dp, Teal, RoundedCornerShape(24.dp)),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(part, color = Color.White, fontSize = Mate.title, fontWeight = FontWeight.Bold)
                Text(app, color = Color.White.copy(alpha = 0.85f), fontSize = Mate.heading, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}

/**
 * The bar between the two halves of the split screen. Held or drawn along, it is taken: [grab], then [move] with every
 * step of the finger, [letGo] at the end (true: the split takes the share shown). A quick flick along it to either side
 * swaps the halves, a double tap does too.
 */
private val SplitGap = 8.dp
private val SplitTouch = 28.dp

@Composable
private fun SplitBar(modifier: Modifier, swap: () -> Unit, grab: () -> Unit, move: (Float) -> Unit, letGo: (Boolean) -> Unit) {
    // touched (pressed, swiped or held), the grip grows - only drawn, the panes stay where they are
    var touched by remember { mutableStateOf(false) }
    val gripW by androidx.compose.animation.core.animateDpAsState(if (touched) 12.dp else 4.dp, label = "grip")
    val gripH by androidx.compose.animation.core.animateDpAsState(if (touched) 112.dp else 72.dp, label = "grip")
    // the gestures below start once and live as long as the bar: they call what the split is now, not what it was when
    // the bar came up (with the first one kept, the sides could be swapped only once - 18.11.2026: "kann ich die linke
    // und rechte App nicht tauschen")
    val swapNow by androidx.compose.runtime.rememberUpdatedState(swap)
    val grabNow by androidx.compose.runtime.rememberUpdatedState(grab)
    val moveNow by androidx.compose.runtime.rememberUpdatedState(move)
    val letGoNow by androidx.compose.runtime.rememberUpdatedState(letGo)
    Box(
        modifier.fillMaxHeight().width(SplitTouch).testTag("split-bar")
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        touched = e.changes.any { it.pressed }
                    }
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { swapNow() }) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // times as the touches carry them
                    val since = down.uptimeMillis
                    var last = since
                    var moved = 0f
                    var taken = false
                    while (true) {
                        // resting on the bar for a moment takes it as well as drawing it does
                        val wait = viewConfiguration.longPressTimeoutMillis - (last - since)
                        val e = if (taken) awaitPointerEvent() else if (wait > 0) withTimeoutOrNull(wait) { awaitPointerEvent() } else null
                        if (e == null) { taken = true; grabNow(); continue }
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        last = c.uptimeMillis
                        if (!c.pressed) break
                        val dx = c.position.x - c.previousPosition.x
                        moved += dx
                        if (taken) moveNow(dx)
                        else if (kotlin.math.abs(moved) > viewConfiguration.touchSlop) { taken = true; grabNow(); moveNow(moved) }
                        if (taken) c.consume()
                    }
                    // a quick flick swaps the sides as before; anything slower set the share
                    val flick = last - since < 300 && kotlin.math.abs(moved) > 40.dp.toPx()
                    if (taken) letGoNow(!flick)
                    if (flick) swapNow()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(gripW).height(gripH).clip(RoundedCornerShape(gripW / 2)).background(if (touched) Teal else Mate.handle))
    }
}


private val CallGreen get() = Mate.call
private val CallRed get() = Mate.callEnd

/**
 * Calls, kept out of the map's way. Ringing: a small card right above the controls with the name and
 * the two answers. On a call: nothing but the green sign in the controls; this card (name, time, hang up)
 * comes only when that sign is tapped ([open]).
 */
@Composable
private fun CallCorner(state: CarUiState, actions: CarActions, open: Boolean, modifier: Modifier, close: () -> Unit) {
    val call = state.call ?: return
    // a second call coming in: its answers, whatever was open (it was not shown at all before, 19.11.2026)
    if (call.waiting != null) { WaitingCard(call, actions, modifier); return }
    if (!call.ringing && !open) return
    Row(
        modifier.widthIn(max = 460.dp).height(76.dp).clip(RoundedCornerShape(18.dp)).background(Mate.cardSolid)
            .clickable(enabled = !call.ringing, onClick = close).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(if (call.ringing) PanelHigh else CallGreen), contentAlignment = Alignment.Center) {
            Text(call.name.take(1).uppercase(), color = Color.White, fontSize = Mate.heading, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f)) {
            if (call.ringing) Text(stringResource(R.string.phone_incoming_call), color = TextDim, fontSize = Mate.caption, maxLines = 1)
            else {
                val elapsed by androidx.compose.runtime.produceState(0L, call.sinceMs) {
                    while (true) { value = (System.currentTimeMillis() - call.sinceMs) / 1000; kotlinx.coroutines.delay(1_000) }
                }
                Text("%d:%02d".format(elapsed / 60, elapsed % 60), color = CallGreen, fontSize = Mate.caption, maxLines = 1)
            }
            Text(call.name, color = Color.White, fontSize = Mate.heading, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (net.prototux.aare.car.Calls.canEnd) CallButton(Icons.Rounded.CallEnd, stringResource(if (call.ringing) R.string.phone_action_decline else R.string.idmate_call_end), CallRed) { actions.endCall() }
        if (call.ringing) CallButton(Icons.Rounded.Call, stringResource(R.string.phone_action_answer), CallGreen) { actions.answerCall() }
    }
}

@Composable
private fun CallButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Box(Modifier.size(56.dp).clip(CircleShape).background(color).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(28.dp))
    }
}

/**
 * The dots that say the phone's microphone (orange) or a camera (green) is in use right now, by any app.
 * Nothing is drawn while neither is.
 */
@Composable
private fun InUseDots() {
    val context = LocalContext.current
    var mic by remember { mutableStateOf(false) }
    var cameras by remember { mutableStateOf(setOf<String>()) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        val camera = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val recording = object : android.media.AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>?) {
                mic = !configs.isNullOrEmpty()
            }
        }
        val availability = object : android.hardware.camera2.CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(id: String) { cameras = cameras - id }
            override fun onCameraUnavailable(id: String) { cameras = cameras + id }
        }
        mic = audio?.activeRecordingConfigurations?.isNotEmpty() == true
        audio?.registerAudioRecordingCallback(recording, handler)
        camera?.registerAvailabilityCallback(availability, handler)
        onDispose {
            audio?.unregisterAudioRecordingCallback(recording)
            camera?.unregisterAvailabilityCallback(availability)
        }
    }
    // Mate's own listening shows at once, without waiting for the system's recording report
    val micOn = mic || net.prototux.aare.speech.Dictation.listening
    if (!micOn && cameras.isEmpty()) return
    Row(Modifier.padding(end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        // an orange microphone while anything records (Mate's own listening too), a green camera while one is in use (O108)
        if (micOn) Icon(Icons.Rounded.Mic, stringResource(R.string.idmate_mic_in_use), tint = Mate.mic, modifier = Modifier.size(20.dp))
        if (cameras.isNotEmpty()) Icon(Icons.Rounded.Videocam, null, tint = Mate.camera, modifier = Modifier.size(20.dp))
    }
}

/**
 * O211 (17.11.2026): a button of the bar is hit over the bar's whole height (56 dp), not only on its 40 dp face -
 * Google asks for 76 dp in the car; the look stays, a tap above or below the face counts too.
 */
@Composable
private fun BarHit(onClick: () -> Unit, content: @Composable () -> Unit) {
    // at least 56 dp wide too (18.11.2026, car with 183 dpi: the 40 dp faces were 8 mm - now 11.6 mm each way)
    Box(Modifier.fillMaxHeight().widthIn(min = 56.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center) { content() }
}
