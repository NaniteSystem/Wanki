package app.wanki.ui.texts

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.wanki.graph
import app.wanki.media.VoiceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 🎙 / ▶ / 🔁 for your own voice over [pieceIds] (the current piece, or the whole portion/block).
 * Key it on the ids: everything stops when the target changes, the screen is left or the app goes to the background.
 */
@Composable
fun VoiceBar(pieceIds: List<String>, snackbar: SnackbarHostState, scope: CoroutineScope) {
    val context = LocalContext.current
    val graph = context.graph
    val name = remember(pieceIds) { VoiceRecorder.fileName(pieceIds) }
    var recording by remember { mutableStateOf(false) }
    var looping by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    val hasFile = remember(name, version) { graph.media.file(name) != null }
    val say: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }

    fun stopAll() {
        if (recording) { graph.recorder.stop(); recording = false; version++ }
        if (looping) looping = false
        graph.media.stop()
    }

    fun startRecording() {
        looping = false
        runCatching { graph.recorder.start(name) }
            .onSuccess { recording = true }
            .onFailure { say("Микрофон недоступен") }
    }

    fun stopRecording() {
        val kept = graph.recorder.stop()
        recording = false
        version++
        if (!kept) say("Запись слишком короткая — не сохранена")
    }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else say("Нужен доступ к микрофону")
    }

    DisposableEffect(name) { onDispose { stopAll() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { stopAll() }

    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recording) {
        elapsed = 0L
        val t0 = System.currentTimeMillis()
        while (recording) { delay(250); elapsed = System.currentTimeMillis() - t0 }
    }

    val container = if (recording) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer
    Surface(color = container, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (recording) {
                val s = elapsed / 1000
                Text(
                    "● Запись ${s / 60}:${(s % 60).toString().padStart(2, '0')}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.testTag("voice-recording"),
                )
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { stopRecording() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    modifier = Modifier.testTag("voice-stop"),
                ) { Text("■ Стоп") }
            } else {
                Text("Свой голос", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                FilledTonalButton(
                    onClick = {
                        graph.media.stop()
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        if (granted) startRecording() else askMic.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    modifier = Modifier.testTag("voice-record"),
                ) { Text(if (hasFile) "🎙 Заново" else "🎙 Записать") }
                OutlinedButton(
                    onClick = {
                        looping = false
                        if (!graph.media.play(name)) say("Не удалось воспроизвести запись")
                    },
                    enabled = hasFile,
                    modifier = Modifier.testTag("voice-play"),
                ) { Text("▶") }
                FilterChip(
                    selected = looping,
                    onClick = {
                        if (looping) { graph.media.stop(); looping = false }
                        else if (graph.media.play(name, loop = true)) looping = true
                        else say("Не удалось воспроизвести запись")
                    },
                    enabled = hasFile,
                    label = { Text(if (looping) "🔁 Стоп" else "🔁") },
                    modifier = Modifier.testTag("voice-loop"),
                )
            }
        }
    }
}
