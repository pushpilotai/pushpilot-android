package ai.pushpilot.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.widget.RemoteViews
import org.json.JSONObject

/**
 * Draws a live layout block (LIVE.md "Contract: layouts, fields and state") as nested
 * [RemoteViews] of the SDK's `pushpilot_live_*` layouts. A block type it does not know draws
 * nothing, so later layout versions still show. [images] holds the downloaded `url` images.
 */
internal class LiveViews(
    private val context: Context,
    private val live: LiveUpdate,
    private val images: Map<String, Bitmap>,
    private val nowMs: Long,
) {
    private val pkg = context.packageName

    /** The lock block inside a full-width column, or null when the layout has none. */
    fun lock(): RemoteViews? {
        val block = live.layout.optJSONObject("lock") ?: return null
        val root = RemoteViews(pkg, R.layout.pushpilot_live_column)
        draw(block)?.let { root.addView(R.id.pushpilot_live_box, it) }
        return root
    }

    /**
     * The collapsed look, one line like iOS's compact Dynamic Island: `compact_leading`, the
     * island's `center`, then `compact_trailing` at the end. Null when the layout has none of
     * them; the lock layout is then used, cut to the collapsed height.
     */
    fun collapsed(): RemoteViews? {
        val island = live.layout.optJSONObject("island") ?: return null
        val parts = listOf("compact_leading", "center", "compact_trailing").map { island.optJSONObject(it)?.let { b -> draw(b, true) } }
        if (parts.all { it == null }) return null
        val row = RemoteViews(pkg, R.layout.pushpilot_live_row)
        row.setInt(R.id.pushpilot_live_box, "setGravity", Gravity.CENTER_VERTICAL)
        parts[0]?.let { row.addView(R.id.pushpilot_live_box, it); row.addView(R.id.pushpilot_live_box, RemoteViews(pkg, R.layout.pushpilot_live_gap_8)) }
        parts[1]?.let { row.addView(R.id.pushpilot_live_box, it) }
        row.addView(R.id.pushpilot_live_box, RemoteViews(pkg, R.layout.pushpilot_live_spacer))
        parts[2]?.let { row.addView(R.id.pushpilot_live_box, RemoteViews(pkg, R.layout.pushpilot_live_gap_8)); row.addView(R.id.pushpilot_live_box, it) }
        return row
    }

    /** One block, or null for an unknown type or an empty one; [narrow] when it must be only as wide as its content. */
    private fun draw(block: JSONObject, narrow: Boolean = false): RemoteViews? = when (block.optString("type")) {
        "row", "column" -> box(block, narrow)
        "spacer" -> RemoteViews(pkg, R.layout.pushpilot_live_spacer)
        "text" -> text(live.fill(block.optString("text")), block, R.layout.pushpilot_live_text)
        "badge" -> badge(block)
        "icon" -> icon(block.optString("name"), block.optString("asset"), block.optString("size"), color(block.optString("color")))
        "image" -> image(block)
        "progress" -> progress(block)
        "timer" -> timer(block)
        "pairs" -> pairs(block, narrow)
        else -> null
    }

    /**
     * A row or column with its items, spacing and alignment. Inside a row it is only as wide as
     * its blocks: a full-width one would leave its siblings no room (the cut-off score layout).
     * So is every box inside such a narrow one, as a narrow column ignores full-width children
     * when it sizes itself and would cut them off.
     */
    private fun box(block: JSONObject, narrow: Boolean): RemoteViews {
        val row = block.optString("type") == "row"
        val views = RemoteViews(pkg, layout(row, narrow))
        val gravity = when (block.optString("align")) {
            "top" -> Gravity.TOP
            "bottom" -> Gravity.BOTTOM
            "center" -> if (row) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
            "trailing" -> Gravity.END
            "leading" -> Gravity.START
            else -> if (row) Gravity.CENTER_VERTICAL else Gravity.START
        }
        views.setInt(R.id.pushpilot_live_box, "setGravity", gravity)
        val gap = gap(block.optInt("spacing"))
        val items = block.optJSONArray("items")
        var first = true
        for (i in 0 until (items?.length() ?: 0)) {
            val child = items!!.optJSONObject(i)?.let { draw(it, row || narrow) } ?: continue
            if (!first && gap != 0) views.addView(R.id.pushpilot_live_box, RemoteViews(pkg, gap))
            views.addView(R.id.pushpilot_live_box, child)
            first = false
        }
        return views
    }

    /** The row or column layout: full width, or only as wide as its blocks when [narrow]. */
    private fun layout(row: Boolean, narrow: Boolean): Int = when {
        row && narrow -> R.layout.pushpilot_live_row_wrap
        row -> R.layout.pushpilot_live_row
        narrow -> R.layout.pushpilot_live_column_wrap
        else -> R.layout.pushpilot_live_column
    }

    /** The gap layout nearest [spacing] dp, or 0 for none. */
    private fun gap(spacing: Int): Int = when {
        spacing <= 0 -> 0
        spacing <= 5 -> R.layout.pushpilot_live_gap_4
        spacing <= 10 -> R.layout.pushpilot_live_gap_8
        spacing <= 14 -> R.layout.pushpilot_live_gap_12
        spacing <= 19 -> R.layout.pushpilot_live_gap_16
        else -> R.layout.pushpilot_live_gap_24
    }

    /** A text view with the block's style, weight, colour, alignment and line limit. */
    private fun text(value: String, block: JSONObject, layout: Int): RemoteViews {
        val views = RemoteViews(pkg, layout)
        val id = R.id.pushpilot_live_text
        val bold = block.optString("weight") in setOf("semibold", "bold") || block.optString("style") in setOf("title", "number")
        views.setTextViewText(id, if (bold) bold(value) else value)
        views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, size(block.optString("style")))
        views.setInt(id, "setMaxLines", block.optInt("lines", 1).coerceIn(1, 4))
        when (block.optString("align")) {
            "center" -> views.setInt(id, "setGravity", Gravity.CENTER_HORIZONTAL)
            "trailing" -> views.setInt(id, "setGravity", Gravity.END)
        }
        tint(views, id, block.optString("color"))
        return views
    }

    /** A badge: white text in a capsule of the block's colour (the accent when none); the capsule is a plain box before Android 12. */
    private fun badge(block: JSONObject): RemoteViews {
        val views = RemoteViews(pkg, R.layout.pushpilot_live_badge)
        views.setTextViewText(R.id.pushpilot_live_text, live.fill(block.optString("text")))
        val fill = color(block.optString("color").ifEmpty { "accent" }) ?: return views
        // shortcut: no tint API for a RemoteViews background before 31, so older Androids get a square box.
        if (Build.VERSION.SDK_INT >= 31) {
            views.setColorStateList(R.id.pushpilot_live_text, "setBackgroundTintList", ColorStateList.valueOf(fill))
        } else {
            views.setInt(R.id.pushpilot_live_text, "setBackgroundColor", fill)
        }
        return views
    }

    /** An icon from the PushPilot list (`name`) or the app's own drawable (`asset`); null when neither exists. */
    fun icon(name: String, asset: String, size: String, color: Int?): RemoteViews? {
        val res = drawable(asset) ?: drawable("pushpilot_live_$name") ?: return null
        val views = RemoteViews(pkg, when (size) { "s" -> R.layout.pushpilot_live_icon_16; "l" -> R.layout.pushpilot_live_icon_32; else -> R.layout.pushpilot_live_icon_24 })
        views.setImageViewResource(R.id.pushpilot_live_image, res)
        if (color != null) views.setInt(R.id.pushpilot_live_image, "setColorFilter", color)
        return views
    }

    /** An image from the app's drawables or a downloaded URL; the fallback icon while it is missing. */
    private fun image(block: JSONObject): RemoteViews? {
        val layout = when (block.optString("size")) { "s" -> R.layout.pushpilot_live_image_24; "l" -> R.layout.pushpilot_live_image_64; else -> R.layout.pushpilot_live_image_40 }
        val bitmap = bitmap(block)
        val res = drawable(block.optString("asset"))
        if (bitmap == null && res == null) return icon(block.optString("fallback"), "", block.optString("size"), null)
        val views = RemoteViews(pkg, layout)
        if (bitmap != null) views.setImageViewBitmap(R.id.pushpilot_live_image, bitmap) else views.setImageViewResource(R.id.pushpilot_live_image, res!!)
        return views
    }

    /** An image block's downloaded picture, cut to its `shape`; null until downloaded. */
    fun bitmap(block: JSONObject): Bitmap? = images[live.fill(block.optString("url"))]?.let { shaped(it, block.optString("shape")) }

    /** A progress bar at the field's value, or between two times as of now (it does not move by itself before Android 16). */
    private fun progress(block: JSONObject): RemoteViews {
        val views = RemoteViews(pkg, R.layout.pushpilot_live_progress)
        views.setProgressBar(R.id.pushpilot_live_progress, 1000, (live.fraction(block, nowMs) * 1000).toInt(), false)
        val tint = color(block.optString("color").ifEmpty { "accent" })
        if (tint != null && Build.VERSION.SDK_INT >= 31) {
            views.setColorStateList(R.id.pushpilot_live_progress, "setProgressTintList", ColorStateList.valueOf(tint))
        }
        return views
    }

    /** A chronometer counting down to, or up from, a time field; nothing when the field is missing. */
    private fun timer(block: JSONObject): RemoteViews? {
        val at = live.time(block.optString("field")) ?: return null
        val views = RemoteViews(pkg, R.layout.pushpilot_live_timer)
        val id = R.id.pushpilot_live_timer
        views.setChronometer(id, SystemClock.elapsedRealtime() + (at * 1000 - nowMs), null, true)
        if (Build.VERSION.SDK_INT >= 24) views.setChronometerCountDown(id, block.optString("mode") != "up")
        views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, size(block.optString("style")))
        tint(views, id, block.optString("color"))
        return views
    }

    /** Label and value rows: the label quiet on the left, the value bold on the right. */
    private fun pairs(block: JSONObject, narrow: Boolean): RemoteViews {
        val column = RemoteViews(pkg, layout(false, narrow))
        val items = block.optJSONArray("items")
        for (i in 0 until (items?.length() ?: 0)) {
            val item = items!!.optJSONObject(i) ?: continue
            val row = RemoteViews(pkg, R.layout.pushpilot_live_row)
            row.addView(R.id.pushpilot_live_box, text(live.fill(item.optString("label")), JSONObject().put("color", "secondary"), R.layout.pushpilot_live_text))
            // The gap keeps label and value apart when the pairs are only as wide as their widest line.
            row.addView(R.id.pushpilot_live_box, RemoteViews(pkg, R.layout.pushpilot_live_gap_8))
            row.addView(R.id.pushpilot_live_box, RemoteViews(pkg, R.layout.pushpilot_live_spacer))
            row.addView(R.id.pushpilot_live_box, text(live.fill(item.optString("value")), JSONObject().put("weight", "semibold"), R.layout.pushpilot_live_text))
            column.addView(R.id.pushpilot_live_box, row)
        }
        return column
    }

    /** Applies a text colour: `secondary` dims the theme's colour, any other name or `#RRGGBB` replaces it. */
    private fun tint(views: RemoteViews, id: Int, name: String) {
        if (name == "secondary") return views.setFloat(id, "setAlpha", 0.7f)
        color(name)?.let { views.setTextColor(id, it) }
    }

    /** A layout colour: `#RRGGBB`, or `accent` (the activity's accent colour); null keeps the theme's colour. */
    fun color(name: String?): Int? = when {
        name == "accent" -> live.accent?.let(::hex)
        name != null && name.startsWith("#") -> hex(name)
        else -> null
    }

    /** Parses `#RRGGBB`, or null. */
    private fun hex(value: String): Int? = try { Color.parseColor(value) } catch (e: IllegalArgumentException) { null }

    /** A drawable of the app (including the SDK's own) by name, or null. */
    @SuppressLint("DiscouragedApi") // layouts name icons and the app's own images, so lookup by name is the design
    fun drawable(name: String): Int? =
        name.takeIf { it.isNotEmpty() }?.let { context.resources.getIdentifier(it, "drawable", pkg) }?.takeIf { it != 0 }

    companion object {
        /** Text size in sp for a style. */
        fun size(style: String): Float = when (style) {
            "title" -> 16f
            "headline" -> 15f
            "caption" -> 12f
            "number" -> 28f
            else -> 14f
        }

        /** A centred square of [src] with round corners (`rounded`) or as a circle (`circle`); other shapes keep it. */
        fun shaped(src: Bitmap, shape: String): Bitmap {
            if (shape != "circle" && shape != "rounded") return src
            val side = minOf(src.width, src.height)
            val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader.setLocalMatrix(Matrix().apply { setTranslate(-(src.width - side) / 2f, -(src.height - side) / 2f) })
            val radius = if (shape == "circle") side / 2f else side * 0.2f
            Canvas(out).drawRoundRect(RectF(0f, 0f, side.toFloat(), side.toFloat()), radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
            return out
        }

        /** The text in bold. */
        private fun bold(value: String) = SpannableString(value).apply { setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }
}
