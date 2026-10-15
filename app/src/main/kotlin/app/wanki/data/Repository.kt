package app.wanki.data

import androidx.room.withTransaction
import app.wanki.ink.InkCodec
import app.wanki.ink.InkDrawing
import app.wanki.ink.InkStroke
import app.wanki.srs.CardState
import app.wanki.srs.FsrsScheduler
import app.wanki.srs.Rating
import app.wanki.srs.SrsState
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZoneId

/** All persistence used by the screens. */
class Repository(private val db: WankiDatabase) {
    private val decks = db.decks()
    private val cards = db.cards()
    private val notes = db.notes()
    private val reviews = db.reviews()
    private val notebooks = db.notebooks()
    private val textNotes = db.textNotes()

    // --- decks & cards ---------------------------------------------------------------------

    fun observeDecks(now: Long = System.currentTimeMillis()): Flow<List<DeckWithCounts>> = decks.observeWithCounts(now)
    fun observeDeck(id: String) = decks.observe(id)
    fun observeCards(deckId: String) = cards.observeInDeck(deckId)

    suspend fun createDeck(name: String, language: String, mode: AnswerMode): DeckEntity {
        val d = DeckEntity(name = name.trim(), language = language, defaultAnswerMode = mode.name)
        decks.upsert(d)
        return d
    }

    suspend fun addCard(deckId: String, front: String, back: String, mode: AnswerMode, tags: String = "") {
        val note = NoteEntity.basic(front.trim(), back.trim(), tags)
        db.withTransaction {
            notes.upsert(note)
            cards.upsert(CardEntity(noteId = note.id, deckId = deckId, answerMode = mode.name))
        }
    }

    suspend fun setAnswerMode(card: CardEntity, mode: AnswerMode) =
        cards.upsert(card.copy(answerMode = mode.name, updatedAt = System.currentTimeMillis()))

    suspend fun deleteCard(id: String) = cards.delete(id)

    /** Due learning and review cards first, then new cards within the deck's daily limit. */
    suspend fun reviewQueue(deckId: String, now: Long = System.currentTimeMillis()): List<CardWithNote> {
        val deck = decks.get(deckId) ?: return emptyList()
        val due = cards.due(deckId, now, now + LEARN_AHEAD_MS, 500)
        val introduced = cards.introducedSince(deckId, startOfToday())
        val fresh = cards.newCards(deckId, (deck.newPerDay - introduced).coerceAtLeast(0))
        return due + fresh
    }

    suspend fun deck(id: String) = decks.get(id)

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

    /** Schedules the card, writes the review log and the handwriting attempt in one transaction. */
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
        now: Long = System.currentTimeMillis(),
    ): CardEntity {
        val next = scheduler.review(srsState(card), rating, now, card.seed)
        val updated = card.copy(
            state = next.state.name, step = next.step, stability = next.stability, difficulty = next.difficulty,
            due = next.due, lastReview = next.lastReview, reps = next.reps, lapses = next.lapses, updatedAt = now,
        )
        val log = ReviewLogEntity(
            cardId = card.id, reviewedAt = now, rating = rating.value, mode = mode.name,
            score = score, beauty = beauty, durationMs = durationMs, usedHint = usedHint,
        )
        db.withTransaction {
            cards.upsert(updated)
            reviews.insert(log)
            if (ink != null && ink.strokes.isNotEmpty()) {
                reviews.insertAttempt(InkAttemptEntity(reviewLogId = log.id, strokes = InkCodec.encode(ink)))
            }
        }
        return updated
    }

    fun observeReviewedToday(): Flow<Int> = reviews.observeCountSince(startOfToday())

    // --- notebooks, diary, board -------------------------------------------------------------

    fun observeNotebooks(kind: NotebookKind) = notebooks.observeByKind(kind.name)
    fun observePages(notebookId: String) = notebooks.observePages(notebookId)
    fun observeDiaryDates(diaryId: String) = notebooks.observeDiaryDates(diaryId)

    suspend fun notebook(id: String) = notebooks.get(id)

    suspend fun createNotebook(title: String, template: PageTemplate, language: String): NotebookEntity {
        val n = NotebookEntity(title = title.trim(), template = template.name, language = language)
        db.withTransaction {
            notebooks.upsert(n)
            notebooks.upsertPage(PageEntity(notebookId = n.id, pageIndex = 0, template = n.template))
        }
        return n
    }

    suspend fun pages(notebookId: String) = notebooks.pages(notebookId)

    suspend fun addPage(notebook: NotebookEntity, template: PageTemplate? = null): PageEntity {
        val index = (notebooks.pages(notebook.id).maxOfOrNull { it.pageIndex } ?: -1) + 1
        val p = PageEntity(notebookId = notebook.id, pageIndex = index, template = (template?.name ?: notebook.template))
        notebooks.upsertPage(p)
        notebooks.touch(notebook.id)
        return p
    }

    suspend fun setPageTemplate(page: PageEntity, template: PageTemplate) =
        notebooks.upsertPage(page.copy(template = template.name, updatedAt = System.currentTimeMillis()))

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

    suspend fun renameNotebook(id: String, title: String) {
        notebooks.get(id)?.let { notebooks.upsert(it.copy(title = title.trim(), updatedAt = System.currentTimeMillis())) }
    }

    suspend fun deleteNotebook(id: String) {
        notebooks.get(id)?.let { notebooks.upsert(it.copy(deleted = true, updatedAt = System.currentTimeMillis())) }
    }

    suspend fun diary(): NotebookEntity = singlePageNotebook(NotebookKind.DIARY, "Дневник", PageTemplate.LINED).first

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

    suspend fun loadInk(pageId: String): List<InkStroke> =
        notebooks.ink(pageId)?.let { runCatching { InkCodec.decode(it.strokes).strokes }.getOrNull() }.orEmpty()

    suspend fun recognizedText(pageId: String): String = notebooks.ink(pageId)?.recognizedText.orEmpty()

    suspend fun saveInk(page: PageEntity, strokes: List<InkStroke>) {
        val old = notebooks.ink(page.id)
        val now = System.currentTimeMillis()
        db.withTransaction {
            notebooks.upsertInk(
                PageInkEntity(
                    pageId = page.id,
                    strokes = InkCodec.encode(InkDrawing(strokes)),
                    recognizedText = old?.recognizedText.orEmpty(),
                    recognizedAt = old?.recognizedAt ?: 0,
                    updatedAt = now,
                ),
            )
            notebooks.upsertPage(page.copy(updatedAt = now))
            notebooks.touch(page.notebookId, now)
        }
    }

    suspend fun setRecognizedText(pageId: String, text: String) =
        notebooks.setRecognizedText(pageId, text, System.currentTimeMillis())

    suspend fun searchPages(query: String) = if (query.isBlank()) emptyList() else notebooks.search(query.trim())

    // --- text notes --------------------------------------------------------------------------

    fun observeTextNotes() = textNotes.observeAll()
    suspend fun textNote(id: String) = textNotes.get(id)
    suspend fun saveTextNote(note: TextNoteEntity) = textNotes.upsert(note.copy(updatedAt = System.currentTimeMillis()))
    suspend fun deleteTextNote(id: String) = textNotes.delete(id)
    suspend fun noteBlocks(noteId: String) = textNotes.blocks(noteId)

    /** Saves a note with its blocks; [TextNoteEntity.body] keeps the plain text (typed + recognized) for search. */
    suspend fun saveNoteWithBlocks(note: TextNoteEntity, blocks: List<NoteBlockEntity>) = db.withTransaction {
        val body = blocks.joinToString("\n") { if (it.type == NoteBlockType.TEXT.name) it.text else it.recognizedText }.trim()
        textNotes.upsert(note.copy(body = body, updatedAt = System.currentTimeMillis()))
        textNotes.upsertBlocks(blocks.mapIndexed { i, b -> b.copy(noteId = note.id, blockIndex = i) })
        textNotes.deleteBlocksExcept(note.id, blocks.map { it.id })
    }

    // --- first run ---------------------------------------------------------------------------

    suspend fun seedIfEmpty() {
        if (decks.count() > 0) return
        val kanji = createDeck("Кандзи · 1 класс (демо)", "ja", AnswerMode.HANDWRITING)
        listOf(
            "день, солнце" to "日", "книга, основа" to "本", "человек" to "人", "гора" to "山", "река" to "川",
            "рот" to "口", "глаз" to "目", "дерево" to "木", "вода" to "水", "огонь" to "火", "луна, месяц" to "月",
            "земля" to "土", "золото, деньги" to "金", "большой" to "大", "маленький" to "小", "верх" to "上",
            "низ" to "下", "середина" to "中", "право" to "右", "лево" to "左", "один" to "一", "два" to "二",
            "три" to "三", "белый" to "白", "камень" to "石", "Япония" to "日本", "горная река" to "山川",
        ).forEach { (front, back) -> addCard(kanji.id, front, back, AnswerMode.HANDWRITING) }

        val kana = createDeck("Хирагана (демо)", "ja", AnswerMode.HANDWRITING)
        listOf(
            "a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お",
            "ka" to "か", "ki" to "き", "ku" to "く", "ke" to "け", "ko" to "こ",
            "sa" to "さ", "shi" to "し", "su" to "す", "se" to "せ", "so" to "そ",
            "ta" to "た", "chi" to "ち", "tsu" to "つ", "te" to "て", "to" to "と",
        ).forEach { (front, back) -> addCard(kana.id, front, back, AnswerMode.HANDWRITING) }

        val words = createDeck("Слова (обычные карточки)", "en", AnswerMode.CLASSIC)
        listOf("cat" to "кошка", "dog" to "собака", "book" to "книга", "water" to "вода", "tree" to "дерево")
            .forEach { (front, back) -> addCard(words.id, back, front, AnswerMode.CLASSIC) }
    }

    companion object {
        /** Learning cards due within this window are shown now, like Anki's learn-ahead. */
        const val LEARN_AHEAD_MS = 20 * 60_000L

        fun startOfToday(): Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
