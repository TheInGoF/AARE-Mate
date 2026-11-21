package net.prototux.aare

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The language library: every text Mate shows or says stands in res/values/strings.xml (English, the base) and once more
 * in every res/values-<language>/strings.xml (German first). A change to a text is made only there; this test holds the
 * languages level: the same entries (same count), the same placeholders in each, the same number of list items.
 * Entries marked translatable="false" (names) stand in the base only.
 */
class StringsParityTest {

    private class Entry(val kind: String, val placeholders: List<String>, val items: Int)

    private val res = File("src/main/res")
    private val placeholder = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfxXc]""")

    private fun read(file: File): Pair<Map<String, Entry>, Set<String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val entries = LinkedHashMap<String, Entry>()
        val fixed = HashSet<String>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            if (e.tagName !in setOf("string", "plurals", "string-array")) continue
            val name = e.getAttribute("name")
            if (e.getAttribute("translatable") == "false") { fixed += name; continue }
            val texts = if (e.tagName == "string") listOf(e.textContent)
            else (0 until e.getElementsByTagName("item").length).map { e.getElementsByTagName("item").item(it).textContent }
            val marks = texts.flatMap { t -> placeholder.findAll(t.replace("%%", "")).map { it.value } }
            entries[name] = Entry(
                e.tagName,
                if (e.tagName == "plurals") marks.distinct().sorted() else marks.sorted(),
                if (e.tagName == "string-array") texts.size else 0,
            )
        }
        return entries to fixed
    }

    @Test
    fun everyLanguageHasEveryEntry() {
        val (base, fixed) = read(File(res, "values/strings.xml"))
        val languages = res.listFiles { f -> f.isDirectory && Regex("""values-[a-z]{2}(-r[A-Z]{2})?""").matches(f.name) }
            .orEmpty().filter { File(it, "strings.xml").exists() }.sortedBy { it.name }
        assertTrue("no language besides the base", languages.isNotEmpty())
        val problems = mutableListOf<String>()
        println("language library: base (en) ${base.size} entries, ${fixed.size} not translated")
        for (dir in languages) {
            val lang = dir.name.removePrefix("values-")
            val (other, otherFixed) = read(File(dir, "strings.xml"))
            println("  $lang: ${other.size} entries")
            (base.keys - other.keys).forEach { problems += "$lang: missing \"$it\"" }
            (other.keys - base.keys - fixed).forEach { problems += "$lang: \"$it\" not in the base (English)" }
            (other.keys intersect fixed).forEach { problems += "$lang: \"$it\" is marked not to translate" }
            otherFixed.forEach { problems += "$lang: \"$it\" marked translatable=false belongs in the base only" }
            for ((name, b) in base) {
                val o = other[name] ?: continue
                if (o.kind != b.kind) problems += "$lang: \"$name\" is ${o.kind}, in English ${b.kind}"
                if (o.placeholders != b.placeholders) problems += "$lang: \"$name\" placeholders ${o.placeholders}, in English ${b.placeholders}"
                if (o.items != b.items) problems += "$lang: \"$name\" has ${o.items} items, in English ${b.items}"
            }
        }
        assertTrue("language library not level:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
