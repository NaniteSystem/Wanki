package app.wanki.ui.texts

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedIconToggleButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.wanki.graph
import app.wanki.media.VoiceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Taps this soon after the bar switches between recording and idle are ignored (a double tap on ■ must not hit 🔁). */
private const val TAP_GUARD_MS = 350L

/**
 * 🎙 / ▶ / 🔁 for your own voice over [pieceIds] (the current piece, or the whole portion/block).
 * Key it on the ids: everything stops when the target changes, the screen is left or the app goes to the background.
 * Fits a 360 dp phone: the label hides when narrow, ▶ and 🔁 are 48 dp icon buttons.
 */
@Composable
fun VoiceBar(pieceIds: List<String>, snackbar: SnackbarHostState, scope: CoroutineScope) {
    val context = LocalContext.current
    val graph = context.graph
    val name = remember(pieceIds) { VoiceRecorder.fileName(pieceIds) }
    val recording by graph.recorder.active.collectAsState()
    val playing by graph.media.playing.collectAsState()
    val playingOnce = playing?.let { it.name == name && !it.loop } == true
    val looping = playing?.let { it.name == name && it.loop } == true
    var version by remember { mutableIntStateOf(0) }
    val hasFile = remember(name, version) { graph.media.file(name) != null }
    var changedAt by remember { mutableLongStateOf(0L) }
    val say: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }

    fun settled() = SystemClock.elapsedRealtime() - changedAt >= TAP_GUARD_MS
    fun changed() { changedAt = SystemClock.elapsedRealtime() }

    fun stopAll() {
        if (graph.recorder.recording) { graph.recorder.stop(); version++ }
        graph.media.stop()
    }

    fun startRecording() {
        graph.media.stop()
        graph.speaker.stop() // TTS must not end up in the recording
        runCatching { graph.recorder.start(name) }
            .onSuccess { changed() }
            .onFailure { say("Микрофон недоступен") }
    }

    fun stopRecording() {
        val kept = graph.recorder.stop()
        changed()
        version++
        if (!kept) say("Запись слишком короткая — не сохранена")
    }

    fun play(loop: Boolean) {
        graph.speaker.stop() // your own voice and TTS never talk over each other
        if (!graph.media.play(name, loop)) say("Не удалось воспроизвести запись")
    }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else say("Нужен доступ к микрофону")
    }

    DisposableEffect(name) { onDispose { stopAll() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { stopAll() }

    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recording) {
        elapsed = 0L
        val t0 = SystemClock.elapsedRealtime()
        while (recording) { delay(250); elapsed = SystemClock.elapsedRealtime() - t0 }
    }

    val container = if (recording) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer
    Surface(color = container, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        BoxWithConstraints {
            val narrow = maxWidth < 340.dp
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = if (narrow) 4.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (recording) {
                    val s = elapsed / 1000
                    Text(
                        "● Запись ${s / 60}:${(s % 60).toString().padStart(2, '0')}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 8.dp).testTag("voice-recording"),
                    )
                    Button(
                        onClick = { if (settled()) stopRecording() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                        modifier = Modifier.testTag("voice-stop"),
                    ) { Text("■ Стоп", maxLines = 1) }
                } else {
                    if (narrow) Spacer(Modifier.weight(1f))
                    else Text(
                        "Свой голос",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(
                        onClick = {
                            if (!settled()) return@FilledTonalButton
                            graph.media.stop()
                            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            if (granted) startRecording() else askMic.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        modifier = Modifier.testTag("voice-record"),
                    ) { Text(if (hasFile) "🎙 Заново" else "🎙 Записать", maxLines = 1) }
                    OutlinedIconButton(
                        onClick = { if (settled()) { if (playingOnce) graph.media.stop() else play(loop = false) } },
                        enabled = hasFile,
                        modifier = Modifier.testTag("voice-play").semantics { contentDescription = if (playingOnce) "Остановить" else "Прослушать запись" },
                    ) { Text(if (playingOnce) "■" else "▶") }
                    OutlinedIconToggleButton(
                        checked = looping,
                        onCheckedChange = { on -> if (settled()) { if (on) play(loop = true) else graph.media.stop() } },
                        enabled = hasFile,
                        modifier = Modifier.testTag("voice-loop").semantics { contentDescription = if (looping) "Остановить повтор" else "Повторять по кругу" },
                    ) { Text("🔁") }
                }
            }
        }
    }
}
