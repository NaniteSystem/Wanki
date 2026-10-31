package app.wanki.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import app.wanki.MainActivity
import app.wanki.data.PageEntity
import app.wanki.data.PageObjectEntity
import app.wanki.data.PageObjectKind
import app.wanki.data.PageTemplate
import app.wanki.graph
import app.wanki.input.InkClipboard
import app.wanki.input.InkLayer
import app.wanki.input.InkSection
import app.wanki.input.InkTiles
import app.wanki.input.ToolSelection
import app.wanki.ui.notebooks.PicturePickers
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class PageObjectsUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val repo get() = rule.activity.graph.repository
    private val stamp = System.nanoTime().toString()

    @Before
    fun reset() {
        rule.activity.graph.settings.setTool(InkSection.NOTEBOOK, ToolSelection())
        rule.runOnUiThread { InkClipboard.clip = null }
        PicturePickers.registry = null
    }

    @After
    fun restore() = reset()

    private fun notebook(title: String, objects: (PageEntity) -> List<PageObjectEntity> = { emptyList() }): PageEntity = runBlocking {
        val nb = repo.createNotebook(title, PageTemplate.LINED, "auto")
        val page = repo.pages(nb.id).single()
        objects(page).takeIf { it.isNotEmpty() }?.let { repo.savePageObjects(page, it) }
        page
    }

    private fun textRow(page: PageEntity, text: String) = PageObjectEntity(
        pageId = page.id, kind = PageObjectKind.TEXT.name, x = 200f, y = 150f, w = 600f, h = 50f, text = text, updatedAt = 1,
    )

    /** Opens the notebook [title] from the list and returns the page's layer once it is loaded. */
    private fun open(title: String, tab: String = "Тетради", pageText: String? = "стр. 1 из 1"): InkLayer {
        val before = rule.runOnUiThread { InkTiles.live().toSet() }
        rule.waitUntilAtLeastOneExists(hasTestTag("tab-$tab"), 15_000)
        rule.onNodeWithTag("tab-$tab").performClick()
        rule.waitUntilAtLeastOneExists(hasText(title), 10_000)
        rule.onNode(hasText(title)).performClick()
        pageText?.let { rule.waitUntilAtLeastOneExists(hasText(it), 10_000) }
        rule.waitUntilAtLeastOneExists(hasTestTag("page-canvas"), 10_000)
        return layerAfter(before)
    }

    private fun layerAfter(before: Set<InkTiles>): InkLayer {
        rule.waitUntil(10_000) { rule.runOnUiThread { InkTiles.live().any { it !in before } } }
        val layer = rule.runOnUiThread { InkTiles.live().first { it !in before }.layer }
        // the editor has loaded when the insert items are offered (or, on a board, the menu has anything)
        rule.waitForIdle()
        return layer
    }

    private fun menu(item: String) {
        rule.onNodeWithTag("menu").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag(item), 5_000)
        rule.onNodeWithTag(item).performClick()
        rule.waitForIdle()
    }

    private fun stored(page: PageEntity) = runBlocking { repo.pageObjects(page.id) }

    private fun shot(name: String) {
        rule.waitForIdle()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun sh(cmd: String) = ui.executeShellCommand(cmd).use { fd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).readBytes() }
        sh("mkdir -p /sdcard/shots-task8")
        sh("screencap -p /sdcard/shots-task8/$name.png")
    }

    private fun insertText(text: String, size: Int? = null, shots: Boolean = false) {
        rule.onNodeWithTag("menu").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("menu-insert-text"), 5_000)
        if (shots) shot("insert-menu")
        rule.onNodeWithTag("menu-insert-text").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("text-block-field"), 5_000)
        rule.onNodeWithTag("text-block-field").performTextInput(text)
        size?.let { rule.onNodeWithTag("text-size-$it").performClick() }
        if (shots) shot("text-dialog")
        rule.onNodeWithTag("text-block-done").performClick()
        rule.waitForIdle()
    }

    private fun lassoAll() {
        rule.onNodeWithTag("tool-LASSO").performClick()
        rule.onNodeWithTag("page-canvas").performTouchInput {
            down(Offset(width * 0.03f, height * 0.03f))
            moveTo(Offset(width * 0.97f, height * 0.03f))
            moveTo(Offset(width * 0.97f, height * 0.97f))
            moveTo(Offset(width * 0.03f, height * 0.97f))
            moveTo(Offset(width * 0.03f, height * 0.04f))
            up()
        }
        rule.waitUntilAtLeastOneExists(hasTestTag("sel-more"), 5_000)
    }

    @Test
    fun insertTextBlock() {
        val title = "Objects text $stamp"
        val page = notebook(title)
        val layer = open(title)
        insertText("Привет", size = 48, shots = true)
        rule.waitUntil(5_000) { stored(page).size == 1 }
        stored(page).single().let {
            assertEquals("Привет", it.text)
            assertEquals(PageObjectKind.TEXT.name, it.kind)
            assertEquals(48f, it.textSize)
            assertEquals(600f, it.w)
            assertTrue(it.h >= 48f)
        }
        assertEquals(1, rule.runOnUiThread { layer.objects.size })

        val before = rule.runOnUiThread { InkTiles.live().toSet() }
        rule.activityRule.scenario.recreate()
        rule.waitUntilAtLeastOneExists(hasText("стр. 1 из 1"), 10_000)
        rule.waitUntil(10_000) { rule.runOnUiThread { InkTiles.live().any { it !in before && it.layer.objects.size == 1 } } }
        assertEquals("Привет", rule.runOnUiThread { InkTiles.live().first { it !in before }.layer.objects.single().text })
    }

    @Test
    fun editTextBlockFromLasso() {
        val title = "Objects edit $stamp"
        val page = notebook(title) { listOf(textRow(it, "Старый текст")) }
        val layer = open(title)
        rule.waitUntil(5_000) { rule.runOnUiThread { layer.objects.size == 1 } }
        lassoAll()
        assertEquals(1, rule.runOnUiThread { layer.selectedObjects.size })
        shot("selection")
        rule.onNodeWithTag("sel-more").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("sel-edit-text"), 5_000)
        shot("selection-menu")
        rule.onNodeWithTag("sel-edit-text").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("text-block-field"), 5_000)
        rule.onNodeWithTag("text-block-field").performTextClearance()
        rule.onNodeWithTag("text-block-field").performTextInput("Новый текст")
        rule.onNodeWithTag("text-block-done").performClick()
        rule.waitUntil(5_000) { stored(page).singleOrNull()?.text == "Новый текст" }
        // one undo step brings the old text back
        rule.onNodeWithTag("undo").performClick()
        rule.waitUntil(5_000) { stored(page).singleOrNull()?.text == "Старый текст" }
    }

    /** A picture "picked" through a stubbed [ActivityResultRegistry]. */
    @Test
    fun insertPictureFromGallery() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(ctx.cacheDir, "test-picture-$stamp.jpg")
        Bitmap.createBitmap(1200, 800, Bitmap.Config.RGB_565).apply {
            eraseColor(Color.rgb(90, 160, 220))
            val c = android.graphics.Canvas(this)
            c.drawCircle(300f, 300f, 200f, android.graphics.Paint().apply { color = Color.rgb(240, 200, 40) })
            file.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
        val uri = Uri.fromFile(file)
        PicturePickers.registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                dispatchResult(requestCode, uri)
            }
        }
        val title = "Objects picture $stamp"
        val page = notebook(title)
        val layer = open(title)
        insertText("Подпись к картинке: зима, лето")
        rule.waitUntil(5_000) { stored(page).size == 1 }
        rule.onNodeWithTag("menu").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("menu-insert-image"), 5_000)
        rule.onNodeWithTag("menu-insert-image").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("insert-gallery"), 5_000)
        assertTrue(rule.onAllNodes(hasTestTag("insert-camera")).fetchSemanticsNodes().isNotEmpty())
        shot("picture-menu")
        rule.onNodeWithTag("insert-gallery").performClick()
        rule.waitUntil(10_000) { stored(page).count { it.kind == PageObjectKind.IMAGE.name } == 1 }
        val image = stored(page).single { it.kind == PageObjectKind.IMAGE.name }
        val name = image.media!!
        assertTrue(name, name.matches(Regex("page-[0-9a-f-]+\\.jpg")))
        assertNotNull(rule.activity.graph.media.file(name))
        // fitted into 600 × 600
        assertEquals(600f, image.w, 0.01f)
        assertEquals(400f, image.h, 0.01f)
        rule.runOnUiThread { layer.clearSelection() }
        rule.onNodeWithTag("tool-PEN").performClick()
        rule.waitForIdle()
        Thread.sleep(500) // the picture decodes on IO
        shot("page-text-and-picture")
        lassoAll()
        shot("page-objects-selected")
        file.delete()
    }

    @Test
    fun undoRemovesInsertedBlock() {
        val title = "Objects undo $stamp"
        val page = notebook(title)
        val layer = open(title)
        insertText("Временно")
        rule.waitUntil(5_000) { stored(page).size == 1 }
        rule.onNodeWithTag("undo").performClick()
        rule.waitUntil(5_000) { rule.runOnUiThread { layer.objects.isEmpty() } }
        rule.waitUntil(5_000) { stored(page).isEmpty() }
        rule.onNodeWithTag("redo").performClick()
        rule.waitUntil(5_000) { stored(page).size == 1 }
    }

    @Test
    fun boardHasNoInsertItems() {
        val title = "Objects board $stamp"
        runBlocking { repo.createBoard(title) }
        open(title, tab = "Доски", pageText = null)
        rule.onNodeWithTag("menu").performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(hasTestTag("menu-insert-text")).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodes(hasTestTag("menu-insert-image")).fetchSemanticsNodes().isEmpty())
    }

    /** A picture whose file is gone (a backup restored without it) draws a placeholder and the page works. */
    @Test
    fun missingPictureShowsPlaceholder() {
        val title = "Objects missing $stamp"
        val page = notebook(title) {
            listOf(
                PageObjectEntity(pageId = it.id, kind = PageObjectKind.IMAGE.name, x = 200f, y = 150f, w = 500f, h = 350f, media = "page-gone-$stamp.jpg", updatedAt = 1),
                textRow(it, "Картинка пропала").copy(y = 520f),
            )
        }
        val layer = open(title)
        rule.waitUntil(5_000) { rule.runOnUiThread { layer.objects.size == 2 } }
        Thread.sleep(500)
        shot("missing-picture")
        // still editable: a new block saves with the other two
        insertText("ещё")
        rule.waitUntil(5_000) { stored(page).size == 3 }
    }
}
