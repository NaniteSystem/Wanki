package app.wanki.data

import android.database.sqlite.SQLiteBlobTooBigException
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import app.wanki.anki.CardContent
import app.wanki.anki.CardRenderer
import app.wanki.anki.NoteType
import app.wanki.catalog.CatalogDeck
import app.wanki.catalog.CatalogRow
import app.wanki.ink.InkAffine
import app.wanki.input.PageObject
import app.wanki.input.toEntity
import app.wanki.input.toModel
import app.wanki.ink.InkCodec
import app.wanki.ink.InkRect
import app.wanki.ui.common.PageGeometry
import app.wanki.ink.InkDrawing
import app.wanki.ink.InkStroke
import app.wanki.media.MediaStore
import app.wanki.practice.CharQuality
import app.wanki.practice.WeakChars
import app.wanki.srs.CardState
import app.wanki.srs.FsrsScheduler
import app.wanki.srs.Rating
import app.wanki.srs.SrsState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import java.time.ZoneId

/** Restoring a diary day is refused: that date has been written to since. */
class DayTakenException : IllegalStateException("day taken")

/** All persistence used by the screens. */
class Repository(private val db: WankiDatabase) {
    private val decks = db.decks()
    private val cards = db.cards()
    private val notes = db.notes()
    private val reviews = db.reviews()
    private val notebooks = db.notebooks()
    private val textNotes = db.textNotes()
    private val charScores = db.charScores()
    private val sourceRefs = db.sourceRefs()

    // --- decks & cards ---------------------------------------------------------------------

    /** Decks as a tree with what can be studied today; [now] decides what is due. */
    fun observeDeckTree(now: Long = System.currentTimeMillis()): Flow<DeckTree> =
        combine(decks.observeAll(), cards.observeCounts(now, now + LEARN_AHEAD_MS), reviews.observeToday(startOfToday())) { d, c, t ->
            DeckTree(d, c, t)
        }

    suspend fun deckTree(now: Long = System.currentTimeMillis()): DeckTree =
        DeckTree(decks.all(), cards.counts(now, now + LEARN_AHEAD_MS), reviews.today(startOfToday()))

    fun observeDeck(id: String) = decks.observe(id)
    suspend fun deck(id: String) = decks.get(id)

    suspend fun createDeck(name: String, language: String, mode: AnswerMode, parentId: String? = null): DeckEntity {
        val d = DeckEntity(name = name.trim(), language = language, defaultAnswerMode = mode.name, parentId = parentId)
        decks.upsert(d)
        return d
    }

    /** Finds or creates nested decks by path (Anki's "Parent::Child"); returns the innermost. */
    suspend fun deckByPath(path: List<String>, language: String, mode: AnswerMode): DeckEntity = db.withTransaction {
        var parent: DeckEntity? = null
        for (name in path.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("Колода") }) {
            parent = decks.findChild(parent?.id, name) ?: createDeck(name, language, mode, parent?.id)
        }
        parent!!
    }

    suspend fun updateDeck(deck: DeckEntity) =
        decks.upsert(deck.copy(name = deck.name.trim().ifBlank { "Колода" }, updatedAt = System.currentTimeMillis()))

    suspend fun setCollapsed(id: String, collapsed: Boolean) = decks.setCollapsed(id, collapsed)

    /** Deletes the deck with its subdecks and cards. */
    suspend fun deleteDeck(id: String) = db.withTransaction {
        val ids = DeckTree(decks.all()).subtree(id)
        decks.delete(ids)
        cards.deleteInDecks(ids)
        notes.deleteOrphans()
    }

    /** Sets the deck's default answer mode and switches all its cards to it. */
    suspend fun setDeckMode(deck: DeckEntity, mode: AnswerMode) = db.withTransaction {
        decks.upsert(deck.copy(defaultAnswerMode = mode.name, updatedAt = System.currentTimeMillis()))
        cards.setModeInDeck(deck.id, mode.name)
    }

    // --- ready-made decks ---------------------------------------------------------------------

    fun observeInstalledCatalogDecks(): Flow<List<DeckEntity>> = decks.observeInstalled()

    data class InstallResult(val added: Int, val updated: Int)

    /**
     * Installs or updates a ready-made deck. Note and card ids are derived from the catalog key, so
     * installing again only adds missing cards and refreshes bundled text; schedules are kept, and
     * cards the user deleted or notes the user edited are left alone. Bundled notes are written with
     * updatedAt = 0 ("untouched"); any edit gives them a real timestamp.
     */
    suspend fun installCatalogDeck(deck: CatalogDeck, rows: List<CatalogRow>, onProgress: (Int) -> Unit = {}): InstallResult {
        val target = decks.byCatalogId(deck.id) ?: db.withTransaction {
            deckByPath(deck.path, deck.language, deck.mode).copy(catalogId = deck.id).also { decks.upsert(it) }
        }
        var added = 0
        var updated = 0
        var done = 0
        var position = cards.maxPosition()
        val t0 = System.currentTimeMillis()
        for (chunk in rows.chunked(500)) {
            db.withTransaction {
                val existingNotes = notes.getAll(chunk.map { catalogNoteId(deck.id, it.key) }).associateBy { it.id }
                val existingCards = cards.existing(chunk.map { catalogCardId(deck.id, it.key) }).toSet()
                val newNotes = ArrayList<NoteEntity>()
                val newCards = ArrayList<CardEntity>()
                for (r in chunk) {
                    val noteId = catalogNoteId(deck.id, r.key)
                    val fields = r.front + NoteEntity.FIELD_SEPARATOR + r.back
                    val old = existingNotes[noteId]
                    if (old == null) {
                        newNotes += NoteEntity(id = noteId, fields = fields, tags = r.tags, updatedAt = 0)
                    } else if (old.updatedAt == 0L && (old.fields != fields || old.tags != r.tags)) {
                        newNotes += old.copy(fields = fields, tags = r.tags)
                        updated++
                    }
                    val cardId = catalogCardId(deck.id, r.key)
                    if (cardId !in existingCards) {
                        newCards += CardEntity(
                            id = cardId, noteId = noteId, deckId = target.id, answerMode = deck.mode.name,
                            position = ++position, updatedAt = t0,
                        )
                        added++
                    }
                }
                notes.upsertAll(newNotes)
                cards.upsertAll(newCards)
            }
            done += chunk.size
            onProgress(done)
        }
        decks.get(target.id)?.let { decks.upsert(it.copy(catalogVersion = deck.version, updatedAt = System.currentTimeMillis())) }
        return InstallResult(added, updated)
    }

    fun observeCards(query: CardQuery, now: Long = System.currentTimeMillis()): Flow<List<CardWithNote>> =
        cards.observeQuery(query.select(now).room())

    fun observeCardCount(query: CardQuery, now: Long = System.currentTimeMillis()): Flow<Int> =
        cards.observeCount(query.count(now).room())

    suspend fun cardIds(query: CardQuery, now: Long = System.currentTimeMillis()): List<String> = cards.ids(query.ids(now).room())

    fun observeCard(id: String) = cards.observe(id)
    suspend fun card(id: String) = cards.get(id)

    suspend fun addCard(deckId: String, front: String, back: String, mode: AnswerMode, tags: String = ""): CardEntity {
        val note = NoteEntity.basic(front.trim(), back.trim(), tags.trim())
        return db.withTransaction {
            val card = CardEntity(noteId = note.id, deckId = deckId, answerMode = mode.name, position = cards.maxPosition() + 1)
            notes.upsert(note)
            cards.upsert(card)
            card
        }
    }

    /** [addCard] plus the [ref] of where it was made from (its noteId is replaced by the new card's). */
    suspend fun addCardFromSource(
        deckId: String, front: String, back: String, mode: AnswerMode, ref: SourceRefEntity, tags: String = "",
    ): CardEntity = db.withTransaction {
        val card = addCard(deckId, front, back, mode, tags)
        sourceRefs.insert(ref.copy(noteId = card.noteId))
        card
    }

    suspend fun sourceOf(noteId: String): SourceRefEntity? = sourceRefs.ofNote(noteId)

    suspend fun sourceRef(id: String): SourceRefEntity? = sourceRefs.byId(id)

    /** Masks on [pageId] without a live (not detached) ref: what «Карточки из скотча» would make cards from. */
    suspend fun masksWithoutCards(pageId: String): List<PageObjectEntity> {
        val live = sourceRefs.ofPage(pageId).filter { !it.detached }.mapNotNullTo(HashSet()) { it.objectId }
        return notebooks.objects(pageId).filter { it.kind == PageObjectKind.MASK.name && it.id !in live }
    }

    /**
     * «Карточки из скотча»: one linked CLASSIC card per [masksWithoutCards]; front = the mask token (by ref id, which stays
     * when the mask moves), back = the token plus the recognised text of lines under the mask. Returns how many were made.
     */
    suspend fun addMaskCards(deckId: String, pageId: String): Int = db.withTransaction {
        val masks = masksWithoutCards(pageId).map { it.toModel().bounds() to it.id }
        val lines = notebooks.lines(pageId).sortedBy { it.lineIndex }
        val now = System.currentTimeMillis()
        for ((b, id) in masks) {
            val hint = lines.filter { it.left < b.right && it.right > b.left && it.top < b.bottom && it.bottom > b.top }
                .joinToString("\n") { it.shown }.trim()
            val ref = SourceRefEntity(
                noteId = "", kind = SourceKind.PAGE.name, sourceId = pageId, objectId = id,
                x = b.left, y = b.top, w = b.right - b.left, h = b.bottom - b.top, linked = true, createdAt = now,
            )
            val token = app.wanki.anki.CardHtml.maskToken(ref.id)
            addCardFromSource(deckId, token, if (hint.isEmpty()) token else "$token\n$hint", AnswerMode.CLASSIC, ref)
        }
        masks.size
    }

    fun observeCardsFrom(kind: SourceKind, sourceId: String): Flow<List<CardWithNote>> = sourceRefs.observeCardsFrom(kind.name, sourceId)

    /**
     * Mask refs on [pageId] whose mask is not in [liveMaskIds] become detached and their linked cards are suspended; refs
     * whose mask is back (undo) are attached again and unsuspended only if the detach suspended them.
     */
    suspend fun detachMaskRefs(pageId: String, liveMaskIds: Set<String>) = db.withTransaction {
        val now = System.currentTimeMillis()
        for (r in sourceRefs.ofPage(pageId)) {
            val id = r.objectId ?: continue
            if (id !in liveMaskIds && !r.detached) {
                val suspended = r.linked && sourceRefs.setCardsSuspended(r.noteId, true, now) > 0
                sourceRefs.setDetached(r.id, true, suspended)
            } else if (id in liveMaskIds && r.detached) {
                if (r.suspendedByDetach) sourceRefs.setCardsSuspended(r.noteId, false, now)
                sourceRefs.setDetached(r.id, false, false)
            }
        }
    }

    /** Inside a transaction: the page is gone for good, so every ref on it is detached. */
    private suspend fun detachPageRefs(pageId: String) {
        detachMaskRefs(pageId, emptySet())
        sourceRefs.detachAllOfPage(pageId)
    }

    /** Masks moved between pages keep their cards: refs to [fromPage]'s mask ids (keys of [ids]) go to [toPage]'s (values). */
    suspend fun repointMasks(fromPage: String, toPage: String, ids: Map<String, String>) = db.withTransaction {
        for ((old, new) in ids) sourceRefs.repoint(fromPage, old, toPage, new)
    }

    private suspend fun syncMaskRefs(pageId: String) =
        detachMaskRefs(pageId, notebooks.objects(pageId).filter { it.kind == PageObjectKind.MASK.name }.mapTo(HashSet()) { it.id })

    data class NewCard(val front: String, val back: String, val mode: AnswerMode, val tags: String = "")

    /** Bulk insert (imports) in one transaction; new cards come up in the given order. */
    suspend fun addCards(deckId: String, items: List<NewCard>) = db.withTransaction {
        val t0 = System.currentTimeMillis()
        val p0 = cards.maxPosition()
        for (chunk in items.withIndex().chunked(500)) {
            val ns = chunk.map { (_, it) -> NoteEntity.basic(it.front.trim(), it.back.trim(), it.tags.trim()) }
            notes.upsertAll(ns)
            cards.upsertAll(chunk.mapIndexed { j, (i, it) ->
                CardEntity(noteId = ns[j].id, deckId = deckId, answerMode = it.mode.name, updatedAt = t0, position = p0 + 1 + i)
            })
        }
    }

    /** Saves the card editor: note fields and tags, deck, answer mode and which field is written. */
    suspend fun saveCard(c: CardWithNote, fields: List<String>, tags: String, deckId: String, mode: AnswerMode, writeFieldOrd: Int) {
        val now = System.currentTimeMillis()
        val values = c.note.fields.split(NoteEntity.FIELD_SEPARATOR).toMutableList()
        fields.forEachIndexed { i, f -> if (i < values.size) values[i] = f.trim() else values += f.trim() }
        while (values.size < 2) values += ""
        db.withTransaction {
            notes.upsert(c.note.copy(fields = values.joinToString(NoteEntity.FIELD_SEPARATOR.toString()), tags = tags.trim(), updatedAt = now))
            cards.upsert(c.card.copy(deckId = deckId, answerMode = mode.name, writeFieldOrd = writeFieldOrd, updatedAt = now))
        }
    }

    /** Basic cards: question and answer. */
    suspend fun saveCard(c: CardWithNote, front: String, back: String, tags: String, deckId: String, mode: AnswerMode, writeFieldOrd: Int) =
        saveCard(c, listOf(front, back), tags, deckId, mode, writeFieldOrd)

    // --- note types and imported collections -----------------------------------------------------

    @Volatile
    private var noteTypeCache: Map<String, NoteType>? = null

    /** Every note type by id, the built-in basic one included. */
    suspend fun noteTypes(): Map<String, NoteType> = noteTypeCache
        ?: (db.noteTypes().all().map(NoteType::from) + NoteType.BASIC).associateBy { it.id }.also { noteTypeCache = it }

    suspend fun noteType(id: String): NoteType = noteTypes()[id] ?: NoteType.BASIC

    /** What the card shows and what is written for it. */
    suspend fun content(c: CardWithNote, deckPath: String = ""): CardContent =
        CardRenderer.render(noteType(c.note.noteTypeId), c.note, c.card.templateOrd, c.card.writeFieldOrd, deckPath)

    suspend fun saveNoteTypes(types: List<NoteType>) {
        db.noteTypes().upsertAll(types.map { it.toEntity() })
        noteTypeCache = null
    }

    data class ImportWritten(val cardsAdded: Int, val notesUpdated: Int, val reviews: Int)

    /**
     * Writes one batch of an import. Cards already here are left alone with their schedule and
     * history (importing the same collection twice changes nothing); a note is refreshed only when
     * the incoming copy is newer than the local one, so local edits survive.
     */
    suspend fun writeImported(newNotes: List<NoteEntity>, newCards: List<CardEntity>, newLogs: List<ReviewLogEntity>): ImportWritten =
        db.withTransaction {
            val existingNotes = newNotes.map { it.id }.chunked(500).flatMap { notes.getAll(it) }.associateBy { it.id }
            val existingCards = newCards.map { it.id }.chunked(500).flatMap { cards.existing(it) }.toSet()
            val noteWrites = newNotes.filter { n -> existingNotes[n.id].let { it == null || n.updatedAt > it.updatedAt } }
            val cardWrites = newCards.filter { it.id !in existingCards }
            val added = cardWrites.map { it.id }.toSet()
            val logWrites = newLogs.filter { it.cardId in added }
            noteWrites.chunked(500).forEach { notes.upsertAll(it) }
            cardWrites.chunked(500).forEach { cards.upsertAll(it) }
            logWrites.chunked(500).forEach { reviews.insertAll(it) }
            ImportWritten(cardWrites.size, noteWrites.count { it.id in existingNotes }, logWrites.size)
        }

    suspend fun maxCardPosition(): Long = cards.maxPosition()

    suspend fun setAnswerMode(card: CardEntity, mode: AnswerMode) =
        cards.upsert(card.copy(answerMode = mode.name, updatedAt = System.currentTimeMillis()))

    suspend fun setAnswerMode(ids: List<String>, mode: AnswerMode) = ids.chunked(500).forEach { cards.setMode(it, mode.name) }
    suspend fun setSuspended(ids: List<String>, suspended: Boolean) = ids.chunked(500).forEach { cards.setSuspended(it, suspended) }
    suspend fun buryUntilTomorrow(ids: List<String>) = ids.chunked(500).forEach { cards.bury(it, startOfTomorrow()) }
    suspend fun resetCards(ids: List<String>) = ids.chunked(500).forEach { cards.reset(it) }
    suspend fun moveCards(ids: List<String>, deckId: String) = ids.chunked(500).forEach { cards.move(it, deckId) }

    suspend fun deleteCards(ids: List<String>) = db.withTransaction {
        ids.chunked(500).forEach { cards.delete(it) }
        notes.deleteOrphans()
    }

    suspend fun deleteCard(id: String) = deleteCards(listOf(id))

    /** Review history of a card, oldest first, with the handwriting of each written answer. */
    suspend fun cardHistory(cardId: String): List<Pair<ReviewLogEntity, InkAttemptEntity?>> {
        val logs = reviews.forCard(cardId)
        val attempts = logs.map { it.id }.chunked(500).flatMap { reviews.attempts(it) }.associateBy { it.reviewLogId }
        return logs.map { it to attempts[it.id] }
    }

    /**
     * Learning cards due within the learn-ahead window, then due reviews and new cards within
     * the daily limits of the deck and its subdecks.
     */
    suspend fun reviewQueue(deckId: String, now: Long = System.currentTimeMillis()): List<CardWithNote> {
        val tree = deckTree(now)
        val ids = tree.subtree(deckId)
        if (ids.isEmpty()) return emptyList()
        val learning = ids.chunked(500).flatMap { cards.dueLearning(it, now, now + LEARN_AHEAD_MS) }.sortedBy { it.card.due }
        val due = tree.reviewQuota(deckId).flatMap { (id, n) -> cards.dueReviews(id, now, n) }.sortedBy { it.card.due }
        val fresh = tree.newQuota(deckId).flatMap { (id, n) -> cards.newCards(id, now, n) }
        return learning + due + fresh
    }

    fun scheduler(retention: Double) = FsrsScheduler(desiredRetention = retention)

    fun srsState(c: CardEntity) = SrsState(
        state = CardState.valueOf(c.state),
        step = c.step,
        stability = c.stability,
        difficulty = c.difficulty,
        due = c.due,
        lastReview = c.lastReview,
        reps = c.reps,
        lapses = c.lapses,
    )

    /** Handwriting result of one written character. */
    data class CharScore(val glyph: String, val score: Double?, val beauty: Double?)

    /** Schedules the card, writes the review log, handwriting attempt and per-character scores in one transaction. */
    suspend fun answer(
        card: CardEntity,
        rating: Rating,
        scheduler: FsrsScheduler,
        mode: AnswerMode,
        score: Double?,
        beauty: Double?,
        durationMs: Long,
        usedHint: Boolean,
        ink: InkDrawing?,
        language: String = "",
        charScores: List<CharScore> = emptyList(),
        now: Long = System.currentTimeMillis(),
    ): Answered {
        val next = scheduler.review(srsState(card), rating, now, card.seed)
        val updated = card.copy(
            state = next.state.name, step = next.step, stability = next.stability, difficulty = next.difficulty,
            due = next.due, lastReview = next.lastReview, reps = next.reps, lapses = next.lapses, updatedAt = now,
        )
        val log = ReviewLogEntity(
            cardId = card.id, reviewedAt = now, rating = rating.value, mode = mode.name,
            score = score, beauty = beauty, durationMs = durationMs, usedHint = usedHint,
            cardState = if (card.isNew) STATE_NEW else card.state,
        )
        db.withTransaction {
            cards.upsert(updated)
            reviews.insert(log)
            if (ink != null && ink.strokes.isNotEmpty()) {
                reviews.insertAttempt(InkAttemptEntity(reviewLogId = log.id, strokes = InkCodec.encode(ink)))
            }
            if (charScores.isNotEmpty()) {
                this@Repository.charScores.insert(charScores.map {
                    CharScoreEntity(
                        glyph = it.glyph, language = language, at = now, score = it.score, beauty = it.beauty,
                        source = ScoreSource.REVIEW.name, cardId = card.id, reviewLogId = log.id,
                    )
                })
            }
        }
        return Answered(card, updated, log.id)
    }

    data class Answered(val before: CardEntity, val after: CardEntity, val logId: String)

    /** Reverts an answer: the card gets its previous schedule back and the log entry disappears. */
    suspend fun undo(a: Answered) = db.withTransaction {
        cards.upsert(a.before.copy(updatedAt = System.currentTimeMillis()))
        reviews.deleteAttempts(a.logId)
        charScores.deleteForLog(a.logId)
        reviews.deleteLog(a.logId)
    }

    fun observeReviewedToday(): Flow<Int> = reviews.observeCountSince(startOfToday())

    // --- handwriting practice ----------------------------------------------------------------

    /** Every practised or reviewed character with its recent handwriting quality, weakest first. */
    suspend fun charQuality(): List<CharQuality> = WeakChars.quality(charScores.recent())

    /** Changes whenever a handwriting result is stored, to refresh the practice queue. */
    fun observeCharScoreCount(): Flow<Int> = charScores.observeCount()

    suspend fun charHistory(glyph: String): List<CharScoreEntity> = charScores.forGlyph(glyph)

    suspend fun savePractice(glyph: String, language: String, score: Double?, beauty: Double?) =
        charScores.insert(listOf(CharScoreEntity(
            glyph = glyph, language = language, at = System.currentTimeMillis(), score = score, beauty = beauty,
            source = ScoreSource.PRACTICE.name,
        )))

    // --- statistics --------------------------------------------------------------------------

    data class Stats(
        val perDay: Map<Long, DayCount>,
        val forecast: List<Int>,
        val states: List<CardStateCount>,
        val chars: List<CharQuality>,
        /** Share of review cards remembered over the last 30 days; null without data. */
        val retention: Double?,
        val retentionN: Int,
        val beautyPerDay: List<DayBeauty>,
        val today: Long,
    )

    /** Everything the statistics screen shows; days are local epoch days. */
    suspend fun stats(days: Int = 140, forecastDays: Int = 30): Stats {
        val zone = ZoneId.systemDefault()
        val offset = zone.rules.getOffset(java.time.Instant.now()).totalSeconds * 1000L
        val today = LocalDate.now(zone).toEpochDay()
        val since = LocalDate.now(zone).minusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val perDay = reviews.perDay(since, offset).associateBy { it.day }
        val until = LocalDate.now(zone).plusDays(forecastDays.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val due = cards.duePerDay(until, offset)
        val forecast = IntArray(forecastDays)
        for (d in due) forecast[(d.day - today).toInt().coerceIn(0, forecastDays - 1)] += d.n // overdue counts as today
        val month = LocalDate.now(zone).minusDays(30).atStartOfDay(zone).toInstant().toEpochMilli()
        val r = reviews.retention(month)
        val halfYear = LocalDate.now(zone).minusDays(182).atStartOfDay(zone).toInstant().toEpochMilli()
        return Stats(
            perDay = perDay,
            forecast = forecast.toList(),
            states = cards.stateCounts(),
            chars = charQuality(),
            retention = if (r.n > 0) (r.ok ?: 0).toDouble() / r.n else null,
            retentionN = r.n,
            beautyPerDay = charScores.beautyPerDay(halfYear, offset),
            today = today,
        )
    }

    // --- notebooks, diary, board -------------------------------------------------------------

    fun observeNotebooks(kind: NotebookKind) = notebooks.observeByKind(kind.name)
    fun observePages(notebookId: String) = notebooks.observePages(notebookId)
    fun observeDiaryDates(diaryId: String) = notebooks.observeDiaryDates(diaryId)

    suspend fun notebook(id: String) = notebooks.get(id)

    suspend fun createNotebook(
        title: String, template: PageTemplate, language: String,
        pageSize: PageSize = PageSize.A4, landscape: Boolean = false,
    ): NotebookEntity {
        val n = NotebookEntity(title = title.trim(), template = template.name, language = language, pageSize = pageSize.name, landscape = landscape)
        db.withTransaction {
            notebooks.upsert(n)
            notebooks.upsertPage(PageEntity(notebookId = n.id, pageIndex = 0, template = n.template, landscape = n.landscape))
        }
        return n
    }

    suspend fun pages(notebookId: String) = notebooks.pages(notebookId)

    /** A page by id, trashed or not. */
    suspend fun page(id: String) = notebooks.page(id)

    suspend fun addPage(notebook: NotebookEntity, template: PageTemplate? = null): PageEntity {
        val index = (notebooks.pages(notebook.id).maxOfOrNull { it.pageIndex } ?: -1) + 1
        val p = PageEntity(notebookId = notebook.id, pageIndex = index, template = (template?.name ?: notebook.template), landscape = notebook.landscape)
        notebooks.upsertPage(p)
        notebooks.touch(notebook.id)
        return p
    }

    // pages are updated column by column: the editor's copy of a page may be stale
    suspend fun setPageTemplate(page: PageEntity, template: PageTemplate) = db.withTransaction {
        insertPageIfMissing(page)
        notebooks.setPageTemplate(page.id, template.name, System.currentTimeMillis())
    }

    /** Turns one page; its strokes stay where they are (world coordinates). */
    suspend fun setPageLandscape(page: PageEntity, landscape: Boolean) = db.withTransaction {
        insertPageIfMissing(page)
        notebooks.setPageLandscape(page.id, landscape, System.currentTimeMillis())
    }

    /**
     * Stores the editor's page if its row is missing. A diary page removed by a restore of its day is not
     * brought back as a second page for that date: [DayTakenException].
     */
    private suspend fun insertPageIfMissing(page: PageEntity) {
        if (page.date != null && notebooks.page(page.id) == null && notebooks.pageForDate(page.notebookId, page.date) != null) {
            throw DayTakenException()
        }
        notebooks.insertPageIfMissing(page)
    }

    /** Remembers a page's zoom and pan (does not count as an edit). */
    suspend fun setPageView(pageId: String, scale: Float, x: Float, y: Float) =
        notebooks.setPageView(pageId, scale, x, y)

    suspend fun createBoard(title: String): NotebookEntity {
        val n = NotebookEntity(title = title.trim().ifBlank { "Доска" }, kind = NotebookKind.BOARD.name, template = PageTemplate.DOTS.name)
        db.withTransaction {
            notebooks.upsert(n)
            notebooks.upsertPage(PageEntity(notebookId = n.id, pageIndex = 0, template = n.template))
        }
        return n
    }

    /** A board is a notebook with a single infinite page. */
    suspend fun boardPage(boardId: String): Pair<NotebookEntity, PageEntity>? {
        val n = notebooks.get(boardId) ?: return null
        val page = notebooks.pages(n.id).firstOrNull()
            ?: PageEntity(notebookId = n.id, pageIndex = 0, template = n.template).also { notebooks.upsertPage(it) }
        return n to page
    }

    suspend fun renameNotebook(id: String, title: String) = notebooks.rename(id, title.trim(), System.currentTimeMillis())

    suspend fun renameNote(id: String, title: String) = textNotes.rename(id, title.trim(), System.currentTimeMillis())

    suspend fun setNotebookSettings(id: String, title: String, language: String, template: PageTemplate, pageSize: PageSize, landscape: Boolean) =
        notebooks.setSettings(id, title.trim(), language, template.name, pageSize.name, landscape, System.currentTimeMillis())

    suspend fun setPinned(kind: TrashKind, id: String, pinned: Boolean) = when (kind) {
        TrashKind.NOTEBOOK, TrashKind.BOARD -> notebooks.setPinned(id, pinned)
        TrashKind.NOTE -> textNotes.setPinned(id, pinned)
        else -> throw IllegalArgumentException("$kind cannot be pinned")
    }

    // --- duplicates --------------------------------------------------------------------------

    /** A deep copy of a notebook or board: its live pages and their ink get new ids. */
    suspend fun duplicateNotebook(id: String): NotebookEntity = db.withTransaction {
        val src = notebooks.get(id) ?: error("no notebook")
        require(src.kind != NotebookKind.DIARY.name) { "the diary cannot be duplicated" }
        val now = System.currentTimeMillis()
        val copy = src.copy(
            id = newId(), title = src.title + COPY_SUFFIX, updatedAt = now, createdAt = now,
            deleted = false, deletedAt = null, pinned = false,
        )
        notebooks.upsert(copy)
        val old = notebooks.pages(id)
        val pages = old.map { it.copy(id = newId(), notebookId = copy.id, updatedAt = now) }
        notebooks.insertPages(pages)
        notebooks.upsertInks(old.indices.mapNotNull { i -> inkRow(old[i].id)?.copy(pageId = pages[i].id, updatedAt = now) })
        old.indices.forEach { i -> copyObjectsAndLines(old[i].id, pages[i].id, now) }
        copy
    }

    suspend fun duplicateNote(id: String): TextNoteEntity = db.withTransaction {
        val src = textNotes.get(id) ?: error("no note")
        val now = System.currentTimeMillis()
        val copy = src.copy(id = newId(), title = src.title + COPY_SUFFIX, updatedAt = now, createdAt = now, deleted = false, deletedAt = null, pinned = false)
        textNotes.upsert(copy)
        textNotes.insertBlocks(textNotes.blocks(id).map { it.copy(id = newId(), noteId = copy.id, updatedAt = now) })
        copy
    }

    /** Copies a notebook page with its ink right after the original. */
    suspend fun duplicatePage(pageId: String): PageEntity = db.withTransaction {
        val src = notebooks.page(pageId) ?: error("no page")
        requireNumbered(src.notebookId)
        val now = System.currentTimeMillis()
        val copy = src.copy(id = newId(), updatedAt = now, deleted = false, deletedAt = null)
        val live = notebooks.pages(src.notebookId).toMutableList()
        live.add((live.indexOfFirst { it.id == pageId } + 1).coerceAtLeast(0), copy)
        notebooks.insertPages(listOf(copy))
        inkRow(pageId)?.let { notebooks.upsertInk(it.copy(pageId = copy.id, updatedAt = now)) }
        copyObjectsAndLines(pageId, copy.id, now)
        renumber(live)
        notebooks.touch(src.notebookId, now)
        copy.copy(pageIndex = live.indexOfFirst { it.id == copy.id })
    }

    /** Objects get new ids and keep their media; lines keep their keys. */
    private suspend fun copyObjectsAndLines(fromPage: String, toPage: String, now: Long) {
        val objs = notebooks.objects(fromPage)
        if (objs.isNotEmpty()) notebooks.insertObjects(objs.map { it.copy(id = newId(), pageId = toPage, updatedAt = now) })
        val lines = notebooks.lines(fromPage)
        if (lines.isNotEmpty()) notebooks.insertLines(lines.map { it.copy(pageId = toPage, updatedAt = now) })
    }

    // --- pages: insert and move --------------------------------------------------------------

    /** A new page at position [at] (clamped), with the notebook's paper and orientation. */
    suspend fun insertPage(notebookId: String, at: Int): PageEntity = db.withTransaction {
        val nb = requireNumbered(notebookId)
        val live = notebooks.pages(notebookId).toMutableList()
        val pos = at.coerceIn(0, live.size)
        val p = PageEntity(notebookId = notebookId, pageIndex = pos, template = nb.template, landscape = nb.landscape)
        notebooks.insertPages(listOf(p))
        live.add(pos, p)
        renumber(live)
        notebooks.touch(notebookId)
        p
    }

    /** A page to insert with [insertPages]: orientation and background picture or PDF page. */
    data class NewPage(val landscape: Boolean, val backgroundMedia: String?, val backgroundPage: Int?)

    /** Inserts [pages] at position [at] (clamped) in one transaction; the paper is BLANK, indices stay dense. */
    suspend fun insertPages(notebookId: String, at: Int, pages: List<NewPage>): List<PageEntity> = db.withTransaction {
        requireNumbered(notebookId)
        val live = notebooks.pages(notebookId).toMutableList()
        val pos = at.coerceIn(0, live.size)
        val now = System.currentTimeMillis()
        val created = pages.mapIndexed { i, n ->
            PageEntity(
                notebookId = notebookId, pageIndex = pos + i, template = PageTemplate.BLANK.name, updatedAt = now,
                landscape = n.landscape, backgroundMedia = n.backgroundMedia, backgroundPage = n.backgroundPage,
            )
        }
        if (created.isNotEmpty()) {
            notebooks.insertPages(created)
            live.addAll(pos, created)
            renumber(live)
            notebooks.touch(notebookId, now)
        }
        created
    }

    /** Moves a page from position [from] to [to]; indices stay 0..n-1. */
    suspend fun movePage(notebookId: String, from: Int, to: Int) = db.withTransaction {
        requireNumbered(notebookId)
        val live = notebooks.pages(notebookId).toMutableList()
        require(from in live.indices && to in live.indices) { "page position out of range" }
        live.add(to, live.removeAt(from))
        renumber(live)
        notebooks.touch(notebookId)
    }

    /** Pages are inserted, copied and moved only in notebooks; diary pages are keyed by date, a board has one page. */
    private suspend fun requireNumbered(notebookId: String): NotebookEntity {
        val nb = notebooks.get(notebookId) ?: error("no notebook")
        require(nb.kind == NotebookKind.NOTEBOOK.name) { "pages of a ${nb.kind} are not ordered" }
        return nb
    }

    /** Rewrites pageIndex to the list order. */
    private suspend fun renumber(pages: List<PageEntity>) =
        pages.forEachIndexed { i, p -> notebooks.setPageIndex(p.id, i) }

    // --- trash -------------------------------------------------------------------------------

    /**
     * Moves an item to the trash. A notebook, board or note is only flagged (its pages stay as they are);
     * a page or diary day is flagged on its own row. The last page of a notebook cannot be deleted.
     */
    suspend fun delete(kind: TrashKind, id: String) = db.withTransaction {
        val now = System.currentTimeMillis()
        when (kind) {
            TrashKind.NOTEBOOK, TrashKind.BOARD -> notebooks.trash(id, now)
            TrashKind.NOTE -> textNotes.trash(id, now)
            TrashKind.PAGE, TrashKind.DIARY_DAY -> {
                val page = notebooks.page(id) ?: return@withTransaction
                if (page.deleted) return@withTransaction
                val nb = notebooks.get(page.notebookId)
                val numbered = kind == TrashKind.PAGE && nb?.kind == NotebookKind.NOTEBOOK.name
                if (numbered && notebooks.pages(page.notebookId).size <= 1) throw IllegalStateException("last page")
                notebooks.trashPage(id, now)
                if (numbered) renumber(notebooks.pages(page.notebookId))
                notebooks.touch(page.notebookId, now)
            }
        }
    }

    /** Brings an item back. Restoring a page or day also restores its notebook; restoring twice changes nothing. */
    suspend fun restore(kind: TrashKind, id: String) = db.withTransaction {
        when (kind) {
            TrashKind.NOTEBOOK, TrashKind.BOARD -> notebooks.untrash(id)
            TrashKind.NOTE -> textNotes.untrash(id)
            TrashKind.PAGE, TrashKind.DIARY_DAY -> {
                val page = notebooks.page(id) ?: return@withTransaction
                if (!page.deleted) return@withTransaction
                val nb = notebooks.get(page.notebookId) ?: return@withTransaction
                if (page.date != null) {
                    val taken = notebooks.pageForDate(nb.id, page.date)
                    if (taken != null) {
                        // written means ink or an object (a picture or text block alone counts too)
                        if (notebooks.inkMeta(taken.id) != null || notebooks.hasObjects(taken.id)) throw DayTakenException()
                        removePage(taken.id)
                    }
                }
                notebooks.untrashPage(id)
                if (nb.deleted) notebooks.untrash(nb.id)
                if (nb.kind == NotebookKind.NOTEBOOK.name) {
                    // back to its old position, indices dense again
                    val live = notebooks.pages(nb.id).filter { it.id != id }.toMutableList()
                    live.add(page.pageIndex.coerceIn(0, live.size), page)
                    renumber(live)
                }
            }
        }
    }

    /**
     * Removes an item that is in the trash for good together with its pages, ink and blocks.
     * A live (or missing) item is left alone; returns whether anything was removed.
     */
    suspend fun purge(kind: TrashKind, id: String): Boolean = db.withTransaction {
        when (kind) {
            TrashKind.NOTEBOOK, TrashKind.BOARD -> {
                if (notebooks.get(id)?.deleted != true) return@withTransaction false
                sourceRefs.pageIdsOf(id).forEach { detachPageRefs(it) }
                notebooks.deleteInkOfNotebook(id)
                notebooks.deleteObjectsOfNotebook(id)
                notebooks.deleteLinesOfNotebook(id)
                notebooks.deletePagesOf(id)
                notebooks.deleteNotebookRow(id)
            }
            TrashKind.NOTE -> {
                if (textNotes.get(id)?.deleted != true) return@withTransaction false
                textNotes.deleteBlocksOf(id)
                textNotes.deleteRow(id)
            }
            TrashKind.PAGE, TrashKind.DIARY_DAY -> {
                if (notebooks.page(id)?.deleted != true) return@withTransaction false
                removePage(id)
            }
        }
        true
    }

    private suspend fun removePage(id: String) {
        detachPageRefs(id)
        notebooks.deleteInk(id)
        notebooks.deleteObjects(id)
        notebooks.deleteLines(id)
        notebooks.deletePageRow(id)
    }

    /** Everything in the trash, newest deletion first. */
    suspend fun trash(): List<TrashItem> {
        val items = mutableListOf<TrashItem>()
        for (n in notebooks.trashedNotebooks()) {
            val kind = when (n.kind) {
                NotebookKind.NOTEBOOK.name -> TrashKind.NOTEBOOK
                NotebookKind.BOARD.name -> TrashKind.BOARD
                else -> continue
            }
            items += TrashItem(kind, n.id, n.title, null, n.deletedAt ?: continue)
        }
        for (n in textNotes.trashed()) items += TrashItem(TrashKind.NOTE, n.id, n.title, null, n.deletedAt ?: continue)
        for (p in notebooks.trashedPages()) {
            val nb = notebooks.get(p.notebookId) ?: continue
            val at = p.deletedAt ?: continue
            items += if (p.date != null) {
                TrashItem(TrashKind.DIARY_DAY, p.id, LocalDate.ofEpochDay(p.date).toString(), nb.title, at)
            } else {
                TrashItem(TrashKind.PAGE, p.id, "Страница ${p.pageIndex + 1}", nb.title, at)
            }
        }
        return items.sortedByDescending { it.deletedAt }
    }

    /** Removes what has been in the trash longer than [TRASH_DAYS]; returns how many items went. */
    suspend fun purgeExpired(now: Long = System.currentTimeMillis()): Int = db.withTransaction {
        val cutoff = now - TRASH_DAYS * 86_400_000L
        val nbs = notebooks.expiredNotebooks(cutoff)
        val notes = textNotes.expired(cutoff)
        val pages = notebooks.expiredPages(cutoff)
        // pages of a purged notebook are already gone: only what was really removed counts
        nbs.count { purge(TrashKind.NOTEBOOK, it.id) } + notes.count { purge(TrashKind.NOTE, it.id) } +
            pages.count { purge(TrashKind.PAGE, it.id) }
    }

    suspend fun diary(): NotebookEntity = singlePageNotebook(NotebookKind.DIARY, "Дневник", PageTemplate.LINED).first

    /** The live page of a diary day, following deletes and restores. */
    fun observeDiaryPage(diaryId: String, day: LocalDate): Flow<PageEntity?> = notebooks.observePageForDate(diaryId, day.toEpochDay())

    suspend fun diaryPage(day: LocalDate): PageEntity {
        val diary = diary()
        val epochDay = day.toEpochDay()
        notebooks.pageForDate(diary.id, epochDay)?.let { return it }
        val p = PageEntity(notebookId = diary.id, pageIndex = epochDay.toInt(), template = diary.template, date = epochDay)
        notebooks.upsertPage(p)
        return p
    }

    private suspend fun singlePageNotebook(kind: NotebookKind, title: String, template: PageTemplate): Pair<NotebookEntity, PageEntity> =
        db.withTransaction {
            val n = notebooks.firstOfKind(kind.name) ?: NotebookEntity(title = title, kind = kind.name, template = template.name)
                .also { notebooks.upsert(it) }
            val page = notebooks.pages(n.id).firstOrNull()
                ?: PageEntity(notebookId = n.id, pageIndex = 0, template = n.template).also {
                    if (kind != NotebookKind.DIARY) notebooks.upsertPage(it)
                }
            n to page
        }

    /**
     * [pageId]'s ink row. A row over the 2 MB a cursor window holds (a dense board) cannot be read in one go: its blob
     * is then read in parts, in one transaction.
     */
    private suspend fun inkRow(pageId: String): PageInkEntity? = try {
        notebooks.ink(pageId)
    } catch (e: SQLiteBlobTooBigException) {
        db.withTransaction {
            val meta = notebooks.inkMeta(pageId) ?: return@withTransaction null
            val blob = ByteArray(meta.size)
            var at = 0
            while (at < blob.size) {
                val part = notebooks.inkPart(pageId, at + 1, minOf(INK_PART, blob.size - at))
                check(part != null && part.isNotEmpty() && at + part.size <= blob.size) { "ink of page $pageId changed while read" }
                part.copyInto(blob, at)
                at += part.size
            }
            PageInkEntity(pageId, blob, meta.recognizedText, meta.recognizedAt, meta.updatedAt)
        }
    }

    suspend fun loadInk(pageId: String): List<InkStroke> =
        inkRow(pageId)?.let { runCatching { InkCodec.decode(it.strokes).strokes }.getOrNull() }.orEmpty()

    /** Like [loadInk], but a blob that fails to decode is reported, so the editor can refuse to overwrite it. */
    suspend fun loadInkResult(pageId: String): InkLoad {
        val row = inkRow(pageId) ?: return InkLoad.Ok(emptyList())
        return try {
            InkLoad.Ok(InkCodec.decode(row.strokes).strokes)
        } catch (e: Exception) {
            InkLoad.Unreadable(e)
        }
    }

    /**
     * Deletes ink rows whose blob decodes to zero strokes (left by versions that kept the row of an emptied page).
     * A blob that fails to decode is kept. Returns the number of deleted rows.
     */
    suspend fun deleteEmptyInk(): Int {
        var deleted = 0
        for (id in notebooks.inkPageIds()) {
            val strokes = inkRow(id)?.let { runCatching { InkCodec.decode(it.strokes).strokes }.getOrNull() } ?: continue
            if (strokes.isEmpty()) { notebooks.deleteInk(id); deleted++ }
        }
        return deleted
    }

    suspend fun recognizedText(pageId: String): String = notebooks.inkText(pageId).orEmpty()

    suspend fun saveInk(page: PageEntity, strokes: List<InkStroke>) =
        saveInkBlob(page, if (strokes.isEmpty()) null else InkCodec.encode(InkDrawing(strokes)))

    /** Stores an already encoded ink blob; null (an emptied page) deletes the ink row. */
    suspend fun saveInkBlob(page: PageEntity, blob: ByteArray?) {
        db.withTransaction {
            insertPageIfMissing(page)
            writeInk(page, blob, System.currentTimeMillis())
        }
    }

    /** Inside a transaction: [blob] becomes [page]'s ink (null deletes the row), keeping its recognised text. */
    private suspend fun writeInk(page: PageEntity, blob: ByteArray?, now: Long) {
        if (blob == null) {
            // an emptied page keeps no ink row, so the diary stops marking its day
            notebooks.deleteInk(page.id)
        } else {
            val old = notebooks.inkMeta(page.id)
            notebooks.upsertInk(
                PageInkEntity(
                    pageId = page.id,
                    strokes = blob,
                    // lines saved before the ink row existed could not store their text: it is written with the row
                    recognizedText = old?.recognizedText ?: joinedText(notebooks.lines(page.id)),
                    recognizedAt = old?.recognizedAt ?: 0,
                    updatedAt = now,
                ),
            )
        }
        notebooks.touchPage(page.id, now)
        notebooks.touch(page.notebookId, now)
    }

    /**
     * «На другую страницу» (Decision 9): adds [strokes] on top of [to]'s ink and [objects] on top of its objects (with
     * new ids, so the source can get the originals back by undo), and stores [fromBlob] (the source page's ink without
     * them; null = none left) as [from]'s and, when given, [fromObjects] as its objects, in one transaction that writes
     * the target first, so everything moved is on at least one page whatever fails. What would fall off [to] is shifted
     * onto it, as a group. A missing or trashed target, or a target whose ink cannot be read (it is never
     * overwritten), throws and changes nothing. Returns what [takeBackFromPage] needs to undo it.
     */
    suspend fun moveToPage(
        from: PageEntity, fromBlob: ByteArray?, to: PageEntity, strokes: List<InkStroke>,
        objects: List<PageObjectEntity> = emptyList(), fromObjects: List<PageObjectEntity>? = null,
    ): MovedInk = db.withTransaction {
        val target = notebooks.page(to.id)?.takeIf { !it.deleted } ?: throw IllegalStateException("page ${to.id} is missing")
        val existing = inkRow(to.id)?.let { InkCodec.decode(it.strokes).strokes }.orEmpty()
        val targetSize = notebooks.get(target.notebookId)?.let { runCatching { PageSize.valueOf(it.pageSize) }.getOrNull() } ?: PageSize.A4
        // an endless target grows to take what arrives: only the width limits it
        val lowest = (existing.mapNotNull { it.bounds()?.bottom } + strokes.mapNotNull { it.bounds()?.bottom } + objects.map { it.toModel().bounds().bottom }).maxOrNull() ?: 0f
        val m = fitInside(strokes, objects.map { it.toModel() }, PageGeometry.bounds(target.landscape, targetSize, lowest))
        val placed = strokes.map { InkCodec.encodeStroke(m.apply(it)) }
        val now = System.currentTimeMillis()
        if (placed.isNotEmpty()) writeInk(target, InkCodec.assemble(existing.map(InkCodec::encodeStroke) + placed), now)
        val z = (notebooks.objects(to.id).maxOfOrNull { it.z } ?: -1) + 1
        val masks = HashMap<String, String>() // old id → new id of moved masks
        val moved = objects.sortedWith(compareBy<PageObjectEntity> { it.z }.thenBy { it.id }).mapIndexed { i, e ->
            e.toModel().transformed(m).copy(id = PageObject.newId(), z = z + i).toEntity(target.id, now)
                .also { if (e.kind == PageObjectKind.MASK.name) masks[e.id] = it.id }
        }
        if (moved.isNotEmpty()) {
            notebooks.insertObjects(moved)
            if (placed.isEmpty()) { notebooks.touchPage(target.id, now); notebooks.touch(target.notebookId, now) }
        }
        // before the source's refs are synced: a moved mask keeps its cards on the target
        for ((old, new) in masks) sourceRefs.repoint(from.id, old, target.id, new)
        insertPageIfMissing(from)
        writeInk(from, fromBlob, now)
        if (fromObjects != null) {
            notebooks.deleteObjects(from.id)
            if (fromObjects.isNotEmpty()) notebooks.insertObjects(fromObjects.map { it.copy(pageId = from.id) })
            syncMaskRefs(from.id)
        }
        // keys of the strokes as they read back, exactly as takeBackFromPage will see them
        MovedInk(if (placed.isEmpty()) emptyList() else InkCodec.decode(InkCodec.assemble(placed)).strokes.map(::keyOf), moved.map { it.id }, masks)
    }

    /**
     * Undoes [moveToPage] on the target: removes one stored stroke per entry of [keys] (a multiset: identical strokes
     * share a key, and only as many as were moved go), the topmost matches first, and the objects [objectIds].
     * Moved masks' refs go back to [fromPageId] ([masks] as [MovedInk.masks]). Returns how many strokes and objects were removed.
     */
    suspend fun takeBackFromPage(
        toPageId: String, keys: List<Long>, objectIds: List<String> = emptyList(), fromPageId: String? = null, masks: Map<String, String> = emptyMap(),
    ): Int = db.withTransaction {
        if (fromPageId != null) for ((old, new) in masks) sourceRefs.repoint(toPageId, new, fromPageId, old)
        val page = notebooks.page(toPageId) ?: return@withTransaction 0
        var removed = if (objectIds.isEmpty()) 0 else notebooks.deleteObjectsById(toPageId, objectIds)
        val row = inkRow(toPageId)
        var strokesRemoved = 0
        if (row != null && keys.isNotEmpty()) {
            val strokes = InkCodec.decode(row.strokes).strokes
            val left = HashMap<Long, Int>().apply { keys.forEach { merge(it, 1, Int::plus) } }
            val kept = ArrayList<InkStroke>(strokes.size)
            for (s in strokes.asReversed()) {
                val k = keyOf(s)
                val n = left[k] ?: 0
                if (n > 0) { left[k] = n - 1; strokesRemoved++ } else kept += s
            }
            if (strokesRemoved > 0) {
                kept.reverse()
                writeInk(page, if (kept.isEmpty()) null else InkCodec.encode(InkDrawing(kept)), System.currentTimeMillis())
            }
        }
        if (objectIds.isNotEmpty()) syncMaskRefs(toPageId)
        if (removed > 0 && strokesRemoved == 0) {
            val now = System.currentTimeMillis()
            notebooks.touchPage(page.id, now)
            notebooks.touch(page.notebookId, now)
        }
        removed += strokesRemoved
        removed
    }

    /**
     * Adds [strokes] on top of [page]'s ink and [objects] (with new ids) on top of its objects, in one transaction (a
     * move undone after its editor closed). An unreadable blob throws and stays.
     */
    suspend fun appendInk(page: PageEntity, strokes: List<InkStroke>, objects: List<PageObject> = emptyList()) = db.withTransaction {
        if (strokes.isEmpty() && objects.isEmpty()) return@withTransaction
        insertPageIfMissing(page)
        val now = System.currentTimeMillis()
        if (strokes.isNotEmpty()) {
            val existing = inkRow(page.id)?.let { InkCodec.decode(it.strokes).strokes }.orEmpty()
            writeInk(page, InkCodec.encode(InkDrawing(existing + strokes)), now)
        }
        if (objects.isNotEmpty()) {
            val z = (notebooks.objects(page.id).maxOfOrNull { it.z } ?: -1) + 1
            notebooks.insertObjects(objects.sortedWith(PageObject.ORDER).mapIndexed { i, o -> o.copy(id = PageObject.newId(), z = z + i).toEntity(page.id, now) })
            notebooks.touchPage(page.id, now)
            notebooks.touch(page.notebookId, now)
        }
    }

    private fun keyOf(s: InkStroke): Long = InkCodec.strokeKey(InkCodec.encodeStroke(s))

    /**
     * The shift that puts [strokes] and [objects] (as a group) inside [page] where they fit; a group wider or taller
     * starts at its edge.
     */
    private fun fitInside(strokes: List<InkStroke>, objects: List<PageObject>, page: InkRect): InkAffine {
        val b = (strokes.mapNotNull { it.bounds() } + objects.map { it.bounds() }).reduceOrNull { x, y -> x.union(y) } ?: return InkAffine.identity
        fun shift(lo: Float, hi: Float, min: Float, max: Float): Float = when {
            hi - lo > max - min || lo < min -> min - lo
            hi > max -> max - hi
            else -> 0f
        }
        val dx = shift(b.left, b.right, page.left, page.right)
        val dy = shift(b.top, b.bottom, page.top, page.bottom)
        return if (dx == 0f && dy == 0f) InkAffine.identity else InkAffine.translate(dx, dy)
    }

    // --- page objects, recognised lines, background -------------------------------------------

    suspend fun pageObjects(pageId: String): List<PageObjectEntity> = notebooks.objects(pageId)

    /** Replaces the page's objects in one transaction and touches the page and its notebook. */
    suspend fun savePageObjects(page: PageEntity, objects: List<PageObjectEntity>) = db.withTransaction {
        val now = System.currentTimeMillis()
        insertPageIfMissing(page)
        notebooks.deleteObjects(page.id)
        if (objects.isNotEmpty()) notebooks.insertObjects(objects.map { it.copy(pageId = page.id) })
        syncMaskRefs(page.id)
        notebooks.touchPage(page.id, now)
        notebooks.touch(page.notebookId, now)
    }

    suspend fun pageLines(pageId: String): List<PageLineEntity> = notebooks.lines(pageId)

    /** Replaces the page's lines (corrections come from the caller) and rewrites the searchable text from what is shown, or [searchText] when the caller keeps old text for lines that failed. */
    suspend fun saveLines(pageId: String, lines: List<PageLineEntity>, searchText: String? = null) = db.withTransaction {
        notebooks.deleteLines(pageId)
        if (lines.isNotEmpty()) notebooks.insertLines(lines.map { it.copy(pageId = pageId) })
        notebooks.setRecognizedText(pageId, searchText ?: joinedText(lines), System.currentTimeMillis())
    }

    /** Sets (or with null resets) the user's correction of one line; the searchable text follows. */
    suspend fun setLineCorrection(pageId: String, lineKey: Long, text: String?, searchText: String? = null) = db.withTransaction {
        val lines = notebooks.lines(pageId)
        val line = lines.firstOrNull { it.lineKey == lineKey } ?: return@withTransaction
        val now = System.currentTimeMillis()
        val updated = line.copy(corrected = text, updatedAt = now)
        notebooks.setLineCorrection(pageId, lineKey, text, now)
        notebooks.setRecognizedText(pageId, searchText ?: joinedText(lines.map { if (it.lineKey == lineKey) updated else it }), now)
    }

    private fun joinedText(lines: List<PageLineEntity>) = lines.sortedBy { it.lineIndex }.joinToString("\n") { it.shown }

    /** A picture ([pdfPage] null) or a PDF page as the page's background; media null removes it. */
    suspend fun setPageBackground(pageId: String, media: String?, pdfPage: Int?) =
        notebooks.setPageBackground(pageId, media, pdfPage, System.currentTimeMillis())

    /**
     * Deletes `page-*` files nobody uses: not an object's picture and not a page background
     * (pages in the trash still count). Returns how many files went.
     *
     * Files younger than [minAgeMs] stay: a picture just imported ([app.wanki.media.PictureImport]) is written before
     * its object row is saved, or may sit on an editor's redo stack. A file that cannot be deleted is reported by an
     * IOException once all the others were tried, so the daily cleanup shows it and runs again.
     */
    suspend fun collectMediaGarbage(media: MediaStore, now: Long = System.currentTimeMillis(), minAgeMs: Long = MEDIA_MIN_AGE_MS): Int {
        val used = notebooks.referencedMedia().toSet()
        val files = media.dir.listFiles().orEmpty().filter { it.isFile && it.name.startsWith("page-") && it.name !in used }
        var deleted = 0
        val failed = ArrayList<String>()
        for (f in files) {
            if (now - f.lastModified() < minAgeMs) continue
            if (f.delete()) deleted++ else failed += f.name
        }
        if (failed.isNotEmpty()) throw java.io.IOException("не удалось удалить файлы: ${failed.joinToString()}")
        return deleted
    }

    suspend fun setRecognizedText(pageId: String, text: String) =
        notebooks.setRecognizedText(pageId, text, System.currentTimeMillis())

    suspend fun searchPages(query: String, kind: NotebookKind? = null) =
        if (query.isBlank()) emptyList() else notebooks.search(query.trim(), kind?.name)

    // --- text notes --------------------------------------------------------------------------

    fun observeTextNotes() = textNotes.observeAll()
    suspend fun textNote(id: String) = textNotes.get(id)
    suspend fun saveTextNote(note: TextNoteEntity) = textNotes.upsert(note.copy(updatedAt = System.currentTimeMillis()))
    suspend fun noteBlocks(noteId: String) = textNotes.blocks(noteId)

    /** Saves a note with its blocks; [TextNoteEntity.body] keeps the plain text (typed + recognized) for search. */
    suspend fun saveNoteWithBlocks(note: TextNoteEntity, blocks: List<NoteBlockEntity>) = db.withTransaction {
        val body = blocks.joinToString("\n") { if (it.type == NoteBlockType.TEXT.name) it.text else it.recognizedText }.trim()
        textNotes.upsert(note.copy(body = body, updatedAt = System.currentTimeMillis()))
        textNotes.upsertBlocks(blocks.mapIndexed { i, b -> b.copy(noteId = note.id, blockIndex = i) })
        textNotes.deleteBlocksExcept(note.id, blocks.map { it.id })
    }

    companion object {
        /** Learning cards due within this window are shown now, like Anki's learn-ahead. */
        private const val COPY_SUFFIX = " (копия)"

        /** Bytes of an ink blob read at once when the row is too big for one cursor window. */
        private const val INK_PART = 1 shl 20

        const val LEARN_AHEAD_MS = 20 * 60_000L

        /** Unused page media younger than this are kept (see [collectMediaGarbage]). */
        const val MEDIA_MIN_AGE_MS = 24 * 3_600_000L

        fun startOfToday(): Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        fun startOfTomorrow(): Long = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        private fun CardQuery.Sql.room() = SimpleSQLiteQuery(sql, args.toTypedArray())

        fun catalogNoteId(catalogId: String, key: String) = "$catalogId:$key"
        fun catalogCardId(catalogId: String, key: String) = "$catalogId:$key:0"
    }
}

/** What [Repository.moveToPage] put on the target: the moved strokes' keys (a multiset), the new objects' ids and the moved masks (old id → new id). */
data class MovedInk(val keys: List<Long>, val objectIds: List<String>, val masks: Map<String, String> = emptyMap())

/** Result of reading a page's ink: strokes, or the error of a blob that cannot be decoded. */
sealed interface InkLoad {
    data class Ok(val strokes: List<InkStroke>) : InkLoad
    data class Unreadable(val error: Throwable) : InkLoad
}
