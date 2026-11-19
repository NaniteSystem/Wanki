# П4a «Учёба: связи» Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cards remember where they came from; one dialog makes a card from any page selection; «Открыть источник» / «Карточки отсюда»; the «скотч» tool masks page areas and turns them into self-graded cards that follow page edits.

**Architecture:** A new `source_refs` table (DB v11) links a note to a source (П4a wires only `PAGE`; the column takes `NOTE`, `BOARD_ITEM`, `DIARY`, `TEXT` for П5–П7 and П4b). Masks are `page_objects` of kind `MASK` (reserved in П3). A mask card's note field holds a `[mask:<objectId>]` token; `CardHtml` turns it into `CardBlock.Mask`, drawn live from the page with the existing `renderThumbnail`, so page edits update the card.

**Tech Stack:** Kotlin, Compose, Room (migration 10→11), existing `InkLayer` / `PageEditor` / `CardHtml` / `renderThumbnail` / `TranslationService`.

**Spec:** `docs/superpowers/specs/2026-10-21-early-features-rework-design.md` §4.6 (first four bullets) and §8 items 1, 2, 5. П4b (прописи, «Учить наизусть», общий поиск) gets its own plan. Not here, on purpose: tape on board items (П7 rebuilds boards; the `MASK` object and `CardBlock.Mask` are reused there), card sources from notes/diary entries (П5/П6 wire their `SourceKind`).

## Global Constraints

- UI text and docs in Russian; code and comments in English.
- Room changes need a migration, never destructive fallback; export `app/schemas/…/11.json`; `MigrationTest.migrate10To11`.
- `InkCodec` unchanged (v3).
- UI state after suspend work resumes on main (`ui { }` / `rememberUiScope()`); fire-and-forget saves go to `graph.appScope`.
- Mask cards are **CLASSIC** (self-graded); the recognised text under the mask is stored as an answer hint. Selection cards: answer = recognised (edited) text, mode by the importer's rule (answer in ja/zh/ko script → `HANDWRITING`, else `CLASSIC`); the handwriting picture goes on the answer side.
- **Linked** cards (mask cards) are owned by the source: their fields are read-only in the editor with «Изменить на странице». Mask deleted or page purged → card suspended, ref `detached = true`, history kept. **One-off** cards (from a selection) are copies, freely edited; the ref is only for navigation.
- Source page in the trash → cards unchanged; «Открыть источник» offers «Восстановить».
- Instrumented tests on `emulator-5558`, then `emulator-5556`, one at a time; never `emulator-5554` or device `320147012717`. Commits local only.

## Review Focus

1. A mask card whose page is in the trash or purged: review must still show something (a «Источник удалён» placeholder), never crash or show a blank card. → Task 5 test `maskCardWithPurgedPageShowsPlaceholder`.
2. Undo of a mask delete in the editor must un-suspend its card (delete → suspend, undo → back). → Task 4 test `undoMaskDeleteRestoresCard`.
3. Duplicating a page or notebook copies masks with new ids; the copied masks must not be linked to the original's cards. → Task 1 test `duplicatePageDoesNotShareMaskRefs`.
4. «Добавить ещё» after the selection changed (user lassoed another area) must use the new selection, not the first one. → Task 2 test `addMoreUsesCurrentSelection`.
5. A mask on an endless («Бесконечная») page below the 1414 cut: the card picture must include the mask's area (crop around the mask, not the page top). → Task 5 test `maskBelowThumbnailCutIsCropped`.

---

### Task 1: `source_refs`, `MASK` objects, DB v11

**Files:**
- Modify: `app/src/main/kotlin/app/wanki/data/Entities.kt` (add `SourceRefEntity`, `SourceKind`, `PageObjectKind.MASK`)
- Modify: `app/src/main/kotlin/app/wanki/data/Daos.kt` (new `SourceRefDao`)
- Modify: `app/src/main/kotlin/app/wanki/data/WankiDatabase.kt` (`VERSION = 11`, `MIGRATION_10_11`)
- Modify: `app/src/main/kotlin/app/wanki/data/Repository.kt`
- Test: `app/src/androidTest/kotlin/app/wanki/data/MigrationTest.kt`, `…/data/RepositoryTest.kt`

**Interfaces:**
- Produces:
  - `enum class SourceKind { PAGE, NOTE, BOARD_ITEM, DIARY, TEXT }`
  - `@Entity("source_refs", indices=[Index("noteId"), Index("sourceId")]) data class SourceRefEntity(id: String = newId(), noteId: String, kind: String, sourceId: String, objectId: String? = null, x: Float? = null, y: Float? = null, w: Float? = null, h: Float? = null, linked: Boolean, detached: Boolean = false, createdAt: Long)` — `sourceId` = page id for `PAGE`; `objectId` = mask id for mask cards; rect = selection bounds in world units.
  - Mask object: `PageObjectEntity(kind = "MASK", x,y,w,h = bounds, text = "" for a rectangle or polygon points `"x1,y1 x2,y2 …"` in world units)`.
  - `Repository.addCardFromSource(deckId: String, front: String, back: String, mode: AnswerMode, ref: SourceRefEntity /* noteId ignored */, tags: String = ""): CardEntity`
  - `Repository.sourceOf(noteId: String): SourceRefEntity?`
  - `Repository.observeCardsFrom(kind: SourceKind, sourceId: String): Flow<List<CardWithNote>>`
  - `Repository.detachMaskRefs(pageId: String, liveMaskIds: Set<String>)` — refs on `pageId` whose `objectId` is not in `liveMaskIds`: `detached = true`, their cards suspended; refs whose mask came back (undo): `detached = false`, unsuspended **only if** they were suspended by detach (store that in the ref: add `suspendedByDetach: Boolean = false`).
  - Called from the page save path (`saveInkBlob`/objects save) with the saved object list, and from `purge` of a page/notebook (all refs of those pages detached).

- [ ] **Step 1:** `MigrationTest.migrate10To11`: v10 db with one page object → v11 opens, `source_refs` empty and insertable, object intact.
- [ ] **Step 2:** `RepositoryTest`: `addCardFromSourceStoresRef` (ref noteId = card's note), `maskDeleteSuspendsLinkedCard`, `maskBackUnsuspends` (a card the user suspended by hand stays suspended), `purgePageDetachesRefs`, `oneOffCardIsNotSuspended` (linked = false refs never suspend), `duplicatePageDoesNotShareMaskRefs` (copy's masks get new ids; `observeCardsFrom(PAGE, copyId)` is empty).
- [ ] **Step 3:** Run on 5558 — FAIL.
- [ ] **Step 4:** Implement; `copyObjectsAndLines` already gives copies new ids — verify, don't change.
- [ ] **Step 5:** Run `MigrationTest`, `RepositoryTest` on 5558 — PASS; `./gradlew test` green.
- [ ] **Step 6:** Commit `П4a: source_refs, MASK page objects, DB v11`.

### Task 2: One card dialog for selections

**Files:**
- Create: `app/src/main/kotlin/app/wanki/ui/common/CardFromSelectionDialog.kt` (replaces `MakeCardDialog` in `TextActions.kt`; delete the old one, keep its deck picker use)
- Modify: `app/src/main/kotlin/app/wanki/ui/common/TextActions.kt` (SelectionBar «Карточка» opens the new dialog with a `CardSource`)
- Modify: `app/src/main/kotlin/app/wanki/data/Settings.kt` (`lastDeckFor(sourceId)`, `setLastDeckFor`, `lastDeck`)
- Test: `app/src/test/kotlin/app/wanki/ui/common/DeckChoiceTest.kt`, `app/src/androidTest/kotlin/app/wanki/ui/CardFromSelectionTest.kt`

**Interfaces:**
- Consumes: `Repository.addCardFromSource` (Task 1), `TranslationService`, `InkLayer.selectedObjectList`, selection bounds, existing recognition of the selection.
- Produces:
  - `data class CardSource(val kind: SourceKind, val sourceId: String, val notebookId: String?, val language: String)`
  - `fun chooseDeck(lastForSource: String?, decks: List<DeckEntity>, language: String, lastUsed: String?): String?` (pure, in `CardFromSelectionDialog.kt`): last deck for this notebook if it still exists → first deck whose language = `language` → `lastUsed` → null.
  - `@Composable fun CardFromSelectionDialog(source: CardSource, read: suspend () -> SelectionContent?, onDismiss: () -> Unit)` where `data class SelectionContent(val text: String, val bounds: InkRect, val picture: Bitmap?)`; `read` is called on open **and on each «Добавить ещё»** (so it reads the current lasso selection).
  - Fields: «Ответ» (recognised text, editable), «Вопрос» (translation, editable), checkbox «Приложить рукопись» (default on when `picture != null`), deck chip. Picture saved via `graph.media` as `card-<uuid>.png` and appended to the answer as `<img src="…">`. Buttons «Добавить», «Добавить ещё», «Закрыть».
- [ ] **Step 1:** `DeckChoiceTest`: the four fallback cases + deleted last deck skipped.
- [ ] **Step 2:** `CardFromSelectionTest` (Compose, 5558): lasso strokes → «Карточка» → «Добавить» → card exists with `sourceOf(note)` = page and bounds; `addMoreUsesCurrentSelection` (change selection, «Добавить ещё» reads the new bounds); picture unchecked → no `<img>`.
- [ ] **Step 3:** Run — FAIL. **Step 4:** Implement. **Step 5:** Run — PASS; screenshot at 360 dp / font 1.3 fits.
- [ ] **Step 6:** Commit `П4a: one card dialog for selections`.

### Task 3: «Открыть источник» and «Карточки отсюда»

**Files:**
- Modify: `ui/MainShell.kt` (`Route.Notebook` gets `area: String? = null`, `"x,y,w,h"`; `RouteCodec` round-trips it)
- Modify: `ui/notebooks/NotebooksScreen.kt`, `PageEditor.kt` (show the area: a pulsing outline for 3 s, view scrolled to it; consumed like `highlight`)
- Modify: `ui/review/*` card screen and `ui/browser` editor: «Открыть источник» in «⋮» when `sourceOf(noteId) != null`
- Create: `ui/notebooks/CardsFromHereSheet.kt` (bottom sheet: list of cards from `observeCardsFrom(PAGE, pageId)`, tap → card editor)
- Test: `app/src/androidTest/kotlin/app/wanki/ui/SourceLinksTest.kt`, `RouteCodecTest`

**Interfaces:**
- Consumes: Task 1 queries.
- Produces: `Route.Notebook(id, pageId, highlight, area)`; page toolbar «⋮» item «Карточки отсюда (N)», N also as a badge on the item.
- Source states: page live → open with area; page in trash → dialog «Страница в корзине» with «Восстановить и открыть»; ref detached / page gone → item disabled with «Источник удалён».
- [ ] **Step 1:** `RouteCodecTest.notebookAreaRoundTrips`. **Step 2:** `SourceLinksTest`: review «Открыть источник» lands on the page with the area outline node (`testTag("source-area")`); trashed page shows restore dialog and restore opens it; «Карточки отсюда» lists 2 cards made on the page.
- [ ] **Step 3:** FAIL → implement → PASS on 5558.
- [ ] **Step 4:** Commit `П4a: open source, cards from here`.

### Task 4: «Скотч» tool

**Files:**
- Modify: `input/InkLayer.kt` (masks are page objects: add/remove go through the existing object undo path)
- Modify: `ui/notebooks/PageEditor.kt` + toolbar (tool «Скотч»: drag = rectangle; with the lasso-style hold-and-draw = polygon via the lasso path code; tap on a mask toggles open/closed; «⋮» «Открыть всё» / «Закрыть всё» / «Карточки из скотча»)
- Modify: `ui/common/PageObjects` drawing (`drawPageObject`): closed mask = opaque tape colour (Material `tertiaryContainer` on paper, fixed in dark theme like `WritingPaper`), open = 25 % outline only
- Test: `app/src/androidTest/kotlin/app/wanki/ui/MaskToolTest.kt`

**Interfaces:**
- Consumes: `PageObjectKind.MASK` (Task 1).
- Produces: open/closed is view state (`PageEditorState.openMasks: Set<String>`), not stored — every mask opens closed. Masks are drawn **over** ink (unlike text/pictures, which are under it). Lasso selects masks like other objects (move, delete, copy).
- [ ] **Step 1:** `MaskToolTest`: drag makes a mask that hides ink under it (pixel check of the canvas at a stroke point = tape colour); tap opens / taps again closes; «Открыть всё»; masks survive reopen; `undoMaskDeleteRestoresCard` (with a mask card from Task 5's repository call made directly: delete mask → card suspended; undo → not suspended).
- [ ] **Step 2:** FAIL → implement → PASS on 5558 and 5556.
- [ ] **Step 3:** Commit `П4a: tape masks on pages`.

### Task 5: Cards from tape

**Files:**
- Modify: `anki/CardHtml.kt` (`CardBlock.Mask(val objectId: String, val revealed: Boolean)`; token `[mask:<id>]` on the question side → `revealed = false`, answer side → `true`)
- Modify: `ui/common/CardBlocks.kt` (`MaskImage`: loads the page of the mask, renders with `renderThumbnail` cropped to the mask's bounds padded by 1.5× its size (min 400 world units), this mask drawn closed or open, other masks open; placeholder «Источник удалён» when the mask/page is gone)
- Modify: `ui/notebooks/PageEditor.kt` («Карточки из скотча»: one linked CLASSIC card per mask without a live ref; dialog shows count and deck chip via `chooseDeck`)
- Modify: `ui/browser` editor: linked card → fields read-only + «Изменить на странице» (= Open source)
- Test: `app/src/test/kotlin/app/wanki/anki/CardHtmlTest.kt`, `app/src/androidTest/kotlin/app/wanki/ui/MaskCardsTest.kt`

**Interfaces:**
- Consumes: Tasks 1–4. Note fields: front = `[mask:<id>]`, back = `[mask:<id>]` + `\n` + recognised text of strokes inside the mask bounds (from `page_lines` whose line bounds intersect; empty if none).
- [ ] **Step 1:** `CardHtmlTest.maskTokenBecomesBlock` (question/answer revealed flag).
- [ ] **Step 2:** `MaskCardsTest`: two masks → «Карточки из скотча» → 2 cards; again → still 2 (no duplicates); review shows closed mask on question, open on answer (pixel check); edit ink under the mask → card picture changes; `maskCardWithPurgedPageShowsPlaceholder`; `maskBelowThumbnailCutIsCropped` (endless page, mask at y = 3000 → image contains the tape colour).
- [ ] **Step 3:** FAIL → implement → PASS on 5558.
- [ ] **Step 4:** Commit `П4a: cards from tape`.

### Task 6: Smoke, docs, release check

**Files:** `app/src/androidTest/kotlin/app/wanki/SmokeTest.kt`, `docs/PROGRESS.md`, `docs/PLAN.md`, `CLAUDE.md`

- [ ] **Step 1:** Smoke: page → lasso → card via dialog → tape → «Карточки из скотча» → review a mask card → «Открыть источник». Screenshots phone 360/1.3 and tablet.
- [ ] **Step 2:** Full `./gradlew test`; full connected suite on 5558, then 5556 (known flaky list in PROGRESS; parked page-open time excluded).
- [ ] **Step 3:** `./gradlew :app:assembleRelease`, install on 5558, run the smoke path by hand (R8 keep rules if anything is reflective).
- [ ] **Step 4:** PROGRESS: П4a ✅ with decisions and follow-ups; PLAN status; CLAUDE.md: `source_refs`, masks, `[mask:]` token, DB v11.
- [ ] **Step 5:** Commit `Stage П4a done: study links, tape, mask cards`.
