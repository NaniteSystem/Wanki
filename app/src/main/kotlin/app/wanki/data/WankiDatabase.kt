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
    ],
    version = 2,
    exportSchema = true,
)
abstract class WankiDatabase : RoomDatabase() {
    abstract fun decks(): DeckDao
    abstract fun cards(): CardDao
    abstract fun notes(): NoteDao
    abstract fun reviews(): ReviewDao
    abstract fun notebooks(): NotebookDao
    abstract fun textNotes(): TextNoteDao

    companion object {
        fun create(context: Context): WankiDatabase =
            Room.databaseBuilder(context, WankiDatabase::class.java, "wanki.db")
                .addMigrations(MIGRATION_1_2)
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
    }
}
