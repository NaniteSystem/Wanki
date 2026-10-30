package app.wanki.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import app.wanki.data.PageEntity
import app.wanki.data.PageTemplate
import app.wanki.graph
import app.wanki.ink.InkStroke
import app.wanki.input.InkRaster
import android.util.Log
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState
import kotlin.math.roundToInt

private const val THUMB_LONG_SIDE = 340
private val cache = ThumbnailCache<ImageBitmap>(maxEntries = 32)

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
        // a broken page keeps plain paper: one card must not take the list down
        try {
            val rendered = withContext(Dispatchers.Default) {
                renderThumbnail(PageTemplate.valueOf(page.template), page.landscape, repository.loadInk(page.id))
            }
            cache.put(key, page.updatedAt, rendered)
            bitmap = rendered
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Wanki", "page ${page.id}: thumbnail not rendered", e)
        }
    }
    val size = PageGeometry.size(page.landscape)
    Canvas(modifier.aspectRatio(size.width / size.height).semantics { contentDescription = "Страница" }) {
        val b = bitmap
        if (b == null) drawRect(PaperColor)
        else drawImage(b, dstSize = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()), filterQuality = FilterQuality.Medium)
    }
}

/** A hairline stroke would vanish at thumbnail scale: keep it at least about a pixel wide in the bitmap. */
private const val MIN_STROKE_PX = 1.2f

/** Draws the page with [drawPage] and the strokes with the editor's stroke renderer into a bitmap. */
internal fun renderThumbnail(template: PageTemplate, landscape: Boolean, strokes: List<InkStroke>): ImageBitmap {
    val page = PageGeometry.size(landscape)
    val scale = THUMB_LONG_SIDE / maxOf(page.width, page.height)
    val image = ImageBitmap((page.width * scale).roundToInt(), (page.height * scale).roundToInt())
    val canvas = ComposeCanvas(image)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, androidx.compose.ui.geometry.Size(image.width.toFloat(), image.height.toFloat())) {
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) { drawPage(template, landscape) }
        if (strokes.isNotEmpty()) drawIntoCanvas { InkRaster.draw(it.nativeCanvas, strokes, scale, 0f, 0f, MIN_STROKE_PX) }
    }
    return image
}

/** The first page of a notebook (or board) as a thumbnail, centred in the width and at most [maxHeight] tall; nothing while it loads. */
@Composable
fun FirstPageThumbnail(notebookId: String, modifier: Modifier = Modifier, board: Boolean = false, maxHeight: Dp = 170.dp) {
    val repository = LocalContext.current.graph.repository
    val first by remember(notebookId) { repository.observePages(notebookId).map { it.firstOrNull() } }.collectAsState(null)
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        first?.let { if (board) BoardThumbnail(it, Modifier.fillMaxWidth()) else PageThumbnail(it, Modifier.heightIn(max = maxHeight)) }
    }
}

private const val BOARD_THUMB_WIDTH = 340
private const val BOARD_ASPECT = 4f / 3f
private val boardCache = ThumbnailCache<ImageBitmap>(maxEntries = 32)

/** A board as a 4:3 picture cropped to the bounds of its ink (the whole dotted sheet when it is empty). */
@Composable
fun BoardThumbnail(page: PageEntity, modifier: Modifier = Modifier) {
    val repository = LocalContext.current.graph.repository
    var bitmap by remember(page.id, page.updatedAt) { mutableStateOf(boardCache.get(page.id, page.updatedAt)) }
    LaunchedEffect(page.id, page.updatedAt) {
        if (bitmap != null) return@LaunchedEffect
        try {
            val rendered = withContext(Dispatchers.Default) { renderBoardThumbnail(repository.loadInk(page.id)) }
            boardCache.put(page.id, page.updatedAt, rendered)
            bitmap = rendered
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Wanki", "board ${page.id}: thumbnail not rendered", e)
        }
    }
    Canvas(modifier.aspectRatio(BOARD_ASPECT).semantics { contentDescription = "Доска" }) {
        val b = bitmap
        if (b == null) drawRect(PaperColor)
        else drawImage(b, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium)
    }
}

internal fun renderBoardThumbnail(strokes: List<InkStroke>): ImageBitmap {
    val bounds = strokes.mapNotNull { it.bounds() }.reduceOrNull { a, b -> a.union(b) }
    // pad the ink, then widen the shorter side to the 4:3 frame around the same centre
    var w = maxOf(bounds?.width ?: 0f, 1f) * 1.1f + 40f
    var h = maxOf(bounds?.height ?: 0f, 1f) * 1.1f + 40f
    if (bounds == null) { w = 1200f; h = 900f }
    if (w / h < BOARD_ASPECT) w = h * BOARD_ASPECT else h = w / BOARD_ASPECT
    val cx = bounds?.let { (it.left + it.right) / 2 } ?: 600f
    val cy = bounds?.let { (it.top + it.bottom) / 2 } ?: 450f
    val scale = BOARD_THUMB_WIDTH / w
    val image = ImageBitmap(BOARD_THUMB_WIDTH, (h * scale).roundToInt())
    val canvas = ComposeCanvas(image)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, androidx.compose.ui.geometry.Size(image.width.toFloat(), image.height.toFloat())) {
        val visible = app.wanki.ink.InkRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        withTransform({ scale(scale, scale, pivot = Offset.Zero); translate(-visible.left, -visible.top) }) { drawBoard(visible, scale) }
        if (strokes.isNotEmpty()) drawIntoCanvas { InkRaster.draw(it.nativeCanvas, strokes, scale, visible.left, visible.top, MIN_STROKE_PX) }
    }
    return image
}
