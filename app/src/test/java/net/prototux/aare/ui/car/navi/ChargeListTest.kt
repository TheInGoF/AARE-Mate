package net.prototux.aare.ui.car.navi

import android.content.Context
import net.prototux.aare.dash.ServerLogbook
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.spatialk.geojson.Position
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * System L-113, I-143: the logbook's charges with their charger, and the chargers counted only for real charges. The
 * charges are an answer the IDmate server itself made (`resources/idmate/charging.json`, places in Berlin at public
 * chargers of the shipped list).
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36])
class ChargeListTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val place = Position(longitude = 13.38555, latitude = 52.48061)
    private fun near(m: Double) = Position(longitude = place.longitude, latitude = place.latitude + m / 111_320.0)
    private val enbw = Charger(near(-3.0), 300, 8, "EnBW mobility+ AG und Co.KG")
    private val shell = Charger(near(4.0), 150, 4, "Shell Deutschland GmbH")
    private val t = 1_800_000_000_000L
    private fun entry(key: String = "s1", at: Position? = place, home: Boolean = false, start: Long = t, end: Long = t + 40 * 60_000L) =
        ChargeList.Entry(key, start, end, at, "Schnelllader", true, home, 20.0, 70.0, 40.0, 60.0, null, 20.0)
    private fun none(@Suppress("UNUSED_PARAMETER") p: Position): Charger? = null

    @Before fun clean() {
        ctx.getSharedPreferences("charger_visits", Context.MODE_PRIVATE).edit().clear().commit()
        ChargerVisits.forget()
    }

    @Test fun theServersChargeBookWithWhereAndWhatItCost() {
        val list = ServerLogbook.parseCharges(JSONObject(javaClass.classLoader!!.getResource("idmate/charging.json")!!.readText()))
        assertEquals(3, list.size)
        val (home, road, older) = list
        assertTrue(home.home); assertNull(home.lat); assertNull(home.cost)
        assertFalse(road.home)
        assertEquals(52.48061, road.lat!!, 1e-9); assertEquals(13.38555, road.lon!!, 1e-9)
        assertEquals("EnBW", road.operator); assertEquals(23.48, road.cost!!, 1e-9)
        assertEquals(39.8, road.energy!!, 1e-9); assertEquals(true, road.dc)
        assertNull(older.operator)
        val e = ChargeList.fromServer(list)
        assertEquals(listOf("s1", "s2", "s3"), e.map { it.key })
        assertEquals(place.latitude, e[1].place!!.latitude, 1e-9)
        // by month, the newest first: November with two, October with one
        val months = ChargeList.byMonth(e, java.time.ZoneOffset.UTC)
        assertEquals(listOf(2026 to 11, 2026 to 10), months.map { it.first })
        assertEquals(listOf("s1", "s2"), months[0].second.map { it.key })
    }

    @Test fun twoSideBySideTheNearestIsOnlyAGuess() {
        val s = ChargeList.station(entry(), listOf(shell, enbw), emptyList(), emptyList(), null, ::none)
        assertSame(enbw, s.charger)
        assertFalse(s.sure)
        assertEquals(listOf(enbw, shell), s.choices)
    }

    @Test fun aloneThereItIsSure() {
        val s = ChargeList.station(entry(), listOf(enbw), emptyList(), emptyList(), null, ::none)
        assertSame(enbw, s.charger); assertTrue(s.sure)
    }

    @Test fun theDriversWordWins() {
        val s = ChargeList.station(entry(), listOf(shell, enbw), emptyList(), emptyList(), "%.6f,%.6f".format(java.util.Locale.ROOT,
            shell.at.latitude, shell.at.longitude), ::none)
        assertSame(shell, s.charger); assertTrue(s.sure)
        assertSame(shell, s.choices.first())
        // "none of these"
        val n = ChargeList.station(entry(), listOf(shell, enbw), emptyList(), emptyList(), "", ::none)
        assertNull(n.charger); assertTrue(n.sure)
    }

    @Test fun theStarsGivenInTheCarAtThatTimeNameIt() {
        val v = ChargerVisits.Visit(shell.at, "Shell · 150 kW", null, 150, 1, t + 45 * 60_000L, 4)
        val s = ChargeList.station(entry(), listOf(shell, enbw), emptyList(), listOf(v), null, ::none)
        assertSame(shell, s.charger); assertTrue(s.sure)
        // a day later it says nothing about this charge
        val old = ChargerVisits.Visit(shell.at, "Shell · 150 kW", null, 150, 1, t + 26 * 3600_000L, 4)
        assertSame(enbw, ChargeList.station(entry(), listOf(shell, enbw), emptyList(), listOf(old), null, ::none).charger)
        // a charge without a place (an older server): the visit at its time and the charger found there
        val noPlace = ChargeList.station(entry(at = null), emptyList(), emptyList(), listOf(v), null) { p ->
            if (ChargerIndex.distanceM(p, shell.at) < 5) shell else null }
        assertSame(shell, noPlace.charger)
    }

    @Test fun theWallboxHasNone() {
        val s = ChargeList.station(entry(home = true, at = null), emptyList(), emptyList(), emptyList(), null, ::none)
        assertNull(s.charger); assertTrue(s.choices.isEmpty())
    }

    @Test fun aChargeCountsOnceWhetherSaidInTheCarOrInTheList() {
        ChargerVisits.seen(ctx, enbw, t + 40 * 60_000L)          // the car's tile at departure
        ChargerVisits.seen(ctx, enbw, t + 30 * 60_000L)          // the list, same charge
        assertEquals(1, ChargerVisits.at(ctx, enbw.at)!!.visits)
        ChargerVisits.seen(ctx, enbw, t - 5 * 86_400_000L)       // an older charge there, put to it later
        val v = ChargerVisits.at(ctx, enbw.at)!!
        assertEquals(2, v.visits)
        assertEquals(t + 40 * 60_000L, v.lastMillis)              // the last stays the newest
    }

    @Test fun tidyingOnceTakesWhatNobodyJudged() {
        ChargerVisits.seen(ctx, enbw, t)
        ChargerVisits.seen(ctx, shell, t)
        ChargerVisits.rate(ctx, shell.at, 5)
        ChargerVisits.tidy(ctx, t)
        assertNull(ChargerVisits.at(ctx, enbw.at))
        assertEquals(listOf(shell.at), ChargerVisits.judged(ctx).map { it.at })
        // once only: what comes later stays
        ChargerVisits.seen(ctx, enbw, t + 86_400_000L)
        ChargerVisits.tidy(ctx, t + 86_400_000L)
        assertEquals(1, ChargerVisits.at(ctx, enbw.at)!!.visits)
    }

    @Test fun theBadgeCountsChargesNoticedUntilTheListIsOpened() {
        ChargerVisits.allSeen(ctx, t)
        ChargerVisits.noticed(ctx); ChargerVisits.noticed(ctx)
        assertEquals(2, ChargerVisits.freshCount(ctx))
        assertEquals(t, ChargerVisits.allSeen(ctx, t + 1000))
        assertEquals(0, ChargerVisits.freshCount(ctx))
    }

    @Test fun linksAreKept() {
        ChargerVisits.link(ctx, "s2", shell.at)
        ChargerVisits.link(ctx, "s3", null)
        val l = ChargerVisits.links(ctx)
        assertEquals("", l["s3"])
        val p = ChargerVisits.linkPlace(l.getValue("s2"))!!
        assertTrue(ChargerIndex.distanceM(p, shell.at) < 1)
    }
}
