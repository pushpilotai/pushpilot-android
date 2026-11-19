package ai.pushpilot.sdk.inapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EligibilityTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val now = 1_800_000_000_000L
    private val a = message("00000000-0000-4000-8000-00000000000a")
    private val b = message("00000000-0000-4000-8000-00000000000b")

    /** Picks with every message cached and no marked views unless given. */
    private fun pickOf(
        messages: List<InAppMessage>,
        trigger: String = APP_OPEN,
        name: String? = null,
        local: Map<String, Int> = emptyMap(),
        targets: Map<String, Frame> = emptyMap(),
        cached: Set<String>? = null,
    ) = pick(messages, trigger, name, now, { local[it] ?: 0 }, { targets[it] }, { m -> if (cached == null || m.id in cached) "<p>${m.id}</p>" else null })

    @Test
    fun firstEligibleInFetchOrderWithTheRightTrigger() {
        assertEquals(a.id, pickOf(listOf(a, b))!!.message.id)
        val checkout = b.copy(trigger = SHOW_IN_APP, triggerName = "checkout")
        assertEquals(checkout.id, pickOf(listOf(a, checkout), SHOW_IN_APP, "checkout")!!.message.id)
        assertNull(pickOf(listOf(a, checkout), SHOW_IN_APP, "cart"))
        assertNull(pickOf(listOf(checkout)))
    }

    @Test
    fun onlyCachedPagesShow() {
        assertEquals(b.id, pickOf(listOf(a, b), cached = setOf(b.id))!!.message.id)
        assertNull(pickOf(listOf(a, b), cached = emptySet()))
    }

    @Test
    fun capUsesTheLargerOfLocalAndServerCounts() {
        val capped = a.copy(capPerDevice = 2, shownCount = 1)
        assertEquals(a.id, pickOf(listOf(capped), local = mapOf(a.id to 1))!!.message.id)
        assertNull(pickOf(listOf(capped), local = mapOf(a.id to 2)))
        assertNull(pickOf(listOf(capped.copy(shownCount = 2))))
        assertEquals(a.id, pickOf(listOf(a.copy(capPerDevice = null)), local = mapOf(a.id to 99))!!.message.id)
    }

    @Test
    fun datesAreCheckedAgainstTheDeviceClock() {
        assertNull(pickOf(listOf(a.copy(startsAt = now + 1))))
        assertNull(pickOf(listOf(a.copy(endsAt = now))))
        assertEquals(a.id, pickOf(listOf(a.copy(startsAt = now, endsAt = now + 1)))!!.message.id)
    }

    @Test
    fun coachMarksNeedTheirViewOnScreenAndCarryItsFrame() {
        val coach = a.copy(format = COACH_MARK, coachTarget = "compose")
        assertEquals(b.id, pickOf(listOf(coach, b))!!.message.id)
        assertNull(pickOf(listOf(a.copy(format = COACH_MARK))))
        val frame = Frame(1.0, 2.0, 3.0, 4.0)
        val chosen = pickOf(listOf(coach, b), targets = mapOf("compose" to frame))!!
        assertEquals(coach.id, chosen.message.id)
        assertEquals(frame, chosen.target)
        assertNull(pickOf(listOf(b.copy(coachTarget = "compose")), targets = mapOf("compose" to frame))!!.target)
    }

    @Test
    fun dismissedOnlyWhenNothingWasTracked() {
        val quiet = Showing("m", 100)
        assertEquals(InAppEvent("m", "shown", null, 100), quiet.shown())
        assertEquals(InAppEvent("m", "dismissed", null, 200), quiet.close(200))
        assertNull(quiet.close(300))

        val clicked = Showing("m", 100)
        assertEquals(InAppEvent("m", "clicked", "buy", 150), clicked.track("buy", 150))
        assertNull(clicked.close(200))
        assertNull(clicked.track("late", 250))
    }

    @Test
    fun trackRefusesIdsTheServerWouldRejectAndFloods() {
        val showing = Showing("m", 0)
        assertNull(showing.track("", 1))
        assertNull(showing.track("x".repeat(65), 1))
        // 22 three-byte characters are 66 bytes: over the server's 64-byte limit.
        assertNull(showing.track("あ".repeat(22), 1))
        assertEquals("x".repeat(64), showing.track("x".repeat(64), 1)!!.buttonId)
        // A refused id does not count as a track, so this showing still has one click.
        repeat(MAX_CLICKS_PER_SHOWING + 10) { showing.track("b", 2) }
        assertNull(showing.track("b", 3))
        assertNull(showing.close(4))
        val refusedOnly = Showing("m", 0)
        refusedOnly.track("", 1)
        assertEquals("dismissed", refusedOnly.close(2)!!.type)
    }

    @Test
    fun cacheInlinesAssetsReplacesOldVersionsAndRetriesFailures() {
        val dir = File(folder.root, "cache")
        val files = mutableMapOf(
            "https://c/v3.html" to "<img src=\"https://c/hero.png\">",
            "https://c/hero.png" to "PNG",
        )
        val asked = mutableListOf<String>()
        val cache = InAppCache(dir, slow = false, download = { url -> asked += url; files[url]?.let { Download(it.toByteArray(), null) } }) {
            java.util.Base64.getEncoder().encodeToString(it)
        }
        val m = a.copy(assets = listOf("https://c/hero.png"))
        cache.update(listOf(m))
        assertEquals("<img src=\"data:image/png;base64,UE5H\">", cache.read(m))

        // Cached files are not downloaded again.
        asked.clear()
        cache.update(listOf(m))
        assertEquals(emptyList<String>(), asked)

        // A missing asset leaves nothing cached for that message; the next update retries it.
        val v4 = m.copy(htmlVersion = 4, htmlUrl = "https://c/v4.html", assets = listOf("https://c/gone.png"))
        files["https://c/v4.html"] = "<img src=\"https://c/gone.png\">"
        cache.update(listOf(v4))
        assertNull(cache.read(v4))
        assertNull(cache.read(m))
        assertEquals(emptyList<String>(), dir.list()!!.toList())
        files["https://c/gone.png"] = "GIF"
        cache.update(listOf(v4))
        assertEquals(listOf("${m.id}-v4.html"), dir.list()!!.toList())

        // A message missing from a new answer is dropped.
        cache.update(emptyList())
        assertNull(cache.read(v4))
    }
}
