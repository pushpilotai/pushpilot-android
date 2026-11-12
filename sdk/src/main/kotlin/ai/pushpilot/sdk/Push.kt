package ai.pushpilot.sdk

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One action button of a push; without a [url] it only dismisses the notification. */
internal class Button(val id: String, val label: String, val url: String?)

/** What tapping a notification or one of its buttons does. */
internal sealed class Tap {
    /** Opens [url] (an app link or https). */
    class OpenUrl(val url: String) : Tap()

    /** Opens the app's launcher activity. */
    object OpenApp : Tap()

    /** Only dismisses the notification. */
    object Dismiss : Tap()
}

/**
 * A PushPilot push. The FCM data map carries exactly `source` = `pushpilot` and `pushpilot`, a
 * JSON string holding one flat object: `notification_id`, `title`, `body`, `image_url`, `url`,
 * `buttons` (a list of `{id, label, url?}`) and `data` (string to string). Empty fields are left out.
 */
internal class Push(
    val notificationId: String,
    val title: String,
    val body: String?,
    val imageUrl: String?,
    val url: String?,
    val buttons: List<Button>,
    val data: Map<String, String>,
) {
    /** What a tap does: the body (no [buttonId]) opens the push's link or the app; a button opens its link or dismisses. */
    fun tap(buttonId: String?): Tap {
        if (buttonId == null) return url?.let { Tap.OpenUrl(it) } ?: Tap.OpenApp
        val button = buttons.firstOrNull { it.id == buttonId } ?: return Tap.OpenApp
        return button.url?.let { Tap.OpenUrl(it) } ?: Tap.Dismiss
    }

    companion object {
        const val MAX_BUTTONS = 3

        /** True when the data map came from PushPilot, whether or not its `pushpilot` value can be read. */
        fun isOurs(data: Map<String, String>) = data["source"] == "pushpilot"

        /**
         * Parses a PushPilot data map; null when it is not ours, its `pushpilot` value is not a JSON
         * object, or it lacks the id or title needed to show and track it. The log never carries
         * the push's contents. Malformed optional fields are dropped rather than losing the push.
         */
        fun parse(data: Map<String, String>): Push? {
            if (!isOurs(data)) return null
            val json = try {
                JSONObject(data["pushpilot"] ?: return null.also { log("PushPilot push without its pushpilot value; not shown") })
            } catch (e: JSONException) {
                return null.also { log("PushPilot push whose pushpilot value is not a JSON object; not shown") }
            }
            val id = json.str("notification_id") ?: return null.also { log("PushPilot push without a notification id; not shown") }
            val title = json.str("title") ?: return null.also { log("PushPilot push without a title; not shown") }
            return Push(
                notificationId = id,
                title = title,
                body = json.str("body"),
                imageUrl = json.str("image_url")?.takeIf { it.startsWith("https://") },
                url = json.str("url"),
                buttons = buttons(json.optJSONArray("buttons")),
                data = customData(json.optJSONObject("data")),
            )
        }

        /** A field as a non-blank string; JSON null and missing keys are null, not "null". */
        private fun JSONObject.str(key: String) = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

        /** Up to three buttons; entries without an id or label are skipped. */
        private fun buttons(list: JSONArray?): List<Button> {
            val out = ArrayList<Button>()
            for (i in 0 until (list?.length() ?: 0)) {
                val item = list!!.optJSONObject(i) ?: continue
                val id = item.str("id") ?: continue
                val label = item.str("label") ?: continue
                out += Button(id, label, item.str("url"))
                if (out.size == MAX_BUTTONS) break
            }
            return out
        }

        /** The customer's flat key-value map; null values are skipped. */
        private fun customData(json: JSONObject?): Map<String, String> {
            val out = HashMap<String, String>()
            for (key in json?.keys() ?: return out) json.str(key)?.let { out[key] = it }
            return out
        }
    }
}
