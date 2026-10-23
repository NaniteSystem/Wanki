package app.wanki.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
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
import app.wanki.data.Backup
import app.wanki.data.TRASH_DAYS
import app.wanki.graph
import app.wanki.input.StylusTracker
import app.wanki.reminders.Reminders
import app.wanki.ui.common.ScreenHeader
import app.wanki.ui.common.rememberUiScope
import app.wanki.ui.common.ui
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val backupDate = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.forLanguageTag("ru"))

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onOpenLab: () -> Unit, onOpenTrash: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val s = graph.settings
    val scope = rememberUiScope()
    var timePicking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf<Backup.Checked?>(null) }
    var autoList by remember { mutableStateOf<List<Backup.Info>?>(null) }

    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        s.reminderEnabled = granted
        Reminders.schedule(context)
        if (!granted) message = "Без разрешения на уведомления напоминание не появится."
    }
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            message = runCatching { graph.backup.exportTo(uri) }.fold({ "Резервная копия сохранена" }, { "Не удалось сохранить: ${it.message}" })
        }
    }
    fun check(open: () -> java.io.InputStream?) {
        scope.launch {
            runCatching { ui { graph.backup.check(open() ?: error("Не удалось открыть файл")) } }
                .onSuccess { restoring = it }
                .onFailure { message = "Не удалось восстановить: ${it.message}" }
        }
    }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) check { context.contentResolver.openInputStream(uri) }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Настройки")
        Column(
            Modifier.padding(16.dp).widthIn(max = 640.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Оформление", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("system" to "Как в системе", "light" to "Светлая", "dark" to "Тёмная").forEach { (v, label) ->
                    FilterChip(s.theme == v, { s.theme = v }, { Text(label) })
                }
            }
            HorizontalDivider()
            Text("Повторение", style = MaterialTheme.typography.titleMedium)
            Setting("Желаемое удержание (FSRS): %.0f%%".format(s.desiredRetention * 100), s.desiredRetention, 0.75f..0.97f) {
                s.desiredRetention = it
            }
            Toggle("Озвучивать ответ", "Ответ читается вслух голосом системы, когда показан", s.autoSpeak) { s.autoSpeak = it }
            HorizontalDivider()
            Text("Тексты", style = MaterialTheme.typography.titleMedium)
            Text(
                "Для расписания «сабак·сабки·манзиль»: сабки — недавно выученные куски, они повторяются каждый день, " +
                    "пока не пройдёт окно; манзиль — всё выученное раньше, по кругу.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IntSetting("Окно сабки, дней", s.sabqiDays, 3..30) { s.sabqiDays = it }
            IntSetting("Круг манзиля, дней", s.manzilCycleDays, 3..60) { s.manzilCycleDays = it }
            HorizontalDivider()
            Text("Проверка письма", style = MaterialTheme.typography.titleMedium)
            Setting("Порог «знак вспомнен»: %.0f%%".format(s.passThreshold), s.passThreshold, 40f..90f) { s.passThreshold = it }
            Setting(
                "Строгость порядка черт: " + when {
                    s.orderStrictness < 0.05f -> "не учитывать"
                    s.orderStrictness < 0.95f -> "%.0f%%".format(s.orderStrictness * 100)
                    else -> "полная"
                },
                s.orderStrictness, 0f..1f,
            ) { s.orderStrictness = it }
            Setting("Пауза перед автопроверкой: %.1f с".format(s.autoCheckDelayMs / 1000), s.autoCheckDelayMs, 600f..4000f) {
                s.autoCheckDelayMs = it
            }
            HorizontalDivider()
            Text("Напоминание", style = MaterialTheme.typography.titleMedium)
            Toggle(
                "Напоминать о повторении",
                "Каждый день в %02d:%02d, если есть карточки".format(s.reminderMinutes / 60, s.reminderMinutes % 60),
                s.reminderEnabled,
            ) { on ->
                if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Reminders.canNotify(context)) {
                    notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    s.reminderEnabled = on
                    Reminders.schedule(context)
                }
            }
            if (s.reminderEnabled) OutlinedButton(onClick = { timePicking = true }) { Text("Изменить время") }
            HorizontalDivider()
            Text("Корзина", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onOpenTrash, modifier = Modifier.testTag("settings-trash")) {
                Text("Корзина · удалённое хранится $TRASH_DAYS дней")
            }
            HorizontalDivider()
            Text("Резервная копия", style = MaterialTheme.typography.titleMedium)
            Text(
                "Копия содержит колоды, карточки, историю ответов, почерк, тетради, заметки, настройки и " +
                    "медиафайлы карточек. Раз в день приложение само делает копию без медиафайлов (хранятся последние ${Backup.KEEP}).",
                style = MaterialTheme.typography.bodySmall,
            )
            if (s.lastBackupAt > 0) {
                Text(
                    "Последняя копия: " + Instant.ofEpochMilli(s.lastBackupAt).atZone(ZoneId.systemDefault()).format(backupDate),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportBackup.launch("wanki-${LocalDate.now()}.zip") }, modifier = Modifier.testTag("backup-export")) {
                    Text("Сохранить копию…")
                }
                OutlinedButton(onClick = { importBackup.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                    Text("Восстановить из файла…")
                }
                OutlinedButton(onClick = { autoList = graph.backup.autoBackups() }) { Text("Автокопии") }
            }
            HorizontalDivider()
            Text("Ввод", style = MaterialTheme.typography.titleMedium)
            Text(
                "Стилус и пальцы определяются сами: перо рисует всегда, палец рисует, пока перо не использовалось " +
                    "последние несколько секунд, иначе двигает холст. Двумя пальцами — перемещение и масштаб. " +
                    "Обратный конец пера и кнопка на нём стирают.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(if (StylusTracker.stylusSeen) "Стилус обнаружен" else "Стилус пока не обнаружен", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onOpenLab) { Text("Лаборатория письма (подробный разбор оценки)") }
            HorizontalDivider()
            About()
        }
    }

    if (timePicking) ReminderTimeDialog(s.reminderMinutes, onDismiss = { timePicking = false }) { minutes ->
        timePicking = false
        s.reminderMinutes = minutes
        Reminders.schedule(context)
    }
    message?.let { m ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(m) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }
    autoList?.let { list ->
        AlertDialog(
            onDismissRequest = { autoList = null },
            title = { Text("Автокопии") },
            text = {
                if (list.isEmpty()) Text("Автокопий пока нет: первая появится в течение дня.")
                else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(list) { b ->
                        Text(
                            Instant.ofEpochMilli(b.at).atZone(ZoneId.systemDefault()).format(backupDate) + " · %.1f МБ".format(b.size / 1e6),
                            modifier = Modifier.fillMaxWidth().clickable {
                                autoList = null
                                check { b.file.inputStream() }
                            }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { autoList = null }) { Text("Закрыть") } },
        )
    }
    restoring?.let { r ->
        AlertDialog(
            onDismissRequest = { restoring = null },
            title = { Text("Восстановить копию?") },
            text = {
                Text(
                    "Копия от " + Instant.ofEpochMilli(r.createdAt).atZone(ZoneId.systemDefault()).format(backupDate) +
                        ". В ней карточек: ${r.cards}, текстов: ${r.texts}, тетрадей: ${r.notebooks}, досок: ${r.boards}, дней дневника: ${r.diaryDays}, страниц: ${r.pages}, заметок: ${r.notes}. Все текущие данные будут заменены, приложение перезапустится.",
                )
            },
            confirmButton = {
                TextButton(onClick = { graph.backup.restoreAndRestart(r) }) { Text("Восстановить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { restoring = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun About() {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    Text("О приложении", style = MaterialTheme.typography.titleMedium)
    Text("Wanki $version", style = MaterialTheme.typography.bodyMedium)
    Text(
        "Данные: эталоны черт — KanjiVG © Ulrich Apel (CC BY-SA 3.0); кандзи и японские слова — KANJIDIC2 и JMdict " +
            "© EDRDG (CC BY-SA 4.0); HSK — Complete HSK Vocabulary (MIT) со значениями CC-CEDICT (CC BY-SA 4.0); " +
            "корейские слова — 한국어기초사전 © 국립국어원 (CC BY-SA 2.0 KR). Изменённые данные распространяются " +
            "на тех же условиях.\n" +
            "Значки — Material Symbols © Google (Apache License 2.0).\n" +
            "Распознавание рукописи, определение языка и перевод — ML Kit (Google), работают на устройстве после загрузки моделей.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderTimeDialog(minutes: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Время напоминания") },
        text = { TimePicker(state) },
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun IntSetting(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column {
        Text("$label: $value", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(range)) },
            // rounded instead of `steps`: a tick per day is too dense for a 3–60 range
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

@Composable
private fun Setting(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
