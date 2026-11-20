package net.prototux.aare.host

import aap_protobuf.service.sensorsource.message.SensorTypeOuterClass.SensorType
import android.location.Location
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.info.Accelerometer
import androidx.car.app.hardware.info.CarHardwareLocation
import androidx.car.app.hardware.info.Compass
import androidx.car.app.hardware.info.EnergyLevel
import androidx.car.app.hardware.info.EnergyProfile
import androidx.car.app.hardware.info.Gyroscope
import androidx.car.app.hardware.info.Mileage
import androidx.car.app.hardware.info.Model
import androidx.car.app.hardware.info.Speed
import androidx.car.app.hardware.info.TollCard
import net.prototux.aare.car.CarData

/**
 * The Car App Library's CarInfo and CarSensors results, built from [CarData].
 *
 * The library's contract: a result type we answer with isSupported = false becomes its own
 * "unimplemented" value in the app. So a whole result is unsupported when the car doesn't
 * advertise the sensor behind it; when it does but hasn't reported yet, we answer with
 * STATUS_UNAVAILABLE fields; fields AAP has no source for at all are STATUS_UNIMPLEMENTED.
 */
object CarHardwareResults {
    // ICarHardwareResultTypes (car-app-projected)
    const val MODEL = 1
    const val ENERGY_PROFILE = 2
    const val TOLL = 3
    const val ENERGY_LEVEL = 4
    const val SPEED = 5
    const val MILEAGE = 6
    const val EV_STATUS = 7
    const val ACCELEROMETER = 20
    const val COMPASS = 21
    const val GYROSCOPE = 22
    const val CAR_LOCATION = 23

    /** Result types that depend on each kind of sensor data, to push only what changed. */
    fun typesFor(changed: Set<CarData.Kind>): Set<Int> = buildSet {
        for (k in changed) when (k) {
            CarData.Kind.SPEED -> add(SPEED)
            CarData.Kind.ODOMETER -> add(MILEAGE)
            CarData.Kind.FUEL -> add(ENERGY_LEVEL)
            CarData.Kind.TOLL_CARD -> add(TOLL)
            CarData.Kind.ACCELEROMETER -> add(ACCELEROMETER)
            CarData.Kind.GYROSCOPE -> add(GYROSCOPE)
            CarData.Kind.COMPASS -> add(COMPASS)
            CarData.Kind.LOCATION -> add(CAR_LOCATION)
            else -> Unit
        }
    }

    /**
     * The result for [type], or null for "not supported". [location] and [heading] are the
     * runtime's car GPS and compass (null when the car hasn't sent them, as before: apps such as
     * CoMaps then keep the phone's own sensors).
     */
    fun build(type: Int, s: CarData.State, location: Location?, heading: Float?): Any? {
        if (!s.connected && type != CAR_LOCATION && type != COMPASS) return null
        return when (type) {
            MODEL -> s.identity?.let { id ->
                Model.Builder()
                    .setManufacturer(text(id.make))
                    .setName(text(id.model))
                    .setYear(id.year?.let { CarValue(it, now(), CarValue.STATUS_SUCCESS) } ?: unavailable())
                    .build()
            }
            // System L-001 / I-004 ("Better Route Planner bekommt den SoC nicht vom System"): a car that reports no fuel
            // at all gave ABRP and the other apps neither a profile nor a level - IDmate's stood in only beside a fuel
            // sensor. With a battery level from IDmate the car is an electric one with CCS2 (the ID.7)
            ENERGY_PROFILE -> if (!s.has(SensorType.SENSOR_FUEL) && s.fuelTypes.isEmpty()) idmateProfile() else EnergyProfile.Builder()
                .setFuelTypes(intList(s.fuelTypes))
                .setEvConnectorTypes(intList(s.evConnectorTypes))
                .build()
            ENERGY_LEVEL -> if (!s.has(SensorType.SENSOR_FUEL)) idmateEnergy() else energyLevel(s)
            SPEED -> if (!s.has(SensorType.SENSOR_SPEED)) null else Speed.Builder()
                .setRawSpeedMetersPerSecond(s.speed?.let { CarValue(it.value.metersPerSecond, it.atMillis, CarValue.STATUS_SUCCESS) } ?: unavailable())
                // The speed the dashboard shows (usually a little above the real one) and its unit aren't in AAP.
                .setDisplaySpeedMetersPerSecond(unimplemented())
                .setSpeedDisplayUnit(unimplemented())
                .build()
            MILEAGE -> if (!s.has(SensorType.SENSOR_ODOMETER)) null else Mileage.Builder()
                .setOdometerMeters(s.odometer?.let { CarValue(it.value.km * 1000f, it.atMillis, CarValue.STATUS_SUCCESS) } ?: unavailable())
                .setDistanceDisplayUnit(unimplemented())
                .build()
            TOLL -> if (!s.has(SensorType.SENSOR_TOLL_CARD)) null else TollCard.Builder()
                // AAP only says whether a card is inserted, not whether it is valid: present counts as valid.
                .setCardState(s.tollCardPresent?.let {
                    CarValue(if (it.value) TollCard.TOLLCARD_STATE_VALID else TollCard.TOLLCARD_STATE_NOT_INSERTED, it.atMillis, CarValue.STATUS_SUCCESS)
                } ?: unavailable())
                .build()
            // AAP has no charge-port data.
            EV_STATUS -> null
            ACCELEROMETER -> if (!s.has(SensorType.SENSOR_ACCELEROMETER_DATA)) null else Accelerometer(vector(s.accelerometer))
            GYROSCOPE -> if (!s.has(SensorType.SENSOR_GYROSCOPE_DATA)) null else Gyroscope(vector(s.gyroscope))
            COMPASS -> heading?.let { h ->
                // [azimuth, pitch, roll] in degrees; pitch and roll only when the car gives both.
                val c = s.compass?.value
                val values = if (c?.pitchDeg != null && c.rollDeg != null) listOf(h, c.pitchDeg, c.rollDeg) else listOf(h)
                Compass(CarValue(values, s.compass?.atMillis ?: now(), CarValue.STATUS_SUCCESS))
            }
            CAR_LOCATION -> location?.let { CarHardwareLocation(CarValue(it, now(), CarValue.STATUS_SUCCESS)) }
            else -> null
        }
    }

    private fun energyLevel(s: CarData.State): EnergyLevel {
        val f = s.fuel
        // The car reports no energy level: IDmate's state of charge stands in (server or stick, O102), as a battery
        if (f == null) idmateEnergy()?.let { return it }
        // An electric-only car reports its battery as "fuel".
        val electric = s.fuelTypes.isNotEmpty() && s.fuelTypes.all { it == EnergyProfile.FUEL_TYPE_ELECTRIC }
        val level: CarValue<Float> = f?.value?.levelPercent?.let { CarValue(it.toFloat(), f.atMillis, CarValue.STATUS_SUCCESS) } ?: unavailable()
        return EnergyLevel.Builder()
            .setFuelPercent(if (electric) unimplemented() else level)
            .setBatteryPercent(if (electric) level else unimplemented())
            .setEnergyIsLow(f?.value?.low?.let { CarValue(it, f.atMillis, CarValue.STATUS_SUCCESS) } ?: unavailable())
            .setRangeRemainingMeters(f?.value?.rangeKm?.let { CarValue(it * 1000f, f.atMillis, CarValue.STATUS_SUCCESS) } ?: unavailable())
            .setDistanceDisplayUnit(unimplemented())
            .setFuelVolumeDisplayUnit(unimplemented())
            .build()
    }

    /** An electric car with CCS2, when IDmate knows its battery level; null otherwise. */
    private fun idmateProfile(): EnergyProfile? {
        val ctx = runCatching { net.prototux.aare.AareApp.instance }.getOrNull() ?: return null
        net.prototux.aare.dash.DashData.soc(ctx) ?: return null
        return EnergyProfile.Builder()
            .setFuelTypes(CarValue(listOf(EnergyProfile.FUEL_TYPE_ELECTRIC), now(), CarValue.STATUS_SUCCESS))
            .setEvConnectorTypes(CarValue(listOf(EnergyProfile.EVCONNECTOR_TYPE_COMBO_2), now(), CarValue.STATUS_SUCCESS))
            .build()
    }

    /** When IDmate's battery level was taken (0 = none), to push it to subscribed apps when it changes. */
    fun idmateSocAt(): Long {
        val ctx = runCatching { net.prototux.aare.AareApp.instance }.getOrNull() ?: return 0
        return if (net.prototux.aare.dash.DashData.soc(ctx) == null) 0 else net.prototux.aare.dash.DashData.socAtMs(ctx)
    }

    /** The battery level and range from IDmate, when it has fresh ones; null otherwise. */
    private fun idmateEnergy(): EnergyLevel? {
        val ctx = runCatching { net.prototux.aare.AareApp.instance }.getOrNull() ?: return null
        val soc = net.prototux.aare.dash.DashData.soc(ctx) ?: return null
        val at = net.prototux.aare.dash.DashData.socAtMs(ctx).takeIf { it > 0 } ?: now()
        val range = net.prototux.aare.dash.DashData.rangeKm(ctx)
        return EnergyLevel.Builder()
            .setFuelPercent(unimplemented())
            .setBatteryPercent(CarValue(soc.toFloat(), at, CarValue.STATUS_SUCCESS))
            .setEnergyIsLow(CarValue(soc < 15.0, at, CarValue.STATUS_SUCCESS))
            .setRangeRemainingMeters(range?.let { CarValue((it * 1000).toFloat(), at, CarValue.STATUS_SUCCESS) } ?: unavailable())
            .setDistanceDisplayUnit(unimplemented())
            .setFuelVolumeDisplayUnit(unimplemented())
            .build()
    }

    /** [x, y, z]; an axis the car leaves out counts as 0. */
    private fun vector(v: CarData.Timed<CarData.Vector3>?): CarValue<List<Float>> =
        v?.let { CarValue(listOf(it.value.x ?: 0f, it.value.y ?: 0f, it.value.z ?: 0f), it.atMillis, CarValue.STATUS_SUCCESS) } ?: unavailable()

    private fun text(value: String): CarValue<String> =
        if (value.isNotBlank()) CarValue(value, now(), CarValue.STATUS_SUCCESS) else unavailable()

    private fun intList(values: List<Int>): CarValue<List<Int>> =
        if (values.isNotEmpty()) CarValue(values, now(), CarValue.STATUS_SUCCESS) else CarValue(emptyList(), now(), CarValue.STATUS_UNAVAILABLE)

    private fun <T> unavailable(): CarValue<T> = CarValue(null, now(), CarValue.STATUS_UNAVAILABLE)
    private fun <T> unimplemented(): CarValue<T> = CarValue(null, 0, CarValue.STATUS_UNIMPLEMENTED)
    private fun now() = System.currentTimeMillis()
}
