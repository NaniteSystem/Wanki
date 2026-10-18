# Stage A — Texts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Learn any text by heart in Wanki: split it into pieces, learn portions with vanishing cues / first letters / chain / full recital, record your own voice, and review either by FSRS (default) or by the sabaq/sabqi/manzil plan.

**Architecture:** All planning logic (splitting, word boundaries, cue levels, chain steps, ratings, portions, hifz plan, schedule switching, weak pieces) lives in a new pure-JVM module `:core:texts` with fast tests. The app gets three Room tables (DB v6), a `TextRepository` that maps rows to the core types and writes results in transactions, and Compose screens reached from the Decks screen (no new tab).

**Tech Stack:** Kotlin 2.3.20, Jetpack Compose (BOM 2026.03.00), Room + KSP, existing FSRS-6 port `:core:srs`, `android.icu.text.BreakIterator`, `MediaRecorder`/`MediaPlayer`, `TextToSpeech` (existing `Speaker`).

**Spec:** `docs/superpowers/specs/2026-10-19-memory-methods-design.md` — sections 2–4 and 6 (stage A). Read it before starting.

## Global Constraints

- Room schema changes only via a migration with a `MigrationTest` case; never destructive fallback; commit `app/schemas/app.wanki.data.WankiDatabase/6.json`.
- Instrumented tests only on the emulator: `ANDROID_SERIAL=emulator-5554`; one emulator at a time. **Never run tests on the user's phone (serial `320147012717`).**
- Start the emulator: `~/Library/Android/sdk/emulator/emulator -avd wanki-phone -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot-save &`
- Commits local only, never push. End every commit message with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- UI text in Russian; code, identifiers and comments in English. Match the surrounding code's comment density.
- UI state changes after suspend work resume on main: `ui { }` / `rememberUiScope()`; fire-and-forget saves go to `graph.appScope`.
- No new bottom-navigation tab; texts are reached from the Decks screen.
- Text content is stored and shown exactly as saved; normalization (case, marks, letter variants) only inside comparisons.
- Nothing in this stage needs the network.
- Release builds use R8: anything found by reflection needs a keep rule in `app/proguard-rules.pro`.
- After each task: append a line to the "Этап A" block of `docs/PROGRESS.md` (create the block in Task 1).

## Review Focus

1. **Empty or punctuation-only text** → no pieces; the editor must not save a text with zero pieces (Task 1 splitter test, Task 6 `createTextRejectsEmpty`).
2. **Manzil pointer to a piece that was deleted or joined away** → the chunk starts from the first manzil piece instead of crashing or skipping everything (Task 3 `pointerToMissingPieceStartsFromFirst`).
3. **Switching schedule back and forth (FSRS → hifz → FSRS)** → history is never lost; FSRS state equals a replay of the log (Task 6 `switchingTwiceKeepsHistory`).
4. **Arabic text with harakat and Quranic marks** → marks stay inside words, first-letter checks ignore them, the saved content round-trips byte-for-byte (Task 1 `arabicMarksStayInsideWords`, Task 2 `arabicVariantsMatch`, Task 6 `contentRoundTripsVerbatim`).
5. **A paused hifz/FSRS text** → no new portions, but its sabqi, manzil and FSRS reviews keep coming so nothing is forgotten (Task 3 `pausedTextGetsNoPortionButKeepsReviews`).

---

### Task 1: `:core:texts` module — pieces and words

**Files:**
- Create: `core/texts/build.gradle.kts` (copy of `core/srs/build.gradle.kts` plus `implementation(project(":core:srs"))`; comment line: `// Pure JVM: planning logic for texts learned by heart.`)
- Modify: `settings.gradle.kts` — `include(":core:texts")` after `:core:srs`
- Modify: `app/build.gradle.kts` — `implementation(project(":core:texts"))` next to the other core modules
- Create: `core/texts/src/main/kotlin/app/wanki/texts/Pieces.kt`
- Create: `core/texts/src/main/kotlin/app/wanki/texts/Words.kt`
- Test: `core/texts/src/test/kotlin/app/wanki/texts/PiecesTest.kt`, `.../WordsTest.kt`
- Modify: `docs/PROGRESS.md` — add "## Этап A — тексты" block with a link to this plan

**Interfaces:**
- Produces:
  - `enum class TextKind { POEM, PROSE }`, `enum class TextSchedule { FSRS, HIFZ }` (in `Pieces.kt`)
  - `object PieceSplitter { fun split(text: String, kind: TextKind): List<String> }`
  - `data class Word(val start: Int, val end: Int)` — `[start, end)` offsets in the piece
  - `fun interface WordBreaker { fun words(text: String): List<Word> }`
  - `object Words { val Spaced: WordBreaker; fun needsDictionary(language: String): Boolean; fun isRtl(language: String): Boolean }`

- [ ] **Step 1: Write the failing tests**

```kotlin
class PiecesTest {
    @Test fun poemSplitsByNonBlankLines() =
        assertEquals(listOf("Мороз и солнце; день чудесный!", "Ещё ты дремлешь, друг прелестный —"),
            PieceSplitter.split("Мороз и солнце; день чудесный!\n\n  Ещё ты дремлешь, друг прелестный —  \n", TextKind.POEM))
    @Test fun proseSplitsBySentences() =
        assertEquals(listOf("Hello there.", "How are you?", "«Fine!» she said."),
            PieceSplitter.split("Hello there. How are you? «Fine!» she said.", TextKind.PROSE))
    @Test fun proseKeepsClosingQuoteWithSentence() =
        assertEquals(listOf("Он сказал: «Иди.»", "Я пошёл."), PieceSplitter.split("Он сказал: «Иди.» Я пошёл.", TextKind.PROSE))
    @Test fun cjkTerminatorsBreakWithoutSpaces() =
        assertEquals(listOf("今日は晴れです。", "散歩しましょう！"), PieceSplitter.split("今日は晴れです。散歩しましょう！", TextKind.PROSE))
    @Test fun arabicQuestionMarkBreaks() =
        assertEquals(listOf("ما اسمك؟", "اسمي علي."), PieceSplitter.split("ما اسمك؟ اسمي علي.", TextKind.PROSE))
    @Test fun newlineAlwaysBreaksProse() =
        assertEquals(listOf("Первая строка", "Вторая"), PieceSplitter.split("Первая строка\nВторая", TextKind.PROSE))
    @Test fun emptyAndPunctuationOnlyGiveNothing() {
        assertTrue(PieceSplitter.split("  \n \n", TextKind.POEM).isEmpty())
        assertTrue(PieceSplitter.split("— … !", TextKind.PROSE).isEmpty())
    }
}

class WordsTest {
    private fun words(s: String) = Words.Spaced.words(s).map { s.substring(it.start, it.end) }
    @Test fun punctuationIsNotAWord() = assertEquals(listOf("Мороз", "и", "солнце", "день", "чудесный"), words("Мороз и солнце; день чудесный!"))
    @Test fun innerApostropheAndHyphenStay() = assertEquals(listOf("don't", "well-known", "кто-то"), words("don't well-known, кто-то."))
    @Test fun arabicMarksStayInsideWords() = assertEquals(listOf("بِسْمِ", "اللَّهِ", "الرَّحْمَٰنِ"), words("بِسْمِ اللَّهِ الرَّحْمَٰنِ"))
    @Test fun dictionaryLanguages() { assertTrue(Words.needsDictionary("ja")); assertTrue(Words.needsDictionary("zh")); assertFalse(Words.needsDictionary("ru")) }
    @Test fun rtlLanguages() { assertTrue(Words.isRtl("ar")); assertFalse(Words.isRtl("ja")) }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:texts:test` — Expected: compilation FAIL (unresolved `PieceSplitter`, `Words`).

- [ ] **Step 3: Implement**

`PieceSplitter.split`: POEM — trimmed lines. PROSE — split on every newline, then inside a line break after a terminator `. ! ? … ؟` followed by optional closing quotes/brackets `» " ” ’ ) ]` and then whitespace or end, **unless the next non-space character is a lowercase letter** (keeps `«Fine!» she said.` and abbreviations like `т. е.` together); `。 ！ ？` break even without whitespace. For both kinds: trim pieces and drop pieces that contain no letter or digit (`\p{L}|\p{N}`), so `* * *` or `—` lines never become pieces.
`Words.Spaced`: regex `[\p{L}\p{M}\p{N}]+(?:['’\-][\p{L}\p{M}\p{N}]+)*` over the text.
`needsDictionary`: `ja`, `zh`, `th`. `isRtl`: `ar`, `he`, `fa`, `ur`.

- [ ] **Step 4: Run to verify pass** — `./gradlew :core:texts:test` → PASS.
- [ ] **Step 5: Commit** — `git add settings.gradle.kts app/build.gradle.kts core/texts docs/PROGRESS.md && git commit -m "Texts core module: piece splitting and word boundaries"`

---

### Task 2: Cue levels, first letters, chain steps

**Files:**
- Create: `core/texts/src/main/kotlin/app/wanki/texts/Cues.kt` (`VanishingCues`, `FirstLetters`), `.../Chain.kt`
- Test: `core/texts/src/test/kotlin/app/wanki/texts/CuesTest.kt`, `.../ChainTest.kt`

**Interfaces:**
- Produces:
  - `enum class WordDisplay { VISIBLE, FIRST_LETTER, HIDDEN }`
  - `object VanishingCues { const val MAX_LEVEL = 5; fun display(level: Int, wordCount: Int, seed: Long): List<WordDisplay> }`
  - `object FirstLetters { fun hint(word: String): String; fun matches(typed: String, word: String): Boolean }`
  - `data class ChainStep(val cue: Int?, val targets: IntRange)`; `object Chain { fun steps(count: Int): List<ChainStep> }`

- [ ] **Step 1: Write the failing tests**

```kotlin
class CuesTest {
    private fun hidden(level: Int, n: Int = 8, seed: Long = 42) =
        VanishingCues.display(level, n, seed).withIndex().filter { it.value == WordDisplay.HIDDEN }.map { it.index }.toSet()
    @Test fun levelZeroShowsEverything() = assertTrue(VanishingCues.display(0, 8, 42).all { it == WordDisplay.VISIBLE })
    @Test fun levelsHideAQuarterHalfThreeQuarters() = assertEquals(listOf(2, 4, 6), (1..3).map { hidden(it).size })
    @Test fun levelsAreNested() { assertTrue(hidden(2).containsAll(hidden(1))); assertTrue(hidden(3).containsAll(hidden(2))) }
    @Test fun sameSeedSameWords() = assertEquals(hidden(2, seed = 7), hidden(2, seed = 7))
    @Test fun oneWordIsHiddenAtLevelOne() = assertEquals(setOf(0), hidden(1, n = 1))
    @Test fun levelFourIsFirstLettersLevelFiveIsNothing() {
        assertTrue(VanishingCues.display(4, 5, 1).all { it == WordDisplay.FIRST_LETTER })
        assertTrue(VanishingCues.display(5, 5, 1).all { it == WordDisplay.HIDDEN })
    }
    @Test fun hintDropsMarks() { assertEquals("Ё", FirstLetters.hint("Ёлка")); assertEquals("ب", FirstLetters.hint("بِسْمِ")) }
    @Test fun matchIgnoresCaseAndYo() { assertTrue(FirstLetters.matches("е", "Ёлка")); assertTrue(FirstLetters.matches("м", "Мороз")) }
    @Test fun arabicVariantsMatch() {
        assertTrue(FirstLetters.matches("ا", "أَحَدٌ")); assertTrue(FirstLetters.matches("ا", "إِنَّا")); assertTrue(FirstLetters.matches("ا", "ٱللَّهُ"))
        assertTrue(FirstLetters.matches("ي", "ئ")); assertFalse(FirstLetters.matches("ب", "تَبَّتْ"))
    }
}

class ChainTest {
    @Test fun buildsUpWithCues() = assertEquals(
        listOf(ChainStep(null, 0..0), ChainStep(0, 1..1), ChainStep(null, 0..1), ChainStep(1, 2..2), ChainStep(null, 0..2)),
        Chain.steps(3),
    )
    @Test fun emptyTextHasNoSteps() = assertTrue(Chain.steps(0).isEmpty())
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :core:texts:test` → FAIL (unresolved).
- [ ] **Step 3: Implement**

`display`: levels 1–3 hide the first `ceil(share × n)` indices of `indices.shuffled(java.util.Random(seed))` with shares 0.25 / 0.5 / 0.75 (nested by construction); level 4 all `FIRST_LETTER`; level 5 all `HIDDEN`; level is clamped to `0..MAX_LEVEL`.
`hint`: the first letter code point of the word as stored (no NFD — `Ё` stays `Ё`), with the combining marks after it dropped (keep its case).
`matches`: compare the first base letter of `typed` and of `word` after: NFD, drop `\p{M}`, lowercase, `ё→е`, `آ أ إ ٱ → ا`, `ى → ي`, `ئ → ي`, `ؤ → و`, `ة → ه`, drop tatweel `ـ`.
`Chain.steps(count)`: for `i` in `0 until count`: `ChainStep(cue = i - 1 or null, targets = i..i)`, then for `i > 0` also `ChainStep(null, 0..i)`.

- [ ] **Step 4: Run to verify pass** — `./gradlew :core:texts:test` → PASS.
- [ ] **Step 5: Commit** — `git commit -m "Texts core: vanishing cue levels, first letters, chain steps"`

---

### Task 3: Ratings, portions, hifz plan, FSRS due list

**Files:**
- Create: `core/texts/src/main/kotlin/app/wanki/texts/Plan.kt` (`PlanPiece`, `PlanText`, `Portions`, `FsrsTextPlan`, `TextRating`, `RecallOutcome`), `.../HifzPlan.kt`
- Test: `core/texts/src/test/kotlin/app/wanki/texts/PlanTest.kt`, `.../HifzPlanTest.kt`

**Interfaces:**
- Consumes: `TextSchedule` (Task 1); `app.wanki.srs.Rating` from `:core:srs`.
- Produces:
```kotlin
data class PlanPiece(
    val id: String, val index: Int,
    val learnedDay: Long?,   // epoch day it was learned; null = not learned
    val due: Long = 0,       // FSRS due, epoch ms (FSRS texts)
    val sabqiLeft: Int = 0,  // hifz: sabqi recitations left; 0 = in manzil
    val hifzDay: Long? = null, // hifz: last epoch day counted in sabqi/manzil
)
data class PlanText(
    val id: String, val schedule: TextSchedule, val active: Boolean, val portionSize: Int,
    val order: Long, val lastPortionDay: Long?, val pieces: List<PlanPiece>, // pieces sorted by index
)
enum class RecallOutcome { FORGOT, ERRORS, CORRECT, EASY }
object TextRating { fun rating(outcome: RecallOutcome, usedHints: Boolean): Rating }
object Portions { fun next(text: PlanText, today: Long): List<String> }
object FsrsTextPlan { fun due(text: PlanText, until: Long): List<String> }
object HifzPlan {
    fun sabqi(text: PlanText, today: Long): List<String>
    fun manzilChunk(texts: List<PlanText>, pointer: String?, cycleDays: Int): List<String>
    fun nextPointer(texts: List<PlanText>, chunk: List<String>): String?
    fun learned(p: PlanPiece, today: Long, window: Int): PlanPiece
    fun afterSabqi(p: PlanPiece, error: Boolean, today: Long, window: Int): PlanPiece
    fun afterManzil(p: PlanPiece, error: Boolean, today: Long, window: Int): PlanPiece
}
```

- [ ] **Step 1: Write the failing tests** (helper `fun piece(i: Int, learned: Long? = null, left: Int = 0, hifzDay: Long? = null, due: Long = 0) = PlanPiece("p$i", i, learned, due, left, hifzDay)` and `fun text(vararg p: PlanPiece, schedule: TextSchedule = TextSchedule.HIFZ, active: Boolean = true, size: Int = 2, order: Long = 0, lastPortionDay: Long? = null, id: String = "t")`)

```kotlin
// PlanTest
@Test fun ratingRule() {
    assertEquals(Rating.AGAIN, TextRating.rating(RecallOutcome.FORGOT, false))
    assertEquals(Rating.HARD, TextRating.rating(RecallOutcome.ERRORS, false))
    assertEquals(Rating.GOOD, TextRating.rating(RecallOutcome.CORRECT, false))
    assertEquals(Rating.EASY, TextRating.rating(RecallOutcome.EASY, false))
    assertEquals(Rating.HARD, TextRating.rating(RecallOutcome.CORRECT, true))
    assertEquals(Rating.HARD, TextRating.rating(RecallOutcome.EASY, true))
}
@Test fun portionIsNextUnlearnedPieces() = assertEquals(listOf("p1", "p2"), Portions.next(text(piece(0, learned = 1), piece(1), piece(2), piece(3)), today = 10))
@Test fun onePortionPerDay() = assertTrue(Portions.next(text(piece(0), piece(1), lastPortionDay = 10), today = 10).isEmpty())
@Test fun pausedTextGetsNoPortionButKeepsReviews() {
    val t = text(piece(0, learned = 1, left = 3), piece(1), active = false)
    assertTrue(Portions.next(t, 10).isEmpty())
    assertEquals(listOf("p0"), HifzPlan.sabqi(t, 10))
    val f = text(piece(0, learned = 1, due = 500), piece(1), active = false, schedule = TextSchedule.FSRS)
    assertEquals(listOf("p0"), FsrsTextPlan.due(f, until = 1000))
}
@Test fun dueIsLearnedAndInOrder() = assertEquals(listOf("p0", "p2"),
    FsrsTextPlan.due(text(piece(0, learned = 1, due = 900), piece(1, learned = 1, due = 2000), piece(2, learned = 1, due = 100), piece(3, due = 0), schedule = TextSchedule.FSRS), until = 1000))

// HifzPlanTest
@Test fun learnedTodayIsNotInTodaysSabqi() = assertTrue(HifzPlan.sabqi(text(HifzPlan.learned(piece(0), 10, 7)), 10).isEmpty())
@Test fun sabqiCountsOncePerDay() {
    val once = HifzPlan.afterSabqi(piece(0, learned = 9, left = 7), error = false, today = 10, window = 7)
    assertEquals(6, once.sabqiLeft)
    assertEquals(6, HifzPlan.afterSabqi(once, error = false, today = 10, window = 7).sabqiLeft)
    assertTrue(HifzPlan.sabqi(text(once), 10).isEmpty())
    assertEquals(listOf("p0"), HifzPlan.sabqi(text(once), 11))
}
@Test fun errorRestartsSabqiWindow() = assertEquals(7, HifzPlan.afterSabqi(piece(0, learned = 1, left = 2), true, 10, 7).sabqiLeft)
@Test fun afterSevenCleanDaysItIsManzil() {
    var p = HifzPlan.learned(piece(0), 0, 7)
    for (d in 1L..7L) p = HifzPlan.afterSabqi(p, false, d, 7)
    assertEquals(0, p.sabqiLeft)
    assertTrue(HifzPlan.sabqi(text(p), 8).isEmpty())
    assertEquals(listOf("p0"), HifzPlan.manzilChunk(listOf(text(p)), null, 14))
}
@Test fun manzilErrorGoesBackToSabqi() = assertEquals(7, HifzPlan.afterManzil(piece(0, learned = 1), true, 20, 7).sabqiLeft)
@Test fun manzilChunkIsShareOfCycle() {
    val t = text(*Array(30) { piece(it, learned = 1) })
    assertEquals((0 until 3).map { "p$it" }, HifzPlan.manzilChunk(listOf(t), null, 14)) // ceil(30/14) = 3
    assertEquals((3 until 6).map { "p$it" }, HifzPlan.manzilChunk(listOf(t), "p3", 14))
}
@Test fun manzilWrapsAroundAndNeverRepeats() {
    val t = text(piece(0, learned = 1), piece(1, learned = 1), piece(2, learned = 1))
    assertEquals(listOf("p2", "p0"), HifzPlan.manzilChunk(listOf(t), "p2", 2))
    assertEquals("p1", HifzPlan.nextPointer(listOf(t), listOf("p2", "p0")))
    assertEquals(listOf("p0", "p1", "p2"), HifzPlan.manzilChunk(listOf(t), "p0", 1))
}
@Test fun manzilGoesAcrossTextsInOrderAndSkipsFsrsTexts() {
    val a = text(piece(0, learned = 1), id = "a", order = 2)
    val b = text(PlanPiece("b0", 0, 1), id = "b", order = 1)
    val f = text(PlanPiece("f0", 0, 1), id = "f", order = 0, schedule = TextSchedule.FSRS)
    assertEquals(listOf("b0", "p0"), HifzPlan.manzilChunk(listOf(a, b, f), null, 1))
}
@Test fun pointerToMissingPieceStartsFromFirst() =
    assertEquals(listOf("p0"), HifzPlan.manzilChunk(listOf(text(piece(0, learned = 1), piece(1, learned = 1))), "gone", 2))
@Test fun noManzilPiecesNoPointer() = assertNull(HifzPlan.nextPointer(listOf(text(piece(0))), emptyList()))
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :core:texts:test` → FAIL.
- [ ] **Step 3: Implement** the rules exactly as the tests pin them:
  - `Portions.next`: empty if `!active` or `lastPortionDay == today`; else first `portionSize` pieces with `learnedDay == null`.
  - `FsrsTextPlan.due`: pieces with `learnedDay != null && due <= until`, by index (paused texts included).
  - `HifzPlan.sabqi`: `learnedDay != null && sabqiLeft > 0 && learnedDay != today && hifzDay != today`, by index.
  - `manzilChunk`: pieces of `schedule == HIFZ` texts (active or paused) with `learnedDay != null && sabqiLeft == 0`, ordered by `text.order` then `index`; size `ceil(n / cycleDays)` (at least 1, at most n); start at the pointer's position (0 if absent/missing); circular.
  - `nextPointer`: id following the chunk's last element in that same ordering, circular; `null` when there are no manzil pieces.
  - `learned`: `learnedDay = today, sabqiLeft = window, hifzDay = null`.
  - `afterSabqi`: unchanged if `hifzDay == today`; error → `sabqiLeft = window`; else `sabqiLeft - 1` (min 0); `hifzDay = today`.
  - `afterManzil`: error → `sabqiLeft = window`; `hifzDay = today`.
- [ ] **Step 4: Run to verify pass** — PASS.
- [ ] **Step 5: Commit** — `git commit -m "Texts core: ratings, portions, sabaq/sabqi/manzil plan"`

---

### Task 4: Schedule switching, weak pieces, load estimate

**Files:**
- Create: `core/texts/src/main/kotlin/app/wanki/texts/ScheduleSwitch.kt`, `.../WeakPieces.kt` (also holds `TextLoad`)
- Test: `core/texts/src/test/kotlin/app/wanki/texts/ScheduleSwitchTest.kt`, `.../WeakPiecesTest.kt`

**Interfaces:**
- Consumes: `PlanPiece`, `TextSchedule`; `FsrsScheduler`, `SrsState`, `Rating`, `Review` from `:core:srs`.
- Produces:
```kotlin
data class PieceLog(val pieceId: String, val at: Long, val rating: Rating)
object ScheduleSwitch {
    fun toHifz(pieces: List<PlanPiece>, today: Long, window: Int): Map<String, Int> // learned pieces → sabqiLeft
    fun toFsrs(logs: List<PieceLog>, scheduler: FsrsScheduler, seeds: Map<String, Long>): Map<String, SrsState>
}
data class PieceOutcome(val pieceId: String, val at: Long, val error: Boolean)
object WeakPieces { const val RECENT = 5; const val ERRORS = 2; fun weak(outcomes: List<PieceOutcome>): Set<String> }
object TextLoad { fun daily(schedule: TextSchedule, portionSize: Int, window: Int): Int }
```

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun toHifzPutsRecentInSabqiOlderInManzil() = assertEquals(
    mapOf("p0" to 7, "p1" to 4, "p2" to 1, "p3" to 0),
    ScheduleSwitch.toHifz(listOf(PlanPiece("p0", 0, 10), PlanPiece("p1", 1, 7), PlanPiece("p2", 2, 4), PlanPiece("p3", 3, 2), PlanPiece("p4", 4, null)), today = 10, window = 7),
)
@Test fun toFsrsEqualsReplay() {
    val s = FsrsScheduler(enableFuzzing = false)
    val logs = listOf(PieceLog("p0", 1_000, Rating.GOOD), PieceLog("p0", 90_000_000, Rating.AGAIN), PieceLog("p1", 5_000, Rating.EASY))
    val out = ScheduleSwitch.toFsrs(logs, s, mapOf("p0" to 1L, "p1" to 2L))
    assertEquals(s.replay(listOf(Review(Rating.GOOD, 1_000), Review(Rating.AGAIN, 90_000_000)), 1L), out["p0"])
    assertEquals(setOf("p0", "p1"), out.keys)
}
@Test fun twoErrorsInLastFiveIsWeak() {
    val o = (1L..5L).map { PieceOutcome("a", it, it == 2L || it == 4L) } + (1L..5L).map { PieceOutcome("b", it, it == 1L) }
    assertEquals(setOf("a"), WeakPieces.weak(o))
}
@Test fun oldErrorsAgeOut() = assertTrue(WeakPieces.weak((1L..7L).map { PieceOutcome("a", it, it <= 2L) }).isEmpty())
@Test fun dailyLoad() { assertEquals(4, TextLoad.daily(TextSchedule.FSRS, 4, 7)); assertEquals(32, TextLoad.daily(TextSchedule.HIFZ, 4, 7)) }
```

- [ ] **Step 2: Run to verify failure** — FAIL.
- [ ] **Step 3: Implement** — `toHifz`: for learned pieces, `days = today - learnedDay`; `days < window` → `window - days` (≥ 1), else 0. `toFsrs`: group by piece, `scheduler.replay(logs.map { Review(it.rating, it.at) }, seeds[id] ?: 0)`. `weak`: per piece, the latest `RECENT` outcomes by `at`, weak if errors ≥ `ERRORS`. `TextLoad.daily`: FSRS → `portionSize`; HIFZ → `portionSize + portionSize * window`.
- [ ] **Step 4: Run to verify pass** — PASS.
- [ ] **Step 5: Commit** — `git commit -m "Texts core: schedule switching, weak pieces, daily load"`

---

### Task 5: Room tables and migration 5 → 6

**Files:**
- Create: `app/src/main/kotlin/app/wanki/data/TextEntities.kt`, `app/src/main/kotlin/app/wanki/data/TextDao.kt`
- Modify: `app/src/main/kotlin/app/wanki/data/WankiDatabase.kt` — entities, `abstract fun texts(): TextDao`, `VERSION = 6`, `MIGRATION_5_6` (KDoc `/** v6: texts learned by heart, their pieces and the recitation log. */`), add to `MIGRATIONS`
- Create (generated, commit): `app/schemas/app.wanki.data.WankiDatabase/6.json`
- Test: `app/src/androidTest/kotlin/app/wanki/data/MigrationTest.kt` — new case

**Interfaces:**
- Produces (column names are final; the migration SQL must match the generated `6.json` exactly — copy it from there):
```kotlin
@Entity(tableName = "texts")
data class TextEntity(
    @PrimaryKey val id: String = newId(), val title: String, val language: String,
    val kind: String = TextKind.POEM.name, val schedule: String = TextSchedule.FSRS.name,
    val portionSize: Int = 4, val active: Boolean = true, val lastPortionDay: Long? = null,
    val position: Long = System.currentTimeMillis(), val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(), val deleted: Boolean = false,
)
@Entity(tableName = "text_pieces", indices = [Index("textId")])
data class TextPieceEntity(
    @PrimaryKey val id: String = newId(), val textId: String, val pieceIndex: Int, val content: String,
    val note: String = "", val learnedAt: Long? = null,
    val state: String = "LEARNING", val step: Int? = 0, val stability: Double? = null, val difficulty: Double? = null,
    val due: Long = 0, val lastReview: Long? = null, val reps: Int = 0, val lapses: Int = 0,
    val seed: Long = UUID.randomUUID().mostSignificantBits,
    val sabqiLeft: Int = 0, val hifzDay: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)
enum class RecitationPart { NEW, SABAQ, REVIEW, SABQI, MANZIL, PRACTICE, KNOWN }
@Entity(tableName = "recitations", indices = [Index("textId"), Index("pieceId")])
data class RecitationEntity(
    @PrimaryKey val id: String = newId(), val textId: String, val pieceId: String, val at: Long,
    val part: String, val mode: String, val rating: Int?, val error: Boolean, val hints: Int = 0, val durationMs: Long = 0,
)
```
  `TextDao` — whatever queries Task 6 needs (observe/get/upsert texts, non-deleted count, pieces by text ordered by `pieceIndex`, observe pieces, upsert/delete pieces, pieces of all non-deleted HIFZ texts, insert/select recitations by text or by piece ids). `mode` records how the recital was checked: `SELF` (self-check — everything in this stage), `NONE` (part `KNOWN`); stage B adds `VOICE`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun migrate5To6AddsTexts() {
    helper.createDatabase(DB6, 5).close()
    val db = helper.runMigrationsAndValidate(DB6, 6, true, WankiDatabase.MIGRATION_5_6)
    db.execSQL("INSERT INTO texts (id, title, language, kind, schedule, portionSize, active, lastPortionDay, position, createdAt, updatedAt, deleted) VALUES ('t', 'Стих', 'ru', 'POEM', 'FSRS', 4, 1, NULL, 0, 0, 0, 0)")
    db.execSQL("INSERT INTO text_pieces (id, textId, pieceIndex, content, note, learnedAt, state, step, stability, difficulty, due, lastReview, reps, lapses, seed, sabqiLeft, hifzDay, updatedAt) VALUES ('p', 't', 0, 'Строка', '', NULL, 'LEARNING', 0, NULL, NULL, 0, NULL, 0, 0, 1, 0, NULL, 0)")
    db.execSQL("INSERT INTO recitations (id, textId, pieceId, at, part, mode, rating, error, hints, durationMs) VALUES ('r', 't', 'p', 0, 'NEW', 'SELF', 3, 0, 0, 0)")
    db.query("SELECT COUNT(*) FROM recitations").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
}
```
(add `const val DB6 = "migration-test-6"` to the companion)

- [ ] **Step 2: Build to generate the schema** — `./gradlew :app:assembleDebug` → creates `6.json`; write `MIGRATION_5_6` from its `createSql` statements (tables + indices).
- [ ] **Step 3: Run the migration test** — `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.wanki.data.MigrationTest` → all 4 cases PASS.
- [ ] **Step 4: Commit** — `git add app/src app/schemas && git commit -m "DB v6: texts, pieces, recitation log"`

---

### Task 6: `TextRepository`, hifz settings, backup condition, reminders

**Files:**
- Create: `app/src/main/kotlin/app/wanki/data/TextRepository.kt`
- Modify: `app/src/main/kotlin/app/wanki/data/Settings.kt` — four prefs below
- Modify: `app/src/main/kotlin/app/wanki/AppGraph.kt` — `val texts = TextRepository(db, settings)`
- Modify: `app/src/main/kotlin/app/wanki/data/Backup.kt` — extract `suspend fun hasContent(): Boolean = db.decks().count() > 0 || db.texts().count() > 0`; `autoBackupIfDue` uses it
- Modify: `app/src/main/kotlin/app/wanki/reminders/Reminders.kt` — `fire()` adds `context.graph.texts.today().pieces` as "текстов: N" and notifies when cards or texts are due
- Test: `app/src/androidTest/kotlin/app/wanki/data/TextRepositoryTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 1–5.
- Produces:
```kotlin
// Settings
var sabqiDays by pref("sabqiDays", 7)
var manzilCycleDays by pref("manzilCycleDays", 14)
/** Id of the piece the next manzil chunk starts from; "" = from the beginning. */
var manzilPointer by pref("manzilPointer", "")
/** Epoch day the last manzil chunk was finished. */
var manzilDoneDay by pref("manzilDoneDay", -1L)

// TextRepository
data class EditedPiece(val id: String?, val content: String, val note: String = "", val scheduleFrom: String? = null)
sealed interface TodayItem {
    val pieceIds: List<String>
    data class NewPortion(val text: TextEntity, override val pieceIds: List<String>) : TodayItem // "Сабак" for HIFZ, "Новое" for FSRS
    data class Review(val text: TextEntity, override val pieceIds: List<String>) : TodayItem
    data class Sabqi(val text: TextEntity, override val pieceIds: List<String>) : TodayItem
    data class Manzil(override val pieceIds: List<String>) : TodayItem
}
data class TextToday(val items: List<TodayItem>) { val pieces: Int get() = items.sumOf { it.pieceIds.size } }

class TextRepository(db: WankiDatabase, settings: Settings) {
    fun observeTexts(): Flow<List<TextEntity>>
    fun observeText(id: String): Flow<TextEntity?>
    fun observePieces(textId: String): Flow<List<TextPieceEntity>>
    suspend fun text(id: String): TextEntity?
    suspend fun pieces(textId: String): List<TextPieceEntity>
    suspend fun piecesByIds(ids: List<String>): List<TextPieceEntity>   // in the given order
    suspend fun createText(title: String, language: String, kind: TextKind, schedule: TextSchedule, portionSize: Int, content: String): TextEntity? // null when no pieces
    suspend fun saveText(text: TextEntity, pieces: List<EditedPiece>)
    suspend fun deleteText(id: String)
    suspend fun setActive(id: String, active: Boolean)
    suspend fun switchSchedule(id: String, to: TextSchedule, now: Long = System.currentTimeMillis())
    suspend fun markLearned(textId: String, upToIndex: Int, now: Long = System.currentTimeMillis())
    suspend fun completePortion(textId: String, pieceIds: List<String>, durationMs: Long, now: Long = System.currentTimeMillis())
    suspend fun reviewPiece(pieceId: String, rating: Rating, hints: Int, durationMs: Long, now: Long = System.currentTimeMillis())
    suspend fun completeSabqi(textId: String, pieceIds: List<String>, errorIds: Set<String>, durationMs: Long, now: Long = System.currentTimeMillis())
    suspend fun completeManzil(pieceIds: List<String>, errorIds: Set<String>, durationMs: Long, now: Long = System.currentTimeMillis())
    suspend fun logPractice(textId: String, pieceIds: List<String>, errorIds: Set<String>, hints: Int, durationMs: Long, now: Long = System.currentTimeMillis())
    suspend fun weakPieces(textId: String): Set<String>
    suspend fun nextPortion(textId: String, now: Long = System.currentTimeMillis()): List<String> // Portions.next for one text
    suspend fun today(now: Long = System.currentTimeMillis()): TextToday
    fun observeToday(): Flow<TextToday> // recomputed on Room invalidation of texts, text_pieces, recitations
}
```
Rules (from the spec, §6.5):
- Day = `LocalDate.now(zone).toEpochDay()` of `now`; a piece's `learnedDay` = day of `learnedAt`. Scheduler: `FsrsScheduler(desiredRetention = settings.desiredRetention.toDouble())`.
- `today()` order: all `NewPortion` (by text position), then `Review` (FSRS, `due <= now + Repository.LEARN_AHEAD_MS`), then `Sabqi`, then `Manzil` (omitted when `manzilDoneDay == today`); items with no pieces omitted.
- `completePortion`: every piece `learnedAt = now`; FSRS text → `scheduler.review(state, GOOD, now, seed)`; HIFZ → `HifzPlan.learned(…, window = settings.sabqiDays)`; log part `NEW` (FSRS) or `SABAQ` (HIFZ), rating 3; text `lastPortionDay = today`.
- `reviewPiece`: FSRS update, log `REVIEW` with the rating, `error = rating == AGAIN`.
- `completeSabqi` / `completeManzil`: `HifzPlan.afterSabqi/afterManzil`; log `SABQI`/`MANZIL`, rating 1 for errors and 3 otherwise; `completeManzil` sets `manzilPointer = HifzPlan.nextPointer(...)` and `manzilDoneDay = today`.
- `markLearned`: unlearned pieces with `pieceIndex <= upToIndex` get `learnedAt = now`; FSRS → review `EASY`; HIFZ → `sabqiLeft = 0`, `hifzDay = null`; log `KNOWN`, rating 4.
- `logPractice`: log `PRACTICE`, `rating = null`, `error` per piece; schedules untouched.
- Every log row gets `mode = "SELF"`, except `KNOWN` rows (`mode = "NONE"`).
- `switchSchedule`: FSRS→HIFZ applies `ScheduleSwitch.toHifz`; HIFZ→FSRS writes `ScheduleSwitch.toFsrs` built from the text's recitations with `rating != null` (PRACTICE excluded); then `schedule = to`.
- `saveText`: pieces with an `id` keep their row (content/note/index updated); `id == null` → new row, copying all schedule fields (learnedAt, FSRS, hifz) from `scheduleFrom` when given; existing pieces missing from the list are deleted; recitations are never deleted.
- `weakPieces`: `WeakPieces.weak` over all the text's recitations.

- [ ] **Step 1: Write the failing tests** (in-memory DB as in `RepositoryTest`; clear `Settings.NAME` prefs in `@Before` and build a fresh `Settings`; `now = LocalDate.of(2026, 10, 19).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()` so no step crosses midnight; `DAY = 86_400_000L`)

```kotlin
@Test fun createTextRejectsEmpty() = runBlocking { assertNull(texts.createText("x", "ru", TextKind.POEM, TextSchedule.FSRS, 4, " \n — ")) }
@Test fun contentRoundTripsVerbatim() = runBlocking {
    val src = "بِسْمِ ٱللَّهِ ٱلرَّحْمَٰنِ ٱلرَّحِيمِ\nٱلْحَمْدُ لِلَّهِ رَبِّ ٱلْعَٰلَمِينَ"
    val t = texts.createText("Фатиха", "ar", TextKind.POEM, TextSchedule.HIFZ, 3, src)!!
    assertEquals(src.lines(), texts.pieces(t.id).map { it.content })
}
@Test fun portionThenReviewByFsrs() = runBlocking {
    val t = texts.createText("Стих", "ru", TextKind.POEM, TextSchedule.FSRS, 2, "a\nb\nc")!!
    val today = texts.today(now)
    assertEquals(listOf(2), today.items.filterIsInstance<TodayItem.NewPortion>().map { it.pieceIds.size })
    val ids = today.items.first().pieceIds
    texts.completePortion(t.id, ids, 1000, now)
    assertTrue(texts.pieces(t.id).take(2).all { it.learnedAt == now && it.reps == 1 })
    assertTrue(texts.today(now).items.none { it is TodayItem.NewPortion })        // one portion per day
    assertEquals(1, texts.today(now + DAY).items.filterIsInstance<TodayItem.NewPortion>().size)
    assertEquals(ids, texts.today(now + 2 * DAY).items.filterIsInstance<TodayItem.Review>().single().pieceIds)
}
@Test fun hifzSabqiThenManzil() = runBlocking {
    settings.sabqiDays = 2
    val t = texts.createText("Сура", "ar", TextKind.POEM, TextSchedule.HIFZ, 1, "a\nb")!!
    val p = texts.pieces(t.id)[0].id
    texts.completePortion(t.id, listOf(p), 0, now)
    for (d in 1..2) {
        val s = texts.today(now + d * DAY).items.filterIsInstance<TodayItem.Sabqi>().single()
        texts.completeSabqi(t.id, s.pieceIds, emptySet(), 0, now + d * DAY)
    }
    val m = texts.today(now + 3 * DAY).items.filterIsInstance<TodayItem.Manzil>().single()
    assertEquals(listOf(p), m.pieceIds)
    texts.completeManzil(m.pieceIds, setOf(p), 0, now + 3 * DAY)          // error → back to sabqi
    assertEquals(2, texts.pieces(t.id)[0].sabqiLeft)
    assertTrue(texts.today(now + 3 * DAY).items.none { it is TodayItem.Manzil })
}
@Test fun switchingTwiceKeepsHistory() = runBlocking {
    val t = texts.createText("Т", "ru", TextKind.POEM, TextSchedule.FSRS, 1, "a\nb")!!
    val p = texts.pieces(t.id)[0].id
    texts.completePortion(t.id, listOf(p), 0, now)
    texts.reviewPiece(p, Rating.GOOD, 0, 0, now + 3 * DAY)
    texts.switchSchedule(t.id, TextSchedule.HIFZ, now + 3 * DAY)
    texts.switchSchedule(t.id, TextSchedule.FSRS, now + 3 * DAY)
    val expected = FsrsScheduler(desiredRetention = settings.desiredRetention.toDouble())
        .replay(listOf(Review(Rating.GOOD, now), Review(Rating.GOOD, now + 3 * DAY)), texts.pieces(t.id)[0].seed)
    assertEquals(expected.due, texts.pieces(t.id)[0].due)
}
@Test fun markLearnedHifzGoesStraightToManzil() = runBlocking {
    val t = texts.createText("Т", "ru", TextKind.POEM, TextSchedule.HIFZ, 1, "a\nb\nc")!!
    texts.markLearned(t.id, 1, now)
    assertEquals(listOf(0, 0), texts.pieces(t.id).take(2).map { it.sabqiLeft })
    assertEquals(1, texts.today(now).items.filterIsInstance<TodayItem.NewPortion>().single().pieceIds.size) // piece c is still new today
}
@Test fun joinAndSplitKeepSchedule() = runBlocking {
    val t = texts.createText("Т", "ru", TextKind.POEM, TextSchedule.FSRS, 2, "a\nb")!!
    val (a, b) = texts.pieces(t.id)
    texts.completePortion(t.id, listOf(a.id, b.id), 0, now)
    texts.saveText(t, listOf(EditedPiece(a.id, "a\nb"), EditedPiece(null, "c", scheduleFrom = a.id)))
    val after = texts.pieces(t.id)
    assertEquals(listOf("a\nb", "c"), after.map { it.content })
    assertEquals(a.id, after[0].id)
    assertEquals(after[0].due, after[1].due)
}
@Test fun practiceDoesNotMoveSchedule() = runBlocking {
    val t = texts.createText("Т", "ru", TextKind.POEM, TextSchedule.FSRS, 1, "a")!!
    val p = texts.pieces(t.id)[0]
    texts.completePortion(t.id, listOf(p.id), 0, now)
    val before = texts.pieces(t.id)[0]
    texts.logPractice(t.id, listOf(p.id), setOf(p.id), 0, 0, now + 1); texts.logPractice(t.id, listOf(p.id), setOf(p.id), 0, 0, now + 2)
    assertEquals(before, texts.pieces(t.id)[0])
    assertEquals(setOf(p.id), texts.weakPieces(t.id))
}
@Test fun backupRunsWithOnlyTexts() = runBlocking {
    val backup = Backup(context, db, settings)
    assertFalse(backup.hasContent())
    texts.createText("Т", "ru", TextKind.POEM, TextSchedule.FSRS, 1, "a")
    assertTrue(backup.hasContent())
}
```

- [ ] **Step 2: Run to verify failure** — `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.wanki.data.TextRepositoryTest` → FAIL (compilation).
- [ ] **Step 3: Implement** `TextRepository` (all writes in `db.withTransaction`; map rows ↔ `PlanPiece`/`PlanText` in one private place), the Settings prefs, `Backup.hasContent`, the Reminders change, and the `AppGraph` field.
- [ ] **Step 4: Run to verify pass** — same command → PASS; then `./gradlew test :app:testDebugUnitTest` → PASS.
- [ ] **Step 5: Commit** — `git commit -m "Text repository: portions, FSRS and hifz reviews, schedule switch, backups and reminders count texts"`

---

### Task 7: Word breaking for languages without spaces

**Files:**
- Create: `app/src/main/kotlin/app/wanki/texts/TextWords.kt`
- Test: `app/src/androidTest/kotlin/app/wanki/texts/TextWordsTest.kt`

**Interfaces:**
- Consumes: `Word`, `WordBreaker`, `Words` (Task 1).
- Produces: `object TextWords { fun breaker(language: String): WordBreaker }` — `Words.Spaced` unless `Words.needsDictionary(language)`, then an ICU breaker: `android.icu.text.BreakIterator.getWordInstance(ULocale.forLanguageTag(language))`, keeping segments whose rule status is not `BreakIterator.WORD_NONE`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun japaneseIsSplitIntoWords() {
    val s = "私は学生です。"
    val w = TextWords.breaker("ja").words(s).map { s.substring(it.start, it.end) }
    assertTrue(w.size >= 3); assertTrue("学生" in w); assertFalse("。" in w)
}
@Test fun russianUsesSpaces() = assertEquals(2, TextWords.breaker("ru").words("Мороз, солнце!").size)
```

- [ ] **Step 2: Run to verify failure** — `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.wanki.texts.TextWordsTest` → FAIL.
- [ ] **Step 3: Implement** as described.
- [ ] **Step 4: Run to verify pass** — PASS.
- [ ] **Step 5: Commit** — `git commit -m "ICU word breaking for Japanese and Chinese texts"`

---

### Task 8: Texts list, editor, detail; entry from Decks; hifz settings

**Files:**
- Create: `app/src/main/kotlin/app/wanki/ui/texts/TextsScreen.kt` (today summary + list + FAB), `.../TextEditorScreen.kt`, `.../TextDetailScreen.kt`
- Modify: `app/src/main/kotlin/app/wanki/ui/MainShell.kt` — routes `Texts`, `TextEditor(val id: String?)`, `TextDetail(val id: String)`; content wiring
- Modify: `app/src/main/kotlin/app/wanki/ui/decks/DecksScreen.kt` — first `LazyColumn` item: a row "Тексты" with `"Сегодня: N"` (from `graph.texts.observeToday()`) or "Стихи, отрывки, любые тексты наизусть" when there are none; tag `open-texts`
- Modify: `app/src/main/kotlin/app/wanki/ui/settings/SettingsScreen.kt` — section "Тексты": "Окно сабки, дней" (`sabqiDays`, 3–30) and "Круг манзиля, дней" (`manzilCycleDays`, 3–60)
- Modify: `app/src/androidTest/kotlin/app/wanki/SmokeTest.kt` — steps below

**Interfaces:**
- Consumes: `TextRepository` API (Task 6), `PieceSplitter`, `TextLoad`, `Words.isRtl`, `Languages` (`ui/common/Common.kt`), `graph.textRecognition.identify(text)` for the default language.
- Produces: routes above; `TextsScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onNew: () -> Unit)`, `TextEditorScreen(id: String?, onBack: () -> Unit, onSaved: (String) -> Unit)`, `TextDetailScreen(id: String, onBack: () -> Unit, onEdit: (String) -> Unit)`. Study actions are wired in Task 9.

Behaviour to build (layout is the implementer's choice; check it against the `android-ui-design` skill):
- **TextsScreen**: header "Тексты" with back; the today list from `observeToday()` — each `TodayItem` is a row ("Новое: <title> · N", "Сабак: <title> · N", "Повторить: <title> · N", "Сабки: <title> · N", "Манзиль · N"), not clickable until Task 9; below — all texts with progress `learned/total`, a "пауза" badge, schedule badge ("FSRS" / "Сабак·сабки·манзиль"); FAB "+ Текст" (tag `new-text`).
- **TextEditorScreen**: title; language picker (defaults to `identify(content)` until the user picks); kind chips "Стих"/"Проза" (portion default 4 / 3); schedule chips "FSRS" (default) / "Сабак·сабки·манзиль"; portion size stepper 1–20 with the hint `"≈ +${TextLoad.daily(...)} в день"`; content field (tag `text-content`); live piece preview from `PieceSplitter`, each piece with "склеить с предыдущим" and "разрезать" (at the cursor); per-piece "Перевод/пояснение". Save (tag `save-text`) disabled while there are no pieces. Editing an existing text edits its pieces through `saveText` with `EditedPiece` ids (split → new piece with `scheduleFrom` = original).
- **TextDetailScreen**: pieces in order with status (новый / выучен / в сабки / в манзиле / к повторению) and weak pieces highlighted (`weakPieces`); progress; actions (the two study actions "Учить порцию" and "Рассказать весь текст" arrive in Task 9): "Отметить выученным до этого места" (long-press a piece), "Пауза/Продолжить", "Расписание: FSRS ↔ Сабак·сабки·манзиль" (confirm dialog, `switchSchedule`), "Изменить", "Удалить" (`ConfirmDialog`). Arabic and other RTL languages render with `LocalLayoutDirection provides LayoutDirection.Rtl`.

- [ ] **Step 1: Add smoke steps** after the deck part of `walkThroughAllSections`: tap `open-texts` → shot `14-texts`; tap `new-text`, type `"Мороз и солнце; день чудесный!\nЕщё ты дремлешь, друг прелестный —\nПора, красавица, проснись"` into `text-content`, shot `15-text-editor`; tap `save-text`; assert `hasText("Мороз", substring = true)` exists → shot `16-text-detail`.
- [ ] **Step 2: Run to verify failure** — `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.wanki.SmokeTest` → FAIL (no `open-texts`).
- [ ] **Step 3: Implement** the three screens, routes, Decks row and settings section.
- [ ] **Step 4: Run to verify pass** — same command → PASS; pull screenshots (`adb -s emulator-5554 pull /sdcard/Android/data/app.wanki/files/screens <dir>` with `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`) and look at 14–16 on phone; repeat on `wanki-tablet` (one emulator at a time).
- [ ] **Step 5: Commit** — `git commit -m "Texts screens: list with today plan, editor with piece preview, detail; entry from decks"`

---

### Task 9: Study screen — modes and sessions

**Files:**
- Create: `app/src/main/kotlin/app/wanki/ui/texts/StudyScreen.kt` (screen + session logic), `.../StudyText.kt` (renders a piece with per-word display, tap-to-reveal, error marking), `.../StudyRequest.kt`
- Modify: `app/src/main/kotlin/app/wanki/ui/MainShell.kt` — `Route.TextStudy(val request: StudyRequest)`, immersive like `Review`
- Modify: `TextsScreen` (today rows open the study screen; new param `onStudy: (StudyRequest) -> Unit`), `TextDetailScreen` ("Учить порцию" when `nextPortion` is non-empty → LEARN; "Рассказать весь текст" → PRACTICE with all piece ids; new param `onStudy`)
- Modify: `app/src/androidTest/kotlin/app/wanki/SmokeTest.kt`

**Interfaces:**
- Consumes: Tasks 2, 3, 6, 7 APIs; `graph.speaker.speak(text, language)`.
- Produces:
```kotlin
enum class StudyKind { LEARN, REVIEW, SABQI, MANZIL, PRACTICE }
enum class StudyMode { READ, CUES, FIRST_LETTERS, CHAIN, RECITE }
data class StudyRequest(val kind: StudyKind, val textId: String?, val pieceIds: List<String>) // textId null only for MANZIL
fun TodayItem.toStudyRequest(): StudyRequest
```
Session rules:
- **LEARN** (new portion): mode bar READ / CUES / FIRST_LETTERS / CHAIN / RECITE, starting at READ. CUES: level 0–5 slider + "Дальше" (tag `cue-next`); after a level is passed with no reveals the level rises by itself. Hidden words show as blanks of the word's length; tapping one reveals it and counts a hint. FIRST_LETTERS shows `FirstLetters.hint`; for languages where `!Words.needsDictionary`, an optional keyboard field checks `FirstLetters.matches` word by word (a mismatch counts an error and reveals the word). CHAIN walks `Chain.steps(n)` (cue piece visible, targets hidden, "Показать" then "Дальше"). RECITE hides everything; "Показать" reveals; then the user taps pieces that had mistakes; "Без ошибок" (tag `learned`) → `completePortion`, any marked piece or used hint → "Ещё раз" (stays in LEARN).
- **REVIEW** (FSRS): one piece at a time in chain form (previous piece of the text visible as cue, current hidden; hints by tapping words). After "Показать": buttons "Не вспомнил" / "С ошибками" / "Верно" / "Легко" → `TextRating.rating(outcome, usedHints)` → `reviewPiece`.
- **SABQI / MANZIL / PRACTICE**: RECITE of the whole block (pieces of one text in order; manzil may span texts — show text titles as separators); CUES available as a fallback, and any piece with a revealed word is marked as an error automatically; after "Показать" the user taps pieces with mistakes; "Готово" → `completeSabqi` / `completeManzil` / `logPractice`.
- Duration = time on screen; leaving mid-session saves nothing.
- Each piece row has 🔊 (`speaker.speak(content, language)`; a snackbar "Нет голоса для этого языка" when it returns false).
- Words come from `TextWords.breaker(language)`; cue seed = `TextPieceEntity.seed`; RTL as in Task 8.
- Mode switches never change the rule "used hint ⇒ not clean".

- [ ] **Step 1: Add smoke steps** continuing Task 8: tap "Учить порцию" → shot `17-study-read`; select CUES; tap `cue-next` twice → shot `18-study-cues`; select RECITE, "Показать", tap `learned` → back on detail, assert text "выучен" exists → shot `19-text-learned`.
- [ ] **Step 2: Run to verify failure** — SmokeTest command from Task 8 → FAIL.
- [ ] **Step 3: Implement** the screen and route.
- [ ] **Step 4: Run to verify pass** — PASS; check screenshots 17–19 on phone and tablet; also open an Arabic text manually in the emulator and check RTL rendering and blanks (screenshot into the PROGRESS note).
- [ ] **Step 5: Commit** — `git commit -m "Texts study screen: read, vanishing cues, first letters, chain, recital; FSRS and hifz sessions"`

---

### Task 10: Recording your own voice

**Files:**
- Create: `app/src/main/kotlin/app/wanki/media/VoiceRecorder.kt`
- Modify: `app/src/main/kotlin/app/wanki/media/MediaStore.kt` — `fun play(name: String, loop: Boolean = false): Boolean`, `fun stop()`
- Modify: `app/src/main/AndroidManifest.xml` — `<uses-permission android:name="android.permission.RECORD_AUDIO" />`
- Modify: `app/src/main/kotlin/app/wanki/ui/texts/StudyScreen.kt` — 🎙 / ▶ / 🔁 for the current piece or portion
- Test: `app/src/test/kotlin/app/wanki/media/VoiceRecorderTest.kt`

**Interfaces:**
- Produces:
```kotlin
class VoiceRecorder(private val media: MediaStore) {
    fun start(name: String)          // AAC in MPEG-4 into media.dir/name.part, renamed on stop
    fun stop(): Boolean              // false when nothing usable was recorded (< 300 ms)
    val recording: Boolean
    companion object { fun fileName(pieceIds: List<String>): String } // "rec-<first>.m4a" or "rec-<first>-<last>.m4a"
}
```
  `AppGraph`: `val recorder = VoiceRecorder(media)`. Permission requested at the first 🎙 tap with `ActivityResultContracts.RequestPermission`; denied → snackbar "Нужен доступ к микрофону". Recording again overwrites. Files live in `files/media/` (manual backups include them; daily ones do not — no change needed).

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun fileNames() {
    assertEquals("rec-a.m4a", VoiceRecorder.fileName(listOf("a")))
    assertEquals("rec-a-c.m4a", VoiceRecorder.fileName(listOf("a", "b", "c")))
}
```
- [ ] **Step 2: Run to verify failure** — `./gradlew :app:testDebugUnitTest --tests app.wanki.media.VoiceRecorderTest` → FAIL.
- [ ] **Step 3: Implement** recorder, looping playback, permission, buttons.
- [ ] **Step 4: Run to verify pass** — PASS; on the emulator, record (emulator mic input is silent — check only that a file appears and plays without error; `adb -s emulator-5554 shell ls /data/data/app.wanki/files/media` via `run-as app.wanki`).
- [ ] **Step 5: Commit** — `git commit -m "Record and loop your own voice for a piece or portion"`

---

### Task 11: Full verification and docs

**Files:**
- Modify: `docs/PROGRESS.md` (stage A → done, test counts, screenshots), `docs/PLAN.md` (insert stages A–F before Wanki stages 4–6 in "Полноценное приложение"; mark A done), `README.md` (texts feature), `app/src/main/kotlin/app/wanki/ui/settings/SettingsScreen.kt` "О приложении" only if it lists features
- Modify: `CLAUDE.md` — add `core/texts` to Modules and the `ui/texts` package; add the convention "Text practice (free recital) never touches schedules"

- [ ] **Step 1:** `./gradlew test` → PASS (JVM, including `:core:texts`).
- [ ] **Step 2:** `./gradlew :app:assembleDebug :app:testDebugUnitTest` → PASS.
- [ ] **Step 3:** `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest` on `wanki-phone`, then on `wanki-tablet` (stop the first emulator before starting the second) → PASS on both.
- [ ] **Step 4:** `./gradlew :app:assembleRelease` → builds; install the release on the emulator (`adb -s emulator-5554 install -r app/build/outputs/apk/release/app-release.apk`), create a text and pass one cue level and one recording to confirm R8 kept everything. **Do not install on the user's phone unless asked.**
- [ ] **Step 5:** Update the docs listed above and the contact sheet (`python3 tools/screens/sheet.py out.png 4 360 *.png`).
- [ ] **Step 6: Commit** — `git commit -m "Stage A done: texts learned by heart"`
