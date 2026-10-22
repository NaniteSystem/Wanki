package app.wanki.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.wanki.srs.FsrsScheduler
import app.wanki.srs.Rating
import app.wanki.srs.Review
import app.wanki.texts.MorphToken
import app.wanki.texts.Morphology
import app.wanki.texts.PieceWords
import app.wanki.texts.ReadingOverride
import app.wanki.texts.TextKind
import app.wanki.texts.TextSchedule
import app.wanki.texts.WordOutcome
import app.wanki.texts.WordStat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class TextRepositoryTest {
    private lateinit var context: Context
    private lateinit var db: WankiDatabase
    private lateinit var settings: Settings
    private lateinit var texts: TextRepository

    /** Noon, so no step of a test crosses midnight. */
    private val now = LocalDate.of(2026, 10, 19).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences(Settings.NAME, Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, WankiDatabase::class.java).build()
        settings = Settings(context)
        texts = TextRepository(db, settings) { CharMorphology }
    }

    @After
    fun tearDown() {
        db.close()
        context.getSharedPreferences(Settings.NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun createTextRejectsEmpty() = runBlocking {
        assertNull(texts.createText("x", "ru", TextKind.POEM, TextSchedule.FSRS, 4, " \n — "))
    }

    @Test
    fun saveTextCreatesANewTextInOneCall() = runBlocking {
        // the editor saves a new text this way: the text row and its pieces in one transaction
        val t = TextEntity(title = " ", language = "ar", kind = TextKind.PROSE.name, schedule = TextSchedule.HIFZ.name, portionSize = 0)
        texts.saveText(t, listOf(EditedPiece(null, "a", "n"), EditedPiece(null, "b", scheduleFrom = "missing")))
        val stored = texts.text(t.id)!!
        assertEquals(listOf("Текст", "ar", "PROSE", "HIFZ", 1), listOf(stored.title, stored.language, stored.kind, stored.schedule, stored.portionSize))
        val pieces = texts.pieces(t.id)
        assertEquals(listOf(Triple(0, "a", "n"), Triple(1, "b", "")), pieces.map { Triple(it.pieceIndex, it.content, it.note) })
        assertTrue(pieces.all { it.learnedAt == null && it.sabqiLeft == 0 })
        // portion size 0 was raised to 1
        assertEquals(listOf(1), texts.today(now).items.filterIsInstance<TodayItem.NewPortion>().map { it.pieceIds.size })
    }

    @Test
    fun contentRoundTripsVerbatim() = runBlocking {
        val src = "بِسْمِ ٱللَّهِ ٱلرَّحْمَٰنِ ٱلرَّحِيمِ\nٱلْحَمْدُ لِلَّهِ رَبِّ ٱلْعَٰلَمِينَ"
        val t = texts.createText("Фатиха", "ar", TextKind.POEM, TextSchedule.HIFZ, 3, src)!!
        assertEquals(src.lines(), texts.pieces(t.id).map { it.content })
    }

    @Test
    fun portionThenReviewByFsrs() = runBlocking {
        val t = texts.createText("Стих", "ru", TextKind.POEM, TextSchedule.FSRS, 2, "a\nb\nc")!!
        val today = texts.today(now)
        assertEquals(listOf(2), today.items.filterIsInstance<TodayItem.NewPortion>().map { it.pieceIds.size })
        val ids = today.items.first().pieceIds
        texts.completePortion(t.id, ids, 1000, now)
        assertTrue(texts.pieces(t.id).take(2).all { it.learnedAt == now && it.reps == 1 })
        assertTrue(texts.today(now).items.none { it is TodayItem.NewPortion }) // one portion per day
        assertEquals(1, texts.today(now + DAY).items.filterIsInstance<TodayItem.NewPortion>().size)
        assertEquals(ids, texts.today(now + 2 * DAY).items.filterIsInstance<TodayItem.Review>().single().pieceIds)
    }

    @Test
    fun hifzSabqiThenManzil() = runBlocking {
        settings.sabqiDays = 2
        val t = texts.createText("Сура", "ar", TextKind.POEM, TextSchedule.HIFZ, 1, "a\nb")!!
        val p = texts.pieces(t.id)[0].id
        texts.completePortion(t.id, listOf(p), 0, now)
        for (d in 1..2) {
            val s = texts.today(now + d * DAY).items.filterIsInstance<TodayItem.Sabqi>().single()
            texts.completeSabqi(t.id, s.pieceIds, emptySet(), 0, now + d * DAY)
        }
        val m = texts.today(now + 3 * DAY).items.filterIsInstance<TodayItem.Manzil>().single()
        assertEquals(listOf(p), m.pieceIds)
        texts.completeManzil(m.pieceIds, setOf(p), 0, now + 3 * DAY) // error → back to sabqi
        assertEquals(2, texts.pieces(t.id)[0].sabqiLeft)
        assertTrue(texts.today(now + 3 * DAY).items.none { it is TodayItem.Manzil })
    }

    @Test
    fun sabqiSubmittedTwiceTheSameDayCountsOnce() = runBlocking {
        settings.sabqiDays = 3
        val t = texts.createText("Сура", "ar", TextKind.POEM, TextSchedule.HIFZ, 2, "a\nb\nc")!!
        val (a, b) = texts.pieces(t.id)
        texts.completePortion(t.id, listOf(a.id, b.id), 0, now)
        texts.completeSabqi(t.id, listOf(a.id, b.id), setOf(a.id), 0, now + DAY)
        val after = texts.pieces(t.id).take(2)
        // a double tap on «Готово»: the error must not be logged twice (which would make a weak piece at once)
        texts.completeSabqi(t.id, listOf(a.id, b.id), setOf(a.id), 0, now + DAY + 1)
        assertEquals(after, texts.pieces(t.id).take(2))
        assertEquals(1, db.texts().recitations(t.id).count { it.part == RecitationPart.SABQI.name && it.pieceId == a.id })
        assertEquals(emptySet<String>(), texts.weakPieces(t.id))
        // the next day it counts again
        texts.completeSabqi(t.id, listOf(b.id), emptySet(), 0, now + 2 * DAY)
        assertEquals(2, db.texts().recitations(t.id).count { it.part == RecitationPart.SABQI.name && it.pieceId == b.id })
    }

    @Test
    fun manzilSubmittedTwiceTheSameDayCountsOnce() = runBlocking {
        settings.sabqiDays = 1
        settings.manzilCycleDays = 2
        val t = texts.createText("Сура", "ar", TextKind.POEM, TextSchedule.HIFZ, 1, "a\nb\nc\nd")!!
        val ids = texts.pieces(t.id).map { it.id }
        texts.markLearned(t.id, 3, now)
        val m = texts.today(now).items.filterIsInstance<TodayItem.Manzil>().single()
        texts.completeManzil(m.pieceIds, setOf(ids[0]), 0, now)
        val pieces = texts.pieces(t.id)
        val pointer = settings.manzilPointer
        texts.completeManzil(m.pieceIds, setOf(ids[0]), 0, now + 1) // double tap
        assertEquals(pieces, texts.pieces(t.id))
        assertEquals(pointer, settings.manzilPointer)
        assertEquals(1, db.texts().recitations(t.id).count { it.part == RecitationPart.MANZIL.name && it.pieceId == ids[0] })
        // the next day's chunk goes on from the pointer
        val next = texts.today(now + DAY).items.filterIsInstance<TodayItem.Manzil>().single()
        assertEquals(ids.drop(2), next.pieceIds)
    }

    @Test
    fun switchingTwiceKeepsHistory() = runBlocking {
        val t = texts.createText("Т", "ru", TextKind.POEM, TextSchedule.FSRS, 1, "a\nb")!!
        val p = texts.pieces(t.id)[0].id
        texts.completePortion(t.id, listOf(p), 0, now)
        texts.reviewPiece(p, Rating.GOOD, 0, 0, now + 3 * DAY)
        texts.switchSchedule(t.id, TextSchedule.HIFZ, now + 3 * DAY)
        texts.switchSchedule(t.id, TextSchedule.FSRS, now + 3 * DAY)
        val expected = FsrsScheduler(desiredRetention = settings.desiredRetention.toDouble())
            .replay(listOf(Review(Rating.GOOD, now), Review(Rating.GOOD, now + 3 * DAY)), texts.pieces(t.id)[0].seed)
        assertEquals(expected.due, texts.pieces(t.id)[0].due)
    }

    @Test
    fun hifzToFsrsReplaysOnlyRatedLogs() = runBlocking {
        val t = texts.createText("Сура", "ar", TextKind.POEM, TextSchedule.HIFZ, 1, "a\nb")!!
        val p = texts.pieces(t.id)[0]
        texts.completePortion(t.id, listOf(p.id), 0, now)
        texts.completeSabqi(t.id, listOf(p.id), setOf(p.id), 0, now + DAY)
        texts.logPractice(t.id, listOf(p.id), setOf(p.id), 0, 0, now + DAY + 1)
        texts.switchSchedule(t.id, TextSchedule.FSRS, now + 2 * DAY)
        val expected = FsrsScheduler(desiredRetention = settings.desiredRetention.toDouble())
            .replay(listOf(Review(Rating.GOOD, now), Review(Rating.AGAIN, now + DAY)), p.seed) // practice left out
        assertEquals(expected.due, texts.pieces(t.id)[0].due)
    }

    @Test
    fun fsrsToHifzUsesRecency() = runBlocking {
        val t = texts.createText("Т", "ru", TextKind.POEM, TextSchedule.FSRS, 1, "a\nb")!!
        val p = texts.pieces(t.id)[0].id
        texts.completePortion(t.id, listOf(p), 0, now)
        texts.switchSchedule(t.id, TextSchedule.HIFZ, now + 3 * DAY)
        assertEquals(4, texts.pieces(t.id)[0].sabqiLeft) // window 7, learned 3 days ago
    }

    @Test
    fun manzilPointerAdvances() = runBlocking {
        settings.sabqiDays = 1
        settings.manzilCycleDays = 2
        val t = texts.createText("Сура", "ar", TextKind.POEM, TextSchedule.HIFZ, 1, "a\nb\nc\nd")!!
        val ids = texts.pieces(t.id).map { it.id }
        texts.markLearned(t.id, 3, now)
        val m = texts.today(now).items.filterIsInstance<TodayItem.Manzil>().single()
        assertEquals(ids.take(2), m.pieceIds)
        texts.completeManzil(m.pieceIds, emptySet(), 0, now)
        assertEquals(ids.drop(2), texts.today(now + DAY).items.filterIsInstance<TodayItem.Manzil>().single().pieceIds)
    }

    @Test
    fun splitPieceKeepsHistoryAcrossSwitch() = runBlocking {
        val t = texts.createText("Т", "ru", TextKind.POEM, TextSchedule.FSRS, 2, "a\nb")!!
        val (a, b) = texts.pieces(t.id)
        texts.completePortion(t.id, listOf(a.id, b.id), 0, now)
        texts.reviewPiece(a.id, Rating.GOOD, 0, 0, now + 3 * DAY)
        texts.reviewPiece(b.id, Rating.HARD, 0, 0, now + 3 * DAY)
        suspend fun log(pieceId: String) =
            db.texts().recitations(t.id).filter { it.pieceId == pieceId }.map { Triple(it.part, it.rating, it.at) }
        val parentLog = log(b.id)
        texts.saveText(t, listOf(EditedPiece(a.id, "a"), EditedPiece(b.id, "b1"), EditedPiece(null, "b2", scheduleFrom = b.id)))
        assertEquals(parentLog, log(texts.pieces(t.id)[2].id)) // the split-off piece carries the parent's log
        texts.switchSchedule(t.id, TextSchedule.HIFZ, now + 4 * DAY)
        texts.switchSchedule(t.id, TextSchedule.FSRS, now + 4 * DAY)
        val (_, parent, split) = texts.pieces(t.id)
        assertEquals(parent.due, split.due)
    }

    @Test
    fun markLearnedHifzGoesStraightToManzil() = runBlocking {
        val t = texts.createText("Т", "ru", TextKind.POEM, TextSchedule.HIFZ, 1, "a\nb\nc")!!
        texts.markLearned(t.id, 1, now)
        assertEquals(listOf(0, 0), texts.pieces(t.id).take(2).map { it.sabqiLeft })
        // piece c is still new today
        assertEquals(1, texts.today(now).items.filterIsInstance<TodayItem.NewPortion>().single().pieceIds.size)
    }

    @Test
    fun joinAndSplitKeepSchedule() = runBlocking {
        val t = texts.createText("Т", "ru", TextKind.POEM, TextSchedule.FSRS, 2, "a\nb")!!
        val (a, b) = texts.pieces(t.id)
        texts.completePortion(t.id, listOf(a.id, b.id), 0, now)
        texts.saveText(t, listOf(EditedPiece(a.id, "a\nb"), EditedPiece(null, "c", scheduleFrom = a.id)))
        val after = texts.pieces(t.id)
        assertEquals(listOf("a\nb", "c"), after.map { it.content })
        assertEquals(a.id, after[0].id)
        assertEquals(after[0].due, after[1].due)
    }

    @Test
    fun practiceDoesNotMoveSchedule() = runBlocking {
        val t = texts.createText("Т", "ru", TextKind.POEM, TextSchedule.FSRS, 1, "a")!!
        val p = texts.pieces(t.id)[0]
        texts.completePortion(t.id, listOf(p.id), 0, now)
        val before = texts.pieces(t.id)[0]
        texts.logPractice(t.id, listOf(p.id), setOf(p.id), 0, 0, now + 1)
        texts.logPractice(t.id, listOf(p.id), setOf(p.id), 0, 0, now + 2)
        assertEquals(before, texts.pieces(t.id)[0])
        assertEquals(setOf(p.id), texts.weakPieces(t.id))
    }

    @Test
    fun backupRunsWithOnlyTexts() = runBlocking {
        val backup = Backup(context, db, settings)
        assertFalse(backup.hasContent())
        val t = texts.createText("Т", "ru", TextKind.POEM, TextSchedule.FSRS, 1, "a")!!
        assertTrue(backup.hasContent())
        texts.recordWords(mapOf(WordKey(texts.pieces(t.id)[0].id, 0) to WordOutcome.WRONG))
        // the archive carries the whole database file, so the word history survives a restore
        val bytes = java.io.ByteArrayOutputStream().also { backup.write(it, includeMedia = false) }.toByteArray()
        val checked = backup.check(bytes.inputStream())
        android.database.sqlite.SQLiteDatabase.openDatabase(checked.dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use {
            it.rawQuery("SELECT COUNT(*) FROM text_word_stats", null).use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        }
    }

    @Test
    fun wordsStoredOnCreate() = runBlocking {
        val t = texts.createText("Т", "ru", TextKind.POEM, TextSchedule.FSRS, 2, "Мама мыла\nраму")!!
        val pieces = texts.pieces(t.id)
        assertTrue(pieces.all { it.words != null })
        assertEquals(listOf("Мама", "мыла"), pieces[0].words!!.let(PieceWords::decode).map { pieces[0].content.substring(it.start, it.end) })
        assertEquals(1, PieceWords.decode(pieces[1].words!!).size)
    }

    @Test
    fun editKeepsHistoryOfUnchangedPieces() = runBlocking {
        val t = texts.createText("Т", "ru", TextKind.POEM, TextSchedule.FSRS, 2, "один два\nтри четыре")!!
        val (a, b) = texts.pieces(t.id)
        texts.recordWords(mapOf(WordKey(a.id, 0) to WordOutcome.WRONG, WordKey(b.id, 0) to WordOutcome.CORRECT))
        texts.saveText(t, listOf(EditedPiece(a.id, a.content), EditedPiece(b.id, "три пять шесть")))
        val stats = texts.wordStats(listOf(a.id, b.id))
        assertEquals(setOf(WordKey(a.id, 0)), stats.keys)
        assertEquals(WordStat(0, 1, 1), stats[WordKey(a.id, 0)])
    }

    @Test
    fun setReadingsRecomputesAndResets() = runBlocking {
        val t = texts.createText("Т", "ja", TextKind.POEM, TextSchedule.FSRS, 2, "日本\n語")!!
        val (a, b) = texts.pieces(t.id)
        texts.recordWords(mapOf(WordKey(a.id, 0) to WordOutcome.WRONG, WordKey(b.id, 0) to WordOutcome.WRONG))
        texts.setReadings(t.id, mapOf("日本" to ReadingOverride("にほん", emptyList())))
        val after = texts.pieces(t.id)
        assertEquals(listOf("にほん"), PieceWords.decode(after[0].words!!).map { it.reading })
        assertEquals(b.words, after[1].words)
        assertEquals(setOf(WordKey(b.id, 0)), texts.wordStats(listOf(a.id, b.id)).keys)
        assertEquals(mapOf("日本" to ReadingOverride("にほん", emptyList())), PieceWords.decodeReadings(texts.text(t.id)!!.readings))
    }

    @Test
    fun missingWordsAreBackfilled() = runBlocking {
        val t = texts.createText("Т", "ru", TextKind.POEM, TextSchedule.FSRS, 1, "один два")!!
        val p = texts.pieces(t.id)[0]
        db.texts().upsertPieces(listOf(p.copy(words = null)))
        val loaded = texts.pieces(t.id)
        assertNull(loaded[0].words)
        assertEquals(2, texts.words(loaded)[p.id]!!.size)
        assertNotNull(texts.pieces(t.id)[0].words)
    }

    @Test
    fun draftRoundTrip() = runBlocking {
        assertNull(texts.draft("k"))
        val d = TypingDraftEntity("k", "при", "MEMORY", 5, 20_000, 6)
        texts.saveDraft(d)
        assertEquals(d, texts.draft("k"))
        texts.saveDraft(d.copy(typed = "привет"))
        assertEquals("привет", texts.draft("k")!!.typed)
        texts.clearDraft("k")
        assertNull(texts.draft("k"))
    }

    @Test
    fun typedReviewLogsTypedMode() = runBlocking {
        val t = texts.createText("Т", "ru", TextKind.POEM, TextSchedule.FSRS, 1, "a")!!
        val p = texts.pieces(t.id)[0].id
        texts.completePortion(t.id, listOf(p), 0, now)
        texts.reviewPiece(p, Rating.GOOD, 0, 0, now + DAY, TextRepository.MODE_TYPED)
        val modes = db.texts().recitations(t.id).map { it.part to it.mode }
        assertEquals(listOf("NEW" to "SELF", "REVIEW" to "TYPED"), modes)
    }

    /** One token per character, no readings. */
    private object CharMorphology : Morphology {
        override fun tokens(text: String) = text.indices.map { MorphToken(it, it + 1, null) }
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
