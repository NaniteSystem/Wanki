package app.wanki.texts

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import app.wanki.data.Settings
import app.wanki.data.TextRepository
import app.wanki.data.WankiDatabase
import app.wanki.ui.texts.TextReadingsScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The readings screen of a Japanese text, over a repository with a fake analyser (one token per character). */
@OptIn(ExperimentalTestApi::class)
class TextReadingsTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: WankiDatabase
    private lateinit var texts: TextRepository

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, WankiDatabase::class.java).build()
        texts = TextRepository(db, Settings(context)) { Morphology { s -> s.indices.map { MorphToken(it, it + 1, null) } } }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun editReading() {
        val t = runBlocking { texts.createText("T", "ja", TextKind.POEM, TextSchedule.FSRS, 2, "日本")!! }
        rule.setContent { TextReadingsScreen(t.id, onBack = {}, texts = texts) }
        rule.waitUntilAtLeastOneExists(hasTestTag("reading-日"), 10_000)
        rule.onNode(hasTestTag("reading-日")).performClick()
        rule.onNode(hasTestTag("reading-field")).performTextClearance()
        rule.onNode(hasTestTag("reading-field")).performTextInput("にち")
        rule.onNode(hasTestTag("forms-field")).performTextInput("ニチ, 日, ニチ")
        rule.onNode(hasTestTag("reading-save")).performClick()

        rule.waitUntil(10_000) { runBlocking { texts.text(t.id)!!.readings.isNotEmpty() } }
        val stored = PieceWords.decodeReadings(runBlocking { texts.text(t.id)!!.readings })
        assertEquals(mapOf("日" to ReadingOverride("にち", listOf("ニチ"))), stored)
        rule.waitUntilAtLeastOneExists(androidx.compose.ui.test.hasText("にち · изменено"), 10_000)
        assertTrue(runBlocking { texts.words(texts.pieces(t.id)) }.values.single().first().reading == "にち")

        // reset removes the override again
        rule.onNode(hasTestTag("reading-日")).performClick()
        rule.onNode(hasTestTag("reading-reset")).performClick()
        rule.waitUntil(10_000) { runBlocking { texts.text(t.id)!!.readings.isEmpty() } }
    }
}
