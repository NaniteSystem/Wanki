package app.wanki.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import java.io.File
import java.io.InputStream

/**
 * Pictures and sounds of imported cards and your own voice recordings, in files/media under their Anki names (paths stripped,
 * so a name can never point outside the folder).
 */
class MediaStore(context: Context) {
    val dir: File = File(context.filesDir, DIR)
    private var player: MediaPlayer? = null

    fun file(name: String): File? = File(dir, safeName(name)).takeIf { it.isFile }

    fun save(name: String, input: InputStream): File {
        dir.mkdirs()
        val target = File(dir, safeName(name))
        val part = File(dir, target.name + ".part")
        part.outputStream().use { input.copyTo(it) }
        part.renameTo(target)
        return target
    }

    /** Decodes a picture no larger than about [maxSide] pixels. */
    fun bitmap(name: String, maxSide: Int = 1600): Bitmap? {
        val f = file(name) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Plays a sound, over and over when [loop]; returns false when the file is missing or cannot be played. */
    @Synchronized
    fun play(name: String, loop: Boolean = false): Boolean {
        val f = file(name) ?: return false
        stop()
        val p = MediaPlayer()
        return runCatching {
            p.setDataSource(f.path)
            p.isLooping = loop
            p.setOnCompletionListener { done -> synchronized(this@MediaStore) { if (player === done) player = null }; done.release() }
            p.prepare()
            p.start()
            player = p
        }.onFailure { p.release() }.isSuccess
    }

    /** Stops whatever is playing. */
    @Synchronized
    fun stop() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    companion object {
        const val DIR = "media"

        fun safeName(name: String): String {
            val base = name.substringAfterLast('/').substringAfterLast('\\').replace("\u0000", "").trim()
            return if (base.isEmpty() || base == "." || base == "..") "_" else base
        }
    }
}
