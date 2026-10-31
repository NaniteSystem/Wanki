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
import android.util.LruCache
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Page backgrounds (Decision 12): a stored picture, or a page of a stored PDF rendered on demand through
 * [PdfRenderer], one render at a time. The screen's bitmaps are kept in an LRU bounded by [CACHE_BYTES] (one size
 * per page). The last PDF opened stays open for consecutive pages until another file is asked for or it is idle for
 * [IDLE_CLOSE_MS].
 */
object PageBackgrounds {
    const val MIN_SIDE = 1024
    const val MAX_SIDE = 4096

    /** Room for one A4 page rendered at [MAX_SIDE] (4096 × 2896 × 4 bytes). */
    const val CACHE_BYTES = 48 shl 20
    private const val IDLE_CLOSE_MS = 5_000L

    private data class Key(val name: String, val pdfPage: Int?, val side: Int)

    /** PdfRenderer is not to be used from two threads: every use of it, and of [open], holds this lock. */
    private val lock = Mutex()
    private val cache = object : LruCache<Key, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount
    }

    /** The PDF kept open, and what identifies its file. */
    private class OpenPdf(val path: String, val modified: Long, val length: Long, val fd: ParcelFileDescriptor, val renderer: PdfRenderer) {
        fun matches(f: File) = f.path == path && f.lastModified() == modified && f.length() == length

        fun close() {
            runCatching { renderer.close() }
            runCatching { fd.close() }
        }
    }

    private var open: OpenPdf? = null
    private val closer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var closeJob: Job? = null

    /** Drops every cached bitmap (before a PDF export, which needs the memory). */
    fun evictAll() = cache.evictAll()

    /** Page count and orientation (wider than tall) of the first [max] pages of the PDF [file]; IOException or SecurityException when it cannot be opened. */
    class PdfPages(val count: Int, val landscape: List<Boolean>)

    suspend fun pdfPages(file: File, max: Int): PdfPages = lock.withLock {
        withContext(Dispatchers.IO) {
            val r = rendererLocked(file)
            PdfPages(r.pageCount, (0 until minOf(r.pageCount, max)).map { i -> r.openPage(i).use { p -> p.width > p.height } })
        }
    }

    /** The renderer for [file], reusing the open one; must hold [lock]. A failed open leaves nothing open. */
    private fun rendererLocked(file: File): PdfRenderer {
        open?.takeIf { it.matches(file) }?.let { scheduleClose(); return it.renderer }
        open?.close()
        open = null
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(fd)
        } catch (e: Throwable) {
            fd.close()
            throw e
        }
        open = OpenPdf(file.path, file.lastModified(), file.length(), fd, renderer)
        scheduleClose()
        return renderer
    }

    private fun scheduleClose() {
        closeJob?.cancel()
        closeJob = closer.launch {
            delay(IDLE_CLOSE_MS)
            lock.withLock {
                open?.close()
                open = null
            }
        }
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
            cache.get(key) ?: renderLocked(media, name, pdfPage, key.side)?.also { b ->
                // one size per page: the others go
                cache.snapshot().keys.filter { it.name == name && it.pdfPage == pdfPage && it != key }.forEach { cache.remove(it) }
                cache.put(key, b)
            }
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
        val r = rendererLocked(f)
        if (index !in 0 until r.pageCount) return null
        return r.openPage(index).use { p ->
            val k = side.toFloat() / maxOf(p.width, p.height)
            val bmp = Bitmap.createBitmap((p.width * k).roundToInt().coerceAtLeast(1), (p.height * k).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE) // a PDF page is transparent where nothing is printed
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }
    }
}
