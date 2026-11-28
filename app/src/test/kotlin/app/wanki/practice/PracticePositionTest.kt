package app.wanki.practice

import kotlin.test.Test
import kotlin.test.assertEquals

class PracticePositionTest {
    @Test
    fun positionInsideTheQueueStays() {
        assertEquals(2 to 1, practicePosition(2, 1, size = 5))
    }

    @Test
    fun positionPastARebuiltQueueGoesToItsLastCharacter() {
        // restored after process death into a queue that is now shorter
        assertEquals(2 to 0, practicePosition(7, 2, size = 3))
        assertEquals(0 to 0, practicePosition(-1, 1, size = 3))
        assertEquals(0 to 0, practicePosition(4, 1, size = 0))
    }

    @Test
    fun copybookUsesTheBaseLanguageOfTheText() {
        assertEquals("ja", copybookLanguage("ja"))
        assertEquals("ja", copybookLanguage("ja-JP"))
        assertEquals("zh", copybookLanguage("zh_Hant"))
        assertEquals("ja", copybookLanguage("JA"))
    }
}
