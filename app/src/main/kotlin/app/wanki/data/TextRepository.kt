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
import app.wanki.texts.Morphology
import app.wanki.texts.PieceSplitter
import app.wanki.texts.PieceWord
import app.wanki.texts.PieceWords
import app.wanki.texts.PlanPiece
import app.wanki.texts.PlanText
import app.wanki.texts.Portions
import app.wanki.texts.ReadingOverride
import app.wanki.texts.ScheduleSwitch
import app.wanki.texts.TextKind
import app.wanki.texts.TextSchedule
import app.wanki.texts.TextWords
import app.wanki.texts.WeakPieces
import app.wanki.texts.WordHistory
import app.wanki.texts.WordOutcome
import app.wanki.texts.WordStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/** A piece as the editor saves it; [id] null = a new piece, which copies the schedule of [scheduleFrom] (split/join). */
data class EditedPiece(val id: String?, val content: String, val note: String = "", val scheduleFrom: String? = null)

/** A word of a piece: its index in the piece's word list. */
data class WordKey(val pieceId: String, val index: Int)

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
class TextRepository(
    private val db: WankiDatabase,
    private val settings: Settings,
    /** The Japanese analyser; its first use loads a dictionary, so it is only called on [Dispatchers.Default]. */
    private val morphology: () -> Morphology? = { null },
) {
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
        readings: Map<String, ReadingOverride> = emptyMap(),
    ): TextEntity? {
        val parts = PieceSplitter.split(content, kind)
        if (parts.isEmpty()) return null
        val text = TextEntity(
            title = title.trim().ifBlank { "Текст" }, language = language, kind = kind.name, schedule = schedule.name,
            portionSize = portionSize.coerceAtLeast(1), readings = PieceWords.encodeReadings(readings),
        )
        val words = computeWords(parts, language, readings)
        db.withTransaction {
            dao.upsertText(text)
            dao.upsertPieces(parts.mapIndexed { i, s ->
                TextPieceEntity(textId = text.id, pieceIndex = i, content = s, words = words[i])
            })
        }
        return text
    }

    /**
     * Saves the editor: title, language, kind, portion size and the pieces in the given order. A piece with
     * an id keeps its row and schedule; a new one copies learnedAt, FSRS and hifz fields and the recitation
     * log of [EditedPiece.scheduleFrom], so a later schedule switch replays the same history; pieces left out
     * are deleted (their recitations stay). A changed schedule goes through [switchSchedule]. An empty piece
     * list leaves the pieces alone (a text never has none). [readings] null keeps the stored overrides. Each
     * piece's words are recomputed (outside the transaction) when its content, the language or the readings
     * changed; a piece whose words changed loses its word history.
     */
    suspend fun saveText(text: TextEntity, pieces: List<EditedPiece>, readings: Map<String, ReadingOverride>? = null) {
        val now = System.currentTimeMillis()
        val before = dao.getText(text.id)
        val readingsCode = readings?.let(PieceWords::encodeReadings) ?: before?.readings.orEmpty()
        val sameSetup = before != null && before.language == text.language && before.readings == readingsCode
        val known = if (sameSetup) dao.pieces(text.id).associateBy { it.id } else emptyMap()
        val fresh = if (pieces.isEmpty()) emptyMap() else {
            val todo = pieces.withIndex().filter { (_, e) ->
                val old = e.id?.let(known::get)
                old == null || old.words == null || old.content != e.content
            }
            val computed = computeWords(todo.map { it.value.content }, text.language, PieceWords.decodeReadings(readingsCode))
            todo.mapIndexed { n, (i, _) -> i to computed[n] }.toMap()
        }
        db.withTransaction {
            val stored = dao.getText(text.id)
            // language or readings changed under us (setReadings committed while words were computed): keep the
            // stored ones and leave the words to be recomputed on first use
            val raced = stored != null && before != null && (stored.language != before.language || stored.readings != before.readings)
            val setupChanged = raced || (stored != null && (stored.language != text.language || stored.readings != readingsCode))
            val code = if (raced && readings == null) stored!!.readings else readingsCode
            if (pieces.isEmpty() && setupChanged) {
                val all = dao.pieces(text.id)
                all.map { it.id }.chunked(500).forEach { dao.deleteWordStats(it) }
                dao.upsertPieces(all.map { it.copy(words = null) })
            }
            if (pieces.isNotEmpty()) {
                val existing = dao.pieces(text.id).associateBy { it.id }
                val changed = ArrayList<String>() // pieces whose words differ from the stored ones
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
                    val words = if (raced) null else fresh[i] ?: known.getValue(e.id!!).words
                    if (own != null && own.words != words) changed += own.id
                    base.copy(pieceIndex = i, content = e.content, note = e.note, words = words, updatedAt = now)
                }
                changed.chunked(500).forEach { dao.deleteWordStats(it) }
                dao.upsertPieces(rows)
                if (split.isNotEmpty()) {
                    val logs = dao.recitations(text.id).groupBy { it.pieceId }
                    dao.insertRecitations(split.flatMap { (child, parent) ->
                        logs[parent].orEmpty().map { it.copy(id = newId(), pieceId = child) }
                    })
                }
                val kept = rows.mapTo(HashSet()) { it.id }
                existing.keys.filter { it !in kept }.chunked(500).forEach { dao.deleteWordStats(it); dao.deletePieces(it) }
            }
            val edited = (stored ?: text).copy(
                title = text.title.trim().ifBlank { "Текст" }, language = text.language, kind = text.kind,
                portionSize = text.portionSize.coerceAtLeast(1), readings = code, updatedAt = now,
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

    // --- typing: words, readings, history, drafts ----------------------------------------------

    /** Words of [pieces] by piece id: stored lists are decoded, missing ones computed and stored. */
    suspend fun words(pieces: List<TextPieceEntity>): Map<String, List<PieceWord>> {
        val out = HashMap<String, List<PieceWord>>()
        val missing = ArrayList<TextPieceEntity>()
        for (p in pieces) p.words?.let { out[p.id] = PieceWords.decode(it) } ?: run { missing += p }
        for ((textId, group) in missing.groupBy { it.textId }) {
            val text = dao.getText(textId) ?: continue
            val codes = computeWords(group.map { it.content }, text.language, PieceWords.decodeReadings(text.readings))
            group.forEachIndexed { i, p ->
                dao.setPieceWords(p.id, p.content, codes[i])
                out[p.id] = PieceWords.decode(codes[i])
            }
        }
        return out
    }

    /** Replaces the text's reading overrides; pieces whose words change lose their word history. */
    suspend fun setReadings(textId: String, readings: Map<String, ReadingOverride>) {
        val before = dao.getText(textId) ?: return
        val code = PieceWords.encodeReadings(readings)
        val pieces = dao.pieces(textId)
        val codes = computeWords(pieces.map { it.content }, before.language, readings)
        db.withTransaction {
            val text = dao.getText(textId) ?: return@withTransaction
            val now = System.currentTimeMillis()
            val current = dao.pieces(textId)
            val computed = pieces.indices.associateBy { pieces[it].id }
            val rows = current.map { cur ->
                val i = computed[cur.id]
                // a piece edited or added meanwhile has no words for these readings: null = computed on first use
                cur.copy(words = if (i != null && pieces[i].content == cur.content) codes[i] else null, updatedAt = now)
            }
            val old = current.associate { it.id to it.words }
            rows.filter { old[it.id] != it.words }.map { it.id }
                .chunked(500).forEach { dao.deleteWordStats(it) }
            dao.upsertPieces(rows)
            dao.upsertText(text.copy(readings = code, updatedAt = now))
        }
    }

    suspend fun wordStats(pieceIds: List<String>): Map<WordKey, WordStat> =
        pieceIds.distinct().chunked(500).flatMap { dao.wordStats(it) }
            .associate { WordKey(it.pieceId, it.wordIndex) to WordStat(it.cleanStreak, it.recent, it.cue) }

    /** Applies [WordHistory.after] to the current history of each word. */
    suspend fun recordWords(outcomes: Map<WordKey, WordOutcome>, now: Long = System.currentTimeMillis()) {
        if (outcomes.isEmpty()) return
        db.withTransaction {
            val current = wordStats(outcomes.keys.map { it.pieceId })
            dao.upsertWordStats(outcomes.map { (k, o) ->
                val s = WordHistory.after(current[k] ?: WordStat(), o)
                TextWordStatEntity(k.pieceId, k.index, s.cleanStreak, s.recent, s.cue, now)
            })
        }
    }

    suspend fun draft(key: String): TypingDraftEntity? = dao.draft(key)
    suspend fun saveDraft(d: TypingDraftEntity) = dao.upsertDraft(d)
    suspend fun clearDraft(key: String) = dao.deleteDraft(key)

    /** Encoded word lists of [contents]; the analyser may load its dictionary, so this runs off the main thread. */
    private suspend fun computeWords(contents: List<String>, language: String, readings: Map<String, ReadingOverride>): List<String> =
        withContext(Dispatchers.Default) {
            val breaker = TextWords.breaker(language)
            val morph = morphology()
            contents.map { PieceWords.encode(PieceWords.build(it, language, breaker, morph, readings)) }
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
        dao.insertRecitations(logRows(known, RecitationPart.KNOWN, now, 0, 0, emptySet(), MODE_NONE) { Rating.EASY.value })
    }

    /** A portion recited cleanly: its new pieces become learned and the text gets no other portion today. */
    suspend fun completePortion(
        textId: String, pieceIds: List<String>, durationMs: Long, now: Long = System.currentTimeMillis(), check: String = MODE_SELF,
    ) =
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
            dao.insertRecitations(logRows(learned, part, now, durationMs, 0, emptySet(), check) { Rating.GOOD.value })
            dao.upsertText(text.copy(lastPortionDay = today, updatedAt = now))
        }

    /** One FSRS review of a piece; [error] (an Again, or a typed run with wrong or skipped words) marks the log row. */
    suspend fun reviewPiece(
        pieceId: String, rating: Rating, hints: Int, durationMs: Long, now: Long = System.currentTimeMillis(), check: String = MODE_SELF,
        error: Boolean = rating == Rating.AGAIN,
    ) =
        db.withTransaction {
            val piece = dao.getPiece(pieceId) ?: return@withTransaction
            val next = piece.withSrs(scheduler().review(piece.srs(), rating, now, piece.seed), now)
            dao.upsertPieces(listOf(next))
            val errors = if (error) setOf(pieceId) else emptySet()
            dao.insertRecitations(logRows(listOf(next), RecitationPart.REVIEW, now, durationMs, hints, errors, check) { rating.value })
        }

    /**
     * Today's sabqi of a text; a piece with an error stays in sabqi for a full window again. A piece already
     * recited in sabqi today is skipped, so a double submit neither logs a second error nor moves it twice.
     */
    suspend fun completeSabqi(
        textId: String, pieceIds: List<String>, errorIds: Set<String>, durationMs: Long, now: Long = System.currentTimeMillis(),
        check: String = MODE_SELF,
    ) = db.withTransaction {
        val today = day(now)
        val byId = dao.pieces(textId).associateBy { it.id }
        val doneToday = dao.recitations(textId)
            .filter { it.part == RecitationPart.SABQI.name && day(it.at) == today }.mapTo(HashSet()) { it.pieceId }
        val done = pieceIds.distinct().filter { it !in doneToday }.mapNotNull { byId[it] }.map {
            it.withHifz(HifzPlan.afterSabqi(it.plan(), it.id in errorIds, today, settings.sabqiDays), now)
        }
        if (done.isEmpty()) return@withTransaction
        dao.upsertPieces(done)
        dao.insertRecitations(logRows(done, RecitationPart.SABQI, now, durationMs, 0, errorIds, check, ::hifzRating))
    }

    /**
     * Today's manzil chunk: a piece with an error goes back to sabqi; the pointer moves past the chunk. Once
     * a day: when today's manzil is already done (a double submit), nothing changes.
     */
    suspend fun completeManzil(
        pieceIds: List<String>, errorIds: Set<String>, durationMs: Long, now: Long = System.currentTimeMillis(), check: String = MODE_SELF,
    ) =
        db.withTransaction {
            val today = day(now)
            if (settings.manzilDoneDay == today) return@withTransaction
            val texts = dao.texts()
            val all = dao.livePieces()
            val chunk = pieceIds.toSet()
            val byId = all.filter { it.id in chunk }
                .map { it.withHifz(HifzPlan.afterManzil(it.plan(), it.id in errorIds, today, settings.sabqiDays), now) }
                .associateBy { it.id }
            val done = pieceIds.distinct().mapNotNull { byId[it] }
            dao.upsertPieces(done)
            dao.insertRecitations(logRows(done, RecitationPart.MANZIL, now, durationMs, 0, errorIds, check, ::hifzRating))
            val after = all.map { byId[it.id] ?: it }.groupBy { it.textId }
            val pointer = HifzPlan.nextPointer(texts.map { plan(it, after[it.id].orEmpty()) }, pieceIds)
            // last, so the change is in place before Room tells observers about the commit
            settings.manzilPointer = pointer.orEmpty()
            settings.manzilDoneDay = today
        }

    /** A free recital: logged (it marks weak pieces) but never moves a schedule. */
    suspend fun logPractice(
        textId: String, pieceIds: List<String>, errorIds: Set<String>, hints: Int, durationMs: Long, now: Long = System.currentTimeMillis(),
        check: String = MODE_SELF,
    ) = db.withTransaction {
        val byId = dao.pieces(textId).associateBy { it.id }
        dao.insertRecitations(logRows(pieceIds.distinct().mapNotNull { byId[it] }, RecitationPart.PRACTICE, now, durationMs, hints, errorIds, check) { null })
    }

    /** Pieces with repeated recent mistakes over every recitation of the text, or with words often typed wrong. */
    suspend fun weakPieces(textId: String): Set<String> {
        val recitals = dao.recitations(textId).map { PieceOutcome(it.pieceId, it.at, it.error) }
        val words = wordStats(dao.pieces(textId).map { it.id }).entries.groupBy({ it.key.pieceId }, { it.value })
        return WeakPieces.weak(recitals, words)
    }

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
        errorIds: Set<String>, check: String, rating: (error: Boolean) -> Int?,
    ) = pieces.mapIndexed { i, p ->
        val error = p.id in errorIds
        RecitationEntity(
            textId = p.textId, pieceId = p.id, at = now, part = part.name,
            mode = check,
            rating = rating(error), error = error,
            hints = share(hints.toLong(), pieces.size, i).toInt(), durationMs = share(durationMs, pieces.size, i),
        )
    }

    private fun share(total: Long, n: Int, i: Int): Long = total / n + if (i < total % n) 1 else 0

    companion object {
        const val MODE_SELF = "SELF"
        const val MODE_TYPED = "TYPED"
        const val MODE_NONE = "NONE"
    }
}
