package net.prototux.aare.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.speech.RecognitionService
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Q-064: Android's recognition as a choice - never Mate's own service, Google's first, Whisper when there is none. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36])
class GoogleAsrTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private fun recognizer(pkg: String, cls: String) {
        val c = ComponentName(pkg, cls)
        shadowOf(ctx.packageManager).apply {
            addServiceIfNotPresent(c)
            addIntentFilterForService(c, IntentFilter(RecognitionService.SERVICE_INTERFACE))
        }
    }

    @Test fun withoutARecognizerWhisperStays() {
        net.prototux.aare.Settings(ctx).asrEngine = GoogleAsr.SYSTEM
        assertFalse(GoogleAsr.usable(ctx))
        assertFalse(GoogleAsr.chosen(ctx))
    }

    @Test fun googleFirstAndNeverMatesOwn() {
        recognizer(ctx.packageName, "net.prototux.aare.speech.MateRecognitionService")
        recognizer("org.other.voice", "org.other.voice.Service")
        recognizer("com.google.android.tts", "com.google.android.tts.Recognizer")
        assertEquals(listOf("com.google.android.tts", "org.other.voice"), GoogleAsr.services(ctx).map { it.packageName })
        assertTrue(GoogleAsr.usable(ctx))
        net.prototux.aare.Settings(ctx).asrEngine = GoogleAsr.SYSTEM
        assertTrue(GoogleAsr.chosen(ctx))
        net.prototux.aare.Settings(ctx).asrEngine = GoogleAsr.WHISPER
        assertFalse(GoogleAsr.chosen(ctx))
    }
}
