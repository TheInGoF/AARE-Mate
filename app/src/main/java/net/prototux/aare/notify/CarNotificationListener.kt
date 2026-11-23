package net.prototux.aare.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import net.prototux.aare.R
import net.prototux.aare.media.ArtLoader
import java.util.Locale

/** One conversation, from a messaging app's MessagingStyle notification. */
class Conversation(
    /** The notification's key. */
    val key: String,
    val packageName: String,
    /** The contact or group name. */
    val title: String,
    /** Oldest first. */
    val messages: List<Line>,
    val icon: Bitmap?,
    val timeMs: Long,
    val markRead: PendingIntent?,
    /** The reply action, with its RemoteInput. */
    val reply: NotificationCompat.Action?,
) {
    /** [fromUser]: written by the phone's owner (a reply), not by the contact. */
    class Line(val sender: String, val text: String, val timeMs: Long, val fromUser: Boolean = false)

    val canReply: Boolean get() = reply?.remoteInputs?.isNotEmpty() == true

    /**
     * What is read aloud: the latest messages, each as "<who> writes: <message>" ([says] is that sentence with
     * two places). Several messages in a row from the same person name that person once.
     */
    fun spoken(says: String): String {
        val out = StringBuilder()
        var before: String? = null
        for (m in messages.takeLast(5)) {
            val who = m.sender.ifEmpty { title }
            if (out.isNotEmpty()) out.append(". ")
            out.append(if (who == before) m.text else says.format(who, m.text))
            before = who
        }
        return out.toString()
    }
}

/**
 * Messages in the car. Conversations posted as MessagingStyle notifications (what Android Auto
 * reads too) appear as banners on the car screen, with "Read aloud" and "Mark as read", and in
 * each messaging app's screen (see MessagingScreen). Navigation apps' guidance notifications
 * become [guidance], for the dashboard and the instrument cluster.
 *
 * Also what lets the media hub see other apps' media sessions (notification access).
 */
class CarNotificationListener : NotificationListenerService() {
    private var tts: TextToSpeech? = null
    /** Whether [tts] has finished starting; what comes before waits in [ttsPending] (main thread). */
    private var ttsReady = false
    private val ttsPending = ArrayList<String>()
    /** The newest message seen per notification key (bounded), so re-posts aren't shown again. */
    private val forwarded = object : LinkedHashMap<String, Conversation.Line>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Conversation.Line>?) = size > 256
    }
    /** The dialer notification that named [caller]: only its removal clears the name. */
    private var callerKey: String? = null

    override fun onListenerConnected() {
        instance = this
        // A car that connected before this listener was bound still gets what waits unread.
        catchUp()
        _changes.update { it + 1 }
        refreshGuidance()
    }

    override fun onListenerDisconnected() {
        instance = null
        _guidance.value = null
        // Its removal would not reach us any more.
        callerKey = null
        _caller.value = null
        _waiting.value = null
    }

    override fun onDestroy() {
        tts?.shutdown()
        instance = null
        super.onDestroy()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        _changes.update { it + 1 }
        if (sbn.key == callerKey) { callerKey = null; _caller.value = null }
        if (sbn.key == _waiting.value?.key) _waiting.value = null
        if (sbn.key == _guidance.value?.key) refreshGuidance()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        _changes.update { it + 1 }
        // The dialer's call notification names the caller (no call log permission needed for that).
        // Only the dialer's: the car shows this name for the cellular call, so any other app could spoof it.
        if (sbn.notification.category == Notification.CATEGORY_CALL && isDialer(sbn.packageName)) {
            val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
            val acts = sbn.notification.actions.orEmpty().mapNotNull { a -> a.title?.toString()?.takeIf { it.isNotBlank() }?.let { WaitingCall.Action(it, a.actionIntent) } }
            // a call coming in while another is on (19.11.2026: "wenn ich gerade ein Telefonat führe und ich kriege noch einen
            // Anruf rein ... Anruf halten und rangehen, Anruf beenden und rangehen oder den anderen Anruf abblocken"): the
            // dialer posts it as a call of its own with its answers - kept with them, the name of the call on stays
            val incoming = callType(sbn.notification) == 1 || (callType(sbn.notification) == 0 && acts.any { ANSWER.containsMatchIn(it.title) })
            if (incoming && callerKey != null && callerKey != sbn.key) {
                val w = WaitingCall(sbn.key, title ?: "", acts)
                if (_waiting.value?.key != w.key) net.prototux.aare.DiagLog.add("call: another call comes in during the call - the phone app offers: " +
                    acts.joinToString { "\"" + it.title + "\"" }.ifEmpty { "nothing" }, area = "PHONE")
                _waiting.value = w
                return
            }
            title?.let {
                _caller.value = it
                callerKey = sbn.key
            }
            return
        }
        if (sbn.packageName != packageName && PhoneGuidance.isGuidance(sbn)) {
            PhoneGuidance.from(this, sbn)?.let { _guidance.value = it }
            return
        }
        val chatty = sbn.packageName != packageName && looksLikeMessage(sbn.notification)
        val sink = sink
        if (sink == null) { if (chatty) note(sbn, "no car connected - kept for the message area"); return }
        val m = message(sbn)
        if (m != null) { if (chatty) note(sbn, "shown"); sink(m) } else if (chatty) note(sbn, why ?: "not a conversation")
    }

    /** Why the last [message] gave none (for the log). */
    private var why: String? = null

    /**
     * A line in Mate's log for each messenger notification and what became of it (23.11.2026: Element X's private messages
     * did not come through, its group messages did, and the log said nothing): the app, the kind of notification and the
     * outcome - no names, no texts.
     */
    private fun note(sbn: StatusBarNotification, outcome: String) {
        val n = sbn.notification
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val kind = when {
            n.flags and Notification.FLAG_GROUP_SUMMARY != 0 -> "group summary"
            style == null -> "plain (no MessagingStyle)"
            style.isGroupConversation -> "group conversation, ${style.messages.size} messages"
            else -> "private conversation, ${style.messages.size} messages"
        }
        net.prototux.aare.DiagLog.add("message: ${sbn.packageName} $kind, category ${n.category ?: "none"}" +
            (if (replyAction(n) != null) ", can reply" else "") + " - $outcome", area = "PHONE")
    }



    /**
     * The messages that came in while no car was connected and are still unread on the phone, oldest first:
     * the car's message area starts with them instead of empty. Each is handed out once.
     */
    fun waiting(): List<Message> = runCatching { activeNotifications }.getOrNull().orEmpty()
        .sortedBy { it.postTime }.mapNotNull { message(it) }
        .also { Log.i(TAG, "messages waiting at connect: ${it.size}") }

    /** The news of a conversation's notification as a message for the car; null when there is none. */
    private fun message(sbn: StatusBarNotification): Message? {
        why = null
        val c = parse(sbn) ?: return null
        val last = c.messages.last()
        // Apps re-post the whole conversation on every change: after the user's own reply, when
        // marked read, or just refreshed. Only a last message other than the one already seen is
        // news (not "newer": senders' clocks differ), and never one the user wrote (an auto-reply
        // would otherwise show as from the contact).
        val prev = forwarded[sbn.key]
        if (prev != null && last.timeMs == prev.timeMs && last.text == prev.text && last.sender == prev.sender) { why = "the same last message again"; return null }
        forwarded[sbn.key] = last
        if (last.fromUser) { why = "last message is the user's own"; return null }
        val actions = buildList<Pair<String, () -> Unit>> {
            add(getString(R.string.msg_action_read_aloud) to { speak(getString(R.string.msg_spoken, last.sender.ifEmpty { c.title }, last.text)) })
            c.markRead?.let { pi -> add(getString(R.string.msg_action_mark_read) to { runCatching { pi.send() } }) }
        }
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        val title = if (last.sender.isNotEmpty() && last.sender != c.title) "${c.title} · ${last.sender}" else c.title.ifEmpty { app }
        val group = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)?.isGroupConversation == true
        Log.i(TAG, "message from ${sbn.packageName} for the car")
        val key = sbn.key
        // Answers go to the conversation as it stands when the answer is sent: the app may have re-posted it since.
        val reply: ((String) -> Boolean)? = if (!c.canReply) null else { text ->
            conversations().firstOrNull { it.key == key }?.let { reply(it, text) } ?: false
        }
        return Message(sbn.key, title, last.text, c.icon, actions, group, reply) { speak(getString(R.string.msg_spoken, last.sender.ifEmpty { c.title }, last.text)) }
    }

    /**
     * The default dialer, the system one (some phones post the call notification from it), or any app
     * of the system image: on Samsung and some AOSP ROMs a separate in-call UI posts it
     * (com.samsung.android.incallui, com.android.incallui), and no installed app can pass for one.
     */
    private fun isDialer(pkg: String): Boolean = runCatching {
        val telecom = getSystemService(TelecomManager::class.java) ?: return@runCatching false
        if (pkg == telecom.defaultDialerPackage) return@runCatching true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pkg == telecom.systemDialerPackage) return@runCatching true
        val flags = packageManager.getApplicationInfo(pkg, 0).flags
        (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    }.getOrDefault(false)

    /** The conversations currently in the notification shade, newest first; all apps if [packageName] is null. */
    fun conversations(packageName: String? = null): List<Conversation> =
        runCatching { activeNotifications }.getOrNull().orEmpty()
            .filter { packageName == null || it.packageName == packageName }
            .mapNotNull(::parse)
            .sortedByDescending { it.timeMs }

    fun readAloud(c: Conversation) = speak(c.spoken(getString(R.string.msg_spoken)))

    /** The newest guidance notification still showing, if any. */
    private fun refreshGuidance() {
        _guidance.value = runCatching { activeNotifications }.getOrNull().orEmpty()
            .filter { it.packageName != packageName && PhoneGuidance.isGuidance(it) }
            .maxByOrNull { it.postTime }
            ?.let { PhoneGuidance.from(this, it) }
    }

    /** Sends [text] through the conversation's reply action. False if it has none or it failed. */
    fun reply(c: Conversation, text: String): Boolean {
        val action = c.reply ?: return false
        val inputs = action.remoteInputs?.takeIf { it.isNotEmpty() } ?: return false
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } })
        return runCatching { action.actionIntent?.send(this, 0, intent) }.isSuccess
    }

    private fun parse(sbn: StatusBarNotification): Conversation? {
        val n = sbn.notification
        if (sbn.packageName == packageName) return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) { why = "group summary"; return null }
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) ?: return plain(sbn)
        val lines = style.messages.mapNotNull { m ->
            val text = m.text?.toString() ?: return@mapNotNull null
            // A null sender is the user, per MessagingStyle; some apps name the user instead.
            val p = m.person
            val fromUser = p == null || p === style.user || (p.key != null && p.key == style.user.key) ||
                (p.key == null && p.name != null && p.name.toString() == style.user.name?.toString())
            Conversation.Line(p?.name?.toString() ?: "", text, m.timestamp, fromUser)
        }
        if (lines.isEmpty()) { why = "conversation without text"; return null }
        val title = style.conversationTitle?.toString()
            ?: lines.lastOrNull { it.sender.isNotEmpty() && !it.fromUser }?.sender
            ?: n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        return Conversation(
            key = sbn.key,
            packageName = sbn.packageName,
            title = title,
            messages = lines,
            icon = (n.getLargeIcon() ?: n.smallIcon)?.let { loadIcon(it, sbn.packageName) },
            timeMs = lines.last().timeMs.takeIf { it > 0 } ?: sbn.postTime,
            markRead = actionWith(n, NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)?.actionIntent,
            reply = actionWith(n, NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                ?: (0 until NotificationCompat.getActionCount(n)).asSequence().mapNotNull { NotificationCompat.getAction(n, it) }
                    .firstOrNull { !it.remoteInputs.isNullOrEmpty() },
        )
    }

    /**
     * A chat notification without MessagingStyle as a conversation of one message (23.11.2026: Element X's private messages
     * did not come through, its group messages did): some messengers post a plain notification when they cannot show the
     * conversation, e.g. an encrypted message not yet readable, or post private chats plainly. Title = sender, text = message.
     */
    private fun plain(sbn: StatusBarNotification): Conversation? {
        val n = sbn.notification
        if (!looksLikeMessage(n)) return null
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) { why = "ongoing notification"; return null }
        val (title, text) = plainParts(n) ?: run { why = "plain notification without title or text"; return null }
        val time = n.`when`.takeIf { it > 0 } ?: sbn.postTime
        return Conversation(
            key = sbn.key, packageName = sbn.packageName, title = title,
            messages = listOf(Conversation.Line(title, text, time)),
            icon = (n.getLargeIcon() ?: n.smallIcon)?.let { loadIcon(it, sbn.packageName) },
            timeMs = time,
            markRead = actionWith(n, NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)?.actionIntent,
            reply = replyAction(n),
        )
    }


    /** Icons are loaded with our identity, so a URI icon must pass the same owner check as media art. */
    private fun loadIcon(icon: Icon, owner: String): Bitmap? = runCatching {
        require(ArtLoader.iconAllowed(this, icon, owner))
        icon.loadDrawable(this)?.toBitmap(96, 96)
    }.getOrNull()

    // The voice while it speaks: how many utterances are still to come, and the audio focus held for them.
    private val speaking = java.util.concurrent.atomic.AtomicInteger()
    private val speechFocus: android.media.AudioFocusRequest by lazy {
        // "may duck": music goes down to a fifth while the voice speaks; players of speech pause instead (Android's rule)
        android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build()
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun speechStarts() {
        if (speaking.getAndIncrement() > 0) return
        // a podcast or audio book pauses while a message is read out (System I-083)
        net.prototux.aare.audio.SpokenPause.init(this); net.prototux.aare.audio.SpokenPause.hold()
        main.post {
            onSpeech?.invoke(true)
            runCatching { getSystemService(android.media.AudioManager::class.java).requestAudioFocus(speechFocus) }
        }
    }

    private fun speechEnds() {
        if (speaking.decrementAndGet() > 0) return
        speaking.set(0)
        net.prototux.aare.audio.SpokenPause.letGo()
        main.post {
            runCatching { getSystemService(android.media.AudioManager::class.java).abandonAudioFocusRequest(speechFocus) }
            onSpeech?.invoke(false)
        }
    }

    /**
     * Whether the internet is there and good enough for a voice from the engine's server: a validated network -
     * Wi-Fi, or mobile data with at least two of four bars and a megabit downstream.
     */
    private fun goodConnection(): Boolean = runCatching {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
        if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return@runCatching false
        if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) return@runCatching true
        val bars = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            runCatching { getSystemService(android.telephony.TelephonyManager::class.java)?.signalStrength?.level }.getOrNull() else null
        (bars == null || bars >= 2) && caps.linkDownstreamBandwidthKbps >= 1_000
    }.getOrDefault(false)

    /** Speaks with the media usage, so it reaches the car along with the music; what plays makes room meanwhile. */
    fun speak(text: String) {
        // the natural voice on the device when chosen and there (O110); the system's otherwise
        // every voice of the device counts, not only Thorsten: with another Piper voice or a Kokoro one chosen, messages
        // and the voice control's answers were said by the phone's voice (18.11.2026: "ein Hin und Her an Sprechern")
        if (net.prototux.aare.speech.LocalTts.available(this)) {
            speechStarts()
            net.prototux.aare.speech.LocalTts.speak(this, text) { speechEnds() }
            return
        }
        fun say(t: TextToSpeech, text: String) {
            // The voice chosen in the settings: the one for a good connection (it may be computed by the engine's
            // server), or the one on the device when there is no internet or the reception is poor - decided for
            // each message, so that none waits for a server it cannot reach.
            val s = net.prototux.aare.Settings(this)
            val want = if (goodConnection()) s.ttsVoice else s.ttsVoiceLocal
            if (want != null && t.voice?.name != want) runCatching { t.voices?.firstOrNull { it.name == want }?.let { t.voice = it } }
            else if (want == null && t.voice?.isNetworkConnectionRequired == true && !goodConnection())
                runCatching { t.voices?.firstOrNull { !it.isNetworkConnectionRequired && it.locale == t.voice?.locale }?.let { t.voice = it } }
            speechStarts()
            if (t.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), "m${System.nanoTime()}") != TextToSpeech.SUCCESS) speechEnds()
        }
        val t = tts
        if (t != null) { if (ttsReady) say(t, text) else ttsPending += text; return }
        // what comes while the voice starts waits for it: spoken before it is ready, it was lost
        ttsReady = false
        ttsPending.clear(); ttsPending += text
        // Android reports a missing engine from within the constructor, before [tts] is set
        var failedAtOnce = false
        val created = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                // not kept: the next message tries a new one
                Log.w(TAG, "no text-to-speech")
                val made = tts
                if (made == null) { failedAtOnce = true; return@TextToSpeech }
                made.shutdown(); tts = null; ttsPending.clear()
                return@TextToSpeech
            }
            val made = tts ?: return@TextToSpeech
            made.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            made.language = Locale.getDefault()
            made.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = speechEnds()
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = speechEnds()
                override fun onStop(utteranceId: String?, interrupted: Boolean) = speechEnds()
            })
            ttsReady = true
            val waiting = ttsPending.toList(); ttsPending.clear()
            waiting.forEach { say(made, it) }
        }
        if (failedAtOnce) { created.shutdown(); ttsPending.clear() } else tts = created
    }

    /** A conversation update for the car screen. */
    class Message(
        val key: String,
        val title: String,
        val text: String,
        val icon: Bitmap?,
        val actions: List<Pair<String, () -> Unit>>,
        val group: Boolean,
        /** Sends an answer into the conversation; null when the app offers no reply. */
        val reply: ((String) -> Boolean)?,
        /** Reads the message aloud (through the media stream, so it reaches the car). */
        val readAloud: () -> Unit,
    )

    companion object {
        private const val TAG = "AareNotify"
        @Volatile var instance: CarNotificationListener? = null
            private set

        /** Set by the car session while connected; called on the main thread. */
        @Volatile var sink: ((Message) -> Unit)? = null

        /** Told when the voice starts (true) and when it has finished (false). Main thread. */
        @Volatile var onSpeech: ((Boolean) -> Unit)? = null

        /** Takes what waits unread when a car connects (see [waiting]): quietly, no banner, no voice. */
        @Volatile var waitingSink: ((Message) -> Unit)? = null

        /** A notification of a chat: a conversation, of the message category, or with a reply field. */
        internal fun looksLikeMessage(n: Notification) = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) != null ||
            n.category == Notification.CATEGORY_MESSAGE || replyAction(n) != null

        internal fun replyAction(n: Notification) = actionWith(n, NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            ?: (0 until NotificationCompat.getActionCount(n)).asSequence().mapNotNull { NotificationCompat.getAction(n, it) }
                .firstOrNull { !it.remoteInputs.isNullOrEmpty() }

        internal fun actionWith(n: Notification, semantic: Int): NotificationCompat.Action? {
            for (i in 0 until NotificationCompat.getActionCount(n)) {
                val a = NotificationCompat.getAction(n, i) ?: continue
                if (a.semanticAction == semantic) return a
            }
            return null
        }

        /** Sender and text of a plain chat notification, null without both. */
        internal fun plainParts(n: Notification): Pair<String, String>? {
            val x = n.extras
            val title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() } ?: return null
            val text = (x.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: x.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.takeIf { it.isNotBlank() } ?: return null
            return title to text
        }

        /** Hands the waiting messages to [waitingSink]; each once, so calling it again does no harm. Main thread. */
        fun catchUp() {
            val s = waitingSink ?: return
            instance?.waiting()?.forEach(s)
        }

        private val _guidance = MutableStateFlow<PhoneGuidance?>(null)
        /** Turn-by-turn from a navigation app running on the phone (Google Maps, Waze…). */
        val guidance: StateFlow<PhoneGuidance?> = _guidance

        /** Whether the user gave AARE notification access (the listener may still be unbound). */
        fun accessGranted(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        /**
         * Access is granted but Android hasn't bound the listener (it can stay unbound after an
         * update): ask for it again. Harmless when it's already connected.
         */
        fun rebind(context: Context) {
            if (instance == null && accessGranted(context)) {
                runCatching { requestRebind(ComponentName(context, CarNotificationListener::class.java)) }
            }
        }

        /** Notification.EXTRA_CALL_TYPE (Android 12+: 1 incoming, 2 ongoing, 3 screening), 0 when not told. */
        private fun callType(n: Notification): Int = n.extras.getInt("android.callType", 0)
        private val ANSWER = Regex("(?i)annehm|answer|accept|rangehen|abheb")

        private val _waiting = MutableStateFlow<WaitingCall?>(null)
        /** A second call ringing while one is on, with the phone app's own answers to it. */
        val waiting: StateFlow<WaitingCall?> = _waiting

        private val _caller = MutableStateFlow<String?>(null)
        /** Who is calling or on the line, from the dialer's call notification. */
        val caller: StateFlow<String?> = _caller

        private val _changes = MutableStateFlow(0)
        /** Bumped whenever a notification is posted or removed: the cue to re-read [conversations]. */
        val changes: StateFlow<Int> = _changes
    }
}

/** A call ringing while another is on ([CarNotificationListener.waiting]): who, and the phone app's own answers. */
class WaitingCall(val key: String, val name: String, val actions: List<Action>) {
    class Action(val title: String, val intent: android.app.PendingIntent?)

    /** The phone app's answer whose words match [what] (and not [not]), if it has one. */
    fun find(what: Regex, not: Regex? = null): Action? = actions.firstOrNull { what.containsMatchIn(it.title) && (not == null || !not.containsMatchIn(it.title)) }

    companion object {
        val HOLD = Regex("(?i)halt|hold|park")
        val END_ANSWER = Regex("(?i)(beend|aufleg|end|hang).*(annehm|answer|accept)|(annehm|answer|accept).*(beend|aufleg|end)")
        val DECLINE = Regex("(?i)ablehn|decline|reject|abweis")
        val ANSWER = Regex("(?i)annehm|answer|accept")
    }
}

