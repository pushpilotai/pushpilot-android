package ai.pushpilot.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePathTest {
    private fun ops(d: String) = LivePath.parse(d)!!

    /** Asserts a point within a small distance. */
    private fun near(x: Float, y: Float, p: Pt?, tolerance: Float = 0.01f) {
        assertEquals(x, p!!.x, tolerance)
        assertEquals(y, p.y, tolerance)
    }

    @Test
    fun movesAndLinesAbsoluteAndRelative() {
        assertEquals(listOf(PathOp.Move(1f, 2f), PathOp.Line(3f, 4f), PathOp.Line(5f, 4f), PathOp.Line(5f, 9f)), ops("M1 2L3 4H5V9"))
        assertEquals(listOf(PathOp.Move(1f, 2f), PathOp.Line(4f, 6f), PathOp.Line(6f, 6f), PathOp.Line(6f, 3f)), ops("m1 2l3 4h2v-3"))
        // Pairs after a move are lines, relative after a relative move.
        assertEquals(listOf(PathOp.Move(1f, 1f), PathOp.Line(2f, 2f), PathOp.Line(3f, 3f)), ops("m1 1 1 1 1 1"))
        assertEquals(listOf(PathOp.Move(1f, 1f), PathOp.Line(2f, 2f)), ops("M1,1 2,2"))
    }

    @Test
    fun curvesAndTheirSmoothForms() {
        assertEquals(listOf(PathOp.Move(0f, 0f), PathOp.Cubic(1f, 2f, 3f, 4f, 5f, 6f)), ops("M0 0C1 2 3 4 5 6"))
        assertEquals(listOf(PathOp.Move(10f, 10f), PathOp.Cubic(11f, 12f, 13f, 14f, 15f, 16f)), ops("M10 10c1 2 3 4 5 6"))
        // S reflects the last control point (13,14) about the current point (15,16).
        assertEquals(PathOp.Cubic(17f, 18f, 20f, 20f, 21f, 22f), ops("M10 10c1 2 3 4 5 6S20 20 21 22")[2])
        assertEquals(PathOp.Cubic(15f, 16f, 20f, 20f, 21f, 22f), ops("M10 10L15 16S20 20 21 22")[2])
        assertEquals(listOf(PathOp.Move(0f, 0f), PathOp.Quad(1f, 2f, 3f, 4f)), ops("M0 0Q1 2 3 4"))
        // T reflects the quadratic control point (1,2) about (3,4); relative t adds the current point.
        assertEquals(PathOp.Quad(5f, 6f, 10f, 10f), ops("M0 0Q1 2 3 4T10 10")[2])
        assertEquals(PathOp.Quad(5f, 6f, 10f, 10f), ops("M0 0q1 2 3 4t7 6")[2])
        assertEquals(PathOp.Quad(3f, 4f, 10f, 10f), ops("M0 0L3 4T10 10")[2])
    }

    @Test
    fun closeReturnsToTheStartAndCompactNumbersRead() {
        assertEquals(listOf(PathOp.Move(0f, 0f), PathOp.Line(4f, 0f), PathOp.Close, PathOp.Line(0f, 4f)), ops("M0 0h4zl0 4"))
        assertEquals(listOf(PathOp.Move(50f, 13f), PathOp.Line(50.1f, 13f)), ops("M50 13h.1"))
        assertEquals(listOf(PathOp.Move(0.5f, 0.25f), PathOp.Line(-1f, 20f)), ops("M.5.25L-1 2e1"))
        assertEquals(listOf(PathOp.Move(10f, -5f)), ops(" M 10-5 "))
    }

    @Test
    fun badPathTextIsRefused() {
        for (bad in listOf("", "L1 2", "M1", "M1 2L3", "M1 2 X", "Z", "M1 2Z 3 4", "M1 2L3 ..", "M1 2C1 2 3 4"))
            assertNull(bad, LivePath.parse(bad))
    }

    @Test
    fun polylinesCutCurvesAndCloseOutlines() {
        val square = LivePath.polylines(ops("M0 0H10V10H0Z"))
        assertEquals(listOf(listOf(Pt(0f, 0f), Pt(10f, 0f), Pt(10f, 10f), Pt(0f, 10f), Pt(0f, 0f))), square)
        assertEquals(40f, LivePath.length(square), 0.001f)
        val curve = LivePath.polylines(ops("M0 0Q5 10 10 0"), steps = 8).single()
        assertEquals(9, curve.size)
        near(5f, 5f, curve[4])
        near(10f, 0f, curve.last())
        // Two outlines, and a line after a close starts at the closed outline's start.
        assertEquals(2, LivePath.polylines(ops("M0 0h1M5 5h1")).size)
        assertEquals(listOf(Pt(0f, 0f), Pt(0f, 3f)), LivePath.polylines(ops("M0 0h2zv3"))[1])
    }

    @Test
    fun trimAndMarkerPositions() {
        val route = LivePath.polylines(ops("M0 0H10V10"))
        near(5f, 0f, LivePath.pointAt(route, 0.25f))
        near(10f, 0f, LivePath.pointAt(route, 0.5f))
        near(10f, 5f, LivePath.pointAt(route, 0.75f))
        near(10f, 10f, LivePath.pointAt(route, 1f))
        near(0f, 0f, LivePath.pointAt(route, 0f))
        near(10f, 10f, LivePath.pointAt(route, 7f)) // clamped
        assertEquals(listOf(listOf(Pt(0f, 0f), Pt(10f, 0f), Pt(10f, 5f))), LivePath.trim(route, 0.75f))
        assertEquals(route, LivePath.trim(route, 1f))
        // Trim runs across outlines: half of two equal lines is the whole first one.
        val two = LivePath.polylines(ops("M0 0h4M0 10h4"))
        assertEquals(1, LivePath.trim(two, 0.5f).size)
        near(2f, 10f, LivePath.pointAt(two, 0.75f))
        assertTrue(LivePath.trim(two, 0f).all { it.size <= 1 })
    }
}
