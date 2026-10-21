package app.wanki.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals

class HeaderActionsTest {
    private fun a(label: String) = HeaderAction(icon = 0, label = label, onClick = {})
    private fun labels(l: List<HeaderAction>) = l.map { it.label }

    @Test
    fun overflowingPrimaryGoesToFrontOfMenu() {
        val (visible, menu) = splitActions(listOf(a("1"), a("2"), a("3")), listOf(a("m")), max = 2)
        assertEquals(listOf("1", "2"), labels(visible))
        assertEquals(listOf("3", "m"), labels(menu))
    }

    @Test
    fun singlePrimaryStaysAndMenuUnchanged() {
        val (visible, menu) = splitActions(listOf(a("1")), listOf(a("m")))
        assertEquals(listOf("1"), labels(visible))
        assertEquals(listOf("m"), labels(menu))
    }

    @Test
    fun zeroMaxPutsEverythingInMenu() {
        val (visible, menu) = splitActions(listOf(a("1"), a("2")), listOf(a("m")), max = 0)
        assertEquals(emptyList(), labels(visible))
        assertEquals(listOf("1", "2", "m"), labels(menu))
    }
}
