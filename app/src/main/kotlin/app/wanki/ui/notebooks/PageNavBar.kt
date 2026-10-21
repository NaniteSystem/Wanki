package app.wanki.ui.notebooks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.wanki.R

/** «‹  3 / 12  ›» under the canvas; tapping the number asks for a page to jump to. [index] is 0-based. */
@Composable
fun PageNavBar(index: Int, count: Int, onIndex: (Int) -> Unit, modifier: Modifier = Modifier) {
    var jump by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onIndex(index - 1) }, enabled = index > 0, modifier = Modifier.testTag("page-prev")) {
            Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = "Предыдущая страница")
        }
        TextButton(
            onClick = { jump = true },
            modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 72.dp).testTag("page-jump"),
        ) { Text("${index + 1} / $count", maxLines = 1, softWrap = false) }
        IconButton(onClick = { onIndex(index + 1) }, enabled = index < count - 1, modifier = Modifier.testTag("page-next")) {
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = "Следующая страница")
        }
    }
    if (jump) {
        var text by remember { mutableStateOf("") }
        val target = text.toIntOrNull()?.takeIf { it in 1..count }
        AlertDialog(
            onDismissRequest = { jump = false },
            title = { Text("Перейти на страницу") },
            text = {
                OutlinedTextField(
                    text, { text = it.filter(Char::isDigit).take(5) }, singleLine = true,
                    label = { Text("Номер, 1–$count") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.testTag("page-jump-field"),
                )
            },
            confirmButton = {
                TextButton(onClick = { jump = false; target?.let { onIndex(it - 1) } }, enabled = target != null) { Text("Перейти") }
            },
            dismissButton = { TextButton(onClick = { jump = false }) { Text("Отмена") } },
        )
    }
}
