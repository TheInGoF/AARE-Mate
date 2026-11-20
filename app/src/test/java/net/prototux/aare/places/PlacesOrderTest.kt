package net.prototux.aare.places

import org.junit.Assert.assertEquals
import org.junit.Test

/** O203: the lists move up and down by the arrows; at the ends nothing happens. */
class PlacesOrderTest {
    private val book = Places.Book(listOf("Arbeit", "Privat", "Laden").map { Places.Category(it, it, "star") }, emptyList())
    private fun order(b: Places.Book) = b.categories.map { it.id }

    @Test fun moveUpAndDown() {
        assertEquals(listOf("Privat", "Arbeit", "Laden"), order(Places.moved(book, "Privat", -1)))
        assertEquals(listOf("Privat", "Arbeit", "Laden"), order(Places.moved(book, "Arbeit", 1)))
        assertEquals(listOf("Arbeit", "Laden", "Privat"), order(Places.moved(book, "Laden", -1)))
    }

    @Test fun endsAndUnknownStay() {
        assertEquals(order(book), order(Places.moved(book, "Arbeit", -1)))
        assertEquals(order(book), order(Places.moved(book, "Laden", 1)))
        assertEquals(order(book), order(Places.moved(book, "fehlt", 1)))
    }
}
