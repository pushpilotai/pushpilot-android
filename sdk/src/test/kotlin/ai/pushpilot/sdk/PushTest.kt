package ai.pushpilot.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PushTest {
    /** The `pushpilot` JSON object of a complete push, as the server builds it. */
    private val fields: Map<String, Any> = mapOf(
        "source" to "pushpilot",
        "notification_id" to "6f1c2b9e-3a4d-4c8e-9f00-1b2c3d4e5f60",
        "title" to "नमस्ते 👋",
        "body" to "مرحبا — உங்கள் ஆர்டர் அனுப்பப்பட்டது",
        "image_url" to "https://example.com/a.png",
        "url" to "myapp://orders/42",
        "buttons" to JSONArray("""[{"id":"track","label":"ट्रैक","url":"https://example.com/t"},{"id":"later","label":"Later"}]"""),
        "data" to JSONObject("""{"order_id":"42","coupon":"दस"}"""),
        "unknown_future_key" to "ignored",
    )

    /** The FCM data map carrying [fields]. */
    private fun message(fields: Map<String, Any> = this.fields) =
        mapOf("source" to "pushpilot", "pushpilot" to JSONObject(fields).toString())

    @Test
    fun parsesEveryFieldOfTheNestedObjectInAnyScript() {
        val push = Push.parse(message())!!
        assertEquals("6f1c2b9e-3a4d-4c8e-9f00-1b2c3d4e5f60", push.notificationId)
        assertEquals("नमस्ते 👋", push.title)
        assertEquals("مرحبا — உங்கள் ஆர்டர் அனுப்பப்பட்டது", push.body)
        assertEquals("https://example.com/a.png", push.imageUrl)
        assertEquals("myapp://orders/42", push.url)
        assertEquals(listOf("track", "later"), push.buttons.map { it.id })
        assertEquals("ट्रैक", push.buttons[0].label)
        assertEquals("https://example.com/t", push.buttons[0].url)
        assertNull(push.buttons[1].url)
        assertEquals(mapOf("order_id" to "42", "coupon" to "दस"), push.data)
    }

    @Test
    fun readsTheExactServerString() {
        val raw = """{"source":"pushpilot","notification_id":"n1","title":"Hi","buttons":[{"id":"a","label":"A"}],"data":{"k":"v"}}"""
        val push = Push.parse(mapOf("source" to "pushpilot", "pushpilot" to raw))!!
        assertEquals("n1", push.notificationId)
        assertEquals(listOf("a"), push.buttons.map { it.id })
        assertEquals(mapOf("k" to "v"), push.data)
    }

    @Test
    fun minimalPushHasOnlyIdAndTitle() {
        val push = Push.parse(message(mapOf("notification_id" to "n1", "title" to "Hi")))!!
        assertNull(push.body)
        assertNull(push.imageUrl)
        assertNull(push.url)
        assertTrue(push.buttons.isEmpty())
        assertTrue(push.data.isEmpty())
    }

    @Test
    fun notOursIsNotParsed() {
        assertNull(Push.parse(message() - "source"))
        assertNull(Push.parse(message() + ("source" to "other")))
        assertTrue(!Push.isOurs(mapOf("title" to "someone else's push")))
    }

    @Test
    fun oursButMalformedIsNotParsed() {
        val malformed = listOf(
            mapOf("source" to "pushpilot"),
            mapOf("source" to "pushpilot", "pushpilot" to ""),
            mapOf("source" to "pushpilot", "pushpilot" to "{not json"),
            mapOf("source" to "pushpilot", "pushpilot" to "[1,2]"),
            // The dropped top-level format is not read any more.
            mapOf("source" to "pushpilot", "notification_id" to "n1", "title" to "Hi"),
            message(fields - "notification_id"),
            message(fields + ("title" to "  ")),
        )
        for (data in malformed) {
            assertTrue(Push.isOurs(data))
            assertNull(Push.parse(data))
        }
    }

    @Test
    fun wrongShapedOptionalFieldsAreDroppedButThePushIsKept() {
        val push = Push.parse(message(fields + ("buttons" to JSONObject("""{"id":"a"}""")) + ("data" to JSONArray("[1,2]"))))!!
        assertTrue(push.buttons.isEmpty())
        assertTrue(push.data.isEmpty())
        val strings = Push.parse(message(fields + ("buttons" to "[]") + ("data" to "{}")))!!
        assertTrue(strings.buttons.isEmpty())
        assertTrue(strings.data.isEmpty())
    }

    @Test
    fun buttonsAreCappedAtThreeAndIncompleteOnesSkipped() {
        val buttons = """[{"id":"a","label":"A"},{"label":"no id"},{"id":"b"},{"id":"c","label":"C","url":null},
            |{"id":"d","label":"D"},{"id":"e","label":"E"},"text"]""".trimMargin()
        val push = Push.parse(message(fields + ("buttons" to JSONArray(buttons))))!!
        assertEquals(listOf("a", "c", "d"), push.buttons.map { it.id })
        assertNull(push.buttons[1].url)
    }

    @Test
    fun nonHttpsImageIsDropped() {
        assertNull(Push.parse(message(fields + ("image_url" to "http://example.com/a.png")))!!.imageUrl)
    }

    @Test
    fun customDataSkipsNullValues() {
        val push = Push.parse(message(fields + ("data" to JSONObject("""{"a":"1","b":null,"c":2}"""))))!!
        assertEquals(mapOf("a" to "1", "c" to "2"), push.data)
    }

    @Test
    fun tapOpensLinkAppOrDismisses() {
        val push = Push.parse(message())!!
        assertEquals("myapp://orders/42", (push.tap(null) as Tap.OpenUrl).url)
        assertEquals("https://example.com/t", (push.tap("track") as Tap.OpenUrl).url)
        assertSame(Tap.Dismiss, push.tap("later"))
        // A stale or unknown button id falls back to opening the app.
        assertSame(Tap.OpenApp, push.tap("gone"))
        assertSame(Tap.OpenApp, Push.parse(message(fields - "url"))!!.tap(null))
    }
}
