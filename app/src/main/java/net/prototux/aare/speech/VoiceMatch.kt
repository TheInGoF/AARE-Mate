package net.prototux.aare.speech

/**
 * Finds what was meant in a spoken music wish (O56): the recognized words are compared with the names in the user's
 * own collection - artists and albums from Plex - not with a dictionary. So "Spiele wie zu" finds the band Wizo:
 * every run of words in the sentence is compared with every name, by spelling (edit distance) and by sound
 * (Kölner Phonetik, made for German: "wie zu" and "Wizo" both sound 38).
 */
object VoiceMatch {
    enum class Kind { ARTIST, ALBUM, PLAYLIST }

    class Name(val kind: Kind, val key: String, val name: String, val artist: String = "")

    class Hit(val name: Name, val score: Double) {
        override fun toString() = "${name.kind} ${name.name} %.2f".format(score)
    }

    /** Words that ask for music rather than name it: a run of words may not begin or end with one. */
    private val COMMAND = setOf("spiel", "spiele", "spielt", "abspielen", "bitte", "mal", "musik", "von", "vom", "album", "alben",
        "lied", "lieder", "song", "songs", "titel", "etwas", "ich", "will", "moechte", "hoeren", "hoere", "play", "by", "mit", "und",
        "playlist", "liste", "wiedergabeliste", "zufaellig", "gemischt")
    private val ARTICLE = setOf("die", "der", "das", "den", "dem", "the")

    /** The best artist and the best album for [text] (either may be null). */
    fun find(text: String, names: List<Name>): Pair<Hit?, Hit?> {
        val words = text.split(Regex("[^\\p{L}\\p{N}]+")).map { fold(it) }.filter { it.isNotEmpty() }
        val spans = ArrayList<String>()
        for (i in words.indices) for (j in i until minOf(words.size, i + 6)) {
            if (words[i] in COMMAND || words[j] in COMMAND) continue
            if (i == j && words[i] in ARTICLE) continue
            spans += words.subList(i, j + 1).joinToString(" ")
        }
        val albumWanted = "album" in words || "platte" in words
        val listWanted = "playlist" in words || "liste" in words || "wiedergabeliste" in words
        var artist: Hit? = null
        var album: Hit? = null
        var list: Hit? = null
        // a name is also known by its main title, before a subtitle ("Learning English: Lesson 1")
        val prepared = names.flatMap { n ->
            val main = n.name.split(Regex(":| - |\\(|\\[")).first().trim()
            if (main.length >= 3 && main != n.name) listOf(n to Prepared(n.name), n to Prepared(main)) else listOf(n to Prepared(n.name))
        }
        for (span in spans) {
            val s = Prepared(span)
            for ((n, p) in prepared) {
                var sc = score(s, p)
                if (n.kind == Kind.ALBUM && albumWanted) sc += 0.15
                if (n.kind == Kind.PLAYLIST && listWanted) sc += 0.15
                when (n.kind) {
                    Kind.ARTIST -> if (artist == null || sc > artist.score) artist = Hit(n, sc)
                    Kind.ALBUM -> if (album == null || sc > album.score) album = Hit(n, sc)
                    Kind.PLAYLIST -> if (list == null || sc > list.score) list = Hit(n, sc)
                }
            }
        }
        lastList = list
        return artist to album
    }

    /** The best playlist of the last [find] (kept apart so that callers of the artist/album pair stay as they are). */
    @Volatile var lastList: Hit? = null
        private set

    /** What to play, playlists included: a playlist wins when it fits clearly better than the rest. */
    fun pick(text: String, names: List<Name>): Hit? {
        val found = find(text, names)
        val other = choose(found)
        val list = lastList?.takeIf { it.score >= 0.8 }
        return if (list != null && (other == null || list.score > other.score)) list else other
    }

    /** How alike two titles are (0..1), spelling only: for choosing among a server's title search results. */
    fun similarity(a: String, b: String): Double {
        val x = fold(a).replace(" ", ""); val y = fold(b).replace(" ", "")
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return similar(x, y)
    }

    /** The words that name something (command words left out), for a title search at the server. */
    fun query(text: String): String =
        text.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() && fold(it) !in COMMAND }.joinToString(" ")

    /** What to play: the album when it fits at least as well as an artist, else the artist; null if nothing fits. */
    fun choose(found: Pair<Hit?, Hit?>): Hit? {
        val (artist, album) = found
        val ar = artist?.takeIf { it.score >= 0.75 }
        val al = album?.takeIf { it.score >= 0.8 }
        // an album of the very artist that was heard wins even when only part of its title came through
        // ("Jenseits von … den Toten Hosen": the album, not all of the band)
        val ofThatArtist = artist != null && album != null && album.score >= 0.7 &&
            fold(album.name.artist).let { it.isNotEmpty() && it == fold(artist.name.name) }
        return when {
            ofThatArtist && ar != null -> album
            al != null && (ar == null || al.score >= ar.score) -> al
            else -> ar
        }
    }

    private class Prepared(text: String) {
        val joined = fold(text).replace(" ", "")
        val bare = fold(text).split(' ').let { w -> if (w.size > 1 && w[0] in ARTICLE) w.drop(1) else w }.joinToString("")
        val sound = koelner(bare)
    }

    private fun score(s: Prepared, n: Prepared): Double {
        if (s.bare.isEmpty() || n.bare.isEmpty()) return 0.0
        var best = maxOf(similar(s.joined, n.joined), similar(s.bare, n.bare))
        // same sound counts nearly as same spelling, for names long enough to have a sound of their own
        // (short sound codes are blunt - "vico" and "voyage" both sound 34 - so the spelling must be half right too)
        if (s.sound.length >= 2 && s.sound == n.sound && n.bare.length >= 3 && best >= 0.5) best = maxOf(best, 0.9)
        // very short names must match exactly
        if (n.bare.length <= 2 && s.bare != n.bare) best = minOf(best, 0.5)
        return best
    }

    private fun similar(a: String, b: String): Double {
        if (a == b) return 1.0
        val d = levenshtein(a, b)
        return 1.0 - d.toDouble() / maxOf(a.length, b.length)
    }

    fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Lower case, umlauts and accents folded, only letters, digits and single spaces. */
    fun fold(s: String): String {
        val sb = StringBuilder()
        for (c in s.lowercase()) when (c) {
            'ä' -> sb.append("ae"); 'ö' -> sb.append("oe"); 'ü' -> sb.append("ue"); 'ß' -> sb.append("ss")
            else -> {
                val base = java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
                for (b in base) if (b.isLetterOrDigit()) sb.append(b) else if (b.isWhitespace() && sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
            }
        }
        return sb.toString().trim()
    }

    /** Kölner Phonetik: the sound of a German word as digits. */
    fun koelner(word: String): String {
        val w = word.lowercase().replace("ae", "a").replace("oe", "o").replace("ue", "u").filter { it in 'a'..'z' }
        val raw = StringBuilder()
        for (i in w.indices) {
            val c = w[i]; val p = if (i > 0) w[i - 1] else ' '; val n = if (i + 1 < w.length) w[i + 1] else ' '
            raw.append(when (c) {
                'a', 'e', 'i', 'j', 'o', 'u', 'y' -> "0"
                'h' -> ""
                'b' -> "1"
                'p' -> if (n == 'h') "3" else "1"
                'd', 't' -> if (n in "csz") "8" else "2"
                'f', 'v', 'w' -> "3"
                'g', 'k', 'q' -> "4"
                'c' -> if (i == 0) (if (n in "ahkloqrux") "4" else "8") else if (n in "ahkoqux" && p !in "sz") "4" else "8"
                'x' -> if (p in "ckq") "8" else "48"
                'l' -> "5"
                'm', 'n' -> "6"
                'r' -> "7"
                's', 'z' -> "8"
                else -> ""
            })
        }
        val out = StringBuilder()
        for (ch in raw) if (out.isEmpty() || out.last() != ch) out.append(ch)
        return out.toString().let { if (it.isEmpty()) it else it[0] + it.substring(1).replace("0", "") }
    }
}
