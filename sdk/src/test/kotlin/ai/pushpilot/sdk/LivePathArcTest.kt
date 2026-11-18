package ai.pushpilot.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePathArcTest {
    private fun ops(d: String) = LivePath.parse(d)!!

    /** The path's polylines. */
    private fun lines(d: String) = LivePath.polylines(ops(d))

    /** Asserts a point within [tolerance]. */
    private fun near(x: Float, y: Float, p: Pt?, tolerance: Float = 0.05f) {
        assertEquals(x, p!!.x, tolerance)
        assertEquals(y, p.y, tolerance)
    }

    @Test
    fun aHalfCircleIsTwoCubicsThroughTheTop() {
        val half = ops("M0 0A10 10 0 0 1 20 0")
        assertEquals(3, half.size)
        assertTrue(half.drop(1).all { it is PathOp.Cubic })
        near(20f, 0f, (half.last() as PathOp.Cubic).let { Pt(it.x, it.y) }, 0f)
        // Sweep 1 runs the positive way, which with y down passes above the centre (10, 0).
        near(10f, -10f, LivePath.pointAt(lines("M0 0A10 10 0 0 1 20 0"), 0.5f))
        near(10f, 10f, LivePath.pointAt(lines("M0 0A10 10 0 0 0 20 0"), 0.5f))
        assertEquals(Math.PI.toFloat() * 10, LivePath.length(lines("M0 0A10 10 0 0 1 20 0")), 0.05f)
    }

    @Test
    fun relativeArcsAndFlagsWrittenTogether() {
        assertEquals(ops("M0 0A10 10 0 0 1 20 0"), ops("M0 0a10 10 0 0120 0"))
        assertEquals(ops("M5 5A10 10 0 0 1 25 5"), ops("m5 5a10,10,0,0,1,20,0"))
        // Arguments repeat without the letter: two quarter turns make the half.
        assertEquals(LivePath.length(lines("M0 0A10 10 0 0 1 20 0")), LivePath.length(lines("M0 0a10 10 0 0 1 10 -10 10 10 0 0 1 10 10")), 0.05f)
    }

    @Test
    fun largeArcFlagRotationAndRadiiTooSmall() {
        assertEquals(Math.PI.toFloat() * 5, LivePath.length(lines("M10 0A10 10 0 0 1 0 10")), 0.05f)
        assertEquals(Math.PI.toFloat() * 15, LivePath.length(lines("M10 0A10 10 0 1 1 0 10")), 0.1f)
        // An ellipse 20 by 10 turned 90 degrees: its half from (0, 0) to (0, 40) bulges 10 to the right.
        near(10f, 20f, LivePath.pointAt(lines("M0 0A20 10 90 0 1 0 40"), 0.5f), 0.1f)
        // Radii too small to reach are scaled up until they do: here to 10.
        near(10f, -10f, LivePath.pointAt(lines("M0 0A1 1 0 0 1 20 0"), 0.5f))
    }

    @Test
    fun degenerateAndBadArcs() {
        assertEquals(listOf(PathOp.Move(0f, 0f), PathOp.Line(3f, 4f)), ops("M0 0A0 5 0 0 1 3 4"))
        assertEquals(listOf(PathOp.Move(3f, 4f)), ops("M3 4A5 5 0 0 1 3 4"))
        for (bad in listOf("M0 0A10 10 0 2 1 5 5", "M0 0A10 10 0 0 1 5", "A1 1 0 0 1 2 2", "M0 0A10 10 0 0"))
            assertNull(bad, LivePath.parse(bad))
    }
}
