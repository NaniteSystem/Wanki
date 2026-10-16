package app.wanki.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeckTreeTest {
    private fun deck(id: String, parent: String? = null, newPerDay: Int = 20, reviewsPerDay: Int = 200, collapsed: Boolean = false) =
        DeckEntity(id = id, name = id, parentId = parent, newPerDay = newPerDay, reviewsPerDay = reviewsPerDay, collapsed = collapsed)

    private fun counts(id: String, new: Int = 0, learn: Int = 0, review: Int = 0) =
        DeckCardCounts(id, new, learn, review, new + learn + review)

    @Test
    fun subtreeAndPath() {
        val t = DeckTree(listOf(deck("A"), deck("B", "A"), deck("C", "B"), deck("D")))
        assertEquals(listOf("A", "B", "C"), t.subtree("A"))
        assertEquals("A::B::C", t.path("C"))
        assertEquals(listOf("A", "D"), t.roots.map { it.id })
    }

    @Test
    fun ownLimitCapsNewCards() {
        val t = DeckTree(listOf(deck("A", newPerDay = 10)), listOf(counts("A", new = 50)))
        assertEquals(mapOf("A" to 10), t.newQuota("A"))
        assertEquals(10, t.row("A")!!.newCount)
    }

    @Test
    fun parentLimitCapsChildrenAndChildLimitStillApplies() {
        val t = DeckTree(
            listOf(deck("P", newPerDay = 15), deck("C1", "P", newPerDay = 5), deck("C2", "P", newPerDay = 50)),
            listOf(counts("P", new = 3), counts("C1", new = 40), counts("C2", new = 40)),
        )
        // own 3, then C1 up to its limit of 5, then C2 gets the rest of the parent's 15
        assertEquals(mapOf("P" to 3, "C1" to 5, "C2" to 7), t.newQuota("P"))
        // studied on its own, a child only obeys its own limit
        assertEquals(mapOf("C2" to 40), t.newQuota("C2"))
    }

    @Test
    fun studiedTodayCountsAgainstTheLimitOfEveryAncestor() {
        val t = DeckTree(
            listOf(deck("P", newPerDay = 10), deck("C", "P", newPerDay = 10)),
            listOf(counts("C", new = 40)),
            listOf(DeckTodayCounts("C", newDone = 8, reviewDone = 0)),
        )
        assertEquals(mapOf("C" to 2), t.newQuota("P"))
        assertEquals(mapOf("C" to 2), t.newQuota("C"))
    }

    @Test
    fun reviewLimitAndUnlimitedLearning() {
        val t = DeckTree(
            listOf(deck("A", reviewsPerDay = 30)),
            listOf(counts("A", learn = 12, review = 100)),
            listOf(DeckTodayCounts("A", newDone = 0, reviewDone = 25)),
        )
        val row = t.row("A")!!
        assertEquals(5, row.reviewCount)
        assertEquals(12, row.learnCount)
    }

    @Test
    fun collapsedDeckHidesChildrenButCountsThem() {
        val t = DeckTree(
            listOf(deck("A", collapsed = true), deck("B", "A")),
            listOf(counts("B", new = 4, review = 2)),
        )
        val rows = t.rows()
        assertEquals(listOf("A"), rows.map { it.deck.id })
        assertTrue(rows[0].hasChildren)
        assertEquals(4, rows[0].newCount)
        assertEquals(2, rows[0].reviewCount)
    }

    @Test
    fun noCyclesWhenReparenting() {
        val t = DeckTree(listOf(deck("A"), deck("B", "A"), deck("C", "B")))
        assertFalse(t.canBeParent("A", "C"))
        assertFalse(t.canBeParent("A", "A"))
        assertTrue(t.canBeParent("C", "A"))
        assertTrue(t.canBeParent("B", null))
    }

    @Test
    fun cycleInDataDoesNotHang() {
        // A → B → A: neither is a root, both stay reachable through subtree without looping
        val t = DeckTree(listOf(deck("A", "B"), deck("B", "A")), listOf(counts("A", new = 1)))
        assertEquals(listOf("A", "B"), t.subtree("A"))
        assertEquals(1, t.row("A")!!.newCount)
    }
}
