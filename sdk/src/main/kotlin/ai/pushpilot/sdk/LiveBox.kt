package ai.pushpilot.sdk

import org.json.JSONArray
import org.json.JSONObject

/**
 * The keys every version 2 block may have (LIVE.md "Contract version 2", box keys), colours and
 * amounts, read without Android types so JVM tests cover them. Sizes are dp.
 */
internal object LiveBox {
    /** `#RRGGBB` or `#RRGGBBAA` as an ARGB int, `accent` as [accent]; null for anything else (`primary`, `secondary`, bad text). */
    fun color(name: String?, accent: String?): Int? = when {
        name == null -> null
        name == "accent" -> accent?.let { color(it, null) }
        name.startsWith("#") && (name.length == 7 || name.length == 9) -> {
            val v = name.substring(1).toLongOrNull(16)
            when {
                v == null -> null
                name.length == 7 -> (0xFF000000L or v).toInt()
                else -> ((v and 0xFF) shl 24 or (v ushr 8)).toInt()
            }
        }
        else -> null
    }

    /** An amount: a number from 0 to 1, or a number field's value read as one, clamped; [default] when absent. */
    fun amount(value: Any?, live: LiveUpdate, default: Double = 1.0): Double = when (value) {
        is Number -> value.toDouble()
        is String -> if (value.isEmpty()) default else live.number(value) ?: 0.0
        else -> default
    }.coerceIn(0.0, 1.0)

    /** A number, or a number field holding one (grid `filled`, chart `highlight`); null when absent. */
    fun count(value: Any?, live: LiveUpdate): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> live.number(value)
        else -> null
    }

    /** `pad` as `[top, trailing, bottom, leading]`. */
    fun pad(block: JSONObject): FloatArray = four(block.opt("pad"))

    /** `radius` as `[top_leading, top_trailing, bottom_trailing, bottom_leading]`. */
    fun radius(block: JSONObject): FloatArray = four(block.opt("radius"))

    /** One number for all four sides, or four numbers. */
    private fun four(value: Any?): FloatArray = when (value) {
        is Number -> FloatArray(4) { value.toFloat() }
        is JSONArray -> FloatArray(4) { value.optDouble(it, 0.0).toFloat() }
        else -> FloatArray(4)
    }

    /** True when any corner of `radius` is rounded. */
    fun rounded(block: JSONObject): Boolean = radius(block).any { it > 0f }

    /** `offset` as `[x, y]`, or zeros. */
    fun offset(block: JSONObject): Pt {
        val a = block.optJSONArray("offset") ?: return Pt(0f, 0f)
        return Pt(a.optDouble(0, 0.0).toFloat(), a.optDouble(1, 0.0).toFloat())
    }

    /** `rotate` in degrees: a constant, or `from` plus the value's share of the way to `to`. */
    fun rotation(block: JSONObject, live: LiveUpdate): Float = when (val r = block.opt("rotate")) {
        is Number -> r.toFloat()
        is JSONObject -> {
            val from = r.optDouble("from", 0.0)
            (from + (r.optDouble("to", 0.0) - from) * amount(r.opt("value"), live, 0.0)).toFloat()
        }
        else -> 0f
    }

    /** `opacity` from 0 to 1. */
    fun opacity(block: JSONObject): Float = block.optDouble("opacity", 1.0).coerceIn(0.0, 1.0).toFloat()

    /** Where a block of [w] by [h] sits in a layer of [lw] by [lh] by its `at` (default `center`): its top-left corner. */
    fun place(at: String, w: Float, h: Float, lw: Float, lh: Float): Pt {
        val x = when {
            at.endsWith("leading") -> 0f
            at.endsWith("trailing") -> lw - w
            else -> (lw - w) / 2
        }
        val y = when {
            at.startsWith("top") -> 0f
            at.startsWith("bottom") -> lh - h
            else -> (lh - h) / 2
        }
        return Pt(x, y)
    }

    /** A number key, or null when absent. */
    fun num(block: JSONObject, key: String): Float? = (block.opt(key) as? Number)?.toFloat()

    /**
     * How a `path`'s `view` box of [vw] by [vh] maps to a box of [bw] by [bh] by `fit`: `fill`
     * stretches, `contain` scales evenly to fit, `cover` evenly to cover (both centred). Returns
     * scale x, scale y, shift x, shift y.
     */
    fun fit(fit: String, vw: Float, vh: Float, bw: Float, bh: Float): FloatArray {
        if (vw <= 0f || vh <= 0f) return floatArrayOf(1f, 1f, 0f, 0f)
        val sx = bw / vw
        val sy = bh / vh
        val s = when (fit) {
            "contain" -> minOf(sx, sy)
            "cover" -> maxOf(sx, sy)
            else -> return floatArrayOf(sx, sy, 0f, 0f)
        }
        return floatArrayOf(s, s, (bw - vw * s) / 2, (bh - vh * s) / 2)
    }

    /** A `[w, h]` key such as `view`, or [default] for both. */
    fun pair(block: JSONObject, key: String, default: Float = 100f): Pt {
        val a = block.optJSONArray(key) ?: return Pt(default, default)
        return Pt(a.optDouble(0, default.toDouble()).toFloat(), a.optDouble(1, default.toDouble()).toFloat())
    }
}
