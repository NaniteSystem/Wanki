package app.wanki.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.FileProvider
import app.wanki.data.InkLoad
import app.wanki.data.NotebookEntity
import app.wanki.data.NotebookKind
import app.wanki.data.PageEntity
import app.wanki.data.PageObjectKind
import app.wanki.data.PageSize
import app.wanki.data.PageTemplate
import app.wanki.data.Repository
import app.wanki.ink.InkRect
import app.wanki.ink.InkStroke
import app.wanki.input.PageBackgrounds
import app.wanki.input.PageObject
import app.wanki.input.PictureState
import app.wanki.input.StrokeConversion
import app.wanki.input.drawPageObject
import app.wanki.input.toModelOrNull
import app.wanki.media.MediaStore
import app.wanki.ui.common.PageGeometry
import app.wanki.ui.common.PaperColor
import app.wanki.ui.common.drawTemplate
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import android.app.ActivityManager
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Sizes of exported sheets (Decision 13). Pure. */
object PdfGeometry {
    /** A4 595 × 842 pt, A5 420 × 595 pt, swapped for landscape; an endless page is cut into portrait A4 sheets. */
    fun pagePoints(size: PageSize, landscape: Boolean): Pair<Int, Int> {
        val (w, h) = if (size == PageSize.A5) 420 to 595 else 595 to 842
        return if (landscape && size != PageSize.ENDLESS) h to w else w to h
    }

    /** An endless page [worldHeight] tall cut into sheets one page ([PageGeometry.HEIGHT]) high; at least one. */
    fun sheets(worldHeight: Float): List<ClosedFloatingPointRange<Float>> {
        val n = ceil(worldHeight / PageGeometry.HEIGHT).toInt().coerceAtLeast(1)
        return (0 until n).map { i -> (i * PageGeometry.HEIGHT)..((i + 1) * PageGeometry.HEIGHT) }
    }

    /** [srcW] × [srcH] scaled into [box] with its aspect kept, centred. */
    fun fit(srcW: Float, srcH: Float, box: InkRect): InkRect {
        if (srcW <= 0f || srcH <= 0f) return box
        val k = minOf(box.width / srcW, box.height / srcH)
        val w = srcW * k
        val h = srcH * k
        val l = box.left + (box.width - w) / 2
        val t = box.top + (box.height - h) / 2
        return InkRect(l, t, l + w, t + h)
    }
}

/**
 * Writes pages as a PDF in `cache/exports/` and shares it. Each page is drawn in the screen's order: paper and template
 * (vector), the background (raster), objects (text as vector, pictures raster), then the strokes as filled outlines
 * of their androidx.ink meshes (vector). An endless page is cut into A4 sheets down to its lowest ink or object.
 */
class PdfExporter(private val context: Context, private val repository: Repository, private val media: MediaStore) {

    /**
     * Writes [pages] of [notebook] into one PDF and returns it; named after the notebook (plus « стр. N» for one page)
     * and the time, never over an earlier export.
     */
    suspend fun export(notebook: NotebookEntity, pages: List<PageEntity>): File = withContext(Dispatchers.IO) {
        // the screen's cached backgrounds give their memory to the export
        PageBackgrounds.evictAll()
        val size = runCatching { PageSize.valueOf(notebook.pageSize) }.getOrDefault(PageSize.A4)
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val loaded = pages.map { load(it, size) }
        val pxPerPt = rasterScale(loaded)
        val doc = PdfDocument()
        try {
            var number = 1
            for (p in loaded) {
                ensureActive()
                number = drawPage(doc, p, size, pxPerPt, number)
            }
            val target = uniqueFile(dir, fileName(notebook, pages) + " " + LocalDateTime.now().format(STAMP))
            val part = File(dir, target.name + ".part")
            try {
                part.outputStream().use { doc.writeTo(it) }
                if (!part.renameTo(target)) throw java.io.IOException("PDF not stored")
            } finally {
                part.delete()
            }
            target
        } finally {
            doc.close()
        }
    }

    /** A chooser that sends [file] as `application/pdf`, with read access granted to the receiving app. */
    fun share(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Поделиться PDF")
    }

    /** One page's content read from the database, with its strokes converted to outline paths. */
    private class Loaded(
        val page: PageEntity,
        val template: PageTemplate,
        val paths: List<Pair<android.graphics.Path, Int>>,
        val unreadable: Boolean,
        val objects: List<PageObject>,
        /** The lowest ink or object (endless pages). */
        val contentBottom: Float,
    )

    private suspend fun load(page: PageEntity, size: PageSize): Loaded {
        val ink = repository.loadInkResult(page.id)
        val strokes = (ink as? InkLoad.Ok)?.strokes.orEmpty()
        if (ink is InkLoad.Unreadable) Log.e("Wanki", "page ${page.id}: ink cannot be read, exported without it", ink.error)
        val objects = repository.pageObjects(page.id).mapNotNull { it.toModelOrNull() }.sortedWith(PageObject.ORDER)
        var bottom = 0f
        if (size == PageSize.ENDLESS) {
            for (s in strokes) s.bounds()?.let { bottom = maxOf(bottom, it.bottom + s.brush.size / 2) }
            for (o in objects) bottom = maxOf(bottom, o.bounds().bottom)
        }
        return Loaded(
            page, runCatching { PageTemplate.valueOf(page.template) }.getOrDefault(PageTemplate.BLANK),
            outlines(strokes), ink is InkLoad.Unreadable, objects, bottom,
        )
    }

    /** Builds the strokes' meshes on the other cores; a stroke that does not convert is left out (and logged). */
    private suspend fun outlines(strokes: List<InkStroke>): List<Pair<android.graphics.Path, Int>> {
        if (strokes.isEmpty()) return emptyList()
        val parts = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        return coroutineScope {
            strokes.chunked((strokes.size + parts - 1) / parts).map { part ->
                async(Dispatchers.Default) {
                    part.mapNotNull { s ->
                        try {
                            StrokeConversion.toInk(s)?.let { StrokeOutline.path(it) to s.brush.color }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e("Wanki", "a stroke not exported", e)
                            null
                        }
                    }
                }
            }.awaitAll().flatten()
        }
    }

    /**
     * Pixels per point for backgrounds and pictures: [PX_PER_PT], or less when the whole document's rasters would
     * pass [rasterBudget] (PdfDocument keeps every page's bitmaps until it is written).
     */
    private fun rasterScale(pages: List<Loaded>): Float {
        var pt2 = 0.0
        val k = 595f / PageGeometry.WIDTH // points per world unit on A4; A5 needs less
        for (p in pages) {
            if (p.page.backgroundMedia != null) pt2 += 842.0 * 595.0
            for (o in p.objects) if (o.kind == PageObjectKind.IMAGE) pt2 += (o.w * k) * (o.h * k).toDouble()
        }
        if (pt2 == 0.0) return PX_PER_PT
        return minOf(PX_PER_PT, sqrt(rasterBudget() / (pt2 * 4)).toFloat()).coerceAtLeast(0.25f)
    }

    /** Draws [p] onto as many sheets as it needs, numbered from [number]; returns the next number. */
    private suspend fun drawPage(doc: PdfDocument, p: Loaded, size: PageSize, pxPerPt: Float, number: Int): Int {
        val page = p.page
        val world = PageGeometry.size(page.landscape, size, p.contentBottom)
        val sheets = if (size == PageSize.ENDLESS) PdfGeometry.sheets(p.contentBottom) else listOf(0f..world.height)
        val paperHeight = maxOf(world.height, sheets.last().endInclusive)
        val (ptW, ptH) = PdfGeometry.pagePoints(size, page.landscape)
        val k = ptW / world.width

        val box = PageBackgrounds.box(page.landscape, size)
        val background = page.backgroundMedia?.let { name ->
            val side = (maxOf(box.width, box.height) * k * pxPerPt).roundToInt()
            PageBackgrounds.render(media, name, page.backgroundPage, side)
        }
        val backgroundFailed = page.backgroundMedia != null && background == null
        val pictures = HashMap<String, Bitmap?>()
        for (o in p.objects) {
            val name = o.media ?: continue
            if (o.kind != PageObjectKind.IMAGE || name in pictures) continue
            val side = (maxOf(o.w, o.h) * k * pxPerPt).roundToInt().coerceIn(16, PICTURE_MAX_SIDE)
            pictures[name] = try {
                media.bitmap(name, side)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("Wanki", "picture $name not exported", e)
                null
            }
        }

        var n = number
        for (sheet in sheets) {
            currentCoroutineContext().ensureActive()
            val pdfPage = doc.startPage(PdfDocument.PageInfo.Builder(ptW, ptH, n++).create())
            val c = pdfPage.canvas
            c.save()
            c.scale(k, k)
            c.translate(0f, -sheet.start)
            c.clipRect(0f, sheet.start, world.width, sheet.endInclusive)
            drawPaper(c, p.template, world.width, paperHeight)
            if (background != null) PageBackgrounds.draw(c, background, box)
            for (o in p.objects) {
                val picture = o.media?.let { pictures[it] }?.let { PictureState.Ready(it) } ?: PictureState.Missing
                drawPageObject(c, o, k, picture)
            }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            for ((path, color) in p.paths) {
                fill.color = color
                c.drawPath(path, fill)
            }
            c.restore()
            val notes = listOfNotNull(
                "Рукописные записи этой страницы не удалось прочитать".takeIf { p.unreadable },
                "Фон не загрузился".takeIf { backgroundFailed },
            )
            notes.forEachIndexed { i, text -> c.drawText(text, 12f, 18f + i * 12f, NotePaint) }
            doc.finishPage(pdfPage)
        }
        return n
    }

    /**
     * Bytes of background and picture pixels one export may hold: a quarter of the app's memory class, an eighth on a
     * low-RAM device.
     */
    private fun rasterBudget(): Double {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mb = am.memoryClass.toDouble() / if (am.isLowRamDevice) 8 else 4
        return mb * 1024 * 1024
    }

    /** [base].pdf, or [base] (2).pdf etc. when that exists: an app may still be reading an earlier export. */
    private fun uniqueFile(dir: File, base: String): File {
        var f = File(dir, "$base.pdf")
        var n = 2
        while (f.exists()) f = File(dir, "$base (${n++}).pdf")
        return f
    }

    /** Paper colour and the template, through the same drawing code as the screen. */
    private fun drawPaper(c: Canvas, template: PageTemplate, w: Float, h: Float) {
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(c), Size(w, h)) {
            drawRect(PaperColor, Offset.Zero, Size(w, h))
            drawTemplate(template, InkRect(0f, 0f, w, h))
        }
    }

    companion object {
        const val DIR = "exports"
        private const val MIME = "application/pdf"
        private const val PX_PER_PT = 2f
        private const val PICTURE_MAX_SIDE = 2048
        /** Bytes of background and picture pixels one export may hold. */
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm")
        private const val DAY_MS = 24 * 3_600_000L

        private val NotePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFC5221F.toInt()
            textSize = 9f
        }

        /** The file name without extension: the notebook title, plus « стр. N» (a diary day: its date) for one page. */
        fun fileName(notebook: NotebookEntity, pages: List<PageEntity>): String {
            val one = pages.singleOrNull()
            val suffix = when {
                one == null -> ""
                notebook.kind == NotebookKind.DIARY.name && one.date != null -> " " + LocalDate.ofEpochDay(one.date).toString()
                else -> " стр. ${one.pageIndex + 1}"
            }
            return safe(notebook.title) + suffix
        }

        /** A title usable as a file name: no path separators or reserved characters, at most 80 characters. */
        fun safe(title: String): String =
            title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').take(80).ifBlank { "Wanki" }

        /** Deletes exports older than a day (they were shared already). Returns how many went. */
        fun clearOld(context: Context, now: Long = System.currentTimeMillis()): Int {
            val files = File(context.cacheDir, DIR).listFiles().orEmpty()
            return files.count { it.isFile && now - it.lastModified() > DAY_MS && it.delete() }
        }
    }
}
