package app.wanki.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import app.wanki.data.PageEntity
import app.wanki.data.PageTemplate
import app.wanki.graph
import app.wanki.ink.InkStroke
import app.wanki.input.StrokeConversion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val THUMB_LONG_SIDE = 340
private val cache = ThumbnailCache<ImageBitmap>()

/**
 * A page as a small picture: paper, template and strokes scaled into the box (aspect 1000:1414, landscape
 * 1414:1000). Plain paper shows while it renders; the bitmap is cached by page id and `updatedAt`.
 */
@Composable
fun PageThumbnail(page: PageEntity, modifier: Modifier = Modifier) {
    val repository = LocalContext.current.graph.repository
    val key = if (page.landscape) page.id + "/l" else page.id
    var bitmap by remember(key, page.updatedAt) { mutableStateOf(cache.get(key, page.updatedAt)) }
    LaunchedEffect(key, page.updatedAt) {
        if (bitmap != null) return@LaunchedEffect
        val rendered = withContext(Dispatchers.Default) {
            renderThumbnail(PageTemplate.valueOf(page.template), page.landscape, repository.loadInk(page.id))
        }
        cache.put(key, page.updatedAt, rendered)
        bitmap = rendered
    }
    val size = PageGeometry.size(page.landscape)
    Canvas(modifier.aspectRatio(size.width / size.height)) {
        val b = bitmap
        if (b == null) drawRect(PaperColor)
        else drawImage(b, dstSize = IntSize(this.size.width.toInt(), this.size.height.toInt()))
    }
}

/** Draws the page with [drawPage] and the strokes with the editor's stroke renderer into a bitmap. */
internal fun renderThumbnail(template: PageTemplate, landscape: Boolean, strokes: List<InkStroke>): ImageBitmap {
    val page = PageGeometry.size(landscape)
    val scale = THUMB_LONG_SIDE / maxOf(page.width, page.height)
    val image = ImageBitmap((page.width * scale).toInt(), (page.height * scale).toInt())
    val canvas = ComposeCanvas(image)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, androidx.compose.ui.geometry.Size(image.width.toFloat(), image.height.toFloat())) {
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) { drawPage(template, landscape) }
        if (strokes.isNotEmpty()) {
            val renderer = CanvasStrokeRenderer.create()
            val matrix = android.graphics.Matrix().apply { setScale(scale, scale) }
            drawIntoCanvas { c ->
                for (s in strokes) StrokeConversion.toInk(s)?.let { renderer.draw(c.nativeCanvas, it, matrix) }
            }
        }
    }
    return image
}
