package app.wanki.ui

import app.wanki.ui.texts.StudyKind
import app.wanki.ui.texts.StudyRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RouteCodecTest {
    private val routes = listOf(
        Route.Decks, Route.Deck("d1"), Route.DeckOptions("d1"), Route.Review("d1"), Route.Browser, Route.Catalog,
        Route.CardEditor("c1"), Route.Practice(null, "ja"), Route.Practice("字", "zh"),
        Route.Notebooks, Route.Notebook("n1", "p2"), Route.Notebook("n1", null),
        Route.Notes, Route.Note("x1"), Route.Note(null), Route.Diary, Route.DiaryDay(20_000L), Route.Boards, Route.Board("b1"),
        Route.Texts, Route.TextEditor("t1"), Route.TextEditor(null), Route.TextDetail("t1"),
        Route.TextStudy(StudyRequest(StudyKind.MANZIL, null, listOf("a", "b"))),
        Route.TextStudy(StudyRequest(StudyKind.LEARN, "t1", emptyList())),
        Route.Settings, Route.Stats, Route.WritingLab,
    )

    @Test
    fun everyRouteRoundTrips() {
        for (r in routes) assertEquals(r, RouteCodec.decode(RouteCodec.encode(r)), r.toString())
    }

    @Test
    fun idsWithSeparatorsRoundTrip() {
        val r = Route.Note("a|b,c\\d")
        assertEquals(r, RouteCodec.decode(RouteCodec.encode(r)))
        val s = Route.TextStudy(StudyRequest(StudyKind.REVIEW, "t|1", listOf("a,b", "c|d", "")))
        assertEquals(s, RouteCodec.decode(RouteCodec.encode(s)))
    }

    @Test
    fun unknownRouteFallsBackToDecks() {
        assertNull(RouteCodec.decode("Nope|x"))
        assertNull(RouteCodec.decode("Deck"))
        assertEquals(listOf<Route>(Route.Decks), Navigator.restore(listOf("Nope|x")).stack.toList())
        assertEquals(listOf<Route>(Route.Decks), Navigator.restore(emptyList()).stack.toList())
    }
}
