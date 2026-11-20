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
 * box, [rotation] turns it about its centre (degrees, clockwise on screen). [z] orders objects among themselves; text blocks
 * and pictures lie under the ink, tape masks over it. [textSize] is in world units; [media] names a picture's file in the [MediaStore].
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
 * paper-coloured box with an image icon while it loads, crossed out when its file is missing. A mask is opaque tape, or
 * only its outline when [open].
 */
fun drawPageObject(
    canvas: Canvas, o: PageObject, scale: Float, picture: PictureState = PictureState.Loading, layout: StaticLayout? = null, open: Boolean = false,
) {
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
        PageObjectKind.MASK -> {
            val path = maskPath(o)
            if (open) canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f / scale.coerceAtLeast(0.01f)
                color = TAPE_OPEN
            }) else canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TAPE })
        }
    }
    canvas.restore()
}

/** Tape colour: Material's baseline light tertiaryContainer, fixed (pages stay paper-coloured in the dark theme). */
const val TAPE = 0xFFFFD8E4.toInt()
/** An open mask's outline: the baseline tertiary at 25 %. */
private const val TAPE_OPEN = 0x407D5260

/**
 * A mask's outline in world units, unturned: its box for a rectangle ([PageObject.text] empty), else the polygon stored
 * as "x1,y1 x2,y2 …" fitted from its own bounds into the box (so a moved or scaled mask keeps its shape).
 */
fun maskPath(o: PageObject): Path {
    val pts = o.text.split(' ').mapNotNull { p ->
        val xy = p.split(',')
        if (xy.size != 2) null else xy[0].toFloatOrNull()?.let { x -> xy[1].toFloatOrNull()?.let { y -> x to y } }
    }
    if (pts.size < 3) return Path().apply { addRect(o.x, o.y, o.x + o.w, o.y + o.h, Path.Direction.CW) }
    val l = pts.minOf { it.first }
    val t = pts.minOf { it.second }
    val sx = o.w / (pts.maxOf { it.first } - l).coerceAtLeast(1e-3f)
    val sy = o.h / (pts.maxOf { it.second } - t).coerceAtLeast(1e-3f)
    return Path().apply {
        pts.forEachIndexed { i, (x, y) ->
            val px = o.x + (x - l) * sx
            val py = o.y + (y - t) * sy
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }
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
 * A least-recently-used cache bounded by the total [sizeOf] of its values (bytes for bitmaps) rather than by count:
 * putting a value evicts the least recently used others until the total fits [budget]. A value larger than the budget
 * on its own is still kept (alone), so the picture on screen can always be drawn. Not thread-safe.
 */
class SizedLru<K, V>(private val budget: Long, private val sizeOf: (V) -> Long) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)

    var total = 0L
        private set

    val keys: Set<K> get() = map.keys

    fun get(key: K): V? = map[key]

    fun put(key: K, value: V) {
        map.remove(key)?.let { total -= sizeOf(it) }
        map[key] = value
        total += sizeOf(value)
        val it = map.entries.iterator()
        while (total > budget && map.size > 1) {
            val e = it.next()
            if (e.key == key) continue
            total -= sizeOf(e.value)
            it.remove()
        }
    }
}

/**
 * The smallest decode side that failed per picture (out of memory at a finer size, or the file gone after a coarser
 * decode), so a draw never relaunches a decode at that side or finer. A success at that side or finer clears it; a new
 * painter (the page opened again) starts empty.
 */
class DecodeFailures {
    private val failedSide = HashMap<String, Int>()

    fun shouldLoad(name: String, side: Int): Boolean = failedSide[name]?.let { side < it } ?: true

    fun failed(name: String, side: Int) {
        failedSide[name] = minOf(failedSide[name] ?: Int.MAX_VALUE, side)
    }

    fun succeeded(name: String, side: Int) {
        if ((failedSide[name] ?: return) <= side) failedSide.remove(name)
    }
}

/**
 * Draws page objects on screen: pictures are decoded from the [MediaStore] on IO, sampled to the size they are shown
 * at (a finer decode is loaded when zooming in, up to the stored 2048), into an LRU bounded by bytes
 * ([PICTURE_BUDGET]); text layouts are cached. [rev] changes when a picture arrives, so a draw that reads it runs again.
 * Main thread.
 */
class PageObjectPainter(private val media: MediaStore?, private val scope: CoroutineScope) {
    var rev by mutableIntStateOf(0)
        private set

    /** A decoded picture and the long side ([decodeSide]) it was decoded for. */
    private class Decoded(val bitmap: Bitmap, val side: Int)

    private val bitmaps = SizedLru<String, Decoded>(PICTURE_BUDGET) { it.bitmap.byteCount.toLong() }
    private val missing = HashSet<String>()
    private val loading = HashSet<String>()
    private val failures = DecodeFailures()
    private val layouts = LruCache<LayoutKey, StaticLayout>(64)

    private data class LayoutKey(val text: String, val w: Float, val size: Float, val color: Int)

    fun draw(canvas: Canvas, o: PageObject, scale: Float, open: Boolean = false) {
        when (o.kind) {
            PageObjectKind.TEXT -> {
                val key = LayoutKey(o.text, o.w, o.textSize, o.color)
                val layout = layouts.get(key) ?: textLayout(o.text, o.w, o.textSize, o.color).also { layouts.put(key, it) }
                drawPageObject(canvas, o, scale, layout = layout)
            }
            PageObjectKind.IMAGE -> drawPageObject(canvas, o, scale, picture(o.media, decodeSide(maxOf(o.w, o.h) * scale)))
            PageObjectKind.MASK -> drawPageObject(canvas, o, scale, open = open)
        }
    }

    /** The picture [name] decoded for a long side of [side] px; a coarser one already decoded is drawn meanwhile. */
    private fun picture(name: String?, side: Int): PictureState {
        if (name == null || media == null) return PictureState.Missing
        val have = bitmaps.get(name)
        if (have != null && have.side >= side) return PictureState.Ready(have.bitmap)
        if (have == null && name in missing) return PictureState.Missing
        // a decode at this side (or a finer one) already failed: keep what is there
        if (have != null && !failures.shouldLoad(name, side)) return PictureState.Ready(have.bitmap)
        if (loading.add(name)) scope.launch {
            val bitmap = try {
                withContext(Dispatchers.IO) { media.bitmap(name, side) }
            } catch (e: CancellationException) {
                loading -= name
                throw e
            } catch (e: Throwable) {
                Log.e("Wanki", "picture $name not decoded", e)
                null
            }
            loading -= name
            if (bitmap != null) {
                bitmaps.put(name, Decoded(bitmap, side))
                failures.succeeded(name, side)
                rev++
            } else {
                failures.failed(name, side)
                // nothing to draw yet: the placeholder turns into «missing» once; a coarser copy just stays
                if (bitmaps.get(name) == null) {
                    missing += name
                    rev++
                }
            }
        }
        return have?.let { PictureState.Ready(it.bitmap) } ?: PictureState.Loading
    }

    companion object {
        const val MAX_SIDE = 2048

        /** Bytes of decoded pictures kept: min(32 MB, an eighth of the heap). */
        val PICTURE_BUDGET: Long = minOf(32L shl 20, Runtime.getRuntime().maxMemory() / 8)

        /** The decode size for a picture shown [px] long: the next power of two, 256..[MAX_SIDE], so zooming reloads rarely. */
        fun decodeSide(px: Float): Int {
            var s = 256
            while (s < px && s < MAX_SIDE) s *= 2
            return s
        }
    }
}
