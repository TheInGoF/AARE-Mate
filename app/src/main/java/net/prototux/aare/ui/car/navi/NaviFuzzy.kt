package net.prototux.aare.ui.car.navi

import kotlin.math.min

/**
 * Forgiving matching of what was typed (F6, O190, 16.11.2026: "wäre cool wenn die tastatur erkennt wenn man sich paar
 * mal vertippt hat. so elastic search / meili search mäßig"): letters swapped, a neighbour key on the German keyboard
 * (half a mistake), umlauts written plainly, a word not finished yet. Pure and small, for the driver's own places and
 * the names of towns.
 */
internal object NaviFuzzy {
    /** The cost of matching [query] against [text], null when it does not match. Lower is better, 0 is exact. */
    fun cost(query: String, text: String): Double? {
        val q = words(query); if (q.isEmpty()) return null
        val t = words(text); if (t.isEmpty()) return null
        var sum = 0.0
        val unmatched = ArrayList<String>()
        for (w in q) { val c = t.mapNotNull { wordCost(w, it) }.minOrNull(); if (c == null) unmatched += w else sum += c }
        if (unmatched.isEmpty()) return sum
        // System I-008: speech made "Lünen Höhe" of "Lime Home" - the words left over may sound like a run of the text's words
        if (!soundsLike(unmatched, t)) return null
        return sum + SOUND_COST * unmatched.size
    }

    /** Cost of each word matched by its sound only: behind any typo match, ahead of nothing. */
    const val SOUND_COST = 2.5

    /**
     * Whether [words] together sound like a run of one to three neighbouring [text] words (System I-008: "Eigennamen werden
     * falsch erkannt und in scheinbar sinnvolle Wörter umkorrigiert (Beispiel: „hamburg lime home" wurde zu „hamburg Lünen
     * Höhe")"): the Kölner Phonetik of both the same, at least three digits long so that short words do not match anything.
     */
    fun soundsLike(words: List<String>, text: List<String>): Boolean {
        val code = cologne(words.joinToString(""))
        if (code.length < 3) return false
        for (i in text.indices) for (n in 1..3) {
            if (i + n > text.size) break
            if (cologne(text.subList(i, i + n).joinToString("")) == code) return true
        }
        return false
    }

    /** Kölner Phonetik of a folded word (letters a-z): the code without its zeros past the first place. */
    fun cologne(w: String): String {
        val raw = StringBuilder()
        for (i in w.indices) {
            val c = w[i]; val prev = if (i > 0) w[i - 1] else ' '; val next = if (i + 1 < w.length) w[i + 1] else ' '
            val d = when (c) {
                'a', 'e', 'i', 'j', 'o', 'u', 'y' -> '0'
                'h' -> '-'
                'b' -> '1'
                'p' -> if (next == 'h') '3' else '1'
                'd', 't' -> if (next in "csz") '8' else '2'
                'f', 'v', 'w' -> '3'
                'g', 'k', 'q' -> '4'
                'c' -> if (i == 0) (if (next in "ahkloqrux") '4' else '8')
                       else if (prev in "sz") '8' else if (next in "ahkoqux") '4' else '8'
                'x' -> if (prev in "ckq") '8' else 'X'
                'l' -> '5'
                'm', 'n' -> '6'
                'r' -> '7'
                's', 'z' -> '8'
                else -> '-'
            }
            if (d == 'X') { raw.append('4'); raw.append('8') } else if (d != '-') raw.append(d)
        }
        val out = StringBuilder()
        for (i in raw.indices) if (i == 0 || raw[i] != raw[i - 1]) out.append(raw[i])
        return out.filterIndexed { i, ch -> i == 0 || ch != '0' }.toString()
    }

    /** One typed word against one word of the text: its beginning as far as typed, mistakes allowed by length. */
    fun wordCost(typed: String, word: String): Double? {
        if (word.startsWith(typed)) return 0.0
        val allowed = when { typed.length <= 3 -> 0.0; typed.length <= 5 -> 1.0; else -> 2.0 }
        if (allowed == 0.0) return null
        // the word as far as typed (one longer or shorter for a missed or doubled letter)
        val best = (typed.length - 1..typed.length + 1).filter { it in 1..word.length }
            .minOfOrNull { distance(typed, word.substring(0, it)) } ?: return null
        return best.takeIf { it <= allowed }
    }

    /** Damerau-Levenshtein, a neighbour key costing half. */
    fun distance(a: String, b: String): Double {
        val d = Array(a.length + 1) { DoubleArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i.toDouble()
        for (j in 0..b.length) d[0][j] = j.toDouble()
        for (i in 1..a.length) for (j in 1..b.length) {
            val sub = if (a[i - 1] == b[j - 1]) 0.0 else if (neighbours(a[i - 1], b[j - 1])) 0.5 else 1.0
            d[i][j] = min(min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + sub)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = min(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }

    fun words(s: String): List<String> = fold(s).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    private fun fold(s: String) = s.lowercase().replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss")
        .replace("æ", "ae").replace("ø", "o").replace("å", "a")

    private val ROWS = listOf("1234567890", "qwertzuiop", "asdfghjkl", "yxcvbnm")
    private val AT: Map<Char, Pair<Int, Int>> = ROWS.flatMapIndexed { r, row -> row.mapIndexed { c, ch -> ch to (r to c) } }.toMap()

    private fun neighbours(a: Char, b: Char): Boolean {
        val (ra, ca) = AT[a] ?: return false
        val (rb, cb) = AT[b] ?: return false
        return Math.abs(ra - rb) <= 1 && Math.abs(ca - cb) <= 1
    }
}
