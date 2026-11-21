package net.prototux.aare.ui.car.navi

/**
 * Valhalla's spoken instructions per language: how they are smoothed for the voice ([Rules.polish]), shortened to the
 * turn for "Kurz" ([Rules.brief]) and told apart from a mere "go on" ([Rules.goOnOnly]). Valhalla speaks the language
 * Mate asks for (Language.tag); a new language brings its own rules here, the base (English) answers for any other.
 */
internal object NaviPhrases {
    interface Rules {
        fun polish(text: String): String
        fun brief(text: String): String
        fun goOnOnly(text: String): Boolean
    }

    fun of(lang: String): Rules = when (lang) {
        "de" -> German
        else -> English
    }

    /** What starts with a capital or a digit: a road's name, a number, a place. */
    private const val NAME = """[\p{Lu}0-9][\p{L}0-9.\-/]*"""
    private val SPACE_BEFORE_MARK = Regex("\\s+([.,!?])")

    object German : Rules {
        /**
         * Road names and destinations out of a turn: "Rechts auf Baumstraße abbiegen." -> "Rechts abbiegen.",
         * "Ausfahrt 62 in Richtung Hamburg, Sangerhausen nehmen." -> "Ausfahrt 62 nehmen." - a name after "auf",
         * "Richtung", "in Richtung" or "über"; "auf der rechten Seite" stays.
         */
        private val NAMED = Regex("""\s+(?:in Richtung|Richtung|auf|über)\s+(?:die |den |das )?$NAME(?:(?:,\s*|\s+)$NAME)*""")
        /** Turn words: an instruction without any only says to go on. */
        private val TURN = Regex("""(?i)abbieg|links|rechts|ausfahrt|abfahr|auffahr|kreisverkehr|kreisel|wenden|halten|einfädeln|fähre|ziel|spur|gabelung""")
        private val AFTER_DISTANCE = Regex("^(In [0-9.,]+ (?:Metern|Kilometern)),? (\\p{Lu}\\p{L}*)")
        private val SMALL_WORDS = setOf("Leicht", "Links", "Rechts", "Scharf", "Auf", "In", "An", "Am", "Geradeaus", "Wenden", "Halten", "Dem", "Der", "Die", "Den", "Das", "Bei", "Über", "Nach")
        private val DIRECTION = Regex("\\b(nach|Dann|dann) (Links|Rechts)\\b")
        /** "Dann, in 70 Meter, Rechts abbiegen" (17.11.2026): a direction word after a comma is no sentence start */
        private val AFTER_COMMA = Regex(", (Links|Rechts|Leicht|Scharf|Geradeaus|Wenden)\\b")

        override fun goOnOnly(text: String): Boolean = !TURN.containsMatchIn(text)

        override fun brief(text: String): String = NAMED.replace(text, "").replace(SPACE_BEFORE_MARK, "$1").trim()

        /**
         * As one would say it: "In 300 Metern, Leicht nach links …" becomes "In 300 Metern leicht nach links …",
         * "nach Rechts" becomes "nach rechts", "Dann Links" becomes "Dann links"; also without the comma
         * ("In 80 Metern Das Ziel befindet sich …", 16.11.2026).
         */
        override fun polish(text: String): String {
            var t = text.trim()
            t = AFTER_DISTANCE.replace(t) { m ->
                val w = m.groupValues[2]
                m.groupValues[1] + " " + if (w in SMALL_WORDS) w.lowercase() else w
            }
            t = DIRECTION.replace(t) { m -> m.groupValues[1] + " " + m.groupValues[2].lowercase() }
            t = AFTER_COMMA.replace(t) { m -> ", " + m.groupValues[1].lowercase() }
            // the last words lack the article: "Ziel befindet sich auf der rechten Seite."
            if (t.startsWith("Ziel ")) t = "Das $t"
            return t
        }
    }

    object English : Rules {
        /**
         * "Turn right onto Main Street." -> "Turn right.", "Take exit 62 toward Hamburg, Sangerhausen." -> "Take exit 62."
         * - a name after "onto", "toward(s)", "on" or "via"; "on the right" stays.
         */
        // the name ends the sentence in English ("onto Main Street."): its words without the full stop
        private const val WORD = """[\p{Lu}0-9][\p{L}0-9\-/]*"""
        private val NAMED = Regex("""\s+(?:onto|towards|toward|on|via)\s+(?:the )?$WORD(?:(?:,\s*|\s+)$WORD)*""")
        private val TURN = Regex("""(?i)\b(?:turn|left|right|exit|ramp|roundabout|rotary|u-turn|merge|ferry|destination|arrive|lane|fork|keep|bear)""")

        override fun goOnOnly(text: String): Boolean = !TURN.containsMatchIn(text)

        override fun brief(text: String): String = NAMED.replace(text, "").replace(SPACE_BEFORE_MARK, "$1").trim()

        /** Valhalla's English reads as it is said. */
        override fun polish(text: String): String = text.trim()
    }
}
