package app.wanki.ui.notebooks

import android.util.Log
import android.widget.Toast
import app.wanki.ui.common.LocalUndo
import app.wanki.ui.common.listAction
import app.wanki.ui.common.ui
import androidx.compose.foundation.layout.Arrangement
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
import app.wanki.data.PageTemplate
import app.wanki.graph
import app.wanki.ink.InkRect
import app.wanki.ink.InkStroke
import app.wanki.input.StrokeConversion
import androidx.ink.strokes.Stroke
import java.util.IdentityHashMap
import kotlinx.coroutines.isActive
import app.wanki.input.InkLayer
import app.wanki.input.InkSurface
import app.wanki.input.EraserSettings
import app.wanki.input.InkSection
import app.wanki.input.ToolSelection
import app.wanki.input.ToolKind
import app.wanki.input.InkClipboard
import app.wanki.input.Viewport
import app.wanki.recognition.ModelStatus
import app.wanki.ui.common.InkToolbar
import app.wanki.ui.common.ErrorSnackbar
import app.wanki.ui.common.SaveErrorSnackbar
import app.wanki.ui.common.SerialSaver
import app.wanki.ui.common.SelectionBar
import app.wanki.ui.common.TextActionsDialog
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
import kotlinx.coroutines.launch

/**
 * Open panels of a page screen. Lives in the screen (not in the per-page [PageEditorState]), so turning a
 * page keeps the text panel open; saved across recreation.
 */
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
    /** Each stroke's bounds, computed alongside the read. */
    val bounds: Map<InkStroke, InkRect?>,
)

/**
 * Holds a [preloadInk] result until the first editor takes it, so the screen does not keep that ink afterwards.
 * [conversion] parents the background conversion of its strokes.
 */
class InkPreloadSlot(private val result: Deferred<InkPreload?>, private val conversion: Job) {
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
    val result = async(Dispatchers.IO) {
        try {
            val page = pageOf(graph.repository.pages(notebookId)) ?: return@async null
            val ink = graph.repository.loadInkResult(page.id)
            val converted = java.util.Collections.synchronizedMap(IdentityHashMap<InkStroke, Stroke>())
            if (ink is InkLoad.Ok && ink.strokes.isNotEmpty()) {
                val parts = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 3)
                // not children of this async: the ink is handed over as soon as it is read
                ink.strokes.chunked((ink.strokes.size + parts - 1) / parts).forEach { part ->
                    scope.launch(Dispatchers.Default + conversion) {
                        for (s in part) {
                            if (!isActive) return@launch
                            if (converted.containsKey(s)) continue
                            try {
                                StrokeConversion.toInk(s)?.let { converted[s] = it }
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
            val bounds = IdentityHashMap<InkStroke, InkRect?>()
            if (ink is InkLoad.Ok) for (s in ink.strokes) bounds[s] = s.bounds()
            InkPreload(page.id, ink, converted, bounds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e("Wanki", "notebook $notebookId: ink not read ahead", e) // the editor reads again and shows the error
            null
        } finally {
            conversion.complete() // ends once the conversions started above have
        }
    }
    return InkPreloadSlot(result, conversion)
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
) {
    /** Current notebook settings; replaced when they change, so the state (and unsaved ink) stays. */
    var notebook by mutableStateOf(notebook)
    var page by mutableStateOf(page)
        private set
    val layer = InkLayer()
    val viewport = Viewport(minScale = if (infinite) 0.1f else 0.3f, bounds = if (infinite) null else PageGeometry.bounds(page.landscape))
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
    var recognizedText by mutableStateOf("")
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
    }

    internal fun opened() { openEditors[page.id] = this }
    internal fun closed() { if (openEditors[page.id] === this) openEditors.remove(page.id) }

    suspend fun load() {
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
        // decoding thousands of strokes is not main-thread work
        when (val r = ahead?.ink ?: withContext(Dispatchers.IO) { graph.repository.loadInkResult(page.id) }) {
            is InkLoad.Ok -> {
                ahead?.let { layer.adoptBounds(it.bounds) }
                layer.replaceAll(r.strokes, undoable = false)
                ahead?.let { layer.adoptConverted(it.converted) }
                // encode the stroke chunks now, off main, so the first save after an edit only encodes the new stroke
                val encode = layer.snapshotEncoder()
                graph.appScope.launch(Dispatchers.Default) {
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
                Log.e("Wanki", "page ${page.id}: ink cannot be read", r.error)
                readOnly = true
            }
        }
        recognizedText = graph.repository.recognizedText(page.id)
        savedRev = layer.strokesRev
        recognizedVersion = layer.version
        loaded = true
    }

    /**
     * While strokes move to another page ([moveSelectionToPage]) the canvas already lacks them but the target is not
     * written yet: no save may store the source without them until the move's transaction has. Main thread.
     */
    var holdSaves = false

    private suspend fun saveIfDirty() {
        if (!loaded || readOnly || holdSaves || layer.strokesRev == savedRev) return
        val rev = layer.strokesRev
        val encode = layer.snapshotEncoder() // the stroke list is taken here, the joining runs on IO
        withContext(Dispatchers.IO) { graph.repository.saveInkBlob(page, encode()) }
        savedRev = rev
    }

    suspend fun recognizeIfDirty() {
        if (!loaded || readOnly || layer.version == recognizedVersion) return
        val v = layer.version
        val strokes: List<InkStroke> = layer.strokes.toList()
        recognizing = true
        val text = graph.textRecognition.recognize(strokes, notebook.language)?.text
        recognizing = false
        if (text != null) {
            // the recognized text lives in the ink row: the ink must be stored first
            saver.request()
            saver.flush()
            graph.repository.setRecognizedText(page.id, text)
            recognizedText = text
            recognizedVersion = v
        }
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

    /** Puts the ink clipboard at the centre of the view, selected, with the lasso ready to move it. */
    fun paste() {
        val clip = InkClipboard.clip ?: return
        if (!loaded || readOnly) return
        val v = viewport.visibleWorld()
        layer.paste(clip, (v.left + v.right) / 2, (v.top + v.bottom) / 2)
        selectTool(tool.copy(kind = ToolKind.LASSO))
    }

    /** Strokes mostly past the edge of the (portrait) sheet, e.g. after the page was turned. */
    fun offPageCount(): Int = if (infinite) 0 else layer.outside(PageGeometry.bounds(page.landscape)).size

    /** Selects the strokes past the edge, with the lasso ready to move them back. */
    fun selectOffPage(): Int {
        val n = layer.selectOutside(PageGeometry.bounds(page.landscape))
        if (n > 0) selectTool(tool.copy(kind = ToolKind.LASSO))
        return n
    }

    /** Turns the page; the page re-fits so the whole sheet is in view. */
    suspend fun setLandscape(landscape: Boolean) {
        if (landscape == page.landscape) return
        graph.repository.setPageLandscape(page, landscape)
        page = page.copy(landscape = landscape)
        viewport.bounds = PageGeometry.bounds(landscape)
        viewport.fitWidth()
    }
}

/** Header actions shared by every page screen. «Бумага…» only makes sense for sheets, not for boards. */
fun PageEditorState.paperAction() = HeaderAction(R.drawable.ic_article, "Бумага…", "menu-template") { showPaper = true }
fun PageEditorState.fitAction() = HeaderAction(R.drawable.ic_fit_screen, "Вписать", "fit") { viewport.fitWidth() }
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

    LaunchedEffect(state) { ui { state.load() } }
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
            graph.appScope.launch {
                // a change from the last frame may not have reached the effect above yet
                val id = state.page.id
                state.saver.finalSave(context.applicationContext, "page $id")
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
            val paste = if (InkClipboard.clip != null && state.loaded && !state.readOnly) {
                listOf(HeaderAction(R.drawable.ic_content_paste, "Вставить", "menu-paste") { state.paste() })
            } else emptyList()
            ScreenHeader(title, onBack = back, subtitle = subtitle, subtitleMaxLines = 1, primary = primary, menu = paste + menu)
            InkToolbar(state.tool, state::selectTool, state.layer, state.eraser, state::selectEraser, Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            if (state.layer.hasSelection && !state.readOnly) {
                SelectionBar(state.layer, state.notebook.language, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), onMoveToPage = onMoveSelection)
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
                    modifier = Modifier.weight(1f).fillMaxSize().testTag("page-canvas"),
                    background = {
                        if (state.infinite) drawBoard(state.viewport.visibleWorld(), state.viewport.scale)
                        else drawPage(state.template, state.page.landscape)
                    },
                )
                if (state.showText && wide) {
                    Surface(tonalElevation = 1.dp, modifier = Modifier.width(280.dp).fillMaxSize()) {
                        RecognizedTextPanel(state, onTranslate = { translateAll = true })
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
                RecognizedTextPanel(state, onTranslate = { translateAll = true })
            }
        }
    }
    if (state.showPaper && !state.infinite) {
        // turning the page to portrait can leave writing past its edge: offer to select it
        var turned by remember { mutableStateOf(false) }
        val offPage = remember(turned, state.page.landscape, state.layer.strokesRev) { if (turned && !state.page.landscape) state.offPageCount() else 0 }
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
    if (translateAll) TextActionsDialog(initialText = state.recognizedText, onDismiss = { translateAll = false })
}

@Composable
private fun RecognizedTextPanel(state: PageEditorState, onTranslate: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Распознанный текст", style = MaterialTheme.typography.titleSmall)
        val status = state.modelStatus
        Text(
            when {
                state.recognizing -> "распознаю…"
                status == ModelStatus.DOWNLOADING -> "загрузка модели распознавания…"
                status == ModelStatus.UNAVAILABLE -> "распознавание недоступно (нет модели или сети)"
                else -> "обновляется автоматически после паузы"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(state.recognizedText.ifBlank { "—" }, modifier = Modifier.heightIn(min = 40.dp))
        }
        if (state.recognizedText.isNotBlank()) {
            Row {
                TextButton(onClick = { copyToClipboard(context, state.recognizedText) }) { Text("Копировать") }
                TextButton(onClick = onTranslate) { Text("Перевести") }
            }
        }
        Text(
            "Чтобы взять часть текста — обведите её инструментом «Лассо».",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
