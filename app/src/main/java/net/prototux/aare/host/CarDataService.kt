package net.prototux.aare.host

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.provider.Settings
import android.util.Log
import net.prototux.aare.dash.DashData
import net.prototux.aare.host.projected.ProjectedProtocol
import net.prototux.aare.host.projected.SafeParcel

/**
 * Stand-in for the car service of the Android Auto app (O107). Google Maps takes the vehicle's data - electric
 * car, plug types, battery - not from the projection host but from `GearheadCarStartupService`. The ROM
 * (Build 11, switch "Elektroauto", `panel_auto_ev`) redirects that binding to the service of a registered host
 * with the action `panel.auto.action.CAR_DATA_SERVICE`: this one.
 *
 * The protocol is read from Maps and the Android Auto app (`IStartup` tx 13 hands out an `ICar`; from there
 * `CarInfo`, the connection listener, `ICarSensor`). Maps has two clients of it: one fills its `ProjectedCarInfo`
 * (fuel and plug types), the other - started only once the host starts Maps' navigation provider service, see
 * [net.prototux.aare.host.projected.NavigationProvider] - registers the sensors. Sensor 23 is the vehicle energy
 * model ([EnergyModel]) charging stops are planned from. Without the switch (`panel_auto_ev` 0) no car is
 * handed out; only Maps is answered.
 */
class CarDataService : Service() {
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent): IBinder {
        Log.i(TAG, "bind: panel_auto_ev=${if (ev()) 1 else 0}")
        seen(this, "bind")
        return startup
    }

    override fun onUnbind(intent: Intent): Boolean {
        Log.i(TAG, "unbind")
        synchronized(listeners) { listeners.clear() }
        main.removeCallbacks(refresh)
        return false
    }

    private fun ev() = Settings.Global.getInt(contentResolver, "panel_auto_ev", 0) == 1 &&
        net.prototux.aare.Settings(this).evModelSource != "off"

    /** Only Google Maps is told about the car (the service is exported for the ROM's redirection). */
    private fun allowed(): Boolean {
        val uid = Binder.getCallingUid()
        return uid == android.os.Process.myUid() || packageManager.getPackagesForUid(uid)?.any { it == MAPS } == true
    }

    /** `IStartup` of the Android Auto app: Maps asks it for the car (tx 13, answered with an `ICar`). */
    private val startup = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code > 0x00ffffff) return super.onTransact(code, data, reply, flags)
            reply?.writeNoException()
            if (code == 13) {
                val ok = ev() && allowed()
                seen(this@CarDataService, if (ok) "car" else "refused")
                reply?.writeStrongBinder(if (ok) car else null)
            }
            return true
        }
    }

    /** `ICar` as far as the car's data goes: description, "connected", display, sensors. The rest is answered empty. */
    private val car = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code > 0x00ffffff) return super.onTransact(code, data, reply, flags)
            note("car", code)
            when (code) {
                ProjectedProtocol.TX_CAR_GET_CAR_INFO -> { reply?.writeNoException(); reply?.writeInt(1); reply?.let { ProjectedProtocol.writeCarInfo(it) } }
                ProjectedProtocol.TX_CAR_GET_CAR_UI_INFO -> { reply?.writeNoException(); reply?.writeInt(1); reply?.let { ProjectedProtocol.writeCarUiInfo(it) } }
                3 -> { reply?.writeNoException(); reply?.writeInt(1) }          // isConnectedToCar
                4 -> { reply?.writeNoException(); reply?.writeInt(1) }          // kind of connection: by cable
                5 -> {                                                          // registerCarConnectionListener
                    data.enforceInterface(ProjectedProtocol.CAR)
                    val listener = data.readStrongBinder()
                    reply?.writeNoException()
                    if (listener != null) main.post {
                        oneWay(listener, 1, "com.google.android.gms.car.ICarConnectionListener") { it.writeInt(1) }
                    }
                }
                7 -> { reply?.writeNoException(); reply?.writeStrongBinder(sensors) }       // getCarSensorManager
                17 -> { reply?.writeNoException(); reply?.writeStrongBinder(messages) }     // getCarMessageManager
                ProjectedProtocol.TX_CAR_GET_DISPLAY_MANAGER -> { reply?.writeNoException(); reply?.writeStrongBinder(displayManager) }
                else -> reply?.writeNoException()
            }
            return true
        }
    }

    /** `ICarDisplayManager` and `ICarDisplay`: Maps reads the display's `CarUiInfo` before it registers sensors. */
    private val displayManager = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code > 0x00ffffff) return super.onTransact(code, data, reply, flags)
            reply?.writeNoException()
            if (code == ProjectedProtocol.TX_DISPLAY_MANAGER_GET_DISPLAY) reply?.writeStrongBinder(display)
            return true
        }
    }
    private val display = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code > 0x00ffffff) return super.onTransact(code, data, reply, flags)
            reply?.writeNoException()
            if (code == ProjectedProtocol.TX_DISPLAY_GET_CAR_UI_INFO) { reply?.writeInt(1); reply?.let { ProjectedProtocol.writeCarUiInfo(it) } }
            return true
        }
    }

    /** `ICarMessage`: Maps registers a client and asks it for the navigation focus, which the car grants at once. */
    private val messages = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code > 0x00ffffff) return super.onTransact(code, data, reply, flags)
            note("message", code)
            when (code) {
                // registerClient(callback, type): Maps (26.47) asks for the navigation focus through this client and
                // starts guidance only once it has it - answered empty ("false"), guidance with the navigation provider
                // started ended in "loading" (B4, System I-003)
                MSG_REGISTER -> { reply?.writeNoException(); reply?.writeInt(1) }
                // request(callback, type, 0, ...): the car grants it at once - callback tx 1 (type, 0, 1 = granted)
                MSG_REQUEST -> {
                    val callback = runCatching { data.enforceInterface(MESSAGE); data.readStrongBinder() }.getOrNull()
                    val type = runCatching { data.readInt() }.getOrDefault(1)
                    reply?.writeNoException()
                    if (callback != null) grantFocus(callback, type)
                }
                else -> reply?.writeNoException()
            }
            return true
        }
    }

    /** Tells Maps' message callback that it has the focus it asked for (type 1 = navigation). */
    private fun grantFocus(callback: IBinder, type: Int) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(MESSAGE_CALLBACK)
            data.writeInt(type)
            data.writeInt(0)
            data.writeInt(1)
            callback.transact(1, data, null, IBinder.FLAG_ONEWAY)
            Log.i(TAG, "focus $type granted")
        } catch (e: Exception) {
            Log.w(TAG, "focus $type: $e")
        } finally {
            data.recycle()
        }
    }

    /** Listeners for the energy model (`ICarSensorEventListener`). */
    private val listeners = ArrayList<IBinder>()
    private var sent: ByteArray? = null

    /** `ICarSensor`: the one sensor there is (23, the energy model), its last value, fuel and plug types. */
    private val sensors = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code > 0x00ffffff) return super.onTransact(code, data, reply, flags)
            note("sensor", code)
            when (code) {
                1 -> { reply?.writeNoException(); reply?.writeIntArray(if (model() != null) intArrayOf(SENSOR_ENERGY_MODEL) else IntArray(0)) }
                2 -> {                                                          // register: type, rate, listener
                    data.enforceInterface(SENSOR)
                    val type = data.readInt()
                    data.readInt()
                    val listener = data.readStrongBinder()
                    if (type == SENSOR_ENERGY_MODEL && listener != null) {
                        synchronized(listeners) { if (listener !in listeners) listeners += listener }
                        seen(this@CarDataService, "sensor")
                        main.removeCallbacks(refresh)
                        main.post(refresh)
                    }
                    reply?.writeNoException(); reply?.writeInt(if (type == SENSOR_ENERGY_MODEL) 1 else 0)
                }
                3 -> {                                                          // the latest value of a type
                    data.enforceInterface(SENSOR)
                    val bytes = if (data.readInt() == SENSOR_ENERGY_MODEL) model() else null
                    reply?.writeNoException()
                    if (bytes != null) { reply?.writeInt(1); reply?.let { writeEvent(it, bytes) } } else reply?.writeInt(0)
                }
                4 -> {                                                          // unregister: type, listener
                    data.enforceInterface(SENSOR)
                    data.readInt()
                    val listener = data.readStrongBinder()
                    synchronized(listeners) { listeners.remove(listener) }
                    reply?.writeNoException()
                }
                5 -> { reply?.writeNoException(); reply?.writeInt(0) }
                6 -> { reply?.writeNoException(); reply?.writeIntArray(intArrayOf(FUEL_ELECTRIC)) }
                7 -> { reply?.writeNoException(); reply?.writeIntArray(intArrayOf(PLUG_TYPE_2, PLUG_CCS_2)) }
                else -> reply?.writeNoException()
            }
            return true
        }
    }

    /** Tells the listeners the model whenever it has changed (the level moves while driving); looked at once a minute. */
    private val refresh = object : Runnable {
        override fun run() {
            val targets = synchronized(listeners) { listeners.toList() }
            if (targets.isEmpty()) return
            val bytes = model()
            if (bytes != null && !bytes.contentEquals(sent)) {
                sent = bytes
                for (l in targets) oneWay(l, 1, "com.google.android.gms.car.ICarSensorEventListener") { it.writeInt(1); writeEvent(it, bytes) }
                Log.i(TAG, "energy model sent: ${describe(this@CarDataService)}, ${bytes.size} bytes, sum ${java.util.zip.CRC32().apply { update(bytes) }.value.toString(16)}")
                // the step only: describe() is the log's English sentence; the phone app shows the car's values itself
                seen(this@CarDataService, "sent")
            }
            main.postDelayed(this, 60_000)
        }
    }

    /** The model to send: none unless Mate's own model is chosen (Maps then plans with the vehicle saved in Maps). */
    private fun model(): ByteArray? {
        if (net.prototux.aare.Settings(this).evModelSource != "mate") return null
        return car(this)?.let { EnergyModel.fromFile(this, it) ?: EnergyModel.build(it, knownChargeTimes(this)) }
    }

    /** `CarSensorEvent`: type, time (ns), no numbers, the model's bytes. */
    private fun writeEvent(p: Parcel, bytes: ByteArray) {
        val start = SafeParcel.begin(p)
        SafeParcel.intField(p, 1, SENSOR_ENERGY_MODEL)
        SafeParcel.longField(p, 2, android.os.SystemClock.elapsedRealtimeNanos())
        SafeParcel.floatArrayField(p, 3, FloatArray(0))
        SafeParcel.byteArrayField(p, 4, bytes)
        SafeParcel.intField(p, 1000, 1)
        SafeParcel.end(p, start)
    }

    private inline fun oneWay(target: IBinder, code: Int, token: String, body: (Parcel) -> Unit) {
        val d = Parcel.obtain()
        runCatching {
            d.writeInterfaceToken(token)
            body(d)
            target.transact(code, d, null, IBinder.FLAG_ONEWAY)
        }.onFailure { Log.w(TAG, "$token tx $code: $it") }
        d.recycle()
    }

    /** What is asked, each kind once: `adb logcat -s CarDataService`. */
    private val asked = java.util.Collections.synchronizedSet(HashSet<String>())
    private fun note(who: String, code: Int) {
        // every call at debug level: what Maps asks while it starts a route shows only in the order of its calls (B4)
        Log.d(TAG, "call $who $code")
        if (asked.add("$who/$code")) Log.i(TAG, "asked: $who tx $code")
    }

    companion object {
        /**
         * System I-003 (17.11.2026: "Ladestand 26 % stimmt, Schalter an, Mates Modell gewählt - trotzdem keine
         * Ladestopps in Google Maps"): each step Maps takes is noted with its time, for the phone app to show -
         * bind (Maps looks for the car), car (handed out) or refused, sensor (Maps asks for the energy model),
         * sent (the model with the level went out).
         */
        fun seen(c: Context, step: String, what: String = "") {
            c.getSharedPreferences("mate_ev", Context.MODE_PRIVATE).edit()
                .putLong("at:$step", System.currentTimeMillis()).apply { if (what.isNotEmpty()) putString("what:$step", what) else remove("what:$step") }.apply()
        }
        /** The steps as (step, time, what) for the phone app; time 0 = never. */
        fun steps(c: Context): List<Triple<String, Long, String>> {
            val p = c.getSharedPreferences("mate_ev", Context.MODE_PRIVATE)
            return listOf("bind", "car", "refused", "sensor", "sent").map { Triple(it, p.getLong("at:$it", 0), p.getString("what:$it", "").orEmpty()) }
        }
        private const val TAG = "CarDataService"
        private const val MAPS = "com.google.android.apps.maps"
        private const val SENSOR = "com.google.android.gms.car.ICarSensor"
        /** Sensor 23 of Android Auto: the vehicle energy model. */
        private const val SENSOR_ENERGY_MODEL = 23
        private const val MESSAGE = "com.google.android.gms.car.ICarMessage"
        private const val MESSAGE_CALLBACK = "com.google.android.gms.car.ICarMessageCallback"
        private const val MSG_REGISTER = 3
        private const val MSG_REQUEST = 4
        // As Maps' ProjectedCarInfo shows them: fuel 10 = electric; plugs 2 = Type 2 (Mennekes), 5 = CCS Combo 2
        private const val FUEL_ELECTRIC = 10
        private const val PLUG_TYPE_2 = 2
        private const val PLUG_CCS_2 = 5
        /** The last hours count against the model only from this distance on (a long journey, not a hop). */
        private const val RECENT_MIN_KM = 50.0
        /** A battery's level from the last drive still holds for planning the next one. */
        private const val SOC_MAX_AGE_MS = 7 * 24 * 3600_000L

        /** Debug builds: a state of charge to plan with instead of IDmate's (`debug_ev`); null = IDmate's. */
        @Volatile var debugSoc: Double? = null

        /** The car as the energy model takes it, or null when no state of charge is known. */
        internal fun car(context: Context): EnergyModel.Car? {
            // a combustion car has no battery to plan with: no charging stops, no level on arrival (18.11.2026: "dass sich
            // das Dashboard dann dementsprechend und die Navigation daraufhin anpasst")
            if (net.prototux.aare.Settings(context).combustion) return null
            val soc = debugSoc ?: DashData.socWithin(context, SOC_MAX_AGE_MS) ?: return null
            val variant = net.prototux.aare.Settings(context).evVariant
            // IDmate's own model of the car, when its server serves one (IDMate 1.2.341): consumption by speed at the
            // temperature of now, the capacity it learned, and what the last hours really took
            // I-003: the curve can be left out for Maps (a test against the plain model of the test environment)
            val idmate = DashData.energy(context)?.takeIf { net.prototux.aare.Settings(context).evUseIdmateCurve }
            val curve = idmate?.let { m -> m.speeds.indices.map { m.speeds[it] to m.kwh100[it] } }
            val recent = if (idmate != null && curve != null && idmate.recentKwh100 != null && idmate.recentSpeed != null &&
                (idmate.recentKm ?: 0.0) >= RECENT_MIN_KM
            ) (idmate.recentKwh100 / EnergyModel.curveKwh100(curve, idmate.recentSpeed)).coerceIn(0.8, 1.3) else 1.0
            val car = EnergyModel.Car(
                capacityWh = idmate?.capacityKwh?.let { (it * 1000).toInt() } ?: if (variant == "pro") 77_000 else 86_000,
                gtx = variant == "gtx",
                socPercent = soc,
                consumptionKwh100 = DashData.consumption(context),
                outsideCelsius = (idmate?.tExt ?: DashData.outsideCelsius(context))?.toFloat() ?: 15f,
                curve = curve,
                recentFactor = recent,
            )
            return if (debugSoc != null) car else car.copy(socPercent = sinceReading(context, car))
        }

        /**
         * The level now (19.11.2026: "er navigiert einmal die Route und sagt, ich komme dann mit 20% an ... nach 20, 30
         * Minuten komme ich mit 40% an ... der muss dynamisch die Prozente runterzählen, wenn ich mich fortbewege"):
         * IDmate's level is as old as its last reading - without reception for a while, the arrival was counted from a
         * level of half an hour ago against a way that got shorter. What was driven since (the car's speed) is taken off
         * by the model, from a reading older than [OLD_MS] on.
         */
        private fun sinceReading(context: Context, car: EnergyModel.Car): Double {
            val at = DashData.socAtMs(context)
            val age = System.currentTimeMillis() - at
            if (at <= 0 || age < OLD_MS) return car.socPercent
            val (m, s) = net.prototux.aare.car.CarData.drivenSince(at) ?: return car.socPercent
            if (m < 200 || s <= 0) return car.socPercent
            val kmh = (m / s * 3.6).coerceIn(20.0, 180.0)
            val usedWh = EnergyModel.tripW(kmh, car) * (m / (kmh / 3.6)) / 3600.0
            val now = (car.socPercent - usedWh / car.capacityWh * 100.0).coerceAtLeast(0.0)
            val t = System.currentTimeMillis()
            if (t - lastSaid > 60_000) {
                lastSaid = t
                net.prototux.aare.DiagLog.add("charging: IDmate's level %.0f %% is %d min old - %.1f km driven since, now about %.0f %%"
                    .format(java.util.Locale.ROOT, car.socPercent, age / 60_000, m / 1000, now), area = "CHARGE")
            }
            return now
        }
        @Volatile private var lastSaid = 0L
        /** A reading older than this has what was driven since taken off. */
        private const val OLD_MS = 90_000L

        /**
         * The charging time section of a car Google knows, when the user has put one on the device
         * (`files/ev_charge_times.bin`: field 8 of a real car's model, tag included); null otherwise.
         */
        internal fun knownChargeTimes(context: Context): ByteArray? =
            java.io.File(context.filesDir, "ev_charge_times.bin").takeIf { it.isFile && it.length() > 8 }?.let { f ->
                runCatching { f.readBytes().takeIf { it[0].toInt() == 0x42 } }.getOrNull()      // 0x42 = field 8, a message
            }

        /** In words, for the log (English, Locale.ROOT): what Maps is told. */
        fun describe(context: Context): String {
            val car = car(context) ?: return "no state of charge known"
            val use = if (car.curve != null) "IDmate's model: ${"%.1f".format(java.util.Locale.ROOT, EnergyModel.powerW(100.0, car) / 1000)} kWh/100 km at 100, " +
                "${"%.1f".format(java.util.Locale.ROOT, EnergyModel.powerW(130.0, car) / 1300)} at 130, last hours x ${"%.2f".format(java.util.Locale.ROOT, car.recentFactor)}"
            else (car.consumptionKwh100?.let { "trips ${"%.1f".format(java.util.Locale.ROOT, it)} kWh/100 km, " } ?: "no trips known, ") +
                "factor ${"%.2f".format(java.util.Locale.ROOT, EnergyModel.factor(car))}"
            return "battery ${"%.1f".format(java.util.Locale.ROOT, car.capacityWh / 1000.0)} kWh at ${"%.0f".format(java.util.Locale.ROOT, car.socPercent)} %, " +
                "${"%.0f".format(java.util.Locale.ROOT, car.outsideCelsius)} °C outside, $use, " +
                (if (knownChargeTimes(context) != null) "charging times of a known car (file)"
                else "10 to 80 % in ${EnergyModel.chargeTimes(150_000, car).let { t -> Math.round(EnergyModel.stopSeconds(t, car, 10.0, 80.0) / 60.0) }} min at 150 kW")
        }
    }
}
