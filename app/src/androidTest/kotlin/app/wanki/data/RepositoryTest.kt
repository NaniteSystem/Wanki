package app.wanki.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.wanki.srs.Rating
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    private lateinit var db: WankiDatabase
    private lateinit var repo: Repository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, WankiDatabase::class.java).build()
        repo = Repository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun fill(deckId: String, n: Int, prefix: String = "q") =
        repo.addCards(deckId, (1..n).map { Repository.NewCard("$prefix$it", "a$it", AnswerMode.CLASSIC) })

    @Test
    fun newCardLimitsApplyThroughSubdecks() = runBlocking {
        val parent = repo.createDeck("P", "ja", AnswerMode.CLASSIC).let { it.copy(newPerDay = 5).also { d -> repo.updateDeck(d) } }
        val child = repo.createDeck("C", "ja", AnswerMode.CLASSIC, parent.id)
        fill(parent.id, 2)
        fill(child.id, 8)
        assertEquals(5, repo.reviewQueue(parent.id).size)
        assertEquals(8, repo.reviewQueue(child.id).size)
        // the parent's own cards come first, then the child's, in the order they were added
        assertEquals(listOf("q1", "q2", "q1", "q2", "q3"), repo.reviewQueue(parent.id).map { it.note.field(0) })
    }

    @Test
    fun answeringNewCardsUsesUpTheDailyLimit() = runBlocking {
        val deck = repo.createDeck("D", "ja", AnswerMode.CLASSIC).copy(newPerDay = 2).also { repo.updateDeck(it) }
        fill(deck.id, 5)
        val scheduler = repo.scheduler(0.9)
        repo.reviewQueue(deck.id).take(2).forEach {
            repo.answer(it.card, Rating.GOOD, scheduler, AnswerMode.CLASSIC, null, null, 1000, false, null)
        }
        val row = repo.deckTree().row(deck.id)!!
        assertEquals(0, row.newCount)
        // the two answered cards are now learning and due within the learn-ahead window
        assertEquals(2, row.learnCount)
        assertTrue(repo.reviewQueue(deck.id).none { it.card.isNew })
    }

    @Test
    fun suspendedAndBuriedCardsAreNotStudied() = runBlocking {
        val deck = repo.createDeck("D", "ja", AnswerMode.CLASSIC)
        fill(deck.id, 3)
        val ids = repo.reviewQueue(deck.id).map { it.card.id }
        repo.setSuspended(listOf(ids[0]), true)
        repo.buryUntilTomorrow(listOf(ids[1]))
        assertEquals(listOf(ids[2]), repo.reviewQueue(deck.id).map { it.card.id })
        assertEquals(3, repo.deckTree().row(deck.id)!!.total)
        repo.setSuspended(listOf(ids[0]), false)
        assertEquals(2, repo.reviewQueue(deck.id).size)
    }

    @Test
    fun handwritingScoresAreStoredPerCharacterAndUndone() = runBlocking {
        val deck = repo.createDeck("D", "ja", AnswerMode.HANDWRITING)
        repo.addCard(deck.id, "Япония", "日本", AnswerMode.HANDWRITING)
        val card = repo.reviewQueue(deck.id).single().card
        val answered = repo.answer(
            card, Rating.GOOD, repo.scheduler(0.9), AnswerMode.HANDWRITING, 90.0, 65.0, 4000, false, null,
            language = "ja",
            charScores = listOf(Repository.CharScore("日", 92.0, 60.0), Repository.CharScore("本", 90.0, 70.0)),
        )
        assertEquals(listOf("日", "本"), repo.charQuality().map { it.glyph })
        assertEquals(STATE_NEW, db.reviews().forCard(card.id).single().cardState)
        repo.undo(answered)
        assertTrue(repo.charQuality().isEmpty())
        assertTrue(repo.card(card.id)!!.card.isNew)
    }

    @Test
    fun deckPathsAreReusedAndDeletingRemovesTheSubtree() = runBlocking {
        val a = repo.deckByPath(listOf("Japanese", "Kanji", "N5"), "ja", AnswerMode.HANDWRITING)
        val b = repo.deckByPath(listOf("Japanese", "Kanji", "N5"), "ja", AnswerMode.HANDWRITING)
        assertEquals(a.id, b.id)
        val tree = repo.deckTree()
        assertEquals("Japanese::Kanji::N5", tree.path(a.id))
        fill(a.id, 3)
        val root = tree.roots.single()
        repo.deleteDeck(root.id)
        assertTrue(repo.deckTree().byId.isEmpty())
        for (table in listOf("cards", "notes")) {
            db.query("SELECT COUNT(*) FROM $table WHERE deleted = 0", null).use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test
    fun searchFindsByFieldTagAndCase() = runBlocking {
        val deck = repo.createDeck("D", "ru", AnswerMode.CLASSIC)
        repo.addCard(deck.id, "Кошка", "cat", AnswerMode.CLASSIC, tags = "animals")
        repo.addCard(deck.id, "собака", "dog", AnswerMode.CLASSIC)
        assertEquals(listOf("cat"), repo.observeCards(CardQuery(text = "кошка")).first().map { it.note.field(1) })
        assertEquals(listOf("cat"), repo.observeCards(CardQuery(text = "ANIMALS")).first().map { it.note.field(1) })
        assertEquals(2, repo.observeCardCount(CardQuery(deckIds = listOf(deck.id))).first())
        assertEquals(0, repo.observeCardCount(CardQuery(text = "50%")).first())
    }

    @Test
    fun editingKeepsScheduleAndMovesCards() = runBlocking {
        val d1 = repo.createDeck("D1", "ja", AnswerMode.CLASSIC)
        val d2 = repo.createDeck("D2", "ja", AnswerMode.CLASSIC)
        repo.addCard(d1.id, "q", "a", AnswerMode.CLASSIC)
        val c = repo.reviewQueue(d1.id).single()
        repo.saveCard(c, "q2", "a2", "t1 t2", d2.id, AnswerMode.HANDWRITING, 0)
        val e = repo.card(c.card.id)!!
        assertEquals("q2", e.note.field(0))
        assertEquals("a2", e.note.field(1))
        assertEquals("t1 t2", e.note.tags)
        assertEquals(d2.id, e.card.deckId)
        assertEquals(AnswerMode.HANDWRITING.name, e.card.answerMode)
        assertEquals(0, e.card.writeFieldOrd)
        assertEquals(c.card.position, e.card.position)
    }
}
