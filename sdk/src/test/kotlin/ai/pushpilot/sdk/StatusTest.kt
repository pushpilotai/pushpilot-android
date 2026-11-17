package ai.pushpilot.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** [Engine.status]: the installation, the live flags the server accepted and the last sync, without any token. */
class StatusTest {
    private val store = FakeStore()
    private val scheduler = FakeScheduler()
    private val transport = FakeTransport(scheduler)
    private val device = mutableMapOf<String, Any>(
        "notification_permission" to "granted",
        "live" to JSONObject().put("supported", true).put("enabled", true).put("promoted", false),
    )

    /** An engine on the fakes, started as at launch. */
    private fun launch(): Engine = Engine(store, transport, scheduler, Random(42)) { device }.also { it.start() }

    /** Runs every pending task. */
    private fun settle() = scheduler.advance(60_000)

    @Test
    fun `status follows what the server accepted`() {
        val engine = launch()
        engine.setPushToken("fid-secret")
        settle()
        val status = engine.status()
        assertEquals(store.get(Engine.ID), status.installationId)
        assertEquals(true, status.liveSupported)
        assertEquals(true, status.liveEnabled)
        assertEquals(false, status.livePromoted)
        assertEquals("register: OK (HTTP 200)", status.lastSyncResult)
        assertNotNull(status.lastSyncAtMs)
        assertFalse("the status never holds a token", status.toString().contains("fid-secret"))
    }

    @Test
    fun `failed and rejected syncs show in the result`() {
        transport.responses += Response(-1)
        val engine = launch()
        scheduler.advance(Engine.COALESCE_MS)
        assertEquals("register: failed (no answer), retrying", engine.status().lastSyncResult)
        assertNull("nothing accepted yet", engine.status().liveSupported)
        transport.responses += Response(401)
        settle()
        assertTrue(engine.status().lastSyncResult!!.startsWith("register: rejected (HTTP 401)"))
    }
}
