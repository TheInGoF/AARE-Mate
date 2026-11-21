package net.prototux.aare.car

/**
 * Text for the car's small head-up display (17.11.2026, photo: "B 93: Zw…" - "Das ist ein kleines Head-up-Display, da
 * passt nicht viel Text rein ... da sind ungefähr 6, 7 Ziffern Platz. Vielleicht schalten wir die durch"): a road or
 * instruction cut into pieces of at most [MAX] characters - the road's number first ("B 93", "A 72", "Ausf. 25"), then
 * the places one by one, a longer word shortened with a dot - shown one after the other, [STEP_MS] each.
 */
object HudText {
    const val MAX = 9
    const val STEP_MS = 3_000L
    private val NUMBER = Regex("""^(?:[ABLKS]|E|St|Kr)\s?\d+[a-z]?$""")

    /** "Exit 25" in the words of [w] (the language library), English where there are none. */
    private fun exitShort(n: String, w: HudWords?) = (w?.exitShort ?: "Exit %s").format(n)

    /** The pieces of [text], in the order to show them; [text] itself when it fits. */
    fun pieces(text: String, w: HudWords? = null): List<String> {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.length <= MAX) return listOfNotNull(t.ifEmpty { null })
        val out = ArrayList<String>()
        // "Ausfahrt 25" / "Abfahrt 25" -> "Ausf. 25" (the library's short form)
        val parts = t.split(Regex("\\s*(?:[:,;/·|]|\\s-\\s|\\s–\\s)\\s*")).map { it.trim() }.filter { it.isNotEmpty() }
        for (p in parts) {
            val exit = Regex("""^(?:Ausfahrt|Abfahrt|Exit)\s+(\S+)""", RegexOption.IGNORE_CASE).find(p)
            when {
                exit != null -> out += exitShort(exit.groupValues[1], w)
                p.length <= MAX -> out += p
                NUMBER.matches(p) -> out += p
                else -> {
                    // word by word, short words kept together ("Bad Elster")
                    var cur = ""
                    for (w in p.split(' ')) {
                        val word = if (w.length > MAX) w.take(MAX - 1) + "." else w
                        cur = when {
                            cur.isEmpty() -> word
                            cur.length + 1 + word.length <= MAX -> "$cur $word"
                            else -> { out += cur; word }
                        }
                    }
                    if (cur.isNotEmpty()) out += cur
                }
            }
        }
        return out.distinct()
    }

    private val REF = Regex("""(?<![\p{L}\d])(A|B|L|K|S|St|Kr|E)\s?(\d{1,4}[a-z]?)(?![\p{L}\d])""")
    private val EXIT = Regex("""(?:Ausfahrt|Abfahrt|Anschlussstelle|Exit)\s+(\d+[a-z]?)""", RegexOption.IGNORE_CASE)

    /**
     * The road numbers and exit numbers in [text], as on the signs ("B 75", "A 24", "Ausf. 25"), without the names of
     * streets and places (18.11.2026: "die Ausfahrt wird mit B75 ... gekennzeichnet, aber da steht dann nur ZW ... dass
     * wir da gar keine Straßennamen machen, sondern einfach nur sagen, rechts abbiegen auf die B75, auffahren auf die A24").
     */
    fun refs(text: String, w: HudWords? = null): List<String> {
        val out = ArrayList<String>()
        EXIT.findAll(text).forEach { out += exitShort(it.groupValues[1], w) }
        REF.findAll(text).forEach { out += it.groupValues[1] + " " + it.groupValues[2] }
        return out.distinct().filter { it.length <= MAX }
    }

    /**
     * The head-up display's line, no names in it (18.11.2026): the turn in words and the road's number. Far from the turn
     * ([far]) both take turns ("rechts ab", "B 75"); near it one stays, steady, so the car can count down: the number
     * where there is one, else the words. Empty where neither says anything (the arrow and the distance alone).
     */
    fun line(words: String?, road: String, far: Boolean, w: HudWords? = null): List<String> {
        val refs = refs(road, w).take(2)
        if (!far) return listOfNotNull(refs.firstOrNull() ?: words)
        return (listOfNotNull(words) + refs).distinct()
    }

    /**
     * The turn in words from a phone app's instruction (Google Maps says no maneuver), German or English, in the words of
     * [w] (the language library); null when unknown or without words.
     */
    fun wordsIn(instruction: String, w: HudWords?): String? {
        if (w == null) return null
        val t = instruction.lowercase()
        val left = Regex("\\b(links|left)\\b").containsMatchIn(t)
        val right = Regex("\\b(rechts|right)\\b").containsMatchIn(t)
        return when {
            "kreisverkehr" in t || "roundabout" in t ->
                Regex("(\\d{1,2})(?:\\.|st|nd|rd|th)\\s*(?:ausfahrt|exit)").find(t)?.let { w.roundaboutExit.format(it.groupValues[1].toInt()) } ?: w.roundabout
            "wenden" in t || "u-turn" in t || "make a u" in t -> w.uTurn
            "ausfahrt" in t || "abfahren" in t || Regex("\\b(take|use) (the )?exit\\b|\\bexit\\b").containsMatchIn(t) -> w.offRamp
            "auffahrt" in t || "auffahren" in t || "ramp" in t || "merge onto" in t -> w.onRamp
            "halten" in t || "keep" in t -> if (left) w.keepLeft else if (right) w.keepRight else null
            "abbiegen" in t || "biegen" in t || "turn" in t -> if (left) w.left else if (right) w.right else null
            "ziel" in t || "destination" in t || "arrive" in t -> w.destination
            else -> null
        }
    }

    /** The piece of [pieces] to show at [nowMs] (they take turns every [STEP_MS]). */
    fun at(pieces: List<String>, nowMs: Long): String = if (pieces.isEmpty()) "" else pieces[((nowMs / STEP_MS) % pieces.size).toInt()]

    /** The piece to show at [nowMs] (they take turns every [STEP_MS]). */
    fun at(text: String, nowMs: Long): String {
        val p = pieces(text)
        return if (p.isEmpty()) "" else p[((nowMs / STEP_MS) % p.size).toInt()]
    }
}
