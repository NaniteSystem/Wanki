package app.wanki.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.wanki.R
import app.wanki.data.DayTakenException
import app.wanki.data.TRASH_DAYS
import app.wanki.data.TrashItem
import app.wanki.data.TrashKind
import app.wanki.graph
import app.wanki.ui.common.ConfirmDialog
import app.wanki.ui.common.HeaderAction
import app.wanki.ui.common.LocalUndo
import app.wanki.ui.common.ScreenHeader
import app.wanki.ui.common.rememberUiScope
import app.wanki.ui.common.ui
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

private val dayFormat = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ru"))
private const val DAY_MS = 86_400_000L

/** Whole days left before [item] is removed for good; the last started day counts. */
internal fun daysLeft(item: TrashItem, now: Long): Int =
    ceil((item.deletedAt + TRASH_DAYS * DAY_MS - now) / DAY_MS.toDouble()).toInt().coerceAtLeast(0)

/** «Тетрадь · стр. 3» for a page, «Дневник · 21 окт.» for a day, the item's own title otherwise. */
internal fun trashLabel(item: TrashItem): String = when (item.kind) {
    TrashKind.PAGE -> "${item.parentTitle} · стр. ${item.title.substringAfterLast(' ')}"
    TrashKind.DIARY_DAY -> "${item.parentTitle} · ${LocalDate.parse(item.title).format(dayFormat)}"
    else -> item.title
}

private fun icon(kind: TrashKind) = when (kind) {
    TrashKind.NOTEBOOK -> R.drawable.ic_book_2
    TrashKind.BOARD -> R.drawable.ic_dashboard
    TrashKind.NOTE -> R.drawable.ic_sticky_note_2
    TrashKind.PAGE -> R.drawable.ic_article
    TrashKind.DIARY_DAY -> R.drawable.ic_calendar_month
}

@Composable
fun TrashScreen(onBack: () -> Unit) {
    val repo = LocalContext.current.graph.repository
    val undo = LocalUndo.current
    val scope = rememberUiScope()
    var items by remember { mutableStateOf<List<TrashItem>?>(null) }
    var purging by remember { mutableStateOf<TrashItem?>(null) }
    var emptying by remember { mutableStateOf(false) }

    suspend fun reload() { items = ui { repo.trash() } }
    LaunchedEffect(Unit) { reload() }

    /** Runs a trash action, reports a failure and shows the fresh list. */
    fun act(failure: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                ui { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DayTakenException) {
                launch { undo.say("Этот день уже записан") }
            } catch (e: Exception) {
                launch { undo.say(failure) }
            }
            reload()
        }
    }

    val now = System.currentTimeMillis()
    val cleanupError = LocalContext.current.graph.settings.lastCleanupError
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "Корзина", onBack = onBack,
            menu = listOf(
                HeaderAction(R.drawable.ic_delete, "Очистить корзину", "menu-empty-trash", enabled = !items.isNullOrEmpty()) { emptying = true },
            ),
        )
        if (cleanupError.isNotEmpty()) {
            Text(
                "Очистка не удалась: $cleanupError", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("trash-cleanup-error"),
            )
        }
        val list = items
        when {
            list == null -> Unit
            list.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Корзина пуста", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                Text(
                    "Удалённое хранится $TRASH_DAYS дней", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }
            else -> LazyColumn(
                Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        "Удалённое хранится $TRASH_DAYS дней, потом исчезает насовсем.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(list, key = { it.kind.name + it.id }) { item ->
                    Card(Modifier.fillMaxWidth().testTag("trash-row-${item.id}")) {
                        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(icon(item.kind)), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(trashLabel(item), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "осталось ${daysLeft(item, now)} дн.", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { act("Не удалось восстановить") { repo.restore(item.kind, item.id) } }, modifier = Modifier.testTag("trash-restore-${item.id}")) {
                                Icon(painterResource(R.drawable.ic_undo), contentDescription = "Восстановить")
                            }
                            IconButton(onClick = { purging = item }, modifier = Modifier.testTag("trash-purge-${item.id}")) {
                                Icon(painterResource(R.drawable.ic_delete), contentDescription = "Удалить навсегда")
                            }
                        }
                    }
                }
            }
        }
    }

    purging?.let { item ->
        ConfirmDialog(
            title = "Удалить навсегда?",
            text = "«${trashLabel(item)}» будет удалено без возможности восстановления.",
            confirm = "Удалить навсегда",
            onConfirm = { act("Не удалось удалить") { repo.purge(item.kind, item.id) } },
            onDismiss = { purging = null },
        )
    }
    if (emptying) {
        ConfirmDialog(
            title = "Очистить корзину?",
            text = "Всё, что в корзине, будет удалено без возможности восстановления.",
            confirm = "Очистить",
            onConfirm = { act("Не удалось очистить корзину") { items.orEmpty().forEach { repo.purge(it.kind, it.id) } } },
            onDismiss = { emptying = false },
        )
    }
}
