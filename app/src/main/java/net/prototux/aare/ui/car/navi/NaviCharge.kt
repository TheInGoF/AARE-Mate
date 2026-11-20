package net.prototux.aare.ui.car.navi

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import net.prototux.aare.host.CarDataService
import net.prototux.aare.host.EnergyModel
import net.prototux.aare.ui.car.Mate
import uniffi.ferrostar.RouteStep
import kotlin.math.roundToInt

/**
 * The battery at the destination (wish O170 of 16.11.2026): above the speed, the state of charge expected on
 * arrival - from the car's level now and the energy model (IDmate's curve when its server serves one, else the
 * physics), step by step over what is left of the route at each step's own speed. Below [RESERVE_PERCENT] the trip
 * needs a charge on the way: a small "+" in a circle at the tile, like a battery's plus pole.
 */
internal object NaviCharge {
    const val RESERVE_PERCENT = 10.0

    /** [atCharger]: the level on arrival at the next planned charging stop (not at the destination). */
    class Arrival(val percent: Double, val needsCharge: Boolean, val atCharger: Boolean = false)

    /**
     * Energy in Wh for the first [untilM] metres of [steps] (the first step only from [firstLeftM] on) - the way to a
     * charging stop ahead.
     */
    fun energyWhUntil(steps: List<RouteStep>, firstLeftM: Double?, untilM: Double, car: EnergyModel.Car): Double =
        partsUntil(steps.map { it.distance to it.duration }, firstLeftM, untilM).sumOf { (kmh, s) -> EnergyModel.tripW(kmh, car) * s / 3600.0 }

    /**
     * The pieces of the first [untilM] metres of [steps] (each its metres and seconds; the first only from [firstLeftM]
     * on): each piece's speed (km/h) and the seconds it takes at it.
     */
    fun partsUntil(steps: List<Pair<Double, Double>>, firstLeftM: Double?, untilM: Double): List<Pair<Double, Double>> {
        var left = untilM
        val out = ArrayList<Pair<Double, Double>>()
        for ((i, st) in steps.withIndex()) {
            if (left <= 0) break
            val (dist, dur) = st
            if (dist <= 0 || dur <= 0) continue
            val stepM = if (i == 0 && firstLeftM != null) firstLeftM.coerceIn(0.0, dist) else dist
            val m = minOf(stepM, left)
            out += (dist / dur * 3.6) to (dur * m / dist)
            left -= m
        }
        return out
    }

    /**
     * The level on arrival at the next charging stop, [aheadM] metres on (19.11.2026: "Ich brauche nicht das Endziel
     * angezeigt bekommen in dem kleinen Buttonfeld, sondern mit wie viel Prozent ich an der Ladestation ankomme" - the tile
     * said 31 % at home where the plan said 20 % at the IONITY); null when the car's level is not known.
     */
    fun arrivalAtCharger(context: Context, steps: List<RouteStep>, firstLeftM: Double?, aheadM: Double): Arrival? {
        val car = CarDataService.car(context) ?: return null
        val p = car.socPercent - energyWhUntil(steps, firstLeftM, aheadM, car) / car.capacityWh * 100.0
        return Arrival(p, p < CHARGER_WARN_PERCENT, atCharger = true)
    }
    /** Below this at the charger the tile turns to the warning colour. */
    const val CHARGER_WARN_PERCENT = 5.0

    /** Energy in Wh for [steps] (the first one only from [firstLeftM] on). */
    fun energyWh(steps: List<RouteStep>, firstLeftM: Double?, car: EnergyModel.Car): Double = steps.withIndex().sumOf { (i, s) ->
        if (s.distance <= 0 || s.duration <= 0) 0.0 else {
            val share = if (i == 0 && firstLeftM != null) (firstLeftM / s.distance).coerceIn(0.0, 1.0) else 1.0
            val kmh = s.distance / s.duration * 3.6
            EnergyModel.tripW(kmh, car) * (s.duration * share) / 3600.0
        }
    }

    /** The level on arrival, or null when the car's level is not known. */
    fun arrival(context: Context, steps: List<RouteStep>, firstLeftM: Double?, chargedPercent: Double = 0.0): Arrival? {
        val car = CarDataService.car(context) ?: return null
        val wh = energyWh(steps, firstLeftM, car)
        // what the planned stops ahead put in (their leaving less their arriving level)
        val p = car.socPercent - wh / car.capacityWh * 100.0 + chargedPercent
        return Arrival(p, p < RESERVE_PERCENT)
    }
}

/** The tile above the speed: the level on arrival, a plus pole when a charge is needed on the way. */
@Composable
internal fun NaviChargeTile(a: NaviCharge.Arrival?, modifier: Modifier) {
    Box(modifier) {
        Column(
            Modifier.widthIn(min = 96.dp).floatEdge(RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))
                .background(Mate.cardSolid).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val p = a?.percent
            Text(p?.let { "${it.coerceAtLeast(0.0).roundToInt()} %" } ?: "–", style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold, color = if (a?.needsCharge == true) Mate.warn else Mate.text)
            Text(stringResource(if (a?.atCharger == true) R.string.mate_navi_charge_at_charger else R.string.mate_navi_charge_at_destination),
                style = MaterialTheme.typography.labelSmall, color = Mate.textDim)
        }
        // the plus pole: a charge needed that is not planned - not for a stop that is (it is the charge)
        if (a?.needsCharge == true && !a.atCharger) PlusPole(Modifier.align(Alignment.TopEnd).offset(10.dp, (-10).dp).size(26.dp))
    }
}

/** A "+" in a circle, like a battery's plus pole. */
@Composable
internal fun PlusPole(modifier: Modifier) {
    val ring = Mate.warn
    val face = Mate.cardSolid
    Canvas(modifier) {
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(face, r, c)
        drawCircle(ring, r - r * 0.09f, c, style = Stroke(r * 0.18f))
        val a = r * 0.48f
        drawLine(ring, Offset(c.x - a, c.y), Offset(c.x + a, c.y), r * 0.2f, StrokeCap.Round)
        drawLine(ring, Offset(c.x, c.y - a), Offset(c.x, c.y + a), r * 0.2f, StrokeCap.Round)
    }
}
