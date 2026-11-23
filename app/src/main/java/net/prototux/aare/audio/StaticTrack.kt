package net.prototux.aare.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * A short sound played whole from memory (a prompt, a tone): a static mono [AudioTrack] with the samples written into it.
 * A track that could not take them is let go here (I-118i), else it stays held; the failure is thrown to the caller.
 */
internal object StaticTrack {
    /** A track holding [pcm] (16 bit) at [rate]. */
    fun of(attrs: AudioAttributes, rate: Int, pcm: ShortArray): AudioTrack =
        build(attrs, AudioFormat.ENCODING_PCM_16BIT, rate, pcm.size * 2) { it.write(pcm, 0, pcm.size) }

    /** A track holding [pcm] (float) at [rate]. */
    fun of(attrs: AudioAttributes, rate: Int, pcm: FloatArray): AudioTrack =
        build(attrs, AudioFormat.ENCODING_PCM_FLOAT, rate, pcm.size * 4) { it.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING) }

    private inline fun build(attrs: AudioAttributes, encoding: Int, rate: Int, bytes: Int, write: (AudioTrack) -> Int): AudioTrack {
        val t = AudioTrack.Builder().setAudioAttributes(attrs)
            .setAudioFormat(AudioFormat.Builder().setEncoding(encoding).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(bytes).build()
        val written = runCatching { write(t) }.getOrElse { e -> t.release(); throw e }
        if (written < 0) { t.release(); throw IllegalStateException("write failed ($written)") }
        return t
    }
}
