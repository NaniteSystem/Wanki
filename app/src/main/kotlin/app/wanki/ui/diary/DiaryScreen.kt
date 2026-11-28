package app.wanki.ui.diary

import app.wanki.ui.common.ui
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.wanki.R
import app.wanki.data.TrashKind
import app.wanki.graph
import app.wanki.ui.common.HeaderAction
import app.wanki.ui.common.LocalUndo
import app.wanki.ui.common.ScreenHeader
import app.wanki.ui.common.rememberUiScope
import app.wanki.ui.texts.RecordKind
import app.wanki.ui.texts.RecordRef
import app.wanki.ui.texts.recordText
import app.wanki.ui.texts.recordTextProblem
import android.util.Log
import kotlinx.coroutines.launch
import app.wanki.ui.notebooks.PageEditor
import app.wanki.ui.notebooks.ExportDialog
import app.wanki.ui.notebooks.rememberPageUi
import app.wanki.ui.notebooks.fitAction
import app.wanki.ui.notebooks.paperAction
import app.wanki.ui.notebooks.textAction
import app.wanki.ui.notebooks.PageEditorState
import kotlinx.coroutines.flow.flowOf
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

private val ru = Locale.forLanguageTag("ru")

@Composable
fun DiaryScreen(onOpenDay: (Long) -> Unit) {
    val graph = LocalContext.current.graph
    val diary by produceState<app.wanki.data.NotebookEntity?>(null) { value = ui { graph.repository.diary() } }
    val dates by remember(diary?.id) {
        diary?.let { graph.repository.observeDiaryDates(it.id) } ?: flowOf(emptyList())
    }.collectAsState(emptyList())
    // the month shown survives process death (YearMonth is Serializable)
    var month by rememberSaveable { mutableStateOf(YearMonth.now()) }
    val today = LocalDate.now()
    val written = dates.toSet()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Дневник", subtitle = "Одна страница на день") {
            Button(onClick = { onOpenDay(today.toEpochDay()) }) { Text("Сегодня") }
        }
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { month = month.minusMonths(1) }) { Text("◀") }
                Text(
                    month.month.getDisplayName(TextStyle.FULL_STANDALONE, ru).replaceFirstChar { it.uppercase() } + " " + month.year,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { month = month.plusMonths(1) }) { Text("▶") }
            }
            Row {
                DayOfWeek.entries.forEach { d ->
                    Text(
                        d.getDisplayName(TextStyle.SHORT_STANDALONE, ru),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f).padding(4.dp),
                    )
                }
            }
            val first = month.atDay(1)
            val lead = first.dayOfWeek.value - 1
            val days = month.lengthOfMonth()
            val cells = (0 until ((lead + days + 6) / 7) * 7).map { i -> (i - lead + 1).takeIf { it in 1..days } }
            cells.chunked(7).forEach { week ->
                Row {
                    week.forEach { d ->
                        // the whole cell (at least 48 dp at 360 dp width) is the touch target; the 2 dp inset is only visual
                        Box(Modifier.weight(1f).aspectRatio(1f)) {
                            if (d != null) {
                                val date = month.atDay(d)
                                val isToday = date == today
                                val epoch = date.toEpochDay()
                                val hasEntry = epoch in written
                                val future = date.isAfter(today)
                                Box(
                                    Modifier.fillMaxSize()
                                        .testTag("diary-day-$epoch")
                                        .semantics { if (hasEntry) stateDescription = "есть запись" }
                                        .clickable(enabled = !future) { onOpenDay(epoch) }
                                        .padding(2.dp)
                                        .background(
                                            if (hasEntry) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                            RoundedCornerShape(8.dp),
                                        )
                                        .border(if (isToday) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("$d", color = if (future) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else Color.Unspecified)
                                    if (hasEntry) {
                                        Box(
                                            Modifier.align(Alignment.BottomCenter).padding(4.dp).size(5.dp)
                                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DiaryDayScreen(epochDay: Long, onBack: () -> Unit, onMemorize: (String) -> Unit = {}) {
    val graph = LocalContext.current.graph
    val date = LocalDate.ofEpochDay(epochDay)
    val loadedDiary by produceState<app.wanki.data.NotebookEntity?>(null) { value = ui { graph.repository.diary() } }
    // the day's page is followed, not loaded once: undoing a delete while the day is open again switches to the restored page
    var dayPage by remember(epochDay) { mutableStateOf<app.wanki.data.PageEntity?>(null) }
    LaunchedEffect(loadedDiary?.id, epochDay) {
        val d = loadedDiary ?: return@LaunchedEffect
        graph.repository.observeDiaryPage(d.id, date).collect { p ->
            when {
                p != null -> if (p.id != dayPage?.id) dayPage = p
                // the day gets its row when opened; a page that went away later was deleted from this screen
                dayPage == null -> dayPage = ui { graph.repository.diaryPage(date) }
            }
        }
    }
    val diary = loadedDiary ?: return
    val page = dayPage ?: return
    val pageUi = rememberPageUi()
    val undo = LocalUndo.current
    val state = remember(page.id) { PageEditorState(graph, diary, page, infinite = false, ui = pageUi) }
    var exporting by remember { mutableStateOf(false) }
    val scope = rememberUiScope()
    var memorizing by remember { mutableStateOf(false) }
    // «Учить наизусть»: the day's text (handwriting read now, so a line written a moment ago counts) goes to a new text
    fun memorize() {
        if (memorizing || !state.loaded) return
        memorizing = true
        scope.launch {
            try {
                // the editor reads the stored page: its ink and text blocks, then the recognised lines
                state.saver.request()
                state.saver.flush()
                if (state.saver.error.value != null) { undo.say("Запись не сохранилась — сначала сохраните её"); return@launch }
                runCatching { state.recognizeIfDirty() }.onFailure { Log.e("Wanki", "diary ${page.id}: recognition failed", it) }
                val r = recordText(graph.repository, RecordRef(RecordKind.DIARY, page.id), hasInk = !state.layer.isEmpty)
                val problem = recordTextProblem(r)
                if (problem != null) undo.say(problem) else onMemorize(page.id)
            } finally {
                memorizing = false
            }
        }
    }
    PageEditor(
        state,
        title = "${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.FULL, ru)} ${date.year}",
        onBack = onBack,
        modifier = Modifier.fillMaxSize(),
        subtitle = date.dayOfWeek.getDisplayName(TextStyle.FULL, ru).replaceFirstChar { it.uppercase() },
        menu = listOf(
            state.paperAction(), state.fitAction(), state.textAction(),
            HeaderAction(R.drawable.ic_article, "Учить наизусть", "menu-memorize", enabled = !memorizing, onClick = ::memorize),
            HeaderAction(R.drawable.ic_picture_as_pdf, "Экспорт в PDF", "menu-export-pdf") { exporting = true },
            HeaderAction(R.drawable.ic_delete, "Удалить запись", "menu-delete-day") {
                undo.deleted(TrashKind.DIARY_DAY, page.id)
                onBack()
            },
        ),
    )
    // a diary day is one page: nothing to choose
    if (exporting) ExportDialog(
        diary, page, allPages = null,
        beforeExport = { state.saver.request(); state.saver.flush() },
        onDismiss = { exporting = false },
    )
}
