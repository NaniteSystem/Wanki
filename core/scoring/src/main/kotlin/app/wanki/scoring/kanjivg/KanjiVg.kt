package app.wanki.scoring.kanjivg

import app.wanki.scoring.CharReference
import app.wanki.scoring.Component
import app.wanki.scoring.RefStroke
import app.wanki.scoring.geom.SvgPath

/**
 * Parser for KanjiVG SVG files (https://kanjivg.tagaini.net, CC BY-SA 3.0).
 *
 * Uses a tag scanner instead of an XML library: the files are regular, and real XML parsers
 * try to fetch the external SVG DTD the files declare.
 */
object KanjiVg {
    const val SIZE = 109.0
    private val tag = Regex("""<(/?)(g|path)\b([^>]*?)(/?)>""")
    private val attr = Regex("""([\w:.-]+)\s*=\s*"([^"]*)"""")

    private class Node(val element: String?) {
        val strokes = ArrayList<Int>()
        val children = ArrayList<Node>()
        fun build(): Component {
            val kids = children.map { it.build() }
            val all = (strokes + kids.flatMap { it.strokeIndices }).sorted()
            return Component(element, all, kids)
        }
    }

    fun parse(svg: String): CharReference {
        val stack = ArrayList<Node>()
        var root: Node? = null
        var char: String? = null
        var idHex: String? = null
        var inStrokePaths = false
        var depth = 0
        val strokes = ArrayList<RefStroke>()

        for (m in tag.findAll(svg)) {
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2]
            val attrs = attr.findAll(m.groupValues[3]).associate { it.groupValues[1] to it.groupValues[2] }
            val selfClosing = m.groupValues[4] == "/"

            if (name == "g") {
                if (closing) {
                    if (!inStrokePaths) continue
                    depth--
                    if (depth == 0) inStrokePaths = false else stack.removeAt(stack.lastIndex)
                    continue
                }
                val id = attrs["id"].orEmpty()
                if (!inStrokePaths) {
                    if (id.startsWith("kvg:StrokePaths_")) {
                        inStrokePaths = true
                        depth = 1
                        idHex = id.removePrefix("kvg:StrokePaths_").substringBefore('-')
                    }
                    continue
                }
                val node = Node(attrs["kvg:element"])
                if (stack.isEmpty()) {
                    root = node
                    char = node.element
                } else {
                    stack.last().children += node
                }
                if (!selfClosing) { stack += node; depth++ }
            } else if (name == "path" && inStrokePaths && !closing) {
                val d = attrs["d"] ?: continue
                val index = strokes.size
                strokes += RefStroke(SvgPath.flatten(d), attrs["kvg:type"])
                stack.lastOrNull()?.strokes?.add(index)
            }
        }

        require(strokes.isNotEmpty()) { "No strokes found in KanjiVG data" }
        val resolvedChar = char ?: idHex?.toIntOrNull(16)?.let { String(Character.toChars(it)) }
            ?: error("Cannot determine character")
        val rootComponent = root?.build() ?: Component(resolvedChar, strokes.indices.toList())
        return CharReference(resolvedChar, strokes, rootComponent, SIZE, source = "kanjivg")
    }
}
