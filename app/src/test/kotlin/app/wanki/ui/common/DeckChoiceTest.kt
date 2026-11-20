package app.wanki.ui.common

import app.wanki.data.DeckEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeckChoiceTest {
    private val ja = DeckEntity(id = "ja", name = "Японский", language = "ja")
    private val en = DeckEntity(id = "en", name = "English", language = "en")
    private val decks = listOf(ja, en)

    @Test fun lastForSourceWins() = assertEquals("en", chooseDeck("en", decks, "ja", "ja"))
    @Test fun sameLanguageNext() = assertEquals("en", chooseDeck(null, decks, "en", "ja"))
    @Test fun lastUsedThen() = assertEquals("ja", chooseDeck(null, decks, "ru", "ja"))
    @Test fun nothing() = assertNull(chooseDeck(null, decks, "ru", null))
    @Test fun deletedDecksSkipped() {
        assertEquals("ja", chooseDeck("gone", decks, "ja", null))
        assertNull(chooseDeck("gone", decks, "ru", "gone too"))
    }
}
