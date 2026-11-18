package ai.pushpilot.sdk

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Paints laid-out live blocks with a [Canvas] (LIVE.md "Drawing on Android"): every block type,
 * so any part the views cannot draw can be painted. A timer or a self-running progress shows its
 * value at [nowMs]. Icons and images come through [views]. Also measures blocks for [LiveLayout]. Units are dp; bitmaps are scaled to pixels.
 */
internal class LiveCanvas(private val context: Context, private val live: LiveUpdate, private val nowMs: Long, private val views: LiveViews) : LiveSizer {
    private val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val text = LiveText(live, dark, nowMs)
    val layout = LiveLayout(live, this)

    /** The natural size of the blocks measured by fonts or fixed artwork. */
    override fun measure(block: JSONObject, maxWidth: Float): Size = when (block.optString("type")) {
        "text", "timer", "badge", "pairs" -> text.measure(block, maxWidth)
        "icon" -> iconSize(block.optString("size")).let { Size(it, it) }
        "image" -> imageSize(block.optString("size")).let { Size(it, it) }
        "progress" -> Size(0f, if (block.optString("tracker").isNotEmpty()) 18f else 8f)
        else -> Size(0f, 0f)
    }

    /**
     * [node] painted alone into a bitmap at [scale] pixels per layout dp, shown at [shown] pixels per
     * layout dp (the bitmap's density says so to the image view).
     */
    fun bitmap(node: Node, scale: Float, shown: Float): Bitmap? = canvas(node.w, node.h, scale, shown) { draw(it, node, root = true) }

    /** [nodes] (one layer's run of painted items) painted at their places in a box of [w] by [h] whose corner is [origin] in the layer. */
    fun bitmap(nodes: List<Node>, origin: Pt, w: Float, h: Float, scale: Float, shown: Float): Bitmap? = canvas(w, h, scale, shown) { c ->
        c.translate(-origin.x, -origin.y)
        for (n in nodes) draw(c, n)
    }

    /** A bitmap of [w] by [h] layout dp at [scale] pixels per dp, drawn by [paint] in dp. */
    private fun canvas(w: Float, h: Float, scale: Float, shown: Float, paint: (Canvas) -> Unit): Bitmap? {
        val wpx = ceil(w * scale).toInt()
        val hpx = ceil(h * scale).toInt()
        if (wpx <= 0 || hpx <= 0) return null
        val out = Bitmap.createBitmap(wpx, hpx, Bitmap.Config.ARGB_8888)
        out.density = (context.resources.displayMetrics.densityDpi * scale / shown).toInt()
        val c = Canvas(out)
        c.scale(scale, scale)
        paint(c)
        return out
    }

    /** Draws one node and its children: offset, scale, rotation, opacity, background, clip, content, border. [root] ignores its position. */
    fun draw(c: Canvas, n: Node, root: Boolean = false) {
        val b = n.block
        val saved = c.save()
        val off = LiveBox.offset(b)
        c.translate((if (root) 0f else n.x) + off.x, (if (root) 0f else n.y) + off.y)
        if (n.scale != 1f) c.scale(n.scale, n.scale)
        val turn = LiveBox.rotation(b, live)
        if (turn != 0f) c.rotate(turn, n.w / 2, n.h / 2)
        val alpha = LiveBox.opacity(b)
        if (alpha < 1f) c.saveLayerAlpha(null, (alpha * 255).toInt())
        val box = RectF(0f, 0f, n.w, n.h)
        val radii = LiveBox.radius(b)
        // Any colour name here, `primary` too: a def's unset `$name` colour draws primary.
        b.optString("bg").takeIf { it.isNotEmpty() }?.let { c.drawPath(rounded(box, radii), fill(text.color(it))) }
        val clip = b.optBoolean("clip")
        if (clip) {
            c.save()
            c.clipPath(rounded(box, radii))
        }
        content(c, n)
        for (child in n.children) draw(c, child)
        if (clip) c.restore()
        b.optJSONObject("border")?.let { border ->
            val width = border.optDouble("width", 1.0).toFloat()
            val inner = RectF(width / 2, width / 2, n.w - width / 2, n.h - width / 2)
            c.drawPath(rounded(inner, FloatArray(4) { max(0f, radii[it] - width / 2) }), stroke(text.color(border.optString("color")), width, "butt", border.optJSONArray("dash")))
        }
        c.restoreToCount(saved)
    }

    /** The block's own drawing inside its padding; containers draw only their children. */
    private fun content(c: Canvas, n: Node) {
        val b = n.block
        val pad = LiveBox.pad(b)
        val x = pad[3]
        val y = pad[0]
        val w = n.w - pad[1] - pad[3]
        val h = n.h - pad[0] - pad[2]
        when (b.optString("type")) {
            "text", "timer", "badge", "pairs" -> {
                val across = when (b.optString("align")) { "leading" -> 0f; "trailing" -> 1f; else -> 0.5f }
                text.draw(c, b, x + (w - n.contentW) * across, y + (h - n.contentH) / 2, n.contentW, n.contentH)
            }
            "icon" -> icon(c, b.optString("asset"), b.optString("name"), text.color(b.optString("color")), x, y, w, h, iconSize(b.optString("size")))
            "image" -> image(c, b, x, y, w, h)
            "progress" -> progress(c, b, x, y, w, h)
            "shape" -> shape(c, b, RectF(x, y, x + w, y + h))
            "arc" -> arc(c, b, RectF(x, y, x + w, y + h))
            "path" -> path(c, b, x, y, w, h)
            "chart" -> chart(c, b, x, y, w, h)
        }
    }

    /** A rectangle, circle, ellipse, capsule or line, filled and or stroked inside its box. */
    private fun shape(c: Canvas, b: JSONObject, box: RectF) {
        val fillColor = b.optString("fill").takeIf { it.isNotEmpty() }
        val strokeColor = b.optString("stroke").takeIf { it.isNotEmpty() }
        val width = b.optDouble("width", 1.0).toFloat()
        val kind = b.optString("shape", "rect")
        if (kind == "line") {
            val p = stroke(text.color(strokeColor ?: fillColor), width, b.optString("cap"), b.optJSONArray("dash"))
            if (box.width() >= box.height()) c.drawLine(box.left, box.centerY(), box.right, box.centerY(), p)
            else c.drawLine(box.centerX(), box.top, box.centerX(), box.bottom, p)
            return
        }
        /** The outline of the shape in [r]. */
        fun outline(r: RectF): Path = Path().apply {
            when (kind) {
                "circle" -> addCircle(r.centerX(), r.centerY(), min(r.width(), r.height()) / 2, Path.Direction.CW)
                "ellipse" -> addOval(r, Path.Direction.CW)
                "capsule" -> min(r.width(), r.height()).let { addRoundRect(r, it / 2, it / 2, Path.Direction.CW) }
                else -> addPath(rounded(r, LiveBox.radius(b)))
            }
        }
        if (fillColor != null || strokeColor == null) c.drawPath(outline(box), fill(text.color(fillColor)))
        if (strokeColor != null) {
            val inner = RectF(box.left + width / 2, box.top + width / 2, box.right - width / 2, box.bottom - width / 2)
            c.drawPath(outline(inner), stroke(text.color(strokeColor), width, b.optString("cap"), b.optJSONArray("dash")))
        }
    }

    /** A ring, gauge or wedge in the largest square of its box: segments, gaps, colours, track, caps and dashes. */
    private fun arc(c: Canvas, b: JSONObject, box: RectF) {
        val side = min(box.width(), box.height())
        if (side <= 0f) return
        val square = RectF(box.centerX() - side / 2, box.centerY() - side / 2, box.centerX() + side / 2, box.centerY() + side / 2)
        val value = if (b.has("from") && b.has("to")) live.fraction(b, nowMs) else LiveBox.amount(b.opt("value"), live)
        val pieces = LiveGeometry.arcPieces(LiveBox.angle(b.opt("start"), live), b.optDouble("sweep", 360.0).toFloat().coerceIn(1f, 360f), b.optInt("segments", 1), b.optDouble("gap", 0.0).toFloat(), value)
        val colors = b.optJSONArray("colors")?.let { a -> (0 until a.length()).map { text.color(a.optString(it)) } }
            ?: listOf(text.color(b.optString("color").ifEmpty { "accent" }))
        val track = b.optString("track").takeIf { it.isNotEmpty() }?.let { text.color(it) }
        val width = LiveBox.num(b, "width")
        val oval = if (width == null) square else RectF(square.left + width / 2, square.top + width / 2, square.right - width / 2, square.bottom - width / 2)
        for (piece in pieces) {
            val color = if (piece.filled) colors[piece.index % colors.size] else track ?: continue
            val paint = if (width == null) fill(color) else stroke(color, width, b.optString("cap"), b.optJSONArray("dash"))
            c.drawArc(oval, piece.start - 90f, piece.sweep, width == null, paint)
        }
    }

    /** A free outline from `d`, fitted to the box, filled and or stroked, drawn up to `trim`. */
    private fun path(c: Canvas, b: JSONObject, x: Float, y: Float, w: Float, h: Float) {
        val ops = LivePath.parse(b.optString("d")) ?: return
        val view = LiveBox.pair(b, "view")
        val t = LiveBox.fit(b.optString("fit"), view.x, view.y, w, h)
        // Stroke width and dashes are in view units and scale with the outline, by the smaller fit scale (as SVG).
        val unit = min(t[0], t[1])
        val trim = LiveBox.amount(b.opt("trim"), live).toFloat()
        val out = Path()
        if (trim >= 1f) {
            for (op in ops) when (op) {
                is PathOp.Move -> out.moveTo(op.x, op.y)
                is PathOp.Line -> out.lineTo(op.x, op.y)
                is PathOp.Quad -> out.quadTo(op.x1, op.y1, op.x, op.y)
                is PathOp.Cubic -> out.cubicTo(op.x1, op.y1, op.x2, op.y2, op.x, op.y)
                PathOp.Close -> out.close()
            }
            out.transform(Matrix().apply { setScale(t[0], t[1]); postTranslate(t[2], t[3]) })
        } else {
            for (line in LivePath.trim(layout.pathLines(b, w, h) ?: return, trim)) {
                out.moveTo(line[0].x, line[0].y)
                for (p in line.drop(1)) out.lineTo(p.x, p.y)
            }
        }
        out.offset(x, y)
        c.save()
        if (b.optString("fit") == "cover") c.clipRect(x, y, x + w, y + h)
        val fillColor = b.optString("fill").takeIf { it.isNotEmpty() }
        val strokeColor = b.optString("stroke").takeIf { it.isNotEmpty() }
        if (fillColor != null || strokeColor == null) c.drawPath(out, fill(text.color(fillColor)))
        if (strokeColor != null) c.drawPath(out, stroke(text.color(strokeColor), b.optDouble("width", 1.0).toFloat() * unit, b.optString("cap"), b.optJSONArray("dash"), unit))
        c.restore()
    }

    /** A bar, line or area chart of a `numbers` field or fixed values, split into two colours, with a highlight and a last-point dot; bars from the bottom or the middle (`base`), all corners rounded with `round`. */
    private fun chart(c: Canvas, b: JSONObject, x: Float, y: Float, w: Float, h: Float) {
        val raw: JSONArray = b.optJSONArray("values") ?: live.values.optJSONArray(b.optString("values")) ?: return
        val values = (0 until raw.length()).mapNotNull { (raw.opt(it) as? Number)?.toDouble() }
        if (values.isEmpty()) return
        val kind = b.optString("kind", "bar")
        val (lo, hi) = LiveGeometry.range(values, (b.opt("min") as? Number)?.toDouble(), (b.opt("max") as? Number)?.toDouble(), kind == "bar")
        val color = text.color(b.optString("color").ifEmpty { "accent" })
        val rest = b.optString("rest").takeIf { it.isNotEmpty() }?.let { text.color(it) } ?: color
        val split = LiveBox.amount(b.opt("split"), live)
        val highlight = LiveBox.count(b.opt("highlight"), live)?.toInt()
        val highlightColor = b.optString("highlight_color").takeIf { it.isNotEmpty() }?.let { text.color(it) }
        c.save()
        c.translate(x, y)
        if (kind == "bar") {
            val bars = LiveGeometry.chartBars(values, lo, hi, w, h, b.optDouble("gap", 2.0).toFloat(), b.optString("base") == "center")
            bars.forEachIndexed { i, r ->
                val paint = fill(if (i == highlight && highlightColor != null) highlightColor else if (i < split * bars.size) color else rest)
                val corner = if (b.optBoolean("round")) min(r.w, r.h) / 2 else 0f
                c.drawPath(rounded(RectF(r.x, r.y, r.x + r.w, r.y + r.h), FloatArray(4) { corner }), paint)
            }
        } else {
            val pts = LiveGeometry.chartPoints(values, lo, hi, w, h)
            val line = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
            for ((paintColor, from, to) in listOf(Triple(color, 0f, w * split.toFloat()), Triple(rest, w * split.toFloat(), w))) {
                if (to <= from) continue
                c.save()
                c.clipRect(from, -h, to, h * 2)
                if (kind == "area") c.drawPath(Path(line).apply { lineTo(pts.last().x, h); lineTo(pts[0].x, h); close() }, fill(paintColor))
                else c.drawPath(line, stroke(paintColor, b.optDouble("width", 2.0).toFloat(), "round", null))
                c.restore()
            }
            val dot = b.opt("dot")
            if (dot == true || dot is Number) {
                val d = (dot as? Number)?.toFloat() ?: (b.optDouble("width", 2.0).toFloat() * 3)
                c.drawCircle(pts.last().x, pts.last().y, d / 2, fill(highlightColor ?: color))
            }
            if (highlight != null && highlight in pts.indices) c.drawCircle(pts[highlight].x, pts[highlight].y, b.optDouble("width", 2.0).toFloat() * 1.5f, fill(highlightColor ?: color))
        }
        c.restore()
    }

    /** A bar with segments, points and a tracker icon at its value. */
    private fun progress(c: Canvas, b: JSONObject, x: Float, y: Float, w: Float, h: Float) {
        val color = text.color(b.optString("color").ifEmpty { "accent" })
        val value = live.fraction(b, nowMs).toFloat()
        val mid = y + h / 2
        val segments = b.optInt("segments", 1).coerceIn(1, 10)
        val gap = if (segments > 1) 2f else 0f
        val seg = (w - gap * (segments - 1)) / segments
        val track = fill(color).apply { alpha = 64 }
        for (i in 0 until segments) {
            val left = x + i * (seg + gap)
            c.drawRoundRect(RectF(left, mid - 2, left + seg, mid + 2), 2f, 2f, track)
            val filled = (value * w - i * (seg + gap)).coerceIn(0f, seg)
            if (filled > 0f) c.drawRoundRect(RectF(left, mid - 2, left + filled, mid + 2), 2f, 2f, fill(color))
        }
        val points = b.optJSONArray("points") ?: JSONArray()
        for (i in 0 until points.length()) c.drawCircle(x + points.optDouble(i).toFloat().coerceIn(0f, 1f) * w, mid, 4f, fill(color))
        val tracker = b.optString("tracker")
        if (tracker.isNotEmpty()) icon(c, "", tracker, color, x + value * w - 9f, mid - 9f, 18f, 18f, 16f)
    }

    /** A drawable (the app's `asset`, or the PushPilot icon [name]) tinted [color], [size] dp, centred in the box. */
    private fun icon(c: Canvas, asset: String, name: String, color: Int?, x: Float, y: Float, w: Float, h: Float, size: Float) {
        val res = views.drawable(asset) ?: views.drawable("pushpilot_live_$name") ?: return
        val d = context.getDrawable(res)?.mutate() ?: return
        val left = x + (w - size) / 2
        val top = y + (h - size) / 2
        // Drawables are drawn at a large pixel size and scaled, so vectors stay sharp.
        d.setBounds(0, 0, (size * 4).toInt(), (size * 4).toInt())
        if (color != null && asset.isEmpty()) d.setTint(color)
        c.save()
        c.translate(left, top)
        c.scale(0.25f, 0.25f)
        d.draw(c)
        c.restore()
    }

    /** An image: the downloaded URL or the app's drawable, else its fallback icon. */
    private fun image(c: Canvas, b: JSONObject, x: Float, y: Float, w: Float, h: Float) {
        val size = imageSize(b.optString("size"))
        val bitmap = views.bitmap(b)
        val dest = RectF(x + (w - size) / 2, y + (h - size) / 2, x + (w + size) / 2, y + (h + size) / 2)
        when {
            bitmap != null -> c.drawBitmap(bitmap, null, dest, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            views.drawable(b.optString("asset")) != null -> icon(c, b.optString("asset"), "", null, x, y, w, h, size)
            else -> icon(c, "", b.optString("fallback"), text.primary, x, y, w, h, iconSize(b.optString("size")))
        }
    }

    /** The words of a painted part, for its accessible description: texts, runs, timers, badges and pairs, in order. */
    fun describe(n: Node): String {
        val b = n.block
        val own = when (b.optString("type")) {
            "text" -> text.pieces(b).joinToString("") { it.first }
            "badge" -> text.content(b)
            "timer" -> text.timer(b).orEmpty()
            "pairs" -> text.rows(b).joinToString(", ") { "${it.first} ${it.second}" }
            else -> ""
        }
        return (listOf(own) + n.children.map(::describe)).filter { it.isNotBlank() }.joinToString(", ")
    }

    companion object {
        /** An icon's side in dp for its `size`. */
        fun iconSize(size: String) = when (size) { "s" -> 16f; "l" -> 32f; else -> 24f }

        /** An image's side in dp for its `size`. */
        fun imageSize(size: String) = when (size) { "s" -> 24f; "l" -> 64f; else -> 40f }

        /** A fill paint. */
        fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

        /**
         * A stroke paint with a cap (round when absent, as the designs' dots need) and an optional
         * `[on, off]` dash times [unit]; an `on` of 0 with a round cap draws dots.
         */
        fun stroke(color: Int, width: Float, cap: String, dash: JSONArray?, unit: Float = 1f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = width
            strokeCap = if (cap == "butt") Paint.Cap.BUTT else Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            if (dash != null && dash.length() >= 2) {
                pathEffect = DashPathEffect(floatArrayOf(max(0.01f, dash.optDouble(0).toFloat() * unit), max(0.01f, dash.optDouble(1).toFloat() * unit)), 0f)
            }
        }

        /** A rectangle with each corner rounded by [radii] (top leading, top trailing, bottom trailing, bottom leading). */
        fun rounded(r: RectF, radii: FloatArray): Path = Path().apply {
            val k = FloatArray(8) { radii[it / 2].coerceAtMost(min(r.width(), r.height()) / 2).coerceAtLeast(0f) }
            addRoundRect(r, k, Path.Direction.CW)
        }
    }
}
