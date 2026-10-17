package app.wanki.catalog

import androidx.compose.runtime.mutableStateMapOf
import app.wanki.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Installs ready-made decks in the app scope, so leaving the catalog screen does not stop it. */
class CatalogInstaller(private val catalog: Catalog, private val repository: Repository, private val scope: CoroutineScope) {
    sealed interface State {
        data class Running(val done: Int, val total: Int) : State
        data class Done(val added: Int, val updated: Int) : State
        data class Failed(val message: String) : State
    }

    /** By catalog deck id; observable from Compose. */
    val states = mutableStateMapOf<String, State>()

    fun install(deck: CatalogDeck) {
        if (states[deck.id] is State.Running) return
        states[deck.id] = State.Running(0, deck.count)
        scope.launch {
            states[deck.id] = runCatching {
                val rows = withContext(Dispatchers.IO) { catalog.rows(deck) }
                val r = repository.installCatalogDeck(deck, rows) { done -> states[deck.id] = State.Running(done, rows.size) }
                State.Done(r.added, r.updated)
            }.getOrElse { State.Failed(it.message ?: it.javaClass.simpleName) }
        }
    }
}
