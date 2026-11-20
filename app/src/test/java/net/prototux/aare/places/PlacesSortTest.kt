package net.prototux.aare.places

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Navi L-030: a list by use (the last 30 days by how often, then by when last, never visited last from A to Z), A to Z or
 * by distance; the pinned ones always first (O201). Before, the car showed the three most visited of 90 days and then
 * all from A to Z (PlacesForCarTest).
 */
class PlacesSortTest {
    private val now = 1_800_000_000_000L
    private val day = 24 * 3600_000L
    private fun p(id: String, name: String, visitsDaysAgo: List<Int> = emptyList(), pinned: Boolean = false, lat: Double = 50.0, lon: Double = 12.0) =
        Places.Place(id, "c", name, "", lat, lon, visitsDaysAgo.map { now - it * day }, pinned)

    private val book = Places.Book(emptyList(), listOf(
        p("a", "Zahnarzt", pinned = true, lat = 50.30),
        p("b", "Arbeit", listOf(1, 2, 3, 4, 5), pinned = true, lat = 50.01),
        p("c", "Bäcker", listOf(1, 2, 3, 40, 41, 42, 43, 44), lat = 50.05),     // 3 in 30 days
        p("d", "Kita", listOf(1, 2, 3, 4), lat = 50.20),                        // 4 in 30 days
        p("e", "Oma", listOf(35), lat = 50.02),                                 // last 35 days ago
        p("f", "Ostsee", listOf(200), lat = 54.0),                              // last 200 days ago
        p("g", "Alpen", lat = 47.5),                                            // never
        p("h", "Baumarkt", listOf(2), lat = 50.10),                             // 1 in 30 days, the latest of equals
        p("i", "Apotheke", listOf(8), lat = 50.11),                             // 1 in 30 days, older
        p("x", "Fremd", listOf(1)).copy(category = "other"),
    ))

    @Test fun byUse() {
        val s = Places.sorted(book, "c", Places.Order.USE, now = now)
        assertEquals(listOf("Arbeit", "Zahnarzt"), s.pinned.map { it.name })
        // 4, 3 visits in 30 days; then 1 each, the latest first; then by last visit; never visited last
        assertEquals(listOf("Kita", "Bäcker", "Baumarkt", "Apotheke", "Oma", "Ostsee", "Alpen"), s.others.map { it.name })
        assertEquals(Places.Order.USE, s.order)
    }

    @Test fun byNameAndByDistance() {
        assertEquals(listOf("Alpen", "Apotheke", "Bäcker", "Baumarkt", "Kita", "Oma", "Ostsee"),
            Places.sorted(book, "c", Places.Order.NAME, now = now, locale = java.util.Locale.GERMAN).others.map { it.name })
        val far = Places.sorted(book, "c", Places.Order.DISTANCE, 50.0 to 12.0, now)
        assertEquals(listOf("Oma", "Bäcker", "Baumarkt", "Apotheke", "Kita", "Alpen", "Ostsee"), far.others.map { it.name })
        assertEquals(listOf("Arbeit", "Zahnarzt"), far.pinned.map { it.name })
    }

    /** Not only German (19.11.2026): the order of the device's language - Swedish å, ä, ö after z, Spanish ñ after n. */
    @Test fun byNameInTheDevicesLanguage() {
        val names = listOf("Örebro", "Ystad", "Åre", "Ängelholm", "Arvika", "Zinkgruvan")
        val b = Places.Book(emptyList(), names.mapIndexed { i, n -> p("s$i", n) })
        assertEquals(listOf("Arvika", "Ystad", "Zinkgruvan", "Åre", "Ängelholm", "Örebro"),
            Places.sorted(b, "c", Places.Order.NAME, locale = java.util.Locale.forLanguageTag("sv-SE")).others.map { it.name })
        assertEquals(listOf("Ängelholm", "Åre", "Arvika", "Örebro", "Ystad", "Zinkgruvan"),
            Places.sorted(b, "c", Places.Order.NAME, locale = java.util.Locale.GERMAN).others.map { it.name })
        val es = Places.Book(emptyList(), listOf("Nuria", "Ñandú", "Ocaña", "Navarra").mapIndexed { i, n -> p("e$i", n) })
        assertEquals(listOf("Navarra", "Nuria", "Ñandú", "Ocaña"),
            Places.sorted(es, "c", Places.Order.NAME, locale = java.util.Locale.forLanguageTag("es-ES")).others.map { it.name })
    }

    @Test fun byDistanceWithoutPositionByUse() {
        val s = Places.sorted(book, "c", Places.Order.DISTANCE, null, now)
        assertEquals(Places.Order.USE, s.order)
        assertEquals("Kita", s.others.first().name)
    }

    @Test fun distanceAsTheLanguageWritesNumbers() {
        assertEquals("2,6 km", Places.distanceText(2.62, java.util.Locale.GERMAN))
        assertEquals("2.6 km", Places.distanceText(2.62, java.util.Locale.UK))
        assertEquals("13 km", Places.distanceText(12.6, java.util.Locale.forLanguageTag("sv-SE")))
    }

    @Test fun orderKeys() {
        assertEquals(Places.Order.DISTANCE, Places.Order.of("distance"))
        assertEquals(Places.Order.USE, Places.Order.of(null))
        assertEquals(Places.Order.USE, Places.Order.of("whatever"))
    }
}
