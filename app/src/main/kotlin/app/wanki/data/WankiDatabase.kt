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
        CharScoreEntity::class, NoteTypeEntity::class, TextEntity::class, TextPieceEntity::class, RecitationEntity::class,
        TextWordStatEntity::class, TypingDraftEntity::class, PageObjectEntity::class, PageLineEntity::class,
        SourceRefEntity::class,
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
    abstract fun noteTypes(): NoteTypeDao
    abstract fun notebooks(): NotebookDao
    abstract fun textNotes(): TextNoteDao
    abstract fun texts(): TextDao
    abstract fun sourceRefs(): SourceRefDao

    companion object {
        const val NAME = "wanki.db"
        const val VERSION = 11

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

        /** v4: decks remember the ready-made deck they were installed from. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `decks` ADD COLUMN `catalogId` TEXT")
                db.execSQL("ALTER TABLE `decks` ADD COLUMN `catalogVersion` TEXT")
            }
        }

        /** v5: note types imported from Anki. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `note_types` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `fields` TEXT NOT NULL, " +
                        "`templates` TEXT NOT NULL, `kind` TEXT NOT NULL, `css` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "`deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
            }
        }

        /** v6: texts learned by heart, their pieces and the recitation log. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `texts` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `language` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `schedule` TEXT NOT NULL, `portionSize` INTEGER NOT NULL, `active` INTEGER NOT NULL, " +
                        "`lastPortionDay` INTEGER, `position` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `text_pieces` (`id` TEXT NOT NULL, `textId` TEXT NOT NULL, `pieceIndex` INTEGER NOT NULL, " +
                        "`content` TEXT NOT NULL, `note` TEXT NOT NULL, `learnedAt` INTEGER, `state` TEXT NOT NULL, `step` INTEGER, " +
                        "`stability` REAL, `difficulty` REAL, `due` INTEGER NOT NULL, `lastReview` INTEGER, `reps` INTEGER NOT NULL, " +
                        "`lapses` INTEGER NOT NULL, `seed` INTEGER NOT NULL, `sabqiLeft` INTEGER NOT NULL, `hifzDay` INTEGER, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_text_pieces_textId` ON `text_pieces` (`textId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recitations` (`id` TEXT NOT NULL, `textId` TEXT NOT NULL, `pieceId` TEXT NOT NULL, " +
                        "`at` INTEGER NOT NULL, `part` TEXT NOT NULL, `mode` TEXT NOT NULL, `rating` INTEGER, `error` INTEGER NOT NULL, " +
                        "`hints` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recitations_textId` ON `recitations` (`textId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recitations_pieceId` ON `recitations` (`pieceId`)")
            }
        }

        /** v7: each page remembers its zoom and pan. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pages` ADD COLUMN `viewScale` REAL")
                db.execSQL("ALTER TABLE `pages` ADD COLUMN `viewX` REAL")
                db.execSQL("ALTER TABLE `pages` ADD COLUMN `viewY` REAL")
            }
        }

        /** v8: typing from text: reading overrides, per-piece word lists, per-word history, unfinished runs. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `texts` ADD COLUMN `readings` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `text_pieces` ADD COLUMN `words` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `text_word_stats` (`pieceId` TEXT NOT NULL, `wordIndex` INTEGER NOT NULL, " +
                        "`cleanStreak` INTEGER NOT NULL, `recent` INTEGER NOT NULL, `cue` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`pieceId`, `wordIndex`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_text_word_stats_pieceId` ON `text_word_stats` (`pieceId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `typing_drafts` (`key` TEXT NOT NULL, `typed` TEXT NOT NULL, `level` TEXT NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, `day` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))",
                )
            }
        }

        /** v9: pinning, creation and deletion times (the trash), notebook page size and orientation. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()
                db.execSQL("ALTER TABLE `notebooks` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `notebooks` ADD COLUMN `createdAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `notebooks` ADD COLUMN `deletedAt` INTEGER")
                db.execSQL("ALTER TABLE `notebooks` ADD COLUMN `pageSize` TEXT NOT NULL DEFAULT 'A4'")
                db.execSQL("ALTER TABLE `notebooks` ADD COLUMN `landscape` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `pages` ADD COLUMN `deletedAt` INTEGER")
                db.execSQL("ALTER TABLE `pages` ADD COLUMN `landscape` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `text_notes` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `text_notes` ADD COLUMN `createdAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `text_notes` ADD COLUMN `deletedAt` INTEGER")
                for (t in listOf("notebooks", "text_notes")) {
                    db.execSQL("UPDATE `$t` SET `createdAt` = `updatedAt`")
                    // rows deleted before the trash existed start their 30 days now
                    db.execSQL("UPDATE `$t` SET `deletedAt` = $now WHERE `deleted` = 1")
                }
                db.execSQL("UPDATE `pages` SET `deletedAt` = $now WHERE `deleted` = 1")
            }
        }

        /** v10: objects (text, pictures) and recognised lines of a page, page backgrounds. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `page_objects` (`id` TEXT NOT NULL, `pageId` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`x` REAL NOT NULL, `y` REAL NOT NULL, `w` REAL NOT NULL, `h` REAL NOT NULL, `rotation` REAL NOT NULL, " +
                        "`z` INTEGER NOT NULL, `text` TEXT NOT NULL, `color` INTEGER NOT NULL, `textSize` REAL NOT NULL, " +
                        "`media` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_page_objects_pageId` ON `page_objects` (`pageId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `page_lines` (`pageId` TEXT NOT NULL, `lineKey` INTEGER NOT NULL, `lineIndex` INTEGER NOT NULL, " +
                        "`left` REAL NOT NULL, `top` REAL NOT NULL, `right` REAL NOT NULL, `bottom` REAL NOT NULL, " +
                        "`recognized` TEXT NOT NULL, `corrected` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`pageId`, `lineKey`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_page_lines_pageId` ON `page_lines` (`pageId`)")
                db.execSQL("ALTER TABLE `pages` ADD COLUMN `backgroundMedia` TEXT")
                db.execSQL("ALTER TABLE `pages` ADD COLUMN `backgroundPage` INTEGER")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `source_refs` (`id` TEXT NOT NULL, `noteId` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`sourceId` TEXT NOT NULL, `objectId` TEXT, `x` REAL, `y` REAL, `w` REAL, `h` REAL, `linked` INTEGER NOT NULL, " +
                        "`detached` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `suspendedByDetach` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_source_refs_noteId` ON `source_refs` (`noteId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_source_refs_sourceId` ON `source_refs` (`sourceId`)")
            }
        }

        val MIGRATIONS = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
            MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
        )
    }
}
