package app.wanki.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
    val host = remember { SnackbarHostState() }
    val error by saver.error.collectAsState()
    LaunchedEffect(error) {
        if (error == null) return@LaunchedEffect
        val result = host.showSnackbar("Не удалось сохранить", actionLabel = "Повторить", duration = SnackbarDuration.Indefinite)
        if (result == SnackbarResult.ActionPerformed) saver.request()
    }
    SnackbarHost(host, modifier)
}
