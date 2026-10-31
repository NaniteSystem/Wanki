package app.wanki.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Pictures put on pages (Decision 11): decoded with sampling so the long side is at most [MAX_SIDE] (turned upright by
 * their EXIF orientation), stored in the media folder as `page-<uuid>.jpg` (JPEG quality 90) or `.png` when the
 * picture has alpha. The file exists before its object row is saved; the media cleanup keeps young files for that
 * ([app.wanki.data.Repository.collectMediaGarbage]).
 */
object PictureImport {
    const val MAX_SIDE = 2048
    private const val JPEG_QUALITY = 90

    /** Stores the picture at [uri] and returns its media name. IO thread. Not a picture: IOException("unreadable"). */
    fun prepare(context: Context, uri: Uri, media: MediaStore): String {
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // compressed below
                val w = info.size.width
                val h = info.size.height
                val long = maxOf(w, h)
                if (long > MAX_SIDE) {
                    val k = MAX_SIDE.toFloat() / long
                    decoder.setTargetSize((w * k).roundToInt().coerceIn(1, MAX_SIDE), (h * k).roundToInt().coerceIn(1, MAX_SIDE))
                }
            }
        } catch (e: ImageDecoder.DecodeException) {
            throw IOException("unreadable", e)
        }
        try {
            val png = bitmap.hasAlpha()
            val name = "page-${UUID.randomUUID()}.${if (png) "png" else "jpg"}"
            media.dir.mkdirs()
            val target = File(media.dir, name)
            val part = File(media.dir, "$name.part")
            try {
                val ok = part.outputStream().use { out ->
                    bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else JPEG_QUALITY, out)
                }
                if (!ok) throw IOException("picture not encoded")
                if (!part.renameTo(target)) throw IOException("picture not stored")
            } finally {
                part.delete()
            }
            return name
        } finally {
            bitmap.recycle()
        }
    }

    /** Width and height in pixels of the stored picture [name], or null when it cannot be read. */
    fun size(media: MediaStore, name: String): Pair<Int, Int>? {
        val f = media.file(name) ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }
}
