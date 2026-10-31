package app.wanki.ui.common

import app.wanki.data.PageSize
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

    @Test
    fun endlessGrows() {
        fun h(bottom: Float) = PageGeometry.size(false, PageSize.ENDLESS, bottom).height
        assertEquals(1414f, h(0f))
        assertEquals(1414f, h(700f))
        assertEquals(1414f, h(707f))
        // half a page (707) always stays below the lowest ink: 1000 + 707 needs three steps
        assertEquals(2121f, h(1000f))
        assertEquals(2121f, h(1300f))
        assertEquals(2121f, h(1414f))
        assertEquals(2828f, h(1415f))
        assertEquals(1000f, PageGeometry.size(false, PageSize.ENDLESS, 5000f).width)
    }

    @Test
    fun endlessIgnoresLandscape() {
        assertEquals(PageGeometry.size(false, PageSize.ENDLESS, 1300f), PageGeometry.size(true, PageSize.ENDLESS, 1300f))
        val b = PageGeometry.bounds(true, PageSize.ENDLESS, 1300f)
        assertEquals(1000f, b.width)
        assertEquals(2121f, b.height)
    }

    @Test
    fun fixedSizesAreUnchanged() {
        for (size in listOf(PageSize.A4, PageSize.A5)) {
            assertEquals(PageGeometry.size(false), PageGeometry.size(false, size, 5000f))
            assertEquals(PageGeometry.size(true), PageGeometry.size(true, size, 5000f))
            assertEquals(PageGeometry.BOUNDS, PageGeometry.bounds(false, size, 5000f))
        }
    }
}
