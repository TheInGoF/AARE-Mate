package net.prototux.aare.host

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import net.prototux.aare.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Lets the voice assistant act on the car (Lapin and any assistant that adopts it), through
 * `ContentResolver.call` on `content://<application id>.car` (Mate: `content://app.idmate.mate.car`):
 *
 * - `call("tools")` returns `{"json": <JSON array of tool specs>}`, empty when no car is connected;
 * - `call("run", <tool name>, {"args": <JSON object>})` returns `{"result": <JSON object>}`.
 *
 * Only the app chosen as the car's voice assistant (or else the phone's default digital assistant
 * setting, never a guess) may call it.
 */
class CarControlProvider : ContentProvider() {
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        val caller = callingPackage
        val allowed = Assistants.authorized(context, Settings(context).assistantPackage)
        // A debug build (and the measuring build) also answers adb (the shell): whoever has adb has this app's data
        // anyway, and it lets the car screen be tested without a hand on it.
        val adb = net.prototux.aare.BuildConfig.TEST_HOOKS && android.os.Binder.getCallingUid().let { it == android.os.Process.SHELL_UID || it == android.os.Process.ROOT_UID }
        if (!adb && (caller == null || caller != allowed)) throw SecurityException("Only the car's voice assistant (${allowed ?: "none chosen"}) may control the car")
        return when (method) {
            "tools" -> Bundle().apply { putString("json", onMain { if (CarRuntime.active != null) CarTools.specs() else JSONArray() }.toString()) }
            "run" -> {
                val name = arg ?: return null
                // "args64": the same JSON in Base64, for callers whose command line can't carry colons and spaces (adb).
                val json = extras?.getString("args64")?.let { runCatching { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT)) }.getOrNull() }
                    ?: extras?.getString("args") ?: "{}"
                val args = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
                val result = onMain {
                    val rt = CarRuntime.active ?: return@onMain JSONObject().put("ok", false).put("error", "the car isn't connected")
                    CarTools.run(rt, name, args)
                }
                Bundle().apply { putString("result", result.toString()) }
            }
            // test builds through adb, no car needed: the storage places (L-080) and, with arg = a place's id ("-" = the
            // device), a move there
            "storage" -> if (!adb) null else Bundle().apply {
                val s = net.prototux.aare.Storage
                val out = JSONObject()
                if (arg != null) out.put("moved", runCatching { s.volumes(context).first { it.id == arg.removePrefix("-") }.let { v -> s.move(context, v) { _, _, _ -> } } }
                    .exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "ok")
                out.put("chosen", s.chosen(context)).put("missing", s.cardMissing(context))
                    .put("maps", s.dir(context, s.MAPS).path).put("speech", s.dir(context, s.SPEECH).path)
                out.put("volumes", JSONArray().apply { s.volumes(context).forEach { v ->
                    put(JSONObject().put("id", v.id).put("label", v.label).put("root", v.root.path).put("free", v.free).put("fat32", v.fat32)
                        .put("fs", if (v.internal) "" else s.fsType(v.id) ?: "?")
                        .put("problem", s.problem(context, v)?.let { p -> when (p) {
                            is net.prototux.aare.Storage.Problem.TooLarge -> "too large ${p.bytes}"
                            is net.prototux.aare.Storage.Problem.NoRoom -> "no room ${p.need}/${p.free}" } }))
                } })
                putString("result", out.toString())
            }
            // test builds through adb: the address of the offline tile service (OfflineTiles), started if need be
            "tiles" -> if (!adb) null else Bundle().apply { putString("result", net.prototux.aare.ui.car.navi.OfflineTiles.url(context)) }
            else -> null
        }
    }

    /** The car runtime lives on the main thread; provider calls arrive on binder threads. */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        val done = CountDownLatch(1)
        main.post { result = runCatching(block); done.countDown() }
        if (!done.await(10, TimeUnit.SECONDS)) throw IllegalStateException("the car screen is busy")
        return result!!.getOrThrow()
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
