package net.prototux.aare.music

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Random

/**
 * System I-145 (27.11.2026: "von über 400 Liedern spielt er immer wieder die gleiche Musik. Und das auf Strecken von einer
 * halben Stunde"): a list's songs each once before any comes again, over many short trips that each start a new queue.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36])
class ShuffleBagTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val songs = (1..420).map { "s$it" }

    @Before fun clean() { ctx.getSharedPreferences("music_shuffle", Context.MODE_PRIVATE).edit().clear().commit() }

    /** Trips of eight songs (half an hour), each a new queue as at every start of the car. */
    private fun trips(n: Int, list: List<String> = songs): List<String> {
        val heard = ArrayList<String>()
        repeat(n) {
            val q = ShuffleBag.queue(ctx, "pl1", list, Random(heard.size.toLong()))
            assertEquals("the queue holds the whole list", list.size, q.size)
            q.take(8).forEach { s -> ShuffleBag.heard(ctx, s); heard += s }
        }
        return heard
    }

    @Test fun eachSongOnceBeforeAnyAgain() {
        val heard = trips(52)                       // 416 songs, the list has 420
        assertEquals(heard.size, heard.toSet().size)
    }

    @Test fun theNextRoundBeginsWithoutTheLastHeard() {
        val heard = trips(60)                       // 480: past the end of the first round
        val first = heard.take(420)
        assertEquals("the first round has every song", songs.toSet(), first.toSet())
        // the songs heard at the end of the first round do not open the second
        val second = heard.drop(420)
        assertTrue(second.none { it in first.takeLast(40) })
    }

    @Test fun songsNewInTheListJoinTheRound() {
        trips(10)
        val more = songs + (421..430).map { "s$it" }
        val q = ShuffleBag.queue(ctx, "pl1", more, Random(1))
        val ahead = q.take(more.size - 80)
        assertTrue("new ones among those still to come", (421..430).map { "s$it" }.all { it in ahead })
    }

    @Test fun heardLastComeLastInTheNextMix() {
        val after = ShuffleBag.after(listOf("a", "b", "c", "d", "e"), listOf("e", "d"), Random(3))
        assertEquals(listOf("d", "e"), after.takeLast(2))
        assertEquals(setOf("a", "b", "c"), after.take(3).toSet())
    }
}
