package app.wanki.data

import androidx.room.withTransaction
import app.wanki.srs.CardState
import app.wanki.srs.FsrsScheduler
import app.wanki.srs.Rating
import app.wanki.srs.SrsState
import app.wanki.texts.FsrsTextPlan
import app.wanki.texts.HifzPlan
import app.wanki.texts.PieceLog
import app.wanki.texts.PieceOutcome
import app.wanki.texts.PieceSplitter
import app.wanki.texts.PlanPiece
import app.wanki.texts.PlanText
import app.wanki.texts.Portions
import app.wanki.texts.ScheduleSwitch
import app.wanki.texts.TextKind
import app.wanki.texts.TextSchedule
import app.wanki.texts.WeakPieces
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId

/** A piece as the editor saves it; [id] null = a new piece, which copies the schedule of [scheduleFrom] (split/join). */
data class EditedPiece(val id: String?, val content: String, val note: String = "", val scheduleFrom: String? = null)

/** One block of today's text work. */
sealed interface TodayItem {
    val pieceIds: List<String>

    /** A new portion: "Сабак" for hifz texts, "Новое" for FSRS ones. */
    data class NewPortion(val text: TextEntity, override val pieceIds: List<String>) : TodayItem
    /** FSRS pieces due now. */
    data class Review(val text: TextEntity, override val pieceIds: List<String>) : TodayItem
    data class Sabqi(val text: TextEntity, override val pieceIds: List<String>) : TodayItem
    /** Today's manzil chunk; it may span several hifz texts. */
    data class Manzil(override val pieceIds: List<String>) : TodayItem
}

data class TextToday(val items: List<TodayItem>) {
    val pieces: Int get() = items.sumOf { it.pieceIds.size }
}

/**
 * Texts learned by heart: pieces, new portions, FSRS and hifz (sabaq/sabqi/manzil) reviews and the
 * recitation log. A day is the local calendar day of `now`; the log is append-only.
 */
class TextRepository(private val db: WankiDatabase, private val settings: Settings) {
    private val dao = db.texts()

    fun observeTexts(): Flow<List<TextEntity>> = dao.observeTexts()
    fun observeText(id: String): Flow<TextEntity?> = dao.observeText(id)
    fun observePieces(textId: String): Flow<List<TextPieceEntity>> = dao.observePieces(textId)
    suspend fun text(id: String): TextEntity? = dao.getText(id)
    suspend fun pieces(textId: String): List<TextPieceEntity> = dao.pieces(textId)

    /** Pieces in the order of [ids]; unknown ids are skipped. */
    suspend fun piecesByIds(ids: List<String>): List<TextPieceEntity> {
        val byId = ids.distinct().chunked(500).flatMap { dao.getPieces(it) }.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    // --- editing -----------------------------------------------------------------------------

    /** Splits [content] into pieces and saves a new text; null when there is no piece (blank or punctuation only). */
    suspend fun createText(
        title: String, language: String, kind: TextKind, schedule: TextSchedule, portionSize: Int, content: String,
    ): TextEntity? {
        val parts = PieceSplitter.split(content, kind)
        if (parts.isEmpty()) return null
        val text = TextEntity(
            title = title.trim().ifBlank { "Текст" }, language = language, kind = kind.name, schedule = schedule.name,
            portionSize = portionSize.coerceAtLeast(1),
        )
        db.withTransaction {
            dao.upsertText(text)
            dao.upsertPieces(parts.mapIndexed { i, s -> TextPieceEntity(textId = text.id, pieceIndex = i, content = s) })
        }
        return text
    }

    /**
     * Saves the editor: title, language, kind, portion size and the pieces in the given order. A piece with
     * an id keeps its row and schedule; a new one copies learnedAt, FSRS and hifz fields and the recitation
     * log of [EditedPiece.scheduleFrom], so a later schedule switch replays the same history; pieces left out
     * are deleted (their recitations stay). A changed schedule goes through [switchSchedule]. An empty piece
     * list leaves the pieces alone (a text never has none).
     */
    suspend fun saveText(text: TextEntity, pieces: List<EditedPiece>) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            val stored = dao.getText(text.id)
            if (pieces.isNotEmpty()) {
                val existing = dao.pieces(text.id).associateBy { it.id }
                val used = HashSet<String>()
                val split = ArrayList<Pair<String, String>>() // new piece id to the piece it was split from
                val rows = pieces.mapIndexed { i, e ->
                    val own = e.id?.takeIf(used::add)?.let(existing::get)
                    val parent = if (own == null) e.scheduleFrom?.let(existing::get) else null
                    val base = when {
                        own != null -> own
                        parent != null -> parent.copy(id = newId()).also { split += it.id to parent.id }
                        else -> TextPieceEntity(textId = text.id, pieceIndex = i, content = e.content)
                    }
                    base.copy(pieceIndex = i, content = e.content, note = e.note, updatedAt = now)
                }
                dao.upsertPieces(rows)
                if (split.isNotEmpty()) {
                    val logs = dao.recitations(text.id).groupBy { it.pieceId }
                    dao.insertRecitations(split.flatMap { (child, parent) ->
                        logs[parent].orEmpty().map { it.copy(id = newId(), pieceId = child) }
                    })
                }
                val kept = rows.mapTo(HashSet()) { it.id }
                existing.keys.filter { it !in kept }.chunked(500).forEach { dao.deletePieces(it) }
            }
            val edited = (stored ?: text).copy(
                title = text.title.trim().ifBlank { "Текст" }, language = text.language, kind = text.kind,
                portionSize = text.portionSize.coerceAtLeast(1), updatedAt = now,
            )
            dao.upsertText(edited)
            if (stored != null && stored.schedule != text.schedule) switchIn(text.id, schedule(text), now)
        }
    }

    /** Soft delete: pieces and recitations stay. */
    suspend fun deleteText(id: String) = updateText(id) { it.copy(deleted = true) }

    /** A paused text gets no new portions; its reviews go on. */
    suspend fun setActive(id: String, active: Boolean) = updateText(id) { it.copy(active = active) }

    private suspend fun updateText(id: String, change: (TextEntity) -> TextEntity) = db.withTransaction {
        dao.getText(id)?.let { dao.upsertText(change(it).copy(updatedAt = System.currentTimeMillis())) }
        Unit
    }

    /**
     * Moves a text to the other schedule without losing history: FSRS → hifz puts recently learned pieces
     * in sabqi and the rest in manzil; hifz → FSRS replays each piece's rated recitations (practice excluded).
     */
    suspend fun switchSchedule(id: String, to: TextSchedule, now: Long = System.currentTimeMillis()) =
        db.withTransaction { switchIn(id, to, now) }

    private suspend fun switchIn(id: String, to: TextSchedule, now: Long) {
        val text = dao.getText(id) ?: return
        if (schedule(text) == to) return
        val pieces = dao.pieces(id)
        val updated = when (to) {
            TextSchedule.HIFZ -> {
                val left = ScheduleSwitch.toHifz(pieces.map { it.plan() }, day(now), settings.sabqiDays)
                pieces.map { it.copy(sabqiLeft = left[it.id] ?: 0, hifzDay = null, updatedAt = now) }
            }
            TextSchedule.FSRS -> {
                val logs = dao.recitations(id).mapNotNull { r ->
                    Rating.entries.firstOrNull { it.value == r.rating }?.let { PieceLog(r.pieceId, r.at, it) }
                }
                val states = ScheduleSwitch.toFsrs(logs, scheduler(), pieces.associate { it.id to it.seed })
                // a piece without a rated log keeps the FSRS fields it has
                pieces.map { p -> states[p.id]?.let { p.withSrs(it, now) } ?: p }
            }
        }
        dao.upsertPieces(updated)
        dao.upsertText(text.copy(schedule = to.name, updatedAt = now))
    }

    // --- learning and reviews ----------------------------------------------------------------

    /** Already known pieces up to [upToIndex] skip new portions: FSRS rates them Easy, hifz puts them in manzil. */
    suspend fun markLearned(textId: String, upToIndex: Int, now: Long = System.currentTimeMillis()) = db.withTransaction {
        val text = dao.getText(textId) ?: return@withTransaction
        val fsrs = schedule(text) == TextSchedule.FSRS
        val scheduler = scheduler()
        val known = dao.pieces(textId).filter { it.learnedAt == null && it.pieceIndex <= upToIndex }.map {
            val p = it.copy(learnedAt = now, updatedAt = now)
            if (fsrs) p.withSrs(scheduler.review(p.srs(), Rating.EASY, now, p.seed), now) else p.copy(sabqiLeft = 0, hifzDay = null)
        }
        dao.upsertPieces(known)
        dao.insertRecitations(logRows(known, RecitationPart.KNOWN, now, 0, 0, emptySet()) { Rating.EASY.value })
    }

    /** A portion recited cleanly: its new pieces become learned and the text gets no other portion today. */
    suspend fun completePortion(textId: String, pieceIds: List<String>, durationMs: Long, now: Long = System.currentTimeMillis()) =
        db.withTransaction {
            val text = dao.getText(textId) ?: return@withTransaction
            val today = day(now)
            val fsrs = schedule(text) == TextSchedule.FSRS
            val scheduler = scheduler()
            val byId = dao.pieces(textId).associateBy { it.id }
            // already learned pieces are skipped, so a repeated call changes nothing
            val learned = pieceIds.distinct().mapNotNull { byId[it] }.filter { it.learnedAt == null }.map {
                val p = it.copy(learnedAt = now, updatedAt = now)
                if (fsrs) p.withSrs(scheduler.review(p.srs(), Rating.GOOD, now, p.seed), now)
                else p.withHifz(HifzPlan.learned(p.plan(), today, settings.sabqiDays), now)
            }
            if (learned.isEmpty()) return@withTransaction
            dao.upsertPieces(learned)
            val part = if (fsrs) RecitationPart.NEW else RecitationPart.SABAQ
            dao.insertRecitations(logRows(learned, part, now, durationMs, 0, emptySet()) { Rating.GOOD.value })
            dao.upsertText(text.copy(lastPortionDay = today, updatedAt = now))
        }

    /** One FSRS review of a piece; Again counts as an error. */
    suspend fun reviewPiece(pieceId: String, rating: Rating, hints: Int, durationMs: Long, now: Long = System.currentTimeMillis()) =
        db.withTransaction {
            val piece = dao.getPiece(pieceId) ?: return@withTransaction
            val next = piece.withSrs(scheduler().review(piece.srs(), rating, now, piece.seed), now)
            dao.upsertPieces(listOf(next))
            val errors = if (rating == Rating.AGAIN) setOf(pieceId) else emptySet()
            dao.insertRecitations(logRows(listOf(next), RecitationPart.REVIEW, now, durationMs, hints, errors) { rating.value })
        }

    /** Today's sabqi of a text; a piece with an error stays in sabqi for a full window again. */
    suspend fun completeSabqi(
        textId: String, pieceIds: List<String>, errorIds: Set<String>, durationMs: Long, now: Long = System.currentTimeMillis(),
    ) = db.withTransaction {
        val today = day(now)
        val byId = dao.pieces(textId).associateBy { it.id }
        val done = pieceIds.distinct().mapNotNull { byId[it] }.map {
            it.withHifz(HifzPlan.afterSabqi(it.plan(), it.id in errorIds, today, settings.sabqiDays), now)
        }
        dao.upsertPieces(done)
        dao.insertRecitations(logRows(done, RecitationPart.SABQI, now, durationMs, 0, errorIds, ::hifzRating))
    }

    /** Today's manzil chunk: a piece with an error goes back to sabqi; the pointer moves past the chunk. */
    suspend fun completeManzil(pieceIds: List<String>, errorIds: Set<String>, durationMs: Long, now: Long = System.currentTimeMillis()) =
        db.withTransaction {
            val today = day(now)
            val texts = dao.texts()
            val all = dao.livePieces()
            val chunk = pieceIds.toSet()
            val byId = all.filter { it.id in chunk }
                .map { it.withHifz(HifzPlan.afterManzil(it.plan(), it.id in errorIds, today, settings.sabqiDays), now) }
                .associateBy { it.id }
            val done = pieceIds.distinct().mapNotNull { byId[it] }
            dao.upsertPieces(done)
            dao.insertRecitations(logRows(done, RecitationPart.MANZIL, now, durationMs, 0, errorIds, ::hifzRating))
            val after = all.map { byId[it.id] ?: it }.groupBy { it.textId }
            val pointer = HifzPlan.nextPointer(texts.map { plan(it, after[it.id].orEmpty()) }, pieceIds)
            // last, so the change is in place before Room tells observers about the commit
            settings.manzilPointer = pointer.orEmpty()
            settings.manzilDoneDay = today
        }

    /** A free recital: logged (it marks weak pieces) but never moves a schedule. */
    suspend fun logPractice(
        textId: String, pieceIds: List<String>, errorIds: Set<String>, hints: Int, durationMs: Long, now: Long = System.currentTimeMillis(),
    ) = db.withTransaction {
        val byId = dao.pieces(textId).associateBy { it.id }
        dao.insertRecitations(logRows(pieceIds.distinct().mapNotNull { byId[it] }, RecitationPart.PRACTICE, now, durationMs, hints, errorIds) { null })
    }

    /** Pieces with repeated recent mistakes over every recitation of the text. */
    suspend fun weakPieces(textId: String): Set<String> =
        WeakPieces.weak(dao.recitations(textId).map { PieceOutcome(it.pieceId, it.at, it.error) })

    // --- today -------------------------------------------------------------------------------

    /** The next new portion of one text; empty when paused, done today or fully learned. */
    suspend fun nextPortion(textId: String, now: Long = System.currentTimeMillis()): List<String> {
        val text = dao.getText(textId)?.takeUnless { it.deleted } ?: return emptyList()
        return Portions.next(plan(text, dao.pieces(textId)), day(now))
    }

    /** New portions (by text position), FSRS reviews, sabqi, then the manzil chunk unless it is done today. */
    suspend fun today(now: Long = System.currentTimeMillis()): TextToday {
        val today = day(now)
        val (texts, pieces) = db.withTransaction { dao.texts() to dao.livePieces().groupBy { it.textId } }
        val plans = texts.map { it to plan(it, pieces[it.id].orEmpty()) }
        val items = buildList {
            plans.forEach { (t, p) -> add(TodayItem.NewPortion(t, Portions.next(p, today))) }
            plans.filter { it.second.schedule == TextSchedule.FSRS }
                .forEach { (t, p) -> add(TodayItem.Review(t, FsrsTextPlan.due(p, now + Repository.LEARN_AHEAD_MS))) }
            plans.filter { it.second.schedule == TextSchedule.HIFZ }
                .forEach { (t, p) -> add(TodayItem.Sabqi(t, HifzPlan.sabqi(p, today))) }
            if (settings.manzilDoneDay != today) {
                val pointer = settings.manzilPointer.ifEmpty { null }
                add(TodayItem.Manzil(HifzPlan.manzilChunk(plans.map { it.second }, pointer, settings.manzilCycleDays, today)))
            }
        }
        return TextToday(items.filter { it.pieceIds.isNotEmpty() })
    }

    /** [today], recomputed whenever texts, pieces or the log change. */
    fun observeToday(): Flow<TextToday> =
        db.invalidationTracker.createFlow("texts", "text_pieces", "recitations").map { today() }

    // --- rows ↔ planner ----------------------------------------------------------------------

    private fun day(ms: Long): Long = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    private fun scheduler() = FsrsScheduler(desiredRetention = settings.desiredRetention.toDouble())

    private fun schedule(t: TextEntity) = TextSchedule.entries.firstOrNull { it.name == t.schedule } ?: TextSchedule.FSRS

    private fun plan(t: TextEntity, pieces: List<TextPieceEntity>) = PlanText(
        id = t.id, schedule = schedule(t), active = t.active, portionSize = t.portionSize, order = t.position,
        lastPortionDay = t.lastPortionDay, pieces = pieces.sortedBy { it.pieceIndex }.map { it.plan() },
    )

    private fun TextPieceEntity.plan() = PlanPiece(id, pieceIndex, learnedAt?.let(::day), due, sabqiLeft, hifzDay)

    private fun TextPieceEntity.withHifz(p: PlanPiece, now: Long) = copy(sabqiLeft = p.sabqiLeft, hifzDay = p.hifzDay, updatedAt = now)

    private fun TextPieceEntity.srs() =
        SrsState(CardState.valueOf(state), step, stability, difficulty, due, lastReview, reps, lapses)

    private fun TextPieceEntity.withSrs(s: SrsState, now: Long) = copy(
        state = s.state.name, step = s.step, stability = s.stability, difficulty = s.difficulty, due = s.due,
        lastReview = s.lastReview, reps = s.reps, lapses = s.lapses, updatedAt = now,
    )

    private fun hifzRating(error: Boolean) = if (error) Rating.AGAIN.value else Rating.GOOD.value

    /** One log row per piece; the session's duration and hints are shared out so their sums stay exact. */
    private fun logRows(
        pieces: List<TextPieceEntity>, part: RecitationPart, now: Long, durationMs: Long, hints: Int,
        errorIds: Set<String>, rating: (error: Boolean) -> Int?,
    ) = pieces.mapIndexed { i, p ->
        val error = p.id in errorIds
        RecitationEntity(
            textId = p.textId, pieceId = p.id, at = now, part = part.name,
            mode = if (part == RecitationPart.KNOWN) MODE_NONE else MODE_SELF,
            rating = rating(error), error = error,
            hints = share(hints.toLong(), pieces.size, i).toInt(), durationMs = share(durationMs, pieces.size, i),
        )
    }

    private fun share(total: Long, n: Int, i: Int): Long = total / n + if (i < total % n) 1 else 0

    companion object {
        const val MODE_SELF = "SELF"
        const val MODE_NONE = "NONE"
    }
}
