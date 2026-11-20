package net.prototux.aare.ui.car.navi

import net.prototux.aare.host.EnergyModel
import org.maplibre.spatialk.geojson.Position
import kotlin.math.atan2
import kotlin.math.cos

/**
 * The energy of each piece of a route, worked out on the device (Navi L-029, N-016 step 2: "das ist Rechnen, kein Raten"):
 * the speed of the piece through the car's model, the climb from the heights of the route, the temperature, the wind
 * against or behind the car and the rain where and when the car gets there. Without heights the road counts as flat,
 * without weather as today; each part is summed up for the log, so a plan says what it was made with.
 */
internal object ChargeEnergy {

    /** The weather at a place at a time: what is known of it, null where nothing is. */
    class Conditions(val celsius: Double? = null, val windFromDeg: Double? = null, val windMs: Double = 0.0, val rainMmH: Double = 0.0)

    /** Where the weather comes from ([ChargeWeather] or a test): the conditions at [at] reached [secondsAhead] from now. */
    fun interface Weather { fun at(at: Position, secondsAhead: Double): Conditions? }

    /** Heights along the route: the height in metres at a place, null where none is known. */
    fun interface Heights { fun at(p: Position): Double? }

    /** What each part added over the whole route, in Wh (for the log line of the plan). */
    class Parts {
        var drive = 0.0; var climb = 0.0; var wind = 0.0; var climate = 0.0; var rain = 0.0
        var weatherPieces = 0; var heightPieces = 0
        override fun toString() = "drive %.1f kWh, climb %+.1f, wind %+.1f, temperature %+.1f, rain %+.1f kWh".format(
            java.util.Locale.ROOT, drive / 1000, climb / 1000, wind / 1000, climate / 1000, rain / 1000)
    }

    /** Head wind in m/s for a car going [headingDeg] in a wind from [fromDeg] of [ms] at 10 m (ground level: [GROUND]). */
    fun headwindMs(headingDeg: Double, fromDeg: Double, ms: Double) = ms * GROUND * cos(Math.toRadians(fromDeg - headingDeg))

    /** The direction from [a] to [b] in degrees, 0 = north, 90 = east. */
    fun bearing(a: Position, b: Position): Double {
        val x = (b.longitude - a.longitude) * cos(Math.toRadians((a.latitude + b.latitude) / 2))
        val y = b.latitude - a.latitude
        return (Math.toDegrees(atan2(x, y)) + 360.0) % 360.0
    }

    /**
     * Wh for [metres] at [kmh] climbing [climbM] (down: negative), with [headMs] of head wind, at [celsius] (null: the
     * temperature of now) and [rainKwh100] of extra for rain. Climbing takes the car's weight up through the drive's
     * losses; down the car gets [REGEN] of it back.
     */
    fun pieceWh(kmh: Double, metres: Double, climbM: Double, headMs: Double, celsius: Double?, rainKwh100: Double,
                car: EnergyModel.Car, parts: Parts? = null): Double {
        val sec = metres / (kmh / 3.6)
        val drive = EnergyModel.tripW(kmh, car) * sec / 3600.0
        val lift = car.massKg * 9.81 * climbM / 3600.0
        val climb = if (climbM >= 0) lift / car.efficiency else lift * REGEN
        val wind = EnergyModel.windW(kmh, headMs, car) * sec / 3600.0
        val climate = celsius?.let { EnergyModel.climateDeltaW(it, car) * sec / 3600.0 } ?: 0.0
        val rain = rainKwh100 * metres / 100.0
        parts?.let { it.drive += drive; it.climb += climb; it.wind += wind; it.climate += climate; it.rain += rain }
        // never less than the electronics take, whatever the wind behind or the mild air give
        return maxOf(drive + wind + climate, EnergyModel.powerW(0.0, car) * sec / 3600.0) + climb + rain
    }

    /**
     * Extra kWh/100 km in rain from a forecast of [mmH] at [kmh] where IDmate has learned nothing (N-016: "Regen als
     * Aufschlag"): a wet road and spray take about [RAIN_LIGHT] of the drive, heavy rain [RAIN_HEAVY].
     */
    fun rainKwh100(mmH: Double, kmh: Double, car: EnergyModel.Car): Double {
        val share = when { mmH >= HEAVY_MM_H -> RAIN_HEAVY; mmH >= LIGHT_MM_H -> RAIN_LIGHT; else -> return 0.0 }
        return EnergyModel.powerW(kmh, car) / kmh / 10.0 * share
    }

    /**
     * The track of a route given as [points] with the speed of each piece ([kmh], one per point, the first unused):
     * metres, seconds and energy from the start. [learnedRain] is IDmate's rain at a place reached so many seconds from
     * now (null: not learned - the forecast's rain counts then).
     */
    fun track(points: List<Position>, kmh: DoubleArray, car: EnergyModel.Car, heights: Heights? = null, weather: Weather? = null,
              learnedRain: ((Position, Double) -> Double)? = null, parts: Parts = Parts()): ChargePlanner.Track {
        val n = points.size
        val m = DoubleArray(n); val s = DoubleArray(n); val e = DoubleArray(n)
        var lastH = points.firstOrNull()?.let { heights?.at(it) }
        // the weather changes over kilometres and the hour: asked every [WEATHER_M], not at every point
        var cond: Conditions? = null; var condAtM = -1e9
        for (i in 1 until n) {
            val d = ChargerIndex.distanceM(points[i - 1], points[i])
            m[i] = m[i - 1] + d
            val v = kmh[i].coerceIn(5.0, 200.0)
            s[i] = s[i - 1] + d / (v / 3.6)
            if (d <= 0.0) { e[i] = e[i - 1]; continue }
            val h = heights?.at(points[i])
            val climb = if (h != null && lastH != null) (h - lastH).also { parts.heightPieces++ } else 0.0
            if (h != null) lastH = h
            if (weather != null && m[i] - condAtM >= WEATHER_M) { cond = weather.at(points[i], s[i]); condAtM = m[i] }
            val c = cond
            if (c != null) parts.weatherPieces++
            val head = if (c?.windFromDeg != null) headwindMs(bearing(points[i - 1], points[i]), c.windFromDeg, c.windMs) else 0.0
            val rain = learnedRain?.invoke(points[i], s[i]) ?: c?.let { rainKwh100(it.rainMmH, v, car) } ?: 0.0
            e[i] = e[i - 1] + pieceWh(v, d, climb, head, c?.celsius, rain, car, parts)
        }
        return ChargePlanner.Track(points, m, s, e, parts)
    }

    /** Wind at the car's height against the 10 m of the forecast (open land, a motorway between trees and hills). */
    const val GROUND = 0.7
    /** What the car gets back going down (recuperation, drive and battery losses). */
    const val REGEN = 0.6
    private const val WEATHER_M = 2_000.0
    const val LIGHT_MM_H = 0.3
    const val HEAVY_MM_H = 2.5
    const val RAIN_LIGHT = 0.06
    const val RAIN_HEAVY = 0.12
}
