package ai.pushpilot.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.random.Random

/** In-memory [Store]; survives a simulated process death because tests reuse the same instance. */
internal class FakeStore : Store {
    val values = HashMap<String, String>()

    /** Reads one value. */
    override fun get(key: String) = values[key]

    /** Writes or removes each pair. */
    override fun put(vararg pairs: Pair<String, String?>) {
        for ((k, v) in pairs) if (v == null) values.remove(k) else values[k] = v
    }
}

/** Virtual clock and task queue; [advance] runs due tasks in time order. */
internal class FakeScheduler : Scheduler {
    var time = 1_700_000_000_000L
    private val tasks = ArrayList<Pair<Long, () -> Unit>>()

    /** The virtual time. */
    override fun now() = time

    /** Queues [task] at now + delay. */
    override fun schedule(delayMs: Long, task: () -> Unit) {
        tasks += (time + delayMs) to task
    }

    /** Moves time forward by [ms], running every task that falls due on the way. */
    fun advance(ms: Long) {
        val end = time + ms
        while (true) {
            val next = tasks.filter { it.first <= end }.minByOrNull { it.first } ?: break
            tasks.remove(next)
            time = maxOf(time, next.first)
            next.second()
        }
        time = end
    }
}

/** Records each request with the time it was made and answers from a script (default 200). */
internal class FakeTransport(private val scheduler: FakeScheduler) : Transport {
    class Call(val path: String, val body: JSONObject, val at: Long)

    val calls = ArrayList<Call>()
    val responses = ArrayDeque<Response>()

    /** Records the call and returns the next scripted response. */
    override fun post(path: String, json: String): Response {
        calls += Call(path, JSONObject(json), scheduler.now())
        return responses.removeFirstOrNull() ?: Response(200, """{"throttled":false,"warnings":[]}""")
    }
}

class EngineTest {
    private val store = FakeStore()
    private val scheduler = FakeScheduler()
    private val transport = FakeTransport(scheduler)
    private val device = mutableMapOf(
        "notification_permission" to "granted",
        "os_version" to "14",
        "app_version" to "1.2.3",
        "sdk_version" to "1.0.0",
        "device_model" to "Google Pixel 8",
        "locale" to "en-IN",
        "timezone" to "Asia/Kolkata",
    )

    /** A new engine on the shared store, as after a process restart. */
    private fun launch(): Engine = Engine(store, transport, scheduler, Random(42)) { device }.also {
        it.start()
    }

    /** Runs everything due in the next minute. */
    private fun settle() = scheduler.advance(60_000)

    private val last get() = transport.calls.last()

    @Test
    fun firstLaunchRegistersWithAutomaticFieldsOnly() {
        launch()
        settle()
        assertEquals(1, transport.calls.size)
        val body = last.body
        assertEquals("register", last.path)
        UUID.fromString(body.getString("installation_id"))
        assertTrue(body.isNull("token"))
        assertTrue(body.isNull("external_user_id"))
        for ((k, v) in device) assertEquals(v, body.getString(k))
        assertFalse(body.has("topics"))
        assertFalse(body.has("attributes"))
    }

    @Test
    fun rapidChangesCoalesceIntoOneSync() {
        val engine = launch()
        settle()
        engine.setPushToken("tok-1")
        engine.setExternalUserId("user-9")
        engine.setTopics(listOf("sports", "news", "news"))
        engine.setAttributes(mapOf("plan" to "pro", "age" to 30, "vip" to true))
        scheduler.advance(Engine.COALESCE_MS - 1)
        assertEquals(1, transport.calls.size)
        settle()
        assertEquals(2, transport.calls.size)
        val body = last.body
        assertEquals("sync", last.path)
        assertEquals("tok-1", body.getString("token"))
        assertEquals("user-9", body.getString("external_user_id"))
        assertEquals("""["news","sports"]""", body.getJSONArray("topics").toString())
        val attrs = body.getJSONObject("attributes")
        assertEquals("pro", attrs.getString("plan"))
        assertEquals(30, attrs.getInt("age"))
        assertTrue(attrs.getBoolean("vip"))
    }

    @Test
    fun unchangedStateIsNotResentUntilADayPasses() {
        launch()
        settle()
        launch()
        settle()
        assertEquals(1, transport.calls.size)
        scheduler.advance(Engine.DAY_MS)
        launch()
        settle()
        assertEquals(2, transport.calls.size)
        assertEquals("sync", last.path)
    }

    @Test
    fun changedAutomaticFieldIsSentAtNextLaunch() {
        launch()
        settle()
        device["app_version"] = "2.0.0"
        launch()
        settle()
        assertEquals(2, transport.calls.size)
        assertEquals("2.0.0", last.body.getString("app_version"))
    }

    @Test
    fun pendingChangeSurvivesProcessDeath() {
        launch().setPushToken("tok-1")
        // The process dies before the coalesced sync runs.
        launch()
        settle()
        assertEquals(1, transport.calls.size)
        assertEquals("tok-1", last.body.getString("token"))
    }

    @Test
    fun failuresBackOffExponentiallyWithJitterUpToFiveMinutes() {
        repeat(12) { transport.responses += Response(if (it % 2 == 0) 503 else -1) }
        val engine = launch()
        scheduler.advance(Engine.COALESCE_MS)
        repeat(12) { scheduler.advance(Engine.MAX_BACKOFF_MS) }
        assertEquals(13, transport.calls.size)
        val waits = transport.calls.zipWithNext { a, b -> b.at - a.at }
        waits.forEachIndexed { i, wait ->
            val base = minOf(Engine.MAX_BACKOFF_MS, 1000L shl i)
            assertTrue("retry $i waited $wait", wait in base / 2..base)
        }
        // Success resets the backoff: the next failure waits at most a second again.
        transport.responses += Response(500)
        engine.setPushToken("tok-2")
        scheduler.advance(Engine.COALESCE_MS)
        val failedAt = last.at
        scheduler.advance(1_000)
        assertEquals(15, transport.calls.size)
        assertTrue(last.at - failedAt <= 1_000)
    }

    @Test
    fun rateLimitWaitsAtLeastRetryAfterEvenWhenStateChanges() {
        transport.responses += Response(429, "{}", retryAfterSeconds = 120)
        val engine = launch()
        scheduler.advance(Engine.COALESCE_MS)
        val limitedAt = last.at
        engine.setPushToken("tok-1")
        scheduler.advance(119_999)
        assertEquals(1, transport.calls.size)
        scheduler.advance(1)
        assertEquals(2, transport.calls.size)
        assertEquals(limitedAt + 120_000, last.at)
        assertEquals("tok-1", last.body.getString("token"))
    }

    @Test
    fun otherClientErrorsAreNotRetriedButTheNextChangeIsSent() {
        transport.responses += Response(400, """{"detail":"bad"}""")
        val engine = launch()
        repeat(10) { scheduler.advance(Engine.MAX_BACKOFF_MS) }
        assertEquals(1, transport.calls.size)
        engine.setExternalUserId("user-1")
        settle()
        assertEquals(2, transport.calls.size)
        assertEquals("register", last.path)
    }

    @Test
    fun replacementInstallationIdIsAdopted() {
        val replacement = UUID.randomUUID().toString()
        transport.responses += Response(200, """{"installation_id":"$replacement","throttled":false,"warnings":[]}""")
        val engine = launch()
        settle()
        assertEquals(replacement, store.get(Engine.ID))
        // Adopting it does not trigger a redundant sync.
        assertEquals(1, transport.calls.size)
        engine.setPushToken("tok-1")
        settle()
        assertEquals(replacement, last.body.getString("installation_id"))
        assertEquals("sync", last.path)
    }

    @Test
    fun malformedReplacementIdIsIgnored() {
        transport.responses += Response(200, """{"installation_id":"not-a-uuid"}""")
        launch()
        settle()
        assertEquals(transport.calls[0].body.getString("installation_id"), store.get(Engine.ID))
    }

    @Test
    fun logoutClearsOnlyTheExternalUserId() {
        val engine = launch()
        engine.setPushToken("tok-1")
        engine.setExternalUserId("user-9")
        settle()
        val id = store.get(Engine.ID)
        engine.logout()
        settle()
        assertEquals(2, transport.calls.size)
        assertTrue(last.body.isNull("external_user_id"))
        assertEquals("tok-1", last.body.getString("token"))
        assertEquals(id, last.body.getString("installation_id"))
    }

    @Test
    fun forgetDeletesOnServerResetsLocallyAndRetries() {
        val engine = launch()
        engine.setPushToken("tok-1")
        engine.setTopics(listOf("news"))
        settle()
        val oldId = store.get(Engine.ID)
        transport.responses += Response(503)
        engine.forget()
        settle()
        assertEquals(listOf("register", "forget", "forget"), transport.calls.map { it.path })
        assertEquals(oldId, last.body.getString("installation_id"))
        assertEquals(1, last.body.length())
        assertNotEquals(oldId, store.get(Engine.ID))
        assertEquals(null, store.get(Engine.TOKEN))
        assertEquals(null, store.get(Engine.TOPICS))
        // Nothing re-registers in this session; the next launch registers the new id.
        repeat(5) { settle() }
        assertEquals(3, transport.calls.size)
        launch()
        settle()
        assertEquals("register", last.path)
        assertEquals(store.get(Engine.ID), last.body.getString("installation_id"))
        assertTrue(last.body.isNull("token"))
        assertFalse(last.body.has("topics"))
    }

    @Test
    fun forgetBeforeAnySyncMakesNoServerCall() {
        transport.responses += Response(-1)
        val engine = launch()
        scheduler.advance(Engine.COALESCE_MS)
        engine.forget()
        // The pending register's retry also finds nothing left to send.
        repeat(5) { settle() }
        assertEquals(listOf("register"), transport.calls.map { it.path })
    }

    @Test
    fun invalidValuesAreDroppedLocallyInsteadOfFailingTheSync() {
        val engine = launch()
        engine.setPushToken("x".repeat(4097))
        engine.setExternalUserId("   ")
        engine.setTopics(listOf("ok", "has space", "", "a".repeat(256)))
        engine.setAttributes(mapOf("plan" to "pro", "bad" to listOf(1), "nan" to Double.NaN))
        engine.setAttributes(mapOf("plan" to null, "score" to 1.5))
        settle()
        val body = last.body
        assertTrue(body.isNull("token"))
        assertTrue(body.isNull("external_user_id"))
        assertEquals("""["ok"]""", body.getJSONArray("topics").toString())
        val attrs = body.getJSONObject("attributes")
        assertEquals(setOf("plan", "score"), attrs.keys().asSequence().toSet())
        assertTrue(attrs.isNull("plan"))
        assertEquals(1.5, attrs.getDouble("score"), 0.0)
    }

    @Test
    fun topicsAreCappedAtOneHundred() {
        val engine = launch()
        engine.setTopics((1..150).map { "t%03d".format(it) })
        settle()
        assertEquals(100, last.body.getJSONArray("topics").length())
    }
}

class DispatcherTest {
    @Test
    fun callsBeforeInitAreQueuedAndFlushedAfterStartInOrder() {
        val scheduler = FakeScheduler()
        val transport = FakeTransport(scheduler)
        val store = FakeStore()
        val dispatcher = Dispatcher(scheduler)
        dispatcher.submit { setPushToken("tok-1") }
        dispatcher.submit { setExternalUserId("user-1") }
        dispatcher.submit { setExternalUserId("user-2") }
        scheduler.advance(60_000)
        assertTrue(store.values.isEmpty())
        val engine = Engine(store, transport, scheduler, Random(1)) { emptyMap() }
        dispatcher.start(engine)
        // A second init is ignored.
        dispatcher.start(Engine(FakeStore(), transport, scheduler, Random(1)) { emptyMap() })
        scheduler.advance(60_000)
        assertEquals(1, transport.calls.size)
        assertEquals("register", transport.calls[0].path)
        assertEquals("tok-1", transport.calls[0].body.getString("token"))
        assertEquals("user-2", transport.calls[0].body.getString("external_user_id"))
    }
}

class HandleMessageTest {
    @Test
    fun recognisesOnlyPushPilotMessages() {
        assertTrue(PushPilot.handleMessage(mapOf("source" to "pushpilot", "test" to "true")))
        assertFalse(PushPilot.handleMessage(mapOf("source" to "other")))
        assertFalse(PushPilot.handleMessage(emptyMap()))
    }
}
