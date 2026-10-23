package app.wanki.ui.common

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource

/**
 * The long-press menu of a list card. Put it in the same Box as the card so it opens at the card;
 * each item gets the test tag `item-<tag>`.
 */
@Composable
fun ItemMenu(expanded: Boolean, onDismiss: () -> Unit, items: List<HeaderAction>) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        items.forEach { a ->
            DropdownMenuItem(
                text = { Text(a.label) },
                enabled = a.enabled,
                leadingIcon = if (a.icon != 0) ({ Icon(painterResource(a.icon), contentDescription = null) }) else null,
                onClick = { onDismiss(); a.onClick() },
                modifier = a.tag?.let { Modifier.testTag("item-$it") } ?: Modifier,
            )
        }
    }
}
