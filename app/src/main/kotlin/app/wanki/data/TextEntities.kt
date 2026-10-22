package app.wanki.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.wanki.texts.TextKind
import app.wanki.texts.TextSchedule
import java.util.UUID

/** A text learned by heart (poem, prose, ayah...). [kind] is a [TextKind] name, [schedule] a [TextSchedule] name. */
@Entity(tableName = "texts")
data class TextEntity(
    @PrimaryKey val id: String = newId(), val title: String, val language: String,
    val kind: String = TextKind.POEM.name, val schedule: String = TextSchedule.FSRS.name,
    val portionSize: Int = 4, val active: Boolean = true, val lastPortionDay: Long? = null,
    val position: Long = System.currentTimeMillis(), val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(), val deleted: Boolean = false,
    /** Reading overrides for typing, encoded by `PieceWords.encodeReadings`. */
    val readings: String = "",
)

/** One piece (line, sentence) of a text, with its FSRS state and hifz bookkeeping. */
@Entity(tableName = "text_pieces", indices = [Index("textId")])
data class TextPieceEntity(
    @PrimaryKey val id: String = newId(), val textId: String, val pieceIndex: Int, val content: String,
    val note: String = "", val learnedAt: Long? = null,
    val state: String = "LEARNING", val step: Int? = 0, val stability: Double? = null, val difficulty: Double? = null,
    val due: Long = 0, val lastReview: Long? = null, val reps: Int = 0, val lapses: Int = 0,
    val seed: Long = UUID.randomUUID().mostSignificantBits,
    val sabqiLeft: Int = 0, val hifzDay: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    /** The piece's typing words, encoded by `PieceWords.encode`; null = not computed yet. */
    val words: String? = null,
)

enum class RecitationPart { NEW, SABAQ, REVIEW, SABQI, MANZIL, PRACTICE, KNOWN }

/** Recitation log: one row per recalled piece. */
@Entity(tableName = "recitations", indices = [Index("textId"), Index("pieceId")])
data class RecitationEntity(
    @PrimaryKey val id: String = newId(), val textId: String, val pieceId: String, val at: Long,
    val part: String,
    /** How the recital was checked: SELF (self-check), TYPED (typed from the text), NONE (part KNOWN); VOICE comes later. */
    val mode: String,
    val rating: Int?, val error: Boolean, val hints: Int = 0, val durationMs: Long = 0,
)

/** Typing history of one word of a piece (by its index in the piece's word list). */
@Entity("text_word_stats", primaryKeys = ["pieceId", "wordIndex"], indices = [Index("pieceId")])
data class TextWordStatEntity(
    val pieceId: String, val wordIndex: Int, val cleanStreak: Int, val recent: Int, val cue: Int, val updatedAt: Long,
)

/** An unfinished typed run, so it can be resumed. [key] names what is being typed; [day] is its local epoch day. */
@Entity("typing_drafts")
data class TypingDraftEntity(
    @PrimaryKey val key: String, val typed: String, val level: String, val startedAt: Long, val day: Long, val updatedAt: Long,
)
