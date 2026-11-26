package net.prototux.aare.music

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import net.prototux.aare.audio.BassLevel
import java.io.File
import kotlin.math.log10

/**
 * The bass share of a whole song, from its file (System L-105, 26.11.2026: "Die Tonanalyse vielleicht richtig herholen.
 * Über das ganze Lied hinweg"): the offline titles are gone through once after a sync, at home, so in the car their
 * bass lift stands from the first second. The same measure as the live one (BassLevel): power below its split against
 * all of it, quiet passages left out.
 */
object SongBass {
    /** Measures up to [max] offline titles not measured yet; how many were. Off the main thread. */
    @JvmStatic
    fun measureOffline(c: Context, max: Int): Int {
        var done = 0
        val t0 = System.currentTimeMillis()
        for (list in Offline.lists(c)) {
            for (t in Offline.list(c, list) ?: continue) {
                if (done >= max) return done.also { say(it, t0) }
                if (!MusicStats.bass(c, t.key).isNaN()) continue
                val f = Offline.track(c, t.key) ?: continue
                val share = runCatching { analyze(f) }.getOrNull() ?: continue
                MusicStats.bass(c, t.key, share)
                done++
            }
        }
        return done.also { say(it, t0) }
    }

    private fun say(n: Int, t0: Long) {
        if (n > 0) net.prototux.aare.DiagLog.add("music: bass of $n offline songs measured over their whole length (${(System.currentTimeMillis() - t0) / 1000} s)", area = "AUDIO")
    }

    /** The bass share of [file] in dB below its whole power; null when it cannot be decoded or is silent. */
    fun analyze(file: File): Double? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(file.path)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            var low = BassLevel.lowPass(BassLevel.SPLIT_HZ.toDouble(), rate)
            val st = DoubleArray(4)
            var lowSum = 0.0; var allSum = 0.0
            var blockLow = 0.0; var blockAll = 0.0; var blockN = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    low = BassLevel.lowPass(BassLevel.SPLIT_HZ.toDouble(), rate)
                } else if (o >= 0) {
                    val buf = c.getOutputBuffer(o)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val block = rate / 50
                    while (buf.remaining() >= channels * (if (float) 4 else 2)) {
                        var m = 0.0
                        for (ch in 0 until channels) m += if (float) buf.float * 32768.0 else buf.short.toDouble()
                        m /= channels
                        val y = low[0] * m + low[1] * st[0] + low[2] * st[1] - low[3] * st[2] - low[4] * st[3]
                        st[1] = st[0]; st[0] = m; st[3] = st[2]; st[2] = y
                        blockLow += y * y; blockAll += m * m; blockN++
                        if (blockN >= block) {
                            // quiet passages say nothing about the song's bass (as live)
                            if (blockAll / blockN > BassLevel.QUIET * BassLevel.QUIET) { lowSum += blockLow; allSum += blockAll }
                            blockLow = 0.0; blockAll = 0.0; blockN = 0
                        }
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return if (lowSum > 0 && allSum > 0) 10 * log10(lowSum / allSum) else null
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            ex.release()
        }
    }
}
