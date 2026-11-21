package net.prototux.aare.speech

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Measures how fast speech recognition runs on this device (O56): the device's own voice speaks test sentences into
 * files, each installed Whisper size recognizes them with 2 and 4 threads. "Real-time factor" = computing time /
 * length of the sound (below 1: faster than spoken). Times from the device's clock. Results also go to
 * `files/sprache/messung.txt` and the log (tag MateAsr).
 */
object SpeechBench {
    private const val TAG = "MateAsr"

    val SENTENCES = listOf(
        "Spiele Wizo.",
        "Spiele das Album Jenseits von Eden von den Toten Hosen.",
        "Anna schreibt: Ich bin in zehn Minuten da, soll ich noch etwas vom Bäcker mitbringen?",
    )

    class Row(val size: String, val threads: Int, val loadMs: Long, val sentence: Int, val seconds: Double, val ms: Long, val text: String) {
        val rtf get() = ms / 1000.0 / seconds
    }

    class Wave(val samples: FloatArray, val rate: Int) { val seconds get() = samples.size.toDouble() / rate }

    /** Runs everything on the calling thread (minutes for the larger models): call it off the main thread. */
    fun run(context: Context, progress: (String) -> Unit): List<Row> {
        val waves = speak(context, SENTENCES, progress)
        // Mate's own voice (when it is the device voice) spoke them: its model is let go before the recognizers load -
        // both in memory at once was too much for the device (18.11.2026: the app ended at the test sentences)
        LocalTts.release()
        val rows = ArrayList<Row>()
        val out = StringBuilder("Messung ${java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(java.util.Date())} – " +
            "${android.os.Build.MODEL}, ${Runtime.getRuntime().availableProcessors()} Kerne\n")
        for (size in LocalAsr.installed(context)) for (threads in listOf(2, 4)) {
            progress(context.getString(net.prototux.aare.R.string.speech_bench_loading, size, threads))
            val t0 = SystemClock.elapsedRealtime()
            val rec = LocalAsr.load(context, size, threads)
            val loadMs = SystemClock.elapsedRealtime() - t0
            try {
                waves.forEachIndexed { i, w ->
                    progress(context.getString(net.prototux.aare.R.string.speech_bench_sentence, size, threads, i + 1))
                    val s = SystemClock.elapsedRealtime()
                    val text = LocalAsr.recognize(rec, w.samples, w.rate)
                    val row = Row(size, threads, loadMs, i, w.seconds, SystemClock.elapsedRealtime() - s, text)
                    rows += row
                    val line = "whisper-$size ${threads}T laden ${loadMs} ms | Satz ${i + 1} (%.1f s): %d ms, RTF %.2f | %s".format(Locale.ROOT, row.seconds, row.ms, row.rtf, text)
                    Log.i(TAG, line); out.append(line).append('\n')
                }
            } finally {
                rec.release()
            }
        }
        runCatching { File(context.getExternalFilesDir(null), "sprache").apply { mkdirs() }.resolve("messung.txt").writeText(out.toString()) }
        return rows
    }

    /** The test sentences as sound, from the device's own (German) voice. */
    fun speak(context: Context, sentences: List<String>, progress: (String) -> Unit = {}): List<Wave> {
        progress(context.getString(net.prototux.aare.R.string.speech_bench_speaking))
        val ready = CountDownLatch(1)
        var ok = false
        lateinit var tts: TextToSpeech
        tts = TextToSpeech(context.applicationContext) { status -> ok = status == TextToSpeech.SUCCESS; ready.countDown() }
        try {
            check(ready.await(15, TimeUnit.SECONDS) && ok) { "voice not ready" }
            tts.language = Locale.GERMANY
            val left = CountDownLatch(sentences.size)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) = left.countDown()
                @Deprecated("old API") override fun onError(id: String?) = left.countDown()
            })
            val files = sentences.indices.map { File(context.cacheDir, "asr-test-$it.wav") }
            sentences.forEachIndexed { i, s -> tts.synthesizeToFile(s, null, files[i], "s$i") }
            check(left.await(60, TimeUnit.SECONDS)) { "voice took too long" }
            return files.map { readWav(it.readBytes()) }
        } finally {
            tts.shutdown()
        }
    }

    /** 16 bit PCM WAV to mono samples. */
    fun readWav(b: ByteArray): Wave {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var rate = 16000; var channels = 1
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            val len = bb.getInt(pos + 4)
            if (id == "fmt ") { channels = bb.getShort(pos + 10).toInt(); rate = bb.getInt(pos + 12) }
            if (id == "data") {
                val n = minOf(len, b.size - pos - 8) / 2 / channels
                val out = FloatArray(n) { i -> bb.getShort(pos + 8 + i * 2 * channels) / 32768f }
                return Wave(out, rate)
            }
            pos += 8 + len + (len and 1)
        }
        error("no sound in the file")
    }
}
