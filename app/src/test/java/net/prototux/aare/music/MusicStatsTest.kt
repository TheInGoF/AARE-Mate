package net.prototux.aare.music

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** System L-105: plays and skips counted per title, the bass of a song kept. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36])
class MusicStatsTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test fun skipRateOnlyAfterSomeStarts() {
        MusicStats.skipped(ctx, "a"); MusicStats.skipped(ctx, "a")
        assertEquals("two starts say nothing yet", 0.0, MusicStats.skipRate(ctx, "a"), 0.0)
        MusicStats.played(ctx, "a"); MusicStats.skipped(ctx, "a")
        assertEquals(0.75, MusicStats.skipRate(ctx, "a"), 0.001)
        assertEquals(0.0, MusicStats.skipRate(ctx, "never"), 0.0)
    }

    @Test fun bassKeptPerSong() {
        assertTrue(MusicStats.bass(ctx, "s").isNaN())
        MusicStats.bass(ctx, "s", -8.5)
        assertEquals(-8.5, MusicStats.bass(ctx, "s"), 0.01)
    }
}
