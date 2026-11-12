package ai.pushpilot.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PushTest {
    /** A complete data map shaped like the server's `v2_data`. */
    private val full = mapOf(
        "source" to "pushpilot",
        "notification_id" to "6f1c2b9e-3a4d-4c8e-9f00-1b2c3d4e5f60",
        "title" to "नमस्ते 👋",
        "body" to "مرحبا — உங்கள் ஆர்டர் அனுப்பப்பட்டது",
        "image_url" to "https://example.com/a.png",
        "url" to "myapp://orders/42",
        "buttons" to """[{"id":"track","label":"ट्रैक","url":"https://example.com/t"},{"id":"later","label":"Later"}]""",
        "data" to """{"order_id":"42","coupon":"दस"}""",
        "unknown_future_key" to "ignored",
    )

    @Test
    fun parsesEveryFieldIncludingJsonStringsAndAnyScript() {
        val push = Push.parse(full)!!
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
    fun minimalPushHasOnlyIdAndTitle() {
        val push = Push.parse(mapOf("source" to "pushpilot", "notification_id" to "n1", "title" to "Hi"))!!
        assertNull(push.body)
        assertNull(push.imageUrl)
        assertNull(push.url)
        assertTrue(push.buttons.isEmpty())
        assertTrue(push.data.isEmpty())
    }

    @Test
    fun notOursOrIncompleteIsNotParsed() {
        assertNull(Push.parse(full - "source"))
        assertNull(Push.parse(full + ("source" to "other")))
        assertNull(Push.parse(full - "notification_id"))
        assertNull(Push.parse(full + ("title" to "  ")))
        // The old single-device test send: ours, but without payload v2 fields.
        val legacy = mapOf("source" to "pushpilot", "test" to "true")
        assertTrue(Push.isOurs(legacy))
        assertNull(Push.parse(legacy))
    }

    @Test
    fun badJsonFieldsAreDroppedButThePushIsKept() {
        val push = Push.parse(full + ("buttons" to "[{oops") + ("data" to "not json"))!!
        assertTrue(push.buttons.isEmpty())
        assertTrue(push.data.isEmpty())
        val wrongShape = Push.parse(full + ("buttons" to """{"id":"a"}""") + ("data" to "[1,2]"))!!
        assertTrue(wrongShape.buttons.isEmpty())
        assertTrue(wrongShape.data.isEmpty())
    }

    @Test
    fun buttonsAreCappedAtThreeAndIncompleteOnesSkipped() {
        val buttons = """[{"id":"a","label":"A"},{"label":"no id"},{"id":"b"},{"id":"c","label":"C","url":null},
            |{"id":"d","label":"D"},{"id":"e","label":"E"},"text"]""".trimMargin()
        val push = Push.parse(full + ("buttons" to buttons))!!
        assertEquals(listOf("a", "c", "d"), push.buttons.map { it.id })
        assertNull(push.buttons[1].url)
    }

    @Test
    fun nonHttpsImageIsDropped() {
        assertNull(Push.parse(full + ("image_url" to "http://example.com/a.png"))!!.imageUrl)
    }

    @Test
    fun customDataSkipsNullValues() {
        val push = Push.parse(full + ("data" to """{"a":"1","b":null,"c":2}"""))!!
        assertEquals(mapOf("a" to "1", "c" to "2"), push.data)
    }

    @Test
    fun tapOpensLinkAppOrDismisses() {
        val push = Push.parse(full)!!
        assertEquals("myapp://orders/42", (push.tap(null) as Tap.OpenUrl).url)
        assertEquals("https://example.com/t", (push.tap("track") as Tap.OpenUrl).url)
        assertSame(Tap.Dismiss, push.tap("later"))
        // A stale or unknown button id falls back to opening the app.
        assertSame(Tap.OpenApp, push.tap("gone"))
        assertSame(Tap.OpenApp, Push.parse(full - "url")!!.tap(null))
    }
}
