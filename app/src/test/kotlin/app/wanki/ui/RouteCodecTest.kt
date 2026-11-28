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
        Route.Practice(null, "ja", "t1"), Route.Practice(null, "ja", "t|1", "p,2"),
        Route.Notebooks, Route.Notebook("n1", "p2"), Route.Notebook("n1", null), Route.Notebook("n1", "p2", "ёж|1,\\"), Route.Notebook("n1", null, "x"), Route.PageGrid("n1"), Route.PageGrid("a|b"),
        Route.Notes, Route.Note("x1"), Route.Note(null), Route.Diary, Route.DiaryDay(20_000L), Route.Boards, Route.Board("b1"),
        Route.Texts, Route.TextEditor("t1"), Route.TextEditor(null), Route.TextDetail("t1"), Route.TextReadings("x"), Route.TextReadings("a|b"),
        Route.TextStudy(StudyRequest(StudyKind.MANZIL, null, listOf("a", "b"))),
        Route.TextStudy(StudyRequest(StudyKind.LEARN, "t1", emptyList())),
        Route.Settings, Route.Trash, Route.Stats, Route.WritingLab,
        Route.Search(), Route.Search("кот"), Route.Search("a|b\\c"),
    )

    @Test
    fun everyRouteRoundTrips() {
        for (r in routes) assertEquals(r, RouteCodec.decode(RouteCodec.encode(r)), r.toString())
    }

    @Test
    fun oldPracticeRouteWithoutTextStillDecodes() {
        assertEquals(Route.Practice("字", "ja"), RouteCodec.decode("Practice|=字|ja"))
    }

    @Test
    fun oldNotebookRouteWithoutHighlightStillDecodes() {
        assertEquals(Route.Notebook("n1", "p2"), RouteCodec.decode("Notebook|n1|=p2"))
        assertEquals(Route.Notebook("n1", null), RouteCodec.decode("Notebook|n1|"))
    }

    @Test
    fun notebookAreaRoundTrips() {
        val r = Route.Notebook("n1", "p2", null, "10.5,20,30,40")
        assertEquals(r, RouteCodec.decode(RouteCodec.encode(r)))
        assertEquals(Route.Notebook("n1", "p2", "q"), RouteCodec.decode("Notebook|n1|=p2|=q"))
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

    @Test
    fun restoredStackWithoutTabRootStartsAtDecks() {
        // the saved root could not be decoded: the screens above it stay, with the decks tab under them
        val saved = listOf("Nope|x", RouteCodec.encode(Route.Note("n1")))
        assertEquals(listOf(Route.Decks, Route.Note("n1")), Navigator.restore(saved).stack.toList())
        val ok = listOf(Route.Notes, Route.Note("n1"))
        assertEquals(ok, Navigator.restore(ok.map(RouteCodec::encode)).stack.toList())
    }

    @Test
    fun replaceTopKeepsTheStackBelow() {
        val nav = Navigator(listOf(Route.Notes, Route.Note(null)))
        nav.replaceTop(Route.Note("n1"))
        assertEquals(listOf(Route.Notes, Route.Note("n1")), nav.stack.toList())
    }
}
