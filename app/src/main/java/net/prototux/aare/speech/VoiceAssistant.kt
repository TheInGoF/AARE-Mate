package net.prototux.aare.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.prototux.aare.speech.VoiceCommands.Kind

/**
 * The microphone of the bar (18.11.2026: "Die Spracherkennung mit Whisper soll zukünftig das System steuern können ...
 * über das Mikrofon in der Leiste unten. Es muss Musik suchen, Navigation und Apps"): listens with Whisper on the
 * device, splits what was heard into command and target ([VoiceCommands]) and does it - opens an app, navigates (home
 * and the driver's own places by name), plays music from the collection, calls a contact, answers a conversation of a
 * messenger, steers what plays. Says what it does. Without a command word, what is heard goes where the screen is: a
 * place while the navigation is in front, music while the music is.
 */
object VoiceAssistant {
    private const val TAG = "MateVoice"

    enum class Phase { IDLE, LISTEN, THINK, ASK, CONFIRM, DONE, FAILED }

    var phase by mutableStateOf(Phase.IDLE)
        private set
    /** What was heard and what was answered, for the card above the bar. */
    var heard by mutableStateOf("")
        private set
    var answer by mutableStateOf("")
        private set
    /** A call waiting to be placed (18.11.2026): seconds left; [now] places it at once, [cancel] drops it. */
    var secondsLeft by mutableStateOf(0)
        private set
    private var pendingCall: (() -> Unit)? = null

    /** What the car screen lends: its apps, opening one, the navigation, what plays. */
    interface Host {
        val apps: List<net.prototux.aare.ui.car.CarAppEntry>
        val playing: Boolean
        fun open(app: net.prototux.aare.ui.car.CarAppEntry)
        fun navigateTo(place: String)
        fun playPause()
        fun next()
        fun previous()
    }

    @Volatile var host: Host? = null
    enum class Hint { NONE, NAVI, MUSIC }

    private val main = Handler(Looper.getMainLooper())
    private var dictation: Dictation? = null
    /** Waiting for a message's words: the conversation to answer and who it is. */
    private var askFor: Pair<net.prototux.aare.notify.Conversation, String>? = null

    fun tap(context: Context, hint: Hint = Hint.NONE) {
        when (phase) {
            Phase.LISTEN, Phase.ASK -> dictation?.stop()
            Phase.THINK -> {}
            // a call waits: the microphone drops it. A new command begun here hid the card with its X while the
            // countdown ran on, and the call was placed all the same
            Phase.CONFIRM -> cancel()
            else -> listen(context.applicationContext, hint)
        }
    }

    fun cancel() {
        val wasCall = pendingCall != null
        pendingCall = null
        main.removeCallbacksAndMessages(TICK)
        dictation?.cancel(); dictation = null; askFor = null
        if (wasCall) done(VoiceCommands.grammar().say("say_call_cancelled"), false) else phase = Phase.IDLE
    }

    /** Places the waiting call now. */
    fun now() {
        val call = pendingCall ?: return
        pendingCall = null
        main.removeCallbacksAndMessages(TICK)
        dictation?.cancel(); dictation = null
        call()
    }

    private val TICK = Any()
    private const val CONFIRM_S = 4

    private fun listen(ctx: Context, hint: Hint) {
        heard = ""; answer = ""
        if (askFor == null) phase = Phase.LISTEN
        dictation = Dictation(ctx).also { d ->
            d.run({ main.post { phase = Phase.THINK } }) { text -> main.post { dictation = null; handle(ctx, text, hint) } }
        }
    }

    private fun handle(ctx: Context, text: String?, hint: Hint) {
        val g = VoiceCommands.grammar()
        // null: no microphone or no model - not the same as silence
        if (text == null) { askFor = null; done(g.say("say_cannot_listen"), false); return }
        if (text.isBlank()) { askFor = null; done(g.say("say_nothing"), false); return }
        heard = text
        val ask = askFor
        if (ask != null) {
            askFor = null
            val ok = net.prototux.aare.notify.CarNotificationListener.instance?.reply(ask.first, text) == true
            done(if (ok) g.say("say_message_sent", ask.second) else g.say("say_message_none_any", ask.second), ok)
            return
        }
        var c = VoiceCommands.parse(text, g)
        if (c.kind == Kind.UNKNOWN) c = when (hint) {
            Hint.NAVI -> VoiceCommands.Command(Kind.NAVIGATE, VoiceCommands.norm(text), heard = text)
            Hint.MUSIC -> VoiceCommands.Command(Kind.PLAY, VoiceCommands.norm(text), heard = text)
            Hint.NONE -> c
        }
        Log.i(TAG, "\"$text\" -> ${c.kind} \"${c.target}\"" + (c.app?.let { " via $it" } ?: ""))
        run(ctx, c, g)
    }

    private fun run(ctx: Context, c: VoiceCommands.Command, g: VoiceCommands.Grammar) {
        val h = host
        when (c.kind) {
            Kind.HOME -> {
                val home = net.prototux.aare.places.Places.load(ctx).places.firstOrNull { it.home }
                if (home == null) done(g.say("say_no_home"), false)
                else { navigate(ctx, geo(home)); done(g.say("say_home"), true) }
            }
            Kind.NAVIGATE -> {
                val place = ownPlace(ctx, c.target)
                navigate(ctx, place?.let { geo(it) } ?: c.target)
                done(g.say("say_navigate", place?.name ?: c.target), true)
            }
            Kind.OPEN_APP -> {
                if (h == null) { openOnPhone(ctx, c.target, g); return }
                val app = h.apps.map { it to score(c.target, it.label) }.filter { it.second >= 0.7 }.maxByOrNull { it.second }?.first
                if (app == null) done(g.say("say_no_app", c.target), false) else { h.open(app); done(g.say("say_open", app.label), true) }
            }
            Kind.PLAY -> play(ctx, c, g, retried = false)
            Kind.CALL -> {
                if (!granted(ctx, android.Manifest.permission.READ_CONTACTS)) { done(g.say("say_no_contacts_right"), false); return }
                if (!granted(ctx, android.Manifest.permission.CALL_PHONE)) { done(g.say("say_no_call_right"), false); return }
                val who = contact(ctx, c.target)
                if (who == null) done(g.say("say_no_contact", c.target), false)
                else confirmCall(ctx, who, g)
            }
            Kind.MESSAGE -> message(ctx, c, g)
            Kind.PAUSE -> { if (h?.playing == true) h.playPause(); done("", true) }
            Kind.RESUME -> { if (h?.playing == false) h.playPause(); done("", true) }
            Kind.NEXT -> { h?.next(); done("", true) }
            Kind.PREVIOUS -> { h?.previous(); done("", true) }
            Kind.UNKNOWN -> done(g.say("say_unknown", c.heard.trim().trimEnd('.')), false)
        }
    }

    /**
     * 18.11.2026 ("bestätigt er erstmal, liest den Namen vor, den er anruft - nicht den er verstanden hat - und man hat 3-4
     * Sekunden, abbrechen zu sagen oder das X zu drücken, dass er nicht irgendjemanden Komisches anruft"): the contact's own
     * name is said, a card counts down with an X; meanwhile Mate listens - "abbrechen", "nein" drop it, "ja" calls at once.
     * What is said asks nothing: after "Rufe Maria an. Abbrechen?" a "ja" meant to drop it placed the call at once, and
     * the question's last word, still sounding when listening began, could be heard as the driver's "abbrechen".
     */
    private fun confirmCall(ctx: Context, who: Pair<String, String>, g: VoiceCommands.Grammar) {
        pendingCall = {
            if (!net.prototux.aare.car.Calls.place(ctx, who.second)) {
                Log.w(TAG, "call could not be placed after the countdown")
                heard = ""
                done(g.say("say_call_failed", who.first), false)
            } else {
                Log.i(TAG, "call placed after the countdown")
                // shown, not said again: the call's own sound starts now
                answer = g.say("say_call", who.first); heard = ""
                phase = Phase.DONE
                main.postDelayed({ if (phase == Phase.DONE) phase = Phase.IDLE }, 4000)
            }
        }
        answer = who.first
        phase = Phase.CONFIRM
        val q = g.say("say_call_confirm", who.first)
        say(q)
        // the countdown starts once the question has been said; listening for the answer meanwhile
        val spoken = 900L + 65L * q.length
        main.postAtTime({
            if (pendingCall == null) return@postAtTime
            secondsLeft = CONFIRM_S
            dictation = Dictation(ctx).also { d ->
                d.run({}) { heard -> main.post {
                    if (pendingCall == null) return@post
                    val t = VoiceCommands.norm(heard.orEmpty())
                    when {
                        g.cancel.any { t == it || t.startsWith("$it ") || t.endsWith(" $it") } -> { Log.i(TAG, "call cancelled by voice: \"$t\""); cancel() }
                        g.confirm.any { t == it || t.startsWith("$it ") } -> now()
                    }
                } }
            }
            fun tick() {
                if (pendingCall == null) return
                if (secondsLeft <= 0) { now(); return }
                main.postAtTime({ secondsLeft -= 1; tick() }, TICK, android.os.SystemClock.uptimeMillis() + 1000)
            }
            tick()
        }, TICK, android.os.SystemClock.uptimeMillis() + spoken)
    }

    /** Music from the collection (MusicService's own matching); Mate Musik is opened first when it does not run yet. */
    private fun play(ctx: Context, c: VoiceCommands.Command, g: VoiceCommands.Grammar, retried: Boolean) {
        phase = Phase.THINK
        val started = net.prototux.aare.music.MusicService.voice(c.heard) { r ->
            done(if (r != null) ctx.getString(net.prototux.aare.R.string.music_voice_playing, r) else g.say("say_play_none", c.target), r != null)
        }
        if (started) return
        val mate = host?.apps?.firstOrNull { it.packageName == ctx.packageName && it.kind == net.prototux.aare.ui.car.CarAppKind.MEDIA }
        if (retried || mate == null) { done(g.say("say_music_off"), false); return }
        host?.open(mate)
        main.postDelayed({ play(ctx, c, g, retried = true) }, 2500)
    }

    private fun message(ctx: Context, c: VoiceCommands.Command, g: VoiceCommands.Grammar) {
        val listener = net.prototux.aare.notify.CarNotificationListener.instance
        val convs = listener?.conversations(c.app).orEmpty().filter { it.canReply }
        val conv = convs.map { it to score(c.target, it.title) }.filter { it.second >= 0.7 }.maxByOrNull { it.second }?.first
        val appName = c.app?.let { p -> runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p) }
        if (conv == null) {
            done(if (appName != null) g.say("say_message_none", c.target, appName) else g.say("say_message_none_any", c.target), false); return
        }
        if (c.text != null) {
            val ok = listener?.reply(conv, c.text) == true
            done(if (ok) g.say("say_message_sent", conv.title) else g.say("say_message_none_any", conv.title), ok); return
        }
        // ask for the words, then listen again once the question has been spoken
        askFor = conv to conv.title
        phase = Phase.ASK
        val q = g.say("say_message_ask", conv.title)
        answer = q
        say(q)
        main.postDelayed({ if (askFor != null) listen(ctx, Hint.NONE) }, 1200L + 70L * q.length)
    }

    /** To the car's navigation; without a car screen to the phone's navigation app. */
    private fun navigate(ctx: Context, place: String) {
        host?.let { it.navigateTo(place); return }
        runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode(place))).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Without a car screen: an app of the phone by its name. */
    private fun openOnPhone(ctx: Context, said: String, g: VoiceCommands.Grammar) {
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
        val best = apps.map { it to score(said, it.loadLabel(pm).toString()) }.filter { it.second >= 0.7 }.maxByOrNull { it.second }?.first
        val launch = best?.activityInfo?.packageName?.let { pm.getLaunchIntentForPackage(it) }
        if (launch == null) { done(g.say("say_no_app", said), false); return }
        ctx.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        done(g.say("say_open", best.loadLabel(pm).toString()), true)
    }

    private fun done(text: String, ok: Boolean) {
        answer = text
        phase = if (ok) Phase.DONE else Phase.FAILED
        if (text.isNotBlank()) say(text)
        val shown = answer
        main.postDelayed({ if (answer == shown && (phase == Phase.DONE || phase == Phase.FAILED)) phase = Phase.IDLE }, 5000)
    }

    private fun say(text: String) { net.prototux.aare.notify.CarNotificationListener.instance?.speak(text) }

    private fun geo(p: net.prototux.aare.places.Places.Place) = "%.6f,%.6f(%s)".format(java.util.Locale.ROOT, p.lat, p.lon, p.name)

    /** One of the driver's own places by its name, or by its list's name when the list holds just one ("zur Arbeit"). */
    private fun ownPlace(ctx: Context, said: String): net.prototux.aare.places.Places.Place? {
        val book = net.prototux.aare.places.Places.load(ctx)
        book.places.map { it to score(said, it.name) }.filter { it.second >= 0.85 }.maxByOrNull { it.second }?.let { return it.first }
        val cat = book.categories.map { it to score(said, it.name) }.filter { it.second >= 0.85 }.maxByOrNull { it.second }?.first ?: return null
        return book.places.filter { it.category == cat.id }.singleOrNull()
    }

    private fun granted(ctx: Context, permission: String) =
        ctx.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** A contact by name (spelling and sound alike): its name and number, a mobile one first. */
    private fun contact(ctx: Context, said: String): Pair<String, String>? = runCatching {
        val out = ArrayList<Triple<String, String, Int>>()
        ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE),
            null, null, null)?.use { cur -> while (cur.moveToNext()) out += Triple(cur.getString(0).orEmpty(), cur.getString(1).orEmpty(), cur.getInt(2)) }
        val best = out.map { it to score(said, it.first) }.filter { it.second >= 0.75 }
            .maxWithOrNull(compareBy<Pair<Triple<String, String, Int>, Double>> { it.second }
                .thenBy { if (it.first.third == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) 1 else 0 })
        best?.first?.let { it.first to it.second }
    }.getOrNull()

    /** How well [said] names [name]: whole name, or its first name / one word of it ("Maria" for "Maria Müller"). */
    private fun score(said: String, name: String): Double {
        val whole = VoiceMatch.similarity(said, name)
        val words = name.split(Regex("\\s+")).filter { it.length > 1 }
        val part = words.maxOfOrNull { VoiceMatch.similarity(said, it) } ?: 0.0
        return maxOf(whole, part * 0.95)
    }
}
