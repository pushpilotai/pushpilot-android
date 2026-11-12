package ai.pushpilot.sdk

import org.json.JSONArray
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
 * A payload v2 push as the server puts it in the FCM data map: every value is a string,
 * `buttons` and `data` are JSON strings, and empty fields are left out.
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

        /** True when the data map came from PushPilot. */
        fun isOurs(data: Map<String, String>) = data["source"] == "pushpilot"

        /**
         * Parses a PushPilot data map; null when it is not ours or lacks the id or title needed to
         * show and track it. Malformed optional fields are dropped rather than losing the push.
         */
        fun parse(data: Map<String, String>): Push? {
            if (!isOurs(data)) return null
            val id = data["notification_id"].clean() ?: return null
            val title = data["title"].clean() ?: return null
            return Push(
                notificationId = id,
                title = title,
                body = data["body"].clean(),
                imageUrl = data["image_url"].clean()?.takeIf { it.startsWith("https://") },
                url = data["url"].clean(),
                buttons = buttons(data["buttons"]),
                data = customData(data["data"]),
            )
        }

        /** Non-blank value or null. */
        private fun String?.clean() = this?.takeIf { it.isNotBlank() }

        /** A field as a non-blank string; JSON null and missing keys are null, not "null". */
        private fun JSONObject.str(key: String) = if (isNull(key)) null else optString(key).clean()

        /** Up to three buttons from the JSON string; entries without an id or label are skipped. */
        private fun buttons(raw: String?): List<Button> {
            val list = try {
                JSONArray(raw ?: return emptyList())
            } catch (e: Exception) {
                log("push buttons are not a JSON list; shown without buttons")
                return emptyList()
            }
            val out = ArrayList<Button>()
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val id = item.str("id") ?: continue
                val label = item.str("label") ?: continue
                out += Button(id, label, item.str("url"))
                if (out.size == MAX_BUTTONS) break
            }
            return out
        }

        /** The customer's flat key-value map from its JSON string; empty when malformed. */
        private fun customData(raw: String?): Map<String, String> {
            val json = try {
                JSONObject(raw ?: return emptyMap())
            } catch (e: Exception) {
                log("push data is not a JSON object; ignored")
                return emptyMap()
            }
            val out = HashMap<String, String>()
            for (key in json.keys()) json.str(key)?.let { out[key] = it }
            return out
        }
    }
}
