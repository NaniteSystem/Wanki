package app.wanki.scoring.geom

/**
 * Minimal SVG path-data flattener (M L H V C S Q T Z, absolute and relative).
 * Enough for KanjiVG / Make Me a Hanzi medians; arcs are approximated by a straight line.
 */
object SvgPath {
    private val token = Regex("""([MmLlHhVvCcSsQqTtAaZz])|([-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?)""")

    /** Returns the path as one polyline (subpaths are concatenated — KanjiVG strokes are single subpaths). */
    fun flatten(d: String, curveSegments: Int = 12): Polyline {
        val tokens = token.findAll(d).map { it.value }.toList()
        val out = ArrayList<Point>()
        var i = 0
        var cmd = ' '
        var cur = Point(0.0, 0.0)
        var start = cur
        var lastCubicCtrl: Point? = null
        var lastQuadCtrl: Point? = null

        fun isCmd(t: String) = t.length == 1 && t[0].isLetter()
        fun num(): Double = tokens[i++].toDouble()
        fun pt(rel: Boolean): Point { val x = num(); val y = num(); return if (rel) Point(cur.x + x, cur.y + y) else Point(x, y) }

        fun cubic(c1: Point, c2: Point, p: Point) {
            val p0 = cur
            for (k in 1..curveSegments) {
                val t = k.toDouble() / curveSegments
                val u = 1 - t
                out += Point(
                    u * u * u * p0.x + 3 * u * u * t * c1.x + 3 * u * t * t * c2.x + t * t * t * p.x,
                    u * u * u * p0.y + 3 * u * u * t * c1.y + 3 * u * t * t * c2.y + t * t * t * p.y,
                )
            }
            cur = p
        }

        fun quad(c: Point, p: Point) {
            val p0 = cur
            for (k in 1..curveSegments) {
                val t = k.toDouble() / curveSegments
                val u = 1 - t
                out += Point(u * u * p0.x + 2 * u * t * c.x + t * t * p.x, u * u * p0.y + 2 * u * t * c.y + t * t * p.y)
            }
            cur = p
        }

        while (i < tokens.size) {
            if (isCmd(tokens[i])) cmd = tokens[i++][0]
            else if (cmd == ' ') error("Path data must start with a command: $d")
            val rel = cmd.isLowerCase()
            var cubicCtrl: Point? = null
            var quadCtrl: Point? = null
            when (cmd.uppercaseChar()) {
                'M' -> {
                    cur = pt(rel); start = cur; out += cur
                    cmd = if (rel) 'l' else 'L' // implicit lineto for following pairs
                }
                'L' -> { cur = pt(rel); out += cur }
                'H' -> { val x = num(); cur = Point(if (rel) cur.x + x else x, cur.y); out += cur }
                'V' -> { val y = num(); cur = Point(cur.x, if (rel) cur.y + y else y); out += cur }
                'C' -> {
                    val c1 = pt(rel); val c2 = pt(rel); val p = pt(rel)
                    cubic(c1, c2, p); cubicCtrl = c2
                }
                'S' -> {
                    val c1 = lastCubicCtrl?.let { cur * 2.0 - it } ?: cur
                    val c2 = pt(rel); val p = pt(rel)
                    cubic(c1, c2, p); cubicCtrl = c2
                }
                'Q' -> { val c = pt(rel); val p = pt(rel); quad(c, p); quadCtrl = c }
                'T' -> {
                    val c = lastQuadCtrl?.let { cur * 2.0 - it } ?: cur
                    val p = pt(rel); quad(c, p); quadCtrl = c
                }
                'A' -> { repeat(5) { num() }; cur = pt(rel); out += cur }
                'Z' -> { cur = start; out += cur }
                else -> error("Unsupported path command $cmd")
            }
            lastCubicCtrl = cubicCtrl
            lastQuadCtrl = quadCtrl
            if (cmd.uppercaseChar() == 'Z' && i < tokens.size && !isCmd(tokens[i])) error("Numbers after Z in $d")
        }
        return out
    }
}
