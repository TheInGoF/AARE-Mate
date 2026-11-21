package net.prototux.aare

/**
 * The language Mate shows, speaks and listens in: one of the language library's languages (res/values-<language>,
 * befehle-<language>.json), from the device's setting; English, the base, where the device's is not among them.
 */
object Language {
    /** The library's languages, the base first. */
    val SUPPORTED = listOf("en", "de")

    /** The device's language as one of [SUPPORTED]. */
    val code: String get() = java.util.Locale.getDefault().language.takeIf { it in SUPPORTED } ?: SUPPORTED.first()

    /**
     * What a language listens and speaks with: the Whisper models offered (first = the standard, with the download in MB)
     * and the voice set up first. Alternatives side by side, the user chooses among those on the device.
     */
    class Speech(val whisper: LinkedHashMap<String, Int>, val voice: String)

    private val SPEECH = mapOf(
        // base.en: English only, more exact for English; base: the multilingual model, the same size
        "en" to Speech(linkedMapOf("base.en" to 160, "base" to 160), "piper-en-lessac"),
        // 18.11.2026: base as the standard, small too slow on the S6 Lite, tiny taken out
        "de" to Speech(linkedMapOf("base" to 160), "piper"),
    )

    /** The speech of [code] (the device's language). */
    fun speech(lang: String = code): Speech = SPEECH.getValue(lang)

    /** Every Whisper model of any language: kept on the device, the others are left from earlier versions. */
    val ALL_WHISPER: Set<String> get() = SPEECH.values.flatMap { it.whisper.keys }.toSet()
}
