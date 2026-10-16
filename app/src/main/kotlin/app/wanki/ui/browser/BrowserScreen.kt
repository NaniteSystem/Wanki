package app.wanki.ui.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.wanki.graph
import app.wanki.ui.common.ScreenHeader

/** Searches cards across all decks. */
@Composable
fun BrowserScreen(onBack: () -> Unit, onOpenCard: (String) -> Unit) {
    val graph = LocalContext.current.graph
    val tree by remember { graph.repository.observeDeckTree() }.collectAsState(null)
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Карточки", onBack = onBack)
        val t = tree ?: return@Column
        CardBrowser(deckIds = null, tree = t, onOpenCard = onOpenCard, modifier = Modifier.weight(1f))
    }
}
