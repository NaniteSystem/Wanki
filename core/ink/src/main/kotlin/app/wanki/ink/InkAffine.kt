package app.wanki.ink

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A 2D affine map, x' = a·x + c·y + tx and y' = b·x + d·y + ty (the layout of android.graphics.Matrix:
 * a = scaleX, c = skewX, b = skewY, d = scaleY). Lasso transforms are built from [translate], [scaleAbout]
 * and [rotateAbout], so they never stretch to another aspect.
 */
data class InkAffine(val a: Float, val b: Float, val c: Float, val d: Float, val tx: Float, val ty: Float) {
    val isIdentity: Boolean get() = this == identity

    /** How much areas grow; the square root is how much lengths (and brush widths) grow. */
    val determinant: Float get() = a * d - b * c

    /** This map, then [other]. */
    fun then(other: InkAffine): InkAffine = InkAffine(
        a = other.a * a + other.c * b,
        b = other.b * a + other.d * b,
        c = other.a * c + other.c * d,
        d = other.b * c + other.d * d,
        tx = other.a * tx + other.c * ty + other.tx,
        ty = other.b * tx + other.d * ty + other.ty,
    )

    fun apply(x: Float, y: Float): Pair<Float, Float> = (a * x + c * y + tx) to (b * x + d * y + ty)

    /**
     * The stroke moved by this map: every point, the brush width (× √|det|), and the pen orientation turned
     * with the map. Pressure, tilt and time stay.
     */
    fun apply(stroke: InkStroke): InkStroke {
        if (isIdentity) return stroke
        val turn = atan2(b, a)
        val points = stroke.points.map { p ->
            val o = if (p.orientationRadians.isNaN() || turn == 0f) p.orientationRadians else normalizeAngle(p.orientationRadians + turn)
            p.copy(x = a * p.x + c * p.y + tx, y = b * p.x + d * p.y + ty, orientationRadians = o)
        }
        val k = sqrt(abs(determinant))
        return stroke.copy(points = points, brush = if (k == 1f) stroke.brush else stroke.brush.copy(size = stroke.brush.size * k))
    }

    companion object {
        val identity = InkAffine(1f, 0f, 0f, 1f, 0f, 0f)

        fun translate(dx: Float, dy: Float) = InkAffine(1f, 0f, 0f, 1f, dx, dy)

        /** Uniform scale by [s] keeping (cx, cy) in place. */
        fun scaleAbout(cx: Float, cy: Float, s: Float) = InkAffine(s, 0f, 0f, s, cx - s * cx, cy - s * cy)

        /** Turn by [radians] (clockwise on screen, where y grows down) about (cx, cy). */
        fun rotateAbout(cx: Float, cy: Float, radians: Float): InkAffine {
            val cs = cos(radians)
            val sn = sin(radians)
            return InkAffine(cs, sn, -sn, cs, cx - cs * cx + sn * cy, cy - sn * cx - cs * cy)
        }

        private const val TWO_PI = (2 * Math.PI).toFloat()

        private fun normalizeAngle(r: Float): Float {
            val m = r % TWO_PI
            return if (m < 0f) m + TWO_PI else m
        }
    }
}
