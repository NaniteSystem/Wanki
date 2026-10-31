package app.wanki.ui.notebooks

import android.content.ActivityNotFoundException
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.wanki.data.NotebookEntity
import app.wanki.data.PageEntity
import app.wanki.export.PdfExporter
import app.wanki.graph
import app.wanki.ui.common.rememberUiScope
import app.wanki.ui.common.ui
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * «Экспорт в PDF»: «Эта страница» or «Вся тетрадь» ([allPages] null: the page only, started at once), then
 * «Готовлю PDF…» while the file is written off main, then the share chooser. [beforeExport] stores unsaved ink first.
 * A failure stays in the dialog as «Не удалось сделать PDF».
 */
@Composable
fun ExportDialog(
    notebook: NotebookEntity,
    page: PageEntity,
    allPages: (suspend () -> List<PageEntity>)?,
    beforeExport: suspend () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberUiScope()
    val exporter = remember { PdfExporter(context.applicationContext, graph.repository, graph.media) }
    var job by remember { mutableStateOf<Job?>(null) }
    var failed by remember { mutableStateOf(false) }

    fun start(pages: suspend () -> List<PageEntity>) {
        failed = false
        // lazy: the slot is set before the work can end
        val j = scope.launch(start = CoroutineStart.LAZY) {
            try {
                beforeExport()
                val file = exporter.export(notebook, pages())
                ui {
                    try {
                        context.startActivity(exporter.share(file))
                    } catch (e: ActivityNotFoundException) {
                        Log.e("Wanki", "no app to share a PDF", e)
                        failed = true
                        return@ui
                    }
                    onDismiss()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // OutOfMemoryError included: a huge notebook must not take the app down
                Log.e("Wanki", "notebook ${notebook.id}: PDF not made", e)
                ui { failed = true }
            } finally {
                job = null // the scope runs on main
            }
        }
        job = j
        j.start()
    }

    // one page and nothing to choose: start right away (a retry stays possible after a failure)
    if (allPages == null) androidx.compose.runtime.LaunchedEffect(Unit) { start { listOf(page) } }
    val running = job != null
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("Экспорт в PDF") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    running -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("export-progress"),
                    ) {
                        CircularProgressIndicator(Modifier.size(32.dp))
                        Text("Готовлю PDF…")
                    }
                    else -> {
                        if (failed) Text(
                            "Не удалось сделать PDF",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 4.dp).testTag("export-error"),
                        )
                        TextButton(onClick = { start { listOf(page) } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("export-page")) {
                            Text("Эта страница", modifier = Modifier.fillMaxWidth())
                        }
                        if (allPages != null) TextButton(onClick = { start(allPages) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("export-all")) {
                            Text("Вся тетрадь", modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { job?.cancel() ?: onDismiss() }, modifier = Modifier.testTag("export-cancel")) {
                Text(if (running) "Отмена" else "Закрыть")
            }
        },
    )
}

/** A dialog with a spinner and [text] while work runs that cannot be interrupted (an import). */
@Composable
internal fun ProgressDialog(text: String, tag: String) {
    AlertDialog(
        onDismissRequest = {},
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.heightIn(min = 48.dp).testTag(tag),
            ) {
                CircularProgressIndicator(Modifier.size(32.dp))
                Text(text)
            }
        },
        confirmButton = {},
    )
}
