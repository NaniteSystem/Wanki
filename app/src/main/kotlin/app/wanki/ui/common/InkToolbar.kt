package app.wanki.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
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
import app.wanki.input.EraserMode
import app.wanki.input.EraserSettings
import app.wanki.input.EraserSize
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
    ToolKind.MASK -> R.drawable.ic_tape
}

private fun ToolKind.label() = when (this) {
    ToolKind.PEN -> "Перо"
    ToolKind.MARKER -> "Маркер"
    ToolKind.HIGHLIGHTER -> "Выделитель"
    ToolKind.ERASER -> "Ластик"
    ToolKind.LASSO -> "Лассо"
    ToolKind.MASK -> "Скотч"
}

private val ToolKind.hasColors get() = this == ToolKind.PEN || this == ToolKind.MARKER || this == ToolKind.HIGHLIGHTER
private val ToolKind.hasOptions get() = hasColors || this == ToolKind.ERASER

/**
 * One row of icon buttons: tools, undo, redo. Tapping the selected pen / marker / highlighter opens
 * a popup with colours and sizes; the selected tool shows its colour as a dot.
 */
@Composable
fun InkToolbar(
    tool: ToolSelection,
    onTool: (ToolSelection) -> Unit,
    layer: InkLayer,
    eraser: EraserSettings,
    onEraser: (EraserSettings) -> Unit,
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
                    ToolButton(kind, tool, onTool, eraser, onEraser)
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
private fun ToolButton(
    kind: ToolKind, tool: ToolSelection, onTool: (ToolSelection) -> Unit, eraser: EraserSettings, onEraser: (EraserSettings) -> Unit,
) {
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
            if (selected && kind.hasColors) {
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(7.dp)
                        .background(Color(tool.color), CircleShape)
                        .border(1.dp, Color(0x55000000), CircleShape),
                )
            }
        }
        if (open) {
            if (kind == ToolKind.ERASER) EraserOptions(eraser, onEraser) { open = false }
            else ToolOptions(tool, onTool) { open = false }
        }
    }
}

@Composable
private fun ToolOptions(tool: ToolSelection, onTool: (ToolSelection) -> Unit, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf(false) }
    DropdownMenu(expanded = !custom, onDismissRequest = onDismiss) {
        Column(Modifier.testTag("tool-options").padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ColorChoices(tool.color, onPick = { onTool(tool.copy(color = it)) }, onCustom = { custom = true })
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

private fun EraserMode.label() = if (this == EraserMode.WHOLE) "Целиком" else "Частично"

private fun EraserSize.label() = when (this) {
    EraserSize.S -> "Маленький"
    EraserSize.M -> "Средний"
    EraserSize.L -> "Большой"
}

/** Popup of the eraser: whole or partial, and three sizes. */
@Composable
private fun EraserOptions(eraser: EraserSettings, onEraser: (EraserSettings) -> Unit, onDismiss: () -> Unit) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.testTag("eraser-options").padding(horizontal = 8.dp)) {
            EraserMode.entries.forEach { m ->
                EraserChoice("eraser-${m.name}", m.label(), eraser.mode == m, null) { onEraser(eraser.copy(mode = m)) }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            EraserSize.entries.forEach { sz ->
                EraserChoice("eraser-size-${sz.name}", sz.label(), eraser.size == sz, sz.radiusDp) { onEraser(eraser.copy(size = sz)) }
            }
        }
    }
}

/** One radio row (at least 48 dp high); [radiusDp] draws the eraser circle of that size. */
@Composable
private fun EraserChoice(tag: String, label: String, selected: Boolean, radiusDp: Float?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp).weight(1f, fill = false))
        if (radiusDp != null) {
            Spacer(Modifier.width(12.dp))
            // the circle is drawn at half scale so the largest still fits next to the label
            Box(Modifier.size((radiusDp / 2).dp).border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
        }
    }
}

@Composable
private fun Swatch(color: Int, selected: Boolean, description: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).semantics { contentDescription = description }.clickable(onClick = onClick).testTag(tag),
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

/**
 * The colour row shared by the pen popup and the lasso's «Цвет…»: the palette (swatches tagged `color-<index>`) and
 * «Свой цвет» ([onCustom]).
 */
@Composable
fun ColorChoices(selected: Int?, onPick: (Int) -> Unit, onCustom: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ToolSelection.PALETTE.chunked(4).forEachIndexed { row, colors ->
            Row {
                colors.forEachIndexed { i, c ->
                    val name = COLOR_NAMES.getOrElse(row * 4 + i) { "" }
                    Swatch(c, selected = selected == c, description = "Цвет: $name", tag = "color-${row * 4 + i}") { onPick(c) }
                }
            }
        }
        TextButton(onClick = onCustom, modifier = Modifier.heightIn(min = 48.dp).testTag("color-custom")) { Text("Свой цвет") }
    }
}

/** Picks a colour for the lasso selection: the shared colour row, «Свой цвет» opens [CustomColorDialog]. */
@Composable
fun ColorPickDialog(onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf(false) }
    if (!custom) AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Цвет") },
        text = { ColorChoices(null, onPick = onPick, onCustom = { custom = true }) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
    else CustomColorDialog(ToolSelection.DEFAULT_INK, onPick = onPick, onDismiss = onDismiss)
}

/** Hue, saturation and brightness, so any colour can be picked, grey and white too (saturation 0). */
@Composable
fun CustomColorDialog(initial: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    val color = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Свой цвет") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Box(
                    Modifier.fillMaxWidth().size(40.dp).background(Color(color), MaterialTheme.shapes.small)
                        .border(1.dp, Color(0x33000000), MaterialTheme.shapes.small),
                )
                Text("Оттенок", style = MaterialTheme.typography.labelMedium)
                // at full saturation and brightness, so the rainbow stays readable for a grey or dark current colour
                val hueStops = remember { (0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) } }
                GradientSlider(hue, { hue = it }, 0f..360f, hueStops, "Оттенок", "custom-hue")
                Text("Насыщенность", style = MaterialTheme.typography.labelMedium)
                val satStops = listOf(0f, 1f).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, it, value.coerceAtLeast(0.5f)))) }
                GradientSlider(sat, { sat = it }, 0f..1f, satStops, "Насыщенность", "custom-saturation")
                Text("Яркость", style = MaterialTheme.typography.labelMedium)
                val valueStops = listOf(0f, 1f).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, it))) }
                GradientSlider(value, { value = it }, 0f..1f, valueStops, "Яркость", "custom-value")
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
    value: Float, onChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, stops: List<Color>, description: String, tag: String,
) {
    Slider(
        value, onChange, valueRange = range,
        modifier = Modifier.semantics { contentDescription = description }.testTag(tag),
        track = {
            Box(
                Modifier.fillMaxWidth().height(14.dp)
                    .background(Brush.horizontalGradient(stops), CircleShape)
                    .border(1.dp, Color(0x33000000), CircleShape),
            )
        },
    )
}
