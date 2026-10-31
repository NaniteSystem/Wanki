package app.wanki.export

import app.wanki.data.PageSize
import app.wanki.ink.InkRect
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfGeometryTest {
    @Test
    fun pagePointsA4A5AndLandscape() {
        assertEquals(595 to 842, PdfGeometry.pagePoints(PageSize.A4, landscape = false))
        assertEquals(842 to 595, PdfGeometry.pagePoints(PageSize.A4, landscape = true))
        assertEquals(420 to 595, PdfGeometry.pagePoints(PageSize.A5, landscape = false))
        assertEquals(595 to 420, PdfGeometry.pagePoints(PageSize.A5, landscape = true))
        // an endless page is cut into portrait A4 sheets
        assertEquals(595 to 842, PdfGeometry.pagePoints(PageSize.ENDLESS, landscape = false))
        assertEquals(595 to 842, PdfGeometry.pagePoints(PageSize.ENDLESS, landscape = true))
    }

    @Test
    fun sheetsAreSlicesOfOnePageHeight() {
        val s = PdfGeometry.sheets(3000f)
        assertEquals(3, s.size)
        assertEquals(0f, s[0].start, 0f)
        assertEquals(1414f, s[0].endInclusive, 0f)
        assertEquals(1414f, s[1].start, 0f)
        assertEquals(2828f, s[2].start, 0f)
        assertEquals(1, PdfGeometry.sheets(0f).size)
        assertEquals(1, PdfGeometry.sheets(1414f).size)
        assertEquals(2, PdfGeometry.sheets(1415f).size)
    }

    @Test
    fun fitLetterboxesCentred() {
        // a 4:3 picture on a portrait page: full width, centred vertically
        val r = PdfGeometry.fit(400f, 300f, InkRect(0f, 0f, 1000f, 1414f))
        assertEquals(0f, r.left, 0.01f)
        assertEquals(1000f, r.right, 0.01f)
        assertEquals(750f, r.height, 0.01f)
        assertEquals(707f, r.centerY, 0.01f)
        // a tall picture: full height, centred horizontally
        val t = PdfGeometry.fit(100f, 1000f, InkRect(0f, 0f, 1000f, 1414f))
        assertEquals(1414f, t.height, 0.01f)
        assertEquals(500f, t.centerX, 0.01f)
    }
}
