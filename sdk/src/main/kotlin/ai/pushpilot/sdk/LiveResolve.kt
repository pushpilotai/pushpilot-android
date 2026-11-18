package ai.pushpilot.sdk

import org.json.JSONArray
import org.json.JSONObject

/**
 * Copies of block trees with the round two rewrites applied before layout (LIVE.md "Round two"):
 * a shared drawing's `$name` colours from its `use`'s `colors`, the `view` a path or `use` takes
 * from its nearest layer or def, and a grid cell's `{{label}}`. No Android types, so JVM tests cover it.
 */
internal object LiveResolve {
    /** The keys whose text is a colour; inside `colors` (an arc's list or a `use`'s map) every text is one. */
    private val COLOUR_KEYS = setOf("color", "bg", "fill", "stroke", "track", "rest", "highlight_color", "colors")

    /** The block types that take an enclosing `view` when they have none. */
    private val VIEW_TAKERS = setOf("path", "use")

    /**
     * A def ready to draw for one `use`: its `$name` colours from [colors] (`primary` when unset)
     * and its `view` passed down. A `use` inside it keeps these colours, with its own `colors` laid
     * over them (a `$x` there is this def's `x`).
     */
    fun def(def: JSONObject, colors: JSONObject?): JSONObject {
        val coloured = copy(def, false) { s, colour ->
            if (colour && s.length > 1 && s[0] == '$' && name(s.substring(1))) colors?.optString(s.substring(1))?.takeIf { it.isNotEmpty() } ?: "primary" else s
        } as JSONObject
        if (colors != null) passColours(coloured, colors)
        return views(coloured, null, root = true) as JSONObject
    }

    /** Gives every `use` in [json] the enclosing [colors] under its own (already resolved) `colors`. */
    private fun passColours(json: Any?, colors: JSONObject) {
        when (json) {
            is JSONObject -> {
                if (json.optString("type") == "use") {
                    val merged = JSONObject(colors.toString())
                    json.optJSONObject("colors")?.let { own -> for (k in own.keys()) merged.put(k, own.get(k)) }
                    json.put("colors", merged)
                }
                for (key in json.keys()) passColours(json.get(key), colors)
            }
            is JSONArray -> for (i in 0 until json.length()) passColours(json.get(i), colors)
        }
    }

    /** A block tree in which every `path` and `use` without a `view` takes the nearest enclosing layer's `view` (and a def's, at its [root]). */
    fun views(json: Any?, view: JSONArray?, root: Boolean = false): Any? = when (json) {
        is JSONObject -> {
            val out = JSONObject()
            val own = json.optJSONArray("view")
            val type = json.optString("type")
            val inner = if (own != null && (root || type == "layer")) own else view
            for (key in json.keys()) out.put(key, views(json.get(key), inner))
            if (own == null && view != null && type in VIEW_TAKERS) out.put("view", view)
            out
        }
        is JSONArray -> JSONArray().also { a -> for (i in 0 until json.length()) a.put(views(json.get(i), view)) }
        else -> json
    }

    /** A grid cell's block with [label] wherever its texts hold `{{label}}`. */
    fun labelled(block: JSONObject, label: String): JSONObject =
        copy(block, false) { s, _ -> if (s.contains(LABEL)) s.replace(LABEL, label) else s } as JSONObject

    /**
     * The label of each of [count] cells from a grid's `labels`: a list (one text each, tokens
     * filled), or one text (filled) whose characters fill the cells one each. Missing ones are empty.
     */
    fun labels(value: Any?, count: Int, fill: (String) -> String): List<String> = when (value) {
        is JSONArray -> List(count) { if (it < value.length()) fill(value.optString(it)) else "" }
        is String -> {
            val text = fill(value)
            val chars = ArrayList<String>()
            var i = 0
            while (i < text.length && chars.size < count) {
                val n = Character.charCount(text.codePointAt(i))
                chars += text.substring(i, i + n)
                i += n
            }
            List(count) { chars.getOrElse(it) { "" } }
        }
        else -> List(count) { "" }
    }

    /** A deep copy of [json] with each text passed through [f], told whether it sits under a colour key. */
    private fun copy(json: Any?, colour: Boolean, f: (String, Boolean) -> String): Any? = when (json) {
        is JSONObject -> JSONObject().also { o -> for (key in json.keys()) o.put(key, copy(json.get(key), colour || key in COLOUR_KEYS, f)) }
        is JSONArray -> JSONArray().also { a -> for (i in 0 until json.length()) a.put(copy(json.get(i), colour, f)) }
        is String -> f(json, colour)
        else -> json
    }

    /** True for a name as field names are written: a lower-case letter, then letters, digits and `_`. */
    private fun name(s: String): Boolean = s.isNotEmpty() && s[0] in 'a'..'z' && s.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }

    private const val LABEL = "{{label}}"
}
