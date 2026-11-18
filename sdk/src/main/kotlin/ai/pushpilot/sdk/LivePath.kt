package ai.pushpilot.sdk

/** A point in a path's coordinates. */
internal data class Pt(val x: Float, val y: Float)

/** One absolute path command, after relative forms, `H`, `V`, `S` and `T` are resolved. */
internal sealed class PathOp {
    /** Starts a new outline at a point. */
    data class Move(val x: Float, val y: Float) : PathOp()

    /** A straight line to a point. */
    data class Line(val x: Float, val y: Float) : PathOp()

    /** A quadratic curve with one control point. */
    data class Quad(val x1: Float, val y1: Float, val x: Float, val y: Float) : PathOp()

    /** A cubic curve with two control points. */
    data class Cubic(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : PathOp()

    /** Closes the outline back to its start. */
    object Close : PathOp()
}

/**
 * The `path` block's `d` text (SVG commands `M L H V C S Q T Z`, upper case absolute, lower case
 * relative; LIVE.md "Contract version 2") and the geometry of `trim` and `marker`. No regex and no
 * Android types, so JVM tests cover all of it.
 */
internal object LivePath {
    private const val COMMANDS = "MmLlHhVvCcSsQqTtZz"
    private const val MAX_LENGTH = 4_000

    /** The commands of [d] as absolute operations, or null when it is not valid path text (`A` included). */
    fun parse(d: String): List<PathOp>? {
        if (d.length > MAX_LENGTH) return null
        val reader = Reader(d)
        val out = ArrayList<PathOp>()
        var cmd = ' '
        var cx = 0f
        var cy = 0f
        var sx = 0f
        var sy = 0f
        var ctrlX = 0f // the last curve's second control point, for S and T reflection
        var ctrlY = 0f
        var last = ' '
        while (true) {
            reader.skip()
            if (reader.done()) break
            val c = reader.peek()
            if (c.isLetter()) {
                if (c !in COMMANDS) return null
                cmd = c
                reader.next()
                if (cmd == 'Z' || cmd == 'z') {
                    if (out.isEmpty()) return null
                    out += PathOp.Close
                    cx = sx
                    cy = sy
                    last = 'Z'
                    continue
                }
            } else if (cmd == ' ' || cmd == 'Z' || cmd == 'z') {
                return null // numbers with no command before them
            }
            val rel = cmd.isLowerCase()
            val ox = if (rel) cx else 0f
            val oy = if (rel) cy else 0f
            when (cmd.uppercaseChar()) {
                'M' -> {
                    val x = reader.number() ?: return null
                    val y = reader.number() ?: return null
                    cx = ox + x; cy = oy + y; sx = cx; sy = cy
                    out += PathOp.Move(cx, cy)
                    cmd = if (rel) 'l' else 'L' // more pairs after a move are lines
                }
                'L' -> {
                    val x = reader.number() ?: return null
                    val y = reader.number() ?: return null
                    cx = ox + x; cy = oy + y
                    out += PathOp.Line(cx, cy)
                }
                'H' -> {
                    cx = ox + (reader.number() ?: return null)
                    out += PathOp.Line(cx, cy)
                }
                'V' -> {
                    cy = oy + (reader.number() ?: return null)
                    out += PathOp.Line(cx, cy)
                }
                'C', 'S' -> {
                    val smooth = cmd.uppercaseChar() == 'S'
                    val x1: Float
                    val y1: Float
                    if (smooth) {
                        x1 = if (last == 'C') 2 * cx - ctrlX else cx
                        y1 = if (last == 'C') 2 * cy - ctrlY else cy
                    } else {
                        x1 = ox + (reader.number() ?: return null)
                        y1 = oy + (reader.number() ?: return null)
                    }
                    val x2 = ox + (reader.number() ?: return null)
                    val y2 = oy + (reader.number() ?: return null)
                    val x = ox + (reader.number() ?: return null)
                    val y = oy + (reader.number() ?: return null)
                    out += PathOp.Cubic(x1, y1, x2, y2, x, y)
                    ctrlX = x2; ctrlY = y2; cx = x; cy = y
                    last = 'C'
                    continue
                }
                'Q', 'T' -> {
                    val x1: Float
                    val y1: Float
                    if (cmd.uppercaseChar() == 'T') {
                        x1 = if (last == 'Q') 2 * cx - ctrlX else cx
                        y1 = if (last == 'Q') 2 * cy - ctrlY else cy
                    } else {
                        x1 = ox + (reader.number() ?: return null)
                        y1 = oy + (reader.number() ?: return null)
                    }
                    val x = ox + (reader.number() ?: return null)
                    val y = oy + (reader.number() ?: return null)
                    out += PathOp.Quad(x1, y1, x, y)
                    ctrlX = x1; ctrlY = y1; cx = x; cy = y
                    last = 'Q'
                    continue
                }
            }
            last = 'L'
        }
        return out.takeIf { it.isNotEmpty() && it[0] is PathOp.Move }
    }

    /** The outlines as polylines, each curve cut into [steps] straight pieces; a close returns to the outline's start. */
    fun polylines(ops: List<PathOp>, steps: Int = 24): List<List<Pt>> {
        val out = ArrayList<MutableList<Pt>>()
        var current: MutableList<Pt>? = null
        var start = Pt(0f, 0f)
        var at = start
        /** The polyline being drawn, starting one at the current point after a move or a close. */
        fun line(): MutableList<Pt> = current ?: mutableListOf(at).also { out += it; current = it }
        for (op in ops) {
            when (op) {
                is PathOp.Move -> { start = Pt(op.x, op.y); at = start; current = null }
                is PathOp.Line -> { val p = line(); at = Pt(op.x, op.y); p += at }
                is PathOp.Quad -> {
                    val p = line()
                    val a = at
                    for (i in 1..steps) {
                        val t = i.toFloat() / steps
                        val u = 1 - t
                        p += Pt(u * u * a.x + 2 * u * t * op.x1 + t * t * op.x, u * u * a.y + 2 * u * t * op.y1 + t * t * op.y)
                    }
                    at = Pt(op.x, op.y)
                }
                is PathOp.Cubic -> {
                    val p = line()
                    val a = at
                    for (i in 1..steps) {
                        val t = i.toFloat() / steps
                        val u = 1 - t
                        val b0 = u * u * u
                        val b1 = 3 * u * u * t
                        val b2 = 3 * u * t * t
                        val b3 = t * t * t
                        p += Pt(b0 * a.x + b1 * op.x1 + b2 * op.x2 + b3 * op.x, b0 * a.y + b1 * op.y1 + b2 * op.y2 + b3 * op.y)
                    }
                    at = Pt(op.x, op.y)
                }
                PathOp.Close -> {
                    current?.let { if (it.last() != start) it += start }
                    at = start
                    current = null
                }
            }
        }
        return out
    }

    /** The total length of the polylines. */
    fun length(lines: List<List<Pt>>): Float = lines.sumOf { line -> line.zipWithNext { a, b -> dist(a, b).toDouble() }.sum() }.toFloat()

    /** The polylines from their start up to [fraction] (0 to 1) of their total length. */
    fun trim(lines: List<List<Pt>>, fraction: Float): List<List<Pt>> {
        if (fraction >= 1f) return lines
        var left = length(lines) * fraction.coerceAtLeast(0f)
        val out = ArrayList<List<Pt>>()
        for (line in lines) {
            if (left <= 0f) break
            val part = mutableListOf(line.first())
            for ((a, b) in line.zipWithNext()) {
                val d = dist(a, b)
                if (d >= left) {
                    part += lerp(a, b, if (d == 0f) 0f else left / d)
                    left = 0f
                    break
                }
                part += b
                left -= d
            }
            out += part
        }
        return out
    }

    /** The point [fraction] (0 to 1) along the polylines' total length; null when there is no point. */
    fun pointAt(lines: List<List<Pt>>, fraction: Float): Pt? {
        val first = lines.firstOrNull()?.firstOrNull() ?: return null
        val trimmed = trim(lines, fraction.coerceIn(0f, 1f))
        return trimmed.lastOrNull()?.lastOrNull() ?: first
    }

    /** The distance between two points. */
    private fun dist(a: Pt, b: Pt): Float = Math.hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()

    /** The point [t] of the way from [a] to [b]. */
    private fun lerp(a: Pt, b: Pt, t: Float) = Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    /** Reads numbers and command letters from path text: separators are spaces and commas; `-`, `.` and exponents end a number. */
    private class Reader(private val s: String) {
        private var i = 0

        /** True at the end of the text. */
        fun done() = i >= s.length

        /** The next character, unread. */
        fun peek() = s[i]

        /** Moves past one character. */
        fun next() { i++ }

        /** Moves past spaces and commas. */
        fun skip() { while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++ }

        /** The next number, or null when the text has none here. */
        fun number(): Float? {
            skip()
            val start = i
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            var digits = 0
            while (i < s.length && s[i].isDigit()) { i++; digits++ }
            if (i < s.length && s[i] == '.') {
                i++
                while (i < s.length && s[i].isDigit()) { i++; digits++ }
            }
            if (digits == 0) { i = start; return null }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                val mark = i
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                val exp = i
                while (i < s.length && s[i].isDigit()) i++
                if (i == exp) i = mark
            }
            return s.substring(start, i).toFloatOrNull()?.takeIf { it.isFinite() }
        }
    }
}
