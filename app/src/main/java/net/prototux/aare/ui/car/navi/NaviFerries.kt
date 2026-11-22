package net.prototux.aare.ui.car.navi

import okhttp3.Interceptor
import org.json.JSONObject

/**
 * The ferries on a route (System L-084, as IDmate counts them: wind_range.route_profile, range_along_route - "on the
 * ferry: distance without energy"): Valhalla marks each step a ferry takes with mode "ferry", Ferrostar's route leaves
 * that out. So the answers of Valhalla's /route are read on their way to Ferrostar ([interceptor]) and kept per route
 * (by its length), and the planner asks [steps] which steps of a route are on a ferry - their kilometres cost no energy.
 */
internal object NaviFerries {
    private const val MAX_BODY = 16L * 1024 * 1024
    private val seen = object : LinkedHashMap<Long, BooleanArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, BooleanArray>?) = size > 48
    }

    private fun key(distanceM: Double) = Math.round(distanceM * 10)
    /** The ferry steps seen, by their length, time and name - the same in Ferrostar's step as in Valhalla's. */
    private val ferrySteps = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 512
    }
    private fun stepKey(distanceM: Double, seconds: Double, name: String?) =
        "${Math.round(distanceM * 10)}|${Math.round(seconds * 10)}|${name.orEmpty()}"

    /** Whether a step of [distanceM] and [seconds] named [name] is one Valhalla said a ferry takes. */
    fun isFerry(distanceM: Double, seconds: Double, name: String?): Boolean =
        synchronized(seen) { ferrySteps[stepKey(distanceM, seconds, name)] == true }

    fun isFerry(step: uniffi.ferrostar.RouteStep) = isFerry(step.distance, step.duration, step.roadName)

    /** The steps on a ferry of each route in a Valhalla answer (OSRM format), kept when a route has any. */
    fun note(body: String) {
        val routes = JSONObject(body).optJSONArray("routes") ?: return
        for (r in 0 until routes.length()) {
            val route = routes.optJSONObject(r) ?: continue
            val flags = ArrayList<Boolean>()
            val legs = route.optJSONArray("legs") ?: continue
            for (l in 0 until legs.length()) {
                val steps = legs.optJSONObject(l)?.optJSONArray("steps") ?: continue
                for (s in 0 until steps.length()) {
                    val st = steps.optJSONObject(s)
                    val ferry = st?.optString("mode") == "ferry"
                    flags += ferry
                    if (ferry) synchronized(seen) {
                        ferrySteps[stepKey(st!!.optDouble("distance"), st.optDouble("duration"), st.optString("name").ifEmpty { null })] = true
                    }
                }
            }
            if (flags.any { it }) synchronized(seen) { seen[key(route.optDouble("distance"))] = flags.toBooleanArray() }
        }
    }

    /** Which of [stepCount] steps of a route of [distanceM] are on a ferry; null when none is known. */
    fun steps(distanceM: Double, stepCount: Int): BooleanArray? =
        synchronized(seen) { seen[key(distanceM)] }?.takeIf { it.size == stepCount }

    fun steps(route: uniffi.ferrostar.Route): BooleanArray? = steps(route.distance, route.steps.size)

    /** Reads Valhalla's route answers as they pass (the body stays for Ferrostar). */
    val interceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        if (response.isSuccessful && chain.request().url.encodedPath.endsWith("/route"))
            runCatching { note(response.peekBody(MAX_BODY).string()) }
        response
    }
}
