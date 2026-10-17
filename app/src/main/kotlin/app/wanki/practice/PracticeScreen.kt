package app.wanki.practice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wanki.graph
import app.wanki.input.InkLayer
import app.wanki.input.InkSurface
import app.wanki.input.ToolSelection
import app.wanki.review.CellGrade
import app.wanki.scoring.BeautyResult
import app.wanki.ui.common.ScreenHeader
import app.wanki.ui.common.rememberUiScope
import app.wanki.ui.common.ui
import app.wanki.writing.Cell
import app.wanki.writing.HintColor
import app.wanki.writing.ReviewColors
import app.wanki.writing.StrokeOrderAnimation
import app.wanki.writing.WritingPaper
import app.wanki.writing.drawReference
import app.wanki.writing.drawReview
import app.wanki.writing.drawTianzige
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Rounds per character: the first traces over the reference, the others are from memory. */
private const val ROUNDS = 3

/**
 * Handwriting practice: the weak-character queue, or one character ([glyph]). Each character
 * is traced once and then written twice from memory; the scores from memory feed the queue.
 * Practice never touches card schedules.
 */
@Composable
fun PracticeScreen(glyph: String?, language: String, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberUiScope()
    val queue = remember { mutableStateListOf<CharQuality>() }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(glyph) {
        ui {
            val items = if (glyph != null) listOf(CharQuality(glyph, language, 0.0, 0, 0))
            else graph.repository.charQuality().filter { it.weak }
            queue.clear()
            queue.addAll(items.filter { graph.references.get(it.glyph, it.language) != null })
            loaded = true
        }
    }
    var index by remember { mutableIntStateOf(0) }
    var round by remember { mutableIntStateOf(0) }
    val layer = remember { InkLayer() }
    var grade by remember { mutableStateOf<CellGrade?>(null) }
    var checking by remember { mutableStateOf(false) }
    var strokeActive by remember { mutableStateOf(false) }
    val best = remember { mutableStateListOf<Pair<String, Double?>>() }
    val current = queue.getOrNull(index)

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "Тренировка почерка", onBack = onBack,
            subtitle = current?.let { "знак ${index + 1} из ${queue.size} · попытка ${round + 1} из $ROUNDS" },
        )
        when {
            !loaded -> Unit
            queue.isEmpty() -> Message(
                if (glyph != null) "Для «$glyph» нет эталона черт." else "Тренировать пока нечего: почерк всех знаков в норме (75 и выше).",
                onBack,
            )
            current == null -> Summary(best, onBack)
            else -> {
                val ref = graph.references.get(current.glyph, current.language) ?: return@Column
                val density = LocalDensity.current
                val tool = remember { ToolSelection() }
                fun next() {
                    layer.clear()
                    grade = null
                    if (round + 1 < ROUNDS) round++ else {
                        round = 0
                        index++
                    }
                }
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        StrokeOrderAnimation(ref, Modifier.size(96.dp))
                        Column {
                            Text(current.glyph, fontSize = 40.sp)
                            if (current.attempts > 0) {
                                Text("Почерк в среднем: %.0f".format(current.average), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Text(
                        if (round == 0) "Обведите по образцу" else "Напишите по памяти",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (round == 0) HintColor else MaterialTheme.colorScheme.onSurface,
                    )
                    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val sideDp = minOf(maxWidth, 340.dp)
                        val side = with(density) { sideDp.toPx() }
                        val brush = remember(side) { tool.copy(size = side / 30f) }
                        // check once the pen has rested
                        LaunchedEffect(layer.version, strokeActive, grade) {
                            if (grade != null || strokeActive || layer.isEmpty) return@LaunchedEffect
                            delay(graph.settings.autoCheckDelayMs.toLong() + 500)
                            ui {
                                checking = true
                                val g = graph.grader.gradeCells(listOf(current.glyph), listOf(layer.strokes.toList()), side, current.language)
                                    .cells.first()
                                grade = g
                                checking = false
                                if (round > 0) graph.repository.savePractice(current.glyph, current.language, g.score, g.result?.beauty?.score)
                                if (round > 0 && (best.none { it.first == current.glyph } || (g.result?.beauty?.score ?: -1.0) > (best.first { it.first == current.glyph }.second ?: -1.0))) {
                                    best.removeAll { it.first == current.glyph }
                                    best.add(current.glyph to g.result?.beauty?.score)
                                }
                            }
                        }
                        Surface(shape = RoundedCornerShape(12.dp), color = WritingPaper, modifier = Modifier.size(sideDp).testTag("practice-canvas")) {
                            InkSurface(
                                layer = layer,
                                tool = brush,
                                enabled = grade == null && !checking,
                                showInk = grade?.result == null,
                                onStrokeStarted = { strokeActive = true },
                                onStrokeFinished = { strokeActive = false },
                                modifier = Modifier.fillMaxSize(),
                                background = {
                                    val cell = Cell(0f, 0f, side)
                                    drawTianzige(cell)
                                    if (round == 0 && grade == null) drawReference(ref, cell)
                                },
                                overlay = {
                                    val r = grade?.result
                                    if (r != null) drawReview(ref, r, layer.strokes.toList(), Cell(0f, 0f, side))
                                },
                            )
                        }
                    }
                    val g = grade
                    if (g == null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { layer.clear() }, enabled = !layer.isEmpty) { Text("Стереть") }
                            TextButton(onClick = ::next) { Text("Пропустить") }
                        }
                    } else {
                        val beauty = g.result?.beauty?.score
                        Text(
                            when {
                                g.passed != true -> "Не засчитано" + (g.score?.let { " · %.0f%%".format(it) } ?: "")
                                beauty != null -> "Почерк: %.0f".format(beauty) + if (beauty >= WeakChars.THRESHOLD) " — хорошо" else ""
                                else -> "Засчитано"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = if (g.passed == true) ReviewColors.correct else ReviewColors.wrong,
                        )
                        g.result?.beauty?.let { b ->
                            val tips = b.tips()
                            if (tips.isNotEmpty()) Text("Подтянуть: " + tips.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { layer.clear(); grade = null }) { Text("Ещё раз") }
                            Button(onClick = { scope.launch { next() } }) { Text("Дальше") }
                        }
                    }
                }
            }
        }
    }
}

/** The weakest parts of the handwriting score, worst first. */
private fun BeautyResult.tips(): List<String> =
    listOf(shape to "форма черт", position to "положение и размер", proportions to "пропорции", smoothness to "ровность линий")
        .filter { it.first < WeakChars.THRESHOLD }
        .sortedBy { it.first }
        .map { (v, name) -> "$name %.0f".format(v) }

@Composable
private fun Message(text: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("Назад") }
    }
}

@Composable
private fun Summary(best: List<Pair<String, Double?>>, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Тренировка закончена", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        best.forEach { (g, b) -> Text("$g — " + (b?.let { "почерк %.0f".format(it) } ?: "не засчитано"), style = MaterialTheme.typography.bodyLarge) }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("Готово") }
    }
}
