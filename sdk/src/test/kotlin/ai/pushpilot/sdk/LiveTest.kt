package ai.pushpilot.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTest {
    /** A live step exactly as the server's `fcm_message` builds it (LIVE.md "Step 3 as built"). */
    private fun step(
        event: String = "update",
        timestamp: Long = 1_000,
        fields: JSONObject = JSONObject("""{"home_score": 2, "kick_off": 1767268800, "progress": 0.25}"""),
    ): Map<String, String> {
        val live = JSONObject()
            .put("activity_id", "a1")
            .put("event", event)
            .put("timestamp", timestamp)
            .put(
                "attributes",
                JSONObject(
                    """{"activity_id":"a1","notification_id":"s1","name":"Ajax v PSV","url":"myapp://m/7","accent":"#D2122E",
                    "fields":[{"name":"home_score","kind":"number"},{"name":"kick_off","kind":"time"},{"name":"progress","kind":"number"},{"name":"status","kind":"text","max":80}],
                    "layout":{"lock":{"type":"row","items":[{"type":"image","url":"https://e.com/{{home_score}}.png"},{"type":"text","text":"{{title}}"}]},
                      "promoted":{"image":{"type":"image","url":"https://e.com/p.png"}}}}""",
                ),
            )
            .put("state", JSONObject().put("title", "Ajax 2-0").put("fields", fields))
            .put("ends_at", 2_000L)
            .put("dismiss_at", JSONObject.NULL)
            .put("promoted", true)
        if (event == "update") live.put("alert", JSONObject("""{"title":"Goal!","body":"Ajax 2-0"}"""))
        return mapOf("source" to "pushpilot", "pushpilot_live" to live.toString())
    }

    @Test
    fun parsesTheServersShape() {
        val live = LiveUpdate.parse(step())!!
        assertEquals(listOf("a1", "s1", "update", "Ajax 2-0", "myapp://m/7", "#D2122E", "Goal!", "Ajax 2-0"),
            listOf(live.activityId, live.notificationId, live.event, live.title, live.url, live.accent, live.alertTitle, live.alertBody))
        assertEquals(1_000L, live.timestampMs)
        assertEquals(2_000L, live.endsAtSec)
        assertNull(live.dismissAtSec)
        assertTrue(live.promoted)
        assertEquals("time", live.kinds["kick_off"])
    }

    @Test
    fun onlyLiveStepsAreLiveAndBadOnesAreDropped() {
        assertFalse(LiveUpdate.isLive(mapOf("source" to "pushpilot", "pushpilot" to "{}")))
        assertFalse(LiveUpdate.isLive(mapOf("pushpilot_live" to "{}")))
        assertNull(LiveUpdate.parse(mapOf("source" to "pushpilot", "pushpilot_live" to "not json")))
        assertNull(LiveUpdate.parse(mapOf("source" to "pushpilot", "pushpilot_live" to "{}")))
        val unknown = JSONObject(step()["pushpilot_live"]!!).put("event", "pause").toString()
        assertNull(LiveUpdate.parse(mapOf("source" to "pushpilot", "pushpilot_live" to unknown)))
    }

    @Test
    fun fillsTokensFromTheState() {
        val live = LiveUpdate.parse(step())!!
        val text = live.fill("{{title}} · {{ home_score }} · {{kick_off}} · {{status|Live}} · {{status}}.") { "at $it" }
        assertEquals("Ajax 2-0 · 2 · at 1767268800 · Live · .", text)
        assertEquals("0.25", live.fill("{{progress}}"))
        assertEquals("", live.fill(null))
    }

    @Test
    fun progressFollowsItsFieldOrItsTimes() {
        val live = LiveUpdate.parse(step(fields = JSONObject("""{"progress": 1.5, "from": 100, "to": 200}""")))!!
        assertEquals(1.0, live.fraction(JSONObject("""{"value":"progress"}"""), 0), 0.0)
        assertEquals(0.25, live.fraction(JSONObject("""{"from":"from","to":"to"}"""), 125_000), 1e-9)
        assertEquals(0.0, live.fraction(JSONObject("""{"from":"from","to":"missing"}"""), 125_000), 0.0)
    }

    @Test
    fun memoryShowsEachStepOnceInOrderAndNeverAfterADismissal() {
        val memory = LiveMemory(MapStore())
        val parse = { event: String, ts: Long -> LiveUpdate.parse(step(event, ts))!! }
        assertEquals(LiveMemory.Verdict.SHOW_FIRST, memory.accept(parse("start", 1_000), 100))
        assertEquals(LiveMemory.Verdict.DROP, memory.accept(parse("update", 1_000), 100))
        assertEquals(LiveMemory.Verdict.DROP, memory.accept(parse("update", 900), 100))
        assertEquals(LiveMemory.Verdict.SHOW, memory.accept(parse("update", 1_100), 100))
        assertTrue(memory.dismiss("a1", 100))
        assertFalse(memory.dismiss("a1", 100))
        assertEquals(LiveMemory.Verdict.DROP, memory.accept(parse("update", 1_200), 100))
        assertFalse(memory.dismiss("unknown", 100))
    }

    @Test
    fun memoryDrawsNothingButTheEndAfterAnEndAndForgetsWhenOver() {
        val store = MapStore()
        val memory = LiveMemory(store)
        assertEquals(LiveMemory.Verdict.SHOW_FIRST, memory.accept(LiveUpdate.parse(step("end", 5_000))!!, 100))
        assertEquals(LiveMemory.Verdict.DROP, memory.accept(LiveUpdate.parse(step("update", 6_000))!!, 100))
        assertFalse("an ended activity is not dismissed", memory.dismiss("a1", 100))
        assertEquals(listOf<String>(), memory.expired(1_999))
        assertEquals(listOf("a1"), memory.expired(2_001))
        assertNull(store.get(LiveMemory.KEY))
        assertEquals(LiveMemory.Verdict.SHOW_FIRST, memory.accept(LiveUpdate.parse(step("update", 7_000))!!, 100))
    }

    @Test
    fun findsEveryImageUrl() {
        val urls = LinkedHashSet<String>()
        LiveNotifier.collectUrls(LiveUpdate.parse(step())!!.layout, urls)
        assertEquals(setOf("https://e.com/{{home_score}}.png", "https://e.com/p.png"), urls)
    }

    @Test
    fun syncSendsTheLiveObjectAsJson() {
        val sent = ArrayList<String>()
        val tasks = ArrayDeque<() -> Unit>()
        val scheduler = object : Scheduler {
            override fun now() = 0L
            override fun schedule(delayMs: Long, task: () -> Unit) { tasks += task }
        }
        val engine = Engine(MapStore(), { _, json -> sent += json; Response(200, "{}") }, scheduler) {
            mapOf("live" to JSONObject().put("supported", true).put("enabled", true).put("promoted", false))
        }
        engine.start()
        while (tasks.isNotEmpty()) tasks.removeFirst()()
        val live = JSONObject(sent.single()).getJSONObject("live")
        assertEquals(listOf(true, true, false), listOf(live.getBoolean("supported"), live.getBoolean("enabled"), live.getBoolean("promoted")))
    }

    /** An in-memory [Store]. */
    private class MapStore : Store {
        private val values = HashMap<String, String>()
        override fun get(key: String) = values[key]
        override fun put(vararg pairs: Pair<String, String?>) {
            for ((k, v) in pairs) if (v == null) values.remove(k) else values[k] = v
        }
    }
}
