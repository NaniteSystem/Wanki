package app.wanki.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.wanki.catalog.CatalogDeck
import app.wanki.catalog.CatalogRow
import app.wanki.input.toModel
import app.wanki.srs.Rating
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    private lateinit var db: WankiDatabase
    private lateinit var repo: Repository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, WankiDatabase::class.java).build()
        repo = Repository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun fill(deckId: String, n: Int, prefix: String = "q") =
        repo.addCards(deckId, (1..n).map { Repository.NewCard("$prefix$it", "a$it", AnswerMode.CLASSIC) })

    @Test
    fun saveInkBlobRoundTrip() = runBlocking {
        val page = repo.createNotebook("Blob", app.wanki.data.PageTemplate.LINED, "auto").let { repo.pages(it.id).single() }
        val s = app.wanki.ink.InkStroke(listOf(app.wanki.ink.InkPoint(5f, 5f, 0), app.wanki.ink.InkPoint(40f, 30f, 20)))
        val blob = app.wanki.ink.InkCodec.encode(app.wanki.ink.InkDrawing(listOf(s)))
        repo.saveInkBlob(page, blob)
        val loaded = repo.loadInkResult(page.id)
        assertTrue(loaded is InkLoad.Ok)
        assertEquals(1, (loaded as InkLoad.Ok).strokes.size)
    }

    @Test
    fun saveInkBlobNullDeletesRow() = runBlocking {
        val page = repo.createNotebook("Blob", app.wanki.data.PageTemplate.LINED, "auto").let { repo.pages(it.id).single() }
        val s = app.wanki.ink.InkStroke(listOf(app.wanki.ink.InkPoint(5f, 5f, 0), app.wanki.ink.InkPoint(40f, 30f, 20)))
        repo.saveInkBlob(page, app.wanki.ink.InkCodec.encode(app.wanki.ink.InkDrawing(listOf(s))))
        repo.saveInkBlob(page, null)
        assertNull(db.notebooks().ink(page.id))
    }

    @Test
    fun undecodableInkIsUnreadable() = runBlocking {
        val page = repo.createNotebook("Blob", app.wanki.data.PageTemplate.LINED, "auto").let { repo.pages(it.id).single() }
        db.notebooks().upsertInk(PageInkEntity(pageId = page.id, strokes = byteArrayOf(1, 2, 3)))
        assertTrue(repo.loadInkResult(page.id) is InkLoad.Unreadable)
    }

    @Test
    fun newCardLimitsApplyThroughSubdecks() = runBlocking {
        val parent = repo.createDeck("P", "ja", AnswerMode.CLASSIC).let { it.copy(newPerDay = 5).also { d -> repo.updateDeck(d) } }
        val child = repo.createDeck("C", "ja", AnswerMode.CLASSIC, parent.id)
        fill(parent.id, 2)
        fill(child.id, 8)
        assertEquals(5, repo.reviewQueue(parent.id).size)
        assertEquals(8, repo.reviewQueue(child.id).size)
        // the parent's own cards come first, then the child's, in the order they were added
        assertEquals(listOf("q1", "q2", "q1", "q2", "q3"), repo.reviewQueue(parent.id).map { it.note.field(0) })
    }

    @Test
    fun answeringNewCardsUsesUpTheDailyLimit() = runBlocking {
        val deck = repo.createDeck("D", "ja", AnswerMode.CLASSIC).copy(newPerDay = 2).also { repo.updateDeck(it) }
        fill(deck.id, 5)
        val scheduler = repo.scheduler(0.9)
        repo.reviewQueue(deck.id).take(2).forEach {
            repo.answer(it.card, Rating.GOOD, scheduler, AnswerMode.CLASSIC, null, null, 1000, false, null)
        }
        val row = repo.deckTree().row(deck.id)!!
        assertEquals(0, row.newCount)
        // the two answered cards are now learning and due within the learn-ahead window
        assertEquals(2, row.learnCount)
        assertTrue(repo.reviewQueue(deck.id).none { it.card.isNew })
    }

    @Test
    fun suspendedAndBuriedCardsAreNotStudied() = runBlocking {
        val deck = repo.createDeck("D", "ja", AnswerMode.CLASSIC)
        fill(deck.id, 3)
        val ids = repo.reviewQueue(deck.id).map { it.card.id }
        repo.setSuspended(listOf(ids[0]), true)
        repo.buryUntilTomorrow(listOf(ids[1]))
        assertEquals(listOf(ids[2]), repo.reviewQueue(deck.id).map { it.card.id })
        assertEquals(3, repo.deckTree().row(deck.id)!!.total)
        repo.setSuspended(listOf(ids[0]), false)
        assertEquals(2, repo.reviewQueue(deck.id).size)
    }

    @Test
    fun handwritingScoresAreStoredPerCharacterAndUndone() = runBlocking {
        val deck = repo.createDeck("D", "ja", AnswerMode.HANDWRITING)
        repo.addCard(deck.id, "Япония", "日本", AnswerMode.HANDWRITING)
        val card = repo.reviewQueue(deck.id).single().card
        val answered = repo.answer(
            card, Rating.GOOD, repo.scheduler(0.9), AnswerMode.HANDWRITING, 90.0, 65.0, 4000, false, null,
            language = "ja",
            charScores = listOf(Repository.CharScore("日", 92.0, 60.0), Repository.CharScore("本", 90.0, 70.0)),
        )
        assertEquals(listOf("日", "本"), repo.charQuality().map { it.glyph })
        assertEquals(STATE_NEW, db.reviews().forCard(card.id).single().cardState)
        repo.undo(answered)
        assertTrue(repo.charQuality().isEmpty())
        assertTrue(repo.card(card.id)!!.card.isNew)
    }

    @Test
    fun deckPathsAreReusedAndDeletingRemovesTheSubtree() = runBlocking {
        val a = repo.deckByPath(listOf("Japanese", "Kanji", "N5"), "ja", AnswerMode.HANDWRITING)
        val b = repo.deckByPath(listOf("Japanese", "Kanji", "N5"), "ja", AnswerMode.HANDWRITING)
        assertEquals(a.id, b.id)
        val tree = repo.deckTree()
        assertEquals("Japanese::Kanji::N5", tree.path(a.id))
        fill(a.id, 3)
        val root = tree.roots.single()
        repo.deleteDeck(root.id)
        assertTrue(repo.deckTree().byId.isEmpty())
        for (table in listOf("cards", "notes")) {
            db.query("SELECT COUNT(*) FROM $table WHERE deleted = 0", null).use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test
    fun searchFindsByFieldTagAndCase() = runBlocking {
        val deck = repo.createDeck("D", "ru", AnswerMode.CLASSIC)
        repo.addCard(deck.id, "Кошка", "cat", AnswerMode.CLASSIC, tags = "animals")
        repo.addCard(deck.id, "собака", "dog", AnswerMode.CLASSIC)
        assertEquals(listOf("cat"), repo.observeCards(CardQuery(text = "кошка")).first().map { it.note.field(1) })
        assertEquals(listOf("cat"), repo.observeCards(CardQuery(text = "ANIMALS")).first().map { it.note.field(1) })
        assertEquals(2, repo.observeCardCount(CardQuery(deckIds = listOf(deck.id))).first())
        assertEquals(0, repo.observeCardCount(CardQuery(text = "50%")).first())
    }

    private fun catalogDeck(version: String) = CatalogDeck(
        id = "ja-test", path = listOf("Японский", "Тест"), language = "ja", mode = AnswerMode.HANDWRITING, count = 3,
        file = "", description = "", source = "", license = "", version = version,
    )

    @Test
    fun installingFromTheCatalogBuildsThePathAndIsIdempotent() = runBlocking {
        val rows = listOf(CatalogRow("日", "день", "日", "k"), CatalogRow("一", "один", "一", "k"), CatalogRow("人", "человек", "人", "k"))
        assertEquals(Repository.InstallResult(3, 0), repo.installCatalogDeck(catalogDeck("v1"), rows))
        val tree = repo.deckTree()
        val deck = tree.byId.values.single { it.catalogId == "ja-test" }
        assertEquals("Японский::Тест", tree.path(deck.id))
        assertEquals("v1", deck.catalogVersion)
        assertEquals(listOf("день", "один", "человек"), repo.reviewQueue(deck.id).map { it.note.field(0) })
        assertEquals(Repository.InstallResult(0, 0), repo.installCatalogDeck(catalogDeck("v1"), rows))
        assertEquals(3, repo.deckTree().row(deck.id)!!.total)
    }

    @Test
    fun updatingKeepsSchedulesEditsAndDeletions() = runBlocking {
        val v1 = listOf(CatalogRow("日", "день", "日", ""), CatalogRow("一", "один", "一", ""), CatalogRow("人", "человек", "人", ""))
        repo.installCatalogDeck(catalogDeck("v1"), v1)
        val deckId = repo.deckTree().byId.values.single { it.catalogId == "ja-test" }.id
        val queue = repo.reviewQueue(deckId)
        // answer 日, edit 一, delete 人
        repo.answer(queue[0].card, Rating.GOOD, repo.scheduler(0.9), AnswerMode.HANDWRITING, null, null, 1000, false, null)
        repo.saveCard(queue[1], "один (моё)", "一", "", deckId, AnswerMode.HANDWRITING, 1)
        repo.deleteCard(queue[2].card.id)

        val v2 = listOf(
            CatalogRow("日", "день, солнце", "日", ""), CatalogRow("一", "один, единица", "一", ""),
            CatalogRow("人", "человек, люди", "人", ""), CatalogRow("大", "большой", "大", ""),
        )
        val r = repo.installCatalogDeck(catalogDeck("v2"), v2)
        assertEquals(1, r.added)
        assertEquals(1, r.updated)
        val sun = repo.card(Repository.catalogCardId("ja-test", "日"))!!
        assertEquals("день, солнце", sun.note.field(0))
        assertTrue(!sun.card.isNew)
        assertEquals("один (моё)", repo.card(Repository.catalogCardId("ja-test", "一"))!!.note.field(0))
        assertTrue(repo.card(Repository.catalogCardId("ja-test", "人"))!!.card.deleted)
        assertEquals("v2", repo.deck(deckId)!!.catalogVersion)
    }

    @Test
    fun editingKeepsScheduleAndMovesCards() = runBlocking {
        val d1 = repo.createDeck("D1", "ja", AnswerMode.CLASSIC)
        val d2 = repo.createDeck("D2", "ja", AnswerMode.CLASSIC)
        repo.addCard(d1.id, "q", "a", AnswerMode.CLASSIC)
        val c = repo.reviewQueue(d1.id).single()
        repo.saveCard(c, "q2", "a2", "t1 t2", d2.id, AnswerMode.HANDWRITING, 0)
        val e = repo.card(c.card.id)!!
        assertEquals("q2", e.note.field(0))
        assertEquals("a2", e.note.field(1))
        assertEquals("t1 t2", e.note.tags)
        assertEquals(d2.id, e.card.deckId)
        assertEquals(AnswerMode.HANDWRITING.name, e.card.answerMode)
        assertEquals(0, e.card.writeFieldOrd)
        assertEquals(c.card.position, e.card.position)
    }

    @Test
    fun pageViewSurvivesInkSaveAndTemplateChange() = runBlocking {
        val nb = repo.createNotebook("Тетрадь", PageTemplate.LINED, "ru")
        val page = repo.pages(nb.id).single()
        repo.setPageView(page.id, 2f, -30f, -40f)
        // the editor keeps its own (older) copy of the page and saves with it
        repo.saveInk(page, emptyList())
        repo.setPageTemplate(page, PageTemplate.GRID)
        val stored = repo.pages(nb.id).single()
        assertEquals(2f, stored.viewScale)
        assertEquals(-30f, stored.viewX)
        assertEquals(-40f, stored.viewY)
        assertEquals(PageTemplate.GRID.name, stored.template)
    }

    @Test
    fun firstInkSaveStoresAnUnsavedDiaryPage() = runBlocking {
        val diary = repo.diary()
        val day = java.time.LocalDate.of(2026, 10, 21)
        val page = PageEntity(notebookId = diary.id, pageIndex = day.toEpochDay().toInt(), template = diary.template, date = day.toEpochDay())
        repo.saveInk(page, emptyList())
        assertEquals(listOf(page.id), repo.pages(diary.id).map { it.id })
    }

    private fun stroke() = app.wanki.ink.InkStroke(
        listOf(app.wanki.ink.InkPoint(1f, 1f, 0), app.wanki.ink.InkPoint(5f, 5f, 10)),
    )

    private fun backup() = Backup(
        InstrumentationRegistry.getInstrumentation().targetContext, db,
        Settings(InstrumentationRegistry.getInstrumentation().targetContext),
    )

    @Test
    fun openedDiaryDayIsNotMarked() = runBlocking {
        val diary = repo.diary()
        repo.diaryPage(java.time.LocalDate.of(2026, 10, 21))
        assertTrue(repo.observeDiaryDates(diary.id).first().isEmpty())
    }

    @Test
    fun emptiedDiaryDayIsNotMarked() = runBlocking {
        val diary = repo.diary()
        val day = java.time.LocalDate.of(2026, 10, 21)
        val page = repo.diaryPage(day)
        repo.saveInk(page, listOf(stroke()))
        assertEquals(listOf(day.toEpochDay()), repo.observeDiaryDates(diary.id).first())
        repo.saveInk(page, emptyList())
        assertTrue(repo.observeDiaryDates(diary.id).first().isEmpty())
    }

    @Test
    fun hasContentCountsNotebookInk() = runBlocking {
        assertFalse(backup().hasContent())
        val nb = repo.createNotebook("Тетрадь", PageTemplate.LINED, "ru")
        assertFalse(backup().hasContent())
        repo.saveInk(repo.pages(nb.id).single(), listOf(stroke()))
        assertTrue(backup().hasContent())
    }

    @Test
    fun hasContentCountsNotes() = runBlocking {
        assertFalse(backup().hasContent())
        repo.saveTextNote(TextNoteEntity(title = "Т", body = "текст"))
        assertTrue(backup().hasContent())
    }

    @Test
    fun emptyInkCleanupDeletesOnlyDecodableEmptyRows() = runBlocking {
        val nb = repo.createNotebook("Тетрадь", PageTemplate.LINED, "ru")
        val empty = repo.pages(nb.id).single()
        val full = repo.addPage(nb)
        val broken = repo.addPage(nb)
        val dao = db.notebooks()
        dao.upsertInk(PageInkEntity(empty.id, app.wanki.ink.InkCodec.encode(app.wanki.ink.InkDrawing(emptyList()))))
        dao.upsertInk(PageInkEntity(full.id, app.wanki.ink.InkCodec.encode(app.wanki.ink.InkDrawing(listOf(stroke())))))
        dao.upsertInk(PageInkEntity(broken.id, byteArrayOf(1, 2, 3)))
        assertEquals(1, repo.deleteEmptyInk())
        assertEquals(null, dao.ink(empty.id))
        assertTrue(dao.ink(full.id) != null)
        assertTrue(dao.ink(broken.id) != null)
        assertEquals(0, repo.deleteEmptyInk())
    }

    // --- П2: trash, duplicates, page order -----------------------------------------------------

    private suspend fun inkedNotebook(title: String, pages: Int): NotebookEntity {
        val nb = repo.createNotebook(title, PageTemplate.LINED, "ru")
        repeat(pages - 1) { repo.addPage(nb) }
        repo.pages(nb.id).forEach { repo.saveInk(it, listOf(stroke())) }
        return nb
    }

    private suspend fun ids(nb: NotebookEntity) = repo.pages(nb.id).map { it.id }

    @Test
    fun deleteOpenNotebookThenRestore() = runBlocking {
        val nb = inkedNotebook("Т", 2)
        repo.delete(TrashKind.NOTEBOOK, nb.id)
        assertTrue(repo.observeNotebooks(NotebookKind.NOTEBOOK).first().isEmpty())
        assertEquals(listOf(TrashKind.NOTEBOOK to nb.id), repo.trash().map { it.kind to it.id })
        repo.restore(TrashKind.NOTEBOOK, nb.id)
        val back = repo.observeNotebooks(NotebookKind.NOTEBOOK).first().single()
        assertEquals(2, back.pageCount)
        assertEquals(2, repo.pages(nb.id).count { repo.loadInk(it.id).size == 1 })
        assertTrue(repo.trash().isEmpty())
    }

    @Test
    fun restoreIsIdempotent() = runBlocking {
        val nb = inkedNotebook("Т", 1)
        repo.delete(TrashKind.NOTEBOOK, nb.id)
        repo.restore(TrashKind.NOTEBOOK, nb.id)
        repo.restore(TrashKind.NOTEBOOK, nb.id)
        assertEquals(1, repo.observeNotebooks(NotebookKind.NOTEBOOK).first().size)
        repo.delete(TrashKind.NOTEBOOK, nb.id)
        repo.purge(TrashKind.NOTEBOOK, nb.id)
        repo.restore(TrashKind.NOTEBOOK, nb.id)
        repo.purge(TrashKind.NOTEBOOK, nb.id)
        assertTrue(repo.observeNotebooks(NotebookKind.NOTEBOOK).first().isEmpty())
        assertTrue(repo.trash().isEmpty())
    }

    @Test
    fun purgeExpiredRemovesOnlyOld() = runBlocking {
        val day = 86_400_000L
        val old = inkedNotebook("old", 2)
        val fresh = inkedNotebook("fresh", 1)
        val oldNote = TextNoteEntity(title = "old", body = "")
        val freshNote = TextNoteEntity(title = "fresh", body = "")
        repo.saveNoteWithBlocks(oldNote, listOf(NoteBlockEntity(noteId = oldNote.id, blockIndex = 0, type = NoteBlockType.TEXT.name, text = "a")))
        repo.saveNoteWithBlocks(freshNote, listOf(NoteBlockEntity(noteId = freshNote.id, blockIndex = 0, type = NoteBlockType.TEXT.name, text = "b")))
        val oldPages = ids(old)
        val now = System.currentTimeMillis()
        listOf(TrashKind.NOTEBOOK to old.id, TrashKind.NOTEBOOK to fresh.id, TrashKind.NOTE to oldNote.id, TrashKind.NOTE to freshNote.id)
            .forEach { repo.delete(it.first, it.second) }
        db.openHelper.writableDatabase.apply {
            execSQL("UPDATE notebooks SET deletedAt = ${now - 31 * day} WHERE id = '${old.id}'")
            execSQL("UPDATE notebooks SET deletedAt = ${now - 29 * day} WHERE id = '${fresh.id}'")
            execSQL("UPDATE text_notes SET deletedAt = ${now - 31 * day} WHERE id = '${oldNote.id}'")
            execSQL("UPDATE text_notes SET deletedAt = ${now - 29 * day} WHERE id = '${freshNote.id}'")
        }
        assertEquals(2, repo.purgeExpired(now))
        assertEquals(listOf(fresh.id, freshNote.id).toSet(), repo.trash().map { it.id }.toSet())
        assertEquals(null, repo.notebook(old.id))
        val sdb = db.openHelper.readableDatabase
        fun count(sql: String) = sdb.query(sql).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(0, count("SELECT COUNT(*) FROM pages WHERE notebookId = '${old.id}'"))
        assertEquals(0, count("SELECT COUNT(*) FROM page_ink WHERE pageId IN (${oldPages.joinToString { "'$it'" }})"))
        assertEquals(1, count("SELECT COUNT(*) FROM page_ink"))
        assertEquals(0, count("SELECT COUNT(*) FROM note_blocks WHERE noteId = '${oldNote.id}'"))
        assertEquals(1, count("SELECT COUNT(*) FROM note_blocks"))
    }

    @Test
    fun restorePageRestoresNotebook() = runBlocking {
        val nb = inkedNotebook("Т", 3)
        val (a, b, c) = ids(nb)
        repo.delete(TrashKind.PAGE, b)
        assertEquals(listOf(a, c), ids(nb))
        assertEquals(listOf(0, 1), repo.pages(nb.id).map { it.pageIndex })
        repo.delete(TrashKind.NOTEBOOK, nb.id)
        // the deleted page belongs to the trashed notebook's item now
        assertEquals(listOf(TrashKind.NOTEBOOK), repo.trash().map { it.kind })
        repo.restore(TrashKind.PAGE, b)
        assertEquals(1, repo.observeNotebooks(NotebookKind.NOTEBOOK).first().size)
        assertEquals(listOf(a, b, c), ids(nb))
        assertEquals(listOf(0, 1, 2), repo.pages(nb.id).map { it.pageIndex })
    }

    @Test
    fun restoreDiaryDayRestoresItsDay() = runBlocking {
        val day = java.time.LocalDate.of(2026, 10, 21)
        val page = repo.diaryPage(day)
        repo.saveInk(page, listOf(stroke()))
        repo.delete(TrashKind.DIARY_DAY, page.id)
        assertTrue(repo.observeDiaryDates(repo.diary().id).first().isEmpty())
        assertEquals(TrashKind.DIARY_DAY, repo.trash().single().kind)
        repo.restore(TrashKind.DIARY_DAY, page.id)
        assertEquals(listOf(day.toEpochDay()), repo.observeDiaryDates(repo.diary().id).first())
    }

    @Test
    fun duplicateNotebookIsDeepCopy() = runBlocking {
        val nb = inkedNotebook("Т", 3)
        val copy = repo.duplicateNotebook(nb.id)
        assertEquals("Т (копия)", copy.title)
        val a = repo.pages(nb.id)
        val b = repo.pages(copy.id)
        assertEquals(3, b.size)
        assertTrue((a.map { it.id } + b.map { it.id }).toSet().size == 6)
        assertEquals(a.map { it.pageIndex }, b.map { it.pageIndex })
        b.forEachIndexed { i, p -> assertEquals(repo.loadInk(a[i].id), repo.loadInk(p.id)) }
        repo.saveInk(b[0], listOf(stroke(), stroke()))
        assertEquals(1, repo.loadInk(a[0].id).size)
        assertEquals(2, repo.loadInk(b[0].id).size)
    }

    @Test
    fun duplicatePageInsertsAfter() = runBlocking {
        val nb = inkedNotebook("Т", 3)
        val (a, b, c) = ids(nb)
        val copy = repo.duplicatePage(b)
        assertEquals(listOf(a, b, copy.id, c), ids(nb))
        assertEquals(listOf(0, 1, 2, 3), repo.pages(nb.id).map { it.pageIndex })
        assertEquals(1, repo.loadInk(copy.id).size)
    }

    @Test
    fun duplicateNoteCopiesBlocks() = runBlocking {
        val n = TextNoteEntity(title = "З", body = "t")
        repo.saveNoteWithBlocks(n, listOf(NoteBlockEntity(noteId = n.id, blockIndex = 0, type = NoteBlockType.TEXT.name, text = "t")))
        val copy = repo.duplicateNote(n.id)
        assertEquals("З (копия)", copy.title)
        assertEquals(listOf("t"), repo.noteBlocks(copy.id).map { it.text })
        assertEquals(1, repo.noteBlocks(n.id).size)
    }

    @Test
    fun movePageKeepsDenseIndices() = runBlocking {
        val nb = inkedNotebook("Т", 4)
        val (a, b, c, d) = ids(nb)
        repo.movePage(nb.id, 0, 2)
        assertEquals(listOf(b, c, a, d), ids(nb))
        repo.movePage(nb.id, 3, 1)
        assertEquals(listOf(b, d, c, a), ids(nb))
        assertEquals(listOf(0, 1, 2, 3), repo.pages(nb.id).map { it.pageIndex })
    }

    @Test
    fun insertPageUsesNotebookDefaults() = runBlocking {
        val nb = repo.createNotebook("Т", PageTemplate.GRID, "ru")
        repo.setNotebookSettings(nb.id, "Т2", "en", PageTemplate.GRID, PageSize.A5, true)
        val first = repo.pages(nb.id).single()
        val p = repo.insertPage(nb.id, 0)
        assertTrue(p.landscape)
        assertEquals("GRID", p.template)
        assertEquals(listOf(p.id, first.id), ids(nb))
        assertEquals(listOf(0, 1), repo.pages(nb.id).map { it.pageIndex })
        val saved = repo.notebook(nb.id)!!
        assertEquals("Т2", saved.title)
        assertEquals("A5", saved.pageSize)
    }

    @Test
    fun deletingLastPageIsRefused() = runBlocking {
        val nb = repo.createNotebook("Т", PageTemplate.LINED, "ru")
        val only = repo.pages(nb.id).single()
        val e = runCatching { repo.delete(TrashKind.PAGE, only.id) }.exceptionOrNull()
        assertTrue(e is IllegalStateException)
        assertEquals("last page", e?.message)
        assertEquals(listOf(only.id), ids(nb))
        assertTrue(repo.trash().isEmpty())
    }

    @Test
    fun pinningAndRenaming() = runBlocking {
        val nb = repo.createNotebook("Т", PageTemplate.LINED, "ru")
        repo.setPinned(TrashKind.NOTEBOOK, nb.id, true)
        assertTrue(repo.notebook(nb.id)!!.pinned)
        val n = TextNoteEntity(title = "З", body = "")
        repo.saveTextNote(n)
        repo.setPinned(TrashKind.NOTE, n.id, true)
        repo.renameNote(n.id, " Новая ")
        assertEquals("Новая", repo.textNote(n.id)!!.title)
        assertTrue(repo.textNote(n.id)!!.pinned)
    }

    @Test
    fun searchHitHasKind() = runBlocking {
        val nb = inkedNotebook("Т", 1)
        repo.setRecognizedText(repo.pages(nb.id).single().id, "слово")
        assertEquals(listOf("NOTEBOOK"), repo.searchPages("лов").map { it.kind })
    }

    @Test
    fun searchFiltersKind() = runBlocking {
        val nb = inkedNotebook("Т", 1)
        repo.setRecognizedText(repo.pages(nb.id).single().id, "общее слово")
        val day = repo.diaryPage(java.time.LocalDate.of(2026, 10, 20))
        repo.saveInk(day, listOf(stroke()))
        repo.setRecognizedText(day.id, "общее слово")
        assertEquals(setOf("NOTEBOOK", "DIARY"), repo.searchPages("общее").map { it.kind }.toSet())
        val only = repo.searchPages("общее", NotebookKind.NOTEBOOK)
        assertEquals(listOf(repo.pages(nb.id).single().id), only.map { it.pageId })
        // trashed notebooks stay out of the search
        repo.delete(TrashKind.NOTEBOOK, nb.id)
        assertTrue(repo.searchPages("общее", NotebookKind.NOTEBOOK).isEmpty())
    }

    @Test
    fun restoreSummaryCountsByKind() = runBlocking {
        inkedNotebook("Т", 2)
        val board = repo.createBoard("Д")
        repo.saveInk(repo.boardPage(board.id)!!.second, listOf(stroke()))
        repo.saveInk(repo.diaryPage(java.time.LocalDate.of(2026, 10, 20)), listOf(stroke()))
        repo.saveInk(repo.diaryPage(java.time.LocalDate.of(2026, 10, 21)), listOf(stroke()))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = java.io.ByteArrayOutputStream().also { backup().write(it, includeMedia = false) }.toByteArray()
        val checked = backup().check(java.io.ByteArrayInputStream(bytes))
        assertEquals(1, checked.notebooks)
        assertEquals(1, checked.boards)
        assertEquals(2, checked.diaryDays)
        assertEquals(2, checked.pages)
        context.cacheDir.resolve("restore").deleteRecursively()
        Unit
    }

    @Test
    fun purgeOfLiveItemsChangesNothing() = runBlocking {
        val nb = inkedNotebook("Т", 2)
        val n = TextNoteEntity(title = "З", body = "")
        repo.saveNoteWithBlocks(n, listOf(NoteBlockEntity(noteId = n.id, blockIndex = 0, type = NoteBlockType.TEXT.name, text = "a")))
        val page = ids(nb).first()
        repo.purge(TrashKind.NOTEBOOK, nb.id)
        repo.purge(TrashKind.PAGE, page)
        repo.purge(TrashKind.NOTE, n.id)
        assertEquals(2, repo.observeNotebooks(NotebookKind.NOTEBOOK).first().single().pageCount)
        assertEquals(listOf(0, 1), repo.pages(nb.id).map { it.pageIndex })
        assertEquals(2, repo.pages(nb.id).count { repo.loadInk(it.id).size == 1 })
        assertEquals(1, repo.noteBlocks(n.id).size)
        assertTrue(repo.textNote(n.id) != null)
    }

    @Test
    fun restoreDiaryDayOverEmptyDay() = runBlocking {
        val day = java.time.LocalDate.of(2026, 10, 21)
        val old = repo.diaryPage(day)
        repo.saveInk(old, listOf(stroke()))
        repo.delete(TrashKind.DIARY_DAY, old.id)
        val fresh = repo.diaryPage(day)   // opened again, nothing written
        repo.saveInk(fresh, emptyList())
        repo.restore(TrashKind.DIARY_DAY, old.id)
        assertEquals(old.id, repo.diaryPage(day).id)
        assertEquals(1, repo.loadInk(old.id).size)
    }

    @Test
    fun restoreDiaryDayOverWrittenDayThrows() = runBlocking {
        val day = java.time.LocalDate.of(2026, 10, 21)
        val old = repo.diaryPage(day)
        repo.saveInk(old, listOf(stroke()))
        repo.delete(TrashKind.DIARY_DAY, old.id)
        val fresh = repo.diaryPage(day)
        repo.saveInk(fresh, listOf(stroke(), stroke()))
        val e = runCatching { repo.restore(TrashKind.DIARY_DAY, old.id) }.exceptionOrNull()
        assertTrue(e is DayTakenException)
        assertEquals(fresh.id, repo.diaryPage(day).id)
        assertEquals(listOf(TrashKind.DIARY_DAY), repo.trash().map { it.kind })
    }

    /** A day holding only a picture or text block is written: it is marked, and a restored same-date day never deletes it. */
    @Test
    fun diaryDayWithOnlyAnObjectIsTaken() = runBlocking {
        val day = java.time.LocalDate.of(2026, 10, 22)
        val diary = repo.diary()
        val old = repo.diaryPage(day)
        repo.saveInk(old, listOf(stroke()))
        repo.delete(TrashKind.DIARY_DAY, old.id)
        val fresh = repo.diaryPage(day)
        repo.savePageObjects(fresh, listOf(obj(fresh, "только текст")))
        assertNull(db.notebooks().ink(fresh.id))
        assertEquals(listOf(day.toEpochDay()), repo.observeDiaryDates(diary.id).first())
        val e = runCatching { repo.restore(TrashKind.DIARY_DAY, old.id) }.exceptionOrNull()
        assertTrue(e is DayTakenException)
        assertEquals(fresh.id, repo.diaryPage(day).id)
        assertEquals(listOf("только текст"), repo.pageObjects(fresh.id).map { it.text })
        assertEquals(listOf(TrashKind.DIARY_DAY), repo.trash().map { it.kind })
    }

    /** Undo while the day was reopened: the editor's empty page was removed, its late save must not bring it back. */
    @Test
    fun saveOnRemovedDayPageDoesNotDuplicateTheDate() = runBlocking {
        val day = java.time.LocalDate.of(2026, 10, 21)
        val old = repo.diaryPage(day)
        repo.saveInk(old, listOf(stroke()))
        repo.delete(TrashKind.DIARY_DAY, old.id)
        val fresh = repo.diaryPage(day)   // reopened, nothing written yet
        repo.restore(TrashKind.DIARY_DAY, old.id)   // removes the empty fresh page
        assertTrue(runCatching { repo.saveInk(fresh, listOf(stroke(), stroke())) }.exceptionOrNull() is DayTakenException)
        assertTrue(runCatching { repo.setPageTemplate(fresh, PageTemplate.GRID) }.exceptionOrNull() is DayTakenException)
        assertNull(db.notebooks().page(fresh.id))
        assertEquals(old.id, repo.diaryPage(day).id)
        assertEquals(1, repo.loadInk(old.id).size)
    }

    @Test
    fun pageOrderOperationsRefuseTheDiary() = runBlocking {
        val page = repo.diaryPage(java.time.LocalDate.of(2026, 10, 21))
        repo.saveInk(page, listOf(stroke()))
        val diary = repo.diary()
        assertTrue(runCatching { repo.duplicatePage(page.id) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { repo.insertPage(diary.id, 0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { repo.movePage(diary.id, 0, 0) }.exceptionOrNull() is IllegalArgumentException)
    }

    // --- page objects, lines, backgrounds (v10) ---

    private suspend fun onePage(): PageEntity =
        repo.createNotebook("Obj", PageTemplate.LINED, "auto").let { repo.pages(it.id).single() }

    private fun obj(page: PageEntity, text: String, media: String? = null) = PageObjectEntity(
        pageId = page.id, kind = if (media == null) PageObjectKind.TEXT.name else PageObjectKind.IMAGE.name,
        x = 1f, y = 2f, w = 30f, h = 40f, rotation = 15f, z = if (media == null) 0 else 1, text = text, media = media, updatedAt = 5,
    )

    private fun line(page: PageEntity, key: Long, index: Int, text: String, corrected: String? = null) = PageLineEntity(
        pageId = page.id, lineKey = key, lineIndex = index, left = 0f, top = index * 10f, right = 50f, bottom = index * 10f + 8f,
        recognized = text, corrected = corrected, updatedAt = 1,
    )

    private suspend fun inkOn(page: PageEntity) {
        val s = app.wanki.ink.InkStroke(listOf(app.wanki.ink.InkPoint(5f, 5f, 0), app.wanki.ink.InkPoint(40f, 30f, 20)))
        repo.saveInk(page, listOf(s))
    }

    @Test
    fun pageObjectsRoundTrip() = runBlocking {
        val page = onePage()
        val a = obj(page, "a")
        val b = obj(page, "", media = "page-x.jpg")
        repo.savePageObjects(page, listOf(a, b))
        assertEquals(listOf(a, b), repo.pageObjects(page.id))
        repo.savePageObjects(page, listOf(b))
        assertEquals(listOf(b), repo.pageObjects(page.id))
    }

    @Test
    fun saveLinesWritesJoinedText() = runBlocking {
        val page = onePage()
        inkOn(page)
        repo.saveLines(page.id, listOf(line(page, 2, 1, "b", corrected = "B"), line(page, 1, 0, "a")))
        assertEquals("a\nB", repo.recognizedText(page.id))
        assertEquals(listOf(page.id), repo.searchPages("B").map { it.pageId })
        assertEquals(listOf("a", "b"), repo.pageLines(page.id).map { it.recognized })
    }

    @Test
    fun setLineCorrectionNullResets() = runBlocking {
        val page = onePage()
        inkOn(page)
        repo.saveLines(page.id, listOf(line(page, 1, 0, "a"), line(page, 2, 1, "b")))
        repo.setLineCorrection(page.id, 2, "B")
        assertEquals("a\nB", repo.recognizedText(page.id))
        assertEquals("B", repo.pageLines(page.id).first { it.lineKey == 2L }.corrected)
        repo.setLineCorrection(page.id, 2, null)
        assertEquals("a\nb", repo.recognizedText(page.id))
        assertNull(repo.pageLines(page.id).first { it.lineKey == 2L }.corrected)
    }

    @Test
    fun duplicatePageCopiesObjectsAndLines() = runBlocking {
        val page = onePage()
        repo.savePageObjects(page, listOf(obj(page, "t"), obj(page, "", media = "page-x.jpg")))
        repo.saveLines(page.id, listOf(line(page, 1, 0, "a")))
        val copy = repo.duplicatePage(page.id)
        val src = repo.pageObjects(page.id)
        val dup = repo.pageObjects(copy.id)
        assertEquals(2, dup.size)
        assertTrue(dup.map { it.id }.intersect(src.map { it.id }.toSet()).isEmpty())
        assertEquals(src.map { it.media }.toSet(), dup.map { it.media }.toSet())
        assertEquals(listOf("a"), repo.pageLines(copy.id).map { it.recognized })
    }

    @Test
    fun purgeRemovesObjectsAndLines() = runBlocking {
        val nb = repo.createNotebook("P", PageTemplate.LINED, "auto")
        val p1 = repo.pages(nb.id).single()
        val p2 = repo.insertPage(nb.id, 1)
        for (p in listOf(p1, p2)) {
            repo.savePageObjects(p, listOf(obj(p, "t")))
            repo.saveLines(p.id, listOf(line(p, 1, 0, "a")))
        }
        repo.delete(TrashKind.PAGE, p2.id)
        assertTrue(repo.purge(TrashKind.PAGE, p2.id))
        assertTrue(repo.pageObjects(p2.id).isEmpty() && repo.pageLines(p2.id).isEmpty())
        assertEquals(1, repo.pageObjects(p1.id).size)
        repo.delete(TrashKind.NOTEBOOK, nb.id)
        assertTrue(repo.purge(TrashKind.NOTEBOOK, nb.id))
        assertTrue(repo.pageObjects(p1.id).isEmpty() && repo.pageLines(p1.id).isEmpty())
    }

    @Test
    fun insertPagesKeepsDenseIndices() = runBlocking {
        val nb = repo.createNotebook("I", PageTemplate.LINED, "auto")
        repo.insertPage(nb.id, 1)
        val made = repo.insertPages(
            nb.id, 1,
            listOf(
                Repository.NewPage(false, "page-a.jpg", null),
                Repository.NewPage(true, "page-b.pdf", 0),
                Repository.NewPage(true, "page-b.pdf", 1),
            ),
        )
        assertEquals(3, made.size)
        val all = repo.pages(nb.id)
        assertEquals((0..4).toList(), all.map { it.pageIndex })
        assertEquals(made.map { it.id }, all.subList(1, 4).map { it.id })
        assertEquals(listOf("page-a.jpg", "page-b.pdf", "page-b.pdf"), all.subList(1, 4).map { it.backgroundMedia })
        assertEquals(listOf(null, 0, 1), all.subList(1, 4).map { it.backgroundPage })
        assertEquals(listOf(false, true, true), all.subList(1, 4).map { it.landscape })
        assertEquals(PageTemplate.BLANK.name, all[2].template)
    }

    @Test
    fun setPageBackgroundStoresAndClears() = runBlocking {
        val page = onePage()
        repo.setPageBackground(page.id, "page-z.pdf", 3)
        db.notebooks().page(page.id)!!.let { assertEquals("page-z.pdf", it.backgroundMedia); assertEquals(3, it.backgroundPage) }
        repo.setPageBackground(page.id, null, null)
        db.notebooks().page(page.id)!!.let { assertNull(it.backgroundMedia); assertNull(it.backgroundPage) }
    }

    @Test
    fun mediaGcKeepsTrashedReferences() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val media = app.wanki.media.MediaStore(ctx)
        media.dir.deleteRecursively()
        for (n in listOf("page-a.jpg", "page-b.pdf", "page-c.jpg", "card-d.jpg")) media.save(n, "x".byteInputStream()).setLastModified(System.currentTimeMillis() - 2 * Repository.MEDIA_MIN_AGE_MS)
        val nb = repo.createNotebook("G", PageTemplate.LINED, "auto")
        val live = repo.pages(nb.id).single()
        val gone = repo.insertPage(nb.id, 1)
        repo.savePageObjects(live, listOf(obj(live, "", media = "page-a.jpg")))
        repo.setPageBackground(gone.id, "page-b.pdf", 0)
        repo.delete(TrashKind.PAGE, gone.id)
        assertEquals(1, repo.collectMediaGarbage(media))
        assertEquals(setOf("page-a.jpg", "page-b.pdf", "card-d.jpg"), media.dir.list().orEmpty().toSet())
        repo.purge(TrashKind.PAGE, gone.id)
        assertEquals(1, repo.collectMediaGarbage(media))
        assertEquals(setOf("page-a.jpg", "card-d.jpg"), media.dir.list().orEmpty().toSet())
        media.dir.deleteRecursively()
        Unit
    }

    @Test
    fun mediaGcKeepsNewFilesAndReportsFailedDeletes() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val media = app.wanki.media.MediaStore(ctx)
        media.dir.deleteRecursively()
        val old = System.currentTimeMillis() - 2 * Repository.MEDIA_MIN_AGE_MS
        media.save("page-old.jpg", "x".byteInputStream()).setLastModified(old)
        // just imported, its object row not saved yet
        media.save("page-new.jpg", "x".byteInputStream())
        assertEquals(1, repo.collectMediaGarbage(media))
        assertEquals(setOf("page-new.jpg"), media.dir.list().orEmpty().toSet())
        // a day later it goes
        assertEquals(1, repo.collectMediaGarbage(media, now = System.currentTimeMillis() + 2 * Repository.MEDIA_MIN_AGE_MS))
        // a file that cannot be deleted is reported, not ignored
        media.save("page-stuck.jpg", "x".byteInputStream()).setLastModified(old)
        assertTrue(media.dir.setWritable(false))
        try {
            val r = runCatching { repo.collectMediaGarbage(media) }
            assertTrue(r.exceptionOrNull() is java.io.IOException)
            assertTrue(r.exceptionOrNull()!!.message!!.contains("page-stuck.jpg"))
        } finally {
            media.dir.setWritable(true)
        }
        media.dir.deleteRecursively()
        Unit
    }

    @Test
    fun moveToPageCarriesObjects() = runBlocking {
        val (from, to) = twoPages()
        val keep = obj(from, "keep")
        val go = obj(from, "go")
        repo.savePageObjects(from, listOf(keep, go))
        repo.saveInk(from, listOf(ink(10f)))
        val moved = repo.moveToPage(from, blob(listOf(ink(10f))), to, emptyList(), objects = listOf(go), fromObjects = listOf(keep))
        assertTrue(moved.keys.isEmpty())
        assertEquals(1, moved.objectIds.size)
        assertEquals(listOf("keep"), repo.pageObjects(from.id).map { it.text })
        val placed = repo.pageObjects(to.id).single()
        assertEquals("go", placed.text)
        // a new id: the source may get the original back by undo while the target still has it
        assertTrue(placed.id != go.id)
        assertEquals(moved.objectIds, listOf(placed.id))
        // no ink row is made on the target for objects alone
        assertNull(db.notebooks().ink(to.id))
        assertEquals(1, repo.takeBackFromPage(to.id, moved.keys, moved.objectIds))
        assertTrue(repo.pageObjects(to.id).isEmpty())
        // put back on a closed source page with new ids
        repo.appendInk(from, emptyList(), listOf(go.toModel()))
        assertEquals(setOf("keep", "go"), repo.pageObjects(from.id).map { it.text }.toSet())
    }

    // --- «На другую страницу» ---------------------------------------------------------------------

    private fun ink(x: Float, y: Float = 100f) =
        app.wanki.ink.InkStroke(listOf(app.wanki.ink.InkPoint(x, y, 0), app.wanki.ink.InkPoint(x + 50f, y + 20f, 20)))

    private suspend fun twoPages(): Pair<PageEntity, PageEntity> {
        val nb = repo.createNotebook("Move", PageTemplate.LINED, "auto")
        val first = repo.pages(nb.id).single()
        val second = repo.addPage(nb)
        return first to second
    }

    private fun blob(strokes: List<app.wanki.ink.InkStroke>) =
        if (strokes.isEmpty()) null else app.wanki.ink.InkCodec.encode(app.wanki.ink.InkDrawing(strokes))

    private suspend fun stored(pageId: String) = (repo.loadInkResult(pageId) as InkLoad.Ok).strokes

    @Test
    fun moveToPageAppendsAndRemoves() = runBlocking {
        val (from, to) = twoPages()
        val stay = ink(10f)
        val go = ink(300f)
        val old = ink(500f)
        repo.saveInk(from, listOf(stay, go))
        repo.saveInk(to, listOf(old))
        val keys = repo.moveToPage(from, blob(listOf(stay)), to, listOf(go)).keys
        assertEquals(1, keys.size)
        assertEquals(listOf(500f, 300f), stored(to.id).map { it.points[0].x })
        assertEquals(listOf(10f), stored(from.id).map { it.points[0].x })
        // the source can end up empty: its ink row goes
        repo.moveToPage(from, null, to, listOf(stay))
        assertNull(db.notebooks().ink(from.id))
        assertEquals(3, stored(to.id).size)
    }

    @Test
    fun moveToPageIsAtomic() = runBlocking {
        val (from, to) = twoPages()
        repo.saveInk(from, listOf(ink(10f), ink(300f)))
        repo.saveInk(to, listOf(ink(500f)))
        val fromBefore = db.notebooks().ink(from.id)!!.strokes
        val toBefore = db.notebooks().ink(to.id)!!.strokes
        val missing = to.copy(id = "no-such-page")
        val failed = runCatching { repo.moveToPage(from, blob(listOf(ink(10f))), missing, listOf(ink(300f))) }
        assertTrue(failed.isFailure)
        assertTrue(fromBefore.contentEquals(db.notebooks().ink(from.id)!!.strokes))
        assertTrue(toBefore.contentEquals(db.notebooks().ink(to.id)!!.strokes))
        assertNull(db.notebooks().ink("no-such-page"))
        // a target whose ink cannot be read is never overwritten, and the source keeps its strokes
        db.notebooks().upsertInk(PageInkEntity(pageId = to.id, strokes = byteArrayOf(1, 2, 3)))
        assertTrue(runCatching { repo.moveToPage(from, blob(listOf(ink(10f))), to, listOf(ink(300f))) }.isFailure)
        assertTrue(fromBefore.contentEquals(db.notebooks().ink(from.id)!!.strokes))
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(db.notebooks().ink(to.id)!!.strokes))
    }

    @Test
    fun takeBackRemovesOnlyMoved() = runBlocking {
        val (from, to) = twoPages()
        val same = ink(300f)
        // the target already holds an identical stroke (same content, same key) and another one
        repo.saveInk(to, listOf(same, ink(700f)))
        repo.saveInk(from, listOf(same, same))
        val keys = repo.moveToPage(from, null, to, listOf(same)).keys
        assertEquals(3, stored(to.id).size)
        assertEquals(1, repo.takeBackFromPage(to.id, keys))
        // one of the two identical strokes stays: only as many as were moved go
        assertEquals(listOf(300f, 700f), stored(to.id).map { it.points[0].x })
        // two moved, two taken back
        val keys2 = repo.moveToPage(from, null, to, listOf(same, same)).keys
        assertEquals(2, keys2.size)
        assertEquals(2, repo.takeBackFromPage(to.id, keys2))
        assertEquals(listOf(300f, 700f), stored(to.id).map { it.points[0].x })
        // taking back everything leaves no ink row
        val (a, b) = twoPages()
        val k = repo.moveToPage(a, null, b, listOf(ink(10f))).keys
        repo.takeBackFromPage(b.id, k)
        assertNull(db.notebooks().ink(b.id))
    }

    @Test
    fun movedStrokesFitInsideTarget() = runBlocking {
        val (from, to) = twoPages()
        // strokes at x 1100..1300 (a landscape page is 1414 wide) moved to a portrait page (1000 wide)
        val strokes = listOf(ink(1100f), ink(1250f))
        repo.moveToPage(from, null, to, strokes)
        val placed = stored(to.id)
        val left = placed.minOf { s -> s.points.minOf { it.x } }
        val right = placed.maxOf { s -> s.points.maxOf { it.x } }
        assertTrue("$left..$right", left >= 0f && right <= 1000f)
        assertEquals(200f, right - left, 0.01f)
        // strokes already on the page stay where they are
        val (a, b) = twoPages()
        repo.moveToPage(a, null, b, listOf(ink(10f, 20f)))
        assertEquals(10f, stored(b.id).single().points[0].x, 0.01f)
    }
}
