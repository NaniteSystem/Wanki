package app.wanki.input

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import app.wanki.data.PageObjectEntity
import app.wanki.data.PageObjectKind
import app.wanki.ink.InkAffine
import app.wanki.ink.InkRect
import app.wanki.media.MediaStore
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A text block or picture on a page (Decision 10), in world units: ([x], [y]) is the top-left corner of the unturned
 * box, [rotation] turns it about its centre (degrees, clockwise on screen). [z] orders objects among themselves; all of
 * them lie under the ink. [textSize] is in world units; [media] names a picture's file in the [MediaStore].
 */
data class PageObject(
    val id: String,
    val kind: PageObjectKind,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val rotation: Float,
    val z: Int,
    val text: String,
    val color: Int,
    val textSize: Float,
    val media: String?,
) {
    val centerX: Float get() = x + w / 2
    val centerY: Float get() = y + h / 2

    /** The four corners of the turned box, clockwise from the top-left. */
    fun corners(): List<Pair<Float, Float>> {
        val r = Math.toRadians(rotation.toDouble())
        val cs = cos(r).toFloat()
        val sn = sin(r).toFloat()
        val cx = centerX
        val cy = centerY
        return listOf(-w / 2 to -h / 2, w / 2 to -h / 2, w / 2 to h / 2, -w / 2 to h / 2).map { (dx, dy) ->
            (cx + cs * dx - sn * dy) to (cy + sn * dx + cs * dy)
        }
    }

    /** The axis-aligned box around the turned object. */
    fun bounds(): InkRect {
        val c = corners()
        return InkRect(c.minOf { it.first }, c.minOf { it.second }, c.maxOf { it.first }, c.maxOf { it.second })
    }

    /**
     * The object moved by [m] (a lasso transform: moves, uniform scales and turns): its centre goes where [m] puts it,
     * the box and the text size grow by √|det|, and it turns with the map.
     */
    fun transformed(m: InkAffine): PageObject {
        if (m.isIdentity) return this
        val (cx, cy) = m.apply(centerX, centerY)
        val k = sqrt(abs(m.determinant))
        val nw = w * k
        val nh = h * k
        val turn = Math.toDegrees(atan2(m.b, m.a).toDouble()).toFloat()
        return copy(x = cx - nw / 2, y = cy - nh / 2, w = nw, h = nh, textSize = textSize * k, rotation = normalizeDegrees(rotation + turn))
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        /** Draw order: by [z], then by id (as `NotebookDao.objects` reads them). */
        val ORDER: Comparator<PageObject> = compareBy<PageObject> { it.z }.thenBy { it.id }

        private fun normalizeDegrees(d: Float): Float {
            val m = d % 360f
            return if (m < 0f) m + 360f else m
        }
    }
}

fun PageObject.toEntity(pageId: String, now: Long = System.currentTimeMillis()) = PageObjectEntity(
    id = id, pageId = pageId, kind = kind.name, x = x, y = y, w = w, h = h, rotation = rotation, z = z,
    text = text, color = color, textSize = textSize, media = media, updatedAt = now,
)

/** The model of a stored object; null for a kind this version does not know (kept as it is by the editor). */
fun PageObjectEntity.toModelOrNull(): PageObject? {
    val k = PageObjectKind.entries.firstOrNull { it.name == kind } ?: return null
    return PageObject(id, k, x, y, w, h, rotation, z, text, color, textSize, media)
}

fun PageObjectEntity.toModel(): PageObject = toModelOrNull() ?: throw IllegalArgumentException("unknown page object kind $kind")

// --- drawing (android.graphics, so the screen and the PDF export draw objects the same way) -----------------------

/** Text block text laid out [PageObject.w] wide, in world units; the direction comes from the text itself. */
fun textLayout(text: String, width: Float, textSize: Float, color: Int): StaticLayout {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.textSize = textSize
        this.color = color
    }
    return StaticLayout.Builder.obtain(text, 0, text.length, paint, ceil(width).toInt().coerceAtLeast(1))
        .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
        .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .build()
}

/** The height a text block needs for [text] at [width] and [textSize] (world units). */
fun textBlockHeight(text: String, width: Float, textSize: Float): Float =
    textLayout(text, width, textSize, 0).height.toFloat().coerceAtLeast(textSize)

/** What is known of a picture's file while drawing. */
sealed interface PictureState {
    data object Loading : PictureState
    /** The file is gone (e.g. a backup restored without it) or cannot be decoded. */
    data object Missing : PictureState
    data class Ready(val bitmap: Bitmap) : PictureState
}

/**
 * Draws [o] onto [canvas], whose matrix maps world units to the target ([scale] = target pixels per world unit, for
 * hairlines). A text block uses [layout] when given (a cached [textLayout] of it); a picture draws [picture], or a
 * paper-coloured box with an image icon while it loads, crossed out when its file is missing.
 */
fun drawPageObject(canvas: Canvas, o: PageObject, scale: Float, picture: PictureState = PictureState.Loading, layout: StaticLayout? = null) {
    canvas.save()
    if (o.rotation != 0f) canvas.rotate(o.rotation, o.centerX, o.centerY)
    when (o.kind) {
        PageObjectKind.TEXT -> {
            canvas.translate(o.x, o.y)
            (layout ?: textLayout(o.text, o.w, o.textSize, o.color)).draw(canvas)
        }
        PageObjectKind.IMAGE -> {
            val box = RectF(o.x, o.y, o.x + o.w, o.y + o.h)
            if (picture is PictureState.Ready) canvas.drawBitmap(picture.bitmap, null, box, BitmapPaint)
            else drawPicturePlaceholder(canvas, box, scale, missing = picture is PictureState.Missing)
        }
    }
    canvas.restore()
}

private val BitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
private const val PAPER = 0xFFFFFEFA.toInt()
private const val PLACEHOLDER_INK = 0xFF9AA0A6.toInt()
private const val MISSING_INK = 0xFFC5221F.toInt()

/** A paper-coloured box with an outline and an image icon (a frame, a sun and a hill); a red cross when [missing]. */
private fun drawPicturePlaceholder(canvas: Canvas, box: RectF, scale: Float, missing: Boolean) {
    val line = 1.5f / scale.coerceAtLeast(0.01f)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PAPER }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = line
        color = if (missing) MISSING_INK else PLACEHOLDER_INK
    }
    canvas.drawRect(box, fill)
    canvas.drawRect(box, stroke)
    val s = minOf(box.width(), box.height()) * 0.3f
    val cx = box.centerX()
    val cy = box.centerY()
    val icon = RectF(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
    stroke.strokeWidth = s / 12
    canvas.drawRoundRect(icon, s / 10, s / 10, stroke)
    val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = stroke.color }
    canvas.drawCircle(icon.left + s * 0.3f, icon.top + s * 0.3f, s * 0.09f, solid)
    canvas.drawPath(Path().apply {
        moveTo(icon.left + s * 0.12f, icon.bottom - s * 0.15f)
        lineTo(icon.left + s * 0.42f, icon.top + s * 0.5f)
        lineTo(icon.left + s * 0.6f, icon.top + s * 0.7f)
        lineTo(icon.left + s * 0.72f, icon.top + s * 0.58f)
        lineTo(icon.right - s * 0.12f, icon.bottom - s * 0.15f)
        close()
    }, solid)
    if (missing) {
        stroke.strokeWidth = line * 2
        canvas.drawLine(box.left, box.top, box.right, box.bottom, stroke)
        canvas.drawLine(box.right, box.top, box.left, box.bottom, stroke)
    }
}

/**
 * Draws page objects on screen: pictures are decoded from the [MediaStore] (long side ≤ 2048) on IO into an LRU of 16,
 * text layouts are cached. [rev] changes when a picture arrives, so a draw that reads it runs again. Main thread.
 */
class PageObjectPainter(private val media: MediaStore?, private val scope: CoroutineScope) {
    var rev by mutableIntStateOf(0)
        private set

    private val bitmaps = LruCache<String, Bitmap>(16)
    private val missing = HashSet<String>()
    private val loading = HashSet<String>()
    private val layouts = LruCache<LayoutKey, StaticLayout>(64)

    private data class LayoutKey(val text: String, val w: Float, val size: Float, val color: Int)

    fun draw(canvas: Canvas, o: PageObject, scale: Float) {
        when (o.kind) {
            PageObjectKind.TEXT -> {
                val key = LayoutKey(o.text, o.w, o.textSize, o.color)
                val layout = layouts.get(key) ?: textLayout(o.text, o.w, o.textSize, o.color).also { layouts.put(key, it) }
                drawPageObject(canvas, o, scale, layout = layout)
            }
            PageObjectKind.IMAGE -> drawPageObject(canvas, o, scale, picture(o.media))
        }
    }

    private fun picture(name: String?): PictureState {
        if (name == null || media == null) return PictureState.Missing
        bitmaps.get(name)?.let { return PictureState.Ready(it) }
        if (name in missing) return PictureState.Missing
        if (loading.add(name)) scope.launch {
            val bitmap = try {
                withContext(Dispatchers.IO) { media.bitmap(name, MAX_SIDE) }
            } catch (e: CancellationException) {
                loading -= name
                throw e
            } catch (e: Throwable) {
                Log.e("Wanki", "picture $name not decoded", e)
                null
            }
            loading -= name
            if (bitmap != null) bitmaps.put(name, bitmap) else missing += name
            rev++
        }
        return PictureState.Loading
    }

    companion object {
        const val MAX_SIDE = 2048
    }
}
