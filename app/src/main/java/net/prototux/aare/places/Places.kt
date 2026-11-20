package net.prototux.aare.places

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The user's own places for the navigation (wish O169 of 16.11.2026): categories (work, home, their own, each with an
 * icon), in each any number of places with a name, an address and where they are. Every arrival at a place is noted, so
 * a list can be ordered by use (Navi L-030), else A to Z or by distance.
 * Kept in files/places.json; the phone app edits it, the car screen reads it.
 */
object Places {
    /**
     * A list's order (Navi L-030, 19.11.2026: "automatisch nach Häufigkeit der letzten 30 Tage ... älter als 30 Tage ...
     * nach zuletzt ... sortieren A-Z / Entfernung ... eine Urlaubsliste ... lieber nach Entfernung"), kept per list.
     */
    enum class Order(val key: String) {
        /** Visited in the last [RECENT_DAYS] days by how often, then the others by when last. */
        USE("use"),
        NAME("name"),
        /** By the distance from where the car is. */
        DISTANCE("distance");
        companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: USE }
    }

    data class Category(val id: String, val name: String, val icon: String, val order: Order = Order.USE)
    data class Place(
        val id: String, val category: String, val name: String, val address: String,
        val lat: Double, val lon: Double, val visits: List<Long> = emptyList(),
        /** Pinned by the driver: always at the top of its list (O201). */
        val pinned: Boolean = false,
        /** The driver's home (O205: "zuhause muss bei privat extra gesetzt werden"), one at most, and whether it has a wallbox. */
        val home: Boolean = false,
        val wallbox: Boolean = false,
    )
    data class Book(val categories: List<Category>, val places: List<Place>)

    /** The icons a category can have (names of Material icons, drawn by the phone app and the car screen). */
    val ICONS = listOf("work", "home", "store", "restaurant", "hospital", "school", "sport", "charging", "favorite", "star")

    private const val FILE = "places.json"
    /** Visits within this many days count for "how often" (L-030: "Häufigkeit der letzten 30 Tage"). */
    const val RECENT_DAYS = 30
    private const val KEEP_VISITS = 50

    private fun file(context: Context) = File(context.filesDir, FILE)

    @Synchronized
    fun load(context: Context): Book {
        val f = file(context)
        if (!f.isFile) return Book(defaultCategories(context), emptyList())
        return runCatching {
            val o = JSONObject(f.readText())
            val cats = o.getJSONArray("categories").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
                Category(it.getString("id"), it.getString("name"), it.optString("icon", "star"), Order.of(it.optString("order"))) } }
            val places = o.getJSONArray("places").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { p ->
                val v = p.optJSONArray("visits")
                Place(p.getString("id"), p.getString("category"), p.getString("name"), p.optString("address"),
                    p.getDouble("lat"), p.getDouble("lon"), v?.let { (0 until it.length()).map { i -> it.getLong(i) } } ?: emptyList(),
                    p.optBoolean("pinned", false), p.optBoolean("home", false), p.optBoolean("wallbox", false))
            } }
            Book(cats, places)
        }.getOrElse { Book(defaultCategories(context), emptyList()) }
    }

    @Synchronized
    fun save(context: Context, book: Book) {
        val o = JSONObject()
            .put("categories", JSONArray().apply { book.categories.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("icon", it.icon).put("order", it.order.key)) } })
            .put("places", JSONArray().apply { book.places.forEach { p ->
                put(JSONObject().put("id", p.id).put("category", p.category).put("name", p.name).put("address", p.address)
                    .put("lat", p.lat).put("lon", p.lon).put("visits", JSONArray(p.visits.takeLast(KEEP_VISITS))).put("pinned", p.pinned).put("home", p.home).put("wallbox", p.wallbox))
            } })
        val f = file(context)
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeText(o.toString(1))
        tmp.renameTo(f)
    }

    private fun defaultCategories(context: Context) = listOf(
        Category("work", context.getString(net.prototux.aare.R.string.places_category_work), "work"),
        Category("home", context.getString(net.prototux.aare.R.string.places_category_home), "home"),
    )

    fun newId(): String = UUID.randomUUID().toString().take(8)

    fun addCategory(context: Context, name: String, icon: String): Book =
        load(context).let { b -> b.copy(categories = b.categories + Category(newId(), name.trim(), icon)) }.also { save(context, it) }

    fun removeCategory(context: Context, id: String): Book =
        load(context).let { b -> b.copy(categories = b.categories.filter { it.id != id }, places = b.places.filter { it.category != id }) }.also { save(context, it) }

    fun addPlace(context: Context, p: Place): Book =
        load(context).let { b -> b.copy(places = b.places.filter { it.id != p.id } + p) }.also { save(context, it) }

    fun removePlace(context: Context, id: String): Book =
        load(context).let { b -> b.copy(places = b.places.filter { it.id != id }) }.also { save(context, it) }

    /**
     * The category [id] moved [by] places up (negative) or down in the order of the lists (O203: "die reihenfolge der
     * listen … muss sich in der app ändern lassen . mit pfeil tasten hoch und runter"); the car shows them in this order.
     */
    fun moved(book: Book, id: String, by: Int): Book {
        val i = book.categories.indexOfFirst { it.id == id }
        if (i < 0) return book
        val to = (i + by).coerceIn(0, book.categories.lastIndex)
        if (to == i) return book
        val cats = book.categories.toMutableList().apply { add(to, removeAt(i)) }
        return book.copy(categories = cats)
    }

    @Synchronized
    fun moveCategory(context: Context, id: String, by: Int): Book = moved(load(context), id, by).also { save(context, it) }

    /** Pins [id] to the top of its list or takes the pin off (O201). */
    @Synchronized
    fun setPinned(context: Context, id: String, pinned: Boolean): Book =
        load(context).let { b -> b.copy(places = b.places.map { if (it.id == id) it.copy(pinned = pinned) else it }) }.also { save(context, it) }

    /** Makes [id] the home (taking it from any other place) or no longer the home; a home keeps its wallbox setting. */
    @Synchronized
    fun setHome(context: Context, id: String, home: Boolean): Book =
        load(context).let { b -> b.copy(places = b.places.map { when {
            it.id == id -> it.copy(home = home, wallbox = home && it.wallbox)
            home -> it.copy(home = false, wallbox = false)
            else -> it
        } }) }.also { save(context, it) }

    @Synchronized
    fun setWallbox(context: Context, id: String, on: Boolean): Book =
        load(context).let { b -> b.copy(places = b.places.map { if (it.id == id && it.home) it.copy(wallbox = on) else it }) }.also { save(context, it) }

    /** The home with a wallbox, if [lat]/[lon] is it (within [withinM]): there the plan may arrive with less (O205). */
    fun isHomeWithWallbox(book: Book, lat: Double, lon: Double, withinM: Double = 150.0): Boolean =
        book.places.any { it.home && it.wallbox && metres(it.lat, it.lon, lat, lon) <= withinM }

    /** Notes an arrival at the place nearest to [lat]/[lon] within [withinM] metres, if there is one. */
    fun noteArrival(context: Context, lat: Double, lon: Double, withinM: Double = 150.0) {
        val b = load(context)
        val near = b.places.minByOrNull { metres(it.lat, it.lon, lat, lon) }?.takeIf { metres(it.lat, it.lon, lat, lon) <= withinM } ?: return
        save(context, b.copy(places = b.places.map { if (it.id == near.id) it.copy(visits = it.visits + System.currentTimeMillis()) else it }))
    }

    /** The order of list [id], kept for it (the holiday list by distance, the work list by use). */
    fun setOrder(context: Context, id: String, order: Order): Book =
        load(context).let { b -> b.copy(categories = b.categories.map { if (it.id == id) it.copy(order = order) else it }) }.also { save(context, it) }

    /** A list's places split as the car and the phone show them: the pinned ones, then the others, each in [order]. */
    data class Sorted(val pinned: List<Place>, val others: List<Place>, val order: Order)

    /**
     * The places of list [category] in [order] (L-030): the pinned ones always first (O201), then the others.
     * [Order.USE]: those visited in the last [RECENT_DAYS] days by how often (of equals the latest first), then the others
     * by their last visit, those never visited last from A to Z. [Order.DISTANCE] from [here] (latitude, longitude),
     * nearest first; without a position [Order.USE] (said by [Sorted.order]). Ties always by name, in [locale]'s order.
     */
    fun sorted(book: Book, category: String, order: Order, here: Pair<Double, Double>? = null,
               now: Long = System.currentTimeMillis(), locale: java.util.Locale = java.util.Locale.getDefault()): Sorted {
        val inCat = book.places.filter { it.category == category }
        // names in the order of the device's language (19.11.2026: "nicht nur auf Deutsch zuschneiden" - German "Bäcker"
        // before "Baumarkt", Swedish å, ä, ö after z, Spanish ñ after n), small and capital letters alike
        val collator = java.text.Collator.getInstance(locale).apply { strength = java.text.Collator.SECONDARY }
        val byName = compareBy<Place, String>(collator) { it.name }.thenBy { it.id }
        val used = if (order == Order.DISTANCE && here == null) Order.USE else order
        val cmp: Comparator<Place> = when (used) {
            Order.NAME -> byName
            Order.DISTANCE -> compareBy<Place> { metres(here!!.first, here.second, it.lat, it.lon) }.then(byName)
            Order.USE -> {
                val since = now - RECENT_DAYS * 24L * 3600_000L
                fun recent(p: Place) = p.visits.count { it >= since }
                compareByDescending<Place> { recent(it) }.thenByDescending { it.visits.maxOrNull() ?: Long.MIN_VALUE }.then(byName)
            }
        }
        return Sorted(inCat.filter { it.pinned }.sortedWith(cmp), inCat.filter { !it.pinned }.sortedWith(cmp), used)
    }

    /** A distance in km as the device's language writes numbers (one decimal below 10 km). */
    fun distanceText(km: Double, locale: java.util.Locale = java.util.Locale.getDefault()): String =
        if (km < 10) String.format(locale, "%.1f km", km) else String.format(locale, "%d km", Math.round(km))

    fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val k = 111_320.0
        return Math.hypot((lat2 - lat1) * k, (lon2 - lon1) * k * Math.cos(Math.toRadians(lat1)))
    }
}
