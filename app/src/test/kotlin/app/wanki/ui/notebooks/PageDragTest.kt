package app.wanki.ui.notebooks

import kotlin.test.Test
import kotlin.test.assertEquals

class PageDragTest {
    @Test
    fun liftedAtANewPlaceIsStored() {
        val drag = PageDrag(0)
        drag.lift()
        assertEquals(PageDragEnd.Move(0, 2), drag.end(2))
    }

    @Test
    fun liftedWhereItStartedStoresNothing() {
        val drag = PageDrag(1)
        drag.lift()
        assertEquals(PageDragEnd.Stay, drag.end(1))
        assertEquals(PageDragEnd.Stay, drag.end(-1))
    }

    @Test
    fun cancelledGestureRestoresTheStoredOrder() {
        // ACTION_CANCEL: the handle stops the drag but no finger lifted, wherever the page was carried
        assertEquals(PageDragEnd.Restore, PageDrag(0).end(2))
        assertEquals(PageDragEnd.Restore, PageDrag(0).end(0))
    }
}
