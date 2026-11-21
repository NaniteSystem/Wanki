# Wanki

Anki-like Android app with handwriting answers, notebooks, diary, boards and notes.
Plan and decisions: `docs/PLAN.md` (Russian). The user writes in Russian.

## Modules
- `core/scoring` (JVM) — handwriting scoring against KanjiVG: normalization, Hungarian stroke
  matching, style grouping (connected/broken strokes), metrics, look-alike check, beauty score.
- `core/ink` (JVM) — stroke model, `InkCodec` binary format (v3), `SpatialGrid`, `InkAffine`, `StrokeEraser`, `StraightLine`, `LineKeys`,
  `LineSegmenter`, pen `Gestures`.
- `core/srs` (JVM) — FSRS-6 port of py-fsrs, verified against reference data in tests.
- `core/texts` (JVM) — texts learned by heart: `PieceSplitter` (verse lines / prose sentences), `Words`,
  `VanishingCues`, `FirstLetters`, `Chain`, `TextRating` (recital → FSRS rating), `Portions`, `FsrsTextPlan`,
  `HifzPlan` (sabaq/sabqi/manzil), `ScheduleSwitch`, `WeakPieces`, `TextLoad`; typing practice: `TypingText`
  (folding for comparison), `TypingMatcher` (incremental alignment of typed text to word units, rows banded to ±512
  reading chars), `PieceWords` (stored per-piece word lists, ja readings; number + counter tokens merged into one word),
  `ReadingMarkup` (`{表記|よみ}`), `JapaneseNumbers`, `JapaneseReadings` (frequent words with two readings), `WordHistory`, `TypingScore`.
- `app` (Android, Compose, Room, androidx.ink, ML Kit Digital Ink / Language ID / Translate).
  Packages: `input` (canvas + touch handling), `data` (Room, `DeckTree` limits, `CardQuery` search,
  `Backup`), `recognition` (ML Kit, TTS `Speaker`), `review`, `practice` (weak-character queue and
  handwriting practice), `reminders`, `importer` (Anki .apkg, CSV/TSV), `ui/*` screens (`browser` =
  card browser + editor), `writing` (writing lab, review drawing), `texts` (`TextWords`: ICU word
  breaking for ja/zh), `ui/texts` (texts list, editor, detail, study screen with cue modes, `VoiceBar`
  recording, `TypingSession`/`TypingBody` = «Печать» mode); `data/TextRepository` owns texts, pieces and the
  recitation log (DB v8); `KuromojiMorphology` is the Japanese analyser (release APK +13.4 MB).

## Build & test
```
./gradlew test                                   # JVM modules
./gradlew :app:assembleDebug :app:testDebugUnitTest   # needs Android SDK (ANDROID_HOME or local.properties)
./gradlew :app:connectedDebugAndroidTest         # migrations, repositories, smoke test (SmokeTest.kt) on an emulator
```
Without an SDK, settings.gradle.kts includes only the JVM modules. Toolchain: Gradle 9.4.1,
Kotlin 2.3.20, AGP 9.1 (built-in Kotlin), KSP 2.3.6. Plugins are on the root buildscript
classpath (see build.gradle.kts) so AGP and KGP share a classloader.

## Conventions
- UI state changes after suspend work must resume on main: use `ui { }` / `rememberUiScope()`
  (ui/common/Ui.kt). Fire-and-forget saves go to `graph.appScope`.
- Stroke points are captured from MotionEvents in `InkInputHandler`; androidx.ink only renders
  the stroke in progress. Stylus vs finger is automatic (`StylusTracker`), no mode switches.
- Scoring rules agreed with the user: <60% = Again; missing/extra stroke = wrong unless it is
  handwriting style or the recognizer's first candidate is the target; handwriting quality never
  affects FSRS ratings.
- Room schema changes need a migration (`WankiDatabase`), never destructive fallback. Schemas are
  exported to `app/schemas` (commit them); `MigrationTest` checks each migration on a real SQLite.
- Daily limits follow Anki: a deck's limit caps its whole subtree, learning cards are unlimited; the
  card state before a review is logged (`cardState`) to count today's new/review cards.
- Handwriting results are stored per character (`char_scores`); a character is "weak" when the
  average beauty of its last 5 attempts is below 75. Practice never touches FSRS.
- Text practice (free recital, `StudyKind.PRACTICE`) never touches schedules: it is only logged.
- A used hint (an opened cue word) means a recital is not clean: it cannot pass a cue level or count as
  a clean recall.
- Text content is stored and shown exactly as saved; normalization (case, marks, letter variants) only
  inside comparisons.
- Each piece stores its word list (`text_pieces.words`, `PieceWords`); it is recomputed when the content,
  language or readings change. Per-word typing stats (`text_word_stats`) reset when the word's piece is edited
  (`WordHistory`). `{表記|よみ}` markup is accepted in any text. Typed runs are logged with recitation mode `TYPED`; unfinished attempts live in `typing_drafts`.
- Writing canvases stay paper-colored (`WritingPaper`) in the dark theme.
- Navigation state is saveable (`RouteCodec`); MainActivity handles configuration changes itself.
- Ink saves after every finished gesture through `SerialSaver`; errors are shown, never swallowed.
- Ink format is `InkCodec` v3: one length-prefixed, self-contained chunk per stroke (`encodeStroke`/`assemble`,
  `strokeKey` = content hash of the chunk); decode still reads v1 and v2. A save joins `InkLayer`'s cached chunks.
  A blob that fails to decode opens the page read-only and is never overwritten.
- `InkLayer.strokes`/`strokesIn`/the grid are main-thread only; render off main from a snapshot. Canvases with a
  viewport (pages, boards) draw through `InkTiles` (512 px bitmaps per power-of-two zoom level, 64 MB budget or a
  quarter of the heap); note blocks and review cells draw directly. Live lasso transforms use `InkTiles.exclude`.
- Pages own `page_objects` (text blocks and pictures, drawn under the ink; `MASK` = tape, drawn over the ink, open/closed
  is view state only) and `page_lines` (recognised text per line, keyed by an order-independent `lineKey` of its stroke
  chunks; corrections stay while the key exists; `page_ink.recognizedText` stays the joined text for search). DB v11.
- Cards remember their source in `source_refs` (П4a wires `PAGE`): one-off cards (from a selection) are free copies,
  the ref only navigates; linked cards (from tape) are owned by the page — read-only fields, mask deleted or page purged →
  card suspended + ref `detached` (undo un-suspends only what the detach suspended), masks moved/copied repoint or get
  new ids. A mask card's fields hold `[mask:<refId>]` (ref id, stable when the mask moves); `CardBlock.Mask` renders the
  page live around the mask (`renderMaskCard`), «Источник удалён» when gone.
- Page media are `page-<uuid>.jpg|png|pdf` in the media folder (pictures ≤ 2048 px, imported PDFs kept whole as
  backgrounds). The daily purge deletes unreferenced `page-*` files older than 24 h; pages in the trash keep theirs.
  Memory caches are bounded by bytes: tiles 64 MB, pictures min(32 MB, heap/8), PDF backgrounds 48 MB.
- Files shared out (camera shots, PDF exports in `cache/exports/`) go through the FileProvider authority
  `${applicationId}.files` (`res/xml/file_paths.xml`).
- Eraser mode and size are remembered per ink section (`Settings.eraser(section)`), apart from the pen tool.
- Trash: deleting a notebook, board, note, page or diary day only sets `deleted` + `deletedAt`; items stay 30 days
  (`Repository.delete(kind, id)` trashes, `trash()` lists the trash, `restore` brings an item back, `purge` removes it), `purgeExpired` removes the rest for good once a day (`trashPurgedDay`). Lists sort by
  modified/title/created with pinned first (choice remembered per list); page order is rewritten in one transaction
  (`movePage`). Every delete shows the shell snackbar through `LocalUndo`.
- Icons are Material Symbols vector drawables in res/drawable.
- Release builds use R8: anything found by reflection needs a keep rule (`app/proguard-rules.pro`).
- Instrumented tests: run one emulator at a time locally (two at once make Compose screenshots flaky).
  Locally `wanki-phone` runs on port 5558 (`-port 5558`) and `wanki-tablet` on 5556; `emulator-5554`
  belongs to another session. Never run tests on the physical device `320147012717`.

## CI
GitHub Actions builds, runs tests, uploads the debug APK (`wanki-debug-apk`) and runs the smoke
test on phone + tablet emulators; screenshots and logs are pushed to branches
`ci-screenshots-phone` / `ci-screenshots-tablet`.
