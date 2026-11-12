package ai.pushpilot.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TapTest {
    @Test
    fun tappedNotificationReadsTheIdAndDataTheNotificationCarries() {
        val data = mapOf("order_id" to "42", "title" to "the customer's own title key")
        // The same JSON the notification's PendingIntent carries.
        val tapped = TappedNotification.from("n1", JSONObject(data).toString())!!
        assertEquals("n1", tapped.notificationId)
        assertEquals(data, tapped.data)
    }

    @Test
    fun tappedNotificationIsNullWithoutAnId() {
        assertNull(TappedNotification.from(null, """{"a":"1"}"""))
        assertNull(TappedNotification.from(" ", """{"a":"1"}"""))
    }

    @Test
    fun tappedNotificationWithMissingOrBadDataHasEmptyData() {
        assertTrue(TappedNotification.from("n1", null)!!.data.isEmpty())
        assertTrue(TappedNotification.from("n1", "{oops")!!.data.isEmpty())
    }
}

class RouteTapTest {
    private val opened = ArrayList<String>()

    /** Routes [url] and returns what happened, in order. */
    private fun route(url: String?, handler: Boolean, linkOpens: Boolean = true): List<String> {
        opened.clear()
        routeTap(
            url,
            if (handler) { link -> opened += "handler:$link" } else null,
            openApp = { opened += "app" },
            openLink = { link -> opened += "link:$link"; linkOpens },
        )
        return opened
    }

    @Test
    fun withoutALinkTheAppOpensAndTheHandlerIsNotCalled() {
        assertEquals(listOf("app"), route(null, handler = true))
        assertEquals(listOf("app"), route(null, handler = false))
    }

    @Test
    fun aHandlerGetsTheLinkOnceAfterTheAppOpensAndTheSdkOpensNothing() {
        assertEquals(listOf("app", "handler:myapp://orders/42"), route("myapp://orders/42", handler = true))
    }

    @Test
    fun withoutAHandlerTheSdkOpensTheLink() {
        assertEquals(listOf("link:https://example.com"), route("https://example.com", handler = false))
    }

    @Test
    fun withoutAHandlerALinkNothingOpensFallsBackToTheApp() {
        assertEquals(listOf("link:other://x", "app"), route("other://x", handler = false, linkOpens = false))
    }
}
