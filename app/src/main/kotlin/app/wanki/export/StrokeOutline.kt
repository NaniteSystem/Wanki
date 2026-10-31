package app.wanki.export

import android.graphics.Path
import androidx.ink.geometry.MutableVec
import androidx.ink.strokes.Stroke

/** A finished stroke as a vector path: the outlines of its mesh (Decision 13), for the PDF export. */
object StrokeOutline {
    /** One closed sub-path per outline of every render group of [stroke]'s shape, filled with [Path.FillType.WINDING]. */
    fun path(stroke: Stroke): Path {
        val mesh = stroke.shape
        val path = Path().apply { fillType = Path.FillType.WINDING }
        val v = MutableVec(0f, 0f)
        for (group in 0 until mesh.getRenderGroupCount()) {
            for (outline in 0 until mesh.getOutlineCount(group)) {
                val n = mesh.getOutlineVertexCount(group, outline)
                if (n < 2) continue
                for (i in 0 until n) {
                    mesh.populateOutlinePosition(group, outline, i, v)
                    if (i == 0) path.moveTo(v.x, v.y) else path.lineTo(v.x, v.y)
                }
                path.close()
            }
        }
        return path
    }
}
