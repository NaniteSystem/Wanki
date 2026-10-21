package app.wanki.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The three states of a list: a spinner while it loads, an empty state (icon, title, hint, one
 * button) when there is nothing, an error with a retry button; otherwise [content].
 * The icon of the empty state is [HeaderAction.icon] of [emptyAction].
 */
@Composable
fun ListState(
    loading: Boolean,
    isEmpty: Boolean,
    emptyTitle: String,
    emptyHint: String,
    emptyAction: HeaderAction?,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.testTag("list-loading"))
        }
        error != null -> Centered(Modifier.testTag("list-error")) {
            Text(error, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (onRetry != null) OutlinedButton(onClick = onRetry) { Text("Повторить") }
        }
        isEmpty -> Centered(Modifier.testTag("list-empty")) {
            if (emptyAction != null && emptyAction.icon != 0) {
                Icon(
                    painterResource(emptyAction.icon), contentDescription = null,
                    modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(emptyTitle, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(
                emptyHint, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (emptyAction != null) {
                Button(onClick = emptyAction.onClick, enabled = emptyAction.enabled, modifier = emptyAction.tag?.let { Modifier.testTag(it) } ?: Modifier) {
                    Text(emptyAction.label)
                }
            }
        }
        else -> content()
    }
}

@Composable
private fun Centered(modifier: Modifier, body: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { body() }
    }
}
