package net.prototux.aare.headunit

import aap_protobuf.service.media.shared.message.AudioConfigurationOuterClass.AudioConfiguration
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.DataInputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * Decodes the projection with an ffmpeg child process (H.264 Annex-B in, raw BGR frames out).
 * ffmpeg is everywhere on Linux desktops and decodes faster than anything we'd write.
 */
class VideoDecoder(private val width: Int, private val height: Int, private val onFrame: (BufferedImage) -> Unit) {
    private val process: Process = ProcessBuilder(
        listOf("ffmpeg", "-loglevel", "error", "-flags", "low_delay",
            // Probe little: the SPS/PPS and the first key frame (bigger than this) say everything.
            // No "-fflags nobuffer": with it ffmpeg 8 drops the key frame it probed, and a phone sends
            // few key frames, so the picture stays black. The AUD after each unit already flushes frames.
            "-probesize", "4096", "-analyzeduration", "0", "-f", "h264", "-i", "pipe:0") +
            // The first unit is the SPS/PPS alone, which ffmpeg 8 counts as a failed decode; at 100% errors
            // so far it would give up before the first frame (default limit 2/3). Never abort on errors.
            listOf("-max_error_rate", "1.0") +
            // One output frame per input frame, as soon as it is decoded (no frame-rate conversion).
            (if (newFfmpeg) listOf("-fps_mode", "passthrough") else listOf("-vsync", "passthrough")) +
            // Always exactly the size read below, whatever the phone actually encoded.
            listOf("-vf", "scale=$width:$height", "-f", "rawvideo", "-pix_fmt", "bgr24", "pipe:1"),
    ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    private val input: OutputStream = process.outputStream
    // The writer must never block the protocol thread: drop if ffmpeg falls badly behind.
    private val queue = ArrayBlockingQueue<ByteArray>(120)
    @Volatile var frames = 0
        private set

    // Kept to interrupt in close(): with the queue empty it would wait in take() forever.
    private val writer = Thread({
        try {
            while (true) { input.write(queue.take()); input.flush() }
        } catch (_: Exception) {
        }
    }, "ffmpeg-in").apply { isDaemon = true }

    init {
        writer.start()
        Thread({
            val data = DataInputStream(process.inputStream.buffered(width * height * 3))
            try {
                while (true) {
                    val img = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
                    data.readFully((img.raster.dataBuffer as DataBufferByte).data)
                    frames++
                    onFrame(img)
                }
            } catch (_: Exception) {
            }
        }, "ffmpeg-out").apply { isDaemon = true; start() }
    }

    /** False once ffmpeg exited (bad stream, killed): the caller starts a new decoder. */
    val alive: Boolean get() = process.isAlive

    /**
     * Queues one access unit (a frame, or the SPS/PPS). An access unit delimiter follows it: ffmpeg's
     * H.264 parser only outputs a frame once it sees the start of the next one, so without it the
     * screen would always be a frame late, and stuck on the one before last when the picture stops.
     */
    fun feed(data: ByteArray, offset: Int, length: Int) {
        val unit = ByteArray(length + AUD.size)
        System.arraycopy(data, offset, unit, 0, length)
        AUD.copyInto(unit, length)
        if (!queue.offer(unit)) dropped++
    }

    /** Frames dropped because ffmpeg fell behind (the picture is damaged until the next key frame). */
    @Volatile var dropped = 0
        private set

    fun close() {
        writer.interrupt()
        runCatching { input.close() }
        process.destroy()
    }

    companion object {
        /** H.264 access unit delimiter NAL (any slice types). */
        private val AUD = byteArrayOf(0, 0, 0, 1, 9, 0xF0.toByte())

        fun available(): Boolean = runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false)

        /** ffmpeg 5.1+ has -fps_mode; older ones only -vsync. */
        private val newFfmpeg: Boolean by lazy {
            runCatching {
                val p = ProcessBuilder("ffmpeg", "-hide_banner", "-h", "full").redirectErrorStream(true).start()
                p.inputStream.bufferedReader().use { r -> r.lineSequence().any { "fps_mode" in it } }.also { p.destroy() }
            }.getOrDefault(true)
        }
    }
}

/**
 * Plays the car's audio channels on the desktop's speakers, each through a small jitter buffer:
 * the phone's audio arrives in network-timed bursts, so playback starts once ~120 ms are queued
 * (again after a dropout), and the oldest audio is dropped if the queue grows past ~600 ms.
 */
class AudioOutputs {
    private val players = ConcurrentHashMap<AudioStreamType, Player>()

    fun start(stream: AudioStreamType, cfg: AudioConfiguration) {
        stop(stream)
        runCatching { players[stream] = Player(stream, cfg) }
            .onFailure { System.err.println("audio: can't open $stream: ${it.message}") }
    }

    /** Called on the protocol thread: only queues. */
    fun write(stream: AudioStreamType, data: ByteArray, offset: Int, length: Int) {
        players[stream]?.queue(data.copyOfRange(offset, offset + length))
    }

    fun stop(stream: AudioStreamType) {
        players.remove(stream)?.close()
    }

    fun close() = players.keys.toList().forEach(::stop)

    private class Player(private val stream: AudioStreamType, private val src: AudioConfiguration) {
        /**
         * This machine plays every stream as 48 kHz stereo: a 16 kHz mono line (navigation prompts) crackled on the
         * desktop - a pure tone too - while the same sound as music was clean, and what came from the phone was clean
         * (measured). The prompts are spread to 48 kHz stereo here (linear), the phone's stream is left as it is.
         */
        private val cfg: AudioConfiguration = AudioConfiguration.newBuilder().setSamplingRate(48_000).setNumberOfBits(16).setNumberOfChannels(2).build()
        private val upsample = src.samplingRate != 48_000 || src.numberOfChannels != 2
        private var lastSample = 0
        // For telling where a crackle comes from: chunks dropped for being late, waits for sound mid-stream, and
        // the largest gap between two chunks as they came from the phone.
        private var dropped = 0
        private var starved = 0
        private var lastAt = 0L
        private var maxGapMs = 0L
        private var received = 0
        private val bytesPerMs = cfg.samplingRate * cfg.numberOfChannels * (cfg.numberOfBits / 8) / 1000
        private val frame = cfg.numberOfChannels * (cfg.numberOfBits / 8)
        private val line: SourceDataLine
        private val chunks = java.util.concurrent.LinkedBlockingDeque<ByteArray>()
        private val queued = java.util.concurrent.atomic.AtomicInteger()
        @Volatile private var running = true
        private val thread: Thread

        init {
            val format = AudioFormat(cfg.samplingRate.toFloat(), cfg.numberOfBits, cfg.numberOfChannels, true, false)
            line = AudioSystem.getSourceDataLine(format)
            line.open(format, alignDown(bytesPerMs * 300))
            thread = Thread(::play, "audio-${stream.name.lowercase()}").apply { isDaemon = true; start() }
        }

        // For checking the sound that arrives, apart from how this machine plays it: with AARE_AUDIO_DUMP=FILE the
        // media stream is also written to FILE as it comes in (raw PCM in the stream's format), the other streams to
        // FILE.<stream>-<rate>hz-<channels>ch.
        private val dump: java.io.OutputStream? = System.getenv("AARE_AUDIO_DUMP")
            ?.let { if (stream == AudioStreamType.AUDIO_STREAM_MEDIA) it else "$it.${stream.name.removePrefix("AUDIO_STREAM_").lowercase()}-${cfg.samplingRate}hz-${cfg.numberOfChannels}ch" }
            ?.let { runCatching { java.io.FileOutputStream(it, true) }.getOrNull() }

        fun queue(input: ByteArray) {
            dump?.let { runCatching { it.write(input) } }
            val pcm = if (upsample) spread(input) else input
            val now = System.nanoTime() / 1_000_000
            if (lastAt != 0L && received > 0) maxGapMs = maxOf(maxGapMs, now - lastAt)
            lastAt = now
            received++
            chunks.offer(pcm)
            var total = queued.addAndGet(pcm.size)
            // Too far behind (a burst, or the clocks drifting): drop the oldest to keep latency low.
            while (total > bytesPerMs * MAX_MS) {
                val old = chunks.pollFirst() ?: break
                total = queued.addAndGet(-old.size)
                dropped++
            }
        }

        private fun play() {
            val prebuffer = bytesPerMs * PREBUFFER_MS
            try {
                while (running) {
                    // (Re)fill before starting, so jitter doesn't turn into clicks.
                    line.stop()
                    while (running && queued.get() < prebuffer) Thread.sleep(5)
                    line.start()
                    while (running) {
                        val c = chunks.pollFirst(PREBUFFER_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS) ?: run { starved++; null } ?: break
                        queued.addAndGet(-c.size)
                        line.write(c, 0, c.size - c.size % frame)
                    }
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                System.err.println("audio: ${e.message}")
            }
        }

        fun close() {
            System.err.println("audio ${stream.name.removePrefix("AUDIO_STREAM_").lowercase()}: $received chunks, $dropped dropped as late, " +
                "$starved waits for sound, largest gap ${maxGapMs} ms")
            running = false
            thread.interrupt()
            runCatching { line.stop(); line.flush(); line.close() }
        }

        private fun alignDown(n: Int) = n - n % frame

        /** 16-bit little-endian PCM of [src] to 48 kHz stereo, linear between samples (mono taken to both sides). */
        private fun spread(input: ByteArray): ByteArray {
            val ch = src.numberOfChannels
            val factor = 48_000 / src.samplingRate
            val inFrames = input.size / (2 * ch)
            val out = ByteArray(inFrames * factor * 4)
            var o = 0
            for (i in 0 until inFrames) {
                val b = i * 2 * ch
                val s = (input[b].toInt() and 0xff) or (input[b + 1].toInt() shl 8)
                for (k in 1..factor) {
                    val v = lastSample + (s - lastSample) * k / factor
                    out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte(); out[o + 2] = v.toByte(); out[o + 3] = (v shr 8).toByte()
                    o += 4
                }
                lastSample = s
            }
            return out
        }

        companion object {
            const val PREBUFFER_MS = 120
            const val MAX_MS = 600
        }
    }
}

/** The desktop microphone as the car's: 16 kHz mono 16-bit, in 20 ms chunks. */
class Microphone(private val sink: (ByteArray, Int) -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun open(): Boolean {
        if (running) return true
        val format = AudioFormat(16_000f, 16, 1, true, false)
        val line: TargetDataLine = runCatching { AudioSystem.getTargetDataLine(format).apply { open(format, 6400); start() } }
            .getOrElse { System.err.println("microphone unavailable: ${it.message}"); return false }
        running = true
        thread = Thread({
            val buf = ByteArray(640)
            while (running) {
                val n = line.read(buf, 0, buf.size)
                if (n > 0) sink(buf, n)
            }
            line.stop(); line.close()
        }, "mic").apply { isDaemon = true; start() }
        return true
    }

    fun close() {
        running = false
        thread = null
    }
}

/**
 * Moves an Android emulator's GPS (`adb emu geo fix`), so apps that use the phone's location
 * (like CoMaps) follow the simulated drive too. No-op for real phones.
 */
class EmulatorGps(private val serial: String?) {
    val enabled: Boolean = serial?.startsWith("emulator-") == true

    fun fix(f: Fix) {
        if (!enabled) return
        val cmd = listOf("adb", "-s", serial!!, "emu", "geo", "fix", "%.6f".format(java.util.Locale.US, f.lon), "%.6f".format(java.util.Locale.US, f.lat),
            "100", "12", "%.1f".format(java.util.Locale.US, f.speed * 1.943844)) // speed in knots
        runCatching { ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor() }
    }
}
