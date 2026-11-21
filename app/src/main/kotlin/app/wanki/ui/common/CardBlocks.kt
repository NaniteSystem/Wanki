package app.wanki.ui.common

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wanki.anki.CardBlock
import app.wanki.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A card side: text (the first line of a plain question large, further lines smaller), pictures
 * from the media folder and buttons for sounds.
 */
@Composable
fun CardBlocks(blocks: List<CardBlock>, modifier: Modifier = Modifier, textSize: TextUnit = 34.sp) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEachIndexed { i, b ->
            when (b) {
                is CardBlock.Text -> {
                    val lines = b.text.lines()
                    val first = lines.first()
                    val size = if (i == 0) (if (first.length > 40) textSize * 0.75f else textSize) else textSize * 0.6f
                    Text(first, fontSize = size, textAlign = TextAlign.Center, lineHeight = size * 1.2f)
                    lines.drop(1).filter { it.isNotBlank() }.forEach {
                        Text(it, fontSize = textSize * 0.6f, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is CardBlock.Image -> MediaImage(b.file)
                is CardBlock.Sound -> SoundButton(b.file)
                is CardBlock.Mask -> MaskImage(b)
            }
        }
    }
}

@Composable
private fun MediaImage(file: String) {
    val graph = LocalContext.current.graph
    val bitmap by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { graph.media.bitmap(file)?.asImageBitmap() }.getOrNull() }
    }
    val b = bitmap
    if (b == null) {
        Text("[$file]", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Image(b, contentDescription = file, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp))
    }
}

/** A mask card's picture, rendered from the page each time (page edits show); «Источник удалён» when it is gone. */
@Composable
private fun MaskImage(block: CardBlock.Mask) {
    val graph = LocalContext.current.graph
    // null = loading, Result.failure = gone or broken
    val bitmap by produceState<Result<ImageBitmap>?>(null, block) {
        value = runCatching { renderMaskCard(graph, block.refId, block.revealed) ?: error("gone") }
            .onFailure { if (it.message != "gone") android.util.Log.e("Wanki", "mask card ${block.refId} not rendered", it) }
    }
    val r = bitmap ?: return
    val b = r.getOrNull()
    if (b == null) {
        Text("Источник удалён", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("mask-gone"))
    } else {
        Image(
            b, contentDescription = if (block.revealed) "Открытый скотч" else "Скотч", contentScale = ContentScale.Fit,
            // scaled up to the width (a tablet is far wider than the bitmap), at most 360 dp tall
            modifier = Modifier.widthIn(max = 360.dp * (b.width.toFloat() / b.height)).fillMaxWidth().aspectRatio(b.width.toFloat() / b.height)
                .testTag(if (block.revealed) "mask-open" else "mask-closed"),
        )
    }
}

@Composable
private fun SoundButton(file: String) {
    val context = LocalContext.current
    OutlinedButton(onClick = {
        if (!context.graph.media.play(file)) Toast.makeText(context, "Нет звукового файла $file", Toast.LENGTH_SHORT).show()
    }) { Text("🔊 Звук") }
}
