package app.wanki.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TextDao {
    @Query("SELECT * FROM texts WHERE deleted = 0 ORDER BY position")
    fun observeTexts(): Flow<List<TextEntity>>

    @Query("SELECT * FROM texts WHERE deleted = 0 ORDER BY position")
    suspend fun texts(): List<TextEntity>

    @Query("SELECT * FROM texts WHERE id = :id")
    suspend fun getText(id: String): TextEntity?

    @Query("SELECT * FROM texts WHERE id = :id")
    fun observeText(id: String): Flow<TextEntity?>

    @Upsert
    suspend fun upsertText(text: TextEntity)

    @Query("SELECT COUNT(*) FROM texts WHERE deleted = 0")
    suspend fun countTexts(): Int

    @Query("SELECT * FROM text_pieces WHERE textId = :textId ORDER BY pieceIndex")
    suspend fun pieces(textId: String): List<TextPieceEntity>

    @Query("SELECT * FROM text_pieces WHERE textId = :textId ORDER BY pieceIndex")
    fun observePieces(textId: String): Flow<List<TextPieceEntity>>

    @Upsert
    suspend fun upsertPieces(pieces: List<TextPieceEntity>)

    /** At most 500 ids per call (SQLite variable limit). */
    @Query("DELETE FROM text_pieces WHERE id IN (:ids)")
    suspend fun deletePieces(ids: List<String>)

    @Query("SELECT * FROM text_pieces WHERE id = :id")
    suspend fun getPiece(id: String): TextPieceEntity?

    /** At most 500 ids per call (SQLite variable limit). */
    @Query("SELECT * FROM text_pieces WHERE id IN (:ids)")
    suspend fun getPieces(ids: List<String>): List<TextPieceEntity>

    /** Pieces of every live (not deleted) text. */
    @Query(
        """
        SELECT p.* FROM text_pieces p JOIN texts t ON t.id = p.textId
        WHERE t.deleted = 0 ORDER BY t.position, p.pieceIndex
        """,
    )
    suspend fun livePieces(): List<TextPieceEntity>

    @Insert
    suspend fun insertRecitations(recitations: List<RecitationEntity>)

    @Query("SELECT * FROM recitations WHERE textId = :textId ORDER BY at")
    suspend fun recitations(textId: String): List<RecitationEntity>
}
