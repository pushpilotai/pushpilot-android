package ai.pushpilot.sdk

import kotlin.math.ceil

/** One drawn piece of an arc, in degrees (0 at 12 o'clock, clockwise): filled or track, and its segment's index. */
internal data class ArcPiece(val start: Float, val sweep: Float, val filled: Boolean, val index: Int)

/** One cell of a grid: its box and whether it holds a filled tile. */
internal data class Cell(val x: Float, val y: Float, val w: Float, val h: Float, val filled: Boolean)

/** A box: left, top, width and height. */
internal data class Rect4(val x: Float, val y: Float, val w: Float, val h: Float)

/** The geometry of `arc`, `grid`, `use` copies and `chart` (LIVE.md "Contract version 2"), free of Android types. */
internal object LiveGeometry {
    /** Arc pieces shorter than this many degrees are not drawn (as iOS). */
    private const val MIN_PIECE = 0.5f

    /**
     * Cuts an arc of [sweep] degrees from [start] into [segments] equal pieces with [gap] degrees
     * between them (also between the last and first on a full ring), filled up to [value] of the
     * drawn length, gaps excluded. Pieces under half a degree are left out, so 0.667 of six is four.
     */
    fun arcPieces(start: Float, sweep: Float, segments: Int, gap: Float, value: Double): List<ArcPiece> {
        val n = segments.coerceIn(1, 60)
        val gaps = if (n == 1) 0 else if (sweep >= 360f) n else n - 1
        val g = if (n == 1) 0f else gap
        val length = ((sweep - gaps * g) / n).coerceAtLeast(0f)
        val filledSegments = value.coerceIn(0.0, 1.0) * n
        val out = ArrayList<ArcPiece>()
        for (k in 0 until n) {
            val s = start + k * (length + g)
            val fill = ((filledSegments - k).coerceIn(0.0, 1.0) * length).toFloat()
            val drawn = if (fill >= MIN_PIECE) fill else 0f
            if (drawn > 0f) out += ArcPiece(s, drawn, true, k)
            if (length - drawn >= MIN_PIECE) out += ArcPiece(s + drawn, length - drawn, false, k)
        }
        return out
    }

    /** The rows of a grid of [count] cells in [cols] columns. */
    fun rows(cols: Int, count: Int): Int = ceil(count.toDouble() / cols.coerceAtLeast(1)).toInt().coerceAtLeast(1)

    /** A grid's height when no `h` is set: square cells sharing [width]. */
    fun gridHeight(width: Float, cols: Int, count: Int, gap: Float): Float {
        val c = cols.coerceAtLeast(1)
        val cell = ((width - gap * (c - 1)) / c).coerceAtLeast(0f)
        val r = rows(c, count)
        return r * cell + gap * (r - 1)
    }

    /**
     * The cells of a grid [width] wide: they share the width equally and are square, unless [height]
     * is given, when they share it too. The first [filled] cells are filled, or the last when [fromEnd].
     */
    fun gridCells(width: Float, height: Float?, cols: Int, count: Int, filled: Int, fromEnd: Boolean, gap: Float): List<Cell> {
        val c = cols.coerceIn(1, 24)
        val n = count.coerceIn(1, 96)
        val r = rows(c, n)
        val w = ((width - gap * (c - 1)) / c).coerceAtLeast(0f)
        val h = if (height != null) ((height - gap * (r - 1)) / r).coerceAtLeast(0f) else w
        val f = filled.coerceIn(0, n)
        return List(n) { i ->
            val on = if (fromEnd) i >= n - f else i < f
            Cell((i % c) * (w + gap), (i / c) * (h + gap), w, h, on)
        }
    }

    /**
     * Where one `use` copy goes in a box of [bw] by [bh]: the `view` box ([vw] by [vh]) is scaled
     * evenly to fit the box and centred, and the def ([dw] by [dh]) is drawn centred on the copy's
     * point ([x], [y] in the view) at its own size times [scale] times that same factor. Returns the
     * def's top-left corner and its drawing scale.
     */
    fun copy(bw: Float, bh: Float, vw: Float, vh: Float, x: Float, y: Float, scale: Float, dw: Float, dh: Float): Triple<Float, Float, Float> {
        val f = if (vw > 0f && vh > 0f) minOf(bw / vw, bh / vh) else 1f
        val ox = (bw - vw * f) / 2
        val oy = (bh - vh * f) / 2
        val s = scale * f
        return Triple(ox + x * f - dw * s / 2, oy + y * f - dh * s / 2, s)
    }

    /** A chart's value range: `min` and `max` when given, else the values' own, with bars from 0. */
    fun range(values: List<Double>, min: Double?, max: Double?, bars: Boolean): Pair<Double, Double> {
        var lo = min ?: values.minOrNull() ?: 0.0
        val hi = max ?: values.maxOrNull() ?: 1.0
        if (bars && min == null) lo = minOf(lo, 0.0)
        return lo to (if (hi <= lo) lo + 1 else hi)
    }

    /** The points of a line or area chart in a box of [w] by [h]: evenly spread across, higher values higher. */
    fun chartPoints(values: List<Double>, lo: Double, hi: Double, w: Float, h: Float): List<Pt> {
        val n = values.size
        return values.mapIndexed { i, v ->
            val x = if (n <= 1) w / 2 else w * i / (n - 1)
            Pt(x, (h - (v.coerceIn(lo, hi) - lo) / (hi - lo) * h).toFloat())
        }
    }

    /** The bars of a bar chart in a box of [w] by [h], [gap] apart, each rising from the range's bottom, or growing both ways from the middle line when [center] (`base: center`). */
    fun chartBars(values: List<Double>, lo: Double, hi: Double, w: Float, h: Float, gap: Float, center: Boolean = false): List<Rect4> {
        val n = values.size.coerceAtLeast(1)
        val bw = ((w - gap * (n - 1)) / n).coerceAtLeast(0f)
        return values.mapIndexed { i, v ->
            val tall = ((v.coerceIn(lo, hi) - lo) / (hi - lo) * h).toFloat()
            Rect4(i * (bw + gap), if (center) (h - tall) / 2 else h - tall, bw, tall)
        }
    }
}
