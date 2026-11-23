package net.prototux.aare.speech

/**
 * Text made easier for the device voices (Piper, Kokoro) before it is computed (17.11.2026: "eine Baumstraße ... hat
 * Thorsten Baums und Traße gesprochen ... eventuell kann man da ein wenig was feinjustieren"). Their pronunciation
 * rules (espeak-ng) cut long compounds in the wrong place: the kind of road is set apart ("Baum-Straße"), which sounds
 * the same but keeps the "st" together; "Str." is said as "Straße".
 */
object Pronounce {
    private val KINDS = "straße|strasse|weg|platz|allee|gasse|ring|ufer|damm|chaussee|brücke|steig|markt|graben|hof|park"
    /** A word of at least three letters ending in a kind of road: "Baumstraße", "Bahnhofsplatz", "Schillerring". */
    private val COMPOUND = Regex("""\b(\p{Lu}\p{Ll}{2,}?)($KINDS)\b""")
    private val ABBREVIATED = Regex("""\b(\p{L}+?)(str|Str)\.(?=\s|$|,)""")

    /**
     * Abbreviations of road names and signs said in full (18.11.2026: "Piper hat D-R Schumannplatz gelesen statt Doktor
     * Schumannplatz. Also sollten wir ihm vielleicht Abkürzungen mitgeben für die Ansage"). The full stop also ended the
     * sentence for the voice: "Dr." was computed alone and spelled.
     */
    private val SHORT = listOf(
        "Dr" to "Doktor", "Prof" to "Professor", "St" to "Sankt", "Str" to "Straße", "Pl" to "Platz", "Bhf" to "Bahnhof",
        "Hbf" to "Hauptbahnhof", "Bgm" to "Bürgermeister", "Pfr" to "Pfarrer", "Gebr" to "Gebrüder", "Geschw" to "Geschwister",
        "Kr" to "Kreis", "Ri" to "Richtung", "Abzw" to "Abzweig", "Nr" to "Nummer", "ca" to "circa", "bzw" to "beziehungsweise",
        // more help for the voice (18.11.2026: "Für die Sprachsynthese müssen wir immer ein paar Hilfen parat haben")
        "Hl" to "Heilige", "Ch" to "Chaussee", "Rh" to "Rhein", "Gew" to "Gewerbegebiet", "Ind" to "Industrie", "Ortst" to "Ortsteil",
        "Bf" to "Bahnhof", "Mstr" to "Meister",
    ).map { (short, full) -> Regex("""(?<![\p{L}\p{N}])""" + short + """\.(?=[\s,;:)-]|$)""") to full }

    /** Abbreviations of more than one piece, said in full; their full stops ended sentences for the voice. */
    private val PHRASES = listOf(
        "z. B." to "zum Beispiel", "z.B." to "zum Beispiel", "d. h." to "das heißt", "d.h." to "das heißt",
        "u. a." to "unter anderem", "u.a." to "unter anderem", "o. Ä." to "oder Ähnliches", "usw." to "und so weiter",
        "evtl." to "eventuell", "ggf." to "gegebenenfalls", "inkl." to "inklusive", "zzgl." to "zuzüglich",
        "Std." to "Stunden", "Min." to "Minuten", "Tel." to "Telefon",
    ).map { (short, full) -> Regex("""(?<![\p{L}\p{N}])""" + Regex.escape(short) + """(?=[\s,;:)]|$)""") to full }

    /**
     * Units after a number, said in full: espeak spells "km/h", "kWh" or "m" letter by letter. Longest first; the singular
     * after "1".
     */
    private val UNITS = listOf(
        "km/h" to ("Kilometer pro Stunde" to "Kilometer pro Stunde"), "kWh/100 km" to ("Kilowattstunden auf 100 Kilometer" to "Kilowattstunde auf 100 Kilometer"),
        "kWh" to ("Kilowattstunden" to "Kilowattstunde"), "kW" to ("Kilowatt" to "Kilowatt"), "km" to ("Kilometer" to "Kilometer"),
        "m" to ("Meter" to "Meter"), "min" to ("Minuten" to "Minute"), "h" to ("Stunden" to "Stunde"), "s" to ("Sekunden" to "Sekunde"),
        "%" to ("Prozent" to "Prozent"), "°C" to ("Grad" to "Grad"), "°" to ("Grad" to "Grad"), "€" to ("Euro" to "Euro"),
        "V" to ("Volt" to "Volt"), "A" to ("Ampere" to "Ampere"),
    )
    private val UNIT = Regex("""(\d+(?:,\d+)?)\s?(""" + UNITS.joinToString("|") { Regex.escape(it.first) } + """)(?![\p{L}\p{N}/])""")

    /** "14:30 Uhr" and "14:30" -> "14 Uhr 30"; "14:00" -> "14 Uhr". */
    private val TIME = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)(?:\s?Uhr)?\b""")

    /** A decimal comma: "3,5" said "3 Komma 5" (espeak paused at the comma as at a list). */
    private val DECIMAL = Regex("""(\d),(\d)""")

    /**
     * An ordinal number before a word ("die 2. Ausfahrt", "am 21. November"): the word with its ending, so the full stop
     * no longer cuts the sentence in two (LocalTts.pieces) and the voice does not say "zwei".
     */
    private val ORDINAL = Regex("""(?<![\d.,])(?:(\p{L}+)\s)?(\d{1,4})\.(?=\s+(\p{L}+))""")
    /** Words before an ordinal that take the ending -en (dative and accusative): "am 21.", "den 3.", "im 2.". */
    private val EN_BEFORE = setOf("am", "im", "zum", "zur", "vom", "beim", "den", "dem", "des", "einem", "einen", "ab", "bis")
    /** Words before an ordinal with -e: "die 2. Ausfahrt", "der 3. Kreisverkehr". */
    private val E_BEFORE = setOf("die", "der", "das", "jede", "jeder", "jedes")
    private val MONTHS = setOf("januar", "februar", "märz", "april", "mai", "juni", "juli", "august", "september", "oktober", "november", "dezember")

    /** [text] made easier for a voice of [lang]: the rules are German, other languages' text stays as it is. */
    fun fix(text: String, lang: String = "de"): String {
        if (lang != "de") return text
        var t = text
        for ((short, full) in PHRASES) t = short.replace(t, full)
        t = TIME.replace(t) { m -> m.groupValues[1].trimStart('0').ifEmpty { "0" } + " Uhr" + m.groupValues[2].trimStart('0').let { if (it.isEmpty()) "" else " $it" } }
        t = UNIT.replace(t) { m ->
            val (plural, one) = UNITS.first { it.first == m.groupValues[2] }.second
            m.groupValues[1] + " " + if (m.groupValues[1] == "1") one else plural
        }
        t = DECIMAL.replace(t) { m -> m.groupValues[1] + " Komma " + m.groupValues[2] }
        // only after an article or before a month: "Ausfahrt 25. Danach links" ends a sentence and stays
        t = ORDINAL.replace(t) { m ->
            val before = m.groupValues[1]
            val b = before.lowercase()
            val month = m.groupValues[3].lowercase() in MONTHS
            if (b !in EN_BEFORE && b !in E_BEFORE && !month) return@replace m.value
            val n = m.groupValues[2].toInt()
            // a date without article: "Montag, 3. Oktober" -> "Montag, der dritte Oktober"
            (if (before.isEmpty()) (if (month) "der " else "") else "$before ") + ordinal(n) + if (b in EN_BEFORE) "en" else "e"
        }
        t = ABBREVIATED.replace(t) { m -> m.groupValues[1] + "straße" }
        for ((short, full) in SHORT) t = short.replace(t, full)
        t = COMPOUND.replace(t) { m -> m.groupValues[1] + "-" + m.groupValues[2].replaceFirstChar { it.uppercase() } }
        return t
    }

    private val ONES = listOf("null", "ein", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun", "zehn",
        "elf", "zwölf", "dreizehn", "vierzehn", "fünfzehn", "sechzehn", "siebzehn", "achtzehn", "neunzehn")
    private val TENS = listOf("", "", "zwanzig", "dreißig", "vierzig", "fünfzig", "sechzig", "siebzig", "achtzig", "neunzig")

    /** [n] (0 to 9999) in German words as in a compound: "ein", "einundzwanzig", "zweihundertdrei". */
    fun cardinal(n: Int): String = when {
        n < 20 -> ONES[n]
        n < 100 -> (if (n % 10 == 0) "" else ONES[n % 10] + "und") + TENS[n / 10]
        n < 1000 -> ONES[n / 100] + "hundert" + (if (n % 100 == 0) "" else cardinal(n % 100))
        else -> ONES.getOrElse(n / 1000) { cardinal(n / 1000) } + "tausend" + (if (n % 1000 == 0) "" else cardinal(n % 1000))
    }

    /** The stem of the ordinal of [n], to which -e or -en is added: "zweit", "dritt", "einundzwanzigst". */
    fun ordinal(n: Int): String {
        val last = n % 100
        return when {
            n == 1 -> "erst"
            n == 3 -> "dritt"
            n == 7 -> "siebt"
            n == 8 -> "acht"
            n < 20 -> cardinal(n) + "t"
            last in 1..19 && n > 100 -> cardinal(n - last) + ordinal(last)   // "hundertzweit"
            else -> cardinal(n) + "st"
        }
    }
}
