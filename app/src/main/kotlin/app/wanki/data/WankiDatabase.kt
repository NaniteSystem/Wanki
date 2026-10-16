package app.wanki.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DeckEntity::class, NoteEntity::class, CardEntity::class, ReviewLogEntity::class, InkAttemptEntity::class,
        NotebookEntity::class, PageEntity::class, PageInkEntity::class, TextNoteEntity::class, NoteBlockEntity::class,
        CharScoreEntity::class,
    ],
    version = WankiDatabase.VERSION,
    exportSchema = true,
)
abstract class WankiDatabase : RoomDatabase() {
    abstract fun decks(): DeckDao
    abstract fun cards(): CardDao
    abstract fun notes(): NoteDao
    abstract fun reviews(): ReviewDao
    abstract fun charScores(): CharScoreDao
    abstract fun notebooks(): NotebookDao
    abstract fun textNotes(): TextNoteDao

    companion object {
        const val NAME = "wanki.db"
        const val VERSION = 3

        fun create(context: Context): WankiDatabase =
            Room.databaseBuilder(context, WankiDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()

        /** v2: notes made of text and handwriting blocks. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `note_blocks` (`id` TEXT NOT NULL, `noteId` TEXT NOT NULL, " +
                        "`blockIndex` INTEGER NOT NULL, `type` TEXT NOT NULL, `text` TEXT NOT NULL, `strokes` BLOB, " +
                        "`heightDp` REAL NOT NULL, `recognizedText` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_blocks_noteId` ON `note_blocks` (`noteId`)")
            }
        }

        /**
         * v3: daily review limit and collapsed subdecks; suspended/buried cards and the order of new
         * cards; the card state before each review (for daily limits and retention); handwriting
         * results per character, filled from earlier single-character written answers.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `decks` ADD COLUMN `reviewsPerDay` INTEGER NOT NULL DEFAULT 200")
                db.execSQL("ALTER TABLE `decks` ADD COLUMN `collapsed` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `cards` ADD COLUMN `suspended` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `cards` ADD COLUMN `buriedUntil` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `cards` ADD COLUMN `position` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `cards` SET `position` = `updatedAt`")
                db.execSQL("ALTER TABLE `review_logs` ADD COLUMN `cardState` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `char_scores` (`id` TEXT NOT NULL, `glyph` TEXT NOT NULL, `language` TEXT NOT NULL, " +
                        "`at` INTEGER NOT NULL, `score` REAL, `beauty` REAL, `source` TEXT NOT NULL, `cardId` TEXT, " +
                        "`reviewLogId` TEXT, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_char_scores_glyph` ON `char_scores` (`glyph`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_char_scores_reviewLogId` ON `char_scores` (`reviewLogId`)")
                db.execSQL(
                    """
                    INSERT INTO `char_scores` (`id`, `glyph`, `language`, `at`, `score`, `beauty`, `source`, `cardId`, `reviewLogId`)
                    SELECT r.id, substr(n.fields, instr(n.fields, char(31)) + 1), d.language, r.reviewedAt, r.score, r.beauty,
                           'REVIEW', c.id, r.id
                    FROM review_logs r JOIN cards c ON c.id = r.cardId JOIN notes n ON n.id = c.noteId JOIN decks d ON d.id = c.deckId
                    WHERE r.mode = 'HANDWRITING' AND r.beauty IS NOT NULL AND c.writeFieldOrd = 1
                      AND instr(n.fields, char(31)) > 0 AND length(substr(n.fields, instr(n.fields, char(31)) + 1)) = 1
                    """.trimIndent(),
                )
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}
