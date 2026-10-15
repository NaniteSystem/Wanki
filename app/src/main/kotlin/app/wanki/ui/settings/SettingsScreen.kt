package app.wanki.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.wanki.graph
import app.wanki.input.StylusTracker
import app.wanki.ui.common.ScreenHeader

@Composable
fun SettingsScreen(onOpenLab: () -> Unit) {
    val s = LocalContext.current.graph.settings
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Настройки")
        Column(
            Modifier.padding(16.dp).widthIn(max = 640.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
            Text("Интервалы (FSRS)", style = MaterialTheme.typography.titleMedium)
            Setting("Желаемое удержание: %.0f%%".format(s.desiredRetention * 100), s.desiredRetention, 0.75f..0.97f) {
                s.desiredRetention = it
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
            HorizontalDivider()
            OutlinedButton(onClick = onOpenLab) { Text("Лаборатория письма (подробный разбор оценки)") }
        }
    }
}

@Composable
private fun Setting(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
