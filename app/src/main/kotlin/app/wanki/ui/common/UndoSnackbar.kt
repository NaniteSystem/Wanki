package app.wanki.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.compositionLocalOf
import app.wanki.data.DayTakenException
import app.wanki.data.Repository
import app.wanki.data.TrashKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Deletes to the trash and offers «Отменить». It lives at shell level ([LocalUndo]) with the shell's
 * snackbar host, so the snackbar of a delete started on one screen survives leaving that screen.
 */
class Undo(
    private val repository: Repository,
    private val host: SnackbarHostState,
    private val scope: CoroutineScope,
) {
    fun deleted(kind: TrashKind, id: String) {
        scope.launch {
            try {
                repository.delete(kind, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("Не удалось удалить")
                return@launch
            }
            host.currentSnackbarData?.dismiss()
            val result = host.showSnackbar("Удалено", actionLabel = "Отменить", duration = SnackbarDuration.Long)
            if (result != SnackbarResult.ActionPerformed) return@launch
            try {
                repository.restore(kind, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DayTakenException) {
                say("Этот день уже записан")
            } catch (e: Exception) {
                say("Не удалось восстановить")
            }
        }
    }

    suspend fun say(message: String) {
        host.currentSnackbarData?.dismiss()
        host.showSnackbar(message)
    }
}

val LocalUndo = compositionLocalOf<Undo> { error("LocalUndo is provided by MainShell") }
