package app.wanki.writing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.wanki.input.InkSurface
import app.wanki.input.StylusTracker
import app.wanki.input.ToolSelection
import app.wanki.recognition.ModelStatus
import app.wanki.scoring.FailReason
import app.wanki.scoring.Rating
import app.wanki.scoring.RecognitionOutcome

@Composable
fun WritingTestScreen(vm: WritingTestViewModel = viewModel()) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp)) {
        // Tablet / landscape: prompt on the left, canvas on the right. Phone: prompt above the canvas.
        val wide = maxWidth >= 700.dp && maxWidth > maxHeight * 0.9f
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                PromptPane(vm, Modifier.weight(1f).fillMaxHeight())
                CanvasPane(vm, Modifier.weight(1.4f).fillMaxHeight())
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PromptPane(vm, Modifier.fillMaxWidth().weight(1f))
                CanvasPane(vm, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CanvasPane(vm: WritingTestViewModel, modifier: Modifier) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val inkColor = MaterialTheme.colorScheme.onSurface
    val brush = remember(canvasSize.width, inkColor) {
        ToolSelection(color = inkColor.toArgb(), size = (canvasSize.width / 36f).coerceAtLeast(6f))
    }
    val attempt = vm.attempt
    val ref = vm.reference

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f, fill = false).aspectRatio(1f), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
            ) {
                InkSurface(
                    layer = vm.layer,
                    tool = brush,
                    enabled = attempt == null && !vm.checking,
                    showInk = attempt == null,
                    modifier = Modifier.fillMaxSize().onSizeChanged { canvasSize = it },
                    background = {
                        drawTianzige()
                        if (vm.showGhost && attempt == null && ref != null) drawReference(ref)
                    },
                    overlay = {
                        if (attempt != null && ref != null) drawReview(ref, attempt.result, vm.layer.strokes)
                    },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = vm.layer::undo, enabled = attempt == null && !vm.layer.isEmpty) { Text("Отменить") }
            OutlinedButton(onClick = vm::reset) { Text("Очистить") }
            FilterChip(selected = vm.showGhost, onClick = vm::toggleGhost, label = { Text("Призрак") })
            if (attempt == null) {
                Button(
                    onClick = { vm.check(canvasSize.width.toFloat(), canvasSize.height.toFloat()) },
                    enabled = !vm.layer.isEmpty && !vm.checking,
                ) { Text(if (vm.checking) "Проверка…" else "Проверить") }
            } else {
                Button(onClick = vm::next) { Text("Дальше") }
            }
        }
    }
}

@Composable
private fun PromptPane(vm: WritingTestViewModel, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = vm::previous) { Text("‹") }
            Text(
                text = if (vm.hideTarget && vm.attempt == null) "？" else vm.char,
                fontSize = 72.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            TextButton(onClick = vm::next) { Text("›") }
            Spacer(Modifier.width(8.dp))
            Column {
                Text("${vm.index + 1} / ${vm.chars.size}", style = MaterialTheme.typography.labelMedium)
                vm.reference?.let { Text("черт: ${it.strokes.size}", style = MaterialTheme.typography.labelMedium) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = vm.hideTarget, onClick = { vm.hideTarget = !vm.hideTarget }, label = { Text("Скрыть знак") })
        }
        Text(
            if (StylusTracker.stylusSeen) "Стилус обнаружен: пальцы не рисуют, пока перо рядом" else "Рисуйте пальцем или стилусом — переключать ничего не нужно",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Распознавание: " + when (vm.recognizer.status) {
                ModelStatus.UNKNOWN -> "проверка модели…"
                ModelStatus.DOWNLOADING -> "загрузка модели…"
                ModelStatus.READY -> "готово"
                ModelStatus.UNAVAILABLE -> "недоступно (оценка только по геометрии)"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        vm.attempt?.let { ResultPanel(it) }
    }
}

@Composable
private fun ResultPanel(a: Attempt) {
    val r = a.result
    Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("%.0f%%".format(r.score), fontSize = 44.sp, fontWeight = FontWeight.Bold,
                    color = if (r.passed) ReviewColors.correct else ReviewColors.wrong)
                Spacer(Modifier.width(12.dp))
                Text(if (r.passed) "Засчитано" else "Неверно", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(12.dp))
                Text("→ ${a.rating.label()}", style = MaterialTheme.typography.titleMedium)
            }
            r.failReasons.sortedBy { it.ordinal }.forEach { reason ->
                Text("• " + reason.label(r.confusedWith, r.missingRefStrokes.size), color = ReviewColors.wrong)
            }
            if (r.rawScore != r.score) Text("без ограничений: %.0f%%".format(r.rawScore), style = MaterialTheme.typography.bodySmall)
            Metric("Форма", r.metrics.shape)
            Metric("Положение и размер", r.metrics.position)
            Metric("Количество черт", r.metrics.count)
            Metric("Порядок", r.metrics.order)
            Metric("Направление", r.metrics.direction)
            r.beauty?.let { b ->
                Spacer(Modifier.height(4.dp))
                Text("Почерк: %.0f".format(b.score) + if (r.needsHandwritingPractice) " — в очередь тренировки" else "",
                    style = MaterialTheme.typography.titleSmall)
                Metric("форма", b.shape)
                Metric("положение", b.position)
                Metric("пропорции", b.proportions)
                Metric("ровность", b.smoothness)
            }
            Text(
                "Время: %.1f с".format(a.durationMs / 1000.0) + (if (a.usedHint) " · с подсказкой" else "") +
                    " · ML Kit: " + when (r.recognition) {
                        RecognitionOutcome.NOT_CHECKED -> "—"
                        else -> a.candidates.orEmpty().take(5).joinToString(" ")
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Legend()
        }
    }
}

@Composable
private fun Metric(label: String, value: Double) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(150.dp))
        LinearProgressIndicator(progress = { value.toFloat() }, modifier = Modifier.weight(1f).height(6.dp))
        Text("%.0f".format(value * 100), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(40.dp).padding(start = 8.dp))
    }
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LegendItem(ReviewColors.correct, "верно")
        LegendItem(ReviewColors.orderOrDirection, "порядок/направление")
        LegendItem(ReviewColors.wrong, "лишняя/пропущена")
    }
}

@Composable
private fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(12.dp).height(12.dp).background(color, RoundedCornerShape(3.dp)))
        Text(" $text", style = MaterialTheme.typography.labelSmall)
    }
}

private fun Rating.label() = when (this) {
    Rating.AGAIN -> "Again"
    Rating.HARD -> "Hard"
    Rating.GOOD -> "Good"
    Rating.EASY -> "Easy"
}

private fun FailReason.label(confusedWith: String?, missing: Int) = when (this) {
    FailReason.MISSING_STROKES -> "пропущено черт: $missing"
    FailReason.EXTRA_STROKES -> "лишние черты"
    FailReason.NOT_RECOGNIZED -> "распознаватель не увидел этот знак"
    FailReason.CONFUSED_WITH_LOOKALIKE -> "похоже на «$confusedWith»"
    FailReason.LOW_SCORE -> "низкая оценка"
}
