package app.wanki.ui.notebooks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.wanki.AppGraph
import app.wanki.data.NotebookEntity
import app.wanki.data.PageEntity
import app.wanki.data.PageTemplate
import app.wanki.graph
import app.wanki.ink.InkStroke
import app.wanki.input.InkLayer
import app.wanki.input.InkSurface
import app.wanki.input.ToolSelection
import app.wanki.input.Viewport
import app.wanki.recognition.ModelStatus
import app.wanki.recognition.PageRecognizer
import app.wanki.ui.common.InkToolbar
import app.wanki.ui.common.PageGeometry
import app.wanki.ui.common.drawBoard
import app.wanki.ui.common.drawPage
import app.wanki.ui.common.label
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Loads, autosaves and recognizes one page. Lives as long as the page is on screen. */
class PageEditorState(
    private val graph: AppGraph,
    val notebook: NotebookEntity,
    page: PageEntity,
    val infinite: Boolean,
) {
    var page by mutableStateOf(page)
        private set
    val layer = InkLayer()
    val viewport = Viewport(minScale = if (infinite) 0.1f else 0.3f, bounds = if (infinite) null else PageGeometry.BOUNDS)
    var tool by mutableStateOf(ToolSelection())
    var loaded by mutableStateOf(false)
        private set
    var recognizedText by mutableStateOf("")
        private set
    var recognizing by mutableStateOf(false)
        private set
    private var savedVersion = -1
    private var recognizedVersion = -1

    val template: PageTemplate get() = runCatching { PageTemplate.valueOf(page.template) }.getOrDefault(PageTemplate.BLANK)
    val recognizer get() = graph.recognizers.get(notebook.language)

    suspend fun load() {
        val strokes = graph.repository.loadInk(page.id)
        layer.replaceAll(strokes, undoable = false)
        recognizedText = graph.repository.recognizedText(page.id)
        savedVersion = layer.version
        recognizedVersion = layer.version
        loaded = true
    }

    suspend fun saveIfDirty() {
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
        val width = if (infinite) (strokes.mapNotNull { it.bounds() }.let { b -> if (b.isEmpty()) 1000f else b.maxOf { it.right } - b.minOf { it.left } }) else PageGeometry.WIDTH
        val text = PageRecognizer(recognizer).recognize(strokes, width)
        recognizing = false
        if (text != null) {
            saveIfDirty()
            graph.repository.setRecognizedText(page.id, text)
            recognizedText = text
            recognizedVersion = v
        }
    }

    suspend fun setTemplate(t: PageTemplate) {
        graph.repository.setPageTemplate(page, t)
        page = page.copy(template = t.name)
    }
}

/**
 * Full-screen page: toolbar, the zoomable canvas and (optionally) the recognized text.
 * Pinch with two fingers to zoom; with a pen nearby one finger pans.
 */
@Composable
fun PageEditor(
    state: PageEditorState,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
) {
    val graph = LocalContext.current.graph
    var showText by remember { mutableStateOf(false) }
    var templateMenu by remember { mutableStateOf(false) }

    LaunchedEffect(state) { state.load() }
    // autosave shortly after the last change, recognize after a longer pause
    LaunchedEffect(state.layer.version, state.loaded) {
        if (!state.loaded) return@LaunchedEffect
        delay(800)
        state.saveIfDirty()
        delay(2500)
        state.recognizeIfDirty()
    }
    DisposableEffect(state) {
        onDispose { graph.appScope.launch { state.saveIfDirty(); state.recognizeIfDirty() } }
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { header() }
            if (!state.infinite) {
                Box {
                    TextButton(onClick = { templateMenu = true }) { Text(state.template.label()) }
                    DropdownMenu(expanded = templateMenu, onDismissRequest = { templateMenu = false }) {
                        PageTemplate.entries.forEach { t ->
                            DropdownMenuItem(text = { Text(t.label()) }, onClick = {
                                templateMenu = false
                                graph.appScope.launch { state.setTemplate(t) }
                            })
                        }
                    }
                }
            }
            TextButton(onClick = { state.viewport.fitWidth() }) { Text("Вписать") }
            TextButton(onClick = { showText = !showText }) { Text(if (showText) "Скрыть текст" else "Текст") }
        }
        InkToolbar(state.tool, { state.tool = it }, state.layer, Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            InkSurface(
                layer = state.layer,
                tool = state.tool,
                viewport = state.viewport,
                enabled = state.loaded,
                modifier = Modifier.weight(1f).fillMaxSize().testTag("page-canvas"),
                background = {
                    if (state.infinite) drawBoard(state.viewport.visibleWorld(), state.viewport.scale)
                    else drawPage(state.template)
                },
            )
            if (showText) {
                Surface(tonalElevation = 1.dp, modifier = Modifier.width(280.dp).fillMaxSize()) {
                    Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Распознанный текст", style = MaterialTheme.typography.titleSmall)
                        val status = state.recognizer.status
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
                    }
                }
            }
        }
    }
}
