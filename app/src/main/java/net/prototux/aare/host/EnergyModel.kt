package net.prototux.aare.host

import android.content.Context
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * The "vehicle energy model" Google Maps plans charging stops from (O107). Maps learns nothing by itself: on
 * Android Auto the car hands it this model as sensor 23 - battery (level, capacity), how much power driving
 * takes at which speed, the charging curve and how long charging takes. It is a protobuf message without a
 * public description; the layout is read from Maps (its `VehicleEnergyModel`) and from a model a real car sent
 * (docs/maps-projection.md). This one is computed for a VW ID.7 from the car's physics.
 *
 * Changing with the car: the level (IDmate's state of charge) and the consumption - the speed curve is scaled
 * so that it meets IDmate's average over the last trips, which is how the model "learns" the driver.
 */
internal object EnergyModel {
    /** What the model is made from. */
    data class Car(
        /** Usable battery in Wh (ID.7 Pro 77 kWh, Pro S / GTX 86 kWh). */
        val capacityWh: Int,
        /** State of charge in percent. */
        val socPercent: Double,
        /** Average consumption in kWh/100 km from the car's trips, or null: the plain physics then. */
        val consumptionKwh100: Double? = null,
        /** Outside temperature in degrees Celsius, for heating and cooling. */
        val outsideCelsius: Float = 15f,
        /** The GTX: two motors, heavier, a little less efficient. */
        val gtx: Boolean = false,
        /**
         * IDmate's own model of this car, when its server serves one: kWh/100 km (heating included) by the speed
         * actually driven, km/h rising. It takes the place of the physics below and of the heating table.
         */
        val curve: List<Pair<Double, Double>>? = null,
        /** What the last hours really took against [curve] at the speed driven then (1 = as the model says). */
        val recentFactor: Double = 1.0,
    ) {
        val massKg get() = if (gtx) 2450.0 else 2300.0
        val efficiency get() = if (gtx) 0.85 else 0.88
    }

    // VW ID.7: rolling resistance, drag area (cd 0.23 x 2.46 m2); mass with driver and drivetrain efficiency by variant
    private const val ROLLING = 0.008
    private const val DRAG_AREA = 0.566
    private const val AIR_DENSITY = 1.225
    /** Electronics while driving, without heating and cooling (those are in the temperature table). */
    private const val BASE_W = 400.0
    /** The speed the physics is compared to the trips' average at: a mix of town, road and motorway. */
    private const val MIX_KMH = 95.0
    private const val MIX_AUX_W = 1000.0

    /** kWh/100 km of [curve] at [kmh]: between its points in a line, beyond them along speed squared. */
    fun curveKwh100(curve: List<Pair<Double, Double>>, kmh: Double): Double {
        if (kmh <= curve.first().first) return curve.first().second
        val i = curve.indexOfLast { it.first <= kmh }.coerceAtMost(curve.size - 2)
        val (v0, c0) = curve[i]
        val (v1, c1) = curve[i + 1]
        return if (kmh <= v1) c0 + (c1 - c0) * (kmh - v0) / (v1 - v0)
        else c0 + (c1 - c0) * (kmh * kmh - v0 * v0) / (v1 * v1 - v0 * v0)      // drag grows with the square
    }

    /** Power the car takes at [kmh] in W, as the model is told: IDmate's curve when there is one, else the physics. */
    fun powerW(kmh: Double, car: Car): Double {
        val curve = car.curve ?: return BASE_W + (drivingW(kmh, car) - BASE_W) * factor(car)
        // kWh/100 km x km/h x 10 = W; standing still the electronics remain
        return maxOf(BASE_W, curveKwh100(curve, kmh) * kmh * 10.0 * car.recentFactor)
    }

    /**
     * Power for Mate Navi's own planning at [kmh] in W (F3, as ABRP counts the weather): [powerW], and without IDmate's
     * curve (which holds the heating of now) the heating or cooling the outside temperature asks for beyond the little
     * that [factor] already counts. Google's model gets the heating as its own table instead.
     */
    fun tripW(kmh: Double, car: Car): Double =
        powerW(kmh, car) + if (car.curve != null) 0.0 else maxOf(0.0, climateW(car.outsideCelsius) - MIX_AUX_W)

    /**
     * What heating or cooling at [celsius] takes beyond what [tripW] counts for the temperature of now (Navi L-029: the
     * temperature along the way at the time the car gets there). IDmate's curve holds the heating of now, the physics
     * counts what lies above [MIX_AUX_W]; negative where it is milder than now.
     */
    fun climateDeltaW(celsius: Double, car: Car): Double =
        if (car.curve != null) climateW(celsius.toFloat()) - climateW(car.outsideCelsius)
        else maxOf(0.0, climateW(celsius.toFloat()) - MIX_AUX_W) - maxOf(0.0, climateW(car.outsideCelsius) - MIX_AUX_W)

    /**
     * What the car takes standing or creeping at [celsius] (Navi N-019 step 1, E-029: "Heizung steckt in kWh/100 km, läuft
     * aber nach Zeit"): the electronics and the heating or cooling, which run by the minute whatever the speed - in a jam
     * at -5 °C about 3 kW, not the 400 W of the electronics alone.
     */
    fun idleW(celsius: Double, car: Car): Double = BASE_W + climateW(celsius.toFloat())

    /** Extra power in W for a head wind of [headMs] (negative: from behind) at [kmh], from the ID.7's drag. */
    fun windW(kmh: Double, headMs: Double, car: Car): Double {
        val v = kmh / 3.6
        val air = v + headMs
        return 0.5 * AIR_DENSITY * DRAG_AREA * (air * Math.abs(air) - v * v) * v / car.efficiency
    }

    /** Heating and cooling in W by the outside temperature (the table Google's model gets), between its points. */
    fun climateW(celsius: Float): Double {
        val t = CLIMATE
        if (celsius <= t.first().first) return t.first().second.toDouble()
        if (celsius >= t.last().first) return t.last().second.toDouble()
        val i = t.indexOfLast { it.first <= celsius }
        val (t0, w0) = t[i]; val (t1, w1) = t[i + 1]
        return w0 + (w1 - w0) * ((celsius - t0) / (t1 - t0)).toDouble()
    }

    private val CLIMATE = listOf(-22.5f to 4600, -17.5f to 4400, -12.5f to 4000, -7.5f to 3500, -2.5f to 2900, 2.5f to 2300,
        7.5f to 1700, 12.5f to 1100, 17.5f to 700, 22.5f to 600, 27.5f to 900, 32.5f to 1500, 37.5f to 2200, 42.5f to 3000)

    /** Power at the wheels and for the electronics at [kmh], in W. */
    fun drivingW(kmh: Double, car: Car): Double {
        val v = kmh / 3.6
        return BASE_W + (car.massKg * 9.81 * ROLLING * v + 0.5 * AIR_DENSITY * DRAG_AREA * v * v * v) / car.efficiency
    }

    /**
     * How much the driver's real consumption lies above or below the physics. The trips' average holds heating,
     * short trips and the driver's foot; the physics is taken at a mixed speed with a little heating. Kept
     * within 0.85 .. 1.10, so that a winter of short trips does not spoil the motorway (O121) - until IDmate's
     * own model by speed and temperature takes its place.
     */
    fun factor(car: Car): Double {
        val real = car.consumptionKwh100 ?: return 1.0
        val model = (drivingW(MIX_KMH, car) + MIX_AUX_W) / MIX_KMH / 10.0      // W / (km/h) = Wh/km; /10 = kWh/100 km
        return (real / model).coerceIn(0.85, 1.10)
    }

    /**
     * Fast-charging power in W at a level in Wh, into the battery: the ID.7's curve with a warm battery as it is
     * measured at strong chargers (86 kWh: 200 kW up to a quarter, about 140 kW to 55 %, 93 kW at 80 %; the
     * smaller battery peaks at 175 kW). Until IDmate serves the car's own curve.
     */
    fun dcCurveW(levelWh: Double, car: Car): Double {
        val scale = if (car.capacityWh > 80_000) 1.0 else 0.875
        val x = (100.0 * levelWh / car.capacityWh).coerceIn(0.0, 100.0)
        if (x <= DC_CURVE.first().first) return DC_CURVE.first().second * scale
        val i = DC_CURVE.indexOfLast { it.first <= x }.coerceIn(0, DC_CURVE.size - 2)
        val (x0, y0) = DC_CURVE[i]
        val (x1, y1) = DC_CURVE[i + 1]
        return scale * (y0 + (y1 - y0) * (x - x0) / (x1 - x0))
    }

    /** Level in percent to W. */
    private val DC_CURVE = listOf(
        5.0 to 194_000.0, 10.0 to 197_000.0, 15.0 to 199_000.0, 20.0 to 200_000.0, 25.0 to 201_000.0, 30.0 to 159_000.0,
        35.0 to 139_000.0, 40.0 to 140_000.0, 45.0 to 141_000.0, 50.0 to 142_000.0, 55.0 to 144_000.0, 60.0 to 124_000.0,
        65.0 to 123_000.0, 70.0 to 113_000.0, 75.0 to 103_000.0, 80.0 to 93_000.0, 85.0 to 85_000.0, 90.0 to 76_000.0,
        95.0 to 61_000.0, 100.0 to 36_000.0,
    )

    /** Chargers the time tables are made for, in W: what the stations are listed with, fast ones and the wallbox. */
    private val CHARGERS = listOf(400_000, 350_000, 300_000, 250_000, 200_000, 150_000, 100_000, 50_000, 22_000, 11_000)
    /** What a charger delivers ends up in the battery by this much (losses, ramping up). */
    private const val CHARGE_ETA = 0.95
    /** Energy still missing to full, in percent of the battery, the tables have points at. */
    private val HEADROOM = (100 downTo 10 step 5).map { it.toDouble() } + (9 downTo 1).map { it.toDouble() } + 0.5

    /**
     * One charging time table (section 8.1) for a charger of [chargerW]: points of (Wh still missing to full,
     * seconds until full from there), the most missing first, none at zero - the way Google's server reads them
     * (the time of a stop from a to b percent is the difference of two points). Summed up from [dcCurveW] in
     * steps of an eighth of a percent.
     */
    fun chargeTimes(chargerW: Int, car: Car): List<Pair<Int, Int>> {
        val n = 800
        val step = car.capacityWh.toDouble() / n
        val until = DoubleArray(n + 1)                          // seconds from empty up to step i
        for (i in 0 until n) until[i + 1] = until[i] + 3600.0 * step / (minOf(chargerW.toDouble(), dcCurveW((i + 0.5) * step, car)) * CHARGE_ETA)
        return HEADROOM.map { h ->
            (car.capacityWh * h / 100.0).roundToInt() to (until[n] - until[(n * (1 - h / 100.0)).roundToInt()]).roundToInt()
        }
    }

    /** Seconds a stop from [from] to [to] percent takes by a table of [chargeTimes], as Google's server reads it. */
    fun stopSeconds(table: List<Pair<Int, Int>>, car: Car, from: Double, to: Double): Double {
        fun untilFull(missingWh: Double): Double {
            val pts = table + (0 to 0)
            if (missingWh >= pts.first().first) return pts.first().second.toDouble()
            val i = pts.indexOfFirst { it.first <= missingWh }.coerceAtLeast(1)
            val (x0, y0) = pts[i - 1]
            val (x1, y1) = pts[i]
            return y1 + (y0 - y1) * (missingWh - x1) / (x0 - x1).toDouble()
        }
        return untilFull(car.capacityWh * (1 - from / 100)) - untilFull(car.capacityWh * (1 - to / 100))
    }

    /**
     * The model as the bytes of sensor 23, laid out as Google's vehicle energy model v1.2 (VehicleEnergyModel:
     * 1 vehicle info, 2 road load force, 3 drivetrain losses, 4 max regeneration, 5 battery losses, 7 auxiliary
     * drain by temperature, 8 charging curves). Until 22.11.2026 sections 1.2, 2, 3, 4 and 5 were filled with
     * other meanings (a reserve, Wh/km and two numbers of a Ford sample, power by speed): Google's server then
     * took about twice the drag and extra losses and planned too many, too long stops (System I-079).
     *
     * The road load is fitted so that Google's sum - wheels, drivetrain and battery losses, auxiliary drain -
     * meets [powerW] at each speed (IDmate's curve when there is one, else the physics). [knownChargeTimes] is
     * the charging curves section of another car, taken as it is in place of the computed one (a file).
     */
    fun build(car: Car, knownChargeTimes: ByteArray? = null): ByteArray {
        val levelWh = (car.capacityWh * car.socPercent / 100.0).roundToInt().coerceIn(0, car.capacityWh)
        val road = roadLoad(car)
        return Pb().apply {
            msg(1) {                                            // vehicle info
                msg(2) { int(1, car.massKg.roundToInt()) }      // gross mass in kg
                msg(3) { int(1, levelWh) }                      // battery level now
                msg(4) { int(1, car.capacityWh) }               // usable capacity
                int(5, 2)                                       // HVAC on
                float(6, AIR_DENSITY.toFloat())
                float(7, car.outsideCelsius)
                int(9, if (car.gtx) 250_000 else 210_000)       // peak motor power
                int(10, if (car.capacityWh > 80_000) 200_000 else 175_000)  // maximum charging rate
                int(11, 1)                                      // heat pump
            }
            msg(2) {                                            // road load force in N: c0 + c1 v + c2 v^2 (v in m/s)
                msg(1) { float(1, road[0].toFloat()) }
                msg(2) { float(1, road[1].toFloat()) }
                msg(3) { float(1, road[2].toFloat()) }
            }
            msg(3) {                                            // drivetrain losses by speed and power at the wheels
                for (kmh in LOSS_SPEEDS) msg(7) {
                    float(1, (kmh / 3.6).toFloat())
                    for (w in LOSS_POWERS) msg(2) { sint(1, w); msg(2) { int(1, drivetrainLossW(w.toDouble()).roundToInt()) } }
                }
            }
            msg(4) {                                            // regeneration the battery takes, by level
                for (percent in 0..100 step 10) msg(1) {
                    val wh = (car.capacityWh * percent / 100.0).roundToInt()
                    if (wh > 0) int(1, wh)
                    int(2, (if (percent >= 95) 20_000 else if (percent >= 90) 60_000 else MAX_REGEN_W).toInt())
                }
            }
            msg(5) {                                            // battery losses by power at the terminals
                for (w in LOSS_POWERS) msg(4) { sint(1, w); msg(2) { int(1, batteryLossW(w.toDouble()).roundToInt()) } }
            }
            // auxiliary drain by outside temperature: the electronics, and heating and cooling unless IDmate's curve
            // (which holds the heating of now) is in the road load
            for ((t, w) in CLIMATE) {
                val total = (BASE_W + if (car.curve != null) 0.0 else w.toDouble()).roundToInt()
                msg(7) { float(1, t); msg(2) { int(1, if (car.curve != null) 0 else w) }; msg(4) { int(1, total) } }
            }
            // charging curves (8.1): for each charger power, ascending, points of (energy to full, time to full),
            // ascending in energy - the order the v1.2 description asks for
            if (knownChargeTimes != null) bytes(knownChargeTimes) else msg(8) {
                for (w in CHARGERS.sorted()) msg(1) {
                    int(1, w)
                    for ((wh, seconds) in chargeTimes(w, car).sortedBy { it.first }) msg(2) { int(1, wh); int(2, seconds) }
                }
            }
        }.bytes()
    }

    /** Power at the battery for driving at [kmh] as Google sums it up from [build]'s sections, in W (for tests). */
    fun googlePowerW(kmh: Double, car: Car): Double {
        val v = kmh / 3.6
        val r = roadLoad(car)
        val wheels = maxOf(0.0, (r[0] + r[1] * v + r[2] * v * v) * v)
        val terminals = wheels + drivetrainLossW(wheels)
        val aux = BASE_W + if (car.curve != null) 0.0 else climateW(car.outsideCelsius)
        return terminals + batteryLossW(terminals) + aux
    }

    /**
     * Road load coefficients (N, N per m/s, N per (m/s)^2) whose power at the wheels plus the losses meets
     * [powerW] from 20 to 160 km/h: least squares on the force, falling back to no linear part if a
     * coefficient would come out negative.
     */
    internal fun roadLoad(car: Car): DoubleArray {
        val aux = BASE_W                                            // [powerW] holds the electronics
        val pts = (20..160 step 5).map { kmh ->
            val v = kmh / 3.6
            val terminals = wheelsFromBattery(powerW(kmh.toDouble(), car) - aux)
            v to maxOf(0.0, terminals) / v
        }
        fun fit(withLinear: Boolean): DoubleArray {
            // normal equations for F = c0 + c1 v + c2 v^2 (or without c1)
            val cols = if (withLinear) 3 else 2
            val ata = Array(cols) { DoubleArray(cols) }
            val atb = DoubleArray(cols)
            for ((v, f) in pts) {
                val row = if (withLinear) doubleArrayOf(1.0, v, v * v) else doubleArrayOf(1.0, v * v)
                for (i in 0 until cols) { atb[i] += row[i] * f; for (j in 0 until cols) ata[i][j] += row[i] * row[j] }
            }
            val x = solve(ata, atb)
            return if (withLinear) x else doubleArrayOf(x[0], 0.0, x[1])
        }
        val full = fit(true)
        return if (full.all { it >= 0 }) full else fit(false).map { maxOf(0.0, it) }.toDoubleArray()
    }

    /** Power at the wheels for [batteryW] at the terminals of the battery... inverted from the loss models. */
    private fun wheelsFromBattery(driveW: Double): Double {
        // driveW = wheels + drivetrainLoss(wheels) + batteryLoss(terminals); solved by a few fixed-point steps
        var wheels = driveW
        repeat(20) {
            val terminals = wheels + drivetrainLossW(wheels)
            wheels -= (terminals + batteryLossW(terminals) - driveW) * 0.8
        }
        return wheels
    }

    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val m = Array(n) { i -> a[i].copyOf() + b[i] }
        for (c in 0 until n) {
            val p = (c until n).maxByOrNull { kotlin.math.abs(m[it][c]) }!!
            val t = m[c]; m[c] = m[p]; m[p] = t
            for (r in 0 until n) if (r != c) {
                val f = m[r][c] / m[c][c]
                for (k in c..n) m[r][k] -= f * m[c][k]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }

    /** Loss from the battery terminals to the wheels at [wheelsW] (negative = regenerating): motor and inverter. */
    internal fun drivetrainLossW(wheelsW: Double): Double = 250.0 + 0.07 * kotlin.math.abs(wheelsW)

    /** Loss inside the battery at [terminalsW]: internal resistance, about 2.5 % at 100 kW. */
    internal fun batteryLossW(terminalsW: Double): Double = 2.5e-7 * terminalsW * terminalsW

    private val LOSS_SPEEDS = listOf(0, 30, 60, 90, 120, 150, 180)
    private val LOSS_POWERS = listOf(-100_000, -50_000, -20_000, 0, 10_000, 20_000, 40_000, 60_000, 80_000, 120_000, 160_000, 200_000)
    private const val MAX_REGEN_W = 100_000.0

    /**
     * A model from a file instead (`files/ev_model.bin`: one a real car sent, the whole sensor message or the
     * model alone) with this car's level and capacity written into it; null when there is no such file.
     */
    fun fromFile(context: Context, car: Car): ByteArray? {
        val file = java.io.File(context.filesDir, "ev_model.bin").takeIf { it.isFile } ?: return null
        return runCatching {
            var top = Pb.parse(file.readBytes())
            top.firstOrNull { it.number == 23 }?.let { top = Pb.parse(it.bytes!!) }     // wrapped in a sensor batch
            val levelWh = (car.capacityWh * car.socPercent / 100.0).roundToInt()
            Pb().apply {
                for (f in top) {
                    if (f.number != 1) { raw(f); continue }
                    msg(1) {
                        for (b in Pb.parse(f.bytes!!)) when (b.number) {
                            3 -> msg(3) { int(1, levelWh) }
                            4 -> msg(4) { int(1, car.capacityWh) }
                            else -> raw(b)
                        }
                    }
                }
            }.bytes()
        }.getOrNull()
    }

    /** Just enough protobuf: writing messages, and reading one level of a message. */
    class Pb {
        class Field(val number: Int, val wire: Int, val value: Long, val bytes: ByteArray?)

        private val out = ByteArrayOutputStream()
        fun bytes(): ByteArray = out.toByteArray()

        private fun varint(v: Long) {
            var x = v
            while (x and 0x7f.inv().toLong() != 0L) { out.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }
            out.write(x.toInt())
        }
        private fun tag(number: Int, wire: Int) = varint(((number shl 3) or wire).toLong())
        fun int(number: Int, v: Int) { tag(number, 0); varint(v.toLong() and 0xffffffffL) }
        fun sint(number: Int, v: Int) { tag(number, 0); varint(((v shl 1) xor (v shr 31)).toLong() and 0xffffffffL) }
        fun float(number: Int, v: Float) {
            tag(number, 5)
            val b = v.toRawBits()
            for (i in 0..3) out.write(b ushr (8 * i) and 0xff)
        }
        fun msg(number: Int, body: Pb.() -> Unit) {
            val inner = Pb().apply(body).bytes()
            tag(number, 2); varint(inner.size.toLong()); out.write(inner)
        }
        /** A whole field as it was read elsewhere (tag included). */
        fun bytes(b: ByteArray) = out.write(b)
        fun raw(f: Field) {
            tag(f.number, f.wire)
            when (f.wire) {
                0 -> varint(f.value)
                2 -> { varint(f.bytes!!.size.toLong()); out.write(f.bytes) }
                else -> out.write(f.bytes!!)
            }
        }

        companion object {
            fun parse(b: ByteArray): List<Field> {
                val fields = ArrayList<Field>()
                var i = 0
                fun varint(): Long {
                    var r = 0L
                    var s = 0
                    while (true) { val c = b[i++].toInt(); r = r or ((c and 0x7f).toLong() shl s); s += 7; if (c and 0x80 == 0) return r }
                }
                while (i < b.size) {
                    val key = varint().toInt()
                    when (val wire = key and 7) {
                        0 -> fields += Field(key ushr 3, 0, varint(), null)
                        2 -> { val n = varint().toInt(); fields += Field(key ushr 3, 2, 0, b.copyOfRange(i, i + n)); i += n }
                        5 -> { fields += Field(key ushr 3, 5, 0, b.copyOfRange(i, i + 4)); i += 4 }
                        1 -> { fields += Field(key ushr 3, 1, 0, b.copyOfRange(i, i + 8)); i += 8 }
                        else -> throw IllegalArgumentException("wire type $wire")
                    }
                }
                return fields
            }
        }
    }
}
