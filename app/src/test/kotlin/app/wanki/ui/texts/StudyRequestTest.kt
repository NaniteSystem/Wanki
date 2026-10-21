package app.wanki.ui.texts

import app.wanki.data.TextEntity
import app.wanki.data.TodayItem
import app.wanki.texts.VanishingCues
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudyRequestTest {
    private fun text(id: String) = TextEntity(id = id, title = "T", language = "ru")

    @Test fun todayItemsBecomeTheRightKind() {
        assertEquals(StudyRequest(StudyKind.LEARN, "t", listOf("a", "b")), TodayItem.NewPortion(text("t"), listOf("a", "b")).toStudyRequest())
        assertEquals(StudyRequest(StudyKind.REVIEW, "t", listOf("a")), TodayItem.Review(text("t"), listOf("a")).toStudyRequest())
        assertEquals(StudyRequest(StudyKind.SABQI, "t", listOf("a")), TodayItem.Sabqi(text("t"), listOf("a")).toStudyRequest())
        // manzil spans texts, so it carries no single textId
        assertEquals(StudyRequest(StudyKind.MANZIL, null, listOf("a", "b")), TodayItem.Manzil(listOf("a", "b")).toStudyRequest())
    }

    @Test fun cleanLevelRisesToTheNext() =
        assertEquals(2, nextCueLevel(1, revealedThisLevel = false))

    @Test fun dirtyLevelIsRetried() =
        assertEquals(1, nextCueLevel(1, revealedThisLevel = true))

    @Test fun cueLevelClampsAtMax() {
        assertEquals(VanishingCues.MAX_LEVEL, nextCueLevel(VanishingCues.MAX_LEVEL, revealedThisLevel = false))
        assertEquals(VanishingCues.MAX_LEVEL, nextCueLevel(VanishingCues.MAX_LEVEL, revealedThisLevel = true))
    }

    @Test fun usedHintMeansNotClean() {
        assertEquals(true, isClean(0, emptySet()))
        assertEquals(false, isClean(1, emptySet()))          // a hint ⇒ not clean
        assertEquals(false, isClean(0, setOf("p1")))          // a marked mistake ⇒ not clean
    }

    @Test fun recitalErrorsAreMarkedPlusHinted() =
        assertEquals(setOf("p1", "p2", "p3"), recitalErrors(marked = setOf("p1", "p2"), hinted = setOf("p2", "p3")))

    @Test fun hintedPieceCannotBeUnmarked() {
        // a piece with an opened cue word stays an error whatever the tap says
        assertEquals(emptySet<String>(), toggleMark(marked = emptySet<String>(), hinted = setOf("p1"), pieceId = "p1"))
        assertEquals(setOf("p1"), toggleMark(marked = setOf("p1"), hinted = setOf("p1"), pieceId = "p1"))
        assertEquals(setOf("p1"), recitalErrors(toggleMark(emptySet<String>(), setOf("p1"), "p1"), setOf("p1")))
        // other pieces toggle as before
        assertEquals(setOf("p2"), toggleMark(marked = emptySet<String>(), hinted = setOf("p1"), pieceId = "p2"))
        assertEquals(emptySet<String>(), toggleMark(marked = setOf("p2"), hinted = setOf("p1"), pieceId = "p2"))
    }

    @Test fun enteringReciteInALearnSessionStartsAFreshAttempt() {
        assertTrue(startsFreshRecital(StudyKind.LEARN, from = StudyMode.CUES, to = StudyMode.RECITE))
        assertTrue(startsFreshRecital(StudyKind.LEARN, from = StudyMode.CHAIN, to = StudyMode.RECITE))
        // staying in RECITE or leaving it keeps the attempt
        assertFalse(startsFreshRecital(StudyKind.LEARN, from = StudyMode.RECITE, to = StudyMode.RECITE))
        assertFalse(startsFreshRecital(StudyKind.LEARN, from = StudyMode.RECITE, to = StudyMode.CUES))
        // sabqi/manzil/practice judge everything, cue hints included
        assertFalse(startsFreshRecital(StudyKind.SABQI, from = StudyMode.CUES, to = StudyMode.RECITE))
        assertFalse(startsFreshRecital(StudyKind.PRACTICE, from = StudyMode.CUES, to = StudyMode.RECITE))
    }

    @Test fun jumpingToTheSameCueLevelIsANoOp() {
        assertNull(cueJump(current = 2, requested = 2))
        assertNull(cueJump(current = VanishingCues.MAX_LEVEL, requested = VanishingCues.MAX_LEVEL + 3)) // clamps to the same level
        assertEquals(3, cueJump(current = 2, requested = 3))
        assertEquals(0, cueJump(current = 2, requested = -1))
    }

    @Test fun busyGuardDropsASecondSubmitWhileTheFirstRuns() = runBlocking {
        val guard = BusyGuard()
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        val first = launch(start = CoroutineStart.UNDISPATCHED) { guard.run { runs++; gate.await() } }
        assertTrue(guard.busy)
        guard.run { runs++ } // a double tap while saving
        assertEquals(1, runs)
        gate.complete(Unit)
        first.join()
        assertFalse(guard.busy)
        guard.run { runs++ }
        assertEquals(2, runs)
    }

    @Test fun busyGuardFreesAfterAFailure() = runBlocking {
        val guard = BusyGuard()
        runCatching { guard.run { error("db") } }
        assertFalse(guard.busy)
    }
}
