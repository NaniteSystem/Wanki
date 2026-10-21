package app.wanki.input

import app.wanki.ink.InkRect
import kotlin.test.Test
import kotlin.test.assertEquals

class ViewportRestoreTest {
    private val page = InkRect(0f, 0f, 1000f, 1400f)

    @Test
    fun storedViewReplacesFitWidthOnFirstLayout() {
        val v = Viewport(minScale = 0.3f, bounds = page)
        v.restore(2f, -300f, -500f)
        v.onScreenSize(1000f, 1600f)
        assertEquals(2f, v.scale)
        assertEquals(-300f, v.offsetX)
        assertEquals(-500f, v.offsetY)
    }

    @Test
    fun withoutStoredViewFirstLayoutFitsWidth() {
        val v = Viewport(minScale = 0.3f, bounds = page)
        v.onScreenSize(500f, 800f)
        assertEquals(0.5f, v.scale)
    }

    @Test
    fun restoreIsClampedLikeTransform() {
        val v = Viewport(minScale = 0.3f, maxScale = 8f, bounds = page)
        v.onScreenSize(1000f, 1600f)
        v.restore(50f, 0f, 0f)
        assertEquals(8f, v.scale)
        // a page dragged far off screen is pulled back so a third of the screen stays covered
        v.restore(1f, 5000f, 0f)
        assertEquals(1000f * (1 - 0.33f), v.offsetX, 0.01f)
    }

    @Test
    fun laterLayoutsKeepTheView() {
        val v = Viewport(minScale = 0.3f, bounds = page)
        v.restore(1.5f, -100f, -200f)
        v.onScreenSize(1000f, 1600f)
        v.onScreenSize(1600f, 1000f)
        assertEquals(1.5f, v.scale)
    }
}
