package app.wanki.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        DeckEntity::class, NoteEntity::class, CardEntity::class, ReviewLogEntity::class, InkAttemptEntity::class,
        NotebookEntity::class, PageEntity::class, PageInkEntity::class, TextNoteEntity::class,
    ],
    version = 1,
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
            Room.databaseBuilder(context, WankiDatabase::class.java, "wanki.db").build()
    }
}
