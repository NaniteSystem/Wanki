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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.wanki.graph
import app.wanki.ui.common.ScreenHeader
import app.wanki.ui.notebooks.PageEditor
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
    var month by remember { mutableStateOf(YearMonth.now()) }
    val today = LocalDate.now()
    val written = dates.toSet()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Дневник", subtitle = "Одна страница на день") {
            Button(onClick = { onOpenDay(today.toEpochDay()) }) { Text("Сегодня") }
        }
        Column(Modifier.fillMaxWidth().padding(16.dp).widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp)) {
                            if (d != null) {
                                val date = month.atDay(d)
                                val isToday = date == today
                                Box(
                                    Modifier.fillMaxSize()
                                        .background(
                                            if (date.toEpochDay() in written) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                            RoundedCornerShape(8.dp),
                                        )
                                        .border(if (isToday) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                                        .clickable { onOpenDay(date.toEpochDay()) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("$d")
                                    if (date.toEpochDay() in written) {
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
fun DiaryDayScreen(epochDay: Long, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val date = LocalDate.ofEpochDay(epochDay)
    val loaded by produceState<Pair<app.wanki.data.NotebookEntity, app.wanki.data.PageEntity>?>(null, epochDay) {
        value = ui { graph.repository.diary() to graph.repository.diaryPage(date) }
    }
    val (diary, page) = loaded ?: return
    val state = remember(page.id) { PageEditorState(graph, diary, page, infinite = false) }
    PageEditor(state, Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹") }
            Text(
                "${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.FULL, ru)} ${date.year}, " +
                    date.dayOfWeek.getDisplayName(TextStyle.FULL, ru),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
