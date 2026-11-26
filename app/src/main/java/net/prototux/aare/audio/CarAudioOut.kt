package net.prototux.aare.audio

import android.os.Process
import android.os.SystemClock
import android.util.Log
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import net.prototux.aare.DiagLog
import net.prototux.aare.aap.channels.AudioChannel
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Streams phone audio from a [PcmSource] to one of the car's audio channels (media or
 * navigation guidance).
 *
 * The car's stream is opened when sound starts and closed after a few seconds of silence, so the
 * head unit can go back to its own sources, like the official app does. [Focus] asks the car for
 * the right audio focus around it: full focus for media, a transient ducking one for guidance.
 * The stream starts once the car grants focus; the sound captured meanwhile (up to [MAX_QUEUED]
 * chunks) is kept and sent first, so a navigation prompt doesn't lose its first words.
 */
class CarAudioOut(
    private val source: PcmSource,
    private val channel: AudioChannel,
    private val focus: Focus,
    private val onFailed: () -> Unit = {},
) {
    interface Focus {
        /** About to play (true) or done (false). */
        fun request(playing: Boolean)
        /** Whether the car currently lets us play. */
        val granted: Boolean
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private val baseBacklog = if (channel.streamType == AudioStreamType.AUDIO_STREAM_GUIDANCE) MAX_QUEUED else LIVE_BACKLOG
    /** Until when (elapsedRealtime) to keep the larger backlog after the car reported an underflow. */
    @Volatile private var underflowUntil = 0L
    /** Whether we're actually feeding the car sound right now (read from [onUnderflow], written by the capture thread). */
    @Volatile private var playing = false
    /** When playback last stopped (elapsedRealtime): some head units underflow right after, not mid-stream. */
    @Volatile private var stoppedAtMs = 0L
    /**
     * Chunks kept when the car falls behind. Normally small to stay near real time; for a while
     * after an underflow, bigger, so a slow patch of acks doesn't turn into more gaps.
     */
    private val maxBacklog: Int
        get() = if (SystemClock.elapsedRealtime() < underflowUntil) maxOf(baseBacklog, UNDERFLOW_BACKLOG) else baseBacklog

    /** The car ran out of sound on this stream: trim less for [UNDERFLOW_HOLD_MS]. */
    fun onUnderflow() {
        val now = SystemClock.elapsedRealtime()
        // Some head units report one at every stream end rather than an actual gap: ignore it
        // while we're not playing, or just after we stopped, so it doesn't lag the next stream.
        if (!playing || now - stoppedAtMs < IGNORE_UNDERFLOW_AFTER_STOP_MS) return
        // Some head units report one at every stream end: log only the first of a burst.
        val quiet = now < underflowUntil
        underflowUntil = now + UNDERFLOW_HOLD_MS
        if (!quiet) Log.i(TAG,"car audio underflow on ${channel.streamType} (${channel.underflows} so far), keeping up to $UNDERFLOW_BACKLOG chunks")
    }

    fun start() {
        val cfg = channel.config ?: return
        running = true
        thread = Thread({ loop(cfg.samplingRate, cfg.numberOfChannels) }, "aare-audio-${channel.streamType.name.lowercase()}").apply { start() }
    }

    private fun loop(rate: Int, channels: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        if (!source.start()) {
            // Not a failure to act on if stop() came meanwhile (a slow start outlasts its join).
            val wanted = running
            running = false
            source.release()
            if (wanted) onFailed()
            return
        }
        // ~20 ms chunks: small enough for low latency, big enough to keep the message rate sane.
        val chunk = rate / 50 * channels * 2
        val buf = ByteArray(10 + chunk)
        // Mate's sound filter (O50) on the media channel only: navigation prompts keep their own sound.
        val eq = if (channel.streamType == AudioStreamType.AUDIO_STREAM_GUIDANCE) null else CarEqProcessor(rate, channels)
        if (eq != null) CarEq.mediaLinked = true
        // one level for every source (18.11.2026): what is not Mate's own music is measured here and turned down to the
        // music's target - the media of other apps by what plays, the navigation prompts as one source
        val level = if (eq != null) LoudnessLeveller(rate, channels) { CarSound.playing } else LoudnessLeveller(rate, channels, "guidance")
        var lastSound = 0L
        var startedAt = 0L
        playing = false
        var frames = 0L
        // Chunks (with their timestamp) waiting for focus or for room in the car's ack window.
        val queued = ArrayDeque<Pair<ByteArray, Long>>()
        // The source ended by itself (its service or the audio server died), not by stop(): the
        // next capture method takes over, rather than the car going silent for the rest of the drive.
        var died = false
        // Chunks of this stream the car got, and those it never got (its window stayed full, or the
        // wait for its focus outlasted the queue): gaps one hears, noted when the stream ends.
        var sent = 0L
        var lost = 0L
        // of those: dropped while waiting for the car's focus (a call, the car's own sound) - not a gap in what was heard
        // when the source was paused meanwhile (18.11.2026: 3358 of 31011 lost around a call, cause not to be told)
        var lostWaiting = 0L
        var dump: FileOutputStream? = null
        // per stream of a prompt (19.11.2026, crackle on the navigation channel): how long the phone kept the next chunk
        // waiting at most, how often over a chunk and a half, the car's underflows - said when the stream ends
        var lastChunkAt = 0L; var longestWait = 0L; var slowChunks = 0; var underflowsAtStart = 0; var streamChunks = 0
        // gaps in the sound itself (the phone's player stalled while the capture went on in time): a run of at least
        // 2 ms of exact silence between loud samples; and whether the screen was off at any time of the stream
        var dropouts = 0; var zeroRun = 0; var loudBefore = false; var screenOff = false
        fun queue(ts: Long) {
            queued.addLast(buf.copyOf() to ts)
            while (queued.size > MAX_QUEUED) { queued.removeFirst(); lost++; if (!channel.started) lostWaiting++ }
        }
        fun send(b: ByteArray, ts: Long) {
            if (channel.sendPcm(b, 10, chunk, ts)) {
                sent++
                runCatching { dump?.write(b, 10, chunk) }
            } else lost++
        }
        fun streamEnded() {
            runCatching { dump?.close() }
            dump = null
            if (eq == null && streamChunks > 0) {
                val under = channel.underflows - underflowsAtStart
                net.prototux.aare.DiagLog.add("car audio, guidance stream: %.1f s, longest wait for sound from the phone %d ms, %d wait(s) over 30 ms, %d underflow(s) of the car, %d gap(s) in the sound, screen %s, music back %s, %s"
                    .format(java.util.Locale.ROOT, streamChunks * 0.02, longestWait, slowChunks, under, dropouts, if (screenOff) "off" else "on", AudioTrials.musicBack.name, load()),
                    if (longestWait > 60 || under > 0 || dropouts > 0) net.prototux.aare.DiagLog.Level.WARN else null, "AUDIO")
            }
            longestWait = 0; slowChunks = 0; streamChunks = 0; dropouts = 0; zeroRun = 0; loudBefore = false; screenOff = false
            val name = channel.streamType.name.removePrefix("AUDIO_STREAM_").lowercase()
            if (lost > 0) DiagLog.add("car audio, $name: $lost of ${sent + lost} chunks (20 ms each) never reached the car" +
                if (lostWaiting > 0) " ($lostWaiting while waiting for the car to hand over its sound, ${lost - lostWaiting} because the car took no more)" else "")
            sent = 0; lost = 0; lostWaiting = 0
        }
        try {
            while (running) {
                var got = 0
                while (got < chunk && running) {
                    val n = source.read(buf, 10 + got, chunk - got)
                    if (n < 0) { died = running; running = false; break }
                    if (n == 0) { Thread.sleep(2); continue } // no data yet: yield, don't busy-spin
                    got += n
                }
                if (got < chunk) continue
                val now = SystemClock.elapsedRealtime()
                if (playing && eq == null) {
                    if (!net.prototux.aare.service.PowerState.screenOn) screenOff = true
                    // the samples: 16 bit little-endian, every channel
                    var k = 10
                    while (k + 1 < 10 + chunk) {
                        val v = ((buf[k].toInt() and 0xff) or (buf[k + 1].toInt() shl 8)).toShort().toInt()
                        if (v == 0) zeroRun++
                        else {
                            val loud = v > LOUD || v < -LOUD
                            if (zeroRun >= rate / 500 * channels && loudBefore && loud) dropouts++
                            zeroRun = 0
                            loudBefore = loud
                        }
                        k += 2
                    }
                }
                if (playing) {
                    val wait = now - lastChunkAt
                    if (lastChunkAt > 0 && wait > longestWait) longestWait = wait
                    if (lastChunkAt > 0 && wait > 30) slowChunks++
                    streamChunks++
                }
                lastChunkAt = now
                // the music's stream closed and opened anew after a prompt (AudioTrials.MusicBack.RESTART_MEDIA, a trial)
                if (eq != null && AudioTrials.restartMedia && channel.started) {
                    AudioTrials.restartMedia = false
                    net.prototux.aare.DiagLog.add("music back after a prompt: the music's stream to the car opened anew", area = "AUDIO")
                    channel.stopPlayback()
                }
                val sound = !isSilent(buf, 10, chunk)
                if (sound) { lastSound = now; level.process(buf, 10, chunk); eq?.process(buf, 10, chunk); if (eq != null) MediaFade.apply(buf, 10, chunk, now) }
                val ts = frames * 1_000_000L / rate
                frames += chunk / (2 * channels)
                if (!playing && lastSound == now) {
                    focus.request(true)
                    playing = true
                    startedAt = now
                    underflowsAtStart = channel.underflows; longestWait = 0; slowChunks = 0; streamChunks = 0
                    if (eq == null) net.prototux.aare.DiagLog.add("navigation sound: started")
                    // Debug builds, on request: what goes to the car, as raw PCM to look at afterwards.
                    dump = dumpDir?.let { d -> runCatching {
                        FileOutputStream(File(d, "car-${channel.streamType.name.removePrefix("AUDIO_STREAM_").lowercase()}-${rate}hz-${channels}ch-${System.currentTimeMillis()}.pcm"))
                    }.getOrNull() }
                // A prompt's stream is kept open for MIN_PROMPT_MS at least (18.11.2026, again: "Es hat zweimal gepiept
                // ... und danach ist die Musik nicht wieder lauter geworden", though the car had answered that the music
                // has its focus back): the warning tone is 0.6 s of sound, its stream was over after 3.6 s - spoken
                // prompts, after which the music nearly always comes back, last longer. A trial: the car may need the
                // time to finish lowering the music before it can raise it again.
                } else if (playing && now - lastSound > (if (eq != null) SILENCE_MS else PROMPT_SILENCE_MS) && (eq != null || now - startedAt > MIN_PROMPT_MS)) {
                    if (eq == null) net.prototux.aare.DiagLog.add("navigation sound: ended, music asked back")
                    channel.stopPlayback()
                    focus.request(false)
                    playing = false
                    stoppedAtMs = now
                    queued.clear()
                    streamEnded()
                }
                if (!playing) continue
                if (!channel.started) {
                    // Keep the sound until the car grants focus, then open the stream.
                    if (!focus.granted) { queue(ts); continue }
                    channel.startPlayback()
                    if (!channel.started) { queue(ts); continue }
                    // Prompts keep their whole start; media keeps only a little, or it would lag
                    // behind real time (video lip sync) for the whole stream. Use the base backlog,
                    // not a raised one left over from the last stream's underflow.
                    while (queued.size > baseBacklog) queued.removeFirst()
                } else if (!focus.granted) {
                    // Focus lost mid-stream: drop, stale sound later would be worse.
                    queued.clear()
                    continue
                }
                if (queued.isEmpty() && channel.hasRoom) {
                    // A copy: the writer thread sends the array later, and buf is refilled meanwhile.
                    send(buf.copyOf(), ts)
                    continue
                }
                // Catching up: send the backlog in order, as fast as the car's ack window allows.
                queue(ts)
                while (queued.isNotEmpty() && channel.hasRoom) queued.removeFirst().let { (b, t) -> send(b, t) }
                // The car stays behind (or never acks): stay close to real time. The oldest goes
                // through sendPcm so dropped chunks still count towards ack-less detection.
                while (queued.size > maxBacklog) queued.removeFirst().let { (b, t) -> send(b, t) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio capture stopped", e)
        } finally {
            if (playing) { runCatching { channel.stopPlayback() }; focus.request(false); stoppedAtMs = SystemClock.elapsedRealtime() }
            playing = false
            queued.clear()
            streamEnded()
            source.release()
            if (eq != null) CarEq.mediaLinked = false
            if (died) onFailed()
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    companion object {
        private const val TAG = "AareAudioOut"
        /**
         * Silence after which a stream ends; for prompts (26.11.2026: "Die Pause nach Sprachansagen und Hinweistönen wäre
         * auch cool, wenn wir die wieder minimieren" - log 25.11.: a prompt of 17:57:50, the music back at 17:57:57) 1.2 s
         * and at least [MIN_PROMPT_MS] in all; the music's own stream keeps 3 s.
         */
        private const val SILENCE_MS = 3_000L
        private const val PROMPT_SILENCE_MS = 1_200L
        /** A sample this loud on both sides of a run of silence makes the run a gap, not a pause between words. */
        private const val LOUD = 2_000
        /** Was 6 s (18.11.2026, a trial when the music did not come back after a beep); the music is now asked back the way chosen then (RELEASE_FIRST). */
        private const val MIN_PROMPT_MS = 2_500L
        /** About 1 s of 20 ms chunks kept while waiting for the car's focus grant. */
        private const val MAX_QUEUED = 50
        /** Backlog kept once media plays (~240 ms). */
        private const val LIVE_BACKLOG = 12
        /** Backlog after an underflow (~500 ms): more latency, fewer gaps. Bounded by [MAX_QUEUED]. */
        private const val UNDERFLOW_BACKLOG = 25
        private const val UNDERFLOW_HOLD_MS = 30_000L
        /** A head unit's end-of-stream underflow lands within this long of us stopping: not a real gap. */
        private const val IGNORE_UNDERFLOW_AFTER_STOP_MS = 1_000L

        /** Debug builds (tool debug_audio_dump): the folder each stream's sound is also written to, or null. */
        @Volatile var dumpDir: File? = null

        /** Digital silence or noise floor (captures deliver zeros when nothing plays). */
        fun isSilent(b: ByteArray, off: Int, len: Int): Boolean {
            var i = off
            val end = off + len - 1
            while (i < end) {
                val s = (b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8)
                if (abs(s.toShort().toInt()) > 8) return false
                i += 8 // sampling every 4th sample is plenty to detect sound
            }
            return true
        }
    }
}

/**
 * System I-055/I-064: how the tablet treated Mate while a prompt played - Android's CPU group of Mate's process (top-app
 * gets the fast cores, foreground or background less) and the tablet's heat (Android 10+) - so that a crackle heard can be
 * put beside a slowed-down tablet in the log.
 */
private fun load(): String {
    val group = runCatching { java.io.File("/proc/self/cpuset").readText().trim().trimStart('/').ifEmpty { "root" } }.getOrDefault("?")
    val heat = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) runCatching {
        when (net.prototux.aare.AareApp.instance.getSystemService(android.os.PowerManager::class.java).currentThermalStatus) {
            android.os.PowerManager.THERMAL_STATUS_NONE -> "none"
            android.os.PowerManager.THERMAL_STATUS_LIGHT -> "light"
            android.os.PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            android.os.PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            else -> "critical"
        }
    }.getOrDefault("?") else "?"
    return "cpu group $group, heat $heat"
}
