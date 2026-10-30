package app.wanki.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.platform.app.InstrumentationRegistry
import app.wanki.MainActivity
import app.wanki.data.PageTemplate
import app.wanki.graph
import app.wanki.input.EraserMode
import app.wanki.input.EraserSettings
import app.wanki.input.EraserSize
import app.wanki.input.InkLayer
import app.wanki.input.InkSection
import app.wanki.input.InkTiles
import app.wanki.input.ToolSelection
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class EraserTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val settings get() = rule.activity.graph.settings
    private val repo get() = rule.activity.graph.repository
    private val stamp = System.nanoTime().toString()

    @Before
    fun resetSettings() {
        settings.setTool(InkSection.NOTEBOOK, ToolSelection())
        settings.setEraser(InkSection.NOTEBOOK, EraserSettings())
    }

    @After
    fun restoreSettings() = resetSettings()

    private fun openNotebook(title: String): InkLayer {
        val before = rule.runOnUiThread { InkTiles.live().toSet() }
        rule.waitUntilAtLeastOneExists(hasTestTag("tab-Тетради"), 15_000)
        rule.onNodeWithTag("tab-Тетради").performClick()
        rule.waitUntilAtLeastOneExists(hasText(title), 10_000)
        rule.onNode(hasText(title)).performClick()
        rule.waitUntilAtLeastOneExists(hasText("стр. 1 из 1"), 10_000)
        rule.waitUntilAtLeastOneExists(hasTestTag("page-canvas"), 10_000)
        rule.waitUntil(10_000) { rule.runOnUiThread { InkTiles.live().any { it !in before } } }
        return rule.runOnUiThread { InkTiles.live().first { it !in before }.layer }
    }

    /** The whole screen (the popup is a second window) into /sdcard/shots-task5, for the person reviewing the task. */
    private fun shot(name: String) {
        rule.waitForIdle()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun sh(cmd: String) = ui.executeShellCommand(cmd).use { fd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).readBytes() }
        sh("mkdir -p /sdcard/shots-task5")
        sh("screencap -p /sdcard/shots-task5/$name.png")
    }

    @Test
    fun eraserPopupChoosesModeAndSize() {
        val title = "Eraser popup $stamp"
        runBlocking { repo.createNotebook(title, PageTemplate.LINED, "auto") }
        openNotebook(title)
        rule.onNodeWithTag("tool-ERASER").performClick()
        rule.onNodeWithTag("tool-ERASER").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("eraser-options"), 5_000)
        rule.onNodeWithTag("eraser-PARTIAL").assertIsSelected()
        rule.onNodeWithTag("eraser-size-M").assertIsSelected()
        shot("eraser-popup-default")
        rule.onNodeWithTag("eraser-WHOLE").performClick()
        rule.onNodeWithTag("eraser-size-L").performClick()
        rule.waitForIdle()
        shot("eraser-popup-whole-large")
        assertEquals(EraserSettings(EraserMode.WHOLE, EraserSize.L), settings.eraser(InkSection.NOTEBOOK))

        rule.activityRule.scenario.recreate()
        rule.waitUntilAtLeastOneExists(hasTestTag("page-canvas"), 15_000)
        assertEquals(EraserSettings(EraserMode.WHOLE, EraserSize.L), settings.eraser(InkSection.NOTEBOOK))
        // the eraser is still the selected tool: one tap opens the popup with the choice in it
        rule.onNodeWithTag("tool-ERASER").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("eraser-options"), 5_000)
        rule.onNodeWithTag("eraser-WHOLE").assertIsSelected()
        rule.onNodeWithTag("eraser-size-L").assertIsSelected()
    }

    private fun strokesNow(layer: InkLayer) = rule.runOnUiThread { layer.strokes.toList() }

    /** Opens the eraser popup like a user (two taps on the tool) and picks [mode] and [size]. */
    private fun chooseEraser(mode: EraserMode, size: EraserSize) {
        rule.onNodeWithTag("tool-ERASER").performClick()
        rule.onNodeWithTag("tool-ERASER").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("eraser-options"), 5_000)
        rule.onNodeWithTag("eraser-${mode.name}").performClick()
        rule.onNodeWithTag("eraser-size-${size.name}").performClick()
        // back to the pen to draw
        rule.onNodeWithTag("tool-PEN").performClick()
        rule.waitForIdle()
    }

    private fun drawHorizontalLine(layer: InkLayer) {
        rule.onNodeWithTag("page-canvas").performTouchInput { swipe(Offset(width * 0.2f, height * 0.5f), Offset(width * 0.8f, height * 0.5f), 400) }
        rule.waitUntil(5_000) { strokesNow(layer).size == 1 }
    }

    private fun dragAcrossMiddle() {
        rule.onNodeWithTag("tool-ERASER").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("page-canvas").performTouchInput { swipe(Offset(width * 0.5f, height * 0.42f), Offset(width * 0.5f, height * 0.58f), 300) }
        rule.waitForIdle()
    }

    @Test
    fun partialEraseSplitsStroke() {
        val nb = runBlocking { repo.createNotebook("Eraser partial $stamp", PageTemplate.BLANK, "auto") }
        val layer = openNotebook("Eraser partial $stamp")
        chooseEraser(EraserMode.PARTIAL, EraserSize.S)
        drawHorizontalLine(layer)
        dragAcrossMiddle()
        val pieces = strokesNow(layer)
        assertEquals("the line is cut into two", 2, pieces.size)
        assertTrue("a gap between the pieces", pieces[1].points.first().x > pieces[0].points.last().x)
        // one drag, one undo step
        rule.onNodeWithTag("undo").performClick()
        rule.waitForIdle()
        assertEquals(1, strokesNow(layer).size)
        rule.onNodeWithTag("redo").performClick()
        rule.waitForIdle()
        assertEquals(2, strokesNow(layer).size)
        // the cut line is what gets saved
        val page = runBlocking { repo.pages(nb.id).first() }
        rule.waitUntil(10_000) { runBlocking { repo.loadInk(page.id) }.size == 2 }
    }

    @Test
    fun wholeEraseRemovesTheStroke() {
        runBlocking { repo.createNotebook("Eraser whole $stamp", PageTemplate.BLANK, "auto") }
        val layer = openNotebook("Eraser whole $stamp")
        chooseEraser(EraserMode.WHOLE, EraserSize.S)
        drawHorizontalLine(layer)
        dragAcrossMiddle()
        assertEquals(0, strokesNow(layer).size)
    }
}
