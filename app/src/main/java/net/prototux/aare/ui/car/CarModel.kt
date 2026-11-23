package net.prototux.aare.ui.car

import android.graphics.Bitmap
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.prototux.aare.car.NextEvent
import net.prototux.aare.notify.PhoneGuidance

/**
 * What kind of app an entry in the launcher is, which decides where its UI goes. [EXIT] has no UI:
 * it hands the screen back to the car's own.
 */
enum class CarAppKind { NAVIGATION, MEDIA, TEMPLATED, ASSISTANT, MESSAGING, PHONE_SCREEN, BROWSER, CAR_DATA, MATE_NAVI, STANDALONE, PROJECTED, PHONE, SETTINGS, NOTIFICATIONS, DEBUG, EXIT }

/** One launchable app on the car screen. */
@Stable
class CarAppEntry(
    val id: String,
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
    val kind: CarAppKind,
)

@Stable
class NowPlaying(
    val appId: String,
    val title: String,
    val subtitle: String,
    val art: Bitmap?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long,
)

/** The car UI's navigation: the dashboard, the app grid, or one app full screen. */
sealed interface CarScreen {
    data object Dashboard : CarScreen
    data object Launcher : CarScreen
    data class App(val appId: String) : CarScreen
}

/** A transient card, e.g. an incoming message or a toast from a car app. */
@Stable
class CarNotice(
    val key: String,
    val title: String,
    val text: String,
    val icon: Bitmap?,
    val actions: List<Pair<String, () -> Unit>> = emptyList(),
    val timeMs: Long = System.currentTimeMillis(),
    /** A message from a contact: its text is hidden while driving, like in the messaging screens. */
    val message: Boolean = false,
    /** Sends an answer into the message's conversation; null when the app offers no reply. True when sent. */
    val reply: ((String) -> Boolean)? = null,
)

/**
 * All state the car UI reads. The session and hosts write it from the main thread.
 */
@Stable
class CarUiState {
    var night by mutableStateOf(true)
    var screen by mutableStateOf<CarScreen>(CarScreen.Dashboard)
    val apps = mutableStateListOf<CarAppEntry>()
    var nowPlaying by mutableStateOf<NowPlaying?>(null)
    /** The navigation app whose map is shown on the dashboard. */
    var activeNavAppId by mutableStateOf<String?>(null)
    /** The media app the dashboard's media card controls. */
    var activeMediaAppId by mutableStateOf<String?>(null)
    val notices = mutableStateListOf<CarNotice>()
    var drivingRestricted by mutableStateOf(false)
    /** The car stands parked for sure (ParkedGate): phone apps such as videos may show; the passenger switch does not lift it. */
    var parkedForFilms by mutableStateOf(false)
    /** Why not, in the car's words for the screen (null while open). */
    var parkedWhy by mutableStateOf<String?>(null)
    /** Why the cinema has no phone apps (shown in its area while parked), null when it has some. */
    var cinemaMissing by mutableStateOf<String?>(null)
    var headUnitName by mutableStateOf("")
    var listening by mutableStateOf(false)
    /** Recent notifications (messages), newest first, for the notification centre. */
    val history = mutableStateListOf<CarNotice>()
    /** Notifications not seen in the notification centre yet. */
    var unseen by mutableStateOf(0)
    /** The hosted navigation app's trip (next step, distances), for the cluster's turn card. */
    var trip by mutableStateOf<androidx.car.app.navigation.model.Trip?>(null)
    /** The package that sent [trip]: its maneuver icons are opened on that app's behalf. */
    var tripPackage by mutableStateOf<String?>(null)
    /** The next calendar event, for the dashboard. */
    var nextEvent by mutableStateOf<NextEvent?>(null)
    /** The phone call ringing or on, if any. */
    var call by mutableStateOf<CarCall?>(null)
    /** The apps opened last, newest first (ids): the rail shows the recent ones. */
    val recentAppIds = mutableStateListOf<String>()
    /** The voice assistant showing as a bar over the screen (an app entry id), or null. */
    var assistantAppId by mutableStateOf<String?>(null)
    /** Turn-by-turn from a navigation app running on the phone (Google Maps, Waze…). */
    var phoneGuidance by mutableStateOf<PhoneGuidance?>(null)
    /** The driver sits on the right (right-hand drive): the voice button goes on that side. */
    var driverOnRight by mutableStateOf(false)

    fun app(id: String?): CarAppEntry? = apps.firstOrNull { it.id == id }
}

/** A phone call, ringing or on, for the car's call card. */
@Stable
data class CarCall(val ringing: Boolean, val name: String, val sinceMs: Long,
                   /** a second call ringing while this one is on: its name ("" when the phone app gives none) */
                   val waiting: String? = null,
                   /** whether "end this one and answer" can be offered (the phone app has that answer) */
                   val canEndAndAnswer: Boolean = false)

/** Actions the car UI asks for; implemented by the session. */
interface CarActions {
    /** Keep what is needed to find a fault later (long press on the area button, test drive T1). */
    fun markFault() {}
    /** What the fault just marked is, in the driver's words. */
    fun faultNote(text: String) {}
    fun openApp(app: CarAppEntry)
    fun goHome()
    fun openLauncher()
    fun assistant()
    fun mediaPlayPause()
    fun mediaNext()
    /** Wind what plays by [deltaMs] (audio books, podcasts). */
    fun mediaSeekBy(deltaMs: Long) {}
    fun mediaPrevious()
    fun dismissNotice(notice: CarNotice)
    fun answerCall()
    fun endCall()
    /** A second call coming in during a call (19.11.2026): this one held and that one answered. */
    fun holdAndAnswer() {}
    /** This call ended and the waiting one answered (where the phone app offers it). */
    fun endAndAnswer() {}
    /** The waiting call turned away, this one goes on. */
    fun declineWaiting() {}
    /** Navigate to a place (an address or a name) in the car's navigation app. */
    fun navigateTo(place: String)
    /** Call [number] (a favourite of the phone tile); says so on the screen when Mate may not place calls. */
    fun placeCall(number: String) {}
}
