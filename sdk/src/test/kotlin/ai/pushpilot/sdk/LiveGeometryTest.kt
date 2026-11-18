package ai.pushpilot.sdk

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveGeometryTest {
    /** A live step whose state holds [fields]. */
    private fun live(fields: String = "{}"): LiveUpdate = LiveUpdate.parse(
        mapOf(
            "source" to "pushpilot",
            "pushpilot_live" to """{"activity_id":"a","event":"start","timestamp":1,"ends_at":2,
                "attributes":{"notification_id":"n","accent":"#112233","fields":[],"layout":{}},
                "state":{"title":"T","fields":$fields}}""",
        ),
    )!!

    @Test
    fun arcSegmentsShareTheSweepWithGapsAndFillUpToTheValue() {
        // A full ring of 4 with 10° gaps: each piece 80°, a gap after every piece.
        val ring = LiveGeometry.arcPieces(0f, 360f, 4, 10f, 0.5)
        assertEquals(listOf(ArcPiece(0f, 80f, true, 0), ArcPiece(90f, 80f, true, 1), ArcPiece(180f, 80f, false, 2), ArcPiece(270f, 80f, false, 3)), ring)
        // A half gauge of 4 with 4° gaps has 3 gaps: (180 - 12) / 4 = 42° each; no value fills all.
        val gauge = LiveGeometry.arcPieces(270f, 180f, 4, 4f, 1.0)
        assertEquals(listOf(270f, 316f, 362f, 408f), gauge.map { it.start })
        assertEquals(true, gauge.all { it.filled && it.sweep == 42f })
        // 0.667 of six fills four whole segments, not a sliver of the fifth.
        assertEquals(4, LiveGeometry.arcPieces(3.6f, 360f, 6, 7f, 0.667).count { it.filled })
        // A partly filled segment splits into a filled and a track piece.
        assertEquals(listOf(ArcPiece(0f, 90f, true, 0), ArcPiece(90f, 270f, false, 0)), LiveGeometry.arcPieces(0f, 360f, 1, 10f, 0.25))
    }

    @Test
    fun gridCellsAndFilledCountsFromEitherEnd() {
        val cells = LiveGeometry.gridCells(104f, 38f, 5, 10, 7, false, 6f)
        assertEquals(10, cells.size)
        assertEquals(Cell(0f, 0f, 16f, 16f, true), cells[0])
        assertEquals(Cell(88f, 22f, 16f, 16f, false), cells[9])
        assertEquals(7, cells.count { it.filled })
        assertEquals(listOf(false, false, false, true, true), LiveGeometry.gridCells(50f, null, 5, 5, 2, true, 0f).map { it.filled })
        // Square cells when no height is set: 3 columns of (126 - 12) / 3 = 38, 2 rows.
        assertEquals(82f, LiveGeometry.gridHeight(126f, 3, 6, 6f), 0.001f)
        assertEquals(Cell(44f, 44f, 38f, 38f, false), LiveGeometry.gridCells(126f, null, 3, 6, 0, false, 6f)[4])
        assertEquals(3, LiveGeometry.gridCells(30f, null, 3, 3, 99, false, 0f).count { it.filled })
    }

    @Test
    fun copiesScaleWithTheBoxEvenly() {
        // A 376 x 160 view in a 188 x 80 box: factor 0.5; a 25 dp def at scale 2 draws at 25 dp, centred on (50, 20).
        val (x, y, s) = LiveGeometry.copy(188f, 80f, 376f, 160f, 100f, 40f, 2f, 25f, 25f)
        assertEquals(1f, s, 0.0001f)
        assertEquals(37.5f, x, 0.0001f)
        assertEquals(7.5f, y, 0.0001f)
        // The default 100 x 100 view in a wide box is centred: 300 x 100 gives factor 1 and 100 dp to its left.
        val wide = LiveGeometry.copy(300f, 100f, 100f, 100f, 0f, 0f, 1f, 10f, 10f)
        assertEquals(Triple(95f, -5f, 1f), wide)
    }

    @Test
    fun chartRangesPointsAndBars() {
        assertEquals(0.0 to 10.0, LiveGeometry.range(listOf(2.0, 10.0), null, null, true))
        assertEquals(2.0 to 10.0, LiveGeometry.range(listOf(2.0, 10.0), null, null, false))
        assertEquals(-40.0 to 100.0, LiveGeometry.range(listOf(66.0, 0.0), -40.0, 100.0, false))
        assertEquals(listOf(Pt(0f, 100f), Pt(50f, 50f), Pt(100f, 0f)), LiveGeometry.chartPoints(listOf(0.0, 5.0, 10.0), 0.0, 10.0, 100f, 100f))
        assertEquals(listOf(Rect4(0f, 50f, 45f, 50f), Rect4(55f, 0f, 45f, 100f)), LiveGeometry.chartBars(listOf(5.0, 10.0), 0.0, 10.0, 100f, 100f, 10f))
    }

    @Test
    fun amountsColoursAndBoxKeys() {
        val l = live("""{"share":0.54,"big":3,"n":7}""")
        assertEquals(0.54, LiveBox.amount("share", l), 1e-9)
        assertEquals(1.0, LiveBox.amount("big", l), 1e-9)
        assertEquals(0.0, LiveBox.amount("missing", l), 1e-9)
        assertEquals(0.3, LiveBox.amount(0.3, l), 1e-9)
        assertEquals(1.0, LiveBox.amount(null, l), 1e-9)
        assertEquals(0.0, LiveBox.amount(-2, l), 1e-9)
        assertEquals(7.0, LiveBox.count("n", l)!!, 1e-9)
        assertEquals(0xFF0863D1.toInt(), LiveBox.color("#0863D1", null))
        assertEquals(0x80FFFFFF.toInt(), LiveBox.color("#FFFFFF80", null))
        assertEquals(0xFF112233.toInt(), LiveBox.color("accent", l.accent))
        assertNull(LiveBox.color("secondary", null))
        assertNull(LiveBox.color("#12345", null))
        assertNull(LiveBox.color("#GG0000", null))
        val b = JSONObject("""{"pad":[1,2,3,4],"radius":5,"offset":[-6,7],"rotate":{"value":"share","from":-90,"to":90},"opacity":2}""")
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f), LiveBox.pad(b), 0f)
        assertArrayEquals(floatArrayOf(5f, 5f, 5f, 5f), LiveBox.radius(b), 0f)
        assertEquals(Pt(-6f, 7f), LiveBox.offset(b))
        assertEquals(7.2f, LiveBox.rotation(b, l), 0.001f)
        assertEquals(30f, LiveBox.rotation(JSONObject("""{"rotate":30}"""), l), 0f)
        assertEquals(1f, LiveBox.opacity(b), 0f)
        assertEquals(Pt(90f, 0f), LiveBox.place("top_trailing", 10f, 10f, 100f, 50f))
        assertEquals(Pt(45f, 40f), LiveBox.place("bottom", 10f, 10f, 100f, 50f))
        assertEquals(Pt(45f, 20f), LiveBox.place("center", 10f, 10f, 100f, 50f))
        assertArrayEquals(floatArrayOf(2f, 2f, 0f, 30f), LiveBox.fit("contain", 50f, 50f, 100f, 160f), 0f)
        assertArrayEquals(floatArrayOf(2f, 0.5f, 0f, 0f), LiveBox.fit("fill", 50f, 200f, 100f, 100f), 0f)
    }
}
