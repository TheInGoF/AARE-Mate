package net.prototux.aare.debug

/**
 * The log for a public issue (20.11.2026: "Aber wenn wir publik gehen sollten. Dann darf der log keine privaten Infos
 * enthalten. Also brauchen wir noch ein Button für issue log speichern."): every log line's text without what points to
 * the driver - positions, addresses and places, names of people, networks and devices, addresses on the network,
 * phone numbers, the car's VIN, tokens and server addresses. What the line says about the app stays.
 *
 * It works by patterns plus the values Mate knows to be private ([known]: the map server, its token, the Bluetooth
 * address, the own places ...), so it errs on the side of removing: anything in quotes goes, because that is where
 * Mate logs names, roads, SSIDs and dictated text. It is still worth a look before posting.
 */
object LogScrub {
    private const val MIN_KNOWN = 4

    private val URL = Regex("""(?i)\b[a-z][a-z0-9+.\-]*://\S+""")
    private val EMAIL = Regex("""(?i)\b[a-z0-9._%+\-]+@[a-z0-9.\-]+\.[a-z]{2,}\b""")
    private val SECRET = Regex("""(?i)\b(bearer|token|key|pass(?:word)?|secret|code)([=:]\s*|\s+)[A-Za-z0-9._~+/=\-]{6,}""")
    private val MAC = Regex("""(?i)\b([0-9a-f]{2}[:\-]){5}[0-9a-f]{2}\b""")
    private val MAC_TAIL = Regex("""(?i)(…|\.\.\.)[0-9a-f]{2}(:[0-9a-f]{2})+""")
    private val IPV4 = Regex("""\b\d{1,3}(\.\d{1,3}){3}(:\d+)?\b""")
    private val IPV6 = Regex("""(?i)\b(?=[0-9a-f:]*[a-f])([0-9a-f]{1,4}:){3,7}[0-9a-f]{1,4}\b|\b(?=[0-9a-f:]*::)[0-9a-f]{1,4}(:[0-9a-f]{0,4}){2,7}""")
    private val HOST = Regex("""(?i)\b([a-z0-9\-]+\.)+(ts\.net|com|net|org|de|eu|io|lan|local|cloud)\b""")
    private val COORD = Regex("""-?\b\d{1,3}\.\d{4,}\b""")
    private val PHONE = Regex("""(?<![\w.:])(\+|00)\d[\d \-/]{6,}\d|(?<![\w.:])0\d{2,5}[ \-/]?\d{4,}""")
    private val VIN = Regex("""\b(?=[A-HJ-NPR-Z0-9]*[A-HJ-NPR-Z])(?=[A-HJ-NPR-Z0-9]*\d)[A-HJ-NPR-Z0-9]{17}\b""")
    private val LONG_ID = Regex("""\b[A-Za-z0-9_\-]{32,}\b""")
    private val QUOTED = Regex("\"[^\"]+\"|„[^“\"]+[“\"]|“[^”]+”|«[^»]+»")

    /** Lines that carry a name without quotes: the part after the colon goes. */
    private val NAMED = listOf(
        Regex("""^(driving: auto-replied to ).*"""),
        Regex("""^(charge place learned: |charge rated: |charge visit removed by the driver: ).*"""),
        Regex("""^(wireless: ).*?( offers| connected)"""),
        Regex("""^(car bluetooth: )(?:paired with|connected to|bonded with) .*"""),
    )

    fun scrub(text: String, known: Collection<String> = emptyList()): String {
        var t = text
        known.filter { it.length >= MIN_KNOWN }.sortedByDescending { it.length }.forEach { k ->
            t = t.replace(k, "<private>", ignoreCase = true)
        }
        NAMED.forEach { r ->
            t = r.replace(t) { m -> m.groupValues[1] + "<name>" + (m.groupValues.getOrNull(2) ?: "") }
        }
        t = URL.replace(t, "<url>")
        t = EMAIL.replace(t, "<mail>")
        t = SECRET.replace(t) { m -> m.groupValues[1] + m.groupValues[2] + "<secret>" }
        t = MAC.replace(t, "<mac>")
        t = MAC_TAIL.replace(t, "…<mac>")
        t = IPV4.replace(t, "<ip>")
        t = IPV6.replace(t, "<ip>")
        t = HOST.replace(t, "<host>")
        t = VIN.replace(t, "<vin>")
        t = LONG_ID.replace(t, "<id>")
        t = COORD.replace(t, "<pos>")
        t = PHONE.replace(t, "<phone>")
        t = QUOTED.replace(t, "\"<text>\"")
        return t
    }
}
