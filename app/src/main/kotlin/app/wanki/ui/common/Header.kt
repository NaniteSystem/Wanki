package app.wanki.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.wanki.R

/** A header button; [icon] 0 means no icon (allowed for menu items only). */
data class HeaderAction(
    @DrawableRes val icon: Int,
    val label: String,
    val tag: String? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** At most [max] primary actions stay visible; the rest go to the front of the menu. */
fun splitActions(
    primary: List<HeaderAction>,
    menu: List<HeaderAction>,
    max: Int = 2,
): Pair<List<HeaderAction>, List<HeaderAction>> {
    val n = max.coerceAtLeast(0)
    return primary.take(n) to primary.drop(n) + menu
}

@Composable
fun ScreenHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    subtitleMaxLines: Int = Int.MAX_VALUE,
    primary: List<HeaderAction> = emptyList(),
    menu: List<HeaderAction> = emptyList(),
    actions: @Composable () -> Unit = {}, // kept for screens not converted yet
) {
    val (visible, overflow) = splitActions(primary, menu)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Назад")
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = subtitleMaxLines, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        visible.forEach { a ->
            IconButton(onClick = a.onClick, enabled = a.enabled, modifier = a.tag?.let { Modifier.testTag(it) } ?: Modifier) {
                Icon(painterResource(a.icon), contentDescription = a.label)
            }
        }
        actions()
        if (overflow.isNotEmpty()) OverflowMenu(overflow)
    }
}

@Composable
private fun OverflowMenu(items: List<HeaderAction>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("menu")) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "Ещё")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { a ->
                DropdownMenuItem(
                    text = { Text(a.label) },
                    enabled = a.enabled,
                    leadingIcon = if (a.icon != 0) ({ Icon(painterResource(a.icon), contentDescription = null) }) else null,
                    onClick = { open = false; a.onClick() },
                    modifier = a.tag?.let { Modifier.testTag(it) } ?: Modifier,
                )
            }
        }
    }
}
