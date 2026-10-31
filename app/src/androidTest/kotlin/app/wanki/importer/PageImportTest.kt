package app.wanki.importer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.wanki.data.NotebookEntity
import app.wanki.data.PageTemplate
import app.wanki.data.Repository
import app.wanki.data.WankiDatabase
import app.wanki.input.PageBackgrounds
import app.wanki.media.MediaStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageImportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val media = MediaStore(context)
    private lateinit var db: WankiDatabase
    private lateinit var repo: Repository
    private lateinit var importer: PageImporter
    private val dir = File(context.cacheDir, "page-import-test").apply { mkdirs() }
    private lateinit var before: Set<String>

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, WankiDatabase::class.java).build()
        repo = Repository(db)
        importer = PageImporter(context, repo, media)
        before = mediaFiles()
    }

    @After
    fun tearDown() {
        db.close()
        // only what this test added
        (mediaFiles() - before).forEach { File(media.dir, it).delete() }
        dir.deleteRecursively()
    }

    private fun mediaFiles(): Set<String> = media.dir.list().orEmpty().filter { it.startsWith("page-") }.toSet()

    private fun notebook(): NotebookEntity = runBlocking { repo.createNotebook("Импорт", PageTemplate.LINED, "auto") }

    /** A PDF with one page per (width, height) in points, each with a coloured box. */
    private fun pdf(name: String, sizes: List<Pair<Int, Int>>): File {
        val doc = PdfDocument()
        sizes.forEachIndexed { i, (w, h) ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
            page.canvas.drawRect(20f, 20f, w - 20f, h - 20f, Paint().apply { color = Color.rgb(40, 90, 200) })
            doc.finishPage(page)
        }
        val f = File(dir, name)
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    private fun jpeg(name: String, w: Int, h: Int): File {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565).apply { eraseColor(Color.rgb(200, 120, 40)) }
        val f = File(dir, name)
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        b.recycle()
        return f
    }

    /** A well-formed PDF whose page tree is empty. */
    private fun emptyPdf(): File {
        val objects = listOf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [] /Count 0 >>")
        val sb = StringBuilder("%PDF-1.4\n")
        val offsets = objects.mapIndexed { i, o ->
            val at = sb.length
            sb.append("${i + 1} 0 obj\n$o\nendobj\n")
            at
        }
        val xref = sb.length
        sb.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { sb.append(String.format("%010d 00000 n \n", it)) }
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return File(dir, "empty.pdf").apply { writeText(sb.toString(), Charsets.ISO_8859_1) }
    }

    private fun asset(name: String): File {
        val f = File(dir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    private fun import(nb: NotebookEntity, vararg files: File, after: Int = 0) = runBlocking {
        importer.import(files.map { Uri.fromFile(it) }, nb.id, after)
    }

    @Test
    fun importPdfAddsPagesWithBackgrounds() = runBlocking {
        val nb = notebook()
        val r = import(nb, pdf("three.pdf", listOf(595 to 842, 842 to 595, 595 to 842)))
        assertEquals(ImportResult.Added(3, capped = false), r)
        val pages = repo.pages(nb.id)
        assertEquals(4, pages.size)
        assertNull(pages[0].backgroundMedia)
        assertEquals(listOf(0, 1, 2), pages.subList(1, 4).map { it.backgroundPage })
        assertEquals(listOf(false, true, false), pages.subList(1, 4).map { it.landscape })
        val name = pages[1].backgroundMedia!!
        assertTrue(name, name.matches(Regex("page-[0-9a-f-]+\\.pdf")))
        assertTrue(pages.subList(1, 4).all { it.backgroundMedia == name })
        assertNotNull(media.file(name))
    }

    @Test
    fun importImages() = runBlocking {
        val nb = notebook()
        val r = import(nb, jpeg("a.jpg", 800, 600), jpeg("b.jpg", 300, 900))
        assertEquals(ImportResult.Added(2, capped = false), r)
        val pages = repo.pages(nb.id).drop(1)
        assertEquals(2, pages.size)
        pages.forEach { p ->
            assertTrue(p.backgroundMedia!!, p.backgroundMedia!!.endsWith(".jpg"))
            assertNull(p.backgroundPage)
            assertNotNull(media.file(p.backgroundMedia!!))
        }
        assertEquals(listOf(true, false), pages.map { it.landscape })
    }

    @Test
    fun backgroundRenders() = runBlocking {
        val nb = notebook()
        import(nb, pdf("two.pdf", listOf(595 to 842, 842 to 595)))
        val p = repo.pages(nb.id)[1]
        val b = PageBackgrounds.bitmap(media, p.backgroundMedia!!, p.backgroundPage, 1024)
        assertNotNull(b)
        assertTrue("${b!!.width}×${b.height}", maxOf(b.width, b.height) >= 1024)
        // the page's colour box is there
        val c = b.getPixel(b.width / 2, b.height / 2)
        assertTrue(Integer.toHexString(c), Color.blue(c) > 150 && Color.red(c) < 100)
        assertNull(PageBackgrounds.bitmap(media, "page-missing.pdf", 0, 1024))
    }

    private fun assertNothingAdded(nb: NotebookEntity, r: ImportResult, message: String) = runBlocking {
        assertEquals(ImportResult.Failed(message), r)
        assertEquals(1, repo.pages(nb.id).size)
        assertEquals(before, mediaFiles())
    }

    @Test
    fun badInputsAddNothing() {
        val nb = notebook()
        val good = pdf("good.pdf", listOf(595 to 842, 595 to 842))
        val truncated = File(dir, "truncated.pdf").apply { writeBytes(good.readBytes().let { it.copyOf(it.size / 2) }) }
        assertNothingAdded(nb, import(nb, truncated), "Не удалось открыть PDF")
        assertNothingAdded(nb, import(nb, asset("locked.pdf")), "Не удалось открыть PDF")
        assertNothingAdded(nb, import(nb, emptyPdf()), "В PDF нет страниц")
        val text = File(dir, "notes.png").apply { writeText("not a picture at all") }
        assertNothingAdded(nb, import(nb, text), "Не удалось открыть картинку")
        // a good file before a bad one: its media goes too
        assertNothingAdded(nb, import(nb, good, jpeg("ok.jpg", 100, 100), text), "Не удалось открыть картинку")
    }

    @Test
    fun capAt300() = runBlocking {
        val nb = notebook()
        val r = import(nb, pdf("big.pdf", List(301) { 100 to 140 }))
        assertEquals(ImportResult.Added(300, capped = true), r)
        assertEquals(301, repo.pages(nb.id).size)
        assertEquals(299, repo.pages(nb.id).last().backgroundPage)
    }
}
