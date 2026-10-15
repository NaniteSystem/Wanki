package app.wanki.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DeckDao {
    @Query(
        """
        SELECT d.*,
          (SELECT COUNT(*) FROM cards c WHERE c.deckId = d.id AND c.deleted = 0 AND c.lastReview IS NOT NULL AND c.due <= :now) AS dueCount,
          (SELECT COUNT(*) FROM cards c WHERE c.deckId = d.id AND c.deleted = 0 AND c.lastReview IS NULL) AS newCount,
          (SELECT COUNT(*) FROM cards c WHERE c.deckId = d.id AND c.deleted = 0) AS totalCount
        FROM decks d WHERE d.deleted = 0 ORDER BY d.name COLLATE NOCASE
        """,
    )
    fun observeWithCounts(now: Long): Flow<List<DeckWithCounts>>

    @Query("SELECT * FROM decks WHERE id = :id")
    suspend fun get(id: String): DeckEntity?

    @Query("SELECT * FROM decks WHERE id = :id")
    fun observe(id: String): Flow<DeckEntity?>

    @Query("SELECT COUNT(*) FROM decks")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(deck: DeckEntity)
}

@Dao
interface CardDao {
    @Query(
        """
        SELECT c.*, n.id AS note_id, n.noteTypeId AS note_noteTypeId, n.fields AS note_fields, n.tags AS note_tags,
               n.updatedAt AS note_updatedAt, n.deleted AS note_deleted
        FROM cards c JOIN notes n ON n.id = c.noteId
        WHERE c.deckId = :deckId AND c.deleted = 0 ORDER BY c.updatedAt DESC
        """,
    )
    fun observeInDeck(deckId: String): Flow<List<CardWithNote>>

    /** Learning/relearning cards due soon and review cards due now, earliest first. */
    @Query(
        """
        SELECT c.*, n.id AS note_id, n.noteTypeId AS note_noteTypeId, n.fields AS note_fields, n.tags AS note_tags,
               n.updatedAt AS note_updatedAt, n.deleted AS note_deleted
        FROM cards c JOIN notes n ON n.id = c.noteId
        WHERE c.deckId = :deckId AND c.deleted = 0 AND c.lastReview IS NOT NULL
          AND c.due <= CASE WHEN c.state = 'REVIEW' THEN :now ELSE :learnAhead END
        ORDER BY c.due LIMIT :limit
        """,
    )
    suspend fun due(deckId: String, now: Long, learnAhead: Long, limit: Int): List<CardWithNote>

    @Query(
        """
        SELECT c.*, n.id AS note_id, n.noteTypeId AS note_noteTypeId, n.fields AS note_fields, n.tags AS note_tags,
               n.updatedAt AS note_updatedAt, n.deleted AS note_deleted
        FROM cards c JOIN notes n ON n.id = c.noteId
        WHERE c.deckId = :deckId AND c.deleted = 0 AND c.lastReview IS NULL
        ORDER BY c.updatedAt LIMIT :limit
        """,
    )
    suspend fun newCards(deckId: String, limit: Int): List<CardWithNote>

    /** Cards of the deck first reviewed since [dayStart] (counts against the new-card limit). */
    @Query(
        """
        SELECT COUNT(*) FROM cards c WHERE c.deckId = :deckId
          AND (SELECT MIN(r.reviewedAt) FROM review_logs r WHERE r.cardId = c.id) >= :dayStart
        """,
    )
    suspend fun introducedSince(deckId: String, dayStart: Long): Int

    @Upsert
    suspend fun upsert(card: CardEntity)

    @Query("UPDATE cards SET deleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun delete(id: String, now: Long = System.currentTimeMillis())
}

@Dao
interface NoteDao {
    @Upsert
    suspend fun upsert(note: NoteEntity)
}

@Dao
interface ReviewDao {
    @Insert
    suspend fun insert(log: ReviewLogEntity)

    @Insert
    suspend fun insertAttempt(attempt: InkAttemptEntity)

    @Query("SELECT * FROM review_logs WHERE cardId = :cardId ORDER BY reviewedAt")
    suspend fun forCard(cardId: String): List<ReviewLogEntity>

    @Query("SELECT COUNT(*) FROM review_logs WHERE reviewedAt >= :since")
    fun observeCountSince(since: Long): Flow<Int>
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
