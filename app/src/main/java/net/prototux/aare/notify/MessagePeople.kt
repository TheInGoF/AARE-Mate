package net.prototux.aare.notify

import android.graphics.Bitmap

/**
 * The people of the car's message area (System L-057, 19.11.2026: "Die Messages-Kachel sollte die Chats aus allen Apps
 * anzeigen. Also nicht die Chats selber, sondern die Konversationen, die Personen, keine Inhalte. Erstens können wir so
 * überhaupt erst überprüfen, ob mal Nachrichten ankommen"): every conversation the phone shows now, of any messaging app,
 * and those that came in during the drive and are gone from the phone since - newest first, each person once. Only who,
 * from which app and when; never what was written.
 */
internal object MessagePeople {
    /**
     * A person: [conversation] the phone's conversation (answer, read aloud, mark read through it), else [replyTo] /
     * [actions] of a message from the drive.
     */
    class Person(val key: String, val name: String, val app: String, val icon: Bitmap?, val timeMs: Long,
                 val conversation: Conversation? = null, val replyTo: ((String) -> Boolean)? = null,
                 val actions: List<Pair<String, () -> Unit>> = emptyList()) {
        val canReply get() = conversation?.canReply == true || replyTo != null
    }

    /** A message of the drive as it is kept: title ("Group · Sender" or the person), when, how to answer. */
    class Seen(val key: String, val title: String, val icon: Bitmap?, val timeMs: Long, val reply: ((String) -> Boolean)?,
               val actions: List<Pair<String, () -> Unit>>)

    /** [conversations] of the phone and [seen] of the drive as people; [appName] names a package; at most [max]. */
    fun of(conversations: List<Conversation>, seen: List<Seen>, appName: (String) -> String, max: Int = MAX): List<Person> {
        val out = LinkedHashMap<String, Person>()
        conversations.sortedByDescending { it.timeMs }.forEach { c ->
            val name = c.title.ifBlank { appName(c.packageName) }
            val k = name.lowercase()
            if (k !in out) out[k] = Person(c.key, name, appName(c.packageName), c.icon, c.timeMs, conversation = c)
        }
        seen.sortedByDescending { it.timeMs }.forEach { n ->
            val name = n.title.substringBefore(" · ").ifBlank { n.title }
            val k = name.lowercase()
            if (k !in out) out[k] = Person(n.key, name, "", n.icon, n.timeMs, replyTo = n.reply, actions = n.actions)
        }
        return out.values.sortedByDescending { it.timeMs }.take(max)
    }

    const val MAX = 24
}
