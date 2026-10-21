package app.wanki.media

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class VoiceRecorderTest {
    @Test fun fileNames() {
        assertEquals("rec-a.m4a", VoiceRecorder.fileName(listOf("a")))
        assertEquals("rec-a-c.m4a", VoiceRecorder.fileName(listOf("a", "b", "c")))
    }

    @Test fun fileNameOfOnePieceTwiceIsSingle() {
        assertEquals("rec-a.m4a", VoiceRecorder.fileName(listOf("a", "a")))
    }

    @Test fun fileNameStaysInsideTheMediaFolder() {
        val name = VoiceRecorder.fileName(listOf("../x/y"))
        assertEquals(MediaStore.safeName(name), name)
    }

    @Test fun shortRecordingsAreDiscarded() {
        assertFalse(VoiceRecorder.usable(0))
        assertFalse(VoiceRecorder.usable(299))
        assertTrue(VoiceRecorder.usable(300))
        assertTrue(VoiceRecorder.usable(5_000))
    }
}
