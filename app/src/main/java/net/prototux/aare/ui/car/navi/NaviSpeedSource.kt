package net.prototux.aare.ui.car.navi

/**
 * Which speed the speed tile shows (22.11.2026: "dass ein Navigationsgerät auch die echte GPS-Geschwindigkeit anzeigen
 * sollte … wenn das Fahrzeug genügend GPS-Satelliten hat … Wenn wir keine GPS-Satelliten haben, zum Beispiel in Tunneln,
 * dann nehmen wir die Fahrzeuggeschwindigkeit … mit einem kleinen Mini-Satellitensymbol oder mit einem kleinen
 * Mini-Tacho-Symbol"): the GPS's while the car's fix is fresh and good, else the car's speedometer.
 */
internal object NaviSpeedSource {
    enum class From { GPS, CAR }

    /** Satellites in use for a fix the speed can be trusted from. */
    const val MIN_SATELLITES = 4
    /** Without a satellite count from the car: the fix's accuracy instead (metres). */
    const val MAX_ACCURACY_M = 25f
    const val FIX_FRESH_MS = 3_000L
    const val SATS_FRESH_MS = 5_000L

    /**
     * [gpsMs] the GPS speed (m/s) of the car's last fix, [fixAgeMs] its age, [sats]/[satsAgeMs] the satellites in use the
     * car reported (null when it reports none), [accuracyM] the fix's accuracy (null when unknown), [carMs] the car's own
     * speed (m/s, null when not fresh). Null when neither is known.
     */
    fun pick(gpsMs: Double?, fixAgeMs: Long, sats: Int?, satsAgeMs: Long, accuracyM: Float?, carMs: Double?): Pair<Double, From>? {
        val fixGood = gpsMs != null && fixAgeMs <= FIX_FRESH_MS && when {
            sats != null && satsAgeMs <= SATS_FRESH_MS -> sats >= MIN_SATELLITES
            accuracyM != null -> accuracyM in 0f..MAX_ACCURACY_M
            else -> carMs == null              // nothing to judge it by: the GPS only when there is nothing else
        }
        return when {
            fixGood -> gpsMs!! to From.GPS
            carMs != null -> carMs to From.CAR
            else -> null
        }
    }
}
