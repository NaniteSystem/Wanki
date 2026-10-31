package app.wanki.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.wanki.data.Repository
import app.wanki.graph
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PictureImportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val media = MediaStore(context)
    private val made = ArrayList<String>()
    private val dir = File(context.cacheDir, "picture-import-test").apply { mkdirs() }

    @After
    fun cleanUp() {
        made.forEach { media.file(it)?.delete() }
        dir.deleteRecursively()
    }

    private fun write(name: String, bitmap: Bitmap, format: Bitmap.CompressFormat): Uri {
        val f = File(dir, name)
        f.outputStream().use { bitmap.compress(format, 90, it) }
        bitmap.recycle()
        return Uri.fromFile(f)
    }

    @Test
    fun hugeJpegIsDownscaled() {
        val big = Bitmap.createBitmap(8000, 6000, Bitmap.Config.RGB_565).apply { eraseColor(Color.rgb(200, 120, 40)) }
        val uri = write("big.jpg", big, Bitmap.CompressFormat.JPEG)
        val name = PictureImport.prepare(context, uri, media).also { made += it }
        assertTrue(name, name.matches(Regex("page-[0-9a-f-]+\\.jpg")))
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(media.file(name)!!.path, o)
        assertEquals(2048, maxOf(o.outWidth, o.outHeight))
        assertEquals(1536, minOf(o.outWidth, o.outHeight))
        assertEquals(2048 to 1536, PictureImport.size(media, name))
    }

    @Test
    fun pngWithAlphaStaysPng() {
        val b = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.argb(80, 10, 200, 10)) }
        val uri = write("alpha.png", b, Bitmap.CompressFormat.PNG)
        val name = PictureImport.prepare(context, uri, media).also { made += it }
        assertTrue(name, name.endsWith(".png"))
        assertTrue(BitmapFactory.decodeFile(media.file(name)!!.path).hasAlpha())
    }

    @Test
    fun textFileIsNotAPicture() {
        val f = File(dir, "notes.txt").apply { writeText("just some text, not a picture") }
        val before = media.dir.list().orEmpty().toSet()
        val e = runCatching { PictureImport.prepare(context, Uri.fromFile(f), media) }.exceptionOrNull()
        assertTrue("$e", e is IOException && e.message == "unreadable")
        // nothing is left in the media folder
        assertEquals(before, media.dir.list().orEmpty().toSet())
    }

    /** The startup cleanup may run between the file write and the object row being saved: a new file stays. */
    @Test
    fun justImportedPictureSurvivesMediaCleanup() = runBlocking {
        val b = Bitmap.createBitmap(64, 64, Bitmap.Config.RGB_565).apply { eraseColor(Color.BLUE) }
        val name = PictureImport.prepare(context, write("small.jpg", b, Bitmap.CompressFormat.JPEG), media).also { made += it }
        val repo: Repository = context.graph.repository
        repo.collectMediaGarbage(media)
        assertTrue(media.file(name) != null)
    }
}
