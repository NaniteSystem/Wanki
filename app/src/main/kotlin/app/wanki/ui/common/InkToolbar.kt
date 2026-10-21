package app.wanki.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.wanki.R
import app.wanki.input.InkLayer
import app.wanki.input.ToolKind
import app.wanki.input.ToolSelection

private val COLOR_NAMES = listOf("чёрный", "серый", "синий", "красный", "зелёный", "жёлтый", "фиолетовый", "оранжевый")

private fun ToolKind.icon() = when (this) {
    ToolKind.PEN -> R.drawable.ic_stylus
    ToolKind.MARKER -> R.drawable.ic_ink_marker
    ToolKind.HIGHLIGHTER -> R.drawable.ic_ink_highlighter
    ToolKind.ERASER -> R.drawable.ic_ink_eraser
    ToolKind.LASSO -> R.drawable.ic_lasso_select
}

private fun ToolKind.label() = when (this) {
    ToolKind.PEN -> "Перо"
    ToolKind.MARKER -> "Маркер"
    ToolKind.HIGHLIGHTER -> "Выделитель"
    ToolKind.ERASER -> "Ластик"
    ToolKind.LASSO -> "Лассо"
}

private val ToolKind.hasOptions get() = this == ToolKind.PEN || this == ToolKind.MARKER || this == ToolKind.HIGHLIGHTER

/**
 * One row of icon buttons: tools, undo, redo. Tapping the selected pen / marker / highlighter opens
 * a popup with colours and sizes; the selected tool shows its colour as a dot.
 */
@Composable
fun InkToolbar(
    tool: ToolSelection,
    onTool: (ToolSelection) -> Unit,
    layer: InkLayer,
    modifier: Modifier = Modifier,
    tools: List<ToolKind> = ToolKind.entries,
) {
    Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.large, modifier = modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tools.forEach { kind ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    ToolButton(kind, tool, onTool)
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                IconButton(onClick = layer::undo, enabled = layer.canUndo, modifier = Modifier.testTag("undo")) {
                    Icon(painterResource(R.drawable.ic_undo), contentDescription = "Отменить")
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                IconButton(onClick = layer::redo, enabled = layer.canRedo, modifier = Modifier.testTag("redo")) {
                    Icon(painterResource(R.drawable.ic_redo), contentDescription = "Повторить")
                }
            }
        }
    }
}

@Composable
private fun ToolButton(kind: ToolKind, tool: ToolSelection, onTool: (ToolSelection) -> Unit) {
    val selected = tool.kind == kind
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { if (selected && kind.hasOptions) open = true else onTool(tool.copy(kind = kind)) },
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            ),
            modifier = Modifier.testTag("tool-${kind.name}"),
        ) {
            Icon(painterResource(kind.icon()), contentDescription = kind.label())
            if (selected && kind.hasOptions) {
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(7.dp)
                        .background(Color(tool.color), CircleShape)
                        .border(1.dp, Color(0x55000000), CircleShape),
                )
            }
        }
        if (open) ToolOptions(tool, onTool) { open = false }
    }
}

@Composable
private fun ToolOptions(tool: ToolSelection, onTool: (ToolSelection) -> Unit, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf(false) }
    DropdownMenu(expanded = !custom, onDismissRequest = onDismiss) {
        Column(Modifier.testTag("tool-options").padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ToolSelection.PALETTE.chunked(4).forEachIndexed { row, colors ->
                Row {
                    colors.forEachIndexed { i, c ->
                        val name = COLOR_NAMES.getOrElse(row * 4 + i) { "" }
                        Swatch(c, selected = tool.color == c, description = "Цвет: $name") { onTool(tool.copy(color = c)) }
                    }
                }
            }
            TextButton(onClick = { custom = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Свой цвет") }
            Row {
                ToolSelection.SIZES.forEachIndexed { i, s ->
                    val selected = tool.size == s
                    Box(
                        Modifier.size(48.dp).clip(CircleShape)
                            .semantics { contentDescription = "Толщина ${i + 1} из ${ToolSelection.SIZES.size}" }
                            .clickable { onTool(tool.copy(size = s)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size((s * 2f + 4f).dp).background(
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                CircleShape,
                            ),
                        )
                    }
                }
            }
        }
    }
    if (custom) CustomColorDialog(
        initial = tool.color,
        onPick = { onTool(tool.copy(color = it)); onDismiss() },
        onDismiss = { custom = false; onDismiss() },
    )
}

@Composable
private fun Swatch(color: Int, selected: Boolean, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).semantics { contentDescription = description }.clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(30.dp).background(Color(color), CircleShape)
                .border(
                    if (selected) 3.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else Color(0x33000000),
                    CircleShape,
                ),
        )
    }
}

@Composable
private fun CustomColorDialog(initial: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var value by remember { mutableFloatStateOf(hsv[2].coerceIn(0.2f, 1f)) }
    val sat = if (hsv[1] < 0.2f) 0.8f else hsv[1]
    val color = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Свой цвет") },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().size(40.dp).background(Color(color), MaterialTheme.shapes.small))
                Text("Оттенок", style = MaterialTheme.typography.labelMedium)
                // always at full brightness, so the rainbow stays readable for a dark current colour
                val hueStops = remember(sat) {
                    (0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, sat, 1f))) }
                }
                GradientSlider(hue, { hue = it }, 0f..360f, hueStops, "Оттенок")
                Text("Яркость", style = MaterialTheme.typography.labelMedium)
                val valueStops = listOf(0.2f, 1f).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, it))) }
                GradientSlider(value, { value = it }, 0.2f..1f, valueStops, "Яркость")
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color) }) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** A slider whose track shows the colours it selects. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun GradientSlider(
    value: Float, onChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, stops: List<Color>, description: String,
) {
    Slider(
        value, onChange, valueRange = range,
        modifier = Modifier.semantics { contentDescription = description },
        track = {
            Box(
                Modifier.fillMaxWidth().height(14.dp)
                    .background(Brush.horizontalGradient(stops), CircleShape)
                    .border(1.dp, Color(0x33000000), CircleShape),
            )
        },
    )
}
