package ai.pushpilot.sdk

import org.json.JSONObject
import kotlin.math.floor

/** A measured size and the baseline of its first text line from its top, when it has text. */
internal data class Size(val w: Float, val h: Float, val baseline: Float? = null)

/** Measures the blocks whose size comes from fonts or fixed artwork: text, timer, badge, pairs, icon, image and progress. */
internal fun interface LiveSizer {
    /** The block's natural size when it may be at most [maxWidth] wide (infinite when unbounded). */
    fun measure(block: JSONObject, maxWidth: Float): Size
}

/**
 * A laid-out block: its frame ([x], [y] in its parent's space, [w] by [h] dp), its children (row,
 * column and layer items, grid cells, `use` drawings, a path's marker), its first baseline, and
 * the size of a leaf's own content ([contentW], [contentH]), which is centred in a larger frame.
 * [scale] draws it larger or smaller about its top-left corner (`use`).
 */
internal class Node(
    val block: JSONObject,
    val w: Float,
    val h: Float,
    val children: List<Node>,
    val baseline: Float?,
    val contentW: Float,
    val contentH: Float,
    /** True when it took the width it was offered rather than its content's. */
    val fillsWidth: Boolean,
) {
    var x = 0f
    var y = 0f
    var scale = 1f
}

/**
 * Lays out a version 2 block tree the way the iOS renderer does (LIVE.md "Contract version 2"):
 * fixed and fractional sizes, `pad`, rows and columns whose spacers and greedy blocks share the
 * free space, layers placed by `at`, `use` drawings and copies, grids and path markers. Offsets
 * and rotation are applied when drawing. Fonts are measured through [sizer], so JVM tests can fake them.
 */
internal class LiveLayout(private val live: LiveUpdate, private val sizer: LiveSizer) {
    private val defs = live.layout.optJSONObject("defs")

    /** Lays out [block] in a box at most [width] by [height]; [card] makes it exactly [width] wide, as the Lock Screen card is. */
    fun layout(block: JSONObject, width: Float, height: Float, card: Boolean = false): Node =
        node(LiveResolve.views(block, null) as JSONObject, width, height, width, height, 0, exactW = card)

    /** The shared drawing named [ref], or null. */
    fun def(ref: String): JSONObject? = defs?.optJSONObject(ref)

    /** True when the block takes all the width it is offered (shapes, spacers, progress, and boxes holding one). */
    fun fillsW(b: JSONObject, depth: Int = 0): Boolean {
        if (b.has("w") || b.has("fw") || depth > MAX_DEPTH) return false
        return when (b.optString("type")) {
            "spacer", "shape", "arc", "path", "chart", "grid", "progress" -> true
            "use" -> defFills(b, depth)
            // A box holding a fraction takes the whole width, so the fraction has a size to be a fraction of.
            "row", "layer" -> items(b).any { it.has("fw") || fillsW(it, depth + 1) }
            "column" -> items(b).any { it.has("fw") || it.optString("type") != "spacer" && fillsW(it, depth + 1) }
            else -> false
        }
    }

    /** True when the block takes all the height it is offered. */
    fun fillsH(b: JSONObject, depth: Int = 0): Boolean {
        if (b.has("h") || b.has("fh") || depth > MAX_DEPTH) return false
        return when (b.optString("type")) {
            "spacer", "shape", "arc", "path", "chart" -> true
            "use" -> defFills(b, depth)
            "column", "layer" -> items(b).any { it.has("fh") || fillsH(it, depth + 1) }
            "row" -> items(b).any { it.has("fh") || it.optString("type") != "spacer" && fillsH(it, depth + 1) }
            else -> false
        }
    }

    /** True when a `use` fills its box: it has copies, or its drawing has no size of its own. */
    private fun defFills(b: JSONObject, depth: Int): Boolean =
        b.has("copies") || def(b.optString("ref"))?.let { fillsW(it, depth + 1) || fillsH(it, depth + 1) } == true

    /** A box's items of known types that `show` lets through; the others draw nothing and take no space. */
    fun items(b: JSONObject): List<JSONObject> {
        val a = b.optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("type") in TYPES && LiveBox.shown(it, live) }
    }

    /** A path's outlines in a box of [w] by [h], after `view` and `fit`; null when `d` cannot be read. */
    fun pathLines(b: JSONObject, w: Float, h: Float): List<List<Pt>>? {
        val ops = LivePath.parse(b.optString("d")) ?: return null
        val view = LiveBox.pair(b, "view")
        val t = LiveBox.fit(b.optString("fit"), view.x, view.y, w, h)
        return LivePath.polylines(ops).map { line -> line.map { Pt(it.x * t[0] + t[2], it.y * t[1] + t[3]) } }
    }

    /** Lays out one block offered [pw] by [ph], inside a parent whose content is [cw] by [ch]; [exact] forces the offered size, [exactW] its width. */
    private fun node(b: JSONObject, pw: Float, ph: Float, cw: Float, ch: Float, depth: Int, exact: Boolean = false, exactW: Boolean = false): Node {
        // A hidden block becomes an empty one: no size, nothing drawn.
        if (depth > MAX_DEPTH || !LiveBox.shown(b, live)) return Node(JSONObject(), 0f, 0f, emptyList(), null, 0f, 0f, false)
        val pad = LiveBox.pad(b)
        val padX = pad[1] + pad[3]
        val padY = pad[0] + pad[2]
        val fixedW = if (exact || exactW) pw else LiveBox.num(b, "w") ?: fraction(b.opt("fw"), cw)
        val fixedH = if (exact) ph else LiveBox.num(b, "h") ?: fraction(b.opt("fh"), ch)
        val fillW = fixedW == null && fillsW(b, depth)
        val outerW = fixedW ?: if (fillW && pw.isFinite()) pw else null
        val outerH = fixedH ?: if (fixedH == null && fillsH(b, depth) && ph.isFinite()) ph else null
        val aw = ((outerW ?: pw) - padX).coerceAtLeast(0f)
        val ah = ((outerH ?: ph) - padY).coerceAtLeast(0f)
        val c = when (b.optString("type")) {
            "row" -> stack(b, true, aw, ah, outerW != null, outerH != null, depth)
            "column" -> stack(b, false, ah, aw, outerH != null, outerW != null, depth)
            "layer" -> layer(b, aw, ah, outerW != null, outerH != null, depth)
            "use" -> use(b, aw, ah, outerW != null && outerH != null, depth)
            "grid" -> grid(b, aw, ah, outerH != null, depth)
            "path" -> marker(b, finite(aw), finite(ah), depth)
            "spacer", "shape", "arc", "chart" -> Size(0f, 0f) to emptyList()
            else -> sizer.measure(b, aw) to emptyList()
        }
        val (size, children) = c
        val w = outerW ?: (size.w + padX)
        val h = outerH ?: (size.h + padY)
        for (child in children) { child.x += pad[3]; child.y += pad[0] }
        // A leaf's content is centred in a taller frame, so its baseline moves with it.
        val shift = if (children.isEmpty()) ((h - padY - size.h) / 2).coerceAtLeast(0f) else 0f
        return Node(b, w, h, children, size.baseline?.let { it + pad[0] + shift }, size.w, size.h, exactW || (outerW != null && fixedW == null))
    }

    /** A fraction of the parent's size, or null when absent or the parent has no size. */
    private fun fraction(value: Any?, of: Float): Float? =
        if (value == null || !of.isFinite()) null else (LiveBox.amount(value, live) * of).toFloat()

    /** [v], or 0 when it is unbounded. */
    private fun finite(v: Float) = if (v.isFinite()) v else 0f

    /**
     * A row ([row]) or column: blocks with a fixed size first, then the others, then spacers and
     * greedy blocks share what is left. [main] and [cross] are the free space along and across it.
     */
    private fun stack(b: JSONObject, row: Boolean, main: Float, cross: Float, fixedMain: Boolean, fixedCross: Boolean, depth: Int): Pair<Size, List<Node>> {
        val items = items(b)
        if (items.isEmpty()) return Size(0f, 0f) to emptyList()
        val spacing = b.optDouble("spacing", 4.0).toFloat()
        /** Lays out item [i] offered [m] along the stack and [c] across it. */
        fun lay(i: Int, m: Float, c: Float): Node =
            if (row) node(items[i], m, c, if (row) main else cross, cross, depth + 1) else node(items[i], c, m, cross, main, depth + 1)
        fun mainOf(n: Node) = if (row) n.w else n.h
        fun crossOf(n: Node) = if (row) n.h else n.w
        val flex = items.map { if (row) fillsW(it, depth + 1) else fillsH(it, depth + 1) }
        val fillCross = items.map { it.optString("type") != "spacer" && if (row) fillsH(it, depth + 1) else fillsW(it, depth + 1) }
        val nodes = arrayOfNulls<Node>(items.size)
        // No spacing beside a spacer: it already is the flexible space, and fixed heights stay tight.
        val gaps = (0 until items.size - 1).map { if (items[it].optString("type") == "spacer" || items[it + 1].optString("type") == "spacer") 0f else spacing }
        var used = gaps.sum()
        val key = if (row) "w" else "h"
        for (i in items.indices.filter { !flex[it] }.sortedBy { if (items[it].has(key)) 0 else 1 }) {
            nodes[i] = lay(i, (main - used).coerceAtLeast(0f), cross).also { used += mainOf(it) }
        }
        val greedy = items.indices.filter { flex[it] }
        val share = if (main.isFinite() && greedy.isNotEmpty()) ((main - used) / greedy.size).coerceAtLeast(0f) else 0f
        for (i in greedy) {
            nodes[i] = if (items[i].optString("type") == "spacer") {
                Node(items[i], if (row) share else 0f, if (row) 0f else share, emptyList(), null, 0f, 0f, row)
            } else {
                lay(i, share, cross)
            }
        }
        val crossSize = if (fixedCross) cross else nodes.indices.filter { !fillCross[it] }.maxOfOrNull { crossOf(nodes[it]!!) }
            ?: nodes.maxOf { crossOf(it!!) }
        for (i in items.indices) if (fillCross[i] && crossOf(nodes[i]!!) != crossSize) nodes[i] = lay(i, mainOf(nodes[i]!!), crossSize)
        val all = nodes.map { it!! }
        val total = all.sumOf { mainOf(it).toDouble() }.toFloat() + gaps.sum()
        var pos = if (fixedMain && greedy.isEmpty() && main.isFinite()) ((main - total) / 2).coerceAtLeast(0f) else 0f
        val align = b.optString("align").ifEmpty { if (row) "center" else "leading" }
        val ascent = if (row && align == "baseline") all.maxOf { it.baseline ?: it.h } else 0f
        val groupH = if (row && align == "baseline") all.maxOf { ascent - (it.baseline ?: it.h) + it.h } else crossSize
        val groupTop = if (row && align == "baseline") ((crossSize - groupH) / 2).coerceAtLeast(0f) else 0f
        for ((i, n) in all.withIndex()) {
            val free = crossSize - crossOf(n)
            val c = when (align) {
                "baseline" -> groupTop + ascent - (n.baseline ?: n.h)
                "top", "leading" -> 0f
                "bottom", "trailing" -> free
                else -> free / 2
            }
            if (row) { n.x = pos; n.y = c } else { n.x = c; n.y = pos }
            pos += mainOf(n) + gaps.getOrElse(i) { 0f }
        }
        val mainSize = if (fixedMain) main else total
        val crossOut = maxOf(crossSize, if (fixedCross) 0f else groupH)
        val baseline = all.firstOrNull { it.baseline != null }?.let { it.y + it.baseline!! }
        return (if (row) Size(mainSize, crossOut, baseline) else Size(crossOut, mainSize, baseline)) to all
    }

    /** Items drawn on top of each other, each placed by its `at`, or with its centre at its `pos`; the layer is its fixed size or its largest item. */
    private fun layer(b: JSONObject, aw: Float, ah: Float, fixedW: Boolean, fixedH: Boolean, depth: Int): Pair<Size, List<Node>> {
        val items = items(b)
        val first = items.map { node(it, aw, ah, aw, ah, depth + 1) }
        val sized = items.indices.filter { !fillsW(items[it], depth + 1) || !fillsH(items[it], depth + 1) }
        val w = if (fixedW) aw else sized.maxOfOrNull { first[it].w } ?: 0f
        val h = if (fixedH) ah else sized.maxOfOrNull { first[it].h } ?: 0f
        val nodes = items.mapIndexed { i, item ->
            val again = w != aw || h != ah
            if (again && (fillsW(item, depth + 1) || fillsH(item, depth + 1) || item.has("fw") || item.has("fh"))) node(item, w, h, w, h, depth + 1) else first[i]
        }
        for (n in nodes) {
            val pos = LiveBox.pos(n.block, live)
            val p = if (pos != null) Pt(pos.x * w - n.w / 2, pos.y * h - n.h / 2) else LiveBox.place(n.block.optString("at").ifEmpty { "center" }, n.w, n.h, w, h)
            n.x = p.x
            n.y = p.y
        }
        return Size(w, h) to nodes
    }

    /**
     * A shared drawing: once, at its own size or scaled evenly to fit a sized box, or once per
     * copy, the `view` box scaled evenly to fit and each copy at its point (LiveGeometry.copy).
     */
    private fun use(b: JSONObject, aw: Float, ah: Float, sized: Boolean, depth: Int): Pair<Size, List<Node>> {
        val def = def(b.optString("ref"))?.let { LiveResolve.def(it, b.optJSONObject("colors")) } ?: return Size(0f, 0f) to emptyList()
        val fills = fillsW(def, depth + 1) || fillsH(def, depth + 1)
        val copies = b.optJSONArray("copies")
        val w = finite(aw)
        val h = finite(ah)
        if (copies == null) {
            if (fills) return Size(w, h) to listOf(node(def, w, h, w, h, depth + 1))
            val n = node(def, INF, INF, INF, INF, depth + 1)
            if (!sized || n.w <= 0f || n.h <= 0f) return Size(n.w, n.h) to listOf(n)
            n.scale = minOf(w / n.w, h / n.h)
            n.x = (w - n.w * n.scale) / 2
            n.y = (h - n.h * n.scale) / 2
            return Size(w, h) to listOf(n)
        }
        val view = LiveBox.pair(b, "view")
        val nodes = (0 until copies.length()).mapNotNull { i ->
            val at = copies.optJSONArray(i) ?: return@mapNotNull null
            // shortcut: a def with no size of its own is drawn 24 dp square per copy (as iOS); give it a size to change that.
            val n = if (fills) node(def, COPY_SIDE, COPY_SIDE, COPY_SIDE, COPY_SIDE, depth + 1, exact = true) else node(def, INF, INF, INF, INF, depth + 1)
            val (x, y, s) = LiveGeometry.copy(w, h, view.x, view.y, at.optDouble(0).toFloat(), at.optDouble(1).toFloat(), at.optDouble(2, 1.0).toFloat(), n.w, n.h)
            n.x = x
            n.y = y
            n.scale = s
            n
        }
        return Size(w, h) to nodes
    }

    /**
     * A grid: cells share the width (and the height when it is fixed); each holds the `tile`, the
     * `empty` block or, at `current`, the `current_tile`, with its `labels` entry as `{{label}}`,
     * filling its cell on any axis it is not sized on. `filled` is a count, or a numbers field
     * whose non-zero entries mark the filled cells.
     */
    private fun grid(b: JSONObject, aw: Float, ah: Float, fixedH: Boolean, depth: Int): Pair<Size, List<Node>> {
        val cols = b.optInt("cols", 1).coerceIn(1, 24)
        val count = b.optInt("count", 1).coerceIn(1, 96)
        val gap = b.optDouble("gap", 0.0).toFloat()
        val w = if (aw.isFinite()) aw else cols * DEFAULT_CELL + gap * (cols - 1)
        val mask = (b.opt("filled") as? String)?.let { live.values.optJSONArray(it) }
        val filled = floor((LiveBox.count(b.opt("filled"), live) ?: 0.0) + 1e-6).toInt()
        val cells = LiveGeometry.gridCells(w, if (fixedH) ah else null, cols, count, filled, b.optString("from") == "end", gap)
        val labels = LiveResolve.labels(b.opt("labels"), cells.size) { live.fill(it) }
        val current = LiveBox.count(b.opt("current"), live)?.let { floor(it + 1e-6).toInt() }
        val nodes = cells.mapIndexedNotNull { i, cell ->
            val on = if (mask != null) mask.optDouble(i, 0.0).let { it != 0.0 && !it.isNaN() } else cell.filled
            val key = if (i == current && b.has("current_tile")) "current_tile" else if (on) "tile" else "empty"
            val block = b.optJSONObject(key) ?: return@mapIndexedNotNull null
            val sized = LiveResolve.labelled(block, labels[i])
            if (!sized.has("w") && !sized.has("fw")) sized.put("w", cell.w.toDouble())
            if (!sized.has("h") && !sized.has("fh")) sized.put("h", cell.h.toDouble())
            val n = node(sized, cell.w, cell.h, cell.w, cell.h, depth + 1)
            n.x = cell.x + (cell.w - n.w) / 2
            n.y = cell.y + (cell.h - n.h) / 2
            n
        }
        return Size(w, if (fixedH) ah else LiveGeometry.gridHeight(w, cols, count, gap)) to nodes
    }

    /** A path's `marker`, centred on the point `marker_at` (default `trim`) along it. */
    private fun marker(b: JSONObject, w: Float, h: Float, depth: Int): Pair<Size, List<Node>> {
        val marker = b.optJSONObject("marker") ?: return Size(0f, 0f) to emptyList()
        val lines = pathLines(b, w, h) ?: return Size(0f, 0f) to emptyList()
        val at = LiveBox.amount(b.opt("marker_at") ?: b.opt("trim"), live, 1.0).toFloat()
        val p = LivePath.pointAt(lines, at) ?: return Size(0f, 0f) to emptyList()
        val n = node(marker, INF, INF, w, h, depth + 1)
        n.x = p.x - n.w / 2
        n.y = p.y - n.h / 2
        return Size(0f, 0f) to listOf(n)
    }

    companion object {
        private const val INF = Float.POSITIVE_INFINITY
        private const val MAX_DEPTH = 24
        private const val DEFAULT_CELL = 24f
        private const val COPY_SIDE = 24f

        /** Every block type this renderer draws; others draw nothing. */
        val TYPES = setOf(
            "row", "column", "spacer", "text", "icon", "image", "progress", "timer", "badge", "pairs",
            "layer", "shape", "arc", "path", "use", "chart", "grid",
        )
    }
}
