package app.wanki.ui

import androidx.activity.compose.BackHandler
import app.wanki.data.NotebookKind
import app.wanki.data.PageSearchHit
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import app.wanki.R
import app.wanki.input.StylusTracker
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.wanki.graph
import app.wanki.ui.common.LocalUndo
import app.wanki.ui.common.Undo
import app.wanki.ui.common.rememberUiScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.wanki.practice.PracticeScreen
import app.wanki.ui.board.BoardScreen
import app.wanki.ui.board.BoardsScreen
import app.wanki.ui.browser.BrowserScreen
import app.wanki.ui.browser.CardEditorScreen
import app.wanki.ui.catalog.CatalogScreen
import app.wanki.ui.decks.DeckOptionsScreen
import app.wanki.ui.decks.DeckScreen
import app.wanki.ui.decks.DecksScreen
import app.wanki.ui.diary.DiaryDayScreen
import app.wanki.ui.diary.DiaryScreen
import app.wanki.ui.notebooks.NotebookScreen
import app.wanki.ui.notebooks.NotebooksScreen
import app.wanki.ui.notebooks.PageGridScreen
import app.wanki.ui.notes.NoteEditorScreen
import app.wanki.ui.notes.NotesScreen
import app.wanki.ui.review.ReviewScreen
import app.wanki.ui.settings.SettingsScreen
import app.wanki.ui.settings.TrashScreen
import app.wanki.ui.stats.StatsScreen
import app.wanki.ui.texts.StudyRequest
import app.wanki.ui.texts.StudyScreen
import app.wanki.ui.texts.TextDetailScreen
import app.wanki.ui.texts.TextEditorScreen
import app.wanki.ui.texts.TextReadingsScreen
import app.wanki.ui.texts.TextsScreen
import app.wanki.writing.WritingTestScreen

sealed interface Route {
    data object Decks : Route
    data class Deck(val id: String) : Route
    data class DeckOptions(val id: String) : Route
    data class Review(val deckId: String) : Route
    data object Browser : Route
    data object Catalog : Route
    data class CardEditor(val id: String) : Route
    /**
     * Handwriting practice: the weak-character queue, or one [glyph], or a copybook («Прописи») over the characters
     * of text [textId] — only its piece [pieceId] when given; the text's own language is used then.
     */
    data class Practice(
        val glyph: String? = null, val language: String = "ja", val textId: String? = null, val pieceId: String? = null,
    ) : Route
    data object Notebooks : Route
    /**
     * [highlight]: a search query; the lines of [pageId] that contain it are marked when the page opens.
     * [area]: `"x,y,w,h"` in page units, outlined for a moment when the page opens (a card's source).
     */
    data class Notebook(val id: String, val pageId: String? = null, val highlight: String? = null, val area: String? = null) : Route
    data class PageGrid(val notebookId: String) : Route
    data object Trash : Route
    data object Notes : Route
    data class Note(val id: String?) : Route
    data object Diary : Route
    data class DiaryDay(val epochDay: Long) : Route
    data object Boards : Route
    data class Board(val id: String) : Route
    data object Texts : Route
    /** [id] null = a new text. */
    data class TextEditor(val id: String?) : Route
    data class TextDetail(val id: String) : Route
    data class TextReadings(val id: String) : Route
    data class TextStudy(val request: StudyRequest) : Route
    data object Settings : Route
    data object Stats : Route
    data object WritingLab : Route
}

private data class Tab(val route: Route, @DrawableRes val icon: Int, val label: String)

private val tabs = listOf(
    Tab(Route.Decks, R.drawable.ic_style, "Колоды"),
    Tab(Route.Notebooks, R.drawable.ic_book_2, "Тетради"),
    Tab(Route.Notes, R.drawable.ic_sticky_note_2, "Заметки"),
    Tab(Route.Diary, R.drawable.ic_calendar_month, "Дневник"),
    Tab(Route.Boards, R.drawable.ic_dashboard, "Доски"),
    Tab(Route.Settings, R.drawable.ic_settings, "Настройки"),
)

/** Back stack navigation: each tab is a root, screens push on top of it. */
class Navigator(initial: List<Route> = listOf(Route.Decks)) {
    val stack = mutableStateListOf<Route>().apply { addAll(initial.ifEmpty { listOf(Route.Decks) }) }
    val current: Route get() = stack.last()
    val root: Route get() = stack.first()

    fun open(route: Route) { stack.add(route) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    fun switchTab(route: Route) { stack.clear(); stack.add(route) }
    /** Swaps the screen on top for [route], e.g. a new note once it has an id. */
    fun replaceTop(route: Route) { stack[stack.lastIndex] = route }

    /** Opens the screen a search hit lives on: a diary day, a board or a notebook page. */
    fun openSearchHit(hit: PageSearchHit, query: String? = null) {
        val day = hit.date
        open(
            when {
                hit.kind == NotebookKind.DIARY.name && day != null -> Route.DiaryDay(day)
                hit.kind == NotebookKind.BOARD.name -> Route.Board(hit.notebookId)
                else -> Route.Notebook(hit.notebookId, hit.pageId, query?.trim()?.takeIf { it.isNotEmpty() })
            },
        )
    }

    companion object {
        /**
         * A navigator from saved route strings; undecodable entries are dropped. The stack always starts
         * at a tab root: when the saved root was lost, the decks tab goes under the screens left.
         */
        fun restore(saved: List<String>): Navigator {
            val routes = saved.mapNotNull(RouteCodec::decode)
            val root = routes.firstOrNull()
            return Navigator(if (root != null && tabs.none { it.route == root }) listOf(Route.Decks) + routes else routes)
        }

        /** Keeps the back stack across recreation and process death. */
        val Saver: Saver<Navigator, List<String>> = Saver(
            save = { nav -> nav.stack.map(RouteCodec::encode) },
            restore = { restore(it) },
        )
    }
}

@Composable
fun MainShell() {
    val nav = rememberSaveable(saver = Navigator.Saver) { Navigator() }
    BackHandler(enabled = nav.stack.size > 1) { nav.back() }
    // the pen rule is per screen: a pen seen on one page does not stop fingers drawing on the next
    LaunchedEffect(nav.current) { StylusTracker.onScreenChanged() }

    // one snackbar host for the whole app: «Удалено · Отменить» outlives the screen that deleted
    val snackbar = remember { SnackbarHostState() }
    val graph = LocalContext.current.graph
    val uiScope = rememberUiScope()
    val undo = remember { Undo(graph.repository, snackbar, uiScope) }

    // composed once and placed in either layout: a rotation across 600 dp keeps every screen's state
    // lists have a floating button and editors keep their tools at the bottom: lift the snackbar above them
    val snackbarLift = 72.dp
    val content = remember { movableContentOf { n: Navigator -> CompositionLocalProvider(LocalUndo provides undo) { Content(n) } } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        // Screens pushed on top of a tab (review, a page) take the whole screen; tab roots keep the bar.
        val immersive = nav.stack.size > 1 &&
            (nav.current is Route.Review || nav.current is Route.Practice || nav.current is Route.Notebook || nav.current is Route.PageGrid ||
                nav.current is Route.DiaryDay || nav.current is Route.Board || nav.current is Route.Note || nav.current is Route.TextEditor ||
                nav.current is Route.TextStudy)
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail {
                    tabs.forEach { t ->
                        NavigationRailItem(
                            modifier = Modifier.testTag("tab-${t.label}"),
                            selected = nav.root == t.route,
                            onClick = { nav.switchTab(t.route) },
                            icon = { Icon(painterResource(t.icon), contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
                Box(Modifier.weight(1f)) {
                    content(nav)
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = snackbarLift))
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    content(nav)
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = snackbarLift))
                }
                if (!immersive) {
                    // six labels share the width: the bar's text ignores font scale above 1.0 so «Настройки» is never cut
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, minOf(density.fontScale, 1f))) {
                    NavigationBar {
                        tabs.forEach { t ->
                            NavigationBarItem(
                                modifier = Modifier.testTag("tab-${t.label}"),
                                selected = nav.root == t.route,
                                onClick = { nav.switchTab(t.route) },
                                icon = { Icon(painterResource(t.icon), contentDescription = null) },
                                label = { Text(t.label, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.sp)) },
                            )
                        }
                    }
                    }
                }
            }
        }
    }
}

@Composable
internal fun Content(nav: Navigator) {
    when (val r = nav.current) {
        Route.Decks -> DecksScreen(
            onOpen = { nav.open(Route.Deck(it)) },
            onStudy = { nav.open(Route.Review(it)) },
            onStats = { nav.open(Route.Stats) },
            onBrowse = { nav.open(Route.Browser) },
            onPractice = { nav.open(Route.Practice()) },
            onCatalog = { nav.open(Route.Catalog) },
            onTexts = { nav.open(Route.Texts) },
        )
        Route.Texts -> TextsScreen(
            onBack = { nav.back() },
            onOpen = { nav.open(Route.TextDetail(it)) },
            onNew = { nav.open(Route.TextEditor(null)) },
            onStudy = { nav.open(Route.TextStudy(it)) },
        )
        is Route.TextEditor -> TextEditorScreen(
            r.id,
            onBack = { nav.back() },
            // a new text replaces the editor with its detail screen; an edited one returns to it
            onSaved = { id -> nav.back(); if (r.id == null) nav.open(Route.TextDetail(id)) },
        )
        is Route.TextDetail -> TextDetailScreen(
            r.id,
            onBack = { nav.back() },
            onEdit = { nav.open(Route.TextEditor(it)) },
            onReadings = { nav.open(Route.TextReadings(it)) },
            onStudy = { nav.open(Route.TextStudy(it)) },
            onCopybook = { pieceId -> nav.open(Route.Practice(textId = r.id, pieceId = pieceId)) },
        )
        is Route.TextReadings -> TextReadingsScreen(r.id, onBack = { nav.back() })
        is Route.TextStudy -> StudyScreen(r.request, onClose = { nav.back() })
        Route.Catalog -> CatalogScreen(onBack = { nav.back() }, onOpenDeck = { nav.open(Route.Deck(it)) })
        Route.Stats -> StatsScreen(onBack = { nav.back() }, onPractice = { nav.open(Route.Practice()) })
        is Route.Deck -> DeckScreen(
            r.id,
            onBack = { nav.back() },
            onStudy = { nav.open(Route.Review(it)) },
            onOpenDeck = { nav.open(Route.Deck(it)) },
            onOptions = { nav.open(Route.DeckOptions(r.id)) },
            onOpenCard = { nav.open(Route.CardEditor(it)) },
        )
        is Route.DeckOptions -> DeckOptionsScreen(r.id, onBack = { nav.back() }, onDeleted = { nav.back() })
        is Route.Review -> ReviewScreen(r.deckId, onClose = { nav.back() }, onOpenSource = nav::open)
        Route.Browser -> BrowserScreen(onBack = { nav.back() }, onOpenCard = { nav.open(Route.CardEditor(it)) })
        is Route.CardEditor -> CardEditorScreen(r.id, onBack = { nav.back() }, onPractice = { g, lang -> nav.open(Route.Practice(g, lang)) }, onOpenSource = nav::open)
        is Route.Practice -> PracticeScreen(r.glyph, r.language, r.textId, r.pieceId, onBack = { nav.back() })
        Route.Notebooks -> NotebooksScreen(onOpen = { id, page -> nav.open(Route.Notebook(id, page)) }, onOpenHit = { hit, q -> nav.openSearchHit(hit, q) })
        is Route.Notebook -> NotebookScreen(
            r.id, r.pageId, onBack = { nav.back() }, highlight = r.highlight, area = r.area,
            // shown once: recreation or a restored route must not mark the lines again
            onHighlightShown = { if (nav.current == r) nav.replaceTop(Route.Notebook(r.id, r.pageId)) },
            // the notebook screen is left while the grid is open: remember the page it shows
            onGrid = { page -> nav.replaceTop(Route.Notebook(r.id, page)); nav.open(Route.PageGrid(r.id)) },
            onOpenCard = { nav.open(Route.CardEditor(it)) },
        )
        is Route.PageGrid -> PageGridScreen(
            r.notebookId, onBack = { nav.back() },
            onOpen = { page -> nav.back(); nav.replaceTop(Route.Notebook(r.notebookId, page)) },
        )
        Route.Notes -> NotesScreen(onOpen = { nav.open(Route.Note(it)) })
        is Route.Note -> NoteEditorScreen(r.id, onBack = { nav.back() }, onCreated = { nav.replaceTop(Route.Note(it)) })
        Route.Diary -> DiaryScreen(onOpenDay = { nav.open(Route.DiaryDay(it)) })
        is Route.DiaryDay -> DiaryDayScreen(r.epochDay, onBack = { nav.back() })
        Route.Boards -> BoardsScreen(onOpen = { nav.open(Route.Board(it)) })
        is Route.Board -> BoardScreen(r.id, onBack = { nav.back() })
        Route.Settings -> SettingsScreen(onOpenLab = { nav.open(Route.WritingLab) }, onOpenTrash = { nav.open(Route.Trash) })
        Route.Trash -> TrashScreen(onBack = { nav.back() })
        Route.WritingLab -> WritingTestScreen()
    }
}
