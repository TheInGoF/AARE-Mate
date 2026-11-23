package net.prototux.aare.host.projected

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface

/**
 * Hosts one app that renders through the private `ICarProjection` protocol (Google Maps' car UI).
 *
 * The host binds the app's exported projection service, hands it a [Surface] to draw into, and
 * drives the projection lifecycle over Binder. The app creates its own window on the supplied
 * surface, so its pixels arrive wherever the surface is shown (a `TextureView` on the car screen).
 *
 * The app enforces a `CAPTURE_VIDEO_OUTPUT` signature-permission check on the binding host inside
 * its own process, so on a stock phone `onSetup` is rejected. Getting past it needs a system that
 * lets Mate through by its package name (the ROM's car host list, `panel_auto_hosts`); the protocol
 * here is identical either way. See docs/maps-projection.md.
 */
class ProjectedHost(
    context: Context,
    private val component: ComponentName,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(text: String) {}
        fun onError(message: String) {}
    }

    private val context = context.applicationContext
    private val worker = HandlerThread("projected-host").apply { start() }
    private val handler = Handler(worker.looper)

    private var connection: ServiceConnection? = null
    private var rebinds = 0
    private var rebindWindowStart = 0L
    private var service: IBinder? = null
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var densityDpi = 0
    /** How much of the bottom edge the car screen's controls cover (pixels): told to the app as an inset. */
    var insetBottom = 0
    private var motionDownTime = 0L
    private var navigation: NavigationProvider? = null

    // ---- host-side Binder stubs the app calls back into ----

    /** ICarProjectionCallback: the app signals readiness and connection state here; we just ack. */
    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            seen("callback", code, data, flags)
            if (code <= 0x00ffffff) Log.d(TAG, "callback $code (${data.dataSize()} bytes)")
            if (code == 16 || code == 17) {
                // what Maps asks for when its search field is tapped (O97 keyboard): log its pieces
                val pos = data.dataPosition()
                runCatching {
                    data.setDataPosition(0)
                    data.enforceInterface(ProjectedProtocol.CALLBACK)
                    val parts = StringBuilder()
                    repeat(4) {
                        val at = data.dataPosition()
                        val present = data.readInt()
                        parts.append("[$at] int=$present ")
                        if (present == 1) {
                            data.setDataPosition(at + 4)
                            val ei = runCatching { android.view.inputmethod.EditorInfo.CREATOR.createFromParcel(data) }.getOrNull()
                            if (ei != null) { parts.append("EditorInfo(type=${ei.inputType}, action=${ei.imeOptions}, hint=${ei.hintText}, pkg=${ei.packageName}) "); return@repeat }
                            data.setDataPosition(at + 4)
                        }
                        data.setDataPosition(at)
                        val b = runCatching { data.readStrongBinder() }.getOrNull()
                        if (b != null) parts.append("Binder(${runCatching { b.interfaceDescriptor }.getOrNull()}) ") else data.setDataPosition(at + 4)
                    }
                    Log.i(TAG, "callback $code: $parts")
                    if (code == 16) {
                        val hint = Regex("hint=([^,)]*)").find(parts)?.groupValues?.get(1)?.takeIf { it != "null" }.orEmpty()
                        android.os.Handler(android.os.Looper.getMainLooper()).post { ProjectedInput.request(hint) }
                    }
                }.onFailure { Log.i(TAG, "callback $code unreadable: $it") }
                data.setDataPosition(pos)
                if (code == 17) android.os.Handler(android.os.Looper.getMainLooper()).post { ProjectedInput.close() }
            }
            if (code <= 0x00ffffff) net.prototux.aare.debug.Recorder.event("RUECKRUF $code ${data.dataSize()} B")
            reply?.writeNoException()
            return true
        }
    }

    /** ICarDisplay: returns the car UI capabilities the legacy UI reads. */
    private val carDisplay = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            seen("carDisplay", code, data, flags)
            if (code == ProjectedProtocol.TX_DISPLAY_GET_CAR_UI_INFO) {
                reply?.writeNoException()
                reply?.writeInt(1)
                reply?.let { ProjectedProtocol.writeCarUiInfo(it) }
            } else {
                reply?.writeNoException()
            }
            return true
        }
    }

    /** ICarDisplayManager: hands back the single [carDisplay]. */
    private val carDisplayManager = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            seen("carDisplayManager", code, data, flags)
            reply?.writeNoException()
            if (code == ProjectedProtocol.TX_DISPLAY_MANAGER_GET_DISPLAY) reply?.writeStrongBinder(carDisplay)
            return true
        }
    }

    /** ICar: the app reads CarInfo/CarUiInfo and the display manager before it draws. */
    private val car = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            seen("car", code, data, flags)
            when (code) {
                ProjectedProtocol.TX_CAR_GET_CAR_INFO -> {
                    reply?.writeNoException(); reply?.writeInt(1); reply?.let { ProjectedProtocol.writeCarInfo(it) }
                }
                ProjectedProtocol.TX_CAR_GET_CAR_UI_INFO -> {
                    reply?.writeNoException(); reply?.writeInt(1); reply?.let { ProjectedProtocol.writeCarUiInfo(it) }
                }
                ProjectedProtocol.TX_CAR_GET_DISPLAY_MANAGER -> {
                    reply?.writeNoException(); reply?.writeStrongBinder(carDisplayManager)
                }
                // isConnectedToCar
                3 -> { reply?.writeNoException(); reply?.writeInt(1) }
                // registerCarConnectionListener: the app loads the car's data only once it has been told
                // "connected" (ICarConnectionListener tx 1, with the kind of connection: 1 = by cable)
                5 -> {
                    data.enforceInterface(ProjectedProtocol.CAR)
                    val listener = data.readStrongBinder()
                    reply?.writeNoException()
                    if (listener != null) handler.post {
                        transact("car connected", listener, 1, oneWay = true) { d ->
                            d.writeInterfaceToken("com.google.android.gms.car.ICarConnectionListener")
                            d.writeInt(1)
                        }
                    }
                }
                else -> reply?.writeNoException()
            }
            return true
        }
    }

    /** What the app asks of this host, each kind once (for finding out what it still waits for). */
    private val asked = java.util.Collections.synchronizedSet(HashSet<String>())
    private fun seen(who: String, code: Int, data: Parcel, flags: Int) {
        if (code > 0x00ffffff) return      // interface/ping transactions of Binder itself
        if (who != "callback") Log.d(TAG, "call $who $code (${data.dataSize()} bytes)")
        if (asked.add("$who/$code")) {
            val pos = data.dataPosition()
            val raw = runCatching { data.setDataPosition(0); data.marshall() }.getOrNull()
            data.setDataPosition(pos)
            // the readable pieces (UTF-16 text of at least 3 letters) and the raw words after the interface name
            val text = raw?.let { b -> Regex("[\\x20-\\x7e]{3,}").findAll(String(b, Charsets.UTF_16LE)).map { it.value }.filter { "ICar" !in it }.joinToString(" | ") }.orEmpty()
            val tail = raw?.takeLast(24)?.joinToString("") { "%02x".format(it) }.orEmpty()
            Log.i(TAG, "asked: $who code $code, ${data.dataSize()} bytes" + (if (flags and 1 != 0) " (one way)" else "") + " text[$text] tail[$tail]")
        }
    }

    // ---- lifecycle ----

    /** Binds the service and starts projection onto [surface] once connected. */
    fun start(surface: Surface, width: Int, height: Int, densityDpi: Int) {
        this.surface = surface
        this.width = width
        this.height = height
        this.densityDpi = densityDpi
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = binder
                handler.post { onConnected(binder) }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                service = null
                status("projection service disconnected")
            }
            // the app was stopped (force-stop, update, the system's memory guard): the binding is gone for good and the
            // car screen stayed black until another area was chosen (O138) - bind afresh onto the same surface
            override fun onBindingDied(name: ComponentName) {
                status("projection binding died")
                if (live === this@ProjectedHost) live = null
                runCatching { context.unbindService(this) }
                if (connection !== this) return                       // stopped meanwhile
                connection = null; service = null
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - rebindWindowStart > 60_000) { rebindWindowStart = now; rebinds = 0 }
                if (++rebinds > 3) { error("projection ended - the app stopped again and again"); return }
                handler.postDelayed({
                    val s = this@ProjectedHost.surface ?: return@postDelayed
                    if (connection == null) start(s, width, height, densityDpi)
                }, 800)
            }
        }
        connection = conn
        val intent = Intent().setComponent(component)
        // BIND_INCLUDE_CAPABILITIES: the app gets our foreground rights (location while in use), else its
        // location requests stay inactive - it runs only as a bound service here, never on screen itself
        if (!context.bindService(intent, conn, Context.BIND_AUTO_CREATE or Context.BIND_INCLUDE_CAPABILITIES)) {
            connection = null
            error("could not bind ${component.flattenToShortString()}")
        }
    }

    private fun onConnected(binder: IBinder) {
        status("bound ${component.flattenToShortString()}")
        if (!onSetup(binder)) return
        val surface = surface ?: return
        if (!transactProjectionStart(binder, surface)) return
        resume()
        // Maps takes day or night only from a change, not from the start's configuration
        configurationChanged()
        live = this
        // Google Maps registers the car's sensors (the energy model for charging stops) only once its
        // navigation provider is started, as Android Auto does next to the projection
        if (component.packageName == NavigationProvider.MAPS && NavigationProvider.enabled) navigation = NavigationProvider(context).also { it.start() }
        pending?.let { pending = null; newIntent(it) }
    }

    /** Hands the app a task (tx 3, its onNewIntent) - a place to search for or to navigate to. */
    fun newIntent(intent: Intent) {
        val binder = service ?: return
        handler.post {
            transact("onNewIntent", binder, ProjectedProtocol.TX_ON_NEW_INTENT) { data ->
                data.writeInterfaceToken(ProjectedProtocol.PROJECTION)
                data.writeInt(1)
                intent.writeToParcel(data, 0)
            }
        }
    }

    /** onSetup (tx 1): registers our callback and ICar binders. Rejected without the permission. */
    private fun onSetup(binder: IBinder): Boolean = transact("onSetup", binder, ProjectedProtocol.TX_ON_SETUP) { data ->
        data.writeInterfaceToken(ProjectedProtocol.PROJECTION)
        data.writeStrongBinder(callback)
        data.writeStrongBinder(car)
    }

    private fun transactProjectionStart(binder: IBinder, surface: Surface): Boolean =
        transact("onProjectionStart", binder, ProjectedProtocol.TX_ON_PROJECTION_START) { data ->
            ProjectedProtocol.writeProjectionStart(data, surface, width, height, densityDpi, configuration(), insetBottom)
        }

    private fun configuration() = ProjectedProtocol.carConfiguration(context.resources.configuration, night)

    /**
     * The picture got another size (18.11.2026: the split view's shares, full screen): the app is told the new width and
     * height with a configuration change, as with day and night, and lays itself out anew. False when the size is the one
     * it has: nothing is told, so no new picture need come.
     */
    fun resize(width: Int, height: Int): Boolean {
        if (width == this.width && height == this.height) return false
        Log.i(TAG, "resized to ${width}x$height")
        this.width = width
        this.height = height
        configurationChanged()
        return true
    }

    /** Tells the app that day or night changed (tx 6); it switches its map colours. */
    private fun configurationChanged() {
        val binder = service ?: return
        val surface = surface ?: return
        handler.post {
            transact("onConfigurationChanged (night=$night)", binder, ProjectedProtocol.TX_ON_CONFIGURATION_CHANGED) { data ->
                ProjectedProtocol.writeConfigurationChanged(data, surface, width, height, densityDpi, configuration(), insetBottom)
            }
        }
    }

    /** onProjectionResume (tx 4): the app stays stopped after onProjectionStart until this. */
    fun resume() {
        val binder = service ?: return
        handler.post {
            transact("onProjectionResume", binder, ProjectedProtocol.TX_ON_PROJECTION_RESUME) { data ->
                data.writeInterfaceToken(ProjectedProtocol.PROJECTION)
                data.writeInt(ProjectedProtocol.DISPLAY_ID)
            }
            // The app keeps its window unfocusable and answers every touch with "not handled" until the host
            // tells it that it has the input focus (tx 18: has focus, then a second flag the app inverts).
            transact("onInputFocusChange", binder, ProjectedProtocol.TX_ON_INPUT_FOCUS_CHANGE) { data ->
                data.writeInterfaceToken(ProjectedProtocol.PROJECTION)
                data.writeInt(1)
                data.writeInt(FOCUS_SECOND_FLAG)
            }
        }
    }

    fun pause() {
        val binder = service ?: return
        handler.post {
            transact("onProjectionPause", binder, ProjectedProtocol.TX_ON_PROJECTION_PAUSE) { data ->
                data.writeInterfaceToken(ProjectedProtocol.PROJECTION)
                data.writeInt(ProjectedProtocol.DISPLAY_ID)
            }
        }
    }

    // ---- input forwarding ----

    /** Forwards a touch event (tx 7). Coordinates must already be in the projected display's space. */
    fun sendMotion(event: MotionEvent) {
        val binder = service ?: return
        if (event.action == MotionEvent.ACTION_DOWN) motionDownTime = event.downTime
        // A copy: the caller's event is recycled once this returns, and it is sent from the worker thread. Its
        // source must be exactly "touchscreen": the app injects only those into its window; anything else takes
        // it out of touch mode and is treated as a touchpad or rotary movement.
        val copy = MotionEvent.obtain(event)
        copy.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        handler.post {
            transact("onMotionEvent", binder, ProjectedProtocol.TX_ON_MOTION_EVENT, oneWay = true) { data ->
                data.writeInterfaceToken(ProjectedProtocol.PROJECTION)
                data.writeInt(ProjectedProtocol.DISPLAY_ID)
                data.writeInt(1)
                copy.writeToParcel(data, 0)
            }
            copy.recycle()
        }
    }

    /** Forwards a key event (tx 8). */
    fun sendKey(event: KeyEvent) {
        val binder = service ?: return
        handler.post {
            transact("onKeyEvent", binder, ProjectedProtocol.TX_ON_KEY_EVENT, oneWay = true) { data ->
                data.writeInterfaceToken(ProjectedProtocol.PROJECTION)
                data.writeInt(ProjectedProtocol.DISPLAY_ID)
                data.writeInt(1)
                event.writeToParcel(data, 0)
            }
        }
    }

    /** Stops projection and unbinds. Safe to call more than once. */
    fun stop() {
        val binder = service
        if (live === this) live = null
        if (binder != null) {
            handler.post {
                transact("onProjectionStop", binder, ProjectedProtocol.TX_ON_PROJECTION_STOP, oneWay = true) { data ->
                    data.writeInterfaceToken(ProjectedProtocol.PROJECTION)
                    data.writeInt(ProjectedProtocol.DISPLAY_ID)
                }
            }
        }
        navigation?.stop()
        navigation = null
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        service = null
        surface = null
        worker.quitSafely()
    }

    // ---- helpers ----

    private inline fun transact(
        name: String,
        binder: IBinder,
        code: Int,
        oneWay: Boolean = false,
        write: (Parcel) -> Unit,
    ): Boolean {
        val data = Parcel.obtain()
        val reply = if (oneWay) null else Parcel.obtain()
        return try {
            write(data)
            binder.transact(code, data, reply, if (oneWay) IBinder.FLAG_ONEWAY else 0)
            reply?.readException()
            status("$name ok")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "$name failed", t)
            error("$name failed: ${t.javaClass.simpleName}${t.message?.let { ": $it" }.orEmpty()}")
            false
        } finally {
            data.recycle()
            reply?.recycle()
        }
    }

    private fun status(text: String) {
        Log.i(TAG, text)
        // the steps up to the first picture into Mate's own log (System I-077: Maps stayed black and the exported log
        // said nothing, everything went to logcat only); taps, keys and day/night stay out, they come too often
        if (QUIET.none { text.startsWith(it) }) net.prototux.aare.DiagLog.add("maps: $text")
        listener.onStatus(text)
    }

    private fun error(message: String) {
        Log.w(TAG, message)
        net.prototux.aare.DiagLog.error("maps: $message")
        // Maps checks the host inside its own process: only a host the system lets through (a ROM's car host list, by
        // package name) passes - since 2.1.1 Mate is app.idmate.mate, no longer net.prototux.aare
        if (isRefusal(message))
            net.prototux.aare.DiagLog.error("maps: refused Mate (${context.packageName}) as its host - the system lets another package through, not this one")
        listener.onError(message)
    }

    companion object {
        private const val TAG = "ProjectedHost"
        private const val FOCUS_SECOND_FLAG = 0
        /** Steps that come with every tap, key or change of light: logcat only. */
        private val QUIET = listOf("onMotionEvent", "onKeyEvent", "onConfigurationChanged", "onInputFocusChange", "car connected")

        /** Whether an error of the host says the app refused Mate as its host (the permission check inside Maps). */
        fun isRefusal(message: String): Boolean =
            "CAPTURE_VIDEO_OUTPUT" in message || "SecurityException" in message || "ermission" in message

        /** The host whose app is on the car screen, and a task waiting for the app to come up. */
        @Volatile var live: ProjectedHost? = null
            private set
        @Volatile private var pending: Intent? = null

        /** Day or night on the car screen; a change goes at once to the app on the car screen. */
        @Volatile var night: Boolean = true
            set(value) {
                if (field == value) return
                field = value
                Log.i(TAG, "night: $value")
                live?.configurationChanged()
            }

        /** Gives [intent] to the projected app: at once if it is on the car screen, else as soon as it is. */
        fun deliver(intent: Intent) {
            val host = live
            if (host != null) host.newIntent(intent) else pending = intent
        }
    }
}
