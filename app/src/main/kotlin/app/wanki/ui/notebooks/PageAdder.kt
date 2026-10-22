package app.wanki.ui.notebooks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * «Добавить страницу»: remembers which page to show once the new one arrives through the pages
 * flow. A failed insert is kept in [error] (shown with a retry), never thrown.
 */
class PageAdder(private val scope: CoroutineScope) {
    /** Index of the page being added; the current page stays on screen until it arrives. */
    var pendingIndex by mutableStateOf<Int?>(null)
        private set

    private val _error = MutableStateFlow<Throwable?>(null)
    val error: StateFlow<Throwable?> = _error.asStateFlow()

    /** Inserts a page that will be at [target]; ignored while another one is on its way. */
    fun add(target: Int, insert: suspend () -> Unit) {
        if (pendingIndex != null) return
        // set before the insert: the flow may deliver the new page before the insert returns
        pendingIndex = target
        scope.launch {
            try {
                insert()
                _error.value = null
            } catch (e: CancellationException) {
                pendingIndex = null
                throw e
            } catch (e: Throwable) {
                pendingIndex = null
                _error.value = e
            }
        }
    }

    /** The index to show now that [pageCount] pages are known, or null while the new page has not arrived. */
    fun arrived(pageCount: Int): Int? {
        val p = pendingIndex ?: return null
        if (p >= pageCount) return null
        pendingIndex = null
        return p
    }

    fun dismissError() { _error.value = null }
}
