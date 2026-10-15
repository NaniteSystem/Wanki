package app.wanki.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wanki.data.NoteEntity
import app.wanki.data.Repository
import app.wanki.graph
import app.wanki.ui.common.ScreenHeader
import app.wanki.ui.common.ui
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

private val dayFormat = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ru"))

@Composable
fun StatsScreen(onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val stats by produceState<Repository.Stats?>(null) { value = ui { graph.repository.stats() } }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Статистика", onBack = onBack)
        val s = stats ?: return@Column
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 900.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Tiles(s)
            Section("Активность", "Повторения по дням за последние 20 недель") { Heatmap(s) }
            Section("Прогноз", "Сколько карточек придёт на повторение в ближайшие 30 дней") { Forecast(s) }
            Section("Почерк", "Знаки с самым слабым почерком — их стоит потренировать") { Weakest(s) }
        }
    }
}

@Composable
private fun Section(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tiles(s: Repository.Stats) {
    val today = s.perDay[s.today]?.n ?: 0
    val last30 = (0 until 30).mapNotNull { s.perDay[s.today - it] }
    val total30 = last30.sumOf { it.n }
    val ok30 = last30.sumOf { it.ok }
    var streak = 0
    var d = if ((s.perDay[s.today]?.n ?: 0) > 0) s.today else s.today - 1
    while ((s.perDay[d]?.n ?: 0) > 0) { streak++; d-- }
    val cards = s.states.sumOf { it.n }
    val fresh = s.states.filter { it.isNew }.sumOf { it.n }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Tile("$today", "сегодня повторено")
        Tile(if (total30 > 0) "${ok30 * 100 / total30}%" else "—", "верных ответов за 30 дней")
        Tile("$streak", "дней подряд")
        Tile("$cards", "карточек, из них новых $fresh")
    }
}

@Composable
private fun Tile(value: String, label: String) {
    Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).widthIn(min = 120.dp)) {
            Text(value, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** GitHub-style calendar: one hue, light → dark by review count. Tap a day for its numbers. */
@Composable
private fun Heatmap(s: Repository.Stats) {
    val weeks = 20
    val color = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val todayDate = LocalDate.ofEpochDay(s.today)
    // last column ends with the current week; rows Monday..Sunday
    val lastMonday = s.today - (todayDate.dayOfWeek.value - 1)
    val firstDay = lastMonday - (weeks - 1) * 7
    val max = max(1, (firstDay..s.today).maxOf { s.perDay[it]?.n ?: 0 })
    var picked by remember { mutableStateOf<Long?>(null) }
    Canvas(
        Modifier.fillMaxWidth().height(140.dp).pointerInput(Unit) {
            detectTapGestures { o ->
                val cell = minOf(size.width / weeks.toFloat(), size.height / 7f)
                val w = (o.x / cell).toInt()
                val r = (o.y / cell).toInt()
                if (w in 0 until weeks && r in 0..6) picked = (firstDay + w * 7 + r).takeIf { it <= s.today }
            }
        },
    ) {
        val cell = minOf(size.width / weeks, size.height / 7f)
        val gap = 2.dp.toPx()
        for (w in 0 until weeks) for (r in 0..6) {
            val day = firstDay + w * 7 + r
            if (day > s.today) continue
            val n = s.perDay[day]?.n ?: 0
            val c = if (n == 0) empty else color.copy(alpha = 0.25f + 0.75f * n / max)
            drawRoundRect(c, Offset(w * cell + gap / 2, r * cell + gap / 2), Size(cell - gap, cell - gap), CornerRadius(3.dp.toPx()))
            if (day == picked) {
                drawRoundRect(color, Offset(w * cell, r * cell), Size(cell, cell), CornerRadius(3.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            }
        }
    }
    val p = picked
    Text(
        if (p == null) "Нажмите на день, чтобы увидеть подробности" else {
            val c = s.perDay[p]
            LocalDate.ofEpochDay(p).format(dayFormat) + ": " +
                if (c == null) "повторений нет" else "${c.n} повторений, верно ${c.ok * 100 / c.n}%"
        },
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Due cards per day, next 30 days (day 0 includes overdue). Tap a bar for its value. */
@Composable
private fun Forecast(s: Repository.Stats) {
    val color = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outlineVariant
    val max = max(1, s.forecast.maxOrNull() ?: 1)
    var picked by remember { mutableStateOf<Int?>(null) }
    Canvas(
        Modifier.fillMaxWidth().height(160.dp).pointerInput(Unit) {
            detectTapGestures { o -> picked = (o.x / (size.width / s.forecast.size.toFloat())).toInt().coerceIn(0, s.forecast.lastIndex) }
        },
    ) {
        val n = s.forecast.size
        val slot = size.width / n
        val gap = 2.dp.toPx()
        val base = size.height - 1.dp.toPx()
        drawLine(axis, Offset(0f, base), Offset(size.width, base), 1.dp.toPx())
        s.forecast.forEachIndexed { i, v ->
            if (v == 0) return@forEachIndexed
            val h = (base - 4.dp.toPx()) * v / max
            val c = if (picked == null || picked == i) color else color.copy(alpha = 0.45f)
            drawRoundRect(c, Offset(i * slot + gap / 2, base - h), Size(slot - gap, h), CornerRadius(4.dp.toPx()))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(0, 7, 14, 21, 29).forEach { d ->
            Text(if (d == 0) "сегодня" else "+$d д", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val p = picked
    Text(
        if (p == null) "Всего на 30 дней: ${s.forecast.sum()} · нажмите на столбец" else
            LocalDate.ofEpochDay(s.today + p).format(dayFormat) + ": ${s.forecast[p]} карточек" + if (p == 0) " (вместе с просроченными)" else "",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun Weakest(s: Repository.Stats) {
    if (s.weakest.isEmpty()) {
        Text("Пока нет данных: оценка почерка появляется после верных письменных ответов.", style = MaterialTheme.typography.bodySmall)
        return
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("Ответ", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text("Почерк", Modifier.weight(0.5f), style = MaterialTheme.typography.labelMedium)
            Text("Попыток", Modifier.weight(0.5f), style = MaterialTheme.typography.labelMedium)
        }
        HorizontalDivider()
        s.weakest.forEach { w ->
            val answer = w.fields.split(NoteEntity.FIELD_SEPARATOR).getOrElse(1) { "" }
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(answer, Modifier.weight(1f), fontSize = 20.sp)
                Text(w.beauty?.let { "%.0f".format(it) + if (it < 75) " ⚑" else "" } ?: "—", Modifier.weight(0.5f))
                Text("${w.n}", Modifier.weight(0.5f))
            }
        }
        Text("⚑ — ниже 75: в очередь тренировки почерка", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
