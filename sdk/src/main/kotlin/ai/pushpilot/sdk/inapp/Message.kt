package ai.pushpilot.sdk.inapp

import ai.pushpilot.sdk.log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

/** One message of the fetch answer (IN-APP-SDK.md section 1); times are epoch milliseconds. */
internal data class InAppMessage(
    val id: String,
    val format: String,
    val trigger: String,
    val triggerName: String?,
    val htmlUrl: String,
    val lightHtmlUrl: String?,
    val htmlVersion: Long,
    val assets: List<String>,
    val capPerDevice: Int?,
    val shownCount: Int,
    val coachTarget: String?,
    val startsAt: Long?,
    val endsAt: Long?,
)

/** The whole fetch answer: messages in the server's order, and the app's allowed origins. */
internal data class Fetched(val messages: List<InAppMessage>, val origins: List<String>) {
    companion object {
        /** No messages, as before the first fetch. */
        val EMPTY = Fetched(emptyList(), emptyList())
    }
}

/** Parses the fetch answer; null when it is not the expected JSON. Unreadable messages are dropped one by one. */
internal fun parseFetch(body: String): Fetched? = try {
    val json = JSONObject(body)
    val list = json.optJSONArray("messages") ?: JSONArray()
    val messages = (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.let(::parseMessage) }
    if (messages.size < list.length()) log("${list.length() - messages.size} in-app messages unreadable; skipped")
    Fetched(messages, strings(json.optJSONArray("allowed_origins")))
} catch (e: Exception) {
    null
}

/** One message, or null when a field the SDK relies on is missing or malformed. */
private fun parseMessage(json: JSONObject): InAppMessage? {
    // The id names cache files, so only a real UUID is accepted.
    val id = json.str("id")?.takeIf { isUuid(it) } ?: return null
    val startsAt = json.str("starts_at")
    val endsAt = json.str("ends_at")
    return InAppMessage(
        id = id,
        format = json.str("format") ?: return null,
        trigger = json.str("trigger") ?: return null,
        triggerName = json.str("trigger_name"),
        htmlUrl = json.str("html_url") ?: return null,
        lightHtmlUrl = json.str("light_html_url"),
        htmlVersion = if (json.isNull("html_version")) return null else json.optLong("html_version", -1).takeIf { it >= 0 } ?: return null,
        assets = strings(json.optJSONArray("assets")),
        capPerDevice = if (json.isNull("cap_per_device")) null else json.optInt("cap_per_device", 0),
        shownCount = json.optInt("shown_count", 0),
        coachTarget = json.str("coach_target"),
        // An unreadable date drops the message rather than showing it outside its dates.
        startsAt = startsAt?.let { parseTime(it) ?: return null },
        endsAt = endsAt?.let { parseTime(it) ?: return null },
    )
}

/** A non-empty string field, or null when absent, JSON null or empty. */
private fun JSONObject.str(name: String): String? = if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

/** The non-empty strings of [array], in order. */
private fun strings(array: JSONArray?): List<String> =
    (0 until (array?.length() ?: 0)).mapNotNull { i -> array?.optString(i)?.takeIf { it.isNotEmpty() && !array.isNull(i) } }

/** True when [id] parses as a UUID. */
private fun isUuid(id: String) = try { UUID.fromString(id).toString() == id.lowercase() } catch (e: IllegalArgumentException) { false }

/** RFC 3339 with optional fraction and a `Z` or `±hh:mm` offset, as Postgres writes it. */
private val RFC3339 = Regex("""(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2}):(\d{2})(\.\d+)?([Zz]|[+-]\d{2}(:?\d{2})?)""")

/** Epoch milliseconds of an RFC 3339 time, or null; by hand because java.time needs API 26. */
internal fun parseTime(text: String): Long? {
    val m = RFC3339.matchEntire(text.trim()) ?: return null
    val (y, mo, d, h, mi, s) = m.destructured
    val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        isLenient = false
        set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), s.toInt())
    }
    val base = try { calendar.timeInMillis } catch (e: IllegalArgumentException) { return null }
    val millis = m.groupValues[7].drop(1).padEnd(3, '0').take(3).toInt()
    val zone = m.groupValues[8]
    val offsetMinutes = if (zone.equals("z", ignoreCase = true)) 0 else {
        val digits = zone.drop(1).replace(":", "")
        val minutes = digits.take(2).toInt() * 60 + (digits.drop(2).toIntOrNull() ?: 0)
        if (zone[0] == '-') -minutes else minutes
    }
    return base + millis - offsetMinutes * 60_000L
}
