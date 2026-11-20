package net.prototux.aare.trips

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The trip log and the charge log on the tablet (System L-076/L-077): trips with their track, charges - columns named like
 * IDmate's (`triplog/schema.sql`), so they can go to the server later ([sent] marks what went).
 */
class TripStore private constructor(context: Context) : SQLiteOpenHelper(context, "trips.db", null, 1) {

    data class TripRow(
        val id: Long, val startMs: Long, val endMs: Long,
        val startLat: Double?, val startLon: Double?, val endLat: Double?, val endLon: Double?,
        val startName: String?, val endName: String?,
        val distanceKm: Double?, val socStart: Double?, val socEnd: Double?, val energyKwh: Double?, val consumption: Double?,
        val maxKmh: Double?, val points: Int,
    )

    data class ChargeRow(
        val id: Long, val startMs: Long, val endMs: Long, val lat: Double?, val lon: Double?, val name: String?,
        val dc: Boolean?, val socStart: Double?, val socEnd: Double?, val energyKwh: Double?, val maxKw: Double?,
        val source: TripDetector.ChargeSource, val cut: Boolean,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE trips (id INTEGER PRIMARY KEY AUTOINCREMENT, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL,
            start_lat REAL, start_lon REAL, end_lat REAL, end_lon REAL, start_name TEXT, end_name TEXT,
            distance_km REAL, odo_start REAL, odo_end REAL, soc_start REAL, soc_end REAL, energy_kwh REAL, consumption REAL,
            max_kmh REAL, sent INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("""CREATE TABLE trip_points (trip_id INTEGER NOT NULL REFERENCES trips(id) ON DELETE CASCADE, t INTEGER NOT NULL,
            lat REAL NOT NULL, lon REAL NOT NULL, kmh REAL, soc REAL)""")
        db.execSQL("CREATE INDEX trip_points_trip ON trip_points(trip_id)")
        db.execSQL("""CREATE TABLE charges (id INTEGER PRIMARY KEY AUTOINCREMENT, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL,
            lat REAL, lon REAL, name TEXT, type TEXT, soc_start REAL, soc_end REAL, energy_kwh REAL, max_kw REAL,
            source TEXT NOT NULL, cut INTEGER NOT NULL DEFAULT 0, sent INTEGER NOT NULL DEFAULT 0)""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

    fun add(t: TripDetector.Trip): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.insert("trips", null, ContentValues().apply {
                put("start_ms", t.startMs); put("end_ms", t.endMs)
                put("start_lat", t.startLat); put("start_lon", t.startLon); put("end_lat", t.endLat); put("end_lon", t.endLon)
                put("distance_km", t.distanceKm); put("odo_start", t.odoStart); put("odo_end", t.odoEnd)
                put("soc_start", t.socStart); put("soc_end", t.socEnd); put("energy_kwh", t.energyKwh); put("consumption", t.consumption)
                put("max_kmh", t.maxKmh)
            })
            for (p in t.points) db.insert("trip_points", null, ContentValues().apply {
                put("trip_id", id); put("t", p.t); put("lat", p.lat); put("lon", p.lon); put("kmh", p.kmh); put("soc", p.soc)
            })
            db.setTransactionSuccessful()
            return id
        } finally { db.endTransaction() }
    }

    fun add(c: TripDetector.Charge): Long = writableDatabase.insert("charges", null, values(c))

    /** A cut charge completed (same start): its level at the end replaced. */
    fun complete(c: TripDetector.Charge) {
        val n = writableDatabase.update("charges", values(c), "start_ms = ? AND source = ?", arrayOf(c.startMs.toString(), c.source.name))
        if (n == 0) add(c)
    }

    private fun values(c: TripDetector.Charge) = ContentValues().apply {
        put("start_ms", c.startMs); put("end_ms", c.endMs); put("lat", c.lat); put("lon", c.lon)
        put("type", when (c.dc) { true -> "dc"; false -> "ac"; null -> null })
        put("soc_start", c.socStart); put("soc_end", c.socEnd); put("energy_kwh", c.energyKwh); put("max_kw", c.maxKw)
        put("source", c.source.name); put("cut", if (c.cut) 1 else 0)
    }

    fun nameTrip(id: Long, start: String?, end: String?) {
        writableDatabase.update("trips", ContentValues().apply {
            if (start != null) put("start_name", start)
            if (end != null) put("end_name", end)
        }.takeIf { it.size() > 0 } ?: return, "id = ?", arrayOf(id.toString()))
    }

    fun nameCharge(id: Long, name: String) {
        writableDatabase.update("charges", ContentValues().apply { put("name", name) }, "id = ?", arrayOf(id.toString()))
    }

    fun trips(limit: Int = 200): List<TripRow> = readableDatabase.rawQuery(
        "SELECT t.*, (SELECT COUNT(*) FROM trip_points p WHERE p.trip_id = t.id) AS n FROM trips t ORDER BY start_ms DESC LIMIT $limit", null
    ).use { c -> buildList { while (c.moveToNext()) add(trip(c)) } }

    fun charges(limit: Int = 200): List<ChargeRow> = readableDatabase.rawQuery(
        "SELECT * FROM charges ORDER BY start_ms DESC LIMIT $limit", null
    ).use { c -> buildList { while (c.moveToNext()) add(charge(c)) } }

    fun points(tripId: Long): List<TripDetector.Point> = readableDatabase.rawQuery(
        "SELECT t, lat, lon, kmh, soc FROM trip_points WHERE trip_id = ? ORDER BY t", arrayOf(tripId.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(TripDetector.Point(c.getLong(0), c.getDouble(1), c.getDouble(2), c.dbl(3), c.dbl(4))) } }

    fun deleteTrip(id: Long) { writableDatabase.delete("trips", "id = ?", arrayOf(id.toString())) }
    fun deleteCharge(id: Long) { writableDatabase.delete("charges", "id = ?", arrayOf(id.toString())) }

    /** Where the last trip ended (for a charge found from the level across runs of Mate). */
    fun lastEnd(): TripDetector.Spot? = readableDatabase.rawQuery(
        "SELECT end_ms, end_lat, end_lon, soc_end FROM trips ORDER BY end_ms DESC LIMIT 1", null
    ).use { c -> if (c.moveToFirst()) TripDetector.Spot(c.getLong(0), c.dbl(1), c.dbl(2), c.dbl(3)) else null }

    /** The last charge when it was cut and nothing came after it. */
    fun lastCutCharge(): TripDetector.Charge? {
        val row = charges(1).firstOrNull()?.takeIf { it.cut } ?: return null
        val lastTrip = trips(1).firstOrNull()
        if (lastTrip != null && lastTrip.startMs > row.endMs) return null
        return TripDetector.Charge(row.startMs, row.endMs, row.lat, row.lon, row.dc, row.socStart, row.socEnd, row.energyKwh, row.maxKw, row.source, true)
    }

    /** Trips and charges whose places have no name yet (named once the net is there). */
    fun unnamedTrips(): List<TripRow> = trips(50).filter { it.startName == null || it.endName == null }
    fun unnamedCharges(): List<ChargeRow> = charges(50).filter { it.name == null && it.lat != null }

    private fun trip(c: Cursor) = TripRow(
        id = c.long("id"), startMs = c.long("start_ms"), endMs = c.long("end_ms"),
        startLat = c.dbl("start_lat"), startLon = c.dbl("start_lon"), endLat = c.dbl("end_lat"), endLon = c.dbl("end_lon"),
        startName = c.str("start_name"), endName = c.str("end_name"), distanceKm = c.dbl("distance_km"),
        socStart = c.dbl("soc_start"), socEnd = c.dbl("soc_end"), energyKwh = c.dbl("energy_kwh"), consumption = c.dbl("consumption"),
        maxKmh = c.dbl("max_kmh"), points = c.getInt(c.getColumnIndexOrThrow("n")),
    )

    private fun charge(c: Cursor) = ChargeRow(
        id = c.long("id"), startMs = c.long("start_ms"), endMs = c.long("end_ms"), lat = c.dbl("lat"), lon = c.dbl("lon"),
        name = c.str("name"), dc = when (c.str("type")) { "dc" -> true; "ac" -> false; else -> null },
        socStart = c.dbl("soc_start"), socEnd = c.dbl("soc_end"), energyKwh = c.dbl("energy_kwh"), maxKw = c.dbl("max_kw"),
        source = runCatching { TripDetector.ChargeSource.valueOf(c.str("source") ?: "") }.getOrDefault(TripDetector.ChargeSource.LEVEL),
        cut = c.getInt(c.getColumnIndexOrThrow("cut")) == 1,
    )

    private fun Cursor.long(col: String) = getLong(getColumnIndexOrThrow(col))
    private fun Cursor.str(col: String) = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.dbl(col: String) = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getDouble(it) }
    private fun Cursor.dbl(i: Int) = if (isNull(i)) null else getDouble(i)

    companion object {
        @Volatile private var instance: TripStore? = null
        fun get(context: Context): TripStore = instance ?: synchronized(this) {
            instance ?: TripStore(context.applicationContext).also { instance = it }
        }
    }
}
