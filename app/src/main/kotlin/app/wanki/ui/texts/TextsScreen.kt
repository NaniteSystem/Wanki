package app.wanki.ui.texts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.wanki.data.TextEntity
import app.wanki.data.TextToday
import app.wanki.data.TodayItem
import app.wanki.graph
import app.wanki.texts.TextSchedule
import app.wanki.ui.common.ScreenHeader
import app.wanki.ui.common.ui
import app.wanki.ui.decks.DueColor
import app.wanki.ui.decks.LearnColor
import app.wanki.ui.decks.NewColor

private data class Progress(val learned: Int, val total: Int)

@Composable
fun TextsScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onNew: () -> Unit) {
    val graph = LocalContext.current.graph
    // the plan depends on the day and the hifz settings, not only on the database: recompute on resume
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LifecycleResumeEffect(Unit) {
        now = System.currentTimeMillis()
        onPauseOrDispose { }
    }
    val texts by remember { graph.texts.observeTexts() }.collectAsState(null)
    val today by remember(now) { graph.texts.observeToday() }.collectAsState(null)
    val progress by produceState(emptyMap<String, Progress>(), texts, today) {
        value = ui {
            texts.orEmpty().associate { t ->
                val pieces = graph.texts.pieces(t.id)
                t.id to Progress(pieces.count { it.learnedAt != null }, pieces.size)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Тексты", onBack = onBack)
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val bottom = PaddingValues(bottom = 96.dp) // room for the FAB
                if (maxWidth >= 840.dp) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        LazyColumn(Modifier.weight(1f), contentPadding = bottom, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            todaySection(today, texts)
                        }
                        LazyColumn(Modifier.weight(1f), contentPadding = bottom, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            textsSection(texts, progress, onOpen, onNew)
                        }
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        contentPadding = bottom,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        todaySection(today, texts)
                        item { Spacer(Modifier.size(8.dp)) }
                        textsSection(texts, progress, onOpen, onNew)
                    }
                }
            }
        }
        ExtendedFloatingActionButton(onClick = onNew, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("new-text")) {
            Text("+ Текст")
        }
    }
}

private fun LazyListScope.todaySection(today: TextToday?, texts: List<TextEntity>?) {
    item { SectionTitle("Сегодня") }
    if (today == null || texts == null) return
    if (today.items.isEmpty()) {
        item {
            Text(
                if (texts.isEmpty()) "Добавьте текст — здесь появится план на день." else "На сегодня всё: новых порций и повторений нет.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    items(today.items) { TodayRow(it) }
}

private fun LazyListScope.textsSection(
    texts: List<TextEntity>?, progress: Map<String, Progress>, onOpen: (String) -> Unit, onNew: () -> Unit,
) {
    item { SectionTitle("Все тексты") }
    if (texts == null) return
    if (texts.isEmpty()) item { EmptyTexts(onNew) }
    items(texts, key = { it.id }) { t -> TextRow(t, progress[t.id]) { onOpen(t.id) } }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

/** "Новое: <title> · N"; not clickable yet (study sessions come later). */
@Composable
private fun TodayRow(item: TodayItem) {
    val (kind, color) = when (item) {
        is TodayItem.NewPortion -> (if (scheduleOf(item.text.schedule) == TextSchedule.HIFZ) "Сабак" else "Новое") to NewColor
        is TodayItem.Review -> "Повторить" to DueColor
        is TodayItem.Sabqi -> "Сабки" to LearnColor
        is TodayItem.Manzil -> "Манзиль" to DueColor
    }
    val title = when (item) {
        is TodayItem.NewPortion -> item.text.title
        is TodayItem.Review -> item.text.title
        is TodayItem.Sabqi -> item.text.title
        is TodayItem.Manzil -> null
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(12.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(kind) }
                // the title is isolated so an RTL title does not pull the count into its run
                append(if (title != null) ": \u2068$title\u2069 · ${item.pieceIds.size}" else " · ${item.pieceIds.size}")
            },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextRow(t: TextEntity, progress: Progress?, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${textLanguageName(t.language)} · ${kindName(t.kind)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (progress != null) {
                    Text("${progress.learned}/${progress.total}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp))
                }
            }
            LinearProgressIndicator(
                progress = { if (progress == null || progress.total == 0) 0f else progress.learned / progress.total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(scheduleName(t.schedule))
                if (!t.active) Badge("пауза", color = MaterialTheme.colorScheme.tertiaryContainer)
            }
        }
    }
}

@Composable
private fun EmptyTexts(onNew: () -> Unit) {
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Текстов пока нет", style = MaterialTheme.typography.titleMedium)
        Text(
            "Стихи, отрывки, аяты — любой текст наизусть. Он делится на куски (строки стиха или предложения), " +
                "куски учатся порциями и повторяются по расписанию: FSRS или сабак, сабки и манзиль.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onNew) { Text("Добавить текст") }
    }
}
