package app.wanki.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** Columns for [CardWithNote] from `cards c JOIN notes n`. */
const val CARD_WITH_NOTE_COLUMNS = "c.*, n.id AS note_id, n.noteTypeId AS note_noteTypeId, n.fields AS note_fields, " +
    "n.tags AS note_tags, n.updatedAt AS note_updatedAt, n.deleted AS note_deleted"

/** A card can be studied: not deleted, not suspended, not buried. */
private const val ACTIVE = "c.deleted = 0 AND c.suspended = 0 AND c.buriedUntil <= :now"

@Dao
interface DeckDao {
    @Query("SELECT * FROM decks WHERE deleted = 0 ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<DeckEntity>>

    @Query("SELECT * FROM decks WHERE deleted = 0 ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<DeckEntity>

    @Query("SELECT * FROM decks WHERE id = :id")
    suspend fun get(id: String): DeckEntity?

    @Query("SELECT * FROM decks WHERE id = :id")
    fun observe(id: String): Flow<DeckEntity?>

    @Query("SELECT COUNT(*) FROM decks")
    suspend fun count(): Int

    @Query(
        """
        SELECT * FROM decks WHERE deleted = 0 AND name = :name
          AND ((:parentId IS NULL AND parentId IS NULL) OR parentId = :parentId) LIMIT 1
        """,
    )
    suspend fun findChild(parentId: String?, name: String): DeckEntity?

    @Upsert
    suspend fun upsert(deck: DeckEntity)

    @Query("SELECT * FROM decks WHERE catalogId = :catalogId AND deleted = 0 LIMIT 1")
    suspend fun byCatalogId(catalogId: String): DeckEntity?

    @Query("SELECT * FROM decks WHERE catalogId IS NOT NULL AND deleted = 0")
    fun observeInstalled(): Flow<List<DeckEntity>>

    @Query("UPDATE decks SET collapsed = :collapsed WHERE id = :id")
    suspend fun setCollapsed(id: String, collapsed: Boolean)

    @Query("UPDATE decks SET deleted = 1, updatedAt = :now WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>, now: Long = System.currentTimeMillis())
}

@Dao
interface CardDao {
    @Query("SELECT $CARD_WITH_NOTE_COLUMNS FROM cards c JOIN notes n ON n.id = c.noteId WHERE c.id = :id")
    suspend fun get(id: String): CardWithNote?

    @Query("SELECT $CARD_WITH_NOTE_COLUMNS FROM cards c JOIN notes n ON n.id = c.noteId WHERE c.id = :id")
    fun observe(id: String): Flow<CardWithNote?>

    /** Browser search, built by [CardQuery]. */
    @RawQuery(observedEntities = [CardEntity::class, NoteEntity::class])
    fun observeQuery(query: SupportSQLiteQuery): Flow<List<CardWithNote>>

    @RawQuery(observedEntities = [CardEntity::class, NoteEntity::class])
    fun observeCount(query: SupportSQLiteQuery): Flow<Int>

    @RawQuery
    suspend fun ids(query: SupportSQLiteQuery): List<String>

    @Query(
        """
        SELECT c.deckId AS deckId,
          SUM(CASE WHEN $ACTIVE AND c.lastReview IS NULL THEN 1 ELSE 0 END) AS newCount,
          SUM(CASE WHEN $ACTIVE AND c.lastReview IS NOT NULL AND c.state != 'REVIEW' AND c.due <= :learnAhead THEN 1 ELSE 0 END) AS learnCount,
          SUM(CASE WHEN $ACTIVE AND c.lastReview IS NOT NULL AND c.state = 'REVIEW' AND c.due <= :now THEN 1 ELSE 0 END) AS reviewCount,
          COUNT(*) AS total
        FROM cards c WHERE c.deleted = 0 GROUP BY c.deckId
        """,
    )
    fun observeCounts(now: Long, learnAhead: Long): Flow<List<DeckCardCounts>>

    @Query(
        """
        SELECT c.deckId AS deckId,
          SUM(CASE WHEN $ACTIVE AND c.lastReview IS NULL THEN 1 ELSE 0 END) AS newCount,
          SUM(CASE WHEN $ACTIVE AND c.lastReview IS NOT NULL AND c.state != 'REVIEW' AND c.due <= :learnAhead THEN 1 ELSE 0 END) AS learnCount,
          SUM(CASE WHEN $ACTIVE AND c.lastReview IS NOT NULL AND c.state = 'REVIEW' AND c.due <= :now THEN 1 ELSE 0 END) AS reviewCount,
          COUNT(*) AS total
        FROM cards c WHERE c.deleted = 0 GROUP BY c.deckId
        """,
    )
    suspend fun counts(now: Long, learnAhead: Long): List<DeckCardCounts>

    /** Learning/relearning cards of these decks due within the learn-ahead window, earliest first. */
    @Query(
        """
        SELECT $CARD_WITH_NOTE_COLUMNS FROM cards c JOIN notes n ON n.id = c.noteId
        WHERE c.deckId IN (:deckIds) AND $ACTIVE AND c.lastReview IS NOT NULL AND c.state != 'REVIEW' AND c.due <= :learnAhead
        ORDER BY c.due
        """,
    )
    suspend fun dueLearning(deckIds: List<String>, now: Long, learnAhead: Long): List<CardWithNote>

    @Query(
        """
        SELECT $CARD_WITH_NOTE_COLUMNS FROM cards c JOIN notes n ON n.id = c.noteId
        WHERE c.deckId = :deckId AND $ACTIVE AND c.lastReview IS NOT NULL AND c.state = 'REVIEW' AND c.due <= :now
        ORDER BY c.due LIMIT :limit
        """,
    )
    suspend fun dueReviews(deckId: String, now: Long, limit: Int): List<CardWithNote>

    @Query(
        """
        SELECT $CARD_WITH_NOTE_COLUMNS FROM cards c JOIN notes n ON n.id = c.noteId
        WHERE c.deckId = :deckId AND $ACTIVE AND c.lastReview IS NULL
        ORDER BY c.position LIMIT :limit
        """,
    )
    suspend fun newCards(deckId: String, now: Long, limit: Int): List<CardWithNote>

    @Upsert
    suspend fun upsert(card: CardEntity)

    @Upsert
    suspend fun upsertAll(cards: List<CardEntity>)

    @Query("SELECT COALESCE(MAX(position), 0) FROM cards")
    suspend fun maxPosition(): Long

    /** Which of these card ids exist (deleted ones included: a card the user deleted stays deleted). */
    @Query("SELECT id FROM cards WHERE id IN (:ids)")
    suspend fun existing(ids: List<String>): List<String>

    @Query(
        """
        SELECT (due + :offset) / 86400000 AS day, COUNT(*) AS n FROM cards
        WHERE deleted = 0 AND suspended = 0 AND lastReview IS NOT NULL AND due < :until GROUP BY day ORDER BY day
        """,
    )
    suspend fun duePerDay(until: Long, offset: Long): List<DueDay>

    @Query(
        """
        SELECT state, lastReview IS NULL AS isNew, suspended, COUNT(*) AS n FROM cards
        WHERE deleted = 0 GROUP BY state, isNew, suspended
        """,
    )
    suspend fun stateCounts(): List<CardStateCount>

    @Query("UPDATE cards SET suspended = :suspended, updatedAt = :now WHERE id IN (:ids)")
    suspend fun setSuspended(ids: List<String>, suspended: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE cards SET buriedUntil = :until, updatedAt = :now WHERE id IN (:ids)")
    suspend fun bury(ids: List<String>, until: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE cards SET deckId = :deckId, updatedAt = :now WHERE id IN (:ids)")
    suspend fun move(ids: List<String>, deckId: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE cards SET answerMode = :mode, updatedAt = :now WHERE id IN (:ids)")
    suspend fun setMode(ids: List<String>, mode: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE cards SET answerMode = :mode, updatedAt = :now WHERE deckId = :deckId AND deleted = 0")
    suspend fun setModeInDeck(deckId: String, mode: String, now: Long = System.currentTimeMillis())

    /** Back to new: the schedule is forgotten, the review history stays. */
    @Query(
        """
        UPDATE cards SET state = 'LEARNING', step = 0, stability = NULL, difficulty = NULL, due = 0, lastReview = NULL,
          reps = 0, lapses = 0, buriedUntil = 0, updatedAt = :now WHERE id IN (:ids)
        """,
    )
    suspend fun reset(ids: List<String>, now: Long = System.currentTimeMillis())

    @Query("UPDATE cards SET deleted = 1, updatedAt = :now WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>, now: Long = System.currentTimeMillis())

    @Query("UPDATE cards SET deleted = 1, updatedAt = :now WHERE deckId IN (:deckIds) AND deleted = 0")
    suspend fun deleteInDecks(deckIds: List<String>, now: Long = System.currentTimeMillis())
}

@Dao
interface NoteDao {
    @Upsert
    suspend fun upsert(note: NoteEntity)

    @Upsert
    suspend fun upsertAll(notes: List<NoteEntity>)

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE id IN (:ids)")
    suspend fun getAll(ids: List<String>): List<NoteEntity>

    /** Notes left without live cards are deleted too. */
    @Query(
        """
        UPDATE notes SET deleted = 1, updatedAt = :now
        WHERE deleted = 0 AND NOT EXISTS (SELECT 1 FROM cards c WHERE c.noteId = notes.id AND c.deleted = 0)
        """,
    )
    suspend fun deleteOrphans(now: Long = System.currentTimeMillis())
}

@Dao
interface ReviewDao {
    @Insert
    suspend fun insert(log: ReviewLogEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(logs: List<ReviewLogEntity>)

    @Insert
    suspend fun insertAttempt(attempt: InkAttemptEntity)

    @Query("DELETE FROM review_logs WHERE id = :id")
    suspend fun deleteLog(id: String)

    @Query("DELETE FROM ink_attempts WHERE reviewLogId = :logId")
    suspend fun deleteAttempts(logId: String)

    @Query("SELECT * FROM review_logs WHERE cardId = :cardId ORDER BY reviewedAt")
    suspend fun forCard(cardId: String): List<ReviewLogEntity>

    @Query("SELECT * FROM ink_attempts WHERE reviewLogId IN (:logIds)")
    suspend fun attempts(logIds: List<String>): List<InkAttemptEntity>

    @Query(
        """
        SELECT c.deckId AS deckId,
          SUM(CASE WHEN r.cardState = 'NEW' THEN 1 ELSE 0 END) AS newDone,
          SUM(CASE WHEN r.cardState = 'REVIEW' THEN 1 ELSE 0 END) AS reviewDone
        FROM review_logs r JOIN cards c ON c.id = r.cardId WHERE r.reviewedAt >= :dayStart GROUP BY c.deckId
        """,
    )
    fun observeToday(dayStart: Long): Flow<List<DeckTodayCounts>>

    @Query(
        """
        SELECT c.deckId AS deckId,
          SUM(CASE WHEN r.cardState = 'NEW' THEN 1 ELSE 0 END) AS newDone,
          SUM(CASE WHEN r.cardState = 'REVIEW' THEN 1 ELSE 0 END) AS reviewDone
        FROM review_logs r JOIN cards c ON c.id = r.cardId WHERE r.reviewedAt >= :dayStart GROUP BY c.deckId
        """,
    )
    suspend fun today(dayStart: Long): List<DeckTodayCounts>

    @Query(
        """
        SELECT (reviewedAt + :offset) / 86400000 AS day, COUNT(*) AS n,
               SUM(CASE WHEN rating > 1 THEN 1 ELSE 0 END) AS ok
        FROM review_logs WHERE reviewedAt >= :since GROUP BY day ORDER BY day
        """,
    )
    suspend fun perDay(since: Long, offset: Long): List<DayCount>

    /** Answers to cards that were in review (not learning): their success rate is the true retention. */
    @Query(
        """
        SELECT COUNT(*) AS n, SUM(CASE WHEN rating > 1 THEN 1 ELSE 0 END) AS ok
        FROM review_logs WHERE reviewedAt >= :since AND cardState = 'REVIEW'
        """,
    )
    suspend fun retention(since: Long): RetentionCount

    @Query("SELECT COUNT(*) FROM review_logs WHERE reviewedAt >= :since")
    fun observeCountSince(since: Long): Flow<Int>
}

@Dao
interface NoteTypeDao {
    @Query("SELECT * FROM note_types WHERE deleted = 0")
    suspend fun all(): List<NoteTypeEntity>

    @Query("SELECT * FROM note_types WHERE id = :id")
    suspend fun get(id: String): NoteTypeEntity?

    @Upsert
    suspend fun upsertAll(types: List<NoteTypeEntity>)
}

@Dao
interface CharScoreDao {
    @Insert
    suspend fun insert(rows: List<CharScoreEntity>)

    @Query("DELETE FROM char_scores WHERE reviewLogId = :logId")
    suspend fun deleteForLog(logId: String)

    /** Latest handwriting results, newest first. */
    @Query("SELECT * FROM char_scores WHERE beauty IS NOT NULL ORDER BY at DESC LIMIT :limit")
    suspend fun recent(limit: Int = 5000): List<CharScoreEntity>

    @Query("SELECT * FROM char_scores WHERE glyph = :glyph ORDER BY at DESC")
    suspend fun forGlyph(glyph: String): List<CharScoreEntity>

    @Query(
        """
        SELECT (at + :offset) / 86400000 AS day, AVG(beauty) AS beauty, COUNT(*) AS n
        FROM char_scores WHERE beauty IS NOT NULL AND at >= :since GROUP BY day ORDER BY day
        """,
    )
    suspend fun beautyPerDay(since: Long, offset: Long): List<DayBeauty>

    @Query("SELECT COUNT(*) FROM char_scores")
    fun observeCount(): Flow<Int>
}

@Dao
interface NotebookDao {
    @Query(
        """
        SELECT n.*, (SELECT COUNT(*) FROM pages p WHERE p.notebookId = n.id AND p.deleted = 0) AS pageCount
        FROM notebooks n WHERE n.deleted = 0 AND n.kind = :kind ORDER BY n.updatedAt DESC
        """,
    )
    fun observeByKind(kind: String): Flow<List<NotebookWithCount>>

    @Query("SELECT * FROM notebooks WHERE kind = :kind AND deleted = 0 ORDER BY updatedAt LIMIT 1")
    suspend fun firstOfKind(kind: String): NotebookEntity?

    @Query("SELECT * FROM notebooks WHERE id = :id")
    suspend fun get(id: String): NotebookEntity?

    @Upsert
    suspend fun upsert(notebook: NotebookEntity)

    @Query("UPDATE notebooks SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM pages WHERE notebookId = :notebookId AND deleted = 0 ORDER BY pageIndex")
    fun observePages(notebookId: String): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE notebookId = :notebookId AND deleted = 0 ORDER BY pageIndex")
    suspend fun pages(notebookId: String): List<PageEntity>

    @Query("SELECT * FROM pages WHERE notebookId = :notebookId AND date = :epochDay AND deleted = 0 LIMIT 1")
    suspend fun pageForDate(notebookId: String, epochDay: Long): PageEntity?

    @Query("SELECT date FROM pages WHERE notebookId = :notebookId AND deleted = 0 AND date IS NOT NULL")
    fun observeDiaryDates(notebookId: String): Flow<List<Long>>

    @Upsert
    suspend fun upsertPage(page: PageEntity)

    /** Inserts a page not stored yet (a diary day gets its row on the first save); keeps an existing one. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPageIfMissing(page: PageEntity)

    @Query("UPDATE pages SET updatedAt = :now WHERE id = :id")
    suspend fun touchPage(id: String, now: Long)

    @Query("UPDATE pages SET template = :template, updatedAt = :now WHERE id = :id")
    suspend fun setPageTemplate(id: String, template: String, now: Long)

    @Query("UPDATE pages SET viewScale = :scale, viewX = :x, viewY = :y WHERE id = :id")
    suspend fun setPageView(id: String, scale: Float, x: Float, y: Float)

    @Query("SELECT * FROM page_ink WHERE pageId = :pageId")
    suspend fun ink(pageId: String): PageInkEntity?

    @Upsert
    suspend fun upsertInk(ink: PageInkEntity)

    @Query("UPDATE page_ink SET recognizedText = :text, recognizedAt = :at WHERE pageId = :pageId")
    suspend fun setRecognizedText(pageId: String, text: String, at: Long)

    @Query(
        """
        SELECT p.id AS pageId, p.notebookId AS notebookId, n.title AS notebookTitle, p.pageIndex AS pageIndex,
               p.date AS date, i.recognizedText AS recognizedText
        FROM page_ink i JOIN pages p ON p.id = i.pageId JOIN notebooks n ON n.id = p.notebookId
        WHERE p.deleted = 0 AND n.deleted = 0 AND i.recognizedText LIKE '%' || :query || '%'
        ORDER BY p.updatedAt DESC LIMIT 100
        """,
    )
    suspend fun search(query: String): List<PageSearchHit>
}

@Dao
interface TextNoteDao {
    @Query("SELECT * FROM text_notes WHERE deleted = 0 ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<TextNoteEntity>>

    @Query("SELECT * FROM text_notes WHERE id = :id")
    suspend fun get(id: String): TextNoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: TextNoteEntity)

    @Query("UPDATE text_notes SET deleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun delete(id: String, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM note_blocks WHERE noteId = :noteId ORDER BY blockIndex")
    suspend fun blocks(noteId: String): List<NoteBlockEntity>

    @Upsert
    suspend fun upsertBlocks(blocks: List<NoteBlockEntity>)

    @Query("DELETE FROM note_blocks WHERE noteId = :noteId AND id NOT IN (:keep)")
    suspend fun deleteBlocksExcept(noteId: String, keep: List<String>)
}
