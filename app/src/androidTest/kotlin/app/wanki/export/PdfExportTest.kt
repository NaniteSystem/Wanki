package app.wanki.export

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.wanki.data.NotebookEntity
import app.wanki.data.PageSize
import app.wanki.data.PageTemplate
import app.wanki.data.Repository
import app.wanki.data.WankiDatabase
import app.wanki.ink.BrushKind
import app.wanki.ink.InkBrush
import app.wanki.ink.InkPoint
import app.wanki.ink.InkStroke
import app.wanki.ink.InkTool
import app.wanki.media.MediaStore
import app.wanki.ui.PerfInk
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfExportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: WankiDatabase
    private lateinit var repo: Repository
    private lateinit var exporter: PdfExporter
    private val files = ArrayList<File>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, WankiDatabase::class.java).build()
        repo = Repository(db)
        exporter = PdfExporter(context, repo, MediaStore(context))
    }

    @After
    fun tearDown() {
        db.close()
        files.forEach { it.delete() }
    }

    private fun line(x0: Float, x1: Float, y: Float, size: Float = 6f): InkStroke {
        var t = 0L
        val pts = (0..20).map { k -> InkPoint(x0 + (x1 - x0) * k / 20, y, (t++) * 8, Float.NaN, Float.NaN, Float.NaN) }
        return InkStroke(pts, InkTool.FINGER, InkBrush(BrushKind.PEN, 0xFF000000.toInt(), size))
    }

    private fun notebook(size: PageSize = PageSize.A4): NotebookEntity = runBlocking {
        repo.createNotebook("Экспорт", PageTemplate.LINED, "auto", size)
    }

    private fun export(nb: NotebookEntity, pageCount: Int? = null): File = runBlocking {
        val pages = repo.pages(nb.id).let { if (pageCount != null) it.take(pageCount) else it }
        exporter.export(nb, pages).also { files += it }
    }

    private fun <T> render(file: File, block: (PdfRenderer) -> T): T =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use(block) }

    private fun hasImages(file: File): Boolean {
        val text = String(file.readBytes(), Charsets.ISO_8859_1)
        return text.contains("/Subtype /Image") || text.contains("/Subtype/Image")
    }

    @Test
    fun inkOnlyPageIsVector() = runBlocking {
        val nb = notebook()
        val page = repo.pages(nb.id).single()
        repo.saveInk(page, (0 until 50).map { line(100f, 600f, 200f + it * 20f) })
        val file = export(nb)
        assertTrue(file.path, file.path.contains("/cache/exports/"))
        assertEquals("Экспорт стр. 1.pdf", file.name)
        render(file) { r ->
            assertEquals(1, r.pageCount)
            r.openPage(0).use { p ->
                assertEquals(595, p.width)
                assertEquals(842, p.height)
                val bmp = Bitmap.createBitmap(p.width * 2, p.height * 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                // world (350, 200) is on the first stroke; world (350, 190) is between strokes and the ruled lines
                val k = 2 * 595f / 1000f
                val on = bmp.getPixel((350 * k).toInt(), (200 * k).toInt())
                assertTrue("pixel ${Integer.toHexString(on)}", Color.red(on) < 80 && Color.green(on) < 80 && Color.blue(on) < 80)
                val off = bmp.getPixel((350 * k).toInt(), (190 * k).toInt())
                assertTrue("pixel ${Integer.toHexString(off)}", Color.red(off) > 200)
            }
        }
        assertFalse("a page of ink and ruled paper has no raster images", hasImages(file))
    }

    @Test
    fun wholeNotebookPageCount() = runBlocking {
        val nb = notebook()
        repo.addPage(nb)
        repo.addPage(nb)
        repo.saveInk(repo.pages(nb.id)[1], listOf(line(100f, 500f, 300f)))
        val file = export(nb)
        assertEquals("Экспорт.pdf", file.name)
        assertEquals(3, render(file) { it.pageCount })
    }

    @Test
    fun endlessSplits() = runBlocking {
        val nb = notebook(PageSize.ENDLESS)
        repo.saveInk(repo.pages(nb.id).single(), listOf(line(100f, 500f, 300f), line(100f, 500f, 2500f)))
        val file = export(nb)
        render(file) { r ->
            assertEquals(2, r.pageCount)
            r.openPage(1).use { p -> assertEquals(595 to 842, p.width to p.height) }
        }
    }

    @Test
    fun a5Size() = runBlocking {
        val nb = notebook(PageSize.A5)
        val file = export(nb)
        render(file) { r -> r.openPage(0).use { p -> assertEquals(420 to 595, p.width to p.height) } }
    }

    @Test
    fun threeThousandStrokesUnderLimit() = runBlocking {
        val nb = notebook()
        repo.saveInk(repo.pages(nb.id).single(), PerfInk.page())
        val start = System.nanoTime()
        val file = export(nb)
        val ms = (System.nanoTime() - start) / 1_000_000
        android.util.Log.i("WankiPdf", "3000 strokes: $ms ms, ${file.length()} bytes")
        assertTrue("export took $ms ms", ms <= 5000)
        assertTrue("file is ${file.length()} bytes", file.length() <= 3L * 1024 * 1024)
    }

    @Test
    fun unreadableInkStillExportsThePaper() = runBlocking {
        val nb = notebook()
        repo.saveInkBlob(repo.pages(nb.id).single(), byteArrayOf(9, 9, 9, 9))
        val file = export(nb)
        assertEquals(1, render(file) { it.pageCount })
    }

    @Test
    fun shareIntentGrantsRead() = runBlocking {
        val nb = notebook()
        val file = export(nb)
        val chooser = exporter.share(file)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertNotNull(chooser.clipData)
        val uri = chooser.clipData!!.getItemAt(0).uri
        assertEquals("app.wanki.files", uri.authority)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        @Suppress("DEPRECATION")
        val target = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, target.action)
        assertEquals("application/pdf", target.type)
    }
}
