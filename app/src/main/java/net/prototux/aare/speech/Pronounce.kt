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
    private val KEEP = emptySet<String>()

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

    fun fix(text: String): String {
        var t = ABBREVIATED.replace(text) { m -> m.groupValues[1] + "straße" }
        for ((short, full) in SHORT) t = short.replace(t, full)
        t = COMPOUND.replace(t) { m ->
            val whole = m.value
            if (whole in KEEP) whole else m.groupValues[1] + "-" + m.groupValues[2].replaceFirstChar { it.uppercase() }
        }
        return t
    }
}
