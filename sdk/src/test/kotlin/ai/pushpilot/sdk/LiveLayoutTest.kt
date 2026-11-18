package ai.pushpilot.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLayoutTest {
    /** Fonts faked: each character half the size wide, lines 1.2 times the size tall, the baseline at the size. */
    private val sizer = LiveSizer { block, maxWidth ->
        when (block.optString("type")) {
            "text" -> {
                val size = block.optDouble("size", 14.0).toFloat()
                Size(minOf(block.optString("text").length * size / 2, maxWidth), size * 1.2f, size)
            }
            "icon" -> Size(24f, 24f)
            else -> Size(0f, 0f)
        }
    }

    /** A live step of [layout] and [fields] (a JSON object), with the kinds of [kinds]. */
    private fun live(layout: String, fields: String = "{}", kinds: String = "[]", title: String = "T"): LiveUpdate = LiveUpdate.parse(
        mapOf(
            "source" to "pushpilot",
            "pushpilot_live" to """{"activity_id":"a","event":"start","timestamp":1,"ends_at":2,
                "attributes":{"notification_id":"n","fields":$kinds,"layout":$layout},
                "state":{"title":${JSONObject.quote(title)},"fields":$fields}}""",
        ),
    )!!

    /** Lays out the step's `lock` in a card of [w] by [h]. */
    private fun lock(live: LiveUpdate, w: Float = 376f, h: Float = 160f): Node =
        LiveLayout(live, sizer).layout(live.layout.getJSONObject("lock"), w, h, card = true)

    @Test
    fun rowSpacersTakeTheFreeSpaceWithNoSpacingBesideThem() {
        val node = lock(live("""{"lock":{"type":"row","h":100,"pad":10,"spacing":6,"items":[
            {"type":"text","text":"abcd","size":10},{"type":"icon"},{"type":"spacer"},{"type":"shape","w":40,"h":20}]}}"""))
        assertEquals(376f, node.w)
        assertEquals(100f, node.h)
        val (text, icon, spacer, shape) = node.children
        assertEquals(10f, text.x)
        assertEquals(36f, icon.x) // 10 + 20 wide text + 6 spacing
        assertEquals(366f - 40f, shape.x) // against the trailing padding, no spacing beside the spacer
        assertEquals(356f - 20f - 6f - 24f - 40f, spacer.w)
        assertEquals(10f + (80f - 20f) / 2, shape.y) // centred across by default
    }

    @Test
    fun baselineRowsAlignTheirTextsAndColumnsAlignAcross() {
        val node = lock(live("""{"lock":{"type":"column","align":"trailing","spacing":0,"items":[
            {"type":"row","align":"baseline","spacing":8,"items":[{"type":"text","text":"35%","size":40},{"type":"text","text":"off","size":10}]},
            {"type":"text","text":"ab","size":10}]}}"""))
        val row = node.children[0]
        val (big, small) = row.children
        assertEquals(big.y + 40f, small.y + 10f, 0.001f)
        assertEquals(48f, row.h, 0.001f)
        val tail = node.children[1]
        assertEquals(376f - 10f, tail.x, 0.001f)
        assertEquals(48f, tail.y, 0.001f)
    }

    @Test
    fun layersPlaceItemsByAtAndFractionsFollowFields() {
        val node = lock(live(
            """{"lock":{"type":"layer","h":160,"items":[
                {"type":"shape","shape":"rect","at":"leading","fw":"share"},
                {"type":"text","text":"VS","at":"top_trailing","size":10,"offset":[17,10]},
                {"type":"shape","at":"bottom","fh":0.25,"w":10}]}}""",
            """{"share":0.5}""",
        ))
        val (half, vs, foot) = node.children
        assertEquals(188f, half.w)
        assertEquals(160f, half.h)
        assertEquals(0f, half.x)
        assertEquals(376f - 10f, vs.x) // offset is applied when drawing, not in layout
        assertEquals(0f, vs.y)
        assertEquals(40f, foot.h)
        assertEquals(120f, foot.y)
        assertEquals(183f, foot.x)
    }

    @Test
    fun drawingsScaleToTheirBoxAndCopiesKeepTheirProportions() {
        val l = live("""{"defs":{"dot":{"type":"shape","shape":"circle","w":25,"h":25},"scene":{"type":"layer","items":[{"type":"path","d":"M0 0H10"}]}},
            "lock":{"type":"layer","h":80,"items":[
              {"type":"use","ref":"dot","w":22,"h":22,"at":"top_leading"},
              {"type":"use","ref":"dot","copies":[[188,80],[0,0,2]],"view":[376,160]},
              {"type":"use","ref":"scene"}]}}""")
        val (one, many, scene) = lock(l, 188f).children
        val drawn = one.children.single()
        assertEquals(22f / 25f, drawn.scale, 0.0001f)
        assertEquals(22f, one.w)
        // Factor 0.5: the first copy is centred on (94, 40) at 12.5 dp, the second on (0, 0) at 25 dp.
        val (a, b) = many.children
        assertEquals(0.5f, a.scale, 0.0001f)
        assertEquals(94f - 6.25f, a.x, 0.0001f)
        assertEquals(1f, b.scale, 0.0001f)
        assertEquals(-12.5f, b.x, 0.0001f)
        // A drawing with no size of its own fills its box.
        assertEquals(188f, scene.children.single().w)
        assertEquals(80f, scene.children.single().h)
    }

    @Test
    fun gridsFillTilesFromAFieldAndPathsPlaceTheirMarker() {
        val l = live(
            """{"lock":{"type":"column","items":[
                {"type":"grid","w":104,"h":38,"cols":5,"count":10,"gap":6,"filled":"sixes","from":"end","tile":{"type":"shape","shape":"circle"},"empty":{"type":"text","text":"+","size":10}},
                {"type":"path","w":100,"h":50,"d":"M0 100H100V0","trim":"climbed","marker":{"type":"shape","w":10,"h":10}}]}}""",
            """{"sixes":7,"climbed":0.75}""",
        )
        val (grid, path) = lock(l).children
        assertEquals(10, grid.children.size)
        assertEquals(3, grid.children.count { it.block.optString("type") == "text" })
        assertEquals("text", grid.children[0].block.optString("type"))
        assertEquals(16f, grid.children[9].w) // a tile takes its cell
        assertEquals(88f, grid.children[9].x)
        val marker = path.children.single()
        // The 100 x 100 view is stretched to 100 x 50: three quarters along (0,50) → (100,50) → (100,0),
        // 112.5 of 150, is (100, 37.5), less half the marker.
        assertEquals(95f, marker.x, 0.01f)
        assertEquals(32.5f, marker.y, 0.01f)
    }

    @Test
    fun everyReferenceDesignReadsAndLaysOut() {
        val names = listOf(
            "air_run", "aurora", "bill_split", "blossom", "deal_battle_2", "doctor_late",
            "forest_planted", "group_order_2", "price_pulse", "savings_goal", "sixes_sale_2", "summit",
        )
        for (name in names) {
            val design = JSONObject(javaClass.getResource("/live-designs/$name.json")!!.readText())
            val fields = design.getJSONArray("fields")
            val state = design.getJSONObject("state")
            val values = JSONObject()
            for (key in state.keys()) {
                if (key == "title") continue
                val kind = (0 until fields.length()).map { fields.getJSONObject(it) }.first { it.getString("name") == key }.getString("kind")
                values.put(key, if (kind == "time") 1_800_000_000L else state.get(key))
            }
            val l = live(design.getJSONObject("layout").toString(), values.toString(), fields.toString(), state.getString("title"))
            assertEquals(name, state.getString("title"), l.title)
            val node = lock(l)
            assertEquals(name, 376f, node.w)
            assertEquals(name, 160f, node.h)
            walk(node) { assertTrue("$name: ${it.block}", it.w.isFinite() && it.h.isFinite() && it.x.isFinite() && it.y.isFinite()) }
            check(name, l, design.getJSONObject("layout"))
            // The island's compact parts lay out too (the collapsed line).
            val island = design.getJSONObject("layout").getJSONObject("island")
            for (key in listOf("compact_leading", "center", "compact_trailing")) LiveLayout(l, sizer).layout(island.getJSONObject(key), 160f, 40f)
        }
    }

    /** Calls [visit] on [n] and every node below it. */
    private fun walk(n: Node, visit: (Node) -> Unit) {
        visit(n)
        n.children.forEach { walk(it, visit) }
    }

    /** Every path reads, every colour parses, every `use` names a def and every `numbers` binding is an array in the state. */
    private fun check(name: String, l: LiveUpdate, json: Any?) {
        when (json) {
            is JSONObject -> {
                if (json.has("d")) assertNotNull("$name: ${json.optString("d")}", LivePath.parse(json.getString("d")))
                for (key in listOf("color", "bg", "fill", "stroke", "track", "rest", "highlight_color")) {
                    val c = json.optString(key)
                    if (c.startsWith("#")) assertNotNull("$name: $c", LiveBox.color(c, null))
                }
                if (json.optString("type") == "use") assertNotNull("$name: ${json.optString("ref")}", LiveLayout(l, sizer).def(json.getString("ref")))
                if (json.optString("type") == "chart" && json.opt("values") is String) assertNotNull(l.values.optJSONArray(json.getString("values")))
                json.keys().forEach { check(name, l, json.get(it)) }
            }
            is JSONArray -> for (i in 0 until json.length()) check(name, l, json.get(i))
        }
    }
}
