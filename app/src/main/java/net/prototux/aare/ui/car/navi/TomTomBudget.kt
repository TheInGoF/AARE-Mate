package net.prototux.aare.ui.car.navi

import android.content.Context
import androidx.core.content.edit
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * The day's budget of TomTom calls (17.11.2026: "Wir müssen auch die TomTom Daten begrenzen, dass wir nicht über die 2500
 * Aufrufe pro Tag kommen"). Every request to the gate's TomTom paths (/verkehr/traffic/...) that goes out to the net is
 * counted per calendar day; from [LIMIT] on (a reserve to TomTom's 2500) none goes out until midnight - the map shows
 * no traffic then and the planning does without it. Answers are kept [KEEP_S] s (the HTTP cache), so the same tile or
 * question does not go out twice.
 */
internal object TomTomBudget {
    const val LIMIT = 2000
    private const val KEEP_S = 180
    private const val PATH = "/verkehr/traffic/"

    private fun prefs(c: Context) = c.getSharedPreferences("tomtom_budget", Context.MODE_PRIVATE)
    private fun today() = java.time.LocalDate.now().toString()

    /** The calls gone out today. */
    fun usedToday(c: Context): Int = prefs(c).let { if (it.getString("day", "") == today()) it.getInt("count", 0) else 0 }
    fun exhausted(c: Context) = usedToday(c) >= LIMIT

    @Synchronized private fun count(c: Context) {
        val p = prefs(c); val d = today()
        val n = if (p.getString("day", "") == d) p.getInt("count", 0) + 1 else 1
        p.edit { putString("day", d); putInt("count", n) }
        if (n == LIMIT) android.util.Log.w("MateNavi", "TomTom: day's budget of $LIMIT calls used up - no traffic until midnight")
    }

    /** Before the cache: over the budget, a TomTom request is not answered from the net (a cached answer still is). */
    fun gate(c: Context) = Interceptor { chain ->
        val r = chain.request()
        if (PATH !in r.url.encodedPath || !exhausted(c)) chain.proceed(r)
        else chain.proceed(r.newBuilder().cacheControl(okhttp3.CacheControl.FORCE_CACHE).build())  // 504 without a cached answer
    }

    /** On the network: counts what really goes out, and keeps TomTom's answers [KEEP_S] s. */
    fun counter(c: Context) = Interceptor { chain ->
        val r = chain.request()
        if (PATH !in r.url.encodedPath) return@Interceptor chain.proceed(r)
        count(c)
        chain.proceed(r).newBuilder().removeHeader("Pragma").header("Cache-Control", "public, max-age=$KEEP_S").build()
    }
}
