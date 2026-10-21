package app.wanki.input

import kotlin.test.Test
import kotlin.test.assertEquals

class ToolSelectionCodecTest {
    @Test fun roundTripsMarker() {
        val t = ToolSelection(ToolKind.MARKER, 0xFF8E44AD.toInt(), 7f)
        assertEquals(t, ToolSelection.decode(t.encode()))
    }

    @Test fun badInputGivesDefault() {
        assertEquals(ToolSelection(), ToolSelection.decode(null))
        assertEquals(ToolSelection(), ToolSelection.decode(""))
        assertEquals(ToolSelection(), ToolSelection.decode("BOGUS:1:2"))
        assertEquals(ToolSelection(), ToolSelection.decode("PEN:x:2"))
    }

    @Test fun eraserAndLassoKeepColourAndSize() {
        for (k in listOf(ToolKind.ERASER, ToolKind.LASSO)) {
            val t = ToolSelection(k, 0xFFD23C3C.toInt(), 2.5f)
            assertEquals(t, ToolSelection.decode(t.encode()))
        }
    }

    @Test fun paletteAndSizes() {
        assertEquals(8, ToolSelection.PALETTE.size)
        assertEquals(listOf(1.5f, 2.5f, 4f, 7f, 11f), ToolSelection.SIZES)
    }
}
