package app.wanki.ui.notebooks

import android.util.Log
import android.widget.Toast
import app.wanki.ui.common.LocalUndo
import app.wanki.ui.common.listAction
import app.wanki.ui.common.ui
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.wanki.AppGraph
import app.wanki.R
import app.wanki.ui.common.HeaderAction
import app.wanki.ui.common.ScreenHeader
import app.wanki.data.NotebookEntity
import app.wanki.data.PageEntity
import app.wanki.data.PageLineEntity
import app.wanki.recognition.LineMatch
import app.wanki.recognition.PageRecognizer
import app.wanki.recognition.SearchText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.wanki.data.PageSize
import app.wanki.data.PageTemplate
import androidx.compose.runtime.mutableFloatStateOf
import app.wanki.graph
import app.wanki.ink.InkRect
import app.wanki.ink.InkStroke
import app.wanki.input.StrokeConversion
import androidx.ink.strokes.Stroke
import java.util.IdentityHashMap
import kotlinx.coroutines.isActive
import app.wanki.input.InkLayer
import app.wanki.input.InkTiles
import app.wanki.input.LoadedInk
import app.wanki.input.OpenTrace
import app.wanki.input.InkSurface
import app.wanki.input.EraserSettings
import app.wanki.input.InkSection
import app.wanki.input.ToolSelection
import app.wanki.input.ToolKind
import app.wanki.input.InkClipboard
import app.wanki.input.InkClip
import app.wanki.input.PageObject
import app.wanki.input.textBlockHeight
import app.wanki.input.toEntity
import app.wanki.input.toModelOrNull
import app.wanki.data.NotebookKind
import app.wanki.data.PageObjectEntity
import app.wanki.data.PageObjectKind
import app.wanki.input.Viewport
import app.wanki.input.PageBackgrounds
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import app.wanki.recognition.ModelStatus
import app.wanki.ui.common.InkToolbar
import app.wanki.ui.common.ErrorSnackbar
import app.wanki.ui.common.SaveErrorSnackbar
import app.wanki.ui.common.SerialSaver
import app.wanki.ui.common.SelectionBar
import app.wanki.ui.common.TextActionsDialog
import app.wanki.ui.common.CardFromSelectionDialog
import app.wanki.ui.common.CardSource
import app.wanki.ui.common.SelectionContent
import app.wanki.data.SourceKind
import app.wanki.ui.common.finalSave
import app.wanki.ui.common.rememberGuardedBack
import app.wanki.ui.common.copyToClipboard
import app.wanki.ui.common.PageGeometry
import app.wanki.ui.common.drawBoard
import app.wanki.ui.common.drawPage
import app.wanki.ui.common.label
import app.wanki.data.InkLoad
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.painterResource
import androidx.core.content.FileProvider
import app.wanki.media.PictureImport
import app.wanki.ui.common.Undo
import java.io.File

/**
 * Open panels of a page screen. Lives in the screen (not in the per-page [PageEditorState]), so turning a
 * page keeps the text panel open; saved across recreation.
 */
/** How long a search highlight stays on the page. */
const val HIGHLIGHT_MS = 4000L

class PageUiState {
    var showText by mutableStateOf(false)
    var showPaper by mutableStateOf(false)

    companion object {
        val Saver = androidx.compose.runtime.saveable.listSaver<PageUiState, Boolean>(
            save = { listOf(it.showText, it.showPaper) },
            restore = { PageUiState().apply { showText = it[0]; showPaper = it[1] } },
        )
    }
}

/**
 * A page's stored ink, read and decoded before its editor exists. [converted] fills up (on background threads) with
 * the strokes already converted for drawing, so the page's tiles need not convert them again.
 */
class InkPreload(
    val pageId: String,
    val ink: InkLoad,
    val converted: MutableMap<InkStroke, Stroke>,
    /** The strokes with their bounds and grid, built alongside the read (null when the ink is unreadable). */
    val prepared: LoadedInk?,
    /** [PageWrites.mark] taken before the read. */
    val writeMark: Long,
)

/** The notebook and its pages as a notebook screen opens, read with its first page's ink ([preloadInk]). */
class Opening(val notebook: NotebookEntity, val pages: List<PageEntity>)

/**
 * Holds a [preloadInk] result until the first editor takes it, so the screen does not keep that ink afterwards.
 * [conversion] parents the background conversion of its strokes.
 */
class InkPreloadSlot(
    private val result: Deferred<InkPreload?>,
    private val conversion: Job,
    /** The notebook and its pages (null when they could not be read: the screen's own queries show that). */
    val opening: Deferred<Opening?>,
) {
    private var pending: Deferred<InkPreload?>? = result

    /** The read-ahead ink, once: later calls (other pages of the screen) get null. Main thread. */
    fun take(): Deferred<InkPreload?>? = pending.also { pending = null }

    /** The result will not be used (another page, or its editor left): stops the read and the conversion. */
    fun discard() {
        pending = null
        result.cancel()
        conversion.cancel()
    }
}

/**
 * Starts reading the ink of the page a screen will open ([pageOf] picks it from the notebook's pages) on IO,
 * alongside the screen's own start-up queries, so the editor's [PageEditorState.load] does not wait for them; then
 * converts its strokes for drawing on the other cores while the screen composes.
 *
 * Nothing here may fail the receiver scope (the screen's): a failed read gives null, so the editor reads again
 * and shows the error, and a stroke that does not convert is left to the tile renderer, which handles failures.
 */
fun CoroutineScope.preloadInk(graph: AppGraph, notebookId: String, pageOf: (List<PageEntity>) -> PageEntity?): InkPreloadSlot {
    val scope = this
    // a child of the screen's job (cancelled with the screen) that a failing conversion cannot cancel
    val conversion = SupervisorJob(coroutineContext[Job])
    val opening = async(Dispatchers.IO) {
        try {
            val pages = graph.repository.pages(notebookId)
            OpenTrace.mark("preload: pages read")
            graph.repository.notebook(notebookId)?.let { Opening(it, pages) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e("Wanki", "notebook $notebookId: not read ahead", e) // the screen's own queries show the error
            null
        }
    }
    val result = async(Dispatchers.IO) {
        try {
            val page = pageOf(opening.await()?.pages ?: graph.repository.pages(notebookId)) ?: return@async null
            val mark = PageWrites.mark(page.id)
            val ink = graph.repository.loadInkResult(page.id)
            OpenTrace.mark("preload: ink decoded")
            val converted = java.util.Collections.synchronizedMap(IdentityHashMap<InkStroke, Stroke>())
            // the strokes indexed first: the editor waits for that, not for the conversion
            val prepared = (ink as? InkLoad.Ok)?.let { InkLayer.prepare(it.strokes) }
            OpenTrace.mark("preload: prepared")
            if (ink is InkLoad.Ok && ink.strokes.isNotEmpty()) {
                // the middle of the sheet first, where the tiles of a fitted page start
                val sheet = PageGeometry.bounds(page.landscape)
                val cx = (sheet.left + sheet.right) / 2
                val cy = (sheet.top + sheet.bottom) / 2
                val queue = ink.strokes.sortedBy { s -> s.points.firstOrNull()?.let { (it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) } ?: 0f }
                val next = java.util.concurrent.atomic.AtomicInteger()
                // with the tiles' render threads (which convert what they draw themselves), every core but the main
                // thread's: more threads only slow the first frame and the renders down
                val cores = Runtime.getRuntime().availableProcessors()
                val workers = (cores - 1 - InkTiles.renderThreads).coerceIn(1, 3)
                // not children of this async: the ink is handed over as soon as it is read
                repeat(workers) {
                    scope.launch(Dispatchers.Default + conversion) {
                        while (isActive) {
                            val s = queue.getOrNull(next.getAndIncrement()) ?: return@launch
                            if (converted.containsKey(s)) continue // a tile converted it already
                            try {
                                StrokeConversion.toInk(s)?.let { converted.putIfAbsent(s, it) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                // the tile that needs it converts it again, where a failure is handled
                                Log.e("Wanki", "page ${page.id}: a stroke not converted ahead", e)
                            }
                        }
                    }
                }
            }
            InkPreload(page.id, ink, converted, prepared, mark)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e("Wanki", "notebook $notebookId: ink not read ahead", e) // the editor reads again and shows the error
            null
        } finally {
            conversion.invokeOnCompletion { OpenTrace.mark("preload: conversion done") }
            conversion.complete() // ends once the conversions started above have
        }
    }
    return InkPreloadSlot(result, conversion, opening)
}

@Composable
fun rememberPageUi(): PageUiState = androidx.compose.runtime.saveable.rememberSaveable(saver = PageUiState.Saver) { PageUiState() }

/**
 * Loads, saves and recognizes one page. Ink is saved after every finished gesture (one
 * [InkLayer.version] step); zoom and pan are remembered per page. Lives as long as the page is on screen.
 */
class PageEditorState(
    private val graph: AppGraph,
    notebook: NotebookEntity,
    page: PageEntity,
    val infinite: Boolean,
    val ui: PageUiState = PageUiState(),
    /** Ink read ahead while the screen was still starting ([preloadInk]); used by the first [load] if it is this page's. */
    private val preload: InkPreloadSlot? = null,
    /** A search query: the lines of this page that contain it are marked for a few seconds after the page opens. */
    private val highlightQuery: String? = null,
    /** Called once the highlight has been shown (or could not be): the route forgets the query. */
    private val onHighlightShown: () -> Unit = {},
) {
    /** Current notebook settings; replaced when they change, so the state (and unsaved ink) stays. */
    var notebook by mutableStateOf(notebook)
    var page by mutableStateOf(page)
        private set
    val layer = InkLayer()

    fun cardSource() = CardSource(SourceKind.PAGE, page.id, notebook.id, notebook.language)
    val viewport = Viewport(minScale = if (infinite) 0.1f else 0.3f, bounds = if (infinite) null else PageGeometry.bounds(page.landscape, pageSizeOf(notebook)))
    private val section = InkSection.ofNotebookKind(notebook.kind)

    /** Saves the ink one save at a time; a failure is kept in [SerialSaver.error] until a save succeeds. */
    val saver = SerialSaver(graph.appScope) { saveIfDirty() }
    private var savedView: Triple<Float, Float, Float>? = null

    init {
        val s = page.viewScale
        val x = page.viewX
        val y = page.viewY
        if (s != null && x != null && y != null) {
            viewport.restore(s, x, y)
            savedView = Triple(s, x, y)
        }
    }
    var tool by mutableStateOf(graph.settings.tool(section))
        private set

    fun selectTool(t: ToolSelection) {
        tool = t
        graph.settings.setTool(section, t)
    }
    var eraser by mutableStateOf(graph.settings.eraser(section))
        private set

    fun selectEraser(e: EraserSettings) {
        eraser = e
        graph.settings.setEraser(section, e)
    }
    var loaded by mutableStateOf(false)
        private set

    /** Screen-only UI state, opened from the header menu. */
    var showText: Boolean
        get() = ui.showText
        set(v) { ui.showText = v }
    var showPaper: Boolean
        get() = ui.showPaper
        set(v) { ui.showPaper = v }
    /** The page's text as the lines show it (corrections included), one line per row. */
    var recognizedText by mutableStateOf("")
        private set

    /** The recognised lines, top to bottom. */
    var lines by mutableStateOf<List<PageLineEntity>>(emptyList())
        private set

    /** Lines the last recognition could not read; they are tried again on the next recognition. */
    var failedLines by mutableStateOf(0)
        private set

    /** Changed or left out as a whole by the correction/recognition writers; short sections only. */
    private val linesLock = Mutex()

    /** World boxes of the lines that matched the search query, shown for [HIGHLIGHT_MS]. */
    var highlight by mutableStateOf<List<InkRect>>(emptyList())
        private set
    var recognizing by mutableStateOf(false)
        private set
    private var savedRev = -1

    /** The stored ink could not be decoded: the page is shown empty, cannot be drawn on and is never saved. */
    var readOnly by mutableStateOf(false)
        private set
    private var viewJob: Job? = null

    /** The last zoom/pan save failed; shown in a snackbar until a later view save succeeds. */
    var viewError by mutableStateOf<Throwable?>(null)
        private set

    /** Retry from the snackbar: saves the view now. */
    fun retryViewSave() {
        graph.appScope.launch {
            try {
                saveView()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Wanki", "page ${page.id}: view not saved", e)
                viewError = e
            }
        }
    }
    private var recognizedVersion = -1

    /** The notebook's paper size; pages follow it. */
    val pageSize: PageSize get() = pageSizeOf(notebook)

    /** An endless sheet: grows downward with the content, always portrait. */
    val endless: Boolean get() = !infinite && pageSize == PageSize.ENDLESS

    /** The lowest painted point of ink and objects of an endless page (0 elsewhere); moves when a gesture ends. */
    var contentBottom by mutableFloatStateOf(0f)
        private set

    /** The sheet in world units, following the paper size and (for endless pages) the content. */
    val bounds: InkRect get() = PageGeometry.bounds(page.landscape, pageSize, contentBottom)

    /** Recomputes [contentBottom] and the viewport limits; called when a gesture or edit ended, never per move. */
    fun refreshBounds() {
        if (infinite) return
        var low = 0f
        if (endless) {
            for (s in layer.strokes) layer.bounds(s)?.let { low = maxOf(low, it.bottom + s.brush.size / 2) }
            for (o in layer.objects) low = maxOf(low, o.bounds().bottom)
        }
        if (low != contentBottom) contentBottom = low
        if (viewport.bounds != bounds) viewport.bounds = bounds
    }

    val template: PageTemplate get() = runCatching { PageTemplate.valueOf(page.template) }.getOrDefault(PageTemplate.BLANK)

    /** Model status across the languages this page is recognized with. */
    val modelStatus: ModelStatus
        get() {
            val statuses = graph.textRecognition.candidateLanguages(notebook.language).map { graph.recognizers.get(it).status }
            return when {
                statuses.any { it == ModelStatus.DOWNLOADING } -> ModelStatus.DOWNLOADING
                statuses.any { it == ModelStatus.READY } -> ModelStatus.READY
                statuses.all { it == ModelStatus.UNAVAILABLE } -> ModelStatus.UNAVAILABLE
                else -> ModelStatus.UNKNOWN
            }
        }

    /** This page's editor is on screen and its ink loaded and writable: edits of its page go through its layer. */
    val editable: Boolean get() = loaded && !readOnly && openEditors[page.id] === this

    companion object {
        /** The editor on screen for each page (main thread), so an undo from the snackbar edits the open page's layer. */
        private val openEditors = HashMap<String, PageEditorState>()

        /** The open, loaded and writable editor of [pageId], if any. Main thread. */
        fun editorOf(pageId: String): PageEditorState? = openEditors[pageId]?.takeIf { it.editable }

        /** The editor on screen for [pageId], loaded or still loading. Main thread. */
        fun openEditorOf(pageId: String): PageEditorState? = openEditors[pageId]

        /**
         * The open editor of [pageId] once its ink is loaded, waiting (up to [timeoutMs]) while one is still loading,
         * so a change meant for that page goes through its layer instead of under it into the stored page, which the
         * loading editor may have read already. Null when no editor is open or the open one cannot edit (an
         * unreadable page). Main thread; the answer holds until the caller next suspends.
         */
        suspend fun awaitEditor(pageId: String, timeoutMs: Long = 30_000): PageEditorState? {
            val end = android.os.SystemClock.uptimeMillis() + timeoutMs
            while (true) {
                val e = openEditors[pageId] ?: return null
                if (e.loaded) return e.takeIf { it.editable }
                check(android.os.SystemClock.uptimeMillis() < end) { "page $pageId still opening" }
                delay(16)
            }
        }
    }

    internal fun opened() { openEditors[page.id] = this }
    internal fun closed() { if (openEditors[page.id] === this) openEditors.remove(page.id) }

    private var loadJob: Job? = null

    /**
     * Starts [load] once, in [scope] (main thread), as soon as the state exists, so the ink is read while the screen
     * composes; later calls do nothing. The load is posted, never run inside the composition that calls this.
     */
    fun startLoad(scope: CoroutineScope) {
        if (loadJob != null) return
        loadJob = scope.launch(Dispatchers.Main) { load() }
    }

    /** The editor left before its page was read: the read stops. */
    internal fun stopLoad() { if (!loaded) loadJob?.cancel() }

    /**
     * Reads the page: ink, objects, lines. A page that cannot be read (its ink does not decode, the database fails, a
     * row too big to read) opens read-only with «Страницу не удалось прочитать», and nothing of it is ever saved.
     */
    suspend fun load() {
        OpenTrace.mark("load start")
        try {
            readPage()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Wanki", "page ${page.id}: not read", e)
            readOnly = true
        }
        loaded = true
        OpenTrace.mark("loaded")
    }

    /** The page's ink as stored now: the one read ahead if no write of the page ran since, else read again. */
    private suspend fun readInk(ahead: InkPreload?): Pair<InkLoad, LoadedInk?> {
        if (ahead != null && PageWrites.unchangedSince(page.id, ahead.writeMark)) return ahead.ink to ahead.prepared
        while (true) {
            PageWrites.awaitIdle(page.id)
            val mark = PageWrites.mark(page.id)
            // decoding thousands of strokes and indexing them is not main-thread work
            val read = withContext(Dispatchers.IO) {
                val r = graph.repository.loadInkResult(page.id)
                r to (r as? InkLoad.Ok)?.let { InkLayer.prepare(it.strokes) }
            }
            if (PageWrites.unchangedSince(page.id, mark)) return read
        }
    }

    private suspend fun readPage() {
        val slot = preload
        val ahead = slot?.take()?.let { p ->
            try {
                p.await()?.takeIf { it.pageId == page.id } ?: run { slot.discard(); null }
            } catch (e: CancellationException) {
                slot.discard() // the editor left before the ink arrived
                throw e
            } catch (e: Exception) {
                Log.e("Wanki", "page ${page.id}: ink not read ahead", e) // read again below, where a failure is shown
                slot.discard()
                null
            }
        }
        val (ink, prepared) = readInk(ahead)
        when (ink) {
            is InkLoad.Ok -> {
                OpenTrace.mark("load: ink in hand")
                OpenTrace.timed("load: adoptLoaded") { layer.adoptLoaded(prepared ?: InkLayer.prepare(ink.strokes)) }
                ahead?.takeIf { it.prepared === prepared }?.let { layer.adoptConverted(it.converted) }
                // encode the stroke chunks now, so the first save after an edit only encodes the new stroke; on a
                // low-priority thread, so it does not slow the tiles of the page being opened
                val encode = layer.snapshotEncoder()
                graph.appScope.launch(Background) {
                    try {
                        encode()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("Wanki", "page ${page.id}: chunks not prepared", e) // the next save encodes again and reports it
                    }
                }
            }
            is InkLoad.Unreadable -> {
                // the stored blob stays untouched: nothing is saved or recognized from this page
                Log.e("Wanki", "page ${page.id}: ink cannot be read", ink.error)
                readOnly = true
            }
        }
        // objects are shown on an unreadable page too (they cannot be edited there either)
        val stored = withContext(Dispatchers.IO) { graph.repository.pageObjects(page.id) }
        val known = stored.mapNotNull { e -> e.toModelOrNull() }
        // a kind this version does not know is kept as it is and saved back with the others
        foreignObjects = stored.filter { it.toModelOrNull() == null }
        layer.resetObjects(known)
        val storedLines = graph.repository.pageLines(page.id)
        lines = storedLines
        // pages recognised before lines existed only have the joined text
        recognizedText = if (storedLines.isEmpty()) graph.repository.recognizedText(page.id) else joined(storedLines)
        savedObjectsRev = layer.objectsRev
        savedRev = layer.strokesRev
        // opening a page does not recognise it (a big page would cost the canvas): lines are checked after an edit
        recognizedVersion = layer.version
        refreshBounds()
        // a stored view deep down an endless page was clamped to the short sheet before the ink was read
        savedView?.let { (sc, x, y) -> if (endless && viewport.hasLayout) viewport.restore(sc, x, y) }
    }

    /**
     * While strokes move to another page ([moveSelectionToPage]) the canvas already lacks them but the target is not
     * written yet: no save may store the source without them until the move's transaction has. A count, so overlapping
     * moves each hold until their own transaction is done. Main thread.
     */
    var saveHolds = 0

    /** Stored objects of kinds this version does not know: never shown, saved back unchanged. */
    private var foreignObjects: List<PageObjectEntity> = emptyList()
    private var savedObjectsRev = -1

    /** Text blocks and pictures go on notebook and diary pages, not on boards (Decision 10). */
    val objectsAllowed: Boolean get() = notebook.kind != NotebookKind.BOARD.name

    /** The page's objects as stored rows, [foreignObjects] included. Main thread. */
    fun objectEntities(): List<PageObjectEntity> = layer.objects.map { it.toEntity(page.id) } + foreignObjects

    /** The ink when [InkLayer.strokesRev] moved, then the objects when [InkLayer.objectsRev] moved. */
    private suspend fun saveIfDirty() {
        if (!loaded || readOnly || saveHolds > 0) return
        if (layer.strokesRev != savedRev) {
            val rev = layer.strokesRev
            val encode = layer.snapshotEncoder() // the stroke list is taken here, the joining runs on IO
            PageWrites.around(page.id) { withContext(Dispatchers.IO) { graph.repository.saveInkBlob(page, encode()) } }
            savedRev = rev
        }
        if (layer.objectsRev != savedObjectsRev) {
            val rev = layer.objectsRev
            val list = objectEntities()
            PageWrites.around(page.id) { withContext(Dispatchers.IO) { graph.repository.savePageObjects(page, list) } }
            savedObjectsRev = rev
        }
    }

    /** Whether objects may be added or changed now. */
    private val objectsEditable: Boolean get() = loaded && !readOnly && objectsAllowed

    /** Selects just [id] with the lasso ready to move it. */
    private fun selectObject(id: String) {
        layer.clearSelection()
        layer.selectedObjects.add(id)
        selectTool(tool.copy(kind = ToolKind.LASSO))
    }

    /**
     * Where a new object goes: the centre of the view, moved by +48, +48 world units while an object's centre is
     * within 24 of it, so consecutive inserts do not hide each other.
     */
    private fun insertCentre(): Pair<Float, Float> {
        val v = viewport.visibleWorld()
        var x = (v.left + v.right) / 2
        var y = (v.top + v.bottom) / 2
        while (layer.objects.any { kotlin.math.hypot(it.centerX - x, it.centerY - y) <= INSERT_TAKEN }) {
            x += INSERT_STEP
            y += INSERT_STEP
        }
        return x to y
    }

    /** A text block [TEXT_BLOCK_WIDTH] wide at the centre of the view, in the pen's colour; selected. */
    fun insertText(text: String, size: Float) {
        if (!objectsEditable || text.isBlank()) return
        val (cx, cy) = insertCentre()
        val w = TEXT_BLOCK_WIDTH
        val h = textBlockHeight(text, w, size)
        val o = PageObject(
            PageObject.newId(), PageObjectKind.TEXT, cx - w / 2, cy - h / 2, w, h,
            0f, layer.nextZ(), text, tool.color, size, null,
        )
        layer.addObject(o)
        selectObject(o.id)
    }

    /** A new text and size for the text block [id] (one undo step); its height follows the text. */
    fun editText(id: String, text: String, size: Float) {
        if (!objectsEditable || text.isBlank()) return
        val o = layer.objects.firstOrNull { it.id == id && it.kind == PageObjectKind.TEXT } ?: return
        layer.updateObject(o.copy(text = text, textSize = size, h = textBlockHeight(text, o.w, size)))
    }

    /** The stored picture [media] ([width] × [height] px) fitted into 600 × 600 at the centre of the view; selected. */
    fun insertPicture(media: String, width: Int, height: Int): Boolean {
        if (!objectsEditable || width <= 0 || height <= 0) return false
        val (cx, cy) = insertCentre()
        val k = PICTURE_BOX / maxOf(width, height)
        val w = width * k
        val h = height * k
        val o = PageObject(
            PageObject.newId(), PageObjectKind.IMAGE, cx - w / 2, cy - h / 2, w, h,
            0f, layer.nextZ(), "", ToolSelection.DEFAULT_INK, 0f, media,
        )
        layer.addObject(o)
        selectObject(o.id)
        return true
    }

    private fun joined(l: List<PageLineEntity>) = l.sortedBy { it.lineIndex }.joinToString("\n") { it.shown }

    suspend fun recognizeIfDirty() {
        if (!loaded || readOnly || layer.version == recognizedVersion) return
        recognizeNow()
    }

    /** Old search text kept for lines that failed (see [SearchText]); null once every line has been read. */
    private var retained: Retained? = null

    private class Retained(val failedIndexes: List<Int>, val dropped: List<PageLineEntity>, val oldText: String)

    private fun searchTextFor(l: List<PageLineEntity>): String? =
        retained?.let { SearchText.build(l, it.failedIndexes, it.dropped, it.oldText) }

    /** Recognizes the lines whose strokes changed (none sent when nothing did) and stores the lines and the search text. */
    private suspend fun recognizeNow() {
        val v = layer.version
        val strokes: List<InkStroke> = layer.strokes.toList()
        val before = lines
        recognizing = true
        val result = try {
            // segmentation and key hashing of a big page stay off the main thread
            withContext(Dispatchers.Default) {
                PageRecognizer(graph.lineRecognizer).recognize(strokes, layer::strokeKey, before, notebook.language, page.id)
            }
        } finally {
            recognizing = false
        }
        // the recognised text lives in the ink row: the ink must be stored first
        if (strokes.isNotEmpty()) {
            saver.request()
            saver.flush()
        }
        linesLock.withLock {
            // a correction made while the recognizer worked wins over the row it was computed from
            val latest = graph.repository.pageLines(page.id).associateBy { it.lineKey }
            val merged = result.lines.map { l -> latest[l.lineKey]?.let { l.copy(corrected = it.corrected) } ?: l }
            retained = if (result.failed == 0) null else {
                val keys = merged.map { it.lineKey }.toSet()
                Retained(
                    result.failedIndexes, latest.values.filter { it.lineKey !in keys },
                    retained?.oldText ?: graph.repository.recognizedText(page.id),
                )
            }
            val text = searchTextFor(merged)
            if (merged != lines || merged != latest.values.sortedBy { it.lineIndex } || (text != null && text != graph.repository.recognizedText(page.id))) {
                graph.repository.saveLines(page.id, merged, text)
            }
            lines = merged
            // nothing read at all: the text shown stays what it was
            if (!(merged.isEmpty() && result.failed > 0)) recognizedText = joined(merged)
            failedLines = result.failed
        }
        if (result.failed == 0) recognizedVersion = v
    }

    /** Sets the user's fix of one line; blank or equal to the recognised text resets it. Nothing on an unreadable page. */
    suspend fun correctLine(lineKey: Long, text: String) {
        if (!loaded || readOnly) return
        linesLock.withLock {
            val line = lines.firstOrNull { it.lineKey == lineKey } ?: return
            val fix = text.trim().takeIf { it.isNotEmpty() && it != line.recognized }
            val next = lines.map { if (it.lineKey == lineKey) it.copy(corrected = fix) else it }
            graph.repository.setLineCorrection(page.id, lineKey, fix, searchTextFor(next))
            lines = next
            recognizedText = joined(next)
        }
    }

    /**
     * Marks the lines that match the search query and puts the viewport on the first one (not a gesture: the view is
     * not saved for it). The lines are brought up to date first (a page from before lines existed, or edited since its
     * last recognition, has none or old ones); when that fails the page opens without a mark.
     */
    suspend fun showHighlight() {
        val q = highlightQuery?.takeIf { it.isNotBlank() } ?: return
        if (!readOnly) {
            try {
                recognizeNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Wanki", "page ${page.id}: lines not read for the highlight", e)
            }
        }
        highlight = lines.filter { LineMatch.matches(it.shown, q) }.map { InkRect(it.left, it.top, it.right, it.bottom) }
        onHighlightShown()
        val first = highlight.firstOrNull() ?: return
        var waited = 0
        while (!viewport.hasLayout && waited < 2000) { delay(16); waited += 16 }
        if (!viewport.hasLayout) return
        viewport.centerOn(first)
        savedView = Triple(viewport.scale, viewport.offsetX, viewport.offsetY)
        delay(HIGHLIGHT_MS)
        highlight = emptyList()
    }

    /** A pan or pinch ended: stores the view a second later (a following gesture restarts the wait). */
    fun onViewGestureEnd() {
        viewJob?.cancel()
        viewJob = graph.appScope.launch {
            delay(1000)
            try {
                saveView()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Wanki", "page ${page.id}: view not saved", e)
                viewError = e
            }
        }
    }

    /** Stores the current zoom and pan if they changed (not an edit: the page's date stays). */
    suspend fun saveView() {
        if (!loaded || !viewport.hasLayout) return
        val v = Triple(viewport.scale, viewport.offsetX, viewport.offsetY)
        if (v == savedView) return
        graph.repository.setPageView(page.id, v.first, v.second, v.third)
        savedView = v
        viewError = null
    }

    suspend fun setTemplate(t: PageTemplate) {
        graph.repository.setPageTemplate(page, t)
        page = page.copy(template = t.name)
    }

    /** What «Вставить» puts here: a board takes only the strokes of the clip. */
    fun pastable(): InkClip? = InkClipboard.clip?.let { if (objectsAllowed) it else it.copy(objects = emptyList()) }?.takeIf { !it.isEmpty }

    /** Puts the ink clipboard at the centre of the view, selected, with the lasso ready to move it. */
    fun paste() {
        val clip = pastable() ?: return
        if (!loaded || readOnly) return
        val v = viewport.visibleWorld()
        layer.paste(clip, (v.left + v.right) / 2, (v.top + v.bottom) / 2)
        selectTool(tool.copy(kind = ToolKind.LASSO))
    }

    /** The page's background picture or PDF page as rendered for the current zoom; null while it loads or when none. */
    var background by mutableStateOf<android.graphics.Bitmap?>(null)
        private set

    /** The page has a background that could not be rendered: the paper shows with «Фон не загрузился». */
    var backgroundFailed by mutableStateOf(false)
        private set

    /** Renders the background again when the zoom passes a power of two; a finer render that fails keeps the coarser one. */
    suspend fun followBackground() {
        val name = page.backgroundMedia
        if (name == null || infinite) {
            background = null
            backgroundFailed = false
            return
        }
        val pdfPage = page.backgroundPage
        val box = PageBackgrounds.box(page.landscape, pageSize)
        val long = maxOf(box.width, box.height)
        snapshotFlow { PageBackgrounds.sideFor(long, viewport.scale) }.distinctUntilChanged().collectLatest { side ->
            val b = PageBackgrounds.bitmap(graph.media, name, pdfPage, side)
            if (b != null) {
                background = b
                backgroundFailed = false
            } else if (background == null) backgroundFailed = true
        }
    }

    /** Strokes mostly past the edge of the (portrait) sheet, e.g. after the page was turned. */
    fun offPageCount(): Int = if (infinite || endless) 0 else bounds.let { layer.outside(it).size + layer.objectsOutside(it).size }

    /** Selects the strokes past the edge, with the lasso ready to move them back. */
    fun selectOffPage(): Int {
        val n = layer.selectOutside(bounds)
        if (n > 0) selectTool(tool.copy(kind = ToolKind.LASSO))
        return n
    }

    /** Turns the page; the page re-fits so the whole sheet is in view. */
    suspend fun setLandscape(landscape: Boolean) {
        if (landscape == page.landscape || endless) return
        graph.repository.setPageLandscape(page, landscape)
        page = page.copy(landscape = landscape)
        viewport.bounds = bounds
        viewport.fitWidth()
    }
}

/** Header actions shared by every page screen. «Бумага…» only makes sense for sheets, not for boards. */
fun PageEditorState.paperAction() = HeaderAction(R.drawable.ic_article, "Бумага…", "menu-template") { showPaper = true }
fun PageEditorState.fitAction() = HeaderAction(R.drawable.ic_fit_screen, "Вписать", "fit") { viewport.fitWidth() }
private const val TEXT_BLOCK_WIDTH = 600f
private const val PICTURE_BOX = 600f
private const val INSERT_TAKEN = 24f
private const val INSERT_STEP = 48f

fun PageEditorState.textAction() = HeaderAction(R.drawable.ic_notes, "Текст", "show-text") { showText = !showText }

/**
 * Full-screen page: header, one-row toolbar, the zoomable canvas, [pageBar] and the recognized
 * text (side panel on wide windows, bottom sheet on phones).
 * Pinch with two fingers to zoom; with a pen nearby one finger pans.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageEditor(
    state: PageEditorState,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    primary: List<HeaderAction> = emptyList(),
    menu: List<HeaderAction> = emptyList(),
    pageBar: @Composable () -> Unit = {},
    /** A two-finger fling at the page edge turns the page: +1 next, -1 previous. */
    onPageSwipe: ((Int) -> Unit)? = null,
    /** «На другую страницу…» for the lasso selection (notebooks only). */
    onMoveSelection: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val graph = context.graph
    var translateAll by remember { mutableStateOf(false) }
    val undo = LocalUndo.current
    val insert = remember(state) { InsertUi() }
    androidx.compose.runtime.SideEffect { app.wanki.input.OpenTrace.mark("PageEditor composed") }

    // usually started already when the screen made the state, so the page is read while this composes
    LaunchedEffect(state) { state.startLoad(this) }
    LaunchedEffect(state, state.loaded) { if (state.loaded) ui { state.showHighlight() } }
    LaunchedEffect(state, state.page.backgroundMedia, state.page.backgroundPage, state.page.landscape, state.pageSize) { ui { state.followBackground() } }
    // an endless sheet grows when a stroke or edit ends (and follows a changed paper size)
    LaunchedEffect(state.layer.version, state.layer.strokesRev, state.layer.objectsRev, state.pageSize, state.loaded) {
        if (state.loaded) state.refreshBounds()
    }
    // version only changes when a gesture ends: save each one; recognize after a pause
    LaunchedEffect(state.layer.version, state.loaded) {
        if (!state.loaded) return@LaunchedEffect
        state.saver.request()
        delay(2500)
        ui { state.recognizeIfDirty() }
    }
    // leaving while saves fail asks first; leaving any other way still saves once more and reports a failure
    val back = rememberGuardedBack(state.saver, onBack)
    DisposableEffect(state) {
        state.opened()
        onDispose {
            state.closed()
            state.stopLoad()
            graph.appScope.launch {
                // a change from the last frame may not have reached the effect above yet; the page opened again
                // meanwhile reads it once this is stored
                val id = state.page.id
                PageWrites.around(id) { state.saver.finalSave(context.applicationContext, "page $id") }
                runCatching { state.saveView() }.onFailure {
                    Log.e("Wanki", "page $id: view not saved", it)
                    Toast.makeText(context.applicationContext, "Не удалось сохранить вид страницы", Toast.LENGTH_LONG).show()
                }
                runCatching { state.recognizeIfDirty() }.onFailure { Log.e("Wanki", "page $id: recognition failed", it) }
            }
        }
    }

    BoxWithConstraints(modifier) {
        val wide = maxWidth >= 600.dp
        val sheetMax = maxHeight / 2
        Column(Modifier.fillMaxSize()) {
            val paste = if (state.pastable() != null && state.loaded && !state.readOnly) {
                listOf(HeaderAction(R.drawable.ic_content_paste, "Вставить", "menu-paste") { state.paste() })
            } else emptyList()
            val inserts = if (state.objectsAllowed && state.loaded && !state.readOnly) listOf(
                HeaderAction(R.drawable.ic_text_fields, "Текстовый блок", "menu-insert-text") { insert.text = true },
                HeaderAction(R.drawable.ic_image, "Картинка", "menu-insert-image") { insert.picture = true },
            ) else emptyList()
            ScreenHeader(title, onBack = back, subtitle = subtitle, subtitleMaxLines = 1, primary = primary, menu = paste + inserts + menu)
            InkToolbar(state.tool, state::selectTool, state.layer, state.eraser, state::selectEraser, Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            if (state.layer.hasSelection && !state.readOnly) {
                SelectionBar(
                    state.layer, state.notebook.language, Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    onMoveToPage = onMoveSelection,
                    onEditText = if (state.objectsAllowed) ({ id -> insert.editing = id }) else null,
                    cardSource = state.cardSource(),
                )
            }
            if (state.readOnly) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Страницу не удалось прочитать",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).testTag("page-unreadable"),
                    )
                }
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.weight(1f).fillMaxSize()) {
                InkSurface(
                    layer = state.layer,
                    tool = state.tool,
                    viewport = state.viewport,
                    enabled = state.loaded,
                    readOnly = state.readOnly,
                    gestures = true,
                    eraser = state.eraser,
                    onViewGestureEnd = state::onViewGestureEnd,
                    onPageSwipe = onPageSwipe,
                    modifier = Modifier.fillMaxSize().testTag("page-canvas"),
                    background = {
                        if (state.infinite) drawBoard(state.viewport.visibleWorld(), state.viewport.scale)
                        else {
                            drawPage(state.template, state.page.landscape, state.pageSize, state.contentBottom, state.viewport.visibleWorld())
                            state.background?.let { b ->
                                drawIntoCanvas { PageBackgrounds.draw(it.nativeCanvas, b, PageBackgrounds.box(state.page.landscape, state.pageSize)) }
                            }
                        }
                    },
                )
                HighlightBoxes(state)
                if (state.backgroundFailed) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    ) {
                        Text(
                            "Фон не загрузился",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).testTag("background-failed"),
                        )
                    }
                }
                }
                if (state.showText && wide) {
                    Surface(tonalElevation = 1.dp, modifier = Modifier.width(280.dp).fillMaxSize()) {
                        RecognizedLinesPanel(state, onTranslate = { translateAll = true })
                    }
                }
            }
            pageBar()
        }
        SaveErrorSnackbar(state.saver, Modifier.align(Alignment.BottomCenter))
        ErrorSnackbar(state.viewError, "Не удалось сохранить вид страницы", onRetry = state::retryViewSave, modifier = Modifier.align(Alignment.BottomCenter))
        if (state.showText && !wide) {
            ModalBottomSheet(
                onDismissRequest = { state.showText = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                modifier = Modifier.heightIn(max = sheetMax),
            ) {
                RecognizedLinesPanel(state, onTranslate = { translateAll = true })
            }
        }
    }
    if (state.showPaper && !state.infinite) {
        // turning the page to portrait can leave writing past its edge: offer to select it
        var turned by remember { mutableStateOf(false) }
        val offPage = remember(turned, state.page.landscape, state.layer.strokesRev) { if (turned && !state.page.landscape && !state.endless) state.offPageCount() else 0 }
        AlertDialog(
            onDismissRequest = { state.showPaper = false },
            title = { Text("Бумага") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    FilterChip(
                        state.page.landscape,
                        {
                            graph.appScope.listAction(undo, "Не удалось повернуть страницу") {
                                state.setLandscape(!state.page.landscape)
                                turned = true
                            }
                        },
                        { Text("Альбомная страница") },
                        enabled = !state.endless,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("paper-landscape"),
                    )
                    if (offPage > 0) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.padding(vertical = 4.dp)) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text("Часть записей оказалась за краем страницы", style = MaterialTheme.typography.bodyMedium)
                                TextButton(
                                    onClick = {
                                        state.selectOffPage()
                                        state.showPaper = false
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("select-offpage"),
                                ) { Text("Выделить их") }
                            }
                        }
                    }
                    PageTemplate.entries.forEach { t ->
                        TextButton(
                            onClick = {
                                state.showPaper = false
                                graph.appScope.listAction(undo, "Не удалось сменить бумагу") { state.setTemplate(t) }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(
                                (if (t == state.template) "✓ " else "") + t.label(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { state.showPaper = false }) { Text("Закрыть") } },
        )
    }
    var pageCard by remember { mutableStateOf(false) }
    if (translateAll) TextActionsDialog(
        initialText = state.recognizedText, onDismiss = { translateAll = false },
        onMakeCard = { translateAll = false; pageCard = true },
    )
    // a card from the whole page: no selection, so no bounds and no picture
    if (pageCard) CardFromSelectionDialog(
        state.cardSource(), read = { SelectionContent(state.recognizedText, null, null) }, onDismiss = { pageCard = false },
    )
    val owner = PicturePickers.registry?.let { r ->
        remember(r) { object : ActivityResultRegistryOwner { override val activityResultRegistry = r } }
    } ?: LocalActivityResultRegistryOwner.current
    if (owner != null) CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { InsertDialogs(state, insert) }
}

/** Which insert dialog is open. */
private class InsertUi {
    var text by mutableStateOf(false)
    var picture by mutableStateOf(false)
    /** The text block being edited ([SelectionBar]'s «Изменить текст»). */
    var editing by mutableStateOf<String?>(null)
}

/** Lets a test answer the picture pickers ([ActivityResultRegistry]); null in the app. */
object PicturePickers {
    @VisibleForTesting
    var registry: ActivityResultRegistry? = null
}

/** «Текстовый блок», the «Картинка» choice («Из галереи», «Снимок») and the text block editor. */
@Composable
private fun InsertDialogs(state: PageEditorState, insert: InsertUi) {
    val context = LocalContext.current
    val graph = context.graph
    val undo = LocalUndo.current
    val scope = rememberCoroutineScope()
    // the camera's file, kept across recreation while the camera app is open
    var shot by rememberSaveable { mutableStateOf<String?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) importPicture(context.applicationContext, graph, undo, state, uri, null)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = shot?.let(::File)
        shot = null
        if (file == null) return@rememberLauncherForActivityResult
        if (taken && file.length() > 0) importPicture(context.applicationContext, graph, undo, state, Uri.fromFile(file), file) else file.delete()
    }
    if (insert.text) {
        TextBlockDialog(onDone = { t, size -> insert.text = false; state.insertText(t, size) }, onDismiss = { insert.text = false })
    }
    insert.editing?.let { id ->
        val o = state.layer.objects.firstOrNull { it.id == id }
        if (o == null) insert.editing = null
        else TextBlockDialog(o.text, o.textSize, onDone = { t, size -> insert.editing = null; state.editText(id, t, size) }, onDismiss = { insert.editing = null })
    }
    if (insert.picture) {
        AlertDialog(
            onDismissRequest = { insert.picture = false },
            title = { Text("Картинка") },
            text = {
                Column {
                    PictureSource("Из галереи", R.drawable.ic_photo_library, "insert-gallery") {
                        insert.picture = false
                        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    PictureSource("Снимок", R.drawable.ic_photo_camera, "insert-camera") {
                        insert.picture = false
                        try {
                            val file = File(context.cacheDir, "camera/shot-${java.util.UUID.randomUUID()}.jpg").apply { parentFile?.mkdirs() }
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                            shot = file.path
                            camera.launch(uri)
                        } catch (e: ActivityNotFoundException) {
                            shot = null
                            scope.launch { undo.say("Нет приложения камеры") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { insert.picture = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun PictureSource(label: String, icon: Int, tag: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) {
        androidx.compose.material3.Icon(painterResource(icon), contentDescription = null)
        Text(label, modifier = Modifier.padding(start = 12.dp).weight(1f))
    }
}

/** Stores the picture at [uri] (on IO) and puts it on [state]'s page; a failure is said in the shell snackbar. [cleanup] is deleted after. */
private fun importPicture(context: android.content.Context, graph: AppGraph, undo: Undo, state: PageEditorState, uri: Uri, cleanup: File?) {
    graph.appScope.launch {
        try {
            val (name, size) = withContext(Dispatchers.IO) {
                val n = PictureImport.prepare(context, uri, graph.media)
                n to PictureImport.size(graph.media, n)
            }
            ui {
                when {
                    !state.editable -> undo.say("Картинка не вставлена: страница закрыта")
                    size == null || !state.insertPicture(name, size.first, size.second) -> undo.say("Не удалось вставить картинку")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Wanki", "page ${state.page.id}: picture not inserted", e)
            ui { undo.say(if (e is java.io.IOException && e.message == "unreadable") "Это не картинка" else "Не удалось вставить картинку") }
        } finally {
            cleanup?.delete()
        }
    }
}

/** One low-priority thread for work that may wait (a page's save chunks), so it does not slow the page being opened. */
private val Background = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
    Thread(r, "wanki-background").apply {
        priority = Thread.MIN_PRIORITY
        isDaemon = true
    }
}.asCoroutineDispatcher()

private fun pageSizeOf(notebook: NotebookEntity): PageSize = runCatching { PageSize.valueOf(notebook.pageSize) }.getOrDefault(PageSize.A4)
