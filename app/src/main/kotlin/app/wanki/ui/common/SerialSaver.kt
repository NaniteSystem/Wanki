package app.wanki.ui.common

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Runs [save] one at a time. A request during a running save is remembered and runs once more
 * right after it (any number of such requests collapse into one run), so the last state is
 * always saved and two saves never overlap. A failed save is kept in [error] until a later save
 * succeeds; it does not stop later requests.
 */
class SerialSaver(private val scope: CoroutineScope, private val save: suspend () -> Unit) {
    private val lock = Any()
    private var job: Job? = null
    private var pending = false

    private val _error = MutableStateFlow<Throwable?>(null)
    val error: StateFlow<Throwable?> = _error.asStateFlow()

    /** Runs the save now, or once more right after the running one. */
    fun request() {
        val j = synchronized(lock) {
            if (job != null) {
                pending = true
                return
            }
            // lazy: on an immediate dispatcher the loop could otherwise finish before `job` is set
            scope.launch(start = CoroutineStart.LAZY) { loop() }.also { job = it }
        }
        // a loop cancelled with its scope never clears its own slot
        j.invokeOnCompletion { synchronized(lock) { if (job === j) { job = null; pending = false } } }
        j.start()
    }

    /** Waits until nothing is running or pending. */
    suspend fun flush() {
        while (true) {
            val j = synchronized(lock) { job } ?: return
            j.join()
        }
    }

    private suspend fun loop() {
        while (true) {
            try {
                save()
                _error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _error.value = e
            }
            synchronized(lock) {
                if (!pending) {
                    job = null
                    return
                }
                pending = false
            }
        }
    }
}

/**
 * Shows «Не удалось сохранить» while [saver] has a failed save; «Повторить» requests another
 * save. The snackbar goes away by itself once a later save succeeds.
 */
@Composable
fun SaveErrorSnackbar(saver: SerialSaver, modifier: Modifier = Modifier) {
    val error by saver.error.collectAsState()
    ErrorSnackbar(error, "Не удалось сохранить", onRetry = saver::request, modifier = modifier)
}

/**
 * Shows [message] while [error] is set, with «Повторить» ([onRetry]) and, when [onDismiss] is
 * given, a close button. It goes away by itself once [error] is cleared.
 */
@Composable
fun ErrorSnackbar(
    error: Throwable?,
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        if (error == null) return@LaunchedEffect
        val result = host.showSnackbar(
            message, actionLabel = "Повторить", withDismissAction = onDismiss != null, duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) onRetry() else onDismiss?.invoke()
    }
    SnackbarHost(host, modifier)
}

/**
 * Back for a screen saved by [saver]. While its last save has failed, leaving (system back or the
 * returned action) first asks: «Повторить» saves again and leaves once that works (the error stays
 * on screen otherwise); «Выйти без сохранения» leaves and drops the unsaved changes knowingly.
 */
@Composable
fun rememberGuardedBack(saver: SerialSaver, onBack: () -> Unit): () -> Unit {
    val error by saver.error.collectAsState()
    val latestBack by rememberUpdatedState(onBack)
    var asking by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf(false) }
    val scope = rememberUiScope()
    // a later save that succeeded makes the question moot
    LaunchedEffect(error) { if (error == null) asking = false }
    // a save still running may yet fail: wait for it before leaving, then ask if it did
    val guarded = remember(saver) {
        {
            if (!waiting) {
                waiting = true
                scope.launch {
                    try {
                        saver.flush()
                        if (saver.error.value != null) asking = true else latestBack()
                    } finally {
                        waiting = false
                    }
                }
            }
        }
    }
    BackHandler { guarded() }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Изменения не сохранены") },
            text = { Text("Последние изменения не удалось сохранить. Если выйти сейчас, они пропадут.") },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    scope.launch {
                        saver.request()
                        saver.flush()
                        if (saver.error.value == null) latestBack()
                    }
                }) { Text("Повторить") }
            },
            dismissButton = { TextButton(onClick = { asking = false; latestBack() }) { Text("Выйти без сохранения") } },
        )
    }
    return guarded
}

/**
 * The last save of a screen that is gone: saves once more (a retry when the previous one failed)
 * and, if that fails too, says so in a toast instead of only logging it.
 */
suspend fun SerialSaver.finalSave(context: Context, what: String) {
    request()
    flush()
    error.value?.let {
        Log.e("Wanki", "$what: last save failed", it)
        Toast.makeText(context, "Не удалось сохранить изменения", Toast.LENGTH_LONG).show()
    }
}
