package net.prototux.aare.ui.car.navi

import android.content.Context

/**
 * The towns of Germany and Denmark (assets/navi/orte.tsv, projekt/werkzeuge/orte.py from OpenStreetMap) to set a mistyped
 * town right before the map server is asked (F6 step 2, O190: "hambrug" found Delft, 16.11.2026). A word is set right
 * only when no town begins with it and one is within the typo rules of [NaviFuzzy] and starts with the same letter
 * (typos are rarely in the first one - "Tesla" became "Eslarn" without that rule); of equals the biggest town.
 */
internal object NaviTowns {
    class Town(val name: String, val people: Int) { val words = NaviFuzzy.words(name) }

    @Volatile private var towns: List<Town>? = null

    private fun towns(context: Context): List<Town> = towns ?: synchronized(this) {
        towns ?: context.assets.open("navi/orte.tsv").bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { l ->
                val f = l.split('\t'); if (f.isEmpty()) null else Town(f[0], f.getOrNull(1)?.toIntOrNull() ?: 0)
            }.toList()
        }.also { towns = it }
    }

    /** [query] with a mistyped town set right, or null when nothing needs it. */
    fun correct(context: Context, query: String): String? = correct(towns(context), query)

    fun correct(towns: List<Town>, query: String): String? {
        val typed = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var changed = false
        val out = typed.map { raw ->
            val w = NaviFuzzy.words(raw).singleOrNull() ?: return@map raw
            if (w.length < 4 || w in COMMON || towns.any { t -> t.words.any { it.startsWith(w) } }) return@map raw
            val best = towns.flatMap { t -> t.words.filter { it.length >= 4 && it[0] == w[0] }.mapNotNull { tw -> NaviFuzzy.wordCost(w, tw)?.let { Triple(t, tw, it) } } }
                .minWithOrNull(compareBy<Triple<Town, String, Double>> { it.third }.thenByDescending { it.first.people })
                ?: return@map raw
            changed = true
            // the town's own word as it is written
            best.first.name.split(Regex("[\\s-]+")).firstOrNull { NaviFuzzy.words(it).singleOrNull() == best.second } ?: best.first.name
        }
        return if (changed) out.joinToString(" ") else null
    }

    /** Words of streets and places, never set right into a town ("Straße" is near "Strasburg"). */
    private val COMMON = setOf("strasse", "str", "platz", "weg", "allee", "ring", "damm", "ufer", "chaussee", "gasse", "steig",
        "bahnhof", "hauptbahnhof", "flughafen", "markt", "brucke", "park", "center", "zentrum", "kirche", "schule", "hotel",
        "parkhaus", "tankstelle", "rastplatz", "raststatte", "ladesaule", "vej", "gade", "alle", "torv")
}
