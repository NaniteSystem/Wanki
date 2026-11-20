package app.wanki.ui.notebooks

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.wanki.data.PageLineEntity
import app.wanki.graph
import app.wanki.recognition.ModelStatus
import app.wanki.ui.common.LocalUndo
import app.wanki.ui.common.copyToClipboard
import app.wanki.ui.common.listAction

private val HighlightColor = Color(0x55FFD54F)

/** Translucent boxes over the lines a search matched; the canvas below keeps getting the touches. */
@Composable
internal fun HighlightBoxes(state: PageEditorState) {
    val boxes = state.highlight
    if (boxes.isEmpty()) return
    Canvas(Modifier.fillMaxSize().testTag("page-highlight")) {
        val vp = state.viewport
        val pad = 4f * vp.scale
        for (b in boxes) {
            val x = b.left * vp.scale + vp.offsetX - pad
            val y = b.top * vp.scale + vp.offsetY - pad
            drawRect(HighlightColor, Offset(x, y), Size(b.width * vp.scale + 2 * pad, b.height * vp.scale + 2 * pad))
        }
    }
}

/** A pulsing outline around the area a card was made from. */
@Composable
internal fun SourceAreaOutline(state: PageEditorState) {
    val r = state.area ?: return
    val color = MaterialTheme.colorScheme.primary
    val pulse by rememberInfiniteTransition(label = "source-area").animateFloat(
        0.3f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "alpha",
    )
    Canvas(Modifier.fillMaxSize().testTag("source-area")) {
        val vp = state.viewport
        val pad = 6f * vp.scale
        drawRect(
            color.copy(alpha = pulse), Offset(r.left * vp.scale + vp.offsetX - pad, r.top * vp.scale + vp.offsetY - pad),
            Size(r.width * vp.scale + 2 * pad, r.height * vp.scale + 2 * pad), style = Stroke(3.dp.toPx()),
        )
    }
}

@Composable
internal fun RecognizedLinesPanel(state: PageEditorState, onTranslate: () -> Unit) {
    val context = LocalContext.current
    val undo = LocalUndo.current
    val graph = context.graph
    var editing by remember { mutableStateOf<Long?>(null) }
    val canEdit = state.loaded && !state.readOnly
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
        if (state.lines.isEmpty()) {
            Text(state.recognizedText.ifBlank { "—" }, modifier = Modifier.heightIn(min = 40.dp))
        }
        for (line in state.lines) {
            key(line.lineKey) {
                LineRow(
                    line, editing == line.lineKey, canEdit,
                    onEdit = { editing = line.lineKey },
                    onDone = { text ->
                        editing = null
                        graph.appScope.listAction(undo, "Не удалось сохранить исправление") { state.correctLine(line.lineKey, text) }
                    },
                    onReset = {
                        graph.appScope.listAction(undo, "Не удалось сбросить исправление") { state.correctLine(line.lineKey, "") }
                    },
                )
            }
        }
        if (state.failedLines > 0) {
            Text(
                "Не удалось распознать строк: ${state.failedLines} — повторю позже",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("lines-failed"),
            )
        }
        if (state.recognizedText.isNotBlank()) {
            Row {
                TextButton(onClick = { copyToClipboard(context, state.recognizedText) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Копировать") }
                TextButton(onClick = onTranslate, modifier = Modifier.heightIn(min = 48.dp)) { Text("Перевести") }
            }
        }
        Text(
            "Чтобы взять часть текста — обведите её инструментом «Лассо».",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LineRow(
    line: PageLineEntity, editing: Boolean, canEdit: Boolean,
    onEdit: () -> Unit, onDone: (String) -> Unit, onReset: () -> Unit,
) {
    val i = line.lineIndex
    if (editing) {
        var text by remember { mutableStateOf(line.shown) }
        // field and button side by side, so the button stays in reach when the keyboard squeezes the sheet; the
        // keyboard's own «Готово» key finishes too
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                text, { text = it }, modifier = Modifier.weight(1f).testTag("line-edit-$i"),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone(text) }),
            )
            TextButton(onClick = { onDone(text) }, modifier = Modifier.heightIn(min = 48.dp).testTag("line-done-$i")) { Text("Готово") }
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (canEdit) Modifier.clickable(onClick = onEdit) else Modifier)
            .testTag("line-$i"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(line.shown.ifBlank { "—" })
            if (line.corrected != null) {
                Text("исправлено", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (line.corrected != null && canEdit) {
            TextButton(onClick = onReset, modifier = Modifier.heightIn(min = 48.dp).testTag("line-reset-$i")) { Text("Сбросить") }
        }
    }
}
