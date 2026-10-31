package app.wanki.ui.notebooks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.wanki.data.NotebookEntity
import app.wanki.data.PageSize
import app.wanki.data.PageTemplate
import app.wanki.ui.common.NotebookLanguages
import app.wanki.ui.common.label

/**
 * Notebook settings: title, recognition language, default paper, page size and orientation of new pages.
 * [initial] null = create mode. Scrolls, so it fits at 360 dp and large font scales.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotebookSettingsDialog(
    initial: NotebookEntity?,
    onDismiss: () -> Unit,
    onSave: (title: String, language: String, template: PageTemplate, pageSize: PageSize, landscape: Boolean) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf(initial?.title ?: "") }
    var lang by rememberSaveable { mutableStateOf(initial?.language ?: "auto") }
    var template by rememberSaveable {
        mutableStateOf(initial?.let { runCatching { PageTemplate.valueOf(it.template) }.getOrNull() } ?: PageTemplate.LINED)
    }
    var size by rememberSaveable {
        mutableStateOf(initial?.let { runCatching { PageSize.valueOf(it.pageSize) }.getOrNull() } ?: PageSize.A4)
    }
    var landscape by rememberSaveable { mutableStateOf(initial?.landscape ?: false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Новая тетрадь" else "Настройки тетради") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.testTag("settings-title"))
                Text("Бумага новых страниц", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PageTemplate.entries.forEach { t ->
                        FilterChip(template == t, { template = t }, { Text(t.label()) }, modifier = Modifier.testTag("settings-paper-${t.name}"))
                    }
                }
                Text("Размер страницы", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PageSize.entries.forEach { s ->
                        FilterChip(size == s, { size = s }, { Text(s.label()) }, modifier = Modifier.testTag("settings-size-${s.name}"))
                    }
                }
                Text("Ориентация новых страниц", style = MaterialTheme.typography.labelLarge)
                // an endless sheet is always portrait
                val turnable = size != PageSize.ENDLESS
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(!landscape || !turnable, { landscape = false }, { Text("Книжная") }, modifier = Modifier.testTag("settings-portrait"))
                    FilterChip(landscape && turnable, { landscape = true }, { Text("Альбомная") }, enabled = turnable, modifier = Modifier.testTag("settings-landscape"))
                }
                Text("Язык записей (для распознавания)", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NotebookLanguages.forEach { (code, name) ->
                        FilterChip(lang == code, { lang = code }, { Text(name) }, modifier = Modifier.testTag("settings-lang-$code"))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title.trim().ifBlank { initial?.title ?: "Тетрадь" }, lang, template, size, landscape && size != PageSize.ENDLESS) },
                modifier = Modifier.testTag("settings-save"),
            ) { Text(if (initial == null) "Создать" else "Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
