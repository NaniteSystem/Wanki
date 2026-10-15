package app.wanki.importer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import app.wanki.data.AnswerMode
import app.wanki.data.Repository
import app.wanki.review.WritingLayout
import com.github.luben.zstd.ZstdInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Imports Anki decks (.apkg / .colpkg). Supports the legacy collection (anki2/anki21) and the
 * current one (anki21b, zstd-compressed). Notes become basic cards: first field → question,
 * second → answer; CJK answers become handwriting cards. Scheduling and media are not imported.
 */
class AnkiImporter(private val context: Context, private val repository: Repository) {
    data class Result(val decks: Int, val cards: Int, val skipped: Int)

    suspend fun import(uri: Uri): Result = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "apkg-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val collection = unpack(uri, dir) ?: error("В файле нет коллекции Anki")
            val db = SQLiteDatabase.openDatabase(collection.path, null, SQLiteDatabase.OPEN_READONLY)
            val parsed = db.use { read(it) }
            write(parsed)
        } finally {
            dir.deleteRecursively()
        }
    }

    private class Note(val deck: String, val front: String, val back: String, val tags: String)

    /** Extracts the newest collection format present (newer exports also contain a dummy anki2). */
    private fun unpack(uri: Uri, dir: File): File? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name in setOf("collection.anki21b", "collection.anki21", "collection.anki2")) {
                    File(dir, e.name).outputStream().use { zip.copyTo(it) }
                }
            }
        }
        File(dir, "collection.anki21b").takeIf { it.exists() }?.let { packed ->
            val out = File(dir, "collection.sqlite")
            ZstdInputStream(packed.inputStream().buffered()).use { z -> out.outputStream().use { z.copyTo(it) } }
            return out
        }
        return File(dir, "collection.anki21").takeIf { it.exists() } ?: File(dir, "collection.anki2").takeIf { it.exists() }
    }

    private fun read(db: SQLiteDatabase): List<Note> {
        val deckNames = HashMap<Long, String>()
        // schema 18+: a decks table; older: JSON in col.decks
        runCatching {
            db.rawQuery("SELECT id, name FROM decks", null).use { c ->
                while (c.moveToNext()) deckNames[c.getLong(0)] = AnkiText.deckName(c.getString(1))
            }
        }.onFailure {
            db.rawQuery("SELECT decks FROM col", null).use { c ->
                if (c.moveToFirst()) {
                    val json = JSONObject(c.getString(0))
                    json.keys().forEach { k -> deckNames[k.toLong()] = AnkiText.deckName(json.getJSONObject(k).getString("name")) }
                }
            }
        }
        val deckOfNote = HashMap<Long, Long>()
        db.rawQuery("SELECT nid, did FROM cards ORDER BY ord", null).use { c ->
            while (c.moveToNext()) deckOfNote.putIfAbsent(c.getLong(0), c.getLong(1))
        }
        val notes = ArrayList<Note>()
        db.rawQuery("SELECT id, flds, tags FROM notes", null).use { c ->
            while (c.moveToNext()) {
                val fields = c.getString(1).split('\u001F')
                val deck = deckNames[deckOfNote[c.getLong(0)]] ?: "Импорт из Anki"
                notes += Note(deck, AnkiText.clean(fields.getOrElse(0) { "" }), AnkiText.clean(fields.getOrElse(1) { "" }), c.getString(2).trim())
            }
        }
        return notes
    }

    private suspend fun write(notes: List<Note>): Result {
        var cards = 0
        var skipped = 0
        val byDeck = notes.groupBy { it.deck }
        for ((name, deckNotes) in byDeck) {
            val usable = deckNotes.filter { it.front.isNotBlank() && it.back.isNotBlank() }
            skipped += deckNotes.size - usable.size
            if (usable.isEmpty()) continue
            val handwriting = usable.count { WritingLayout.forAnswer(it.back) is WritingLayout.Cells }
            val deck = repository.createDeck(
                name,
                AnkiText.guessLanguage(usable.take(200).map { it.back }),
                if (handwriting * 2 >= usable.size) AnswerMode.HANDWRITING else AnswerMode.CLASSIC,
            )
            repository.addCards(deck.id, usable.map { n ->
                Repository.NewCard(
                    n.front, n.back,
                    if (WritingLayout.forAnswer(n.back) is WritingLayout.Cells) AnswerMode.HANDWRITING else AnswerMode.CLASSIC,
                    n.tags,
                )
            })
            cards += usable.size
        }
        return Result(byDeck.size, cards, skipped)
    }
}
