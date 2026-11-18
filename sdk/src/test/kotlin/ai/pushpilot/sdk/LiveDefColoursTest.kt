package ai.pushpilot.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** A `use` inside a def keeps the def's colours, and its own `colors` lay over them. */
class LiveDefColoursTest {
    @Test
    fun nestedUsesInheritAndOverlayTheEnclosingColours() {
        val dot = """{"type":"shape","w":4,"h":4,"fill":"${'$'}a","stroke":"${'$'}b"}"""
        val layout = """{"defs":{"dot":$dot,"pair":{"type":"row","items":[
            {"type":"use","ref":"dot"},
            {"type":"use","ref":"dot","colors":{"b":"${'$'}a","a":"#0000FF"}},
            {"type":"use","ref":"dot","colors":{"a":"${'$'}c"}}]}},
            "lock":{"type":"use","ref":"pair","colors":{"a":"#FF0000","b":"#00FF00"}}}"""
        val live = LiveUpdate.parse(
            mapOf(
                "source" to "pushpilot",
                "pushpilot_live" to """{"activity_id":"x","event":"start","timestamp":1,"ends_at":2,
                    "attributes":{"notification_id":"n","fields":[],"layout":$layout},"state":{"title":"T","fields":{}}}""",
            ),
        )!!
        val node = LiveLayout(live, { _, _ -> Size(0f, 0f) }).layout(live.layout.getJSONObject("lock"), 376f, 160f, card = true)
        val dots = node.children.single().children.map { it.children.single().block }
        /** The dot's fill and stroke. */
        fun look(b: JSONObject) = b.getString("fill") to b.getString("stroke")
        assertEquals("#FF0000" to "#00FF00", look(dots[0])) // no colors: the enclosing ones
        assertEquals("#0000FF" to "#FF0000", look(dots[1])) // overlaid; ${'$'}a is the enclosing a
        assertEquals("primary" to "#00FF00", look(dots[2])) // ${'$'}c is unset in the enclosing def
    }
}
