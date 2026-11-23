package net.prototux.aare.notify

import android.app.Notification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 23.11.2026: Element X's private messages did not come through - plain chat notifications count as messages too. */
@RunWith(RobolectricTestRunner::class)
class PlainMessageTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun b() = NotificationCompat.Builder(ctx, "c").setSmallIcon(android.R.drawable.ic_dialog_info)

    @Test fun plainChatIsAMessage() {
        val n = b().setCategory(Notification.CATEGORY_MESSAGE).setContentTitle("Anna").setContentText("Bin gleich da").build()
        assertTrue(CarNotificationListener.looksLikeMessage(n))
        assertEquals("Anna" to "Bin gleich da", CarNotificationListener.plainParts(n))
    }

    @Test fun conversationIsAMessage() {
        val me = Person.Builder().setName("Ich").setKey("me").build()
        val n = b().setStyle(NotificationCompat.MessagingStyle(me).addMessage("Hallo", 1L, Person.Builder().setName("Anna").build())).build()
        assertTrue(CarNotificationListener.looksLikeMessage(n))
    }

    @Test fun otherNotificationsAreNot() {
        val n = b().setContentTitle("Update").setContentText("Fertig").build()
        assertFalse(CarNotificationListener.looksLikeMessage(n))
        assertNull(CarNotificationListener.plainParts(b().setCategory(Notification.CATEGORY_MESSAGE).setContentTitle("Nur Titel").build()))
    }
}
