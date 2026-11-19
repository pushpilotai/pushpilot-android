package ai.pushpilot.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The engine's second queue: in-app events go to `in-app/events` in batches, like push events. */
class InAppEngineTest {
    private val store = FakeStore()
    private val scheduler = FakeScheduler()
    private val transport = FakeTransport(scheduler)

    /** An engine whose installation has been registered, with the register call cleared. */
    private fun registered(): Engine = Engine(store, transport, scheduler, Random(7)) { emptyMap() }.also {
        it.start()
        scheduler.advance(60_000)
        transport.calls.clear()
    }

    /** The in-app event requests made so far. */
    private fun inAppCalls() = transport.calls.filter { it.path == "in-app/events" }

    @Test
    fun inAppEventsCarryTheContractFieldsAndTheirOwnTime() {
        val engine = registered()
        engine.trackInApp("m1", "shown", null, 1_700_000_000_123L)
        engine.trackInApp("m1", "clicked", "buy", 1_700_000_001_000L)
        scheduler.advance(60_000)
        val sent = inAppCalls().single().body.getJSONArray("events")
        assertEquals(2, sent.length())
        val shown = sent.getJSONObject(0)
        assertEquals("m1", shown.getString("message_id"))
        assertEquals(store.get(Engine.ID), shown.getString("installation_id"))
        assertEquals("shown", shown.getString("type"))
        assertEquals("2023-11-14T22:13:20.123Z", shown.getString("at"))
        assertFalse(shown.has("button_id"))
        assertEquals("buy", sent.getJSONObject(1).getString("button_id"))
        assertNull(store.get(Engine.IN_APP_EVENTS))
    }

    @Test
    fun pushAndInAppEventsGoToTheirOwnRoutesInBatchesOfOneHundred() {
        val engine = registered()
        engine.track("n1", "delivered")
        repeat(150) { engine.trackInApp("m$it", "shown", null, scheduler.now()) }
        scheduler.advance(60_000)
        assertEquals(listOf("events", "in-app/events", "in-app/events"), transport.calls.map { it.path })
        assertEquals(listOf(100, 50), inAppCalls().map { it.body.getJSONArray("events").length() })
    }

    @Test
    fun aFailedBatchIsResentUnchanged() {
        val engine = registered()
        transport.responses += Response(503)
        engine.trackInApp("m1", "dismissed", null, 1_700_000_000_000L)
        scheduler.advance(Engine.COALESCE_MS)
        assertEquals(1, inAppCalls().size)
        assertTrue(store.get(Engine.IN_APP_EVENTS)!!.contains("m1"))
        scheduler.advance(60_000)
        assertEquals(2, inAppCalls().size)
        assertEquals(inAppCalls()[0].body.toString(), inAppCalls()[1].body.toString())
        assertNull(store.get(Engine.IN_APP_EVENTS))
    }

    @Test
    fun syncedInstallationIdWaitsForAnAcceptedSyncAndForgetDropsTheQueue() {
        val fresh = Engine(store, transport, scheduler, Random(7)) { emptyMap() }
        fresh.start()
        assertNull(fresh.syncedInstallationId())
        scheduler.advance(60_000)
        assertEquals(store.get(Engine.ID), fresh.syncedInstallationId())
        fresh.trackInApp("m1", "shown", null, scheduler.now())
        fresh.forget()
        scheduler.advance(60_000)
        assertTrue(inAppCalls().isEmpty())
        assertNull(fresh.syncedInstallationId())
    }
}
