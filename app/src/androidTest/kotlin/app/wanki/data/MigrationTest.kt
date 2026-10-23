package app.wanki.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Migrations on a real SQLite, validated against the exported schemas (app/schemas). */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), WankiDatabase::class.java)

    @Test
    fun migrate2To3KeepsDataAndFillsCharScores() {
        helper.createDatabase(DB, 2).apply {
            execSQL("INSERT INTO decks VALUES ('d', NULL, 'Kanji', 'ja', 'HANDWRITING', 20, 1, 0)")
            execSQL("INSERT INTO notes VALUES ('n1', 'basic', 'день' || char(31) || '日', '', 1, 0)")
            execSQL("INSERT INTO notes VALUES ('n2', 'basic', 'Япония' || char(31) || '日本', '', 1, 0)")
            // id, noteId, deckId, templateOrd, answerMode, writeFieldOrd, state, step, stability, difficulty, due,
            // lastReview, reps, lapses, seed, updatedAt, deleted
            execSQL("INSERT INTO cards VALUES ('c1', 'n1', 'd', 0, 'HANDWRITING', 1, 'REVIEW', NULL, 3.0, 5.0, 1000, 500, 2, 0, 7, 100, 0)")
            execSQL("INSERT INTO cards VALUES ('c2', 'n2', 'd', 0, 'HANDWRITING', 1, 'REVIEW', NULL, 3.0, 5.0, 1000, 500, 2, 0, 7, 200, 0)")
            // id, cardId, reviewedAt, rating, mode, score, beauty, durationMs, usedHint
            execSQL("INSERT INTO review_logs VALUES ('r1', 'c1', 500, 3, 'HANDWRITING', 88.0, 70.0, 3000, 0)")
            execSQL("INSERT INTO review_logs VALUES ('r2', 'c2', 500, 3, 'HANDWRITING', 90.0, 80.0, 3000, 0)")
            execSQL("INSERT INTO review_logs VALUES ('r3', 'c1', 400, 1, 'CLASSIC', NULL, NULL, 2000, 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 3, true, WankiDatabase.MIGRATION_2_3)

        db.query("SELECT glyph, language, beauty, reviewLogId, source FROM char_scores").use { c ->
            // only the single-character written answer carries over (a word's beauty is an average)
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("日", c.getString(0))
            assertEquals("ja", c.getString(1))
            assertEquals(70.0, c.getDouble(2), 0.0)
            assertEquals("r1", c.getString(3))
            assertEquals("REVIEW", c.getString(4))
        }
        db.query("SELECT id, position, suspended, buriedUntil FROM cards ORDER BY id").use { c ->
            c.moveToFirst()
            assertEquals(100L, c.getLong(1))
            assertEquals(0, c.getInt(2))
            assertEquals(0L, c.getLong(3))
            c.moveToNext()
            assertEquals(200L, c.getLong(1))
        }
        db.query("SELECT reviewsPerDay, collapsed FROM decks").use { c ->
            c.moveToFirst()
            assertEquals(200, c.getInt(0))
            assertEquals(0, c.getInt(1))
        }
        db.query("SELECT COUNT(*) FROM review_logs WHERE cardState IS NULL").use { c ->
            c.moveToFirst()
            assertEquals(3, c.getInt(0))
        }
    }

    @Test
    fun migrate3To4AddsCatalogColumns() {
        helper.createDatabase(DB4, 3).apply {
            execSQL("INSERT INTO decks (id, parentId, name, language, defaultAnswerMode, newPerDay, updatedAt, deleted, reviewsPerDay, collapsed) VALUES ('d', NULL, 'Kanji', 'ja', 'HANDWRITING', 20, 1, 0, 200, 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(DB4, 4, true, WankiDatabase.MIGRATION_3_4)
        db.query("SELECT name, catalogId, catalogVersion FROM decks").use { c ->
            c.moveToFirst()
            assertEquals("Kanji", c.getString(0))
            assertEquals(true, c.isNull(1))
            assertEquals(true, c.isNull(2))
        }
    }

    @Test
    fun migrate4To5AddsNoteTypes() {
        helper.createDatabase(DB5, 4).close()
        val db = helper.runMigrationsAndValidate(DB5, 5, true, WankiDatabase.MIGRATION_4_5)
        db.execSQL("INSERT INTO note_types VALUES ('anki-1', 'Basic', '[]', '[]', 'STANDARD', '', 0, 0)")
        db.query("SELECT COUNT(*) FROM note_types").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
    }

    @Test
    fun migrate5To6AddsTexts() {
        helper.createDatabase(DB6, 5).close()
        val db = helper.runMigrationsAndValidate(DB6, 6, true, WankiDatabase.MIGRATION_5_6)
        db.execSQL("INSERT INTO texts (id, title, language, kind, schedule, portionSize, active, lastPortionDay, position, createdAt, updatedAt, deleted) VALUES ('t', 'Стих', 'ru', 'POEM', 'FSRS', 4, 1, NULL, 0, 0, 0, 0)")
        db.execSQL("INSERT INTO text_pieces (id, textId, pieceIndex, content, note, learnedAt, state, step, stability, difficulty, due, lastReview, reps, lapses, seed, sabqiLeft, hifzDay, updatedAt) VALUES ('p', 't', 0, 'Строка', '', NULL, 'LEARNING', 0, NULL, NULL, 0, NULL, 0, 0, 1, 0, NULL, 0)")
        db.execSQL("INSERT INTO recitations (id, textId, pieceId, at, part, mode, rating, error, hints, durationMs) VALUES ('r', 't', 'p', 0, 'NEW', 'SELF', 3, 0, 0, 0)")
        db.query("SELECT COUNT(*) FROM recitations").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
    }

    @Test
    fun migrate6To7AddsPageView() {
        helper.createDatabase(DB7, 6).apply {
            execSQL("INSERT INTO pages (id, notebookId, pageIndex, template, date, updatedAt, deleted) VALUES ('p', 'nb', 2, 'LINED', NULL, 5, 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(DB7, 7, true, WankiDatabase.MIGRATION_6_7)
        db.query("SELECT id, pageIndex, template, viewScale, viewX, viewY FROM pages").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("p", c.getString(0))
            assertEquals(2, c.getInt(1))
            assertEquals("LINED", c.getString(2))
            assertEquals(true, c.isNull(3))
            assertEquals(true, c.isNull(4))
            assertEquals(true, c.isNull(5))
        }
    }

    @Test
    fun migrate7To8AddsTypingTables() {
        helper.createDatabase(DB8, 7).apply {
            execSQL(
                "INSERT INTO texts (id, title, language, kind, schedule, portionSize, active, lastPortionDay, position, createdAt, updatedAt, deleted) " +
                    "VALUES ('t', 'T', 'ru', 'POEM', 'FSRS', 4, 1, NULL, 1, 1, 1, 0)",
            )
            execSQL(
                "INSERT INTO text_pieces (id, textId, pieceIndex, content, note, learnedAt, state, step, stability, difficulty, due, lastReview, " +
                    "reps, lapses, seed, sabqiLeft, hifzDay, updatedAt) VALUES ('p', 't', 0, 'a b', '', NULL, 'LEARNING', 0, NULL, NULL, 0, NULL, 0, 0, 7, 0, NULL, 1)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(DB8, 8, true, WankiDatabase.MIGRATION_7_8)
        db.query("SELECT readings FROM texts").use { c -> c.moveToFirst(); assertEquals("", c.getString(0)) }
        db.query("SELECT words FROM text_pieces").use { c -> c.moveToFirst(); assertEquals(true, c.isNull(0)) }
        db.execSQL("INSERT INTO text_word_stats VALUES ('p', 0, 1, 2, 0, 5)")
        db.execSQL("INSERT INTO typing_drafts VALUES ('k', 'x', 'MEMORY', 1, 2, 3)")
        db.query("SELECT (SELECT COUNT(*) FROM text_word_stats), (SELECT COUNT(*) FROM typing_drafts)").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
            assertEquals(1, c.getInt(1))
        }
    }

    @Test
    fun migrate8To9AddsTrashPinAndPageSettings() {
        helper.createDatabase(DB9, 8).apply {
            // id, title, kind, language, template, updatedAt, deleted
            execSQL("INSERT INTO notebooks (id, title, kind, language, template, updatedAt, deleted) VALUES ('nb', 'N', 'NOTEBOOK', 'ru', 'LINED', 500, 0)")
            execSQL("INSERT INTO pages (id, notebookId, pageIndex, template, date, updatedAt, deleted) VALUES ('p1', 'nb', 0, 'LINED', NULL, 5, 0)")
            execSQL("INSERT INTO pages (id, notebookId, pageIndex, template, date, updatedAt, deleted) VALUES ('p2', 'nb', 1, 'LINED', NULL, 6, 1)")
            execSQL("INSERT INTO text_notes (id, title, body, updatedAt, deleted) VALUES ('t1', 'live', 'x', 700, 0)")
            execSQL("INSERT INTO text_notes (id, title, body, updatedAt, deleted) VALUES ('t2', 'gone', 'y', 800, 1)")
            close()
        }
        val before = System.currentTimeMillis()
        val db = helper.runMigrationsAndValidate(DB9, 9, true, WankiDatabase.MIGRATION_8_9)
        val after = System.currentTimeMillis()
        db.query("SELECT pinned, createdAt, deletedAt, pageSize, landscape FROM notebooks").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
            assertEquals(500L, c.getLong(1))
            assertEquals(true, c.isNull(2))
            assertEquals("A4", c.getString(3))
            assertEquals(0, c.getInt(4))
        }
        db.query("SELECT id, deletedAt, landscape FROM pages ORDER BY id").use { c ->
            c.moveToFirst()
            assertEquals(true, c.isNull(1))
            assertEquals(0, c.getInt(2))
            c.moveToNext()
            assertEquals(true, c.getLong(1) in before..after)
        }
        db.query("SELECT id, pinned, createdAt, updatedAt, deletedAt FROM text_notes ORDER BY id").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(1))
            assertEquals(c.getLong(3), c.getLong(2))
            assertEquals(true, c.isNull(4))
            c.moveToNext()
            assertEquals(800L, c.getLong(2))
            assertEquals(true, c.getLong(4) in before..after)
        }
    }

    private companion object {
        const val DB9 = "migration-test-9"
        const val DB8 = "migration-test-8"
        const val DB7 = "migration-test-7"
        const val DB6 = "migration-test-6"
        const val DB5 = "migration-test-5"
        const val DB4 = "migration-test-4"
        const val DB = "migration-test"
    }
}
