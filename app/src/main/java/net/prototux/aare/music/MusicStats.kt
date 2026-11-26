package net.prototux.aare.music

import android.content.Context

/**
 * What Mate keeps of the music played (System L-105): its own count of plays and skips per title (Plex counts plays
 * too, but whether a skip reaches its skipCount is not sure - the shuffle uses these), titles played without a network
 * to be told to Plex later, and each song's bass share measured over its whole length.
 */
object MusicStats {
    private fun prefs(c: Context) = c.getSharedPreferences("music_stats", Context.MODE_PRIVATE)

    fun played(c: Context, key: String) { val p = prefs(c); p.edit().putInt("p:$key", p.getInt("p:$key", 0) + 1).apply() }
    fun skipped(c: Context, key: String) { val p = prefs(c); p.edit().putInt("s:$key", p.getInt("s:$key", 0) + 1).apply() }

    /** How often [key] was skipped of all its starts, 0..1; 0 while it was started fewer than three times. */
    fun skipRate(c: Context, key: String): Double {
        val p = prefs(c)
        val s = p.getInt("s:$key", 0); val n = s + p.getInt("p:$key", 0)
        return if (n < 3) 0.0 else s.toDouble() / n
    }

    /** A title played to its end without the server reachable: told later. */
    fun pending(c: Context, key: String) {
        val p = prefs(c)
        val set = p.getStringSet("pending", emptySet())!!.toMutableSet()
        if (set.size < 500) set += key + "|" + System.currentTimeMillis()
        p.edit().putStringSet("pending", set).apply()
    }

    /** Tells the server what was played offline; true when nothing is left. Off the main thread. */
    @JvmStatic
    fun flush(c: Context, scrobble: (String) -> Unit): Boolean {
        val p = prefs(c)
        val set = p.getStringSet("pending", emptySet())!!
        if (set.isEmpty()) return true
        val left = set.toMutableSet()
        for (e in set) {
            if (runCatching { scrobble(e.substringBefore('|')) }.isFailure) break
            left -= e
        }
        p.edit().putStringSet("pending", left).apply()
        if (left.size < set.size) net.prototux.aare.DiagLog.add("music: ${set.size - left.size} titles played offline told to Plex", area = "AUDIO")
        return left.isEmpty()
    }

    /** The bass share of a song measured over its whole length (dB below its whole power); NaN when not known yet. */
    @JvmStatic
    fun bass(c: Context, key: String): Double = prefs(c).getFloat("b:$key", Float.NaN).toDouble()

    @JvmStatic
    fun bass(c: Context, key: String, shareDb: Double) { prefs(c).edit().putFloat("b:$key", shareDb.toFloat()).apply() }
}
