package ai.pushpilot.sdk.inapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageTest {
    private val id = "0b3e8d8e-6a4f-4d55-9f7a-2b8a1c6a9f10"

    /** One message as the server writes it. */
    private fun message(): String =
        """{"id":"$id","name":"Welcome","format":"modal","trigger":"app_open","trigger_name":null,
           "html_url":"https://c.example/v3.html","light_html_url":null,"html_version":3,
           "assets":["https://c.example/hero.png"],"cap_per_device":2,"shown_count":1,
           "coach_target":null,"starts_at":null,"ends_at":null}"""

    @Test
    fun parsesTheContractAnswer() {
        val fetched = parseFetch("""{"messages":[${message()}],"allowed_origins":["https://api.example.com"]}""")!!
        val m = fetched.messages.single()
        assertEquals(id, m.id)
        assertEquals("modal", m.format)
        assertEquals("app_open", m.trigger)
        assertNull(m.triggerName)
        assertNull(m.lightHtmlUrl)
        assertEquals(3L, m.htmlVersion)
        assertEquals(listOf("https://c.example/hero.png"), m.assets)
        assertEquals(2, m.capPerDevice)
        assertEquals(1, m.shownCount)
        assertNull(m.coachTarget)
        assertNull(m.startsAt)
        assertEquals(listOf("https://api.example.com"), fetched.origins)
    }

    @Test
    fun unreadableMessagesAreDroppedOneByOne() {
        val bad = listOf(
            message().replace(id, "../../etc"),
            message().replace("\"html_url\":\"https://c.example/v3.html\",", ""),
            message().replace("\"starts_at\":null", "\"starts_at\":\"tomorrow\""),
            message().replace("\"html_version\":3", "\"html_version\":null"),
        )
        val body = """{"messages":[${(bad + message().replace("\"cap_per_device\":2", "\"cap_per_device\":null")).joinToString(",")}],"allowed_origins":[]}"""
        val fetched = parseFetch(body)!!
        assertEquals(1, fetched.messages.size)
        assertNull(fetched.messages.single().capPerDevice)
        assertNull(parseFetch("<html>"))
        assertEquals(emptyList<InAppMessage>(), parseFetch("{}")!!.messages)
    }

    @Test
    fun parsesPostgresAndZuluTimes() {
        assertEquals(1_763_546_400_000L, parseTime("2025-11-19T10:00:00Z"))
        assertEquals(1_763_546_400_000L, parseTime("2025-11-19T10:00:00+00:00"))
        assertEquals(1_763_546_400_123L, parseTime("2025-11-19T10:00:00.123456+00:00"))
        assertEquals(1_763_546_400_000L, parseTime("2025-11-19T15:30:00+05:30"))
        assertEquals(1_763_546_400_000L, parseTime("2025-11-19T05:00:00-05"))
        assertEquals(1_763_546_400_500L, parseTime("2025-11-19 10:00:00.5Z"))
        assertNull(parseTime("2025-13-40T10:00:00Z"))
        assertNull(parseTime("2025-11-19"))
    }
}
