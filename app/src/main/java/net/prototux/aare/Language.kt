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
     * The language tag routing and speaking ask for (Valhalla's spoken instructions, the phone's voice): the device's
     * country where it fits the language, else the language's usual one.
     */
    fun tag(lang: String = code): String {
        val country = java.util.Locale.getDefault().takeIf { it.language == lang }?.country.orEmpty()
        return when {
            country.isNotEmpty() && "$lang-$country" in VALHALLA -> "$lang-$country"
            else -> DEFAULT_TAG.getValue(lang)
        }
    }

    /** [tag] as a locale, for a speech engine. */
    fun locale(lang: String = code): java.util.Locale = java.util.Locale.forLanguageTag(tag(lang))

    /** The language tags of Valhalla's narrative that belong to [SUPPORTED]. */
    private val VALHALLA = setOf("en-US", "en-GB", "de-DE")
    private val DEFAULT_TAG = mapOf("en" to "en-US", "de" to "de-DE")

    /**
     * What a language listens and speaks with: the Whisper models offered (first = the standard, with the download in MB)
     * and the voice set up first. Alternatives side by side, the user chooses among those on the device.
     */
    class Speech(val whisper: LinkedHashMap<String, Int>, val voice: String)

    private val SPEECH = mapOf(
        // base.en: English only, more exact for English; base: the multilingual model, the same size
        "en" to Speech(linkedMapOf("base.en" to 160, "base" to 160), "piper-en-lessac-high"),
        // 18.11.2026: base as the standard, small too slow on the S6 Lite, tiny taken out; the first voice Thorsten hoch
        // (21.11.2026: "hoch bevorzugen")
        "de" to Speech(linkedMapOf("base" to 160), "piper-thorsten-high"),
    )

    /** The speech of [code] (the device's language). */
    fun speech(lang: String = code): Speech = SPEECH.getValue(lang)

    /** Every Whisper model of any language: kept on the device, the others are left from earlier versions. */
    val ALL_WHISPER: Set<String> get() = SPEECH.values.flatMap { it.whisper.keys }.toSet()
}
