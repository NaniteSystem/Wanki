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

    private companion object {
        const val DB5 = "migration-test-5"
        const val DB4 = "migration-test-4"
        const val DB = "migration-test"
    }
}
