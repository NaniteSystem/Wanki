# Wanki

Anki-like Android app with handwriting answers, notebooks, diary, boards and notes.
Plan and decisions: `docs/PLAN.md` (Russian). The user writes in Russian.

## Modules
- `core/scoring` (JVM) — handwriting scoring against KanjiVG: normalization, Hungarian stroke
  matching, style grouping (connected/broken strokes), metrics, look-alike check, beauty score.
- `core/ink` (JVM) — stroke model, `InkCodec` binary format, `LineSegmenter`, pen `Gestures`.
- `core/srs` (JVM) — FSRS-6 port of py-fsrs, verified against reference data in tests.
- `app` (Android, Compose, Room, androidx.ink, ML Kit Digital Ink / Language ID / Translate).
  Packages: `input` (canvas + touch handling), `data` (Room, `DeckTree` limits, `CardQuery` search,
  `Backup`), `recognition` (ML Kit, TTS `Speaker`), `review`, `practice` (weak-character queue and
  handwriting practice), `reminders`, `importer` (Anki .apkg, CSV/TSV), `ui/*` screens (`browser` =
  card browser + editor), `writing` (writing lab, review drawing).

## Build & test
```
./gradlew test                                   # JVM modules
./gradlew :app:assembleDebug :app:testDebugUnitTest   # needs Android SDK (ANDROID_HOME or local.properties)
./gradlew :app:connectedDebugAndroidTest         # smoke test on a device/emulator (SmokeTest.kt)
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
- Writing canvases stay paper-colored (`WritingPaper`) in the dark theme.
- Release builds use R8: anything found by reflection needs a keep rule (`app/proguard-rules.pro`).
- Instrumented tests: run one emulator at a time locally (two at once make Compose screenshots flaky).

## CI
GitHub Actions builds, runs tests, uploads the debug APK (`wanki-debug-apk`) and runs the smoke
test on phone + tablet emulators; screenshots and logs are pushed to branches
`ci-screenshots-phone` / `ci-screenshots-tablet`.
