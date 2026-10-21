package app.wanki.media

import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Records your own voice reciting a piece or a portion, as AAC in MPEG-4 into files/media.
 * Writes to "<name>.part" and renames on [stop]; recording the same name again overwrites the earlier file.
 * A take shorter than 300 ms never replaces anything, but a re-record of 300 ms or more that is
 * interrupted (Back, leaving the screen, the app going to the background) is stopped and does replace
 * the previous take.
 */
class VoiceRecorder(private val media: MediaStore) {
    private var recorder: MediaRecorder? = null
    private val _active = MutableStateFlow(false)
    /** [recording] as a flow, for screens that disable other sound while the mic is open. */
    val active: StateFlow<Boolean> = _active.asStateFlow()
    private var part: File? = null
    private var target: File? = null
    private var startedAt = 0L

    val recording: Boolean @Synchronized get() = recorder != null

    /** Starts recording into [name]; a recording already running is discarded first. Throws when the mic is unavailable. */
    @Synchronized
    fun start(name: String) {
        discard()
        media.stop()
        media.dir.mkdirs()
        val out = File(media.dir, MediaStore.safeName(name))
        val tmp = File(media.dir, out.name + ".part").also { it.delete() }
        @Suppress("DEPRECATION") // MediaRecorder(Context) is API 31+; minSdk is 29
        val r = MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(96_000)
            r.setOutputFile(tmp.path)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            tmp.delete()
            throw e
        }
        recorder = r
        _active.value = true
        part = tmp
        target = out
        startedAt = SystemClock.elapsedRealtime()
    }

    /** Stops and keeps the recording; false when nothing usable was recorded (shorter than 300 ms), leaving no file. */
    @Synchronized
    fun stop(): Boolean {
        val r = recorder ?: return false
        val tmp = part
        val out = target
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        recorder = null
        _active.value = false
        part = null
        target = null
        // stop() throws when no audio data was written; the file is then unusable
        val stopped = runCatching { r.stop() }.isSuccess
        r.release()
        if (tmp == null || out == null) return false
        if (!stopped || !usable(elapsed) || tmp.length() == 0L) { tmp.delete(); return false }
        // rename(2) replaces an existing target atomically
        return tmp.renameTo(out).also { if (!it) tmp.delete() }
    }

    /** Stops without keeping anything. */
    @Synchronized
    fun discard() {
        val r = recorder ?: return
        recorder = null
        _active.value = false
        runCatching { r.stop() }
        r.release()
        part?.delete()
        part = null
        target = null
    }

    companion object {
        const val MIN_MILLIS = 300L

        fun usable(millis: Long): Boolean = millis >= MIN_MILLIS

        /** "rec-<first>.m4a" for one piece, "rec-<first>-<last>.m4a" for a portion. */
        fun fileName(pieceIds: List<String>): String {
            require(pieceIds.isNotEmpty()) { "no pieces to name a recording after" }
            val first = clean(pieceIds.first())
            val last = clean(pieceIds.last())
            return if (first == last) "rec-$first.m4a" else "rec-$first-$last.m4a"
        }

        private fun clean(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
    }
}
