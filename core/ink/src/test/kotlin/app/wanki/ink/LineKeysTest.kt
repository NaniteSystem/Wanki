package app.wanki.ink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LineKeysTest {
    @Test
    fun sameKeysInAnotherOrderGiveAnEqualKey() {
        assertEquals(LineKeys.key(listOf(5L, 9L, 1L)), LineKeys.key(listOf(1L, 5L, 9L)))
    }

    @Test
    fun oneChangedKeyChangesTheKey() {
        assertNotEquals(LineKeys.key(listOf(1L, 5L, 9L)), LineKeys.key(listOf(1L, 5L, 10L)))
    }

    @Test
    fun duplicatesCount() {
        assertNotEquals(LineKeys.key(listOf(4L, 4L)), LineKeys.key(listOf(4L)))
        assertNotEquals(LineKeys.key(listOf(4L, 4L, 7L)), LineKeys.key(listOf(4L, 7L, 7L)))
    }
}
