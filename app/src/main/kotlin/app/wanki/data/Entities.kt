package app.wanki.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

/** How a card is answered. Chosen per card, can be switched during review. */
enum class AnswerMode { CLASSIC, HANDWRITING }

enum class NotebookKind { NOTEBOOK, DIARY, BOARD }

enum class PageTemplate { BLANK, LINED, GRID, DOTS, GENKOU, TIANZIGE, WONGOJI, LINED_RTL }

@Entity(tableName = "decks")
data class DeckEntity(
    @PrimaryKey val id: String = newId(),
    val parentId: String? = null,
    val name: String,
    /** BCP-47-ish: ja, zh, ko, ar, en, ru… Used for recognition and references. */
    val language: String = "ja",
    val defaultAnswerMode: String = AnswerMode.HANDWRITING.name,
    val newPerDay: Int = 20,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)

/** Anki-compatible: fields are joined with 0x1F. Only the "basic" note type for now. */
@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String = newId(),
    val noteTypeId: String = "basic",
    val fields: String,
    val tags: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
) {
    fun field(i: Int): String = fields.split(FIELD_SEPARATOR).getOrElse(i) { "" }

    companion object {
        const val FIELD_SEPARATOR = '\u001F'
        fun basic(front: String, back: String, tags: String = "") =
            NoteEntity(fields = front + FIELD_SEPARATOR + back, tags = tags)
    }
}

@Entity(tableName = "cards", indices = [Index("deckId"), Index("noteId"), Index("due")])
data class CardEntity(
    @PrimaryKey val id: String = newId(),
    val noteId: String,
    val deckId: String,
    val templateOrd: Int = 0,
    val answerMode: String = AnswerMode.HANDWRITING.name,
    /** Field the user writes when [answerMode] is HANDWRITING (the back by default). */
    val writeFieldOrd: Int = 1,
    val state: String = "LEARNING",
    val step: Int? = 0,
    val stability: Double? = null,
    val difficulty: Double? = null,
    val due: Long = 0,
    val lastReview: Long? = null,
    val reps: Int = 0,
    val lapses: Int = 0,
    /** Stable seed for FSRS fuzz, so replaying the log gives the same schedule. */
    val seed: Long = UUID.randomUUID().mostSignificantBits,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)

@Entity(tableName = "review_logs", indices = [Index("cardId")])
data class ReviewLogEntity(
    @PrimaryKey val id: String = newId(),
    val cardId: String,
    val reviewedAt: Long,
    val rating: Int,
    val mode: String,
    val score: Double? = null,
    val beauty: Double? = null,
    val durationMs: Long = 0,
    val usedHint: Boolean = false,
)

@Entity(tableName = "ink_attempts", indices = [Index("reviewLogId")])
data class InkAttemptEntity(
    @PrimaryKey val id: String = newId(),
    val reviewLogId: String,
    /** InkCodec blob */
    val strokes: ByteArray,
)

@Entity(tableName = "notebooks")
data class NotebookEntity(
    @PrimaryKey val id: String = newId(),
    val title: String,
    val kind: String = NotebookKind.NOTEBOOK.name,
    val language: String = "ja",
    val template: String = PageTemplate.LINED.name,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)

@Entity(tableName = "pages", indices = [Index("notebookId")])
data class PageEntity(
    @PrimaryKey val id: String = newId(),
    val notebookId: String,
    val pageIndex: Int,
    val template: String,
    /** Epoch day, for diary pages. */
    val date: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)

@Entity(tableName = "page_ink")
data class PageInkEntity(
    @PrimaryKey val pageId: String,
    val strokes: ByteArray,
    val recognizedText: String = "",
    val recognizedAt: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "text_notes")
data class TextNoteEntity(
    @PrimaryKey val id: String = newId(),
    val title: String,
    val body: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)

data class DeckWithCounts(
    @Embedded val deck: DeckEntity,
    @ColumnInfo(name = "dueCount") val dueCount: Int,
    @ColumnInfo(name = "newCount") val newCount: Int,
    @ColumnInfo(name = "totalCount") val totalCount: Int,
)

data class CardWithNote(
    @Embedded val card: CardEntity,
    @Embedded(prefix = "note_") val note: NoteEntity,
)

data class NotebookWithCount(
    @Embedded val notebook: NotebookEntity,
    @ColumnInfo(name = "pageCount") val pageCount: Int,
)

data class PageSearchHit(
    val pageId: String,
    val notebookId: String,
    val notebookTitle: String,
    val pageIndex: Int,
    val date: Long?,
    val recognizedText: String,
)
