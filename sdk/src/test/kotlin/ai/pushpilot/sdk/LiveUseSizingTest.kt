package ai.pushpilot.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ceil

/** Findings from comparing all 87 designs: `use` sizing and placement, spacer tiles, wrapped text, icon sizes. */
class LiveUseSizingTest {
    /** Fonts faked: each character half the size wide, lines 1.2 times the size tall, wrapping up to `lines`. */
    private val sizer = LiveSizer { block, maxWidth ->
        when (block.optString("type")) {
            "text" -> {
                val size = block.optDouble("size", 14.0).toFloat()
                val natural = block.optString("text").length * size / 2
                val lines = minOf(block.optInt("lines", 1), ceil(natural / maxWidth).toInt().coerceAtLeast(1))
                Size(minOf(natural, maxWidth), lines * size * 1.2f, size)
            }
            else -> Size(0f, 0f)
        }
    }

    /** Lays out the `lock` of [layout] in a 376 by 160 card. */
    private fun lock(layout: String): Node {
        val live = LiveUpdate.parse(
            mapOf(
                "source" to "pushpilot",
                "pushpilot_live" to """{"activity_id":"a","event":"start","timestamp":1,"ends_at":2,
                    "attributes":{"notification_id":"n","fields":[],"layout":$layout},"state":{"title":"T","fields":{}}}""",
            ),
        )!!
        return LiveLayout(live, sizer).layout(live.layout.getJSONObject("lock"), 376f, 160f, card = true)
    }

    @Test
    fun usesKeepTheirDefsNaturalSizeAndAreSetByTheirOwnPlacement() {
        val node = lock("""{"defs":{
              "live":{"type":"text","text":"LIVE","pad":[3,8,3,8],"size":10,"bg":"#D7263D"},
              "booth":{"type":"column","w":70,"fh":1,"bg":"#C8102E","items":[{"type":"spacer"}]},
              "star":{"type":"shape","shape":"circle","w":28,"h":28}},
            "lock":{"type":"layer","h":160,"items":[
              {"type":"use","ref":"live","at":"top_leading"},
              {"type":"use","ref":"booth","at":"trailing"},
              {"type":"use","ref":"star","pos":[0.25,0.5]}]}}""")
        val (badge, booth, star) = node.children
        assertEquals(20f + 16f, badge.w) // the text's own width plus its padding, not the card's
        assertEquals(10f * 1.2f + 6f, badge.h, 0.001f)
        assertEquals(0f, badge.x)
        assertEquals(70f, booth.w) // a width-only def keeps its width ...
        assertEquals(160f, booth.h) // ... and its fh is a share of the use's box
        assertEquals(376f - 70f, booth.x)
        assertEquals(94f - 14f, star.x)
        assertEquals(80f - 14f, star.y)
    }

    @Test
    fun spacerTilesFillTheirCellsAndWrappedTextKeepsItsLines() {
        val node = lock("""{"lock":{"type":"row","items":[
            {"type":"grid","w":60,"h":20,"cols":3,"count":3,"filled":2,"gap":0,"tile":{"type":"spacer","bg":"#4AE37A","radius":9}},
            {"type":"column","h":160,"pad":[20,22,20,22],"items":[{"type":"spacer"},{"type":"text","text":"Free delivery","w":60,"lines":2,"size":10},{"type":"spacer"}]}]}}""")
        val (grid, column) = node.children
        assertEquals(2, grid.children.size)
        assertEquals(20f, grid.children[0].w)
        assertEquals(20f, grid.children[0].h)
        val text = column.children[1]
        assertEquals(24f, text.h, 0.001f) // two lines
        assertEquals(20f + (120f - 24f) / 2, text.y, 0.001f)
    }

    @Test
    fun iconSizesInPoints() {
        assertEquals(40f, LiveCanvas.iconSize(40), 0f)
        assertEquals(120f, LiveCanvas.iconSize(500), 0f)
        assertEquals(8f, LiveCanvas.iconSize(2.0), 0f)
        assertEquals(32f, LiveCanvas.iconSize("l"), 0f)
        assertEquals(24f, LiveCanvas.iconSize(JSONObject().opt("size")), 0f)
    }
}
