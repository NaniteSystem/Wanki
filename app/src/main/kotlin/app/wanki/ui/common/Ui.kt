package app.wanki.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs suspend work that ends in UI state changes so that every resumption happens on the main
 * thread — even when the work hops to Room's or ML Kit's threads in between.
 */
suspend fun <T> ui(block: suspend CoroutineScope.() -> T): T = withContext(Dispatchers.Main.immediate, block)

/** A composition-bound scope whose coroutines always resume on the main thread. */
@Composable
fun rememberUiScope(): CoroutineScope = rememberCoroutineScope { Dispatchers.Main.immediate }
