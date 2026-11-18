package ai.pushpilot.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.widget.RemoteViews
import org.json.JSONObject
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Draws a live layout (LIVE.md "Contract version 2", "Drawing on Android") as nested [RemoteViews]
 * of the SDK's `pushpilot_live_*` layouts where views can draw a block, and paints the smallest
 * parts they cannot (shapes, arcs, paths, drawings, charts, grids, offsets, rotation, borders,
 * clips, rounded corners, and sizes before Android 12) into bitmaps with [LiveCanvas]. A block type it
 * does not know draws nothing. [images] holds the downloaded `url` images.
 */
internal class LiveViews(
    private val context: Context,
    private val live: LiveUpdate,
    private val images: Map<String, Bitmap>,
    private val nowMs: Long,
) {
    private val pkg = context.packageName
    private val sdk = Build.VERSION.SDK_INT
    private val metrics = context.resources.displayMetrics
    private val painter by lazy { LiveCanvas(context, live, nowMs, this) }
    private var budget = 0L

    /** Every dp of the layout times this: below 1 when the custom view is narrower than the card the designs are drawn for. */
    private val zoom = min(1f, cardWidth() / DESIGN_WIDTH)

    /** A drawn block: its views and the id of their root view (0 when it has none), for spacing margins. */
    private class Part(val views: RemoteViews, val id: Int)

    /** The lock block inside a full-width column whose background is the lock's `bg`, or null when the layout has none. */
    fun lock(): RemoteViews? {
        val block = live.layout.optJSONObject("lock") ?: return null
        budget = BITMAP_BUDGET
        val root = RemoteViews(pkg, R.layout.pushpilot_live_column)
        LiveBox.color(block.optString("bg"), live.accent)?.let { root.setInt(R.id.pushpilot_live_box, "setBackgroundColor", it) }
        draw(painter.layout.layout(block, cardWidth() / zoom, CARD_HEIGHT, card = true))?.let { root.addView(R.id.pushpilot_live_box, it.views) }
        return root
    }

    /**
     * The collapsed look, one line like iOS's compact Dynamic Island: `compact_leading`, the
     * island's `center`, then `compact_trailing` at the end. Null when the layout has none of
     * them; the lock layout is then used, cut to the collapsed height.
     */
    fun collapsed(): RemoteViews? {
        val island = live.layout.optJSONObject("island") ?: return null
        budget = BITMAP_BUDGET
        val parts = listOf("compact_leading", "center", "compact_trailing").map { key ->
            island.optJSONObject(key)?.let { draw(painter.layout.layout(it, cardWidth() / zoom / 2, COLLAPSED_HEIGHT), true) }
        }
        if (parts.all { it == null }) return null
        val row = RemoteViews(pkg, R.layout.pushpilot_live_row)
        row.setInt(R.id.pushpilot_live_box, "setGravity", Gravity.CENTER_VERTICAL)
        parts[0]?.let { row.addView(R.id.pushpilot_live_box, it.views); row.addView(R.id.pushpilot_live_box, RemoteViews(pkg, R.layout.pushpilot_live_gap_8)) }
        parts[1]?.let { row.addView(R.id.pushpilot_live_box, it.views) }
        row.addView(R.id.pushpilot_live_box, RemoteViews(pkg, R.layout.pushpilot_live_spacer))
        parts[2]?.let { row.addView(R.id.pushpilot_live_box, RemoteViews(pkg, R.layout.pushpilot_live_gap_8)); row.addView(R.id.pushpilot_live_box, it.views) }
        return row
    }

    /** The custom view's width in dp: the screen less the shade's and the template's margins. */
    private fun cardWidth(): Float = metrics.widthPixels / metrics.density - if (sdk >= 31) CARD_MARGIN else OLD_CARD_MARGIN

    /** True when views cannot draw the block itself, so it is painted. */
    fun painted(b: JSONObject): Boolean = when {
        b.optString("type") in PAINTED_TYPES -> true
        PAINTED_KEYS.any { b.has(it) } -> true
        (b.has("w") || b.has("h")) && sdk < 31 -> true
        else -> false
    }

    /** One laid-out block as views, or painted when views cannot draw it; null for an unknown type or an empty one. [narrow] keeps boxes as wide as their content. */
    private fun draw(n: Node, narrow: Boolean = false): Part? {
        val b = n.block
        if (painted(b)) return paint(n)
        val part = when (b.optString("type")) {
            "row", "column" -> box(n, narrow)
            "layer" -> layer(n)
            "spacer" -> Part(RemoteViews(pkg, R.layout.pushpilot_live_spacer), R.id.pushpilot_live_box)
            "text" -> text(b)
            "badge" -> badge(b)
            "icon" -> icon(b.optString("name"), b.optString("asset"), b.optString("size"), color(b.optString("color")))?.let { Part(it, R.id.pushpilot_live_image) }
            "image" -> image(b)
            "progress" -> progress(b)
            "timer" -> timer(b)
            "pairs" -> pairs(b, narrow)
            else -> null
        } ?: return null
        decorate(part, b)
        return part
    }

    /** The box keys views can apply: `pad`, `bg`, `opacity`, and on Android 12+ `w` and `h`. */
    private fun decorate(part: Part, b: JSONObject) {
        val id = part.id
        val v = part.views
        val pad = LiveBox.pad(b)
        if (pad.any { it != 0f }) v.setViewPadding(id, px(pad[3]), px(pad[0]), px(pad[1]), px(pad[2]))
        LiveBox.color(b.optString("bg"), live.accent)?.let { v.setInt(id, "setBackgroundColor", it) }
        LiveBox.opacity(b).takeIf { it < 1f }?.let { v.setFloat(id, "setAlpha", it) }
        if (sdk >= 31) {
            LiveBox.num(b, "w")?.let { v.setViewLayoutWidth(id, it * zoom, TypedValue.COMPLEX_UNIT_DIP) }
            LiveBox.num(b, "h")?.let { v.setViewLayoutHeight(id, it * zoom, TypedValue.COMPLEX_UNIT_DIP) }
        }
    }

    /** [dp] of the layout in pixels, zoomed. */
    private fun px(dp: Float): Int = (dp * zoom * metrics.density + 0.5f).toInt()

    /** A painted block shown as a bitmap at its laid-out size, described by its words. */
    private fun paint(n: Node): Part? {
        val bitmap = painter.bitmap(n, scale(n.w, n.h), metrics.density * zoom) ?: return null
        return picture(bitmap, n.fillsWidth, painter.describe(n))
    }

    /** A bitmap in an image view: stretched across when it [fills] the width, else at its own size. */
    private fun picture(bitmap: Bitmap, fills: Boolean, description: String): Part {
        val views = RemoteViews(pkg, if (fills) R.layout.pushpilot_live_paint_fill else R.layout.pushpilot_live_paint)
        views.setImageViewBitmap(R.id.pushpilot_live_image, bitmap)
        if (description.isNotBlank()) views.setContentDescription(R.id.pushpilot_live_image, description)
        return Part(views, R.id.pushpilot_live_image)
    }

    /** Pixels per layout dp for a bitmap of [w] by [h] dp: the screen's density zoomed, less when the views' bitmap budget runs low. */
    private fun scale(w: Float, h: Float): Float {
        val area = (w * h).coerceAtLeast(1f)
        val s = min(metrics.density * zoom, sqrt(budget.coerceAtLeast(0L) / (area * 4f))).coerceAtLeast(0.5f)
        budget -= (area * s * s * 4).toLong()
        return s
    }

    /**
     * A row or column with its items, spacing and alignment. Inside a row it is only as wide as
     * its blocks: a full-width one would leave its siblings no room (the cut-off score layout).
     * So is every box inside such a narrow one, as a narrow column ignores full-width children
     * when it sizes itself and would cut them off. Spacing is exact on Android 12+ (negative overlaps),
     * else the nearest fixed gap.
     */
    private fun box(n: Node, narrow: Boolean): Part {
        val b = n.block
        val row = b.optString("type") == "row"
        val views = RemoteViews(pkg, layout(row, narrow))
        val spacers = painter.layout.items(b).any { it.optString("type") == "spacer" }
        var gravity = when (b.optString("align")) {
            "top", "baseline" -> Gravity.TOP // a row aligns its texts' baselines along the top
            "bottom" -> Gravity.BOTTOM
            "center" -> if (row) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
            "trailing" -> Gravity.END
            "leading" -> Gravity.START
            else -> if (row) Gravity.CENTER_VERTICAL else Gravity.START
        }
        if (!spacers && b.has(if (row) "w" else "h")) gravity = gravity or if (row) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
        views.setInt(R.id.pushpilot_live_box, "setGravity", gravity)
        val spacing = b.optDouble("spacing", 4.0).toFloat()
        val gap = gap((spacing * zoom).toInt())
        var previous: String? = null
        for (child in n.children) {
            val part = draw(child, row || narrow) ?: continue
            val type = child.block.optString("type")
            // As LiveLayout: no spacing beside a spacer.
            val spaced = previous != null && previous != "spacer" && type != "spacer"
            if (spaced && sdk >= 31) {
                part.views.setViewLayoutMargin(part.id, if (row) RemoteViews.MARGIN_START else RemoteViews.MARGIN_TOP, spacing * zoom, TypedValue.COMPLEX_UNIT_DIP)
            } else if (spaced && gap != 0) {
                views.addView(R.id.pushpilot_live_box, RemoteViews(pkg, gap))
            }
            views.addView(R.id.pushpilot_live_box, part.views)
            previous = type
        }
        return Part(views, R.id.pushpilot_live_box)
    }

    /**
     * A layer as a frame: each view item in a full-size box whose gravity is its `at`, and each run
     * of painted items as one bitmap over the layer's content, so a scene stays one picture under live text.
     */
    private fun layer(n: Node): Part {
        val views = RemoteViews(pkg, if (n.fillsWidth) R.layout.pushpilot_live_layer else R.layout.pushpilot_live_layer_wrap)
        val run = ArrayList<Node>()
        /** Adds the painted items waiting in the run as one bitmap. */
        fun flush() {
            if (run.isEmpty()) return
            val pad = LiveBox.pad(n.block)
            val w = n.w - pad[1] - pad[3]
            val h = n.h - pad[0] - pad[2]
            painter.bitmap(run.toList(), Pt(pad[3], pad[0]), w, h, scale(w, h), metrics.density * zoom)?.let {
                views.addView(R.id.pushpilot_live_box, picture(it, true, run.joinToString(", ") { r -> painter.describe(r) }).views)
            }
            run.clear()
        }
        for (child in n.children) {
            if (painted(child.block)) { run += child; continue }
            flush()
            val part = draw(child, !child.fillsWidth) ?: continue
            val place = RemoteViews(pkg, R.layout.pushpilot_live_place)
            place.setInt(R.id.pushpilot_live_box, "setGravity", gravity(child.block.optString("at")))
            place.addView(R.id.pushpilot_live_box, part.views)
            views.addView(R.id.pushpilot_live_box, place)
        }
        flush()
        return Part(views, R.id.pushpilot_live_box)
    }

    /** A layer item's `at` as gravity (default centre). */
    private fun gravity(at: String): Int {
        val h = when { at.endsWith("leading") -> Gravity.START; at.endsWith("trailing") -> Gravity.END; else -> Gravity.CENTER_HORIZONTAL }
        val v = when { at.startsWith("top") -> Gravity.TOP; at.startsWith("bottom") -> Gravity.BOTTOM; else -> Gravity.CENTER_VERTICAL }
        return h or v
    }

    /** The row or column layout: full width, or only as wide as its blocks when [narrow]. */
    private fun layout(row: Boolean, narrow: Boolean): Int = when {
        row && narrow -> R.layout.pushpilot_live_row_wrap
        row -> R.layout.pushpilot_live_row
        narrow -> R.layout.pushpilot_live_column_wrap
        else -> R.layout.pushpilot_live_column
    }

    /** The gap layout nearest [spacing] dp, or 0 for none (before Android 12). */
    private fun gap(spacing: Int): Int = when {
        spacing <= 0 -> 0
        spacing <= 5 -> R.layout.pushpilot_live_gap_4
        spacing <= 10 -> R.layout.pushpilot_live_gap_8
        spacing <= 14 -> R.layout.pushpilot_live_gap_12
        spacing <= 19 -> R.layout.pushpilot_live_gap_16
        else -> R.layout.pushpilot_live_gap_24
    }

    /** A text view: weight, italic, strike, capitals and runs as spans; size, colour, alignment and line limit; tracking and even digits on Android 16. */
    private fun text(block: JSONObject): Part {
        val views = RemoteViews(pkg, R.layout.pushpilot_live_text)
        val id = R.id.pushpilot_live_text
        views.setTextViewText(id, painter.text.spanned(block, zoom))
        textSize(views, id, block)
        views.setInt(id, "setMaxLines", block.optInt("lines", 1).coerceIn(1, 4))
        when (block.optString("align")) {
            "center" -> views.setInt(id, "setGravity", Gravity.CENTER_HORIZONTAL)
            "trailing" -> views.setInt(id, "setGravity", Gravity.END)
        }
        tint(views, id, block.optString("color"))
        return Part(views, id)
    }

    /** The text size (`size` in dp, else the style's in sp), and on Android 16 tracking and even digits. */
    private fun textSize(views: RemoteViews, id: Int, block: JSONObject) {
        val size = LiveBox.num(block, "size")
        if (size != null) views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_DIP, size * zoom) else views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, size(block.optString("style")))
        // shortcut: TextView's letter spacing and font features are remotable on Android 16 (checked on the device); older ones skip them.
        if (sdk >= 36) {
            block.optDouble("tracking", 0.0).takeIf { it != 0.0 }?.let { views.setFloat(id, "setLetterSpacing", it.toFloat()) }
            if (block.optBoolean("mono")) views.setString(id, "setFontFeatureSettings", "tnum")
        }
    }

    /** A badge: white text in a capsule of the block's colour (the accent when none); the capsule is a plain box before Android 12. */
    private fun badge(block: JSONObject): Part {
        val views = RemoteViews(pkg, R.layout.pushpilot_live_badge)
        views.setTextViewText(R.id.pushpilot_live_text, painter.text.content(block))
        val fill = color(block.optString("color").ifEmpty { "accent" }) ?: return Part(views, R.id.pushpilot_live_text)
        // shortcut: no tint API for a RemoteViews background before 31, so older Androids get a square box.
        if (sdk >= 31) {
            views.setColorStateList(R.id.pushpilot_live_text, "setBackgroundTintList", ColorStateList.valueOf(fill))
        } else {
            views.setInt(R.id.pushpilot_live_text, "setBackgroundColor", fill)
        }
        return Part(views, R.id.pushpilot_live_text)
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
    private fun image(block: JSONObject): Part? {
        val layout = when (block.optString("size")) { "s" -> R.layout.pushpilot_live_image_24; "l" -> R.layout.pushpilot_live_image_64; else -> R.layout.pushpilot_live_image_40 }
        val bitmap = bitmap(block)
        val res = drawable(block.optString("asset"))
        if (bitmap == null && res == null) return icon(block.optString("fallback"), "", block.optString("size"), null)?.let { Part(it, R.id.pushpilot_live_image) }
        val views = RemoteViews(pkg, layout)
        if (bitmap != null) views.setImageViewBitmap(R.id.pushpilot_live_image, bitmap) else views.setImageViewResource(R.id.pushpilot_live_image, res!!)
        return Part(views, R.id.pushpilot_live_image)
    }

    /** An image block's downloaded picture, cut to its `shape`; null until downloaded. */
    fun bitmap(block: JSONObject): Bitmap? = images[live.fill(block.optString("url"))]?.let { shaped(it, block.optString("shape")) }

    /** A progress bar at the field's value, or between two times as of now (it does not move by itself before Android 16). */
    private fun progress(block: JSONObject): Part {
        val views = RemoteViews(pkg, R.layout.pushpilot_live_progress)
        views.setProgressBar(R.id.pushpilot_live_progress, 1000, (live.fraction(block, nowMs) * 1000).toInt(), false)
        val tint = color(block.optString("color").ifEmpty { "accent" })
        if (tint != null && sdk >= 31) {
            views.setColorStateList(R.id.pushpilot_live_progress, "setProgressTintList", ColorStateList.valueOf(tint))
        }
        return Part(views, R.id.pushpilot_live_progress)
    }

    /** A chronometer counting down to, or up from, a time field, with the text's size and colour (weight on Android 16); nothing when the field is missing. */
    private fun timer(block: JSONObject): Part? {
        val at = live.time(block.optString("field")) ?: return null
        val views = RemoteViews(pkg, R.layout.pushpilot_live_timer)
        val id = R.id.pushpilot_live_timer
        views.setChronometer(id, SystemClock.elapsedRealtime() + (at * 1000 - nowMs), null, true)
        if (sdk >= 24) views.setChronometerCountDown(id, block.optString("mode") != "up")
        textSize(views, id, block)
        val weight = painter.text.weight(block)
        if (sdk >= 36 && weight != 400) views.setString(id, "setFontVariationSettings", "'wght' $weight")
        tint(views, id, block.optString("color"))
        return Part(views, id)
    }

    /** Label and value rows: the label quiet on the left, the value bold on the right. */
    private fun pairs(block: JSONObject, narrow: Boolean): Part {
        val column = RemoteViews(pkg, layout(false, narrow))
        for ((label, value) in painter.text.rows(block)) {
            val row = RemoteViews(pkg, R.layout.pushpilot_live_row)
            row.addView(R.id.pushpilot_live_box, text(JSONObject().put("text", label).put("color", "secondary")).views)
            // The gap keeps label and value apart when the pairs are only as wide as their widest line.
            row.addView(R.id.pushpilot_live_box, RemoteViews(pkg, R.layout.pushpilot_live_gap_8))
            row.addView(R.id.pushpilot_live_box, RemoteViews(pkg, R.layout.pushpilot_live_spacer))
            row.addView(R.id.pushpilot_live_box, text(JSONObject().put("text", value).put("weight", "semibold")).views)
            column.addView(R.id.pushpilot_live_box, row)
        }
        return Part(column, R.id.pushpilot_live_box)
    }

    /** Applies a text colour: `secondary` dims the theme's colour, any other name or `#RRGGBB(AA)` replaces it. */
    private fun tint(views: RemoteViews, id: Int, name: String) {
        if (name == "secondary") return views.setFloat(id, "setAlpha", 0.7f)
        color(name)?.let { views.setTextColor(id, it) }
    }

    /** A layout colour: `#RRGGBB`, `#RRGGBBAA`, or `accent` (the activity's accent colour); null keeps the theme's colour. */
    fun color(name: String?): Int? = LiveBox.color(name, live.accent)

    /** A drawable of the app (including the SDK's own) by name, or null. */
    @SuppressLint("DiscouragedApi") // layouts name icons and the app's own images, so lookup by name is the design
    fun drawable(name: String): Int? =
        name.takeIf { it.isNotEmpty() }?.let { context.resources.getIdentifier(it, "drawable", pkg) }?.takeIf { it != 0 }

    companion object {
        /** Block types only the painter draws. */
        private val PAINTED_TYPES = setOf("shape", "arc", "path", "use", "chart", "grid")

        /** Box keys only the painter applies. */
        private val PAINTED_KEYS = listOf("rotate", "offset", "fw", "fh", "border", "clip", "radius")

        /** Bitmap bytes allowed in one notification view, well under the system's limit for custom views. */
        private const val BITMAP_BUDGET = 3_000_000L

        /** dp of the screen beside the custom view on Android 12+: the shade.s margins and the template.s icon column (311 of 411 dp on Android 16). */
        private const val CARD_MARGIN = 100f

        // shortcut: estimated, not measured on Android 11 or older; a full-width painted part stretches to fit if it is off.
        private const val OLD_CARD_MARGIN = 64f

        /** The card width in dp the designs are drawn for: the canvas's card (LIVE.md: a Lock Screen card is about 360 to 400 wide). */
        private const val DESIGN_WIDTH = 376f

        /** The tallest Lock Screen card (LIVE.md "Contract version 2", units). */
        private const val CARD_HEIGHT = 160f

        /** The height offered to each part of the collapsed line. */
        private const val COLLAPSED_HEIGHT = 40f

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
    }
}
