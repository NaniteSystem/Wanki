package app.wanki.anki

import app.wanki.data.STATE_NEW
import app.wanki.srs.CardState
import app.wanki.srs.FsrsScheduler
import app.wanki.srs.Rating
import app.wanki.srs.SrsState

/** One row of Anki's revlog: time (epoch ms), ease 1–4 (0 = manual), type, interval after it. */
data class AnkiRevlog(val time: Long, val ease: Int, val type: Int, val ivl: Int, val durationMs: Long)

data class ReplayedLog(val time: Long, val rating: Rating, val durationMs: Long, val cardState: String)

data class Replayed(val state: SrsState, val logs: List<ReplayedLog>)

/**
 * Rebuilds a card's schedule from Anki's review history with our FSRS, so imported cards continue
 * exactly as if they had been studied here. Revlog types: 0 learn, 1 review, 2 relearn, 3 filtered
 * (cram), 4 manual (a "forget" has ivl 0), 5 rescheduled.
 */
object ScheduleReplay {
    private const val DAY = 86_400_000L

    /** [schedulerV1]: Anki's first scheduler graded learning cards 1 Again, 2 Good, 3 Easy. */
    fun replay(revlog: List<AnkiRevlog>, scheduler: FsrsScheduler, seed: Long, schedulerV1: Boolean = false): Replayed {
        var s = SrsState()
        val logs = ArrayList<ReplayedLog>()
        for (r in revlog.sortedBy { it.time }) {
            if (r.type == 4 && r.ivl == 0) {
                s = SrsState()
                continue
            }
            if (r.ease !in 1..4 || r.type !in 0..3) continue
            val learning = r.type == 0 || r.type == 2
            val rating = if (schedulerV1 && learning && r.ease >= 2) Rating.entries[r.ease] else Rating.entries[r.ease - 1]
            val before = if (s.isNew) STATE_NEW else s.state.name
            s = scheduler.review(s, rating, r.time, seed)
            logs += ReplayedLog(r.time, rating, r.durationMs.coerceAtLeast(0), before)
        }
        return Replayed(s, logs)
    }

    /**
     * A review card exported without its history: stability from the interval, difficulty from
     * the ease factor (250% ≈ 5 of 10), due date from Anki's day number.
     */
    fun fromInterval(ivlDays: Int, factor: Int, dueDay: Long, collectionCreatedSec: Long, reps: Int, lapses: Int): SrsState {
        val due = (collectionCreatedSec + dueDay * 86_400L) * 1000
        val ivl = ivlDays.coerceAtLeast(1)
        return SrsState(
            state = CardState.REVIEW,
            step = null,
            stability = ivl.toDouble(),
            difficulty = (5.0 + (2500 - factor) / 200.0).coerceIn(1.0, 10.0),
            due = due,
            lastReview = due - ivl * DAY,
            reps = reps.coerceAtLeast(1),
            lapses = lapses,
        )
    }
}
