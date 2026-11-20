package net.prototux.aare.ui.car.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** I-032 and E-018: which package Mate draws from without the net. */
@RunWith(RobolectricTestRunner::class)
class RegionPacksTest {
    private fun square(w: Double, s: Double, e: Double, n: Double) =
        """{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[$w,$s],[$e,$s],[$e,$n],[$w,$n],[$w,$s]]]}}"""
    private fun map(id: String, bbox: List<Double>, border: String? = null) =
        RegionPacks.LocalMap(File("/x/$id/karte.pmtiles"), bbox, id, border?.let { RegionPacks.parseBorder(it) })

    // a big region shaped like an L: its box holds the corner where the small neighbour lies, its border does not
    private val ell = """{"type":"MultiPolygon","coordinates":[[[[0,0],[10,0],[10,4],[0,4],[0,0]]],[[[0,4],[4,4],[4,10],[0,10],[0,4]]]]}"""
    private val big = map("mate-test-gross", listOf(0.0, 0.0, 10.0, 10.0), ell)
    private val small = map("mate-test-klein", listOf(6.0, 6.0, 9.0, 9.0), square(6.0, 6.0, 9.0, 9.0))

    @Test fun theWholePackageAlwaysWins() {
        val whole = map(RegionPacks.GESAMT, listOf(0.0, 0.0, 20.0, 20.0))
        assertEquals(RegionPacks.GESAMT, RegionPacks.pick(listOf(small, whole, big), 7.0, 7.0)?.id)
        assertEquals(RegionPacks.GESAMT, RegionPacks.pick(listOf(small, whole), 50.0, 50.0)?.id)
    }

    @Test fun theBorderDecidesNotTheBox() {
        // inside the big region's box but outside its border, inside the small one: the small one
        assertEquals("mate-test-klein", RegionPacks.pick(listOf(big, small), 7.0, 7.0)?.id)
        // inside the big region's border: the big one, though the small box is smaller (the small one does not hold it)
        assertEquals("mate-test-gross", RegionPacks.pick(listOf(big, small), 2.0, 8.0)?.id)
        // a box that wrongly holds the place (no border loaded) still counts by its box, as before
        val boxOnly = map("mate-test-box", listOf(1.0, 7.0, 3.0, 9.0))
        assertEquals("mate-test-box", RegionPacks.pick(listOf(big, boxOnly), 2.0, 8.0)?.id)
    }

    @Test fun outsideAllTheLargest() {
        assertEquals("mate-test-gross", RegionPacks.pick(listOf(small, big), 30.0, 30.0)?.id)
        assertNull(RegionPacks.pick(emptyList(), 1.0, 1.0))
    }

    @Test fun bordersOfAllKindsAreRead() {
        assertEquals(1, RegionPacks.parseBorder(square(0.0, 0.0, 1.0, 1.0))?.size)
        assertEquals(2, RegionPacks.parseBorder(ell)?.size)
        assertEquals(2, RegionPacks.parseBorder("""{"type":"FeatureCollection","features":[${square(0.0, 0.0, 1.0, 1.0)},${square(2.0, 2.0, 3.0, 3.0)}]}""")?.size)
        assertNull(RegionPacks.parseBorder("""{"type":"Point","coordinates":[1,2]}"""))
        assertNull(RegionPacks.parseBorder("kein json"))
    }

    @Test fun theCatalogueOfTheWholePackage() {
        val c = RegionPacks.parse("""{"format":1,"stand_karte":"2026-11-18","regionen":[{"id":"gesamt","name":"Gesamtpaket (2 Regionen)","eltern":null,
            "pfad":[],"bbox":[5,47,16,58],"enthaelt":["Thüringen","Niedersachsen"],
            "dateien":[{"art":"karte","pfad":"gesamt/karte.pmtiles","bytes":1000,"sha256":"AB"},{"art":"grenze","pfad":"gesamt/grenze.geojson","bytes":10,"sha256":"cd"}]}]}""")!!
        val w = RegionPacks.whole(c)!!
        assertEquals(listOf("Thüringen", "Niedersachsen"), w.contains)
        assertEquals(1010L, w.bytes)
        assertEquals("ab", w.files[0].sha256)
        assertNull(RegionPacks.whole(RegionPacks.parse("""{"format":1,"regionen":[]}""")!!))
    }
}
