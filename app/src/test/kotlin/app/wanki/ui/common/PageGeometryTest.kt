package app.wanki.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals

class PageGeometryTest {
    @Test
    fun portraitAndLandscapeSizes() {
        val p = PageGeometry.size(landscape = false)
        assertEquals(1000f, p.width)
        assertEquals(1414f, p.height)
        val l = PageGeometry.size(landscape = true)
        assertEquals(1414f, l.width)
        assertEquals(1000f, l.height)
    }

    @Test
    fun boundsMatchSize() {
        val b = PageGeometry.bounds(landscape = true)
        assertEquals(1414f, b.width)
        assertEquals(1000f, b.height)
        assertEquals(PageGeometry.BOUNDS, PageGeometry.bounds(landscape = false))
    }
}
