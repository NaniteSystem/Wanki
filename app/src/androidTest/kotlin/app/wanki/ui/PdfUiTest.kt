package app.wanki.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import app.wanki.MainActivity
import app.wanki.data.PageEntity
import app.wanki.data.PageTemplate
import app.wanki.export.PdfExporter
import app.wanki.graph
import app.wanki.input.InkSection
import app.wanki.input.ToolSelection
import app.wanki.ui.notebooks.PicturePickers
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The notebook menu's «Экспорт в PDF» and «Импорт PDF или картинок», and a background that does not load. */
@OptIn(ExperimentalTestApi::class)
class PdfUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val repo get() = rule.activity.graph.repository
    private val stamp = System.nanoTime().toString()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun reset() {
        rule.activity.graph.settings.setTool(InkSection.NOTEBOOK, ToolSelection())
        PicturePickers.registry = null
    }

    @After
    fun restore() = reset()

    private fun open(title: String, pageText: String) {
        rule.waitUntilAtLeastOneExists(hasTestTag("tab-Тетради"), 15_000)
        rule.onNodeWithTag("tab-Тетради").performClick()
        rule.waitUntilAtLeastOneExists(hasText(title), 10_000)
        rule.onNode(hasText(title)).performClick()
        rule.waitUntilAtLeastOneExists(hasText(pageText), 10_000)
        rule.waitUntilAtLeastOneExists(hasTestTag("page-canvas"), 10_000)
        rule.waitForIdle()
    }

    private fun menu(item: String) {
        rule.onNodeWithTag("menu").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag(item), 5_000)
        rule.onNodeWithTag(item).performClick()
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun sh(cmd: String) = ui.executeShellCommand(cmd).use { fd -> ParcelFileDescriptor.AutoCloseInputStream(fd).readBytes() }
        sh("mkdir -p /sdcard/shots-task11")
        sh("screencap -p /sdcard/shots-task11/$name.png")
    }

    /** A worksheet-like PDF: a title bar and boxes to write in; page 2 is landscape. */
    private fun worksheet(): File {
        val doc = PdfDocument()
        listOf(595 to 842, 842 to 595).forEachIndexed { i, (w, h) ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
            val c = page.canvas
            c.drawRect(30f, 30f, w - 30f, 80f, Paint().apply { color = Color.rgb(40, 90, 200) })
            c.drawText("Worksheet ${i + 1}", 40f, 64f, Paint().apply { color = Color.WHITE; textSize = 24f })
            val box = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.rgb(60, 60, 60) }
            var y = 110f
            while (y + 60f < h - 30f) { c.drawRect(40f, y, w - 40f, y + 60f, box); y += 80f }
            doc.finishPage(page)
        }
        val f = File(context.cacheDir, "worksheet-$stamp.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    @Test
    fun importPdfFromMenuAndWriteOverIt() {
        val pdf = worksheet()
        val uri = Uri.fromFile(pdf)
        PicturePickers.registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                @Suppress("UNCHECKED_CAST")
                assertEquals(listOf("application/pdf", "image/*"), (input as Array<String>).toList())
                dispatchResult(requestCode, listOf(uri))
            }
        }
        val title = "PDF import $stamp"
        val nb = runBlocking { repo.createNotebook(title, PageTemplate.LINED, "auto") }
        open(title, "стр. 1 из 1")
        menu("menu-import")
        // the first imported page is shown
        rule.waitUntilAtLeastOneExists(hasText("стр. 2 из 3"), 15_000)
        val pages = runBlocking { repo.pages(nb.id) }
        assertEquals(listOf(null, 0, 1), pages.map { it.backgroundPage })
        Thread.sleep(800) // the background renders on IO
        shot("imported-pdf-page")
        rule.onNodeWithTag("page-canvas").performTouchInput {
            down(Offset(width * 0.2f, height * 0.3f))
            for (k in 1..20) moveTo(Offset(width * (0.2f + k * 0.03f), height * (0.3f + if (k % 2 == 0) 0.02f else 0f)))
            up()
        }
        rule.waitUntil(5_000) { runBlocking { repo.loadInk(pages[1].id) }.isNotEmpty() }
        shot("imported-pdf-written-over")
        pdf.delete()
    }

    @Test
    fun missingBackgroundSaysSo() {
        val title = "PDF missing $stamp"
        val page: PageEntity = runBlocking {
            val nb = repo.createNotebook(title, PageTemplate.GRID, "auto")
            repo.pages(nb.id).single().also { repo.setPageBackground(it.id, "page-gone-$stamp.pdf", 0) }
        }
        open(title, "стр. 1 из 1")
        rule.waitUntilAtLeastOneExists(hasTestTag("background-failed"), 10_000)
        shot("background-failed")
        assertEquals("page-gone-$stamp.pdf", runBlocking { repo.pages(page.notebookId).single().backgroundMedia })
    }

    @Test
    fun exportDialogMakesTheFile() {
        val title = "PDF export $stamp"
        val nb = runBlocking {
            repo.createNotebook(title, PageTemplate.LINED, "auto").also { repo.addPage(it) }
        }
        open(title, "стр. 1 из 2")
        rule.onNodeWithTag("page-canvas").performTouchInput {
            down(Offset(width * 0.2f, height * 0.25f))
            for (k in 1..20) moveTo(Offset(width * (0.2f + k * 0.03f), height * (0.25f + if (k % 2 == 0) 0.03f else 0f)))
            up()
        }
        menu("menu-export-pdf")
        rule.waitUntilAtLeastOneExists(hasTestTag("export-all"), 5_000)
        assertTrue(rule.onAllNodes(hasTestTag("export-page")).fetchSemanticsNodes().isNotEmpty())
        shot("export-dialog")
        val exports = File(context.cacheDir, PdfExporter.DIR)
        fun made() = exports.listFiles().orEmpty().filter { it.name.startsWith(PdfExporter.safe(title) + " ") && it.name.endsWith(".pdf") }
        made().forEach { it.delete() }
        rule.onNodeWithTag("export-all").performClick()
        rule.waitUntil(15_000) { made().isNotEmpty() }
        val target = made().single()
        // the share chooser covers the app; the drawn stroke is in the file
        Thread.sleep(1500)
        shot("export-share-chooser")
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                assertEquals(2, r.pageCount)
                renderForReview(r, "export-pdf-page1", 0, 2f)
                // zoomed in 8×: the strokes stay sharp (vector)
                renderForReview(r, "export-pdf-zoom", 0, 8f, crop = true)
            }
        }
        assertEquals(2, runBlocking { repo.pages(nb.id) }.size)
    }

    /** Writes a rendered page next to the screenshots for review (through the app's external folder, which the shell can read). */
    private fun renderForReview(r: PdfRenderer, name: String, index: Int, pxPerPt: Float, crop: Boolean = false) {
        r.openPage(index).use { p ->
            val w = if (crop) 1000 else (p.width * pxPerPt).toInt()
            val h = if (crop) 700 else (p.height * pxPerPt).toInt()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            // the crop shows the top-left area of the stroke
            val m = android.graphics.Matrix().apply { setScale(pxPerPt, pxPerPt); if (crop) postTranslate(-p.width * pxPerPt * 0.18f, -p.height * pxPerPt * 0.2f) }
            p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val f = File(context.getExternalFilesDir(null), "$name.png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
            ui.executeShellCommand("cp ${f.path} /sdcard/shots-task11/$name.png").use { fd -> ParcelFileDescriptor.AutoCloseInputStream(fd).readBytes() }
        }
    }
}
