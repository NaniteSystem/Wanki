package app.wanki.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.wanki.ui.board.BoardScreen
import app.wanki.ui.decks.DeckScreen
import app.wanki.ui.decks.DecksScreen
import app.wanki.ui.diary.DiaryDayScreen
import app.wanki.ui.diary.DiaryScreen
import app.wanki.ui.notebooks.NotebookScreen
import app.wanki.ui.notebooks.NotebooksScreen
import app.wanki.ui.notes.NoteEditorScreen
import app.wanki.ui.notes.NotesScreen
import app.wanki.ui.review.ReviewScreen
import app.wanki.ui.settings.SettingsScreen
import app.wanki.writing.WritingTestScreen

sealed interface Route {
    data object Decks : Route
    data class Deck(val id: String) : Route
    data class Review(val deckId: String) : Route
    data object Notebooks : Route
    data class Notebook(val id: String, val pageId: String? = null) : Route
    data object Notes : Route
    data class Note(val id: String?) : Route
    data object Diary : Route
    data class DiaryDay(val epochDay: Long) : Route
    data object Board : Route
    data object Settings : Route
    data object WritingLab : Route
}

private data class Tab(val route: Route, val glyph: String, val label: String)

private val tabs = listOf(
    Tab(Route.Decks, "▤", "Колоды"),
    Tab(Route.Notebooks, "▥", "Тетради"),
    Tab(Route.Notes, "✎", "Заметки"),
    Tab(Route.Diary, "◷", "Дневник"),
    Tab(Route.Board, "◻", "Доска"),
    Tab(Route.Settings, "⚙", "Настройки"),
)

/** Back stack navigation: each tab is a root, screens push on top of it. */
class Navigator {
    val stack = mutableStateListOf<Route>(Route.Decks)
    val current: Route get() = stack.last()
    val root: Route get() = stack.first()

    fun open(route: Route) { stack.add(route) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    fun switchTab(route: Route) { stack.clear(); stack.add(route) }
}

@Composable
fun MainShell() {
    val nav = remember { Navigator() }
    BackHandler(enabled = nav.stack.size > 1) { nav.back() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        // Screens pushed on top of a tab (review, a page) take the whole screen; tab roots keep the bar.
        val immersive = nav.stack.size > 1 &&
            (nav.current is Route.Review || nav.current is Route.Notebook || nav.current is Route.DiaryDay)
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail {
                    tabs.forEach { t ->
                        NavigationRailItem(
                            modifier = Modifier.testTag("tab-${t.label}"),
                            selected = nav.root == t.route,
                            onClick = { nav.switchTab(t.route) },
                            icon = { Text(t.glyph) },
                            label = { Text(t.label) },
                        )
                    }
                }
                Box(Modifier.weight(1f)) { Content(nav) }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { Content(nav) }
                if (!immersive) {
                    NavigationBar {
                        tabs.forEach { t ->
                            NavigationBarItem(
                                modifier = Modifier.testTag("tab-${t.label}"),
                                selected = nav.root == t.route,
                                onClick = { nav.switchTab(t.route) },
                                icon = { Text(t.glyph) },
                                label = { Text(t.label, maxLines = 1) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Content(nav: Navigator) {
    when (val r = nav.current) {
        Route.Decks -> DecksScreen(onOpen = { nav.open(Route.Deck(it)) }, onStudy = { nav.open(Route.Review(it)) })
        is Route.Deck -> DeckScreen(r.id, onBack = { nav.back() }, onStudy = { nav.open(Route.Review(r.id)) })
        is Route.Review -> ReviewScreen(r.deckId, onClose = { nav.back() })
        Route.Notebooks -> NotebooksScreen(onOpen = { id, page -> nav.open(Route.Notebook(id, page)) })
        is Route.Notebook -> NotebookScreen(r.id, r.pageId, onBack = { nav.back() })
        Route.Notes -> NotesScreen(onOpen = { nav.open(Route.Note(it)) })
        is Route.Note -> NoteEditorScreen(r.id, onBack = { nav.back() })
        Route.Diary -> DiaryScreen(onOpenDay = { nav.open(Route.DiaryDay(it)) })
        is Route.DiaryDay -> DiaryDayScreen(r.epochDay, onBack = { nav.back() })
        Route.Board -> BoardScreen()
        Route.Settings -> SettingsScreen(onOpenLab = { nav.open(Route.WritingLab) })
        Route.WritingLab -> WritingTestScreen()
    }
}
