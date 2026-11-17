package ai.pushpilot.sdk

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/**
 * One step of a live activity: the FCM data map carries `source` = `pushpilot` and
 * `pushpilot_live`, a JSON string with `activity_id`, `event` (`start`, `update` or `end`),
 * `timestamp` (milliseconds), `attributes` (`notification_id`, `fields`, `layout`, and optional
 * `name`, `url`, `accent`), `state` (`{title, fields}`), `ends_at` and `dismiss_at` (Unix seconds),
 * `promoted` and an optional `alert` (`{title, body}`). See LIVE.md in the PushPilot repository.
 */
internal class LiveUpdate(
    val activityId: String,
    /** The start step's id: live events and taps are recorded against it. */
    val notificationId: String,
    val event: String,
    val timestampMs: Long,
    val name: String?,
    val url: String?,
    val accent: String?,
    val layout: JSONObject,
    /** Field name to kind (`text`, `number` or `time`). */
    val kinds: Map<String, String>,
    val title: String,
    val values: JSONObject,
    val endsAtSec: Long,
    val dismissAtSec: Long?,
    val promoted: Boolean,
    val alertTitle: String?,
    val alertBody: String?,
) {
    /** Fills `{{title}}`, `{{field}}` and `{{field|fallback}}` from the state; times show as local time, numbers as written. */
    fun fill(text: String?, time: (Long) -> String = ::localTime): String {
        if (text == null) return ""
        return TOKEN.replace(text) { match ->
            val name = match.groupValues[1].trim()
            val fallback = match.groupValues.getOrNull(2)?.removePrefix("|").orEmpty()
            val value = if (name == "title") title else display(name, time)
            value?.takeIf { it.isNotEmpty() } ?: fallback
        }
    }

    /** A field's value as text, or null when it is missing. */
    private fun display(name: String, time: (Long) -> String): String? {
        if (values.isNull(name)) return null
        return when (kinds[name]) {
            "time" -> values.optLong(name).let(time)
            "number" -> number(name)?.let(::numberText)
            else -> values.opt(name)?.toString()
        }
    }

    /** A number field's value, or null when it is missing or not a number. */
    fun number(name: String): Double? = (values.opt(name) as? Number)?.toDouble()

    /** A time field's value in Unix seconds, or null. */
    fun time(name: String): Long? = (values.opt(name) as? Number)?.toLong()

    /** A progress block's value from 0 to 1: its number field, or how far [nowMs] is between its two time fields. */
    fun fraction(block: JSONObject, nowMs: Long): Double {
        block.optString("value").takeIf { it.isNotEmpty() }?.let { return (number(it) ?: 0.0).coerceIn(0.0, 1.0) }
        val from = time(block.optString("from")) ?: return 0.0
        val to = time(block.optString("to")) ?: return 0.0
        if (to <= from) return 1.0
        return ((nowMs / 1000.0 - from) / (to - from)).coerceIn(0.0, 1.0)
    }

    companion object {
        /** `{{name}}` or `{{name|fallback}}`. */
        private val TOKEN = Regex("""\{\{\s*([a-z][a-z0-9_]*)\s*(\|[^}]*)?}}""")

        /** True when the data map is a live step; checked before [Push.parse], which would log it as unreadable. */
        fun isLive(data: Map<String, String>) = Push.isOurs(data) && data.containsKey("pushpilot_live")

        /** A number as written: no trailing `.0` on whole numbers. */
        fun numberText(value: Double): String =
            if (value == Math.floor(value) && !value.isInfinite() && Math.abs(value) < 1e15) value.toLong().toString() else value.toString()

        /** The device's short local time for Unix seconds. */
        private fun localTime(seconds: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(seconds * 1000))

        /** Parses a live data map; null when it is not one or misses what drawing it needs. Never logs its contents. */
        fun parse(data: Map<String, String>): LiveUpdate? {
            if (!isLive(data)) return null
            return try {
                val json = JSONObject(data["pushpilot_live"]!!)
                val attributes = json.getJSONObject("attributes")
                val state = json.getJSONObject("state")
                val event = json.getString("event").takeIf { it in EVENTS } ?: return null.also { log("live push with an unknown event; ignored") }
                LiveUpdate(
                    activityId = json.getString("activity_id"),
                    notificationId = attributes.getString("notification_id"),
                    event = event,
                    timestampMs = json.getLong("timestamp"),
                    name = attributes.str("name"),
                    url = attributes.str("url"),
                    accent = attributes.str("accent"),
                    layout = attributes.optJSONObject("layout") ?: JSONObject(),
                    kinds = kinds(attributes.optJSONArray("fields")),
                    title = state.optString("title"),
                    values = state.optJSONObject("fields") ?: JSONObject(),
                    endsAtSec = json.getLong("ends_at"),
                    dismissAtSec = if (json.isNull("dismiss_at")) null else json.optLong("dismiss_at"),
                    promoted = json.optBoolean("promoted"),
                    alertTitle = json.optJSONObject("alert")?.str("title"),
                    alertBody = json.optJSONObject("alert")?.str("body"),
                )
            } catch (e: JSONException) {
                log("live push that cannot be read; ignored")
                null
            }
        }

        private val EVENTS = setOf("start", "update", "end")

        /** The `fields` list as name to kind. */
        private fun kinds(list: JSONArray?): Map<String, String> {
            val out = HashMap<String, String>()
            for (i in 0 until (list?.length() ?: 0)) {
                val field = list!!.optJSONObject(i) ?: continue
                out[field.optString("name")] = field.optString("kind", "text")
            }
            return out
        }

        /** A field as a non-blank string; JSON null and missing keys are null. */
        private fun JSONObject.str(key: String) = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    }
}

/**
 * What the device remembers per activity until it is over: the newest step drawn, and whether
 * the user dismissed it or it ended. Kept in its own [Store] under one key; every call is
 * synchronized because pushes and the dismiss receiver arrive on different threads.
 */
internal class LiveMemory(private val store: Store) {
    /** What to do with a step. */
    enum class Verdict { SHOW_FIRST, SHOW, DROP }

    /**
     * Decides whether [update] is drawn: never after the user dismissed the activity, never an older
     * or repeated step, and nothing but the end after an end. SHOW_FIRST is the first step drawn,
     * when `live_started` is reported. Activities over before [nowSec] are forgotten.
     */
    @Synchronized
    fun accept(update: LiveUpdate, nowSec: Long): Verdict {
        val all = prune(nowSec)
        val seen = all.optJSONObject(update.activityId)
        val verdict = when {
            seen == null -> Verdict.SHOW_FIRST
            seen.optBoolean("dismissed") -> Verdict.DROP
            update.timestampMs <= seen.optLong("ts") -> Verdict.DROP
            seen.optBoolean("ended") && update.event != "end" -> Verdict.DROP
            else -> Verdict.SHOW
        }
        if (verdict != Verdict.DROP) {
            val until = if (update.event == "end") update.dismissAtSec ?: update.endsAtSec else update.endsAtSec
            all.put(
                update.activityId,
                JSONObject().put("ts", update.timestampMs).put("until", maxOf(until, update.endsAtSec)).put("ended", update.event == "end"),
            )
            save(all)
        }
        return verdict
    }

    /** Remembers that the user dismissed the activity; false when it is unknown or already over. */
    @Synchronized
    fun dismiss(activityId: String, nowSec: Long): Boolean {
        val all = prune(nowSec)
        val seen = all.optJSONObject(activityId) ?: return false
        if (seen.optBoolean("dismissed") || seen.optBoolean("ended")) return false
        all.put(activityId, seen.put("dismissed", true))
        save(all)
        return true
    }

    /** The ids of activities still remembered whose time is over; forgets them (for Android 7 and older, which have no timeout). */
    @Synchronized
    fun expired(nowSec: Long): List<String> {
        val all = JSONObject(store.get(KEY) ?: "{}")
        val over = all.keys().asSequence().filter { (all.optJSONObject(it)?.optLong("until") ?: 0L) < nowSec }.toList()
        if (over.isNotEmpty()) prune(nowSec).let(::save)
        return over
    }

    /** The remembered activities without those over before [nowSec]. */
    private fun prune(nowSec: Long): JSONObject {
        val all = JSONObject(store.get(KEY) ?: "{}")
        for (id in all.keys().asSequence().toList()) if ((all.optJSONObject(id)?.optLong("until") ?: 0L) < nowSec) all.remove(id)
        return all
    }

    /** Writes the map, or clears the key when it is empty. */
    private fun save(all: JSONObject) = store.put(KEY to all.takeIf { it.length() > 0 }?.toString())

    companion object {
        const val KEY = "live"
    }
}
