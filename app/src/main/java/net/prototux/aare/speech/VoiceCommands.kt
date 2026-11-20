package net.prototux.aare.speech

import org.json.JSONObject

/**
 * Commands by voice without an AI on the device (18.11.2026: "Es muss eine Befehlsdatenbank aufgebaut werden, damit Whisper
 * funktioniert ... der Befehl muss gesplittet werden und in seine Einzelteile ausgeführt"): what Whisper heard is split into
 * the command (a verb phrase at the start, the longest one wins), filler words around it, and the target - an app, a place,
 * a contact, music. The words live in a file per language (resources `befehle-<lang>.json`); an English one is only a
 * translation of that file.
 *
 * "Öffne die Podimo-App" -> OPEN_APP "podimo"; "Navigiere zu Hamburger Straße 17 in Berlin" -> NAVIGATE "hamburger straße 17
 * in berlin"; "Schreibe eine WhatsApp-Nachricht an Maria" -> MESSAGE "maria" via com.whatsapp.
 */
object VoiceCommands {
    enum class Kind { HOME, NAVIGATE, OPEN_APP, PLAY, CALL, MESSAGE, PAUSE, RESUME, NEXT, PREVIOUS, UNKNOWN }

    /** [target] the named thing; [app] for a message the messenger's package; [text] a message's words if said along. */
    data class Command(val kind: Kind, val target: String = "", val app: String? = null, val text: String? = null, val heard: String = "")

    class Grammar(json: String) {
        private val o = JSONObject(json)
        val lang: String = o.optString("lang", "de")
        val polite = list(o, "polite")
        val articles = list(o, "articles")
        val home = list(o, "home")
        val cancel = list(o, "cancel")
        val confirm = list(o, "confirm")
        val messengers: Map<String, List<String>> = o.optJSONObject("messengers")?.let { m -> m.keys().asSequence().associateWith { list(m, it) } }.orEmpty()
        val words: Map<String, String> = o.optJSONObject("words")?.let { w -> w.keys().asSequence().associateWith { w.getString(it) } }.orEmpty()

        class Cmd(val kind: Kind, val whole: List<String>, val verbs: List<String>, val lead: List<String>, val trail: List<String>,
                  val nouns: List<String>, val to: List<String>, val via: List<String>, val textMark: List<String>, val needsTarget: Boolean)

        val commands: List<Cmd> = o.getJSONArray("commands").let { a ->
            (0 until a.length()).map { i ->
                val c = a.getJSONObject(i)
                Cmd(Kind.valueOf(c.getString("kind")), list(c, "whole"), list(c, "verbs"), list(c, "lead"), list(c, "trail"),
                    list(c, "nouns"), list(c, "to"), list(c, "via"), list(c, "textMark"), c.optBoolean("needsTarget"))
            }
        }

        fun say(key: String, vararg args: Any): String = (words[key] ?: key).format(*args)

        private fun list(o: JSONObject, key: String): List<String> = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { norm(a.getString(it)) } }.orEmpty()
    }

    private val cache = HashMap<String, Grammar>()

    /** The command words of [lang], German when there is no file for it. */
    fun grammar(lang: String = java.util.Locale.getDefault().language): Grammar = synchronized(cache) {
        cache.getOrPut(lang) {
            val res = VoiceCommands::class.java.getResourceAsStream("befehle-$lang.json")
                ?: VoiceCommands::class.java.getResourceAsStream("befehle-de.json")!!
            Grammar(res.bufferedReader().use { it.readText() })
        }
    }

    /** Lower case, hyphens as spaces ("WhatsApp-Nachricht"), no punctuation, single spaces. */
    fun norm(s: String): String = s.lowercase()
        .replace(Regex("[-_/]"), " ")
        .replace(Regex("[.,!?;\"„“”'«»()]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    fun parse(heard: String, g: Grammar = grammar()): Command {
        // what follows a colon is a message's text ("Schreibe Maria: bin gleich da")
        val colon = heard.indexOf(':')
        val said = if (colon > 0) heard.substring(0, colon) else heard
        val afterColon = if (colon > 0) heard.substring(colon + 1).trim().ifBlank { null } else null
        var t = norm(said)
        t = stripPolite(t, g)
        if (t.isBlank()) return Command(Kind.UNKNOWN, heard = heard)
        g.commands.firstOrNull { c -> t in c.whole }?.let { return Command(it.kind, heard = heard) }
        // the verb phrase at the start: the longest of all commands
        var best: Pair<Grammar.Cmd, String>? = null
        for (c in g.commands) for (v in c.verbs) if ((t == v || t.startsWith("$v ")) && v.length > (best?.second?.length ?: 0)) best = c to v
        // or at the end ("Maria anrufen", "Spotify öffnen", "Wizo abspielen")
        if (best == null) for (c in g.commands) for (v in c.verbs) if (t.endsWith(" $v") && c.kind in END_VERBS && v.length > (best?.second?.length ?: 0)) best = c to v
        val (cmd, verb) = best ?: return Command(Kind.UNKNOWN, heard = heard)
        val rest = (if (t.startsWith(verb)) t.removePrefix(verb) else t.removeSuffix(verb)).trim()
        if (cmd.kind == Kind.MESSAGE) return message(rest, cmd, g, heard, afterColon)
        var target = stripLead(rest, cmd.lead + if (cmd.kind == Kind.OPEN_APP) g.articles else emptyList())
        target = stripTrail(target, cmd.trail)
        if (cmd.kind == Kind.OPEN_APP) target = stripLead(target, g.articles)
        if (cmd.kind == Kind.NAVIGATE && (target in g.home || target.isEmpty() && t in g.home)) return Command(Kind.HOME, heard = heard)
        if (cmd.needsTarget && target.isBlank()) return Command(Kind.UNKNOWN, heard = heard)
        return Command(cmd.kind, target, heard = heard)
    }

    /** "eine WhatsApp-Nachricht an Maria", "eine Nachricht über Signal an Maria, dass …", "Maria eine Element X Nachricht". */
    private fun message(rest0: String, c: Grammar.Cmd, g: Grammar, heard: String, afterColon: String?): Command {
        var rest = stripLead(rest0, c.lead)
        var app: String? = null
        var name = ""
        var text: String? = afterColon
        fun messenger(s: String): Pair<String, String>? {
            var hit: Pair<String, String>? = null
            for ((pkg, names) in g.messengers) for (n in names) if ((s == n || s.startsWith("$n ")) && n.length > (hit?.second?.length ?: 0)) hit = pkg to n
            return hit
        }
        var guard = 0
        while (rest.isNotEmpty() && guard++ < 12) {
            val art = g.articles.firstOrNull { rest == it || rest.startsWith("$it ") }
            if (art != null) { rest = rest.removePrefix(art).trim(); continue }
            val m = messenger(rest)
            if (m != null && app == null) { app = m.first; rest = rest.removePrefix(m.second).trim(); continue }
            val noun = c.nouns.firstOrNull { rest == it || rest.startsWith("$it ") }
            if (noun != null) { rest = rest.removePrefix(noun).trim(); continue }
            val via = c.via.firstOrNull { rest.startsWith("$it ") && messenger(rest.removePrefix(it).trim()) != null }
            if (via != null) { rest = rest.removePrefix(via).trim(); continue }
            val to = c.to.firstOrNull { rest.startsWith("$it ") }
            if (to != null || name.isEmpty()) {
                // the name: up to a text mark, a messenger said after it, or an article that starts "eine Nachricht"
                rest = if (to != null) rest.removePrefix(to).trim() else rest
                val words = rest.split(" ")
                var end = words.size
                for (i in words.indices) {
                    val tail = words.drop(i).joinToString(" ")
                    if (i > 0 && (c.textMark.any { tail == it || tail.startsWith("$it ") } ||
                            c.via.any { tail.startsWith("$it ") && messenger(tail.removePrefix(it).trim()) != null } ||
                            (to == null && g.articles.any { tail.startsWith("$it ") }))) { end = i; break }
                }
                name = words.take(end).joinToString(" ")
                rest = words.drop(end).joinToString(" ")
                continue
            }
            val mark = c.textMark.firstOrNull { rest == it || rest.startsWith("$it ") }
            if (mark != null) { text = text ?: rest.removePrefix(mark).trim().ifBlank { null }; rest = ""; continue }
            break
        }
        name = stripTrail(name, listOf("bitte"))
        if (name.isBlank()) return Command(Kind.UNKNOWN, heard = heard)
        return Command(Kind.MESSAGE, name, app, text, heard)
    }

    private val END_VERBS = setOf(Kind.CALL, Kind.OPEN_APP, Kind.PLAY)

    private fun stripPolite(t0: String, g: Grammar): String {
        var t = t0
        var changed = true
        while (changed) {
            changed = false
            for (p in g.polite.sortedByDescending { it.length }) {
                if (t.startsWith("$p ")) { t = t.removePrefix(p).trim(); changed = true }
                if (t.endsWith(" $p")) { t = t.removeSuffix(p).trim(); changed = true }
            }
        }
        return t
    }

    private fun stripLead(t0: String, words: List<String>): String {
        var t = t0
        val sorted = words.sortedByDescending { it.length }
        var changed = true
        while (changed) {
            changed = false
            for (w in sorted) if (t == w || t.startsWith("$w ")) { t = t.removePrefix(w).trim(); changed = true; break }
        }
        return t
    }

    private fun stripTrail(t0: String, words: List<String>): String {
        var t = t0
        val sorted = words.sortedByDescending { it.length }
        var changed = true
        while (changed) {
            changed = false
            for (w in sorted) if (t == w || t.endsWith(" $w")) { t = t.removeSuffix(w).trim(); changed = true; break }
        }
        return t
    }
}
