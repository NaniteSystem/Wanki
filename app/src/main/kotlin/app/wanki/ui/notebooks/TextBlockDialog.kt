package app.wanki.ui.notebooks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Text block sizes in world units: «Мелкий», «Обычный», «Крупный». */
val TextBlockSizes = listOf("Мелкий" to 24f, "Обычный" to 32f, "Крупный" to 48f)

/**
 * «Текстовый блок»: the text and its size. [initialSize] that is none of [TextBlockSizes] (a block scaled with the
 * lasso) stays as it is unless a size is picked. [onDone] gets the text (not blank) and the size.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TextBlockDialog(
    initialText: String = "",
    initialSize: Float = 32f,
    onDone: (String, Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initialText) }
    var size by rememberSaveable { mutableFloatStateOf(initialSize) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Текстовый блок") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    text, { text = it },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("text-block-field"),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextBlockSizes.forEach { (label, s) ->
                        FilterChip(
                            size == s, { size = s }, { Text(label) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("text-size-${s.toInt()}"),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(text, size) }, enabled = text.isNotBlank(), modifier = Modifier.testTag("text-block-done")) { Text("Готово") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
