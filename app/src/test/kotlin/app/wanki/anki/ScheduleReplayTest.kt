package app.wanki.anki

import app.wanki.data.STATE_NEW
import app.wanki.srs.CardState
import app.wanki.srs.FsrsScheduler
import app.wanki.srs.Rating
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScheduleReplayTest {
    private val fsrs = FsrsScheduler()
    private val day = 86_400_000L
    private val t0 = 1_700_000_000_000L

    @Test
    fun historyIsReplayedThroughFsrs() {
        val log = listOf(
            AnkiRevlog(t0, 3, 0, -600, 5000),
            AnkiRevlog(t0 + 600_000, 3, 0, 1, 4000),
            AnkiRevlog(t0 + 2 * day, 3, 1, 4, 3000),
        )
        val r = ScheduleReplay.replay(log, fsrs, seed = 1)
        assertEquals(listOf(STATE_NEW, "LEARNING", "REVIEW"), r.logs.map { it.cardState })
        assertEquals(CardState.REVIEW, r.state.state)
        assertEquals(3, r.state.reps)
        // same as studying here
        val direct = fsrs.replay(log.map { app.wanki.srs.Review(Rating.GOOD, it.time) }, 1)
        assertEquals(direct, r.state)
    }

    @Test
    fun forgetStartsOverAndManualRowsAreSkipped() {
        val log = listOf(
            AnkiRevlog(t0, 3, 0, 1, 1000),
            AnkiRevlog(t0 + day, 0, 5, 30, 0), // rescheduled
            AnkiRevlog(t0 + 2 * day, 0, 4, 0, 0), // forget
            AnkiRevlog(t0 + 3 * day, 1, 0, -60, 1000),
        )
        val r = ScheduleReplay.replay(log, fsrs, seed = 1)
        assertEquals(listOf(STATE_NEW, STATE_NEW), r.logs.map { it.cardState })
        assertEquals(1, r.state.reps)
    }

    @Test
    fun schedulerV1LearningEasesAreShifted() {
        val r = ScheduleReplay.replay(listOf(AnkiRevlog(t0, 2, 0, 1, 1000), AnkiRevlog(t0 + day, 2, 1, 3, 1000)), fsrs, 1, schedulerV1 = true)
        assertEquals(listOf(Rating.GOOD, Rating.HARD), r.logs.map { it.rating })
    }

    @Test
    fun reviewCardWithoutHistory() {
        val s = ScheduleReplay.fromInterval(ivlDays = 20, factor = 2500, dueDay = 100, collectionCreatedSec = 1_600_000_000, reps = 5, lapses = 1)
        assertEquals(CardState.REVIEW, s.state)
        assertEquals(20.0, s.stability)
        assertEquals(5.0, s.difficulty)
        assertEquals((1_600_000_000L + 100 * 86_400L) * 1000, s.due)
        assertTrue(s.lastReview!! < s.due)
    }
}
