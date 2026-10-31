package app.wanki.input

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import app.wanki.data.PageSize
import app.wanki.export.PdfGeometry
import app.wanki.ink.InkRect
import app.wanki.media.MediaStore
import app.wanki.ui.common.PageGeometry
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Page backgrounds (Decision 12): a stored picture, or a page of a stored PDF rendered on demand through
 * [PdfRenderer], one render at a time. The screen's bitmaps are kept in an LRU of [CACHED] entries.
 */
object PageBackgrounds {
    const val MIN_SIDE = 1024
    const val MAX_SIDE = 4096
    private const val CACHED = 3

    private data class Key(val name: String, val pdfPage: Int?, val side: Int)

    /** PdfRenderer is not to be used from two threads; the cache is guarded by the same lock. */
    private val lock = Mutex()
    private val cache = object : LinkedHashMap<Key, Bitmap>(CACHED + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Bitmap>?) = size > CACHED
    }

    /**
     * The long side in pixels for a page [pageLongSide] world units long shown at [scale] screen px per unit: the page's
     * length at the next power-of-two zoom level, so zooming re-renders rarely, clamped to [MIN_SIDE]..[MAX_SIDE].
     */
    fun sideFor(pageLongSide: Float, scale: Float): Int {
        var z = 1f
        while (z < scale && z < 64f) z *= 2f
        while (z / 2f >= scale && z > 1f / 64f) z /= 2f
        return (pageLongSide * z).roundToInt().coerceIn(MIN_SIDE, MAX_SIDE)
    }

    /** The box a background is fitted into: the sheet, or the first A4 sheet of an endless page. */
    fun box(landscape: Boolean, pageSize: PageSize): InkRect =
        if (pageSize == PageSize.ENDLESS) PageGeometry.BOUNDS else PageGeometry.bounds(landscape, pageSize)

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Draws [bitmap] fitted and centred into [box] (world units, under the canvas's matrix). */
    fun draw(canvas: Canvas, bitmap: Bitmap, box: InkRect) {
        val r = PdfGeometry.fit(bitmap.width.toFloat(), bitmap.height.toFloat(), box)
        canvas.drawBitmap(bitmap, null, RectF(r.left, r.top, r.right, r.bottom), paint)
    }

    /**
     * The background [name] ([pdfPage] of a PDF, or a picture when null) with a long side of about [longSidePx]
     * (clamped to [MIN_SIDE]..[MAX_SIDE]), from the cache or rendered now on IO. A missing or unreadable file, or no
     * memory for it, gives null. The bitmap is shared: never recycle it.
     */
    suspend fun bitmap(media: MediaStore, name: String, pdfPage: Int?, longSidePx: Int): Bitmap? {
        val key = Key(name, pdfPage, longSidePx.coerceIn(MIN_SIDE, MAX_SIDE))
        return lock.withLock {
            cache[key] ?: renderLocked(media, name, pdfPage, key.side)?.also { cache[key] = it }
        }
    }

    /** Like [bitmap] at any size and not cached: the caller owns the bitmap (PDF export, thumbnails). */
    suspend fun render(media: MediaStore, name: String, pdfPage: Int?, longSidePx: Int): Bitmap? =
        lock.withLock { renderLocked(media, name, pdfPage, longSidePx.coerceAtLeast(16)) }

    private suspend fun renderLocked(media: MediaStore, name: String, pdfPage: Int?, side: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (pdfPage == null) media.bitmap(name, side) else renderPdf(media, name, pdfPage, side)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // OutOfMemoryError included: the paper shows without it
            Log.e("Wanki", "background $name ($pdfPage) not rendered", e)
            null
        }
    }

    private fun renderPdf(media: MediaStore, name: String, index: Int, side: Int): Bitmap? {
        val f = media.file(name) ?: return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                if (index !in 0 until r.pageCount) return null
                r.openPage(index).use { p ->
                    val k = side.toFloat() / maxOf(p.width, p.height)
                    val bmp = Bitmap.createBitmap((p.width * k).roundToInt().coerceAtLeast(1), (p.height * k).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE) // a PDF page is transparent where nothing is printed
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    }
}
