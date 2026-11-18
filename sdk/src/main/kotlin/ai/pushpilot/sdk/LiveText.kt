package ai.pushpilot.sdk

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import org.json.JSONObject
import kotlin.math.ceil

/**
 * Text for the live renderers (LIVE.md "Contract version 2", text changes): `size`, `weight` up to
 * `black`, `tracking`, `mono`, `strike`, `italic`, `upper` and `runs`, also on `timer`. It measures
 * and paints text, timers, badges and pairs for painted parts, and builds the spans the view path
 * shows. Sizes are dp; [dark] picks the theme's primary and secondary colours.
 */
internal class LiveText(private val live: LiveUpdate, private val dark: Boolean, private val nowMs: Long) {
    /** The theme's text colour, used when a block names none or `primary`. */
    val primary: Int = if (dark) 0xFFFFFFFF.toInt() else 0xDE000000.toInt()
    private val secondary: Int = if (dark) 0xB3FFFFFF.toInt() else 0x8A000000.toInt()

    /** A layout colour, with `primary`, `secondary` and no colour resolved to the theme's text colours. */
    fun color(name: String?, fallback: Int = primary): Int =
        LiveBox.color(name, live.accent) ?: if (name == "secondary") secondary else fallback

    /** A block's text: its tokens filled, in capitals when `upper`. */
    fun content(block: JSONObject, key: String = "text"): String =
        live.fill(block.optString(key)).let { if (block.optBoolean("upper")) it.uppercase() else it }

    /** The pieces of a text block: its `runs`, or the whole text as one piece; each with the object holding its own keys. */
    fun pieces(block: JSONObject): List<Pair<String, JSONObject>> {
        val runs = block.optJSONArray("runs") ?: return listOf(content(block) to JSONObject())
        return (0 until runs.length()).mapNotNull { runs.optJSONObject(it) }.map { content(JSONObject().put("text", it.optString("text")).put("upper", block.optBoolean("upper"))) to it }
    }

    /** What a timer shows now: the time left (`down`) or since (`up`), as `m:ss` or `h:mm:ss`. Null without its field. */
    fun timer(block: JSONObject): String? {
        val at = live.time(block.optString("field")) ?: return null
        val s = maxOf(0L, if (block.optString("mode") == "up") nowMs / 1000 - at else at - nowMs / 1000)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    /** The weight from 100 to 900: the run's, else the block's, else bold for the `title` and `number` styles. */
    fun weight(block: JSONObject, run: JSONObject? = null): Int = when (run?.optString("weight").orEmpty().ifEmpty { block.optString("weight") }) {
        "medium" -> 500
        "semibold" -> 600
        "bold" -> 700
        "heavy" -> 800
        "black" -> 900
        "regular" -> 400
        else -> if (block.optString("style") in setOf("title", "number")) 700 else 400
    }

    /** The size in dp: the run's, else the block's `size`, else its style's. */
    fun size(block: JSONObject, run: JSONObject? = null): Float =
        LiveBox.num(run ?: JSONObject(), "size") ?: LiveBox.num(block, "size") ?: LiveViews.size(block.optString("style"))

    /** A paint for a block's text (or one of its runs), in dp. */
    fun paint(block: JSONObject, run: JSONObject? = null, fallback: Int = primary): TextPaint {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG)
        p.textSize = size(block, run)
        p.color = color(run?.optString("color")?.takeIf { it.isNotEmpty() } ?: block.optString("color"), fallback)
        val italic = block.optBoolean("italic")
        p.typeface = typeface(weight(block, run), italic)
        p.letterSpacing = block.optDouble("tracking", 0.0).toFloat()
        if (block.optBoolean("mono")) p.fontFeatureSettings = "tnum"
        p.isStrikeThruText = block.optBoolean("strike") || run?.optBoolean("strike") == true
        return p
    }

    /** The natural size of a `text`, `timer`, `badge` or `pairs` block at most [maxWidth] wide. */
    fun measure(block: JSONObject, maxWidth: Float): Size = when (block.optString("type")) {
        "timer" -> line(listOf((timer(block) ?: "") to JSONObject()), block, maxWidth)
        "badge" -> line(listOf(content(block) to JSONObject()), badgeStyle(block), maxWidth - 16).let { Size(it.w + 16, it.h + 4, it.baseline?.plus(2)) }
        "pairs" -> pairs(block, maxWidth)
        else -> {
            val lines = block.optInt("lines", 1).coerceIn(1, 4)
            if (lines == 1 || block.has("runs")) line(pieces(block), block, maxWidth) else multi(block, maxWidth, lines).let { Size(it.width.toFloat().coerceAtMost(widest(it)), it.height.toFloat(), it.getLineBaseline(0).toFloat()) }
        }
    }

    /** One line of pieces: their widths added, the tallest height, the deepest baseline. */
    private fun line(pieces: List<Pair<String, JSONObject>>, block: JSONObject, maxWidth: Float): Size {
        var w = 0f
        var ascent = 0f
        var descent = 0f
        for ((text, run) in pieces) {
            val p = paint(block, run)
            w += p.measureText(text)
            ascent = maxOf(ascent, -p.fontMetrics.ascent)
            descent = maxOf(descent, p.fontMetrics.descent)
        }
        return Size(ceil(minOf(w, maxWidth)), ceil(ascent + descent), ascent)
    }

    /** A wrapped layout of a text block for up to [lines] lines. */
    private fun multi(block: JSONObject, maxWidth: Float, lines: Int): StaticLayout {
        val p = paint(block)
        val text = content(block)
        val width = ceil(minOf(p.measureText(text), maxWidth.coerceAtLeast(1f))).toInt().coerceAtLeast(1)
        val align = when (block.optString("align")) {
            "center" -> Layout.Alignment.ALIGN_CENTER
            "trailing" -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, p, width).setAlignment(align).setMaxLines(lines)
            .setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build()
    }

    /** The widest line of a layout. */
    private fun widest(layout: StaticLayout): Float = (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) } ?: 0f

    /** Draws a text-like block's content in the box at [x], [y] of [w] by [h] (its natural size). */
    fun draw(c: Canvas, block: JSONObject, x: Float, y: Float, w: Float, h: Float) {
        when (block.optString("type")) {
            "timer" -> drawLine(c, listOf((timer(block) ?: "") to JSONObject()), block, x, y, w)
            "badge" -> {
                val style = badgeStyle(block)
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(block.optString("color").ifEmpty { "accent" }, 0xFF8E8E93.toInt()) }
                c.drawRoundRect(RectF(x, y, x + w, y + h), h / 2, h / 2, fill)
                drawLine(c, listOf(content(block) to JSONObject()), style, x + 8, y + 2, w - 16)
            }
            "pairs" -> drawPairs(c, block, x, y, w)
            else -> {
                val lines = block.optInt("lines", 1).coerceIn(1, 4)
                if (lines == 1 || block.has("runs")) return drawLine(c, pieces(block), block, x, y, w)
                c.save()
                c.translate(x, y)
                multi(block, w + 1, lines).draw(c)
                c.restore()
            }
        }
    }

    /** Draws pieces on one line from [x], cut with an ellipsis where they pass [w]. */
    private fun drawLine(c: Canvas, pieces: List<Pair<String, JSONObject>>, block: JSONObject, x: Float, y: Float, w: Float) {
        val baseline = y + (line(pieces, block, Float.MAX_VALUE).baseline ?: 0f)
        var at = x
        for ((text, run) in pieces) {
            val p = paint(block, run)
            val room = x + w - at + 0.5f
            if (room <= 0f) break
            val shown = TextUtils.ellipsize(text, p, room, TextUtils.TruncateAt.END).toString()
            c.drawText(shown, at, baseline, p)
            at += p.measureText(shown)
        }
    }

    /** A badge's text style: 12 dp, white, semibold. */
    private fun badgeStyle(block: JSONObject) = JSONObject().put("size", 12).put("weight", "semibold").put("color", "#FFFFFF").put("upper", block.optBoolean("upper"))

    /** The label and value styles of `pairs`. */
    private val label = JSONObject().put("color", "secondary")
    private val value = JSONObject().put("weight", "semibold")

    /** Label and value rows: as wide as the widest row with an 8 dp gap, 2 dp between rows. */
    private fun pairs(block: JSONObject, maxWidth: Float): Size {
        val rows = rows(block)
        if (rows.isEmpty()) return Size(0f, 0f)
        val w = rows.maxOf { (l, v) -> line(listOf(l to JSONObject()), label, Float.MAX_VALUE).w + 8 + line(listOf(v to JSONObject()), value, Float.MAX_VALUE).w }
        val rowH = line(listOf("Ag" to JSONObject()), value, Float.MAX_VALUE)
        return Size(minOf(w, maxWidth), rows.size * rowH.h + (rows.size - 1) * 2, rowH.baseline)
    }

    /** Draws `pairs`: the label on the left, the value at the right edge. */
    private fun drawPairs(c: Canvas, block: JSONObject, x: Float, y: Float, w: Float) {
        val rowH = line(listOf("Ag" to JSONObject()), value, Float.MAX_VALUE).h + 2
        rows(block).forEachIndexed { i, (l, v) ->
            val vw = minOf(line(listOf(v to JSONObject()), value, Float.MAX_VALUE).w, w)
            drawLine(c, listOf(l to JSONObject()), label, x, y + i * rowH, (w - vw - 8).coerceAtLeast(0f))
            drawLine(c, listOf(v to JSONObject()), value, x + w - vw, y + i * rowH, vw)
        }
    }

    /** The filled label and value of each pair. */
    fun rows(block: JSONObject): List<Pair<String, String>> {
        val items = block.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { items.optJSONObject(it) }.map { live.fill(it.optString("label")) to live.fill(it.optString("value")) }
    }

    /** A text block as styled text for a TextView: weight, italic, strike and each run's colour, size (times [zoom]), weight and strike. */
    fun spanned(block: JSONObject, zoom: Float = 1f): CharSequence {
        val out = SpannableStringBuilder()
        for ((text, run) in pieces(block)) {
            val start = out.length
            out.append(text)
            if (!block.has("runs")) continue
            run.optString("color").takeIf { it.isNotEmpty() }?.let { out.span(ForegroundColorSpan(color(it)), start) }
            LiveBox.num(run, "size")?.let { out.span(AbsoluteSizeSpan((it * zoom).toInt(), true), start) }
            if (run.has("weight")) weightSpan(weight(block, run))?.let { out.span(it, start) }
            if (run.optBoolean("strike")) out.span(StrikethroughSpan(), start)
        }
        weightSpan(weight(block))?.let { out.span(it, 0) }
        if (block.optBoolean("italic")) out.span(StyleSpan(Typeface.ITALIC), 0)
        if (block.optBoolean("strike")) out.span(StrikethroughSpan(), 0)
        return out
    }

    /** Sets [span] from [start] to the end. */
    private fun SpannableStringBuilder.span(span: Any, start: Int) = setSpan(span, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

    companion object {
        /** A weight as a span that crosses into the notification's process: font families by name, or bold. */
        fun weightSpan(weight: Int): Any? = when {
            weight >= 800 -> TypefaceSpan("sans-serif-black")
            weight >= 600 -> StyleSpan(Typeface.BOLD)
            weight >= 500 -> TypefaceSpan("sans-serif-medium")
            else -> null
        }

        /** The system sans-serif at [weight], italic or not. */
        fun typeface(weight: Int, italic: Boolean): Typeface {
            if (Build.VERSION.SDK_INT >= 28) return Typeface.create(Typeface.DEFAULT, weight, italic)
            val family = when {
                weight >= 800 -> "sans-serif-black"
                weight in 500..599 -> "sans-serif-medium"
                else -> "sans-serif"
            }
            val bold = weight in 600..799
            return Typeface.create(family, if (bold && italic) Typeface.BOLD_ITALIC else if (bold) Typeface.BOLD else if (italic) Typeface.ITALIC else Typeface.NORMAL)
        }
    }
}
