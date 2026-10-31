package app.wanki.importer

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import app.wanki.data.Repository
import app.wanki.media.MediaStore
import app.wanki.media.PictureImport
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What an import of PDFs and pictures did; [Failed] added nothing. */
sealed interface ImportResult {
    data class Added(val pages: Int, val capped: Boolean) : ImportResult
    data class Failed(val message: String) : ImportResult
}

/** The message for the shell snackbar. */
fun ImportResult.message(): String = when (this) {
    is ImportResult.Added -> if (capped) "Добавлено первых $pages страниц" else "Добавлено страниц: $pages"
    is ImportResult.Failed -> message
}

/**
 * Adds PDFs and pictures to a notebook as new pages with them as backgrounds (Decision 12). A PDF is kept whole in the
 * media folder (`page-<uuid>.pdf`) and gives one page per PDF page, landscape where the PDF page is wider than tall;
 * a picture is stored by [PictureImport] and gives one page. At most [MAX_PAGES] pages are added. All pages go in one
 * transaction; on any failure the media written so far is deleted and nothing is added.
 */
class PageImporter(private val context: Context, private val repository: Repository, private val media: MediaStore) {

    private class Failure(message: String, cause: Throwable? = null) : Exception(message, cause)

    suspend fun import(uris: List<Uri>, notebookId: String, after: Int): ImportResult = withContext(Dispatchers.IO) {
        val written = ArrayList<String>()
        try {
            val pages = ArrayList<Repository.NewPage>()
            var capped = false
            for (uri in uris) {
                if (pages.size >= MAX_PAGES) { capped = true; break }
                if (isPdf(uri)) capped = addPdf(uri, pages, written) || capped
                else addPicture(uri, pages, written)
            }
            repository.insertPages(notebookId, after + 1, pages)
            ImportResult.Added(pages.size, capped)
        } catch (e: CancellationException) {
            discard(written)
            throw e
        } catch (e: Failure) {
            Log.e("Wanki", "import into $notebookId failed: ${e.message}", e.cause)
            discard(written)
            ImportResult.Failed(e.message!!)
        } catch (e: Exception) {
            Log.e("Wanki", "import into $notebookId failed", e)
            discard(written)
            ImportResult.Failed("Не удалось добавить страницы")
        }
    }

    private fun discard(names: List<String>) {
        // a copy cut short leaves its .part file
        for (n in names) for (f in listOf(File(media.dir, n), File(media.dir, "$n.part"))) {
            if (f.exists() && !f.delete()) Log.e("Wanki", "import: ${f.name} not deleted")
        }
    }

    /** By content, not by name or type: «%PDF-» at the start. */
    private fun isPdf(uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(5)
            var n = 0
            while (n < head.size) { val r = input.read(head, n, head.size - n); if (r < 0) break; n += r }
            n == 5 && String(head, Charsets.ISO_8859_1) == "%PDF-"
        } ?: false
    } catch (e: IOException) {
        false
    } catch (e: SecurityException) {
        false
    }

    /** Copies the PDF and adds its pages (up to the cap); returns whether pages were left out. */
    private fun addPdf(uri: Uri, pages: MutableList<Repository.NewPage>, written: MutableList<String>): Boolean {
        val name = "page-${UUID.randomUUID()}.pdf"
        val file = try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("no stream")
            written += name
            input.use { media.save(name, it) }
        } catch (e: IOException) {
            throw Failure(PDF_UNREADABLE, e)
        } catch (e: SecurityException) {
            throw Failure(PDF_UNREADABLE, e)
        }
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    if (r.pageCount == 0) throw Failure("В PDF нет страниц")
                    val room = MAX_PAGES - pages.size
                    val count = minOf(r.pageCount, room)
                    for (i in 0 until count) {
                        val landscape = r.openPage(i).use { p -> p.width > p.height }
                        pages += Repository.NewPage(landscape, name, i)
                    }
                    return r.pageCount > room
                }
            }
        } catch (e: IOException) {
            throw Failure(PDF_UNREADABLE, e)
        } catch (e: SecurityException) {
            // encrypted
            throw Failure(PDF_UNREADABLE, e)
        }
    }

    private fun addPicture(uri: Uri, pages: MutableList<Repository.NewPage>, written: MutableList<String>) {
        val name = try {
            PictureImport.prepare(context, uri, media)
        } catch (e: IOException) {
            throw Failure(PICTURE_UNREADABLE, e)
        } catch (e: SecurityException) {
            throw Failure(PICTURE_UNREADABLE, e)
        }
        written += name
        val size = PictureImport.size(media, name) ?: throw Failure(PICTURE_UNREADABLE)
        pages += Repository.NewPage(size.first > size.second, name, null)
    }

    companion object {
        const val MAX_PAGES = 300
        private const val PDF_UNREADABLE = "Не удалось открыть PDF"
        private const val PICTURE_UNREADABLE = "Не удалось открыть картинку"
    }
}
