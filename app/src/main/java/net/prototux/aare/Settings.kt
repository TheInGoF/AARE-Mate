package net.prototux.aare

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.UUID

/** User settings, in one SharedPreferences file. */
class Settings(context: Context) {
    /** Not the Activity [Settings] may be made with, so an instance kept around does not leak it. */
    internal val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Serve the developer "head unit server" like Android Auto does, through adb (for DHU/openauto/headunit-revived). */
    var headUnitServer: Boolean
        get() = prefs.getBoolean("hu_server", false)
        set(v) = prefs.edit { putBoolean("hu_server", v) }

    /**
     * Which VW ID.7 it is, for the energy model Google Maps plans charging stops from (O107): "pro" (77 kWh),
     * "pros" (86 kWh) or "gtx" (86 kWh, all-wheel drive, heavier and thirstier).
     */
    /** The map server (O169/O170 wish of 16.11.2026): its Tailscale address with port, e.g. "http://nas.tailnet.ts.net:8090"; empty = the build PC through adb reverse. */
    var mapServer: String
        get() = prefs.getString("map_server", "") ?: ""
        set(v) = prefs.edit { putString("map_server", v.trim().trimEnd('/')) }
    /** The token the map server's gate ("Tor") asks for; empty = none. Kept encrypted (Android Keystore, [TokenVault]). */
    var mapToken: String
        get() = TokenVault(appContext, "map_token").read()
        set(v) = TokenVault(appContext, "map_token").write(v.trim())

    /** The name the map server knows this device by (from pairing, handover v0.2); shown on the map server page. */
    var mapDevice: String
        get() = prefs.getString("map_device", "") ?: ""
        set(v) = prefs.edit { putString("map_device", v) }

    /** Mate Navi's frame rate (O174): "30" an even 30 (the decision of 16.11.2026), "video" the car's video rate (tests). */
    var naviFps: String
        get() = prefs.getString("navi_fps", "30") ?: "30"
        set(v) = prefs.edit { putString("navi_fps", v) }

    /** Mate Navi's voice (F4, O192 "laut nur warnungen und stumm"): "loud", "brief" (once per turn, no names), "alerts" (only warnings) or "mute". */
    var naviVoice: String
        get() = prefs.getString("navi_voice_mode", "loud") ?: "loud"
        set(v) = prefs.edit { putString("navi_voice_mode", v) }

    /** The optional speed limit warning of Mate Navi (O194): off by default; how far over, how often, which tone, how loud. */
    var speedWarn: Boolean
        get() = prefs.getBoolean("speed_warn", false)
        set(v) = prefs.edit { putBoolean("speed_warn", v) }
    var speedWarnOver: Int
        get() = prefs.getInt("speed_warn_over", 10)
        set(v) = prefs.edit { putInt("speed_warn_over", v) }
    var speedWarnTimes: Int
        get() = prefs.getInt("speed_warn_times", 1)
        set(v) = prefs.edit { putInt("speed_warn_times", v) }
    /** Suggest the car's Wi-Fi to Android as a normal network, so the tablet takes the car's internet (CarInternet). */
    var carInternet: Boolean
        get() = prefs.getBoolean("car_internet", true)
        set(v) = prefs.edit { putBoolean("car_internet", v) }
    /** The ring in the car while a call comes in (CarRingtone): a tone's key, "tablet" or "off". */
    var carRingtone: String
        get() = prefs.getString("car_ringtone", "marimba") ?: "marimba"
        set(v) = prefs.edit { putString("car_ringtone", v) }
    var carRingtoneVolume: Int
        get() = prefs.getInt("car_ringtone_volume", 70)
        set(v) = prefs.edit { putInt("car_ringtone_volume", v) }
    var speedWarnTone: String
        get() = prefs.getString("speed_warn_tone", "gong") ?: "gong"
        set(v) = prefs.edit { putString("speed_warn_tone", v) }
    var speedWarnVolume: Int
        get() = prefs.getInt("speed_warn_volume", 70)
        set(v) = prefs.edit { putInt("speed_warn_volume", v) }


    /**
     * Charging on a route (O205, 16.11.2026: "mindest ankunft an ladesäule und ziel … und der maximale ladestand wenn man
     * nicht mehr als 80 laden möchte"): at least this much left on reaching a charger and the destination, charged up to
     * this much at a stop - more only where nothing else reaches, and the preview says so.
     */
    var chargeMinAtCharger: Int
        get() = prefs.getInt("charge_min_charger", 10)
        set(v) = prefs.edit { putInt("charge_min_charger", v) }
    var chargeMinAtDestination: Int
        get() = prefs.getInt("charge_min_destination", 10)
        set(v) = prefs.edit { putInt("charge_min_destination", v) }
    /** Least charge on reaching home - only where home has a wallbox (O205: "nicht jeder hat ne ladesäule zuhause"). */
    var chargeMinAtHome: Int
        get() = prefs.getInt("charge_min_home", 10)
        set(v) = prefs.edit { putInt("charge_min_home", v) }
    /** The charging networks preferred on a route (O205), ids of ChargeNetworks; empty: none preferred. */
    var chargeNetworks: Set<String>
        get() = prefs.getStringSet("charge_networks", emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit { putStringSet("charge_networks", v) }
    /**
     * Only the chosen networks (O213, 16.11.2026: "er muss sich auch an den lade anbieter filter halten wenn man sagt nur
     * tesla und ionity"); others only where the destination cannot be reached with them, and said. Off: preferred (O205).
     */
    var chargeNetworksOnly: Boolean
        get() = prefs.getBoolean("charge_networks_only", true)
        set(v) = prefs.edit { putBoolean("charge_networks_only", v) }
    /** Trip log (System L-076): how long the car stands in P before a trip is over, in minutes (Ingo: 1 or 5 for the baker). */
    var tripGraceMin: Int
        get() = prefs.getInt("trip_grace_min", 5)
        set(v) = prefs.edit { putInt("trip_grace_min", v) }
    /** F2: the traffic on Mate Navi's map (TomTom through the map server), on unless turned off. */
    var trafficShown: Boolean
        get() = prefs.getBoolean("traffic_shown", true)
        set(v) = prefs.edit { putBoolean("traffic_shown", v) }
    var chargeMaxLevel: Int
        get() = prefs.getInt("charge_max_level", 80)
        set(v) = prefs.edit { putInt("charge_max_level", v) }

    /** Route settings of Mate Navi (O181): avoid ferries, motorways, tolls, border crossings. */
    var avoidFerries: Boolean
        get() = prefs.getBoolean("avoid_ferries", false)
        set(v) = prefs.edit { putBoolean("avoid_ferries", v) }
    var avoidMotorways: Boolean
        get() = prefs.getBoolean("avoid_motorways", false)
        set(v) = prefs.edit { putBoolean("avoid_motorways", v) }
    var avoidTolls: Boolean
        get() = prefs.getBoolean("avoid_tolls", false)
        set(v) = prefs.edit { putBoolean("avoid_tolls", v) }
    var avoidBorders: Boolean
        get() = prefs.getBoolean("avoid_borders", false)
        set(v) = prefs.edit { putBoolean("avoid_borders", v) }

    /**
     * Whether Google Maps' energy model takes IDmate's consumption curve (by speed, with the last hours' factor) or only
     * the plain consumption from the trips, as in the test environment where Maps did plan charging stops (System I-003,
     * 17.11.2026: Hamburg from 99 % - no stop in Maps with the curve).
     */
    var evUseIdmateCurve: Boolean
        get() = prefs.getBoolean("ev_idmate_curve", true)
        set(v) = prefs.edit { putBoolean("ev_idmate_curve", v) }

    var evVariant: String
        get() = prefs.getString("ev_variant", "gtx") ?: "gtx"
        set(v) = prefs.edit { putString("ev_variant", v) }

    /**
     * Where Google Maps gets the car's energy model from (O117/O118): "google" - none is sent, Maps plans with the
     * vehicle saved in its own settings (Google's data for that car); "mate" - Mate's computed model with IDmate's
     * state of charge (Google's server plans stops badly with models it does not know); "off" - no electric car.
     */
    var evModelSource: String
        get() = prefs.getString("ev_model_source", "mate") ?: "mate"
        set(v) = prefs.edit { putString("ev_model_source", v) }

    /** Write the protocol frame trace to the in-app connection log too, to capture it without adb. */
    var verboseProtocolLog: Boolean
        get() = prefs.getBoolean("verbose_protocol_log", false)
        set(v) = prefs.edit { putBoolean("verbose_protocol_log", v) }

    /** Also accept head units from the network (TCP 5277, no authentication). Switched off again after one session if [headUnitServerLanOnce]. */
    var headUnitServerLan: Boolean
        get() = prefs.getBoolean("hu_server_lan", false)
        set(v) = prefs.edit { putBoolean("hu_server_lan", v) }

    /** Switch [headUnitServerLan] off once a network session starts. Off keeps it on, for openauto/Headunit Revived. */
    var headUnitServerLanOnce: Boolean
        get() = prefs.getBoolean("hu_server_lan_once", true)
        set(v) = prefs.edit { putBoolean("hu_server_lan_once", v) }

    /** Offer "Phone screen" on head unit server sessions too: the server's peer is unauthenticated, so off by default. */
    var headUnitServerMirror: Boolean
        get() = prefs.getBoolean("head_unit_server_mirror", false)
        set(v) = prefs.edit { putBoolean("head_unit_server_mirror", v) }

    /**
     * Accept head-unit connections over loopback TCP ([TcpServer.PORT]) from an Android-Auto-in-the-
     * browser bridge on this phone (webaa, paath — for cars without Android Auto, like Tesla). Any app
     * on the phone can then reach the server, so it is off by default.
     */
    var headUnitServerLocalhost: Boolean
        get() = prefs.getBoolean("hu_server_localhost", false)
        set(v) = prefs.edit { putBoolean("hu_server_localhost", v) }

    /** Connect wirelessly to bonded head units that advertise wireless Android Auto. */
    var wireless: Boolean
        get() = prefs.getBoolean("wireless", true)
        set(v) = prefs.edit { putBoolean("wireless", v) }

    /** Check GitHub for a newer release and warn on the home screen. Off never touches the network. */
    var checkUpdates: Boolean
        get() = prefs.getBoolean("check_updates", true)
        set(v) = prefs.edit { putBoolean("check_updates", v) }

    // Update-check cache (see UpdateChecker): the latest release seen and its page, when we last
    // asked, and the version the user dismissed. Versions are stored without a leading "v".
    var updateLatestVersion: String?
        get() = prefs.getString("update_latest_version", null)
        set(v) = prefs.edit { putString("update_latest_version", v) }
    var updateLatestUrl: String?
        get() = prefs.getString("update_latest_url", null)
        set(v) = prefs.edit { putString("update_latest_url", v) }
    var updateLastCheckMs: Long
        get() = prefs.getLong("update_last_check_ms", 0)
        set(v) = prefs.edit { putLong("update_last_check_ms", v) }
    var updateDismissedVersion: String?
        get() = prefs.getString("update_dismissed_version", null)
        set(v) = prefs.edit { putString("update_dismissed_version", v) }

    /** Largest video height we accept from the head unit's offers (480, 720, 1080, 1440, 2160). */
    var maxVideoHeight: Int
        get() = prefs.getInt("max_video_height", 1080)
        set(v) = prefs.edit { putInt("max_video_height", v) }

    /** Prefer 60 fps configurations when the head unit offers them. */
    var prefer60fps: Boolean
        get() = prefs.getBoolean("prefer_60fps", true)
        set(v) = prefs.edit { putBoolean("prefer_60fps", v) }

    /**
     * Force H.264 video even when the head unit and phone could both do a newer codec (H.265...).
     * The newer codec is used automatically otherwise; this is the escape hatch for head units that
     * advertise one but decode it poorly.
     */
    var forceH264: Boolean
        get() = prefs.getBoolean("force_h264", false)
        set(v) = prefs.edit { putBoolean("force_h264", v) }

    /** Where the car screen's bar goes: next to the driver ([RAIL_AUTO]), [RAIL_LEFT], [RAIL_RIGHT] or [RAIL_BOTTOM]. */
    var railPosition: String
        get() = prefs.getString("rail_position", null)
            ?: if (prefs.contains("rail_right")) (if (prefs.getBoolean("rail_right", false)) RAIL_RIGHT else RAIL_LEFT) else RAIL_AUTO
        set(v) = prefs.edit { putString("rail_position", v); remove("rail_right") }

    /** The car screen's layout: [STYLE_AUTO] (Android Auto's) or [STYLE_CARPLAY] (an easter egg). */
    var carStyle: String
        get() = prefs.getString("car_style", STYLE_IDMATE) ?: STYLE_IDMATE   // this build: the IDmate layout unless another is chosen
        set(v) = prefs.edit { putString("car_style", v) }

    /**
     * Passenger mode: ignore the car's "driving" signal, so a passenger can type a destination or
     * browse long lists while moving. The driver must not use the screen then.
     */
    var ignoreDriving: Boolean
        get() = prefs.getBoolean("ignore_driving", false)
        set(v) = prefs.edit { putBoolean("ignore_driving", v) }

    /** The weather next to the clock (from Open-Meteo, with an approximate position). */
    var showWeather: Boolean
        get() = prefs.getBoolean("show_weather", true)
        set(v) = prefs.edit { putBoolean("show_weather", v) }

    /** The next calendar event on the dashboard. */
    var showCalendar: Boolean
        get() = prefs.getBoolean("show_calendar", true)
        set(v) = prefs.edit { putBoolean("show_calendar", v) }

    /** Behind the dashboard: none, one of the built-in gradients, or [WALLPAPER_CUSTOM] (filesDir/wallpaper.jpg). */
    var wallpaper: String
        get() = prefs.getString("wallpaper", "none") ?: "none"
        set(v) = prefs.edit { putString("wallpaper", v) }

    /** Keep the car screen's bar dark even with day colors. */
    /** Combustion car (O99): no dashboard (its data is for electric cars) - Mate shows its basic areas only. */
    var combustion: Boolean
        get() = prefs.getBoolean("combustion", false)
        set(v) = prefs.edit { putBoolean("combustion", v) }

    var darkBar: Boolean
        get() = prefs.getBoolean("dark_bar", false)
        set(v) = prefs.edit { putBoolean("dark_bar", v) }

    /** Car screen colors: follow the car's day/night signal ([THEME_AUTO]), or always [THEME_DARK] / [THEME_LIGHT]. */
    var carTheme: String
        get() = prefs.getString("car_theme", THEME_AUTO) ?: THEME_AUTO
        set(v) = prefs.edit { putString("car_theme", v) }

    /** Stream phone audio to the car (needs the screen-capture consent for audio). */
    var carAudio: Boolean
        get() = prefs.getBoolean("car_audio", true)
        set(v) = prefs.edit { putBoolean("car_audio", v) }

    /**
     * The phone's own Bluetooth MAC, for the car's in-session pairing handshake. Android hides it
     * from apps on most devices, and some head units withhold the projected screen until they know
     * which phone is connected, so the user can set it (Settings > About phone > Status). Empty: try
     * to read it, else skip.
     */
    var phoneBluetoothAddress: String
        get() = prefs.getString("phone_bt_address", "") ?: ""
        set(v) = prefs.edit { putString("phone_bt_address", v) }

    /** Capture car audio through su (a rooted phone), once the user has allowed it in the audio settings. */
    var rootAudio: Boolean
        get() = prefs.getBoolean("root_audio", false)
        set(v) = prefs.edit { putBoolean("root_audio", v) }

    /**
     * Package of the assistant the steering-wheel voice button opens (a car app such as Lapin, or a
     * phone assistant); null = follow the phone's default digital assistant.
     */
    var assistantPackage: String?
        get() = prefs.getString("assistant_pkg", null)
        set(v) = prefs.edit { if (v == null) remove("assistant_pkg") else putString("assistant_pkg", v) }

    /** Offer "Phone screen" in the car: the phone's own screen, for apps AARE can't host (Google Maps, Waze). */
    var phoneScreen: Boolean
        get() = prefs.getBoolean("phone_screen", false)
        set(v) = prefs.edit { putBoolean("phone_screen", v) }

    /**
     * Launch ordinary phone apps on the car screen (NewPipe, emulators, a browser…): a parked-only,
     * opt-in convenience that needs root or Shizuku. These apps are not Android Auto apps; AARE
     * mirrors their normal phone UI and injects the car's touches, so it is paused while driving.
     */
    var standaloneApps: Boolean
        get() = prefs.getBoolean("standalone_apps", false)
        set(v) = prefs.edit { putBoolean("standalone_apps", v) }

    /** The packages the user allows onto the car screen (see [standaloneApps]). */
    var standaloneAppPackages: Set<String>
        get() = prefs.getStringSet("standalone_app_pkgs", emptySet()) ?: emptySet()
        set(v) = prefs.edit { putStringSet("standalone_app_pkgs", HashSet(v)) }
    /** Show a web browser (a WebView) on the car screen. Off by default; paused while driving. */
    var browser: Boolean
        get() = prefs.getBoolean("browser", false)
        set(v) = prefs.edit { putBoolean("browser", v) }

    /** The page the car browser opens. */
    var browserUrl: String
        get() = prefs.getString("browser_url", BROWSER_DEFAULT_URL) ?: BROWSER_DEFAULT_URL
        set(v) = prefs.edit { putString("browser_url", v) }
    /** Also show the car data (speed, sensors…) as an app on the car screen, for diagnostics. */
    var carDataApp: Boolean
        get() = prefs.getBoolean("car_data_app", false)
        set(v) = prefs.edit { putBoolean("car_data_app", v) }
    /** Where the user dragged the in-call bar on the car screen (px offset from the top centre). */
    var callBarOffsetX: Float
        get() = prefs.getFloat("call_bar_x", 0f)
        set(v) = prefs.edit { putFloat("call_bar_x", v) }
    var callBarOffsetY: Float
        get() = prefs.getFloat("call_bar_y", 0f)
        set(v) = prefs.edit { putFloat("call_bar_y", v) }

    /** While connected, the car's GPS becomes the phone's location (needs AARE as mock location app). */
    var carGpsForPhone: Boolean
        get() = prefs.getBoolean("car_gps", true)
        set(v) = prefs.edit { putBoolean("car_gps", v) }

    /** Start the last media app playing when the car connects. */
    var resumeMedia: Boolean
        get() = prefs.getBoolean("resume_media", false)
        set(v) = prefs.edit { putBoolean("resume_media", v) }

    /**
     * Whether the music played when the car was last connected: [resumeMedia] starts it only then - music the
     * driver had paused stays paused on the next drive.
     */
    var mediaWasPlaying: Boolean
        get() = prefs.getBoolean("media_was_playing", true)
        set(v) = prefs.edit { putBoolean("media_was_playing", v) }

    /**
     * "Fehler markieren" in the switcher, at its bottom right (O159): on during the test drives; off hides it.
     * Not on the area button - its long press is the driver's for the display.
     */
    var faultMarkButton: Boolean
        get() = prefs.getBoolean("fault_mark_button", true)
        set(v) = prefs.edit { putBoolean("fault_mark_button", v) }

    /** Show incoming messages as banners on the car screen. */
    var messageBanners: Boolean
        get() = prefs.getBoolean("message_banners", true)
        set(v) = prefs.edit { putBoolean("message_banners", v) }

    /** The dashboard names what plays beside the controls - for music (default), audio books, podcasts. */
    var dashTitleMusic: Boolean
        get() = prefs.getBoolean("dash_title_music", true)
        set(v) = prefs.edit { putBoolean("dash_title_music", v) }
    var dashTitleBooks: Boolean
        get() = prefs.getBoolean("dash_title_books", false)
        set(v) = prefs.edit { putBoolean("dash_title_books", v) }
    var dashTitlePodcasts: Boolean
        get() = prefs.getBoolean("dash_title_podcasts", false)
        set(v) = prefs.edit { putBoolean("dash_title_podcasts", v) }

    /**
     * Animations on the car screen (18.11.2026: an app taken in the hand and laid into a split, the split opening, its
     * share changing, the switcher folding in and out) - switchable, they may be heavy for the tablet.
     */
    var carAnimations: Boolean
        get() = prefs.getBoolean("car_animations", true)
        set(v) = prefs.edit { putBoolean("car_animations", v) }

    /** Built-in music: even out loud and quiet tracks with the loudness the Plex server measured. */
    var musicLevel: Boolean
        get() = prefs.getBoolean("music_level", true)
        set(v) = prefs.edit { putBoolean("music_level", v) }

    /**
     * The music's target level for every other source too (18.11.2026): audio books, podcasts, other music apps and
     * navigation prompts are measured on their way to the car and turned down to it (audio/Loudness.kt).
     */
    var levelAll: Boolean
        get() = prefs.getBoolean("level_all", true)
        set(v) = prefs.edit { putBoolean("level_all", v) }

    /** Built-in music: how many dB above the server's reference level (-18 LUFS) the evened-out music plays (0 to 10). */
    var musicLevelBoostDb: Int
        get() = prefs.getInt("music_level_boost_db", 0).coerceIn(0, 10)
        set(v) = prefs.edit { putInt("music_level_boost_db", v.coerceIn(0, 10)) }

    /** The Whisper model chosen among those on the device (Language.speech); empty = the language's standard. */
    var asrModel: String
        get() = prefs.getString("asr_model", "") ?: ""
        set(v) = prefs.edit { putString("asr_model", v) }

    /** The voice messages are read with: a voice name of the system's speech engine; null = the engine's own default. */
    /** The voice Mate reads with (O110): "system" (the phone's text-to-speech) or "piper" (Thorsten, on the device). */
    var ttsEngine: String
        get() = prefs.getString("tts_engine", "system") ?: "system"
        set(v) = prefs.edit { putString("tts_engine", v) }

    var ttsVoice: String?
        get() = prefs.getString("tts_voice", null)
        set(v) = prefs.edit { if (v == null) remove("tts_voice") else putString("tts_voice", v) }

    /** The voice for when there is no internet or the reception is poor: one computed on the device; null = the engine's default. */
    var ttsVoiceLocal: String?
        get() = prefs.getString("tts_voice_local", null)
        set(v) = prefs.edit { if (v == null) remove("tts_voice_local") else putString("tts_voice_local", v) }

    /** How loud the built-in music stays while a message is read aloud, in percent (0 to 20; 0 = silent). */
    var voiceDuckPercent: Int
        get() = prefs.getInt("voice_duck_percent", 10).coerceIn(0, 20)
        set(v) = prefs.edit { putInt("voice_duck_percent", v.coerceIn(0, 20)) }

    /** How long a message banner stays, in seconds (1 to 15). */
    var messageBannerSeconds: Int
        get() = prefs.getInt("message_banner_seconds", 5).coerceIn(1, 15)
        set(v) = prefs.edit { putInt("message_banner_seconds", v.coerceIn(1, 15)) }

    /** Whether a message banner shows the message's text, or only who wrote. */
    var messagePreview: Boolean
        get() = prefs.getBoolean("message_preview", true)
        set(v) = prefs.edit { putBoolean("message_preview", v) }

    /** Also show messages from group conversations. */
    var groupMessages: Boolean
        get() = prefs.getBoolean("group_messages", true)
        set(v) = prefs.edit { putBoolean("group_messages", v) }

    /** Do Not Disturb (priority only) while connected to the car. */
    var dndWhileDriving: Boolean
        get() = prefs.getBoolean("dnd_driving", false)
        set(v) = prefs.edit { putBoolean("dnd_driving", v) }

    /** Answer people who write while connected, once per conversation per drive. */
    var autoReply: Boolean
        get() = prefs.getBoolean("auto_reply", false)
        set(v) = prefs.edit { putBoolean("auto_reply", v) }

    /** The default is resolved on read and never stored, so it follows the phone's language. */
    var autoReplyText: String
        get() = prefs.getString("auto_reply_text", null)?.takeIf { it.isNotBlank() } ?: appContext.getString(R.string.msg_autoreply_default)
        set(v) = prefs.edit { putString("auto_reply_text", v) }

    /** Read incoming messages aloud as they arrive. */
    var readMessagesAloud: Boolean
        get() = prefs.getBoolean("read_messages", false)
        set(v) = prefs.edit { putBoolean("read_messages", v) }

    /** Cars AARE connected to: name to the last connection time (ms). */
    val knownCars: Map<String, Long>
        get() = prefs.getStringSet("known_cars", emptySet()).orEmpty().mapNotNull { e ->
            val i = e.lastIndexOf('\t')
            if (i <= 0) null else e.substring(0, i) to (e.substring(i + 1).toLongOrNull() ?: 0L)
        }.toMap()

    fun rememberCar(name: String) {
        if (name.isBlank()) return
        val cars = knownCars + (name to System.currentTimeMillis())
        prefs.edit { putStringSet("known_cars", cars.map { (n, t) -> "$n\t$t" }.toSet()) }
    }

    /**
     * The one size of the car screen for a car, in percent (O134): Mate's own screen, the cards and lists Mate
     * draws for other apps, their maps and apps that draw themselves all follow it. Kept for each car, since
     * screens differ; a car not set yet takes the value set last. A car set with the two earlier sizes
     * ("Mate" and "foreign apps") keeps how it looked: its size comes from Mate's, see [SHELL_BASE].
     */
    fun size(car: String): Int {
        val stored = prefs.getInt("size:$car", prefs.getInt("size", 0))
        if (stored > 0) return stored.coerceIn(APP_SCALES.first(), APP_SCALES.last())
        val mate = prefs.getInt("mate_scale:$car", prefs.getInt("mate_scale", (100 * SHELL_BASE).toInt()))
        return APP_SCALES.minBy { kotlin.math.abs(it - mate / SHELL_BASE) }
    }

    fun setSize(car: String, percent: Int) = prefs.edit {
        // the trim is fixed first, or it would be worked out anew from the earlier sizes against the new size
        val trim = appTrim(car)
        if (car.isNotBlank()) { putInt("size:$car", percent); putInt("app_trim:$car", trim) }
        putInt("size", percent); putInt("app_trim", trim)
    }

    /**
     * Foreign apps against Mate, in steps from -2 (smaller) to 2 (larger), per car: the fine tuning beside
     * the one size, for an app that comes out too large or too small in this car ([TRIMS]).
     */
    fun appTrim(car: String): Int {
        // A car set with the earlier "size of foreign apps" starts at "same": that size was lowered against cards
        // drawn too large, which are in the common measure now (O151).
        return prefs.getInt("app_trim:$car", prefs.getInt("app_trim", 0)).coerceIn(-2, 2)
    }

    fun setAppTrim(car: String, steps: Int) = prefs.edit {
        val size = size(car)
        if (car.isNotBlank()) { putInt("app_trim:$car", steps); putInt("size:$car", size) }
        putInt("app_trim", steps); putInt("size", size)
    }

    /** Mate's sound filter (O50) for this car: on or off, and the gain of each band in dB; per car like [size]. */
    fun eqOn(car: String): Boolean = prefs.getBoolean("eq_on:$car", prefs.getBoolean("eq_on", false))

    fun eqGains(car: String): FloatArray {
        val v = prefs.getString("eq_gains:$car", null) ?: prefs.getString("eq_gains", null).orEmpty()
        val parts = v.split(',').mapNotNull { it.trim().toFloatOrNull() }
        return FloatArray(net.prototux.aare.audio.CarEq.BANDS.size) { parts.getOrElse(it) { 0f } }
    }

    fun setEq(car: String, on: Boolean, gains: FloatArray) = prefs.edit {
        val v = gains.joinToString(",") { if (it % 1f == 0f) it.toInt().toString() else it.toString() }
        if (car.isNotBlank()) { putBoolean("eq_on:$car", on); putString("eq_gains:$car", v) }
        putBoolean("eq_on", on)
        putString("eq_gains", v)
    }

    fun forgetCar(name: String) {
        prefs.edit { putStringSet("known_cars", (knownCars - name).map { (n, t) -> "$n\t$t" }.toSet()) }
    }

    /** Last navigation and media apps used, restored on the next connection. */
    var lastNavApp: String?
        get() = prefs.getString("last_nav", null)
        set(v) = prefs.edit { putString("last_nav", v) }
    var lastMediaApp: String?
        get() = prefs.getString("last_media", null)
        set(v) = prefs.edit { putString("last_media", v) }

    /** The car launcher's order: app keys (package names, or the built-in apps' ids), first first. */
    var appOrder: List<String>
        get() = prefs.getString("app_order", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        set(v) = prefs.edit { putString("app_order", v.joinToString("\n")) }

    /** Runtime permissions setup has asked for at least once, to tell a dismissed dialog from a refusal for good. */
    var requestedPermissions: Set<String>
        get() = prefs.getStringSet("requested_permissions", emptySet()) ?: emptySet()
        set(v) = prefs.edit { putStringSet("requested_permissions", v) }

    /** Apps hidden from the car launcher. */
    var hiddenApps: Set<String>
        get() = prefs.getStringSet("hidden_apps", emptySet()) ?: emptySet()
        set(v) = prefs.edit { putStringSet("hidden_apps", v) }

    /** Stable random id sent to head units so they recognise this phone. */
    val instanceId: String
        get() = prefs.getString("instance_id", null) ?: UUID.randomUUID().toString().also { prefs.edit { putString("instance_id", it) } }

    /** Index of the certificate to offer first (rotates after a head unit rejects one). */
    var certificateIndex: Int
        get() = prefs.getInt("cert_index", 0)
        set(v) = prefs.edit { putInt("cert_index", v) }

    companion object {
        /** The VW ID.7s to choose from: key and name. */
        val EV_VARIANTS = listOf("pro" to "Pro · 77 kWh", "pros" to "Pro S · 86 kWh", "gtx" to "GTX · 86 kWh")
        /** The sizes offered for the car screen, in percent. */
        val APP_SCALES = listOf(70, 80, 90, 100, 115, 130)
        /**
         * Mate's bar and pop-ups are written in a small measure of their own and drawn this much larger than the
         * car's density (found in the first car, a VW ID.7, where they were right at 130 %). Everything else - the
         * screens in an area, the cards Mate draws for other apps, their maps, apps that draw themselves - takes the
         * car's density as it is, so that a text of one kind has one size everywhere (O151).
         */
        const val SHELL_BASE = 1.3f
        const val APP_BASE = 1f
        /**
         * What a navigation app paints on its own map under Mate's cards (ABRP: battery, speed, speed limit) is
         * written for a phone in the hand, about three quarters of the car screen's text: its map gets this much
         * more than the car's density, so that those figures come out as large as the text on the cards (O152).
         */
        const val MAP_BASE = 1.25f
        /** The factors of the five trim steps -2..2. */
        val TRIMS = floatArrayOf(0.8f, 0.9f, 1f, 1.1f, 1.25f)
        const val BROWSER_DEFAULT_URL = "https://duckduckgo.com/"
        const val RAIL_AUTO = "auto"
        const val RAIL_LEFT = "left"
        const val RAIL_RIGHT = "right"
        const val RAIL_BOTTOM = "bottom"
        const val THEME_AUTO = "auto"
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
        const val STYLE_AUTO = "auto"
        const val STYLE_CARPLAY = "carplay"
        const val STYLE_IDMATE = "idmate"
        const val WALLPAPER_CUSTOM = "custom"
        /** The user's wallpaper photo, in the app's files. */
        const val WALLPAPER_FILE = "wallpaper.jpg"
    }

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(l)
    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(l)
}
