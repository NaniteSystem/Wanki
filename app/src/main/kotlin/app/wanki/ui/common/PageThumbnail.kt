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
import androidx.compose.runtime.produceState
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
import app.wanki.data.PageSize
import app.wanki.data.PageTemplate
import app.wanki.graph
import app.wanki.ink.InkRect
import app.wanki.ink.InkStroke
import app.wanki.input.InkRaster
import app.wanki.input.PageBackgrounds
import app.wanki.input.PageObject
import app.wanki.input.PictureState
import app.wanki.input.drawPageObject
import app.wanki.input.toModelOrNull
import app.wanki.data.PageObjectKind
import android.graphics.Bitmap
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
private const val THUMB_PICTURE_SIDE = 256
private val cache = ThumbnailCache<ImageBitmap>(maxEntries = 32)

/**
 * A page as a small picture: paper, template, background, objects and strokes scaled into the box (aspect 1000:1414, landscape
 * 1414:1000). An [PageSize.ENDLESS] page shows its top 1000 × 1414. Plain paper shows while it renders; the bitmap is
 * cached by page id and `updatedAt`.
 */
@Composable
fun PageThumbnail(page: PageEntity, modifier: Modifier = Modifier, pageSize: PageSize = PageSize.A4) {
    val graph = LocalContext.current.graph
    val key = page.id + (if (pageSize == PageSize.ENDLESS) "/e" else if (page.landscape) "/l" else "")
    var bitmap by remember(key, page.updatedAt) { mutableStateOf(cache.get(key, page.updatedAt)) }
    LaunchedEffect(key, page.updatedAt) {
        if (bitmap != null) return@LaunchedEffect
        // a broken page keeps plain paper: one card must not take the list down
        try {
            val rendered = renderPage(graph, page, pageSize)
            cache.put(key, page.updatedAt, rendered)
            bitmap = rendered
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Wanki", "page ${page.id}: thumbnail not rendered", e)
        }
    }
    val size = PageGeometry.size(page.landscape, pageSize)
    Canvas(modifier.aspectRatio(size.width / size.height).semantics { contentDescription = "Страница" }) {
        val b = bitmap
        if (b == null) drawRect(PaperColor)
        else drawImage(b, dstSize = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()), filterQuality = FilterQuality.Medium)
    }
}

/** Loads [page] and renders it with [renderThumbnail] ([crop], [open], [longSide] as there). */
internal suspend fun renderPage(
    graph: app.wanki.AppGraph, page: PageEntity, pageSize: PageSize, crop: InkRect? = null, open: Set<String> = emptySet(),
    longSide: Int = THUMB_LONG_SIDE,
): ImageBitmap = withContext(Dispatchers.IO) {
    val repository = graph.repository
    val strokes = repository.loadInk(page.id)
    val objects = repository.pageObjects(page.id).mapNotNull { it.toModelOrNull() }.sortedWith(PageObject.ORDER)
    val background = page.backgroundMedia?.let { PageBackgrounds.render(graph.media, it, page.backgroundPage, longSide) }
    val pictures = objects.mapNotNull { o -> o.media?.takeIf { o.kind == PageObjectKind.IMAGE } }.distinct()
        .associateWith { runCatching { graph.media.bitmap(it, THUMB_PICTURE_SIDE) }.getOrNull() }
    try {
        withContext(Dispatchers.Default) {
            renderThumbnail(PageTemplate.valueOf(page.template), page.landscape, strokes, pageSize, objects, background, pictures, crop, open, longSide)
        }
    } finally {
        // drawn into the thumbnail: no longer needed
        background?.recycle()
        pictures.values.forEach { it?.recycle() }
    }
}

/** What a mask card shows: the page around the mask, null when the ref, page or mask is gone (or the ref is detached). */
internal suspend fun renderMaskCard(graph: app.wanki.AppGraph, refId: String, revealed: Boolean): ImageBitmap? {
    val repository = graph.repository
    val ref = repository.sourceRef(refId)?.takeIf { !it.detached } ?: return null
    val page = repository.page(ref.sourceId) ?: return null
    val mask = repository.pageObjects(page.id).firstOrNull { it.id == ref.objectId }?.toModelOrNull() ?: return null
    val size = repository.notebook(page.notebookId)?.let { runCatching { PageSize.valueOf(it.pageSize) }.getOrNull() } ?: PageSize.A4
    val b = mask.bounds()
    // shortcut: the mask padded to 1.5× its size, at least 400 units a side; other masks drawn open so only this one asks
    val w = maxOf(b.width * 1.5f, 400f)
    val h = maxOf(b.height * 1.5f, 400f)
    val crop = InkRect(b.centerX - w / 2, b.centerY - h / 2, b.centerX + w / 2, b.centerY + h / 2)
    val others = repository.pageObjects(page.id).filter { it.kind == PageObjectKind.MASK.name && it.id != mask.id }.mapTo(HashSet()) { it.id }
    return renderPage(graph, page, size, crop, if (revealed) others + mask.id else others, longSide = MASK_CARD_LONG_SIDE)
}

private const val MASK_CARD_LONG_SIDE = 720

/** A hairline stroke would vanish at thumbnail scale: keep it at least about a pixel wide in the bitmap. */
private const val MIN_STROKE_PX = 1.2f

/**
 * Draws the page with [drawPage], its [background], [objects] (pictures from [pictures] by media name, a placeholder
 * where missing; tape masks closed, over the ink) and the strokes with the editor's stroke renderer into a bitmap, in the screen's order.
 */
internal fun renderThumbnail(
    template: PageTemplate, landscape: Boolean, strokes: List<InkStroke>, pageSize: PageSize = PageSize.A4,
    objects: List<PageObject> = emptyList(), background: Bitmap? = null, pictures: Map<String, Bitmap?> = emptyMap(),
    /** The world area to draw (default the page; an endless page is cut at the top 1000 × 1414). */
    crop: InkRect? = null,
    /** Masks drawn open (outline only). */
    open: Set<String> = emptySet(),
    longSide: Int = THUMB_LONG_SIDE,
): ImageBitmap {
    val page = PageGeometry.size(landscape, pageSize)
    val area = crop ?: InkRect(0f, 0f, page.width, page.height)
    val scale = longSide / maxOf(area.width, area.height)
    val image = ImageBitmap((area.width * scale).roundToInt().coerceAtLeast(1), (area.height * scale).roundToInt().coerceAtLeast(1))
    val canvas = ComposeCanvas(image)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, androidx.compose.ui.geometry.Size(image.width.toFloat(), image.height.toFloat())) {
        // a crop may reach past the drawn sheet (endless page below its cut, outside the page)
        if (crop != null) drawRect(PaperColor)
        withTransform({ scale(scale, scale, pivot = Offset.Zero); translate(-area.left, -area.top) }) {
            drawPage(template, landscape, pageSize)
            drawIntoCanvas { c ->
                background?.let { PageBackgrounds.draw(c.nativeCanvas, it, PageBackgrounds.box(landscape, pageSize)) }
                for (o in objects) {
                    if (o.kind == PageObjectKind.MASK) continue
                    val picture = o.media?.let { pictures[it] }?.let { PictureState.Ready(it) } ?: PictureState.Missing
                    drawPageObject(c.nativeCanvas, o, scale, picture)
                }
            }
        }
        if (strokes.isNotEmpty()) drawIntoCanvas { InkRaster.draw(it.nativeCanvas, strokes, scale, area.left, area.top, MIN_STROKE_PX) }
        // tape masks lie over the ink, closed unless in [open]
        val masks = objects.filter { it.kind == PageObjectKind.MASK }
        if (masks.isNotEmpty()) withTransform({ scale(scale, scale, pivot = Offset.Zero); translate(-area.left, -area.top) }) {
            drawIntoCanvas { c -> for (o in masks) drawPageObject(c.nativeCanvas, o, scale, open = o.id in open) }
        }
    }
    return image
}

/** The first page of a notebook (or board) as a thumbnail, centred in the width and at most [maxHeight] tall; nothing while it loads. */
@Composable
fun FirstPageThumbnail(notebookId: String, modifier: Modifier = Modifier, board: Boolean = false, maxHeight: Dp = 170.dp) {
    val repository = LocalContext.current.graph.repository
    val first by remember(notebookId) { repository.observePages(notebookId).map { it.firstOrNull() } }.collectAsState(null)
    val size by produceState(PageSize.A4, notebookId) {
        value = repository.notebook(notebookId)?.let { runCatching { PageSize.valueOf(it.pageSize) }.getOrNull() } ?: PageSize.A4
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        first?.let { if (board) BoardThumbnail(it, Modifier.fillMaxWidth()) else PageThumbnail(it, Modifier.heightIn(max = maxHeight), size) }
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
