package ai.pushpilot.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** LIVE.md "Round two: pieces found while writing all 87 designs". */
class LiveRoundTwoTest {
    /** Fonts faked: each character half the size wide, lines 1.2 times the size tall. */
    private val sizer = LiveSizer { block, maxWidth ->
        when (block.optString("type")) {
            "text" -> {
                val size = block.optDouble("size", 14.0).toFloat()
                Size(minOf(block.optString("text").length * size / 2, maxWidth), size * 1.2f, size)
            }
            else -> Size(0f, 0f)
        }
    }

    /** A live step of [layout] with the state [fields] and the kinds [kinds]. */
    private fun live(layout: String, fields: String = "{}", kinds: String = "[]", title: String = "T"): LiveUpdate = LiveUpdate.parse(
        mapOf(
            "source" to "pushpilot",
            "pushpilot_live" to """{"activity_id":"a","event":"start","timestamp":1,"ends_at":2,
                "attributes":{"notification_id":"n","fields":$kinds,"layout":$layout},
                "state":{"title":${JSONObject.quote(title)},"fields":$fields}}""",
        ),
    )!!

    /** Lays out the step's `lock` in a 376 by 160 card. */
    private fun lock(live: LiveUpdate): Node = LiveLayout(live, sizer).layout(live.layout.getJSONObject("lock"), 376f, 160f, card = true)

    /** Whether `show` lets [block] through for [fields]. */
    private fun shown(block: String, fields: String) = LiveBox.shown(JSONObject(block), live("{}", fields))

    @Test
    fun showComparesTheStateAndHiddenBlocksTakeNoSpace() {
        val state = """{"name":"Ravi","n":3,"zero":0,"off":false,"empty":"","at":1800000000,"list":[]}"""
        assertTrue(shown("""{"show":{"field":"name"}}""", state))
        for (f in listOf("zero", "off", "empty", "missing", "list")) assertFalse(f, shown("""{"show":{"field":"$f"}}""", state))
        assertTrue(shown("""{"show":{"field":"name","is":"Ravi"}}""", state))
        assertFalse(shown("""{"show":{"field":"name","is":"Neha"}}""", state))
        assertTrue(shown("""{"show":{"field":"n","is":3}}""", state))
        assertTrue(shown("""{"show":{"field":"n","is":"3"}}""", state))
        assertTrue(shown("""{"show":{"field":"n","above":2,"below":4}}""", state))
        assertFalse(shown("""{"show":{"field":"n","above":3}}""", state))
        assertFalse(shown("""{"show":{"field":"name","below":4}}""", state))
        assertTrue(shown("""{"show":{"field":"at","above":1700000000}}""", state))
        assertTrue(shown("""{"type":"text"}""", state))

        val node = lock(live(
            """{"lock":{"type":"row","spacing":10,"items":[
                {"type":"text","text":"ab","size":10},
                {"type":"text","text":"lit","size":10,"show":{"field":"stars","above":2}},
                {"type":"text","text":"unlit","size":10,"show":{"field":"stars","below":3}},
                {"type":"text","text":"cd","size":10}]}}""",
            """{"stars":1}""",
        ))
        assertEquals(listOf("ab", "unlit", "cd"), node.children.map { it.block.optString("text") })
        assertEquals(10f + 10f, node.children[1].x - node.children[0].x) // one spacing, not two
        // A hidden root draws nothing.
        assertEquals(0f, lock(live("""{"lock":{"type":"text","text":"x","show":{"field":"gone"}}}""")).w)
    }

    @Test
    fun defColoursComeFromTheUseAndUnsetOnesArePrimary() {
        val l = live("""{"defs":{
              "star":{"type":"shape","shape":"circle","fill":"${'$'}body","stroke":"${'$'}edge","w":10,"h":10,"border":{"color":"${'$'}edge","width":1}},
              "pair":{"type":"row","items":[{"type":"use","ref":"star","colors":{"body":"${'$'}main"}},{"type":"text","text":"${'$'}main","color":"${'$'}main"}]}},
            "lock":{"type":"row","items":[{"type":"use","ref":"star","colors":{"body":"#FF0000"}},{"type":"use","ref":"pair","colors":{"main":"#00FF00"}}]}}""")
        val (one, pair) = lock(l).children
        val star = one.children.single().block
        assertEquals("#FF0000", star.getString("fill"))
        assertEquals("primary", star.getString("stroke"))
        assertEquals("primary", star.getJSONObject("border").getString("color"))
        // A def passes its own colours on to the defs it uses; text is never a colour.
        val row = pair.children.single()
        assertEquals("#00FF00", row.children[0].children.single().block.getString("fill"))
        assertEquals("${'$'}main", row.children[1].block.getString("text"))
        assertEquals("#00FF00", row.children[1].block.getString("color"))
        // The layout itself is left as sent.
        assertEquals("${'$'}body", l.layout.getJSONObject("defs").getJSONObject("star").getString("fill"))
    }

    @Test
    fun pathsAndUsesTakeTheNearestLayerOrDefView() {
        val l = live("""{"defs":{"hill":{"type":"column","view":[50,20],"items":[{"type":"path","d":"M0 20H50"},{"type":"path","d":"M0 0H1","view":[1,1]}]}},
            "lock":{"type":"layer","view":[376,160],"items":[
              {"type":"path","d":"M0 160H376"},
              {"type":"column","items":[{"type":"path","d":"M0 0H10","marker":{"type":"path","d":"M0 0H1"}}]},
              {"type":"layer","items":[{"type":"use","ref":"hill","w":100,"h":40}]},
              {"type":"path","d":"M0 0H1","view":[10,10]}]}}""")
        val node = lock(l)
        val (direct, column, inner, own) = node.children
        assertEquals(JSONArray("[376,160]").toString(), direct.block.getJSONArray("view").toString())
        val nested = column.children.single()
        assertEquals("[376,160]", nested.block.getJSONArray("view").toString())
        assertEquals("[376,160]", nested.block.getJSONObject("marker").getJSONArray("view").toString())
        assertEquals("[10,10]", own.block.getJSONArray("view").toString())
        val use = inner.children.single()
        assertEquals("[376,160]", use.block.getJSONArray("view").toString())
        // Inside the def, its own view; a path with its own keeps it. The layout's view does not reach into a def.
        val hill = use.children.single()
        assertEquals("[50,20]", hill.children[0].block.getJSONArray("view").toString())
        assertEquals("[1,1]", hill.children[1].block.getJSONArray("view").toString())
        // The line along the bottom of the 376 x 160 view lies along the bottom of the card.
        val line = LiveLayout(l, sizer).pathLines(direct.block, direct.w, direct.h)!!.single()
        assertEquals(listOf(Pt(0f, 160f), Pt(376f, 160f)), line)
    }

    @Test
    fun posCentresAnItemAtFractionsOfTheLayer() {
        val node = lock(live(
            """{"lock":{"type":"layer","h":100,"items":[
                {"type":"shape","w":10,"h":10,"pos":[0.33,0.5]},
                {"type":"shape","w":20,"h":20,"pos":["done",1],"at":"top_leading"}]}}""",
            """{"done":0.5}""",
        ))
        val (a, b) = node.children
        assertEquals(376f * 0.33f - 5f, a.x, 0.001f)
        assertEquals(45f, a.y, 0.001f)
        assertEquals(188f - 10f, b.x, 0.001f) // pos wins over at
        assertEquals(90f, b.y, 0.001f)
    }

    @Test
    fun boundArcStartAndTimerFormats() {
        val l = live("{}", """{"v":0.25}""")
        assertEquals(-45f, LiveBox.angle(JSONObject("""{"value":"v","from":-90,"to":90}"""), l), 0.001f)
        assertEquals(30f, LiveBox.angle(30, l), 0f)
        assertEquals(0f, LiveBox.angle(null, l), 0f)
        assertEquals("05:12", LiveBox.timerText(312, "clock"))
        assertEquals("05:12", LiveBox.timerText(312, ""))
        assertEquals("2:10:00", LiveBox.timerText(7800, "clock"))
        assertEquals("25 min", LiveBox.timerText(25 * 60 + 59, "minutes"))
        assertEquals("2h 14m", LiveBox.timerText(2 * 3600 + 14 * 60 + 5, "hours"))
        assertEquals("1d 22h", LiveBox.timerText(86400 + 22 * 3600 + 59, "days"))
        assertEquals("42 sec", LiveBox.timerText(42, "seconds"))
        assertEquals("0 min", LiveBox.timerText(-5, "minutes"))
    }

    @Test
    fun centredBarsGrowBothWaysFromTheMiddle() {
        val bars = LiveGeometry.chartBars(listOf(5.0, 10.0), 0.0, 10.0, 100f, 100f, 10f, center = true)
        assertEquals(listOf(Rect4(0f, 25f, 45f, 50f), Rect4(55f, 0f, 45f, 100f)), bars)
    }

    @Test
    fun gridLabelsMasksAndTheCurrentCell() {
        val l = live(
            """{"lock":{"type":"column","items":[
                {"type":"grid","w":100,"h":20,"cols":5,"count":5,"labels":"{{code}}","filled":"revealed","current":"step",
                  "tile":{"type":"text","text":"{{label}}","size":10},
                  "empty":{"type":"text","text":"?{{label}}","size":10},
                  "current_tile":{"type":"text","runs":[{"text":"[{{label}}]"}],"size":10}},
                {"type":"grid","w":60,"h":20,"cols":3,"count":3,"filled":2,"labels":["{{a}}","B"],"tile":{"type":"text","text":"{{label}}","size":10}}]}}""",
            """{"code":"G🌸AT","revealed":[2,0,1,0],"step":3,"a":"Al"}""",
            """[{"name":"code","kind":"text"},{"name":"revealed","kind":"numbers"},{"name":"step","kind":"number"},{"name":"a","kind":"text"}]""",
        )
        val (word, list) = lock(l).children
        assertEquals(listOf("G", "?🌸", "A", "", "?"), word.children.map { it.block.optString("text") })
        assertEquals("[T]", word.children[3].block.getJSONArray("runs").getJSONObject(0).getString("text"))
        // A list gives one text per cell, tokens filled; past its end the label is empty.
        assertEquals(listOf("Al", "B"), list.children.map { it.block.optString("text") })
        assertEquals(LiveResolve.labels(JSONArray("""["x"]"""), 3) { it }, listOf("x", "", ""))
        assertEquals(LiveResolve.labels(null, 2) { it }, listOf("", ""))
    }

    @Test
    fun everyDesignReadsAndLaysOut() {
        val dir = File(javaClass.getResource("/live-designs/air_run.json")!!.toURI()).parentFile
        val files = dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue("${files.size} designs", files.size >= 87)
        for (file in files) {
            val name = file.nameWithoutExtension
            val design = JSONObject(file.readText())
            val fields = design.getJSONArray("fields")
            val kinds = (0 until fields.length()).associate { fields.getJSONObject(it).let { f -> f.getString("name") to f.getString("kind") } }
            val state = design.getJSONObject("state")
            val values = JSONObject()
            for (key in state.keys()) if (key != "title") values.put(key, if (kinds[key] == "time") 1_800_000_000L else state.get(key))
            val l = live(design.getJSONObject("layout").toString(), values.toString(), fields.toString(), state.optString("title"))
            val node = lock(l)
            assertEquals(name, 376f, node.w)
            walk(node) { assertTrue("$name: ${it.block}", it.w.isFinite() && it.h.isFinite() && it.x.isFinite() && it.y.isFinite()) }
            walk(node) { if (it.block.has("d")) assertTrue("$name: ${it.block.optString("d")}", LivePath.parse(it.block.getString("d")) != null) }
            walk(node) { n -> listOf("fill", "stroke", "color", "bg").forEach { assertFalse("$name: ${n.block}", n.block.optString(it).startsWith("$")) } }
            val island = design.getJSONObject("layout").optJSONObject("island") ?: continue
            for (key in listOf("compact_leading", "center", "compact_trailing")) island.optJSONObject(key)?.let { LiveLayout(l, sizer).layout(it, 160f, 40f) }
        }
    }

    /** Calls [visit] on [n] and every node below it. */
    private fun walk(n: Node, visit: (Node) -> Unit) {
        visit(n)
        n.children.forEach { walk(it, visit) }
    }
}
