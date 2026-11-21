package net.prototux.aare.host

import net.prototux.aare.Settings
import net.prototux.aare.notify.CarNotificationListener
import net.prototux.aare.notify.Conversation
import net.prototux.aare.ui.car.CarAppKind
import net.prototux.aare.ui.car.CarScreen
import net.prototux.aare.ui.car.PhoneStatusInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * What a voice assistant may do with the car: the tools its LLM can call while it runs on AARE
 * (see CarControlProvider). Specs use the function-calling JSON schema: name, description,
 * parameters, plus "confirm" and "silent" flags in the style of Lapin's device tools.
 */
object CarTools {

    fun specs(): JSONArray = JSONArray().apply {
        put(tool("car_status", "What the car screen shows now: driving or parked, day or night, the app on screen, navigation " +
            "(next turn, distance, arrival), what is playing, the phone's battery and signal, and what the car's sensors report " +
            "(\"vehicle\": speed, fuel or battery and range, odometer, outside temperature, open doors, lights, tyre pressures, warnings; " +
            "only what this car provides). Use it before answering questions about the trip or the car."))
        put(tool("car_navigate", "Start navigation to a place in the car's navigation app, shown on the car screen.",
            param("destination", "string", "address, place name or category as the user said it") to true))
        put(tool("car_media", "Control the music or podcast playing in the car.",
            param("action", "string", "play, pause, next or previous", listOf("play", "pause", "next", "previous")) to true, silent = true))
        put(tool("car_open", "Show something on the car screen: an app by its name (\"CoMaps\", \"Spotify\", \"WhatsApp\"…), " +
            "\"home\" (map and media), \"apps\" (all apps) or \"phone screen\" (the phone's own screen).",
            param("what", "string", "app name, home, apps or phone screen") to true, silent = true))
        put(tool("car_messages", "Read the latest messages received on the phone (WhatsApp, Signal, SMS…), newest first, to read them out to the driver.",
            param("app", "string", "only this messaging app, if the user named one") to false))
        put(tool("car_reply", "Reply to a conversation from car_messages (WhatsApp, Signal, SMS…), with the user's words. " +
            "Read the message back first: the user confirms before it is sent.",
            param("conversation", "string", "the conversation's name, as car_messages gives it (a contact or a group)") to true,
            param("message", "string", "the reply, as the user wants it sent") to true,
            param("app", "string", "the conversation's app, as car_messages gives it, when several conversations have that name") to false,
            confirm = true, summary = "reply “{message}” to {conversation}"))
        put(tool("car_mark_read", "Mark a conversation from car_messages as read.",
            param("conversation", "string", "the conversation's name, as car_messages gives it") to true,
            param("app", "string", "the conversation's app, as car_messages gives it, when several conversations have that name") to false,
            silent = true))
        put(tool("car_display", "Switch the car screen to dark or light colors, or back to the car's own day/night setting.",
            param("mode", "string", "dark, light or auto", listOf("dark", "light", "auto")) to true, silent = true))
    }

    /** Runs [name] on [runtime] (main thread). */
    fun run(runtime: CarRuntime, name: String, args: JSONObject): JSONObject = when (name) {
        // debug builds, through adb only (CarControlProvider): a tap on the car screen, for tests without a hand on it
        "debug_tap" -> runtime.debugTap?.let {
            it(args.optDouble("x").toFloat(), args.optDouble("y").toFloat(), args.optLong("hold_ms", 60))
            JSONObject().put("ok", true)
        } ?: JSONObject().put("ok", false).put("error", "not a debug build or no car screen")
        "debug_swipe" -> runtime.debugSwipe?.let {
            net.prototux.aare.car.debugSwipeHoldMs = args.optLong("hold", 0)
            it(args.optDouble("x").toFloat(), args.optDouble("y").toFloat(), args.optDouble("x2").toFloat(), args.optDouble("y2").toFloat(), args.optLong("ms", 400))
            JSONObject().put("ok", true)
        } ?: JSONObject().put("ok", false).put("error", "not a debug build or no car screen")
        "debug_pinch" -> runtime.debugPinch?.let {
            it(args.optDouble("x", 960.0).toFloat(), args.optDouble("y", 450.0).toFloat(),
                args.optDouble("from", 200.0).toFloat(), args.optDouble("to", 600.0).toFloat(), args.optLong("ms", 500))
            JSONObject().put("ok", true)
        } ?: JSONObject().put("ok", false).put("error", "not a debug build or no car screen")
        // debug builds: try the private projection (Google Maps) on a surface nobody sees; what the app answers is in the log
        "debug_projection" -> if (!net.prototux.aare.BuildConfig.DEBUG) JSONObject().put("ok", false) else {
            val app = net.prototux.aare.host.projected.ProjectedAppCatalog.query(runtime.context).firstOrNull()
            if (app == null) JSONObject().put("ok", false).put("error", "no projection app installed") else {
                val reader = android.media.ImageReader.newInstance(1920, 966, android.graphics.PixelFormat.RGBA_8888, 2)
                val host = net.prototux.aare.host.projected.ProjectedHost(runtime.context, app.service, object : net.prototux.aare.host.projected.ProjectedHost.Listener {})
                host.start(reader.surface, 1920, 966, 160)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    android.util.Log.i("ProjectedHost", "probe: picture drawn = " + (runCatching { reader.acquireLatestImage()?.also { it.close() } }.getOrNull() != null))
                    host.stop(); reader.close()
                }, 12_000)
                JSONObject().put("ok", true).put("app", app.label)
            }
        }
        "debug_ui" -> JSONObject().put("ok", true).put("on_dashboard", net.prototux.aare.ui.car.ShellDebug.onDashboard)
            .put("title_strip", net.prototux.aare.ui.car.ShellDebug.title ?: JSONObject.NULL)
        // debug builds: what a QR code's text gives for the map server page (O173), without the camera
        "debug_map_qr" -> if (!net.prototux.aare.BuildConfig.DEBUG) JSONObject().put("ok", false) else
            net.prototux.aare.ui.car.navi.NaviServer.fromQr(args.optString("text")).let { r ->
                val pair = net.prototux.aare.ui.car.navi.NaviServer.pairingFromQr(args.optString("text"))
                JSONObject().put("ok", r != null).put("server", r?.first ?: JSONObject.NULL).put("token", r?.second?.let { it.take(6) + "…" } ?: JSONObject.NULL)
                    .put("pair_base", pair?.first ?: JSONObject.NULL).put("pair_code", pair?.second ?: JSONObject.NULL) }
        // debug builds: the map server's region catalogue as the phone app reads it - ids, parents, paths, sizes only
        "debug_catalog" -> if (!net.prototux.aare.BuildConfig.DEBUG) JSONObject().put("ok", false) else kotlinx.coroutines.runBlocking {
            val c = net.prototux.aare.ui.car.navi.RegionPacks.catalogue(runtime.context)
            JSONObject().put("ok", c != null).put("regions", org.json.JSONArray(c?.regions.orEmpty().map { r ->
                "${r.id} < ${r.parent} [${r.path.joinToString("/")}] ${r.files.size} files ${r.bytes / 1_000_000} MB" }))
        }
        // debug builds: Mate Navi's simulated car keeps straight on at the next turn (F1, recalculation)
        // debug builds: a test destination (lat, lon) into Mate Navi's preview
        "debug_navi_goto" -> if (!net.prototux.aare.BuildConfig.TEST_HOOKS) JSONObject().put("ok", false) else
            net.prototux.aare.ui.car.navi.NaviDebug.goTo.let { f -> net.prototux.aare.ui.car.navi.NaviDebug.quiet = !args.optBoolean("voice")
                f?.invoke(args.optDouble("lat"), args.optDouble("lon")); JSONObject().put("ok", f != null) }
        // test builds: Mate Navi follows the car's/phone's position instead of the simulation (F1); the desk window sends
        // its test place as the car's GPS
        // test builds: test jams on the guided route, ahead of the car - {"from":300,"to":900,"magnitude":2}; several
        // calls add up, {"clear":true} takes them away (the route's colours checked without a real jam, 17.11.2026)
        "debug_navi_jam" -> if (!net.prototux.aare.BuildConfig.TEST_HOOKS) JSONObject().put("ok", false) else {
            val keep = net.prototux.aare.ui.car.navi.NaviKeep
            if (args.optBoolean("clear")) keep.trafficAhead.value = emptyList()
            else {
                val driven = keep.drivenM()
                val i = net.prototux.aare.ui.car.navi.NaviTraffic.Incident(6, org.maplibre.spatialk.geojson.Position(0.0, 0.0), 300, "mate-test Stau", args.optInt("magnitude", 2))
                keep.trafficAhead.value = (keep.trafficAhead.value + net.prototux.aare.ui.car.navi.NaviTraffic.Ahead(i,
                    driven + args.optDouble("from", 300.0), driven + args.optDouble("to", 900.0), along = true)).sortedBy { it.alongM }
            }
            JSONObject().put("ok", true).put("jams", keep.trafficAhead.value.size)
        }
        "debug_navi_live" -> if (!net.prototux.aare.BuildConfig.TEST_HOOKS) JSONObject().put("ok", false) else
            net.prototux.aare.ui.car.navi.NaviDebug.live.let { f -> f?.invoke(); JSONObject().put("ok", f != null) }
        // test builds: Mate Navi takes this speed instead of the car's ({"kmh":null} = the real one again)
        // test builds: asks the map server's traffic paths (F2) and says what came back - status, size, the start of the
        // body; never the token ({"path":"/verkehr/ereignisse?bbox=13.3,52.4,13.5,52.6"})
        "debug_traffic" -> if (!net.prototux.aare.BuildConfig.TEST_HOOKS) JSONObject().put("ok", false) else runCatching {
            val ctx = runtime.context
            val base = net.prototux.aare.Settings(ctx).mapServer.trimEnd('/')
            if (base.isBlank()) JSONObject().put("ok", false).put("error", "no map server set") else {
                val req = okhttp3.Request.Builder().url(base + args.optString("path", "/verkehr/ereignisse?bbox=13.3,52.4,13.5,52.6")).build()
                // off the main thread (the tools are answered there)
                java.util.concurrent.Executors.newSingleThreadExecutor().let { ex ->
                    ex.submit<JSONObject> {
                        net.prototux.aare.ui.car.navi.NaviServer.client(ctx).newCall(req).execute().use { r ->
                            val bytes = r.body.bytes()
                            val text = if ((r.header("Content-Type") ?: "").contains("json")) String(bytes).take(args.optInt("chars", 600)) else ""
                            // {"save":"name"}: the body into the app's files for a look at the PC (mate-test data, deleted after)
                            args.optString("save").takeIf { it.isNotBlank() }?.let { n -> java.io.File(ctx.getExternalFilesDir(null), "mate-test-$n").writeBytes(bytes) }
                            JSONObject().put("ok", r.isSuccessful).put("status", r.code).put("type", r.header("Content-Type")).put("bytes", bytes.size).put("start", text)
                        }
                    }.get(40, java.util.concurrent.TimeUnit.SECONDS).also { ex.shutdown() }
                }
            }
        }.getOrElse { JSONObject().put("ok", false).put("error", it.toString()) }
        // test builds: Mate Navi's search opened with this text, as if typed ({"q":"hambrug"})
        "debug_navi_search" -> if (!net.prototux.aare.BuildConfig.TEST_HOOKS) JSONObject().put("ok", false) else
            net.prototux.aare.ui.car.navi.NaviDebug.openSearch.let { f -> f?.invoke(args.optString("q")); JSONObject().put("ok", f != null) }
        // test builds: settings saved to / taken from mate-test-transfer.zip in the app's external files ({"export":true} /
        // {"import":true} / {"delete":true}); without the offline maps
        "debug_transfer" -> if (!net.prototux.aare.BuildConfig.TEST_HOOKS) JSONObject().put("ok", false) else {
            val f = java.io.File(runtime.context.getExternalFilesDir(null), "mate-test-transfer.zip")
            val u = android.net.Uri.fromFile(f)
            when {
                args.optBoolean("export") -> JSONObject().put("ok", true).put("entries", net.prototux.aare.MateTransfer.export(runtime.context, u)).put("bytes", f.length())
                args.optBoolean("import") -> JSONObject().put("ok", true).put("entries", net.prototux.aare.MateTransfer.import(runtime.context, u))
                else -> JSONObject().put("ok", f.delete())
            }
        }
        // test builds: a mate-test charger visit ({"lat":..,"lon":..}) or all mate-test visits removed ({"remove":true})
        // test builds: car values as if IDmate had sent them - battery and outside temperature, level, charging
        "debug_telemetry" -> if (!net.prototux.aare.BuildConfig.TEST_HOOKS) JSONObject().put("ok", false) else
            JSONObject().put("ok", net.prototux.aare.dash.DashData.debugTelemetry(runtime.context, args))
        "debug_charger_visit" -> if (!net.prototux.aare.BuildConfig.TEST_HOOKS) JSONObject().put("ok", false) else {
            val ctx = runtime.context; val V = net.prototux.aare.ui.car.navi.ChargerVisits
            if (args.optBoolean("remove")) V.all(ctx).filter { it.name.startsWith("mate-test") }.forEach { V.remove(ctx, it.at) }
            else V.noteVisit(ctx, net.prototux.aare.ui.car.navi.Charger(org.maplibre.spatialk.geojson.Position(longitude = args.optDouble("lon"), latitude = args.optDouble("lat")),
                args.optInt("kw", 300), 4, "mate-test ${args.optString("name", "Lader")}"))
            JSONObject().put("ok", true).put("visits", V.all(ctx).size)
        }
        // test builds: a stop on the way into the preview ({"lat":..,"lon":..,"name":"mate-test …"})
        "debug_navi_stop" -> if (!net.prototux.aare.BuildConfig.TEST_HOOKS) JSONObject().put("ok", false) else
            net.prototux.aare.ui.car.navi.NaviDebug.addStop.let { f -> f?.invoke(args.optDouble("lat"), args.optDouble("lon"), args.optString("name", "mate-test Halt")); JSONObject().put("ok", f != null) }
        // test builds: a delay in seconds counted as ahead on the guided route, for the faster route ({"s":600} / {"s":null})
        "debug_navi_delay" -> if (!net.prototux.aare.BuildConfig.TEST_HOOKS) JSONObject().put("ok", false) else {
            net.prototux.aare.ui.car.navi.NaviDebug.fakeDelayS = if (args.isNull("s")) null else args.optInt("s"); JSONObject().put("ok", true) }
        "debug_navi_speed" -> if (!net.prototux.aare.BuildConfig.TEST_HOOKS) JSONObject().put("ok", false) else {
            net.prototux.aare.ui.car.navi.NaviDebug.speedKmh = if (args.isNull("kmh")) null else args.optDouble("kmh") / 3.6
            JSONObject().put("ok", true)
        }
        // test builds: a mate-test place in the first list ({"add":"Name","pinned":true}), a mate-test list ({"category":"Name"}) or all mate-test places and lists away ({"remove":true})
        "debug_places" -> if (!net.prototux.aare.BuildConfig.TEST_HOOKS) JSONObject().put("ok", false) else {
            val ctx = runtime.context
            val P = net.prototux.aare.places.Places
            // {"home":"<id>","wallbox":true}: a place made the home, as the phone app's switch does (the driver's answer)
            if (args.has("home")) {
                P.setHome(ctx, args.getString("home"), true)
                if (args.has("wallbox")) P.setWallbox(ctx, args.getString("home"), args.optBoolean("wallbox"))
                return JSONObject().put("ok", true).put("home", P.load(ctx).places.firstOrNull { it.home }?.let { "${it.id} wallbox=${it.wallbox}" } ?: JSONObject.NULL)
            }
            if (args.optBoolean("remove")) {
                P.load(ctx).places.filter { it.id.startsWith("mate-test-") }.forEach { P.removePlace(ctx, it.id) }
                P.load(ctx).categories.filter { it.id.startsWith("mate-test-") }.forEach { P.removeCategory(ctx, it.id) }
                // the last destinations of test drives too
                net.prototux.aare.ui.car.navi.NaviPins.removeTests(ctx)
            }
            // a test list at the end, to try the order arrows (O203) without moving the driver's own lists
            args.optString("category").takeIf { it.isNotBlank() }?.let { name ->
                P.load(ctx).let { b -> P.save(ctx, b.copy(categories = b.categories + net.prototux.aare.places.Places.Category("mate-test-" + P.newId(), "mate-test $name", "star"))) }
            }
            args.optString("add").takeIf { it.isNotBlank() }?.let { name ->
                val cats = P.load(ctx).categories
                val cat = cats.firstOrNull { it.id == args.optString("list") }?.id ?: cats.first().id
                // {"list":"home","lat":..,"lon":..,"home":true,"wallbox":true}: a test home (O205), added beside the driver's own
                // places without touching them; "remove" takes it away again
                P.addPlace(ctx, net.prototux.aare.places.Places.Place("mate-test-" + P.newId(), cat, "mate-test $name", "",
                    args.optDouble("lat", 52.52), args.optDouble("lon", 13.405), pinned = args.optBoolean("pinned"),
                    home = args.optBoolean("home"), wallbox = args.optBoolean("wallbox")))
            }
            P.load(ctx).let { b -> JSONObject().put("ok", true).put("test_places", b.places.count { it.id.startsWith("mate-test-") })
                .put("lists", org.json.JSONArray(b.categories.map { if (it.id.startsWith("mate-test-")) it.name else "own" })) }
        }
        "debug_navi_offline" -> if (!net.prototux.aare.BuildConfig.TEST_HOOKS) JSONObject().put("ok", false) else {
            net.prototux.aare.ui.car.navi.NaviDebug.offline = args.optBoolean("on"); JSONObject().put("ok", true).put("offline", net.prototux.aare.ui.car.navi.NaviDebug.offline) }
        "debug_navi_miss_turn" -> if (!net.prototux.aare.BuildConfig.TEST_HOOKS) JSONObject().put("ok", false) else
            net.prototux.aare.ui.car.navi.NaviDebug.missTurn.let { f -> net.prototux.aare.ui.car.navi.NaviDebug.offlineAfterMiss = args.optBoolean("offline"); f?.invoke(); JSONObject().put("ok", f != null) }
        "debug_driving" -> { runtime.ui.drivingRestricted = args.optBoolean("on"); net.prototux.aare.audio.CarEq.carDriving = args.optBoolean("on"); JSONObject().put("ok", true).put("driving", runtime.ui.drivingRestricted) }
        // debug builds: as if the car switched its lights (counts only while the theme follows the car)
        "debug_dictate_file" -> if (!net.prototux.aare.BuildConfig.DEBUG) JSONObject().put("ok", false) else runCatching {
            val f = java.io.File(runtime.context.cacheDir, args.optString("file", "asr-test-2.wav"))
            net.prototux.aare.speech.Dictation.debugWave = net.prototux.aare.speech.SpeechBench.readWav(f.readBytes())
            JSONObject().put("ok", true).put("file", f.name)
        }.getOrElse { JSONObject().put("ok", false).put("error", it.message) }
        "debug_projected_key" -> if (!net.prototux.aare.BuildConfig.DEBUG) JSONObject().put("ok", false) else {
            val host = net.prototux.aare.host.projected.ProjectedHost.live
            val code = args.optInt("code", android.view.KeyEvent.KEYCODE_BACK)
            if (host == null) JSONObject().put("ok", false).put("error", "no projected app") else {
                val t = android.os.SystemClock.uptimeMillis()
                host.sendKey(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, code, 0))
                host.sendKey(android.view.KeyEvent(t, t + 50, android.view.KeyEvent.ACTION_UP, code, 0))
                JSONObject().put("ok", true).put("code", code)
            }
        }
        "debug_dictate_text" -> if (!net.prototux.aare.BuildConfig.DEBUG) JSONObject().put("ok", false) else runCatching {
            // the device's voice speaks the text; the next dictation hears it instead of the microphone (runs off the main thread)
            val text = args.optString("text")
            val ctx = runtime.context
            Thread { runCatching { net.prototux.aare.speech.Dictation.debugWave = net.prototux.aare.speech.SpeechBench.speak(ctx, listOf(text)).first() } }.start()
            JSONObject().put("ok", true).put("note", "ready in a few seconds")
        }.getOrElse { JSONObject().put("ok", false).put("error", it.message) }
        "debug_ui_info" -> if (!net.prototux.aare.BuildConfig.DEBUG) JSONObject().put("ok", false) else {
            val on = args.optString("on").split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
            net.prototux.aare.host.projected.ProjectedProtocol.UI_INFO_ON = on
            JSONObject().put("ok", true).put("on", on.joinToString(","))
        }
        // debug builds: a state of charge for the energy model Google Maps plans with (O107); without "soc" back to IDmate's
        "debug_ev" -> if (!net.prototux.aare.BuildConfig.DEBUG) JSONObject().put("ok", false) else {
            CarDataService.debugSoc = if (args.has("soc")) args.optDouble("soc") else null
            // the computed model as a file, to compare it with one a real car sent
            CarDataService.car(runtime.context)?.let { java.io.File(runtime.context.filesDir, "ev_generated.bin").writeBytes(EnergyModel.build(it)) }
            JSONObject().put("ok", true).put("model", CarDataService.describe(runtime.context))
        }
        // debug builds: an answer of IDmate's /energy-model as if the server had sent it
        "debug_energy_model" -> if (!net.prototux.aare.BuildConfig.DEBUG) JSONObject().put("ok", false) else {
            net.prototux.aare.dash.DashData.debugEnergyModel(runtime.context, args.toString())
            JSONObject().put("ok", true).put("model", CarDataService.describe(runtime.context))
        }
        // debug builds: write what goes to the car's audio channels to files (raw PCM, named by channel and rate) under the app's external files
        "debug_audio_dump" -> if (!net.prototux.aare.BuildConfig.DEBUG) JSONObject().put("ok", false) else {
            val dir = if (args.optBoolean("on", true)) java.io.File(runtime.context.getExternalFilesDir(null), "audio-dump").apply { mkdirs() } else null
            net.prototux.aare.audio.CarAudioOut.dumpDir = dir
            JSONObject().put("ok", true).put("dir", dir?.path ?: "")
        }
        // debug builds: a pure tone as a navigation prompt (or as music with "media": true), to check the way to the car by its samples
        "debug_tone" -> if (!net.prototux.aare.BuildConfig.DEBUG) JSONObject().put("ok", false) else {
            val rate = args.optInt("rate", 24000); val hz = args.optDouble("hz", 440.0); val ms = args.optInt("ms", 3000)
            val usage = if (args.optBoolean("media")) android.media.AudioAttributes.USAGE_MEDIA else android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            Thread {
                runCatching {
                    val n = rate * ms / 1000
                    val pcm = ShortArray(n) { i -> (kotlin.math.sin(2 * Math.PI * hz * i / rate) * 12000 * minOf(1.0, i / 480.0, (n - i) / 480.0)).toInt().toShort() }
                    val track = android.media.AudioTrack.Builder()
                        .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(usage).build())
                        .setAudioFormat(android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(n * 2).setTransferMode(android.media.AudioTrack.MODE_STATIC).build()
                    track.write(pcm, 0, n); track.play()
                    Thread.sleep(ms + 500L); track.release()
                }.onFailure { android.util.Log.w("AareTools", "debug_tone", it) }
            }.start()
            JSONObject().put("ok", true).put("rate", rate).put("hz", hz).put("ms", ms)
        }
        // debug builds: whether Mate starts Google Maps' navigation provider when Maps comes on the car screen (B4)
        "debug_nav_provider" -> if (!net.prototux.aare.BuildConfig.DEBUG) JSONObject().put("ok", false) else {
            net.prototux.aare.host.projected.NavigationProvider.enabled = args.optBoolean("on", true)
            if (args.optBoolean("stop")) net.prototux.aare.host.projected.NavigationProvider.current?.stop()
            JSONObject().put("ok", true).put("on", net.prototux.aare.host.projected.NavigationProvider.enabled)
        }
        // debug builds: a sentence spoken by the phone's text-to-speech as a navigation prompt, the way ABRP speaks ("media": true: as music)
        "debug_say" -> if (!net.prototux.aare.BuildConfig.DEBUG) JSONObject().put("ok", false) else {
            val text = args.optString("text", "In 300 Metern rechts abbiegen auf die Autobahn A 7 Richtung Hamburg.")
            val ctx = runtime.context.applicationContext
            var tts: android.speech.tts.TextToSpeech? = null
            tts = android.speech.tts.TextToSpeech(ctx) { status ->
                val t = tts ?: return@TextToSpeech
                if (status != android.speech.tts.TextToSpeech.SUCCESS) { t.shutdown(); return@TextToSpeech }
                t.setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(if (args.optBoolean("media")) android.media.AudioAttributes.USAGE_MEDIA else android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                t.language = java.util.Locale.forLanguageTag(net.prototux.aare.Language.code)
                android.util.Log.i("AareTools", "debug_say with engine ${t.defaultEngine}")
                t.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "debug_say")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ t.shutdown() }, 15_000)
            }
            JSONObject().put("ok", true)
        }
        // debug builds: the German voices of the phone's speech engine - name, quality, latency, network, installed
        "debug_voices" -> if (!net.prototux.aare.BuildConfig.DEBUG) JSONObject().put("ok", false) else {
            // asynchronous: the engine answers on the main thread; the list goes to logcat (tag AareVoices)
            var tts: android.speech.tts.TextToSpeech? = null
            tts = android.speech.tts.TextToSpeech(runtime.context.applicationContext) { status ->
                val t = tts ?: return@TextToSpeech
                runCatching {
                    android.util.Log.i("AareVoices", "engine ${t.defaultEngine}, default ${t.defaultVoice?.name}, status $status")
                    t.voices?.filter { it.locale.language == net.prototux.aare.Language.code }?.sortedByDescending { it.quality }?.forEach { v ->
                        android.util.Log.i("AareVoices", "${v.name} quality ${v.quality} latency ${v.latency} network ${v.isNetworkConnectionRequired}" +
                            if (v.features?.contains(android.speech.tts.TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true) " NOT INSTALLED" else "")
                    }
                }
                t.shutdown()
            }
            JSONObject().put("ok", true)
        }
        "debug_night" -> if (!net.prototux.aare.BuildConfig.DEBUG) JSONObject().put("ok", false) else {
            runtime.carNightMode(args.optBoolean("on", true)); JSONObject().put("ok", true).put("night", runtime.ui.night)
        }
        // debug builds: the size of foreign apps for the connected car, as if chosen in the app
        "debug_app_trim" -> if (!net.prototux.aare.BuildConfig.DEBUG) JSONObject().put("ok", false) else {
            net.prototux.aare.Settings(runtime.context).setAppTrim(runtime.ui.headUnitName, args.optInt("steps", 0).coerceIn(-2, 2))
            JSONObject().put("ok", true).put("car", runtime.ui.headUnitName)
        }
        "debug_size" -> if (!net.prototux.aare.BuildConfig.DEBUG) JSONObject().put("ok", false) else {
            net.prototux.aare.Settings(runtime.context).setSize(runtime.ui.headUnitName, args.optInt("percent", 100))
            JSONObject().put("ok", true).put("car", runtime.ui.headUnitName)
        }
        // debug builds: hand the app on the car screen with its own projection (Google Maps) any address, e.g. google.navigation:q=...
        "debug_projected_intent" -> if (!net.prototux.aare.BuildConfig.DEBUG) JSONObject().put("ok", false) else {
            net.prototux.aare.host.projected.ProjectedHost.deliver(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(args.optString("uri"))))
            JSONObject().put("ok", true)
        }
        "car_status" -> status(runtime)
        "car_navigate" -> navigate(runtime, args.optString("destination"))
        "car_media" -> media(runtime, args.optString("action"))
        "car_open" -> open(runtime, args.optString("what"))
        "car_messages" -> messages(runtime, args.optString("app"))
        "car_reply" -> reply(runtime, args.optString("conversation"), args.optString("app"), args.optString("message"))
        "car_mark_read" -> markRead(args.optString("conversation"), args.optString("app"))
        "car_display" -> display(runtime, args.optString("mode"))
        else -> error("unknown car tool $name")
    }

    private fun status(rt: CarRuntime): JSONObject {
        val ui = rt.ui
        val o = JSONObject()
            .put("car", ui.headUnitName)
            .put("driving", ui.drivingRestricted)
            .put("night", ui.night)
            .put("size", rt.size).put("app_trim", rt.appTrim)
            .put("on_screen", when (val s = ui.screen) {
                CarScreen.Dashboard -> "home"
                CarScreen.Launcher -> "app list"
                is CarScreen.App -> ui.app(s.appId)?.label ?: "an app"
            })
        ui.phoneGuidance?.let { g ->
            o.put("navigation", JSONObject().put("app", g.appLabel).put("next", listOf(g.primary, g.secondary).filter { it.isNotBlank() }.joinToString(", "))
                .put("trip", g.detail))
        } ?: ui.app(ui.activeNavAppId)?.let { o.put("navigation_app", it.label) }
        ui.nowPlaying?.let { np ->
            o.put("now_playing", JSONObject().put("title", np.title).put("artist", np.subtitle).put("playing", np.playing)
                .put("app", ui.app(np.appId)?.label ?: ""))
        }
        runCatching { PhoneStatusInfo.read(rt.context) }.getOrNull()?.let { p ->
            o.put("phone", JSONObject().put("battery_percent", p.battery).put("charging", p.charging).put("signal_bars", p.signal ?: 0))
        }
        VehicleStatus.json(net.prototux.aare.car.CarData.state.value)?.let { o.put("vehicle", it) }
        return o
    }

    private fun navigate(rt: CarRuntime, destination: String): JSONObject {
        if (destination.isBlank()) return error("no destination")
        // The Car App Library's navigation intent: a geo: query the app resolves itself.
        if (!rt.navigateTo(destination)) return error("no navigation app in the car; the phone's own navigation app can be used instead")
        val nav = rt.ui.app(rt.ui.activeNavAppId)?.label ?: "the navigation app"
        return ok("searching for \"$destination\" in $nav on the car screen; the driver picks the result if there are several")
    }

    private fun media(rt: CarRuntime, action: String): JSONObject {
        val playing = rt.ui.nowPlaying?.playing == true
        when (action) {
            "play" -> if (!playing) rt.media.playPause()
            "pause" -> if (playing) rt.media.playPause()
            "next" -> rt.media.next()
            "previous" -> rt.media.previous()
            else -> return error("unknown action $action")
        }
        return ok(action)
    }

    private fun open(rt: CarRuntime, what: String): JSONObject {
        val w = what.trim().lowercase()
        val ui = rt.ui
        when (w) {
            "home", "dashboard", "map" -> { ui.screen = CarScreen.Dashboard; return ok("home") }
            "apps", "app list", "launcher" -> { ui.screen = CarScreen.Launcher; return ok("app list") }
        }
        // "Exit to car" isn't an app: a loose match on "car" mustn't take the screen away.
        val apps = ui.apps.filter { it.kind != CarAppKind.EXIT }
        val app = apps.firstOrNull { it.label.equals(what.trim(), ignoreCase = true) }
            ?: apps.firstOrNull { it.label.lowercase().contains(w) || w.contains(it.label.lowercase()) }
            ?: return error("no app called \"$what\" in the car; apps: " + apps.joinToString { it.label })
        rt.actions.openApp(app)
        return ok("${app.label} is on the car screen")
    }

    private fun messages(rt: CarRuntime, app: String): JSONObject {
        val listener = CarNotificationListener.instance ?: return noListener(rt.context)
        val list = listener.conversations()
        val filtered = inApp(list, app) { label(it.packageName) }
        val out = JSONArray()
        filtered.take(8).forEach { c ->
            out.put(JSONObject().put("app", label(c.packageName)).put("conversation", c.title).put("can_reply", c.canReply)
                .put("messages", JSONArray(c.messages.takeLast(5).map { m -> if (m.sender.isNotBlank()) "${m.sender}: ${m.text}" else m.text })))
        }
        return JSONObject().put("conversations", out).put("count", filtered.size)
    }

    /**
     * The items whose title matches [name]: the exact matches if there are any, else the titles that contain it.
     * Blank names and blank titles never match, and a title is never matched by being part of the name ("Al" in
     * "Alice"): a reply must not go to a conversation the driver didn't name.
     */
    internal fun <T> matches(items: List<T>, name: String, title: (T) -> String): List<T> {
        val n = name.trim()
        if (n.isEmpty()) return emptyList()
        val named = items.filter { title(it).isNotBlank() }
        return named.filter { title(it).trim().equals(n, ignoreCase = true) }.ifEmpty { named.filter { title(it).contains(n, ignoreCase = true) } }
    }

    /** The items of [app] (a part of the app's name, as the user said it), or all of them if it's blank. */
    internal fun <T> inApp(items: List<T>, app: String, appName: (T) -> String): List<T> =
        if (app.isBlank()) items else items.filter { appName(it).contains(app.trim(), ignoreCase = true) }

    /**
     * Runs [use] on the one conversation called [name] (in [app], if given), or gives back the error. The
     * assistant's confirmation shows the name the LLM sent, so anything but a single match is refused, and the
     * result names the conversation used. Two conversations with the same name (Mom on WhatsApp and SMS) are told
     * apart by their app.
     */
    private fun withConversation(name: String, app: String, where: String, use: (Conversation) -> JSONObject): JSONObject {
        if (name.isBlank()) return error("which conversation?")
        val found = inApp(matches(CarNotificationListener.instance?.conversations().orEmpty(), name) { it.title }, app) { label(it.packageName) }
        val inWhat = if (app.isBlank()) "" else " in $app"
        return when (found.size) {
            1 -> use(found[0])
            0 -> error("no conversation called \"$name\"$inWhat$where")
            else -> error("several conversations match \"$name\"$inWhat, ask which one, then call again with its name and app: " +
                found.joinToString { "${it.title} (${label(it.packageName)})" })
        }
    }

    private fun reply(rt: CarRuntime, name: String, app: String, message: String): JSONObject {
        val listener = CarNotificationListener.instance ?: return if (CarNotificationListener.accessGranted(rt.context)) {
            CarNotificationListener.rebind(rt.context)
            error("the messages aren't available yet, try again in a moment")
        } else error("AARE has no notification access on the phone")
        if (message.isBlank()) return error("empty message")
        return withConversation(name, app, " among the unread messages") { c ->
            when {
                !c.canReply -> error("${c.title}'s app doesn't allow replies from notifications")
                listener.reply(c, message.trim()) -> resolved(ok("sent to ${c.title}"), c)
                else -> error("the app refused the reply")
            }
        }
    }

    private fun markRead(name: String, app: String): JSONObject = withConversation(name, app, "") { c ->
        val pi = c.markRead ?: return@withConversation error("${c.title}'s app has no mark-as-read action")
        if (runCatching { pi.send() }.isSuccess) resolved(ok("marked as read"), c) else error("the app refused")
    }

    /** Adds the conversation actually used to a result. */
    private fun resolved(result: JSONObject, c: Conversation) = result.put("conversation", c.title).put("app", label(c.packageName))

    /** An app's name for the assistant, or its package if unknown. */
    private fun label(pkg: String): String {
        val pm = CarNotificationListener.instance?.packageManager ?: return pkg
        return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    private fun display(rt: CarRuntime, mode: String): JSONObject {
        val theme = when (mode) {
            "dark" -> Settings.THEME_DARK
            "light" -> Settings.THEME_LIGHT
            "auto" -> Settings.THEME_AUTO
            else -> return error("unknown mode $mode")
        }
        Settings(rt.context).carTheme = theme
        return ok(mode)
    }

    /** No listener bound: either no access at all, or Android hasn't (re)bound it yet. */
    private fun noListener(context: android.content.Context): JSONObject =
        if (CarNotificationListener.accessGranted(context)) {
            CarNotificationListener.rebind(context)
            JSONObject().put("conversations", JSONArray()).put("count", 0)
        } else error("AARE has no notification access on the phone")

    private fun ok(what: String) = JSONObject().put("ok", true).put("result", what)
    private fun error(what: String) = JSONObject().put("ok", false).put("error", what)

    private class Param(val name: String, val json: JSONObject)

    private fun param(name: String, type: String, description: String, enum: List<String>? = null) =
        Param(name, JSONObject().put("type", type).put("description", description).apply { enum?.let { put("enum", JSONArray(it)) } })

    private fun tool(name: String, description: String, vararg params: Pair<Param, Boolean>, silent: Boolean = false,
                     confirm: Boolean = false, summary: String = ""): JSONObject {
        val props = JSONObject()
        params.forEach { (p, _) -> props.put(p.name, p.json) }
        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put("parameters", JSONObject().put("type", "object").put("properties", props)
                .put("required", JSONArray(params.filter { it.second }.map { it.first.name })))
            .put("confirm", confirm)
            .put("silent", silent)
            .apply { if (summary.isNotEmpty()) put("summary", summary) }
    }
}
