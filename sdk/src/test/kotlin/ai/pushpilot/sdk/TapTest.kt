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
