package net.prototux.aare.ui.car.navi

import android.content.Context

/**
 * The hit list of the energy model (Navi L-029 step D, E-030: "Nur Trefferliste, kein Puffer"): what the model missed on
 * the stretches driven with guidance on, kept on the device. From [MIN_SAMPLES] stretches it gives the miss per 100 km
 * ([missPer100]) for the ± of the tile; the plan gets no buffer from it (E-019).
 */
internal class ChargeBuffer(private val samples: List<Sample>) {
    /** A stretch driven: the percent the car used more than the model said (negative: less) over [km] km. */
    class Sample(val missedPercent: Double, val km: Double)

    /** Missed percent per 100 km, the mean and the spread of the samples, null with too few. */
    private val learned: Pair<Double, Double>? = samples.filter { it.km >= MIN_KM }.takeIf { it.size >= MIN_SAMPLES }?.let { s ->
        val per = s.map { it.missedPercent / it.km * 100.0 }
        val mean = per.average()
        mean to Math.sqrt(per.sumOf { (it - mean) * (it - mean) } / per.size)
    }

    /**
     * The hit list's verdict (Navi E-030: "Nur Trefferliste, kein Puffer"): how far the model was off per 100 km on the
     * stretches driven - the root of the mean square, so a model always too low and one scattering both ways count
     * alike - null with fewer than [MIN_SAMPLES]. It sets the ± of the tile (ChargeRolling.spreadAt), never the plan.
     */
    val missPer100: Double? = samples.filter { it.km >= MIN_KM }.takeIf { it.size >= MIN_SAMPLES }?.let { s ->
        Math.sqrt(s.map { it.missedPercent / it.km * 100.0 }.sumOf { it * it } / s.size)
    }

    /** For the log: the hit list - what the model missed on the stretches driven (no buffer comes of it, E-030). */
    fun describe(): String = learned?.let { (mean, spread) ->
        "hit list of %d stretches: the model missed %+.1f %%/100 km on average, spread %.1f (±%.1f %%/100 km on the tile)"
            .format(java.util.Locale.ROOT, samples.count { it.km >= MIN_KM }, mean, spread, missPer100 ?: 0.0) }
        ?: "hit list: ${samples.count { it.km >= MIN_KM }} of $MIN_SAMPLES stretches driven to learn from"

    companion object {
        const val MIN_SAMPLES = 3
        /** Shorter stretches say little about the model (the level's own steps of 1 %). */
        const val MIN_KM = 30.0
        private const val KEEP = 30

        private fun prefs(c: Context) = c.getSharedPreferences("charge_buffer", Context.MODE_PRIVATE)

        /** The buffer as learned on this device. */
        fun of(c: Context): ChargeBuffer = ChargeBuffer(runCatching {
            prefs(c).getString("samples", "").orEmpty().split(';').filter { it.isNotBlank() }.map { s ->
                val (p, k) = s.split(','); Sample(p.toDouble(), k.toDouble()) }
        }.getOrDefault(emptyList()))

        /** A stretch driven with guidance: [missedPercent] used more than the model said over [km]; the last [KEEP] kept. */
        fun note(c: Context, missedPercent: Double, km: Double) {
            if (km < MIN_KM || missedPercent.isNaN()) return
            val all = (of(c).samples + Sample(missedPercent, km)).takeLast(KEEP)
            prefs(c).edit().putString("samples", all.joinToString(";") { "%.2f,%.1f".format(java.util.Locale.ROOT, it.missedPercent, it.km) }).apply()
        }
    }
}
