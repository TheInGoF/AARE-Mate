package net.prototux.aare.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** System L-057: the people of the message area - every app's conversations and the drive's messages, each person once. */
class MessagePeopleTest {
    private fun conv(key: String, pkg: String, title: String, t: Long) =
        Conversation(key, pkg, title, listOf(Conversation.Line(title, "text", t)), null, t, null, null)
    private fun seen(key: String, title: String, t: Long, reply: Boolean = false) =
        MessagePeople.Seen(key, title, null, t, if (reply) ({ _: String -> true }) else null, emptyList())
    private val names = mapOf("com.whatsapp" to "WhatsApp", "org.telegram" to "Telegram", "com.signal" to "Signal")

    @Test fun allAppsNewestFirstEachOnce() {
        val p = MessagePeople.of(
            listOf(conv("1", "com.whatsapp", "Anna", 300), conv("2", "org.telegram", "Ben", 500), conv("3", "com.signal", "anna", 100)),
            listOf(seen("h1", "Familie · Carla", 400, reply = true), seen("h2", "Ben", 600)),
            { names[it] ?: it })
        // Ben from Telegram (the phone's conversation wins over the drive's), Carla's group, Anna once (newest)
        assertEquals(listOf("Ben", "Familie", "Anna"), p.map { it.name })
        assertEquals(listOf("Telegram", "", "WhatsApp"), p.map { it.app })
        assertTrue(p[1].canReply)
        // a conversation of the phone without its reply action cannot be answered
        assertTrue(!p[0].canReply)
    }

    @Test fun nameMissingTheApp() {
        val p = MessagePeople.of(listOf(conv("1", "com.whatsapp", "", 1)), emptyList(), { names[it] ?: it })
        assertEquals("WhatsApp", p.single().name)
    }

    @Test fun atMost() {
        val p = MessagePeople.of((1..40).map { conv("$it", "com.whatsapp", "P$it", it.toLong()) }, emptyList(), { it }, max = 24)
        assertEquals(24, p.size); assertEquals("P40", p.first().name)
    }
}
