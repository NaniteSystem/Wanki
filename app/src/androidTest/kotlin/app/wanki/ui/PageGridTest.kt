package app.wanki.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.wanki.MainActivity
import app.wanki.data.PageTemplate
import app.wanki.graph
import app.wanki.ink.InkBrush
import app.wanki.ink.InkPoint
import app.wanki.ink.InkStroke
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class PageGridTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val repo get() = rule.activity.graph.repository
    private val stamp = System.nanoTime().toString()

    /** A notebook with [count] pages; the first one carries a stroke. Returns its id. */
    private fun notebook(name: String, count: Int): String {
        val nb = runBlocking { repo.createNotebook("$name $stamp", PageTemplate.LINED, "auto") }
        runBlocking {
            repeat(count - 1) { repo.addPage(nb) }
            val first = repo.pages(nb.id).first()
            repo.saveInk(first, listOf(InkStroke((0..20).map { InkPoint(100f + it * 30f, 300f + it * 20f, it * 10L) }, brush = InkBrush(size = 8f, color = 0xFF000000.toInt()))))
        }
        return nb.id
    }

    private fun openGrid(name: String, pages: Int) {
        rule.waitUntilAtLeastOneExists(hasTestTag("tab-Тетради"), 15_000)
        rule.onNodeWithTag("tab-Тетради").performClick()
        rule.waitUntilAtLeastOneExists(hasText("$name $stamp"), 10_000)
        rule.onNode(hasText("$name $stamp")).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("pages-grid"), 10_000)
        rule.onNodeWithTag("pages-grid").performClick()
        // the grid is filled from the pages flow: wait for the last cell, not just the first
        rule.waitUntilAtLeastOneExists(hasTestTag("page-thumb-${pages - 1}"), 10_000)
    }

    private fun menuOn(index: Int, item: String) {
        rule.onNodeWithTag("page-thumb-$index").performTouchInput { longClick() }
        rule.waitUntilAtLeastOneExists(hasTestTag("item-$item"), 5_000)
    }

    private fun ids(nb: String) = runBlocking { repo.pages(nb).map { it.id } }

    @Test
    fun openPageFromGrid() {
        notebook("Open", 3)
        openGrid("Open", 3)
        rule.onNodeWithTag("page-thumb-2").performClick()
        rule.waitUntilAtLeastOneExists(hasText("стр. 3 из 3"), 10_000)
    }

    @Test
    fun insertBeforeAndAfter() {
        val nb = notebook("Ins", 2)
        val (a, b) = ids(nb)
        openGrid("Ins", 2)
        menuOn(0, "insert-before")
        rule.onNodeWithTag("item-insert-before").performClick()
        rule.waitUntil(10_000) { ids(nb).size == 3 }
        val afterBefore = ids(nb)
        assertEquals(listOf(a, b), afterBefore.drop(1))
        menuOn(1, "insert-after")
        rule.onNodeWithTag("item-insert-after").performClick()
        rule.waitUntil(10_000) { ids(nb).size == 4 }
        val now = ids(nb)
        assertEquals(afterBefore[0], now[0])
        assertEquals(a, now[1])
        assertNotEquals(b, now[2])
        assertEquals(b, now[3])
    }

    @Test
    fun duplicatePage() {
        val nb = notebook("Dup", 2)
        val (a, _) = ids(nb)
        openGrid("Dup", 2)
        menuOn(0, "duplicate")
        rule.onNodeWithTag("item-duplicate").performClick()
        rule.waitUntil(10_000) { ids(nb).size == 3 }
        val copy = ids(nb)[1]
        assertNotEquals(a, copy)
        assertEquals(1, runBlocking { repo.loadInk(copy) }.size)
    }

    @Test
    fun deletePageWithUndo() {
        val nb = notebook("Del", 2)
        val (a, b) = ids(nb)
        openGrid("Del", 2)
        menuOn(0, "delete")
        rule.onNodeWithTag("item-delete").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Удалено"), 10_000)
        assertEquals(listOf(b), ids(nb))
        rule.onNodeWithText("Отменить").performClick()
        rule.waitUntil(10_000) { ids(nb).size == 2 }
        assertEquals(listOf(a, b), ids(nb))
    }

    @Test
    fun lastPageCannotBeDeleted() {
        val nb = notebook("Last", 1)
        openGrid("Last", 1)
        menuOn(0, "delete")
        rule.onNodeWithTag("item-delete").assertIsNotEnabled()
        assertEquals(1, ids(nb).size)
    }

    @Test
    fun dragReorderPersists() {
        val nb = notebook("Drag", 3)
        val (a, b, c) = ids(nb)
        openGrid("Drag", 3)
        val target = rule.onNodeWithTag("page-thumb-2").fetchSemanticsNode().boundsInRoot.center
        // the item moves with the finger: carry its centre onto the centre of the target cell
        val start = rule.onNodeWithTag("page-thumb-0").fetchSemanticsNode().boundsInRoot.center
        rule.onNodeWithTag("page-drag-0").performTouchInput {
            down(center)
            moveBy(Offset(1f, 1f))
            val delta = target - start
            val steps = 12
            repeat(steps) { moveBy(delta / steps.toFloat()) }
            moveBy(Offset(2f, 2f))
            up()
        }
        rule.waitUntil(10_000) { ids(nb) == listOf(b, c, a) }
        rule.onNodeWithTag("back").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("pages-grid"), 10_000)
        rule.onNodeWithTag("pages-grid").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("page-thumb-2"), 10_000)
        assertEquals(listOf(b, c, a), ids(nb))
    }
}
