package app.wanki.texts

import app.wanki.srs.FsrsScheduler
import app.wanki.srs.Rating
import app.wanki.srs.Review
import kotlin.test.Test
import kotlin.test.assertEquals

class ScheduleSwitchTest {
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
}
