package net.prototux.aare.music

import android.content.Context
import org.json.JSONArray

/**
 * A round through a list's songs, each once before any comes again (System I-145, 27.11.2026: "von über 400 Liedern spielt
 * er immer wieder die gleiche Musik. Und das auf Strecken von einer halben Stunde"). Before, every start of the car mixed
 * the list anew - a trip of half an hour plays eight songs, and over many short trips the same ones came again; a mix made
 * before the net was up held only the songs already on the tablet for the whole trip. Now the order of a round is kept on
 * the device: what was heard (played or skipped) is out of it until all have come, a new round begins with what was heard
 * last at its end, songs new in the list join the rest of the round at random. Online and offline the same round - offline
 * only the songs on the tablet are played of it, the others stay for later.
 */
object ShuffleBag {
    private const val PREFS = "music_shuffle"
    /** The songs heard last, kept to the end of a new round. */
    private const val RECENT = 40

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The order of [list]'s [songs] (keys) still to come in this round, the round begun anew when all have come. */
    @Synchronized fun order(c: Context, list: String, songs: List<String>, rnd: java.util.Random = java.security.SecureRandom()): List<String> {
        val p = prefs(c)
        val bag = strings(p.getString("b:$list", null))
        val heard = strings(p.getString("h:$list", null)).toSet()
        val recent = strings(p.getString("recent", null))
        val r = next(bag, heard, songs, recent, rnd)
        p.edit().putString("b:$list", JSONArray(r.bag).toString()).putString("h:$list", JSONArray(r.heard.toList()).toString()).apply()
        return r.bag
    }

    /**
     * The whole of [list]'s [songs] in the order to play: the round's songs still to come first, then the ones heard in it,
     * mixed, those heard last at the end - so a queue never runs dry and goes on into the next round as it would begin.
     */
    @Synchronized fun queue(c: Context, list: String, songs: List<String>, rnd: java.util.Random = java.security.SecureRandom()): List<String> {
        val ahead = order(c, list, songs, rnd)
        return ahead + after(songs.filter { it !in ahead.toHashSet() }.distinct(), strings(prefs(c).getString("recent", null)), rnd)
    }

    /** [heard] mixed, those of [recent] (newest first) at the end, the newest last. Pure, for the tests. */
    fun after(heard: List<String>, recent: List<String>, rnd: java.util.Random): List<String> {
        val late = recent.filter { it in heard }.distinct().reversed()
        val lateSet = late.toHashSet()
        return heard.filter { it !in lateSet }.toMutableList().also { java.util.Collections.shuffle(it, rnd) } + late
    }

    /** [song] was played or skipped: out of every round it is in, and among the last heard. */
    @Synchronized fun heard(c: Context, song: String) {
        val p = prefs(c)
        val e = p.edit()
        for ((k, v) in p.all) {
            if (!k.startsWith("b:") || v !is String) continue
            val bag = strings(v)
            if (song !in bag) continue
            val list = k.substring(2)
            e.putString(k, JSONArray(bag - song).toString())
            e.putString("h:$list", JSONArray((strings(p.getString("h:$list", null)) + song).distinct()).toString())
        }
        e.putString("recent", JSONArray((listOf(song) + (strings(p.getString("recent", null)) - song)).take(RECENT)).toString())
        e.apply()
    }

    /** A round as it stands: [bag] still to come in order, [heard] already come. */
    class Round(val bag: List<String>, val heard: Set<String>)

    /**
     * The round for [songs]: [bag] without songs no longer in the list; songs neither in it nor [heard] (new in the list) put
     * in at random places; none left - a new round, all mixed, those [recent]ly heard to its end. Pure, for the tests.
     */
    fun next(bag: List<String>, heard: Set<String>, songs: List<String>, recent: List<String>, rnd: java.util.Random): Round {
        val all = songs.toSet()
        val left = bag.filter { it in all }.toMutableList()
        val inLeft = left.toHashSet()
        val heardNow = heard.filter { it in all }.toHashSet()
        val fresh = songs.filter { it !in inLeft && it !in heardNow }.distinct()
        for (s in fresh) left.add(rnd.nextInt(left.size + 1), s)
        if (left.isNotEmpty()) return Round(left, heardNow)
        // a new round: all mixed, what was heard last at its very end (the newest last) - unless that is the whole list
        val mixed = songs.distinct().toMutableList().also { java.util.Collections.shuffle(it, rnd) }
        val late = recent.filter { it in all }.distinct().reversed()
        if (late.size >= mixed.size) return Round(mixed, emptySet())
        val lateSet = late.toHashSet()
        return Round(mixed.filter { it !in lateSet } + late, emptySet())
    }

    private fun strings(json: String?): List<String> = if (json.isNullOrEmpty()) emptyList() else runCatching {
        val a = JSONArray(json); (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())
}
