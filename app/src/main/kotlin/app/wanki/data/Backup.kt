package app.wanki.data

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import app.wanki.media.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backups: a zip with a consistent copy of the database, the settings, the media folder (pictures
 * and sounds of imported cards) and a manifest. Restoring
 * checks the archive first, then swaps the database files and restarts the app (Room migrates
 * an older database on open). Automatic copies go to files/backups, one a day, the last [KEEP].
 */
class Backup(private val context: Context, private val db: WankiDatabase, private val settings: Settings) {
    data class Info(val file: File, val at: Long, val size: Long)

    val dir: File get() = File(context.filesDir, "backups").apply { mkdirs() }
    private val mediaDir: File get() = File(context.filesDir, MediaStore.DIR)

    fun autoBackups(): List<Info> =
        dir.listFiles { f -> f.name.endsWith(".zip") }.orEmpty()
            .map { Info(it, it.lastModified(), it.length()) }
            .sortedByDescending { it.at }

    suspend fun exportTo(uri: Uri) = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(uri) ?: error("Не удалось создать файл")
        out.use { write(it, includeMedia = true) }
        settings.lastBackupAt = System.currentTimeMillis()
    }

    /** One automatic copy a day (only once there is something to keep). */
    suspend fun autoBackupIfDue(now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        if (now - settings.lastBackupAt < DAY_MS) return@withContext
        if (db.decks().count() == 0) return@withContext
        val name = "wanki-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".zip"
        val tmp = File(dir, "$name.part")
        // daily copies leave the media out (they stay in place when such a copy is restored)
        tmp.outputStream().use { write(it, includeMedia = false) }
        tmp.renameTo(File(dir, name))
        settings.lastBackupAt = now
        autoBackups().drop(KEEP).forEach { it.file.delete() }
    }

    /** Writes the backup archive. */
    fun write(out: OutputStream, includeMedia: Boolean = true) {
        val snapshot = snapshot()
        try {
            ZipOutputStream(out.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(
                    JSONObject()
                        .put("format", 1)
                        .put("dbVersion", DB_VERSION)
                        .put("createdAt", System.currentTimeMillis())
                        .put("app", appVersion())
                        .toString(2).toByteArray(),
                )
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(DB_ENTRY))
                snapshot.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(SETTINGS_ENTRY))
                zip.write(settingsJson().toString(2).toByteArray())
                zip.closeEntry()
                if (includeMedia) mediaDir.listFiles { f -> f.isFile && !f.name.endsWith(".part") }.orEmpty().forEach { f ->
                    zip.putNextEntry(ZipEntry(MEDIA_PREFIX + f.name))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } finally {
            snapshot.delete()
        }
    }

    data class Checked(
        val dbFile: File, val dbVersion: Int, val createdAt: Long, val cards: Int, val settings: JSONObject?,
        /** Media files of the backup (null for backups made before media were included). */
        val media: File?,
    )

    /** Unpacks and validates an archive without touching the current data. */
    suspend fun check(input: InputStream): Checked = withContext(Dispatchers.IO) {
        val work = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var manifest: JSONObject? = null
        var prefs: JSONObject? = null
        val dbFile = File(work, "wanki.db")
        val media = File(work, "media")
        var hasMedia = false
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                when {
                    e.name == MANIFEST -> manifest = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                    e.name == SETTINGS_ENTRY -> prefs = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                    e.name == DB_ENTRY -> dbFile.outputStream().use { zip.copyTo(it) }
                    e.name.startsWith(MEDIA_PREFIX) && !e.isDirectory -> {
                        media.mkdirs()
                        hasMedia = true
                        File(media, MediaStore.safeName(e.name.removePrefix(MEDIA_PREFIX))).outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        }
        val m = manifest ?: error("Это не резервная копия Wanki")
        if (!dbFile.exists()) error("В копии нет базы данных")
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { sdb ->
            sdb.rawQuery("PRAGMA quick_check", null).use { c -> if (!c.moveToFirst() || c.getString(0) != "ok") error("База в копии повреждена") }
            val version = sdb.version
            if (version < 1 || version > DB_VERSION) error("Копия сделана более новой версией Wanki (схема $version)")
            val cards = sdb.rawQuery("SELECT COUNT(*) FROM cards WHERE deleted = 0", null).use { c -> c.moveToFirst(); c.getInt(0) }
            Checked(dbFile, version, m.optLong("createdAt"), cards, prefs, media.takeIf { hasMedia })
        }
    }

    /** Replaces the database and settings with a checked backup and restarts the app. */
    fun restoreAndRestart(checked: Checked) {
        db.close()
        val target = context.getDatabasePath(WankiDatabase.NAME)
        for (suffix in listOf("", "-wal", "-shm", "-journal")) File(target.path + suffix).delete()
        checked.dbFile.copyTo(target, overwrite = true)
        checked.settings?.let(::restoreSettings)
        checked.media?.let { from ->
            mediaDir.deleteRecursively()
            from.copyRecursively(mediaDir, overwrite = true)
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
        context.startActivity(Intent.makeRestartActivityTask(launch.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Runtime.getRuntime().exit(0)
    }

    /** A consistent copy of the live database in the cache dir. */
    private fun snapshot(): File {
        val out = File(context.cacheDir, "snapshot-${System.nanoTime()}.db")
        val sdb = db.openHelper.writableDatabase
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // SQLite 3.28+: a transactionally consistent copy while the app keeps working
            sdb.execSQL("VACUUM INTO ?", arrayOf(out.path))
        } else {
            repeat(3) { attempt ->
                sdb.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
                context.getDatabasePath(WankiDatabase.NAME).copyTo(out, overwrite = true)
                val ok = runCatching {
                    SQLiteDatabase.openDatabase(out.path, null, SQLiteDatabase.OPEN_READONLY).use { c ->
                        c.rawQuery("PRAGMA quick_check", null).use { it.moveToFirst() && it.getString(0) == "ok" }
                    }
                }.getOrDefault(false)
                if (ok) return out
                if (attempt == 2) error("Не удалось сделать согласованную копию базы")
            }
        }
        return out
    }

    private fun settingsJson(): JSONObject {
        val json = JSONObject()
        for ((k, v) in settings.export()) {
            val type = when (v) {
                is Float -> "float"; is Int -> "int"; is Long -> "long"; is Boolean -> "boolean"; is String -> "string"
                else -> continue
            }
            json.put(k, JSONObject().put("type", type).put("value", v))
        }
        return json
    }

    private fun restoreSettings(json: JSONObject) {
        val editor = context.getSharedPreferences(Settings.NAME, Context.MODE_PRIVATE).edit().clear()
        for (k in json.keys()) {
            val o = json.optJSONObject(k) ?: continue
            val value: Any = when (o.optString("type")) {
                "float" -> o.getDouble("value").toFloat()
                "int" -> o.getInt("value")
                "long" -> o.getLong("value")
                "boolean" -> o.getBoolean("value")
                "string" -> o.getString("value")
                else -> continue
            }
            Settings.write(editor, k, value)
        }
        editor.commit()
    }

    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

    companion object {
        const val KEEP = 7
        const val DAY_MS = 24 * 3600_000L
        private const val MANIFEST = "manifest.json"
        private const val DB_ENTRY = "wanki.db"
        private const val SETTINGS_ENTRY = "settings.json"
        private const val MEDIA_PREFIX = "media/"
        const val DB_VERSION = WankiDatabase.VERSION
    }
}
