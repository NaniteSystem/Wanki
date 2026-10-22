package app.wanki.texts

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KuromojiOnDeviceTest {
    @Test
    fun loadsDictionary() {
        val text = "日本語を学びます"
        val tokens = KuromojiMorphology().tokens(text)
        val t = tokens.first { text.substring(it.start, it.end) == "日本語" }
        assertEquals("ニホンゴ", t.reading)
        assertTrue(tokens.all { it.start >= 0 && it.end <= text.length && it.start < it.end })
    }
}
