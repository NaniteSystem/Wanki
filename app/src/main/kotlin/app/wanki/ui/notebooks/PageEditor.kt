package app.wanki.ui.notebooks

import android.util.Log
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
import app.wanki.ink.InkStroke
import app.wanki.input.InkLayer
import app.wanki.input.InkSurface
import app.wanki.input.InkSection
import app.wanki.input.ToolSelection
import app.wanki.input.Viewport
import app.wanki.recognition.ModelStatus
import app.wanki.ui.common.InkToolbar
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
import kotlinx.coroutines.delay
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

@Composable
fun rememberPageUi(): PageUiState = androidx.compose.runtime.saveable.rememberSaveable(saver = PageUiState.Saver) { PageUiState() }

/**
 * Loads, saves and recognizes one page. Ink is saved after every finished gesture (one
 * [InkLayer.version] step); zoom and pan are remembered per page. Lives as long as the page is on screen.
 */
class PageEditorState(
    private val graph: AppGraph,
    val notebook: NotebookEntity,
    page: PageEntity,
    val infinite: Boolean,
    val ui: PageUiState = PageUiState(),
) {
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
    private var savedVersion = -1
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

    suspend fun load() {
        val strokes = graph.repository.loadInk(page.id)
        layer.replaceAll(strokes, undoable = false)
        recognizedText = graph.repository.recognizedText(page.id)
        savedVersion = layer.version
        recognizedVersion = layer.version
        loaded = true
    }

    private suspend fun saveIfDirty() {
        if (!loaded || layer.version == savedVersion) return
        val v = layer.version
        graph.repository.saveInk(page, layer.strokes.toList())
        savedVersion = v
    }

    suspend fun recognizeIfDirty() {
        if (!loaded || layer.version == recognizedVersion) return
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

    /** Stores the current zoom and pan if they changed (not an edit: the page's date stays). */
    suspend fun saveView() {
        if (!loaded || !viewport.hasLayout) return
        val v = Triple(viewport.scale, viewport.offsetX, viewport.offsetY)
        if (v == savedView) return
        graph.repository.setPageView(page.id, v.first, v.second, v.third)
        savedView = v
    }

    suspend fun setTemplate(t: PageTemplate) {
        graph.repository.setPageTemplate(page, t)
        page = page.copy(template = t.name)
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
) {
    val context = LocalContext.current
    val graph = context.graph
    var translateAll by remember { mutableStateOf(false) }

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
        onDispose {
            graph.appScope.launch {
                // a change from the last frame may not have reached the effect above yet
                val id = state.page.id
                state.saver.finalSave(context.applicationContext, "page $id")
                runCatching { state.saveView() }.onFailure { Log.e("Wanki", "page $id: view not saved", it) }
                runCatching { state.recognizeIfDirty() }.onFailure { Log.e("Wanki", "page $id: recognition failed", it) }
            }
        }
    }

    BoxWithConstraints(modifier) {
        val wide = maxWidth >= 600.dp
        val sheetMax = maxHeight / 2
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title, onBack = back, subtitle = subtitle, subtitleMaxLines = 1, primary = primary, menu = menu)
            InkToolbar(state.tool, state::selectTool, state.layer, Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            if (state.layer.hasSelection) {
                SelectionBar(state.layer, state.notebook.language, Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                InkSurface(
                    layer = state.layer,
                    tool = state.tool,
                    viewport = state.viewport,
                    enabled = state.loaded,
                    gestures = true,
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
        AlertDialog(
            onDismissRequest = { state.showPaper = false },
            title = { Text("Бумага") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    FilterChip(
                        state.page.landscape,
                        { graph.appScope.launch { state.setLandscape(!state.page.landscape) } },
                        { Text("Альбомная страница") },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("paper-landscape"),
                    )
                    PageTemplate.entries.forEach { t ->
                        TextButton(
                            onClick = {
                                state.showPaper = false
                                graph.appScope.launch { state.setTemplate(t) }
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
