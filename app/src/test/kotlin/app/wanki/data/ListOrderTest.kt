package app.wanki.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ListOrderTest {
    private data class Item(val title: String, val pinned: Boolean = false, val edited: Long = 0, val created: Long = 0)

    private fun sort(items: List<Item>, by: ListSort) =
        ListOrder.sort(items, by, { it.pinned }, { it.title }, { it.edited }, { it.created }).map { it.title }

    @Test
    fun pinnedComeFirstInEverySort() {
        val items = listOf(
            Item("б", edited = 3, created = 3), Item("я", pinned = true, edited = 1, created = 1),
            Item("а", edited = 2, created = 2), Item("ю", pinned = true, edited = 5, created = 0),
        )
        assertEquals(listOf("ю", "я", "б", "а"), sort(items, ListSort.EDITED))
        assertEquals(listOf("ю", "я", "а", "б"), sort(items, ListSort.TITLE))
        assertEquals(listOf("я", "ю", "б", "а"), sort(items, ListSort.CREATED))
    }

    @Test
    fun titleUsesRussianCollationIgnoringCase() {
        val items = listOf(Item("жук"), Item("ёж"), Item("Еж"), Item("арбуз"))
        assertEquals(listOf("арбуз", "Еж", "ёж", "жук"), sort(items, ListSort.TITLE))
    }

    @Test
    fun editedAndCreatedAreNewestFirst() {
        val items = listOf(Item("a", edited = 1, created = 9), Item("b", edited = 9, created = 1), Item("c", edited = 5, created = 5))
        assertEquals(listOf("b", "c", "a"), sort(items, ListSort.EDITED))
        assertEquals(listOf("a", "c", "b"), sort(items, ListSort.CREATED))
    }

    @Test
    fun tiesKeepTheirOrder() {
        val items = listOf(Item("x", edited = 1), Item("y", edited = 1), Item("z", edited = 1))
        assertEquals(listOf("x", "y", "z"), sort(items, ListSort.EDITED))
        assertEquals(listOf("x", "y", "z"), sort(items, ListSort.CREATED))
        assertEquals(listOf("A", "a"), sort(listOf(Item("A"), Item("a")), ListSort.TITLE))
    }
}
