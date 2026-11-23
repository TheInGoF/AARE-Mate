package net.prototux.aare.car

import android.content.Context
import android.util.Log
import net.prototux.aare.DiagLog
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * This device's own Bluetooth address, for the car's handshake (18.11.2026: "warum wir keine Lautsprecherausgabe übers
 * Bordsystem des Autos haben, wenn ich Anrufe führe"). The car offers a Bluetooth service over Android Auto and wants to
 * be told which phone is the one connected - with that it takes this device as its hands-free phone, and calls go over
 * its speakers and microphone. Android does not tell an app its own address, so the handshake was simply left out
 * unless the address had been typed into the phone app (and its hint said to leave it empty where the car works).
 *
 * Now Mate asks where it may: the system's setting through Shizuku, once, and keeps it.
 */
object OwnBluetooth {
    private const val TAG = "AareBt"
    private val MAC = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

    /**
     * The bond with the car, begun after it took the handshake (18.11.2026: "die Bluetooth-MAC-Adresse zu hinterlegen in
     * der App, damit ich über die Lautsprecher im Auto telefonieren kann, hat auch nicht geholfen" - the car answered
     * "accepted", not "already paired", and Mate left it at that: the handshake only tells the car which phone is
     * coming, the pairing itself has to follow from the phone, as Android Auto does it). What was done, for the log,
     * and whether a pairing was begun that the driver has to confirm on the tablet.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun bondWithCar(context: Context, carAddress: String?): Pair<String, Boolean> {
        if (!valid(carAddress)) return "the car named no Bluetooth address of its own - nothing to pair with" to false
        if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return "Mate may not use Bluetooth (the permission for nearby devices is missing) - no pairing" to false
        val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
            ?: return "the tablet has no Bluetooth" to false
        if (!adapter.isEnabled) return "Bluetooth is off on the tablet - no pairing, calls stay on the tablet" to false
        return runCatching {
            val car = adapter.getRemoteDevice(carAddress!!.trim().uppercase())
            when (car.bondState) {
                android.bluetooth.BluetoothDevice.BOND_BONDED -> phoneState(context) to false
                android.bluetooth.BluetoothDevice.BOND_BONDING -> "the pairing with the car is under way" to true
                else -> if (car.createBond()) "the pairing with the car is begun - to be confirmed on the tablet" to true
                    else "the pairing with the car could not be begun" to false
            }
        }.getOrElse { "pairing with the car failed: ${it.javaClass.simpleName}" to false }
    }

    /** Whether the tablet stands as the car's hands-free phone, in words for the log. */
    @android.annotation.SuppressLint("MissingPermission")
    fun phoneState(context: Context): String = runCatching {
        val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        val phone = adapter?.getProfileConnectionState(android.bluetooth.BluetoothProfile.HEADSET) == android.bluetooth.BluetoothProfile.STATE_CONNECTED
        if (phone) "the tablet is paired with the car and connected as a phone (hands-free)"
        else "the tablet is paired with the car, but no hands-free connection stands - calls stay on the tablet (connect the car under Bluetooth on the tablet, with \"Anrufe\" switched on)"
    }.getOrElse { "the hands-free connection is not known (${it.javaClass.simpleName})" }

    /**
     * The name the car shows for this device in its Android Auto list: the tablet's Bluetooth name, so that the car's
     * Bluetooth list and its Android Auto list name the same device alike (18.11.2026: "Es heißt auch SMP-619. aare
     * allerdings heißt Samsung SMP-619. Vielleicht ist das auch nochmal ein Ansatz, die Namensgebung einheitlich zu
     * gestalten"). Null when Android does not tell it (no permission, Bluetooth off).
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun ownName(context: Context): String? = runCatching {
        if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.name?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull() ?: runCatching {
        android.provider.Settings.Global.getString(context.contentResolver, "device_name")?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun valid(s: String?): Boolean = !s.isNullOrBlank() && s != "02:00:00:00:00:00" && MAC.matches(s.trim())

    /** The address from what a shell command printed, or null. */
    fun parse(out: String?): String? = out?.lineSequence()?.map { it.trim() }?.firstOrNull { valid(it) }?.uppercase()

    /** Asked through Shizuku; null when it is not there or does not tell. Waits [timeoutMs] at most. */
    fun fromShell(timeoutMs: Long = 2500): String? {
        val pool = Executors.newSingleThreadExecutor()
        return try {
            pool.submit(Callable {
                val cmd = arrayOf("settings", "get", "secure", "bluetooth_address")
                shizuku(cmd)
            }).get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.i(TAG, "own address not read: ${e.javaClass.simpleName}")
            null
        } finally {
            pool.shutdownNow()
        }
    }

    private fun shizuku(cmd: Array<String>): String? = runCatching {
        if (!net.prototux.aare.audio.CaptureMethods.shizukuAvailable()) return null
        // Shizuku's own way to run a command as the shell user (not public in its newer versions)
        val m = rikka.shizuku.Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, cmd, null, null) as Process
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        parse(out)
    }.onFailure { Log.i(TAG, "Shizuku: ${it.javaClass.simpleName}") }.getOrNull()

    /**
     * The address for the handshake: typed in, readable by the app, or asked from the shell (kept then). Says in the
     * diagnosis log when none is known - the car is not told which phone this is then.
     */
    fun forCar(context: Context, settings: net.prototux.aare.Settings, readable: List<String?>): String? {
        (listOf(settings.phoneBluetoothAddress.trim()) + readable).firstOrNull { valid(it) }?.let { return it!!.trim().uppercase() }
        val asked = fromShell()
        if (asked != null) {
            settings.phoneBluetoothAddress = asked
            DiagLog.add("car bluetooth: this device's own address read from the system (…${asked.takeLast(5)}) and kept")
            return asked
        }
        DiagLog.add("car bluetooth: this device's own address is not known - the car is not told which phone this is, calls may stay on the tablet (enter it under Verbindung, or allow Shizuku)")
        return null
    }
}
