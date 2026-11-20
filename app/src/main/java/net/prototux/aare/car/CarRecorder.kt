package net.prototux.aare.car

import android.content.Context
import android.util.Log
import net.prototux.aare.aap.AapMessage
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Everything the car tells Mate, kept on the device (wish O167 of 16.11.2026: "mate muss alles abspeichern an
 * informationen was das fahrzeug uns gibt. dev log mäßig") - to see what it sends, e.g. whether the ambient light's
 * colour is among it. One file per connection in files/carlog/ (JSON lines): every message of the head unit with time,
 * channel, message id, length and its bytes; a message that only repeats itself is counted, written again when it
 * changes. The last [KEEP_FILES] connections and at most [KEEP_BYTES] are kept.
 * Read: adb pull /sdcard/... is not needed - `adb shell run-as net.prototux.aare ls files/carlog`.
 */
internal class CarRecorder(context: Context, carName: String) {
    private val dir = File(context.filesDir, "carlog").apply { mkdirs() }
    private val file = File(dir, SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date()) + "_" +
        carName.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(40) + ".jsonl")
    private val io = Executors.newSingleThreadExecutor { Thread(it, "car-recorder").apply { isDaemon = true } }
    private var out: BufferedWriter? = null
    private val last = HashMap<String, Int>()
    private val repeats = HashMap<String, Int>()
    private var lastFlush = 0L
    @Volatile private var closed = false

    init {
        io.execute {
            runCatching {
                prune()
                out = file.bufferedWriter()
                Log.i(TAG, "recording the car to ${file.name}")
            }.onFailure { Log.w(TAG, "cannot record: $it") }
        }
    }

    /** From the protocol thread: copies what it needs and hands the writing to its own thread. */
    fun record(msg: AapMessage, channelName: String) {
        if (closed) return
        val body = runCatching { msg.bodyBytes() }.getOrNull() ?: return
        val t = System.currentTimeMillis()
        io.execute {
            val w = out ?: return@execute
            val key = "${msg.channel}:${msg.control}:${msg.id}"
            val hash = body.contentHashCode()
            if (last[key] == hash) { repeats[key] = (repeats[key] ?: 0) + 1; return@execute }
            last[key] = hash
            val o = JSONObject().put("t", t).put("ch", msg.channel).put("name", channelName)
                .put("ctl", msg.control).put("id", msg.id).put("len", body.size)
                .put("hex", hex(body, MAX_HEX_BYTES))
            repeats.remove(key)?.let { o.put("repeated_before", it) }
            runCatching {
                w.write(o.toString()); w.newLine()
                if (t - lastFlush > 2000) { w.flush(); lastFlush = t }
            }
        }
    }

    fun close() {
        closed = true
        io.execute {
            runCatching {
                out?.write(JSONObject().put("t", System.currentTimeMillis()).put("end", true).put("repeats", JSONObject(repeats.toMap())).toString())
                out?.newLine(); out?.close()
            }
            out = null
        }
        io.shutdown()
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.name.endsWith(".jsonl") }?.sortedByDescending { it.lastModified() } ?: return
        var bytes = 0L
        files.forEachIndexed { i, f ->
            bytes += f.length()
            if (i >= KEEP_FILES || bytes > KEEP_BYTES) f.delete()
        }
    }

    private fun hex(b: ByteArray, max: Int): String {
        val n = minOf(b.size, max)
        val sb = StringBuilder(n * 2)
        for (i in 0 until n) sb.append(String.format("%02x", b[i]))
        if (b.size > max) sb.append("…")
        return sb.toString()
    }

    companion object {
        private const val TAG = "CarRecorder"
        const val KEEP_FILES = 30
        const val KEEP_BYTES = 100L * 1024 * 1024
        const val MAX_HEX_BYTES = 4096
    }
}
