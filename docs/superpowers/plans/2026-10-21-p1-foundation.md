# П1 «Основа» Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The first-version ink sections (notebooks, diary, boards, notes) stop losing the user's place and work on a phone: rotation-safe navigation, a compact header with an overflow menu, real icons, a one-row toolbar, the tool and zoom remembered, one undo step per gesture, saving at the end of every gesture, honest diary marks, auto backup for ink-only users.

**Architecture:** Navigation and screen state survive configuration changes through `android:configChanges` plus a `rememberSaveable` back stack (routes encoded to strings by hand — no new Gradle plugin). Saving goes through a small serial saver that runs after each finished gesture and reports errors. Icons are Material Symbols vector drawables copied into `res/drawable`. The page's zoom/pan is stored in three new nullable `pages` columns (DB v7).

**Tech Stack:** Kotlin 2.3, Jetpack Compose + Material 3, Room (DB v6 → v7), androidx.ink (rendering only), JUnit (JVM) and Compose instrumented tests.

**Spec:** `docs/superpowers/specs/2026-10-21-early-features-rework-design.md` — stage П1 (§4.1, §6, §8.4, §8.7). Research with file:line references: `.superpowers/research/early-features/audit.md` (§0, §1, §3, §4, §6, §9).

## Global Constraints

- Room schema changes need a migration, never destructive fallback; export the schema to `app/schemas` and add a `MigrationTest` case.
- Stylus vs finger stays automatic: no mode switches, no setting for it.
- Writing canvases stay paper-coloured (`WritingPaper`) in the dark theme; ink drawn on paper is dark.
- UI text in Russian; code and comments in English.
- Touch targets ≥ 48 dp; every icon-only control has a Russian `contentDescription`.
- Nothing goes off screen at 360 dp width, font scale 1.0 and 1.3.
- Instrumented tests: only `wanki-phone` on port 5558 (`ANDROID_SERIAL=emulator-5558`) and `wanki-tablet` on 5556, one emulator at a time. Never the serial `emulator-5554`, never the device `320147012717`.
- Commits local only, never push; each commit message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- After each task: append one line «Задача N (дата): …» to a new block «Этап П1» in `docs/PROGRESS.md`.
- Release builds use R8: anything found by reflection needs a keep rule.

## Review Focus

1. Rotation or dark-mode switch while a lasso drag, eraser drag or stroke is in progress — expected: the gesture is cancelled or committed cleanly, no crash, no half-stroke saved twice. → Task 1 (recreate test with a canvas open) and Task 5 (saver test: request during a running save).
2. A save that throws (disk full, DB closed during restore) — expected: a visible «Не удалось сохранить» with «Повторить», strokes stay in memory, the next gesture retries. → Task 5 test `failedSaveKeepsErrorAndRetries`.
3. A route that can no longer be decoded after an app update (unknown string in saved state) — expected: fall back to «Колоды», no crash. → Task 1 test `unknownRouteFallsBackToDecks`.
4. Two-finger tap fired by the start of a pinch, or by a palm plus a finger — expected: no undo when the fingers moved or the contact lasted > 250 ms. → Task 3 tests.
5. Opening a past diary day and leaving without writing, then writing and erasing everything — expected: the day is not marked in either case. → Task 7 test `emptiedDiaryDayIsNotMarked`.

---

### Task 1: Navigation and state survive configuration changes

**Files:**
- Create: `app/src/main/kotlin/app/wanki/ui/RouteCodec.kt`
- Modify: `app/src/main/AndroidManifest.xml` (`MainActivity`), `app/src/main/kotlin/app/wanki/ui/MainShell.kt` (`Navigator`, `MainShell`)
- Modify: `app/src/main/kotlin/app/wanki/ui/notebooks/NotebooksScreen.kt` (current page index), `practice/PracticeScreen.kt` (index/round), any other `remember { mutableStateOf(...) }` that holds the user's position on a screen (open page, selected tab, scroll target)
- Test: `app/src/test/kotlin/app/wanki/ui/RouteCodecTest.kt`, `app/src/androidTest/kotlin/app/wanki/RecreateTest.kt`

**Interfaces:**
- Produces: `object RouteCodec { fun encode(route: Route): String; fun decode(s: String): Route? }`; `Navigator.Companion.Saver: androidx.compose.runtime.saveable.Saver<Navigator, List<String>>`.

- [ ] **Step 1: Write the failing JVM test** `RouteCodecTest`:
  - `everyRouteRoundTrips`: for one instance of every `Route` subtype (including `Notebook("n1", "p2")`, `Notebook("n1", null)`, `Practice(null, "ja")`, `TextStudy(StudyRequest(StudyKind.MANZIL, null, listOf("a","b")))`, `Note(null)`, `TextEditor(null)`), `assertEquals(r, RouteCodec.decode(RouteCodec.encode(r)))`.
  - `idsWithSeparatorsRoundTrip`: `Note("a|b,c")` round-trips (escape the separator).
  - `unknownRouteFallsBackToDecks`: `assertNull(RouteCodec.decode("Nope|x"))`, and `Navigator` restored from `listOf("Nope|x")` has `stack == [Route.Decks]`.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests app.wanki.ui.RouteCodecTest` → FAIL (unresolved `RouteCodec`).
- [ ] **Step 3: Implement** `RouteCodec` (one `when` over the sealed interface; a missing subtype must be a compile error, so no `else` branch in `encode`); `Navigator.Saver` saves `stack.map(RouteCodec::encode)` and restores the decodable ones (empty → `[Route.Decks]`); `MainShell` uses `rememberSaveable(saver = Navigator.Saver) { Navigator() }`. Add to `MainActivity`: `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|uiMode|density|fontScale|keyboard|keyboardHidden|navigation"`. Convert position state on the screens listed above to `rememberSaveable`.
- [ ] **Step 4:** the JVM test passes.
- [ ] **Step 5: Instrumented test** `RecreateTest.notebookPageSurvivesRecreate`: create a notebook with 2 pages through the repository, open it through the UI and go to page 2 with the existing «▶» button, call `rule.activityRule.scenario.recreate()`, assert the notebook title is displayed and the page counter shows page 2. Run with `ANDROID_SERIAL=emulator-5556 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.wanki.RecreateTest` → PASS. (Task 6 updates this test to its new tags.)
- [ ] **Step 6: Commit** — `git commit -m "Navigation survives rotation and process death; saveable screen positions"`

### Task 2: Icons and the shared header

**Files:**
- Create: `app/src/main/res/drawable/ic_*.xml` — Material Symbols Outlined, copied from `https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/<name>/materialsymbolsoutlined/<name>_24px.xml` (Apache 2.0; add a line to the licenses list in «О приложении» and `README.md`). Names: `arrow_back, more_vert, add, undo, redo, stylus, ink_marker, ink_highlighter, ink_eraser, lasso_select, fit_screen, notes, chevron_left, chevron_right, style, book_2, sticky_note_2, calendar_month, dashboard, settings, delete, edit, search`. Keep each file's path data as is and remove the `android:tint` attribute, so Compose `Icon` tints it.
- Create: `app/src/main/kotlin/app/wanki/ui/common/Header.kt`
- Modify: `app/src/main/kotlin/app/wanki/ui/common/Common.kt` (`ScreenHeader` moves to `Header.kt`), `ui/MainShell.kt` (tab icons), every `ScreenHeader` call site that passes `actions` with more than two buttons.
- Test: `app/src/test/kotlin/app/wanki/ui/common/HeaderActionsTest.kt`

**Interfaces:**
- Produces:
```kotlin
data class HeaderAction(val icon: Int, val label: String, val tag: String? = null, val enabled: Boolean = true, val onClick: () -> Unit)
/** At most [max] primary actions stay visible; the rest go to the front of the menu. */
fun splitActions(primary: List<HeaderAction>, menu: List<HeaderAction>, max: Int = 2): Pair<List<HeaderAction>, List<HeaderAction>>
@Composable fun ScreenHeader(
    title: String, onBack: (() -> Unit)? = null, subtitle: String? = null, subtitleMaxLines: Int = Int.MAX_VALUE,
    primary: List<HeaderAction> = emptyList(), menu: List<HeaderAction> = emptyList(),
    actions: @Composable () -> Unit = {},  // kept for screens not converted yet
)
```
  Back button: `IconButton` with `ic_arrow_back`, `contentDescription = "Назад"`, `Modifier.testTag("back")`. Overflow: `IconButton` `ic_more_vert`, description «Ещё», tag `"menu"`; menu items carry their `tag`.

- [ ] **Step 1: Failing test** `HeaderActionsTest`: three primary + one menu with `max = 2` → visible = first two, menu = [third, menuItem]; one primary → visible = it, menu unchanged; `max = 0` → all in menu.
- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3: Implement** `Header.kt`; tabs in `MainShell` use `Icon(painterResource(...), contentDescription = null)` with the label below (icons: Колоды `style`, Тетради `book_2`, Заметки `sticky_note_2`, Дневник `calendar_month`, Доски `dashboard`, Настройки `settings`). Convert headers that today show 3+ text buttons (notebook screen, board screen) to `primary`/`menu`; leave other screens on the `actions` slot.
- [ ] **Step 4:** JVM tests pass; `./gradlew :app:assembleDebug` builds.
- [ ] **Step 5: Commit** — `git commit -m "Shared header with icon back, overflow menu; Material Symbols icons for tabs"`

### Task 3: Canvas input — one undo per gesture, tap gestures, pen and palm

**Files:**
- Create: `app/src/main/kotlin/app/wanki/input/TapGestures.kt`
- Modify: `app/src/main/kotlin/app/wanki/input/InkLayer.kt`, `input/InkInputHandler.kt`, `input/StylusTracker.kt`, `ui/MainShell.kt` (screen change hook)
- Test: `app/src/test/kotlin/app/wanki/input/InkLayerEraseTest.kt`, `app/src/test/kotlin/app/wanki/input/TapGesturesTest.kt`, `app/src/test/kotlin/app/wanki/input/StylusTrackerTest.kt`

**Interfaces:**
- Produces:
  - `InkLayer.beginErase()`, `InkLayer.eraseAt(...)` unchanged signature but inside an erase gesture it does not push undo steps, `InkLayer.endErase()` pushes one `Replaced(before, after)` if anything changed.
  - `class TapGestures(private val maxDurationMs: Long = 250, private val slopPx: Float) { fun onDown(pointerId: Int, x: Float, y: Float, t: Long); fun onMove(pointerId: Int, x: Float, y: Float); fun onUp(pointerId: Int, t: Long): Tap? }` with `enum class Tap { TWO_FINGER, THREE_FINGER }` — returns a tap when the last finger lifts, all fingers stayed within `slopPx`, the whole contact lasted ≤ `maxDurationMs`, and 2 (or 3) fingers were down at once.
  - `StylusTracker.onScreenChanged()` (called by `MainShell` whenever `nav.current` changes) and `StylusTracker.fingerDraws(): Boolean` = no stylus seen since the last screen change. `isStylusActive` is kept only for the review cells' existing behaviour.
  - `PalmFilter.isPalm(touchMajorPx: Float, density: Float): Boolean = touchMajorPx / density > 100f`.
- Rules: two-finger tap → `layer.undo()`, three-finger tap → `layer.redo()` (only on canvases with a viewport — not in review cells). A finger draws only when `StylusTracker.fingerDraws()` and the touch is not a palm and not `FLAG_CANCELED`; otherwise it pans. Pinch keeps working; a pinch never produces a tap (movement > slop).

- [ ] **Step 1: Failing tests:**
  - `InkLayerEraseTest.oneEraserDragIsOneUndoStep`: layer with strokes A, B, C; `beginErase()`, `eraseAt` over A, then over B, `endErase()`; strokes = [C]; one `undo()` → [A, B, C]; `canUndo == false`.
  - `InkLayerEraseTest.eraseGestureThatHitsNothingAddsNoStep`.
  - `TapGesturesTest`: two downs within 30 ms, ups at +120 ms, no movement → `TWO_FINGER`; same with one finger moving 3×slop → null; contact 400 ms → null; three fingers → `THREE_FINGER`; one finger → null.
  - `StylusTrackerTest`: after `onStylusEvent()` `fingerDraws() == false`; after `onScreenChanged()` → true.
- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3: Implement** as described; the eraser in `InkInputHandler` calls `beginErase`/`endErase` around a drag and `onStrokeFinished()` once at the end; finger routing uses `fingerDraws()` and `PalmFilter`.
- [ ] **Step 4:** JVM tests pass.
- [ ] **Step 5: Commit** — `git commit -m "Canvas: one undo step per eraser drag, two/three-finger tap undo/redo, pen rules per screen, palm filter"`

### Task 4: One-row toolbar and remembered tools

**Files:**
- Modify: `app/src/main/kotlin/app/wanki/input/Tools.kt`, `ui/common/InkToolbar.kt`, `data/Settings.kt`, `ui/notebooks/PageEditor.kt` (tool source), `ui/notes/NotesScreen.kt`
- Test: `app/src/test/kotlin/app/wanki/input/ToolSelectionCodecTest.kt`

**Interfaces:**
- Produces:
  - `ToolSelection.PALETTE` = `0xFF1A1A1A, 0xFF5F6368, 0xFF1E5BD8, 0xFFD23C3C, 0xFF2E9E5B, 0xFFE0A100, 0xFF8E44AD, 0xFFE67E22`; `ToolSelection.SIZES` = `1.5f, 2.5f, 4f, 7f, 11f`.
  - `fun ToolSelection.encode(): String` / `fun ToolSelection.Companion.decode(s: String?): ToolSelection` (bad input → default).
  - `enum class InkSection { NOTEBOOK, DIARY, BOARD, NOTE }`; `Settings.tool(section: InkSection): ToolSelection`, `Settings.setTool(section: InkSection, t: ToolSelection)` (keys `tool.NOTEBOOK` etc.).
  - `InkToolbar(tool, onTool, layer, modifier, tools = ToolKind.entries)` — the `trailing` slot is removed.
- Layout: one row, no horizontal scroll: icon toggle buttons for the tools (`stylus` pen, `ink_marker`, `ink_highlighter`, `ink_eraser`, `lasso_select`), then `undo`, `redo` (disabled look only when actually disabled). Tapping the already selected pen/marker/highlighter opens a popup: 8 colour swatches, «Свой цвет» (dialog with hue and brightness sliders), 5 sizes — each swatch/size ≥ 48 dp touch target with a description («Цвет: синий», «Толщина 3 из 5»). The selected tool shows its current colour as a small dot under the icon. Tags: `tool-PEN`, `tool-MARKER`, `tool-HIGHLIGHTER`, `tool-ERASER`, `tool-LASSO`, `undo`, `redo`, `tool-options`.

- [ ] **Step 1: Failing test** `ToolSelectionCodecTest`: `ToolSelection(ToolKind.MARKER, 0xFF8E44AD.toInt(), 7f)` round-trips; `decode(null)`, `decode("")`, `decode("BOGUS:1:2")` → `ToolSelection()`; `decode` of an ERASER/LASSO selection keeps its last colour and size.
- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3: Implement**; `PageEditorState.tool` starts from `graph.settings.tool(section)` (section from the notebook kind) and every change is written back; notes use `InkSection.NOTE`.
- [ ] **Step 4:** JVM tests pass; build; check by eye at 360 dp (phone-size override on 5556, `wm size 1080x2400`, `wm density 420`, restore afterwards) that the toolbar fits with no scroll, at font scale 1.0 and 1.3 — save screenshots to `.superpowers/sdd/<run>/shots-p1-task4/`.
- [ ] **Step 5: Commit** — `git commit -m "One-row ink toolbar with icons and colour/size popup; tool remembered per section"`

### Task 5: Save after every gesture, report failures, remember the view

**Files:**
- Create: `app/src/main/kotlin/app/wanki/ui/common/SerialSaver.kt`
- Modify: `ui/notebooks/PageEditor.kt` (replace the 800 ms autosave; recognition keeps its 2.5 s pause), `ui/notes/NotesScreen.kt` (replace the 700 ms autosave), `data/Entities.kt` (`PageEntity` + `viewScale: Float? = null, viewX: Float? = null, viewY: Float? = null`), `data/WankiDatabase.kt` (`VERSION = 7`, `MIGRATION_6_7`), `data/Daos.kt` / `data/Repository.kt` (`setPageView`), `input/Viewport.kt` (`restore(scale, x, y)`), `app/schemas/…/7.json`
- Test: `app/src/test/kotlin/app/wanki/ui/common/SerialSaverTest.kt`, `app/src/androidTest/kotlin/app/wanki/data/MigrationTest.kt` (`migrate6To7AddsPageView`)

**Interfaces:**
- Produces:
```kotlin
class SerialSaver(private val scope: CoroutineScope, private val save: suspend () -> Unit) {
    val error: StateFlow<Throwable?>
    fun request()            // runs save now, or once more right after the running one
    suspend fun flush()      // waits until nothing is running or pending
}
```
  `Repository.setPageView(pageId: String, scale: Float, x: Float, y: Float)`; `Viewport.restore(scale: Float, offsetX: Float, offsetY: Float)` (clamped like `transform`; applied after the first `onScreenSize`, instead of `fitWidth`, when the page has a stored view).
- Behaviour: `PageEditor` calls `saver.request()` whenever `layer.version` changes (it only changes when a gesture ends — Task 3); errors show a snackbar «Не удалось сохранить» with action «Повторить» (= `request()`); on dispose `appScope.launch { saver.flush(); saveView(); recognizeIfDirty() }`. Notes request a save on every text change and every ink gesture.

- [ ] **Step 1: Failing tests** `SerialSaverTest` (with `runTest` and a `StandardTestDispatcher`): `requestWhileRunningRunsOnceMore` (3 requests during one slow save → exactly 2 runs); `noOverlap` (max concurrent runs = 1); `failedSaveKeepsErrorAndRetries` (first save throws → `error.value != null`; next `request()` succeeds → `error.value == null`); `flushWaitsForPending`. `MigrationTest.migrate6To7AddsPageView`: a v6 `pages` row survives, the three columns exist and are null.
- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3: Implement**; export schema 7.
- [ ] **Step 4:** JVM tests pass; `ANDROID_SERIAL=emulator-5556 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.wanki.data.MigrationTest` → PASS.
- [ ] **Step 5: Commit** — `git commit -m "Save after every gesture with visible errors; page zoom and position remembered (DB v7)"`

### Task 6: Ink screens fit a phone

**Files:**
- Modify: `ui/notebooks/NotebooksScreen.kt` (`NotebookScreen`), `ui/notebooks/PageEditor.kt`, `ui/diary/DiaryScreen.kt` (day screen), `ui/board/BoardScreen.kt`, `ui/notes/NotesScreen.kt`, `ui/MainShell.kt` (`Route.Note` immersive), `androidTest/.../RecreateTest.kt`
- Create: `app/src/main/kotlin/app/wanki/ui/notebooks/PageNavBar.kt`

**Interfaces:**
- Consumes: `ScreenHeader(primary, menu)` and `HeaderAction` (Task 2), `InkToolbar` (Task 4).
- Layout decisions:
  - Notebook page: header = back, title = notebook title, subtitle = «стр. 3 из 12»; primary: «Добавить страницу» (`add`, tag `add-page`), «Текст» (`notes`, tag `show-text`); menu: «Бумага…» (tag `menu-template`, opens the template list), «Вписать» (`fit_screen`, tag `fit`). Under the canvas a `PageNavBar`: `chevron_left` (tag `page-prev`), «3 / 12» (tap → dialog with a number field, tag `page-jump`), `chevron_right` (tag `page-next`).
  - Diary day: header title = the date, menu: «Бумага…», «Вписать», «Текст».
  - Board: header = back + title; menu: «Вписать», «Текст» (rename/duplicate/delete arrive in П2 — the existing «Удалить» moves into this menu with its confirmation).
  - «Текст» panel: side panel 280 dp only when the window is ≥ 600 dp wide; below that a bottom sheet (`ModalBottomSheet`) at most 50 % of the height.
  - Note editor is immersive (add `Route.Note` to the `immersive` list); its title shows the note title; the toolbar's undo/redo act on the handwriting block touched last (track it in the note screen state; today `blocks.firstNotNullOf { it.layer }` is used).
  - A new page appears immediately: no blank frame after «Добавить страницу» (keep showing the current page until the new one arrives, then switch).

- [ ] **Step 1:** update `RecreateTest` to the new tags (`page-next`, subtitle «стр. 2 из 2») and add `RecreateTest.addPageWorksAtPhoneWidth`: with the phone-size override (or on `wanki-phone`) tap `add-page`, assert subtitle «стр. 2 из 2». Run → FAIL.
- [ ] **Step 2: Implement** the layouts above.
- [ ] **Step 3:** `RecreateTest` passes on 5556 with `wm size 1080x2400`/`wm density 420` and without; screenshots of notebook page, diary day, board, note at 360 dp and tablet into `shots-p1-task6/`, looked at: nothing clipped, toolbar one row.
- [ ] **Step 4: Commit** — `git commit -m "Notebook, diary, board and note screens fit a phone: header menu, page bar, text sheet"`

### Task 7: Honest diary, backups for ink, list states, writing-lab ink

**Files:**
- Modify: `data/Daos.kt` (`observeDiaryDates`), `data/Repository.kt` (`saveInk`), `ui/diary/DiaryScreen.kt` (future days), `data/Backup.kt` (`hasContent`, `Checked`), `ui/settings/SettingsScreen.kt` (restore text), `writing/WritingTestScreen.kt` (ink colour), list screens of notebooks, notes, diary, boards
- Create: `app/src/main/kotlin/app/wanki/ui/common/ListState.kt`
- Test: `app/src/androidTest/kotlin/app/wanki/data/RepositoryTest.kt` (new cases)

**Interfaces:**
- Produces:
  - `observeDiaryDates` returns only days whose page has ink with at least one stroke: query joins `page_ink` and `saveInk` **deletes** the `page_ink` row when `strokes` is empty (so an emptied page stops counting).
  - `Backup.hasContent()` = any deck, text, notebook (any kind) with a non-deleted page that has ink, or text note.
  - `Backup.Checked` gains `texts: Int, notebooks: Int, pages: Int, notes: Int`; the restore dialog lists «карточек: N, текстов: N, тетрадей: N, страниц: N, заметок: N».
  - `@Composable fun ListState(loading: Boolean, isEmpty: Boolean, emptyTitle: String, emptyHint: String, emptyAction: HeaderAction?, error: String? = null, onRetry: (() -> Unit)? = null, content: @Composable () -> Unit)` — spinner while loading, an empty state with an icon, a title, a hint and one button, an error with «Повторить».
- Behaviour: diary calendar cells after today are not clickable; empty texts: Тетради «Пока нет тетрадей» / «Пишите от руки: конспекты, прописи, черновики» + «Новая тетрадь»; Заметки «Пока нет заметок» / «Текст и рукописные блоки вперемешку» + «Новая заметка»; Доски «Пока нет досок» / «Большой холст для схем и рисунков» + «Новая доска»; Дневник has no empty state (calendar). Writing lab ink = `ToolSelection.DEFAULT_INK` always (paper is always light).

- [ ] **Step 1: Failing instrumented tests** in `RepositoryTest`: `openedDiaryDayIsNotMarked` (`diaryPage(day)` without saving ink → dates empty); `emptiedDiaryDayIsNotMarked` (save one stroke → marked; save empty → not marked); `hasContentCountsNotebookInk` (fresh DB → false; a notebook page with one stroke → true); `hasContentCountsNotes`.
- [ ] **Step 2:** `ANDROID_SERIAL=emulator-5556 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.wanki.data.RepositoryTest` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** same command → PASS; `./gradlew test :app:testDebugUnitTest` → PASS.
- [ ] **Step 5: Commit** — `git commit -m "Diary marks only written days; auto backup and restore summary include notebooks and notes; list states"`

### Task 8: Full verification and docs

**Files:**
- Modify: `app/src/androidTest/kotlin/app/wanki/SmokeTest.kt`, `docs/PROGRESS.md`, `CLAUDE.md`

- [ ] **Step 1: Smoke steps** in `walkThroughAllSections` (adjust existing notebook/diary/board/note steps to the new tags): in a notebook draw → tap `add-page` → subtitle «стр. 2 из 2» → `page-prev` → shot `20-notebook-p1`; open `tool-options` → shot `21-tool-popup`; in the diary open yesterday, go back without writing → the day has no mark (assert via a test tag on the cell `diary-day-<epochDay>` with semantics `stateDescription` «есть запись»/none) → shot `22-diary`.
- [ ] **Step 2:** `./gradlew test :app:assembleDebug :app:testDebugUnitTest` → PASS.
- [ ] **Step 3:** full `connectedDebugAndroidTest` on `wanki-phone` (`-port 5558`, `ANDROID_SERIAL=emulator-5558`), then — after stopping it — on `wanki-tablet` (5556) → PASS on both; contact sheet of the smoke screenshots (`python3 tools/screens/sheet.py out.png 4 360 *.png`).
- [ ] **Step 4:** `./gradlew :app:assembleRelease`, install on the phone emulator only, rotate inside a notebook page, draw, erase with one drag and undo once, add a page — no crash, state kept.
- [ ] **Step 5: Docs:** `docs/PROGRESS.md` block «Этап П1» marked done with test counts; `CLAUDE.md` conventions: «Navigation state is saveable (`RouteCodec`); MainActivity handles configuration changes itself», «Ink saves after every finished gesture through `SerialSaver`; errors are shown, never swallowed», «Icons are Material Symbols vector drawables in res/drawable».
- [ ] **Step 6: Commit** — `git commit -m "Stage П1 done: foundation of the ink sections"`
