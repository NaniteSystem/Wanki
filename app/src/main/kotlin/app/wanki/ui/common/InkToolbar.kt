package app.wanki.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.wanki.input.InkLayer
import app.wanki.input.ToolKind
import app.wanki.input.ToolSelection

/** Pen / marker / highlighter / eraser, colors, sizes, undo/redo. */
@Composable
fun InkToolbar(
    tool: ToolSelection,
    onTool: (ToolSelection) -> Unit,
    layer: InkLayer,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.large, modifier = modifier) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolKind.entries.forEach { kind ->
                FilterChip(
                    selected = tool.kind == kind,
                    onClick = { onTool(tool.copy(kind = kind)) },
                    label = {
                        Text(
                            when (kind) {
                                ToolKind.PEN -> "✎ Перо"
                                ToolKind.MARKER -> "Маркер"
                                ToolKind.HIGHLIGHTER -> "Выделитель"
                                ToolKind.ERASER -> "Ластик"
                            },
                        )
                    },
                )
            }
            VerticalDivider(Modifier.size(1.dp, 28.dp))
            ToolSelection.PALETTE.forEach { c ->
                val selected = tool.color == c && !tool.isEraser
                Box(
                    Modifier.size(28.dp).background(Color(c), CircleShape)
                        .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color(0x33000000), CircleShape)
                        .clickable { onTool(tool.copy(color = c, kind = if (tool.isEraser) ToolKind.PEN else tool.kind)) },
                )
            }
            VerticalDivider(Modifier.size(1.dp, 28.dp))
            ToolSelection.SIZES.forEach { s ->
                val selected = tool.size == s
                Box(
                    Modifier.size(32.dp).clickable { onTool(tool.copy(size = s)) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size((s * 2.4f).dp).background(
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            CircleShape,
                        ),
                    )
                }
            }
            VerticalDivider(Modifier.size(1.dp, 28.dp))
            TextButton(onClick = layer::undo, enabled = layer.canUndo) { Text("↶") }
            TextButton(onClick = layer::redo, enabled = layer.canRedo) { Text("↷") }
            trailing()
        }
    }
}
