package app.wanki.anki

import android.database.sqlite.SQLiteDatabase
import app.wanki.importer.AnkiText
import org.json.JSONObject

data class AnkiModel(val id: Long, val type: NoteType)
data class AnkiDeck(val id: Long, val name: String, val confId: Long?)
data class AnkiDeckConf(val newPerDay: Int?, val reviewsPerDay: Int?)
data class AnkiNote(val id: Long, val guid: String, val mid: Long, val modSec: Long, val tags: String, val fields: String)
data class AnkiCard(
    val id: Long, val nid: Long, val did: Long, val ord: Int, val type: Int, val queue: Int,
    val due: Long, val ivl: Int, val factor: Int, val reps: Int, val lapses: Int, val odid: Long,
)

/** An Anki collection as read from its SQLite file (legacy schema 11 or the current one, 18+). */
data class AnkiCollection(
    val createdSec: Long,
    val schedulerV1: Boolean,
    val models: Map<Long, AnkiModel>,
    val decks: Map<Long, AnkiDeck>,
    val deckConfs: Map<Long, AnkiDeckConf>,
    val notes: List<AnkiNote>,
    val cards: List<AnkiCard>,
    val revlog: Map<Long, List<AnkiRevlog>>,
)

object AnkiCollectionReader {
    fun read(db: SQLiteDatabase): AnkiCollection {
        var crt = 0L
        var modelsJson = ""
        var decksJson = ""
        var dconfJson = ""
        var confJson = ""
        db.rawQuery("SELECT crt, models, decks, dconf, conf FROM col", null).use { c ->
            if (c.moveToFirst()) {
                crt = c.getLong(0)
                modelsJson = c.getString(1).orEmpty()
                decksJson = c.getString(2).orEmpty()
                dconfJson = c.getString(3).orEmpty()
                confJson = c.getString(4).orEmpty()
            }
        }
        val newSchema = hasTable(db, "notetypes")
        val models = if (newSchema) newModels(db) else legacyModels(modelsJson)
        val decks = if (hasTable(db, "decks")) newDecks(db) else legacyDecks(decksJson)
        val confs = legacyDeckConfs(dconfJson)
        val schedV1 = schedulerVersion(db, confJson) == 1

        val notes = ArrayList<AnkiNote>()
        db.rawQuery("SELECT id, guid, mid, mod, tags, flds FROM notes", null).use { c ->
            while (c.moveToNext()) notes += AnkiNote(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3), c.getString(4).trim(), c.getString(5))
        }
        val cards = ArrayList<AnkiCard>()
        db.rawQuery("SELECT id, nid, did, ord, type, queue, due, ivl, factor, reps, lapses, odid FROM cards", null).use { c ->
            while (c.moveToNext()) {
                cards += AnkiCard(
                    c.getLong(0), c.getLong(1), c.getLong(2), c.getInt(3), c.getInt(4), c.getInt(5),
                    c.getLong(6), c.getInt(7), c.getInt(8), c.getInt(9), c.getInt(10), c.getLong(11),
                )
            }
        }
        val revlog = HashMap<Long, MutableList<AnkiRevlog>>()
        db.rawQuery("SELECT cid, id, ease, type, ivl, time FROM revlog ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                revlog.getOrPut(c.getLong(0)) { ArrayList() } += AnkiRevlog(c.getLong(1), c.getInt(2), c.getInt(3), c.getInt(4), c.getLong(5))
            }
        }
        return AnkiCollection(crt, schedV1, models, decks, confs, notes, cards, revlog)
    }

    private fun hasTable(db: SQLiteDatabase, name: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name)).use { it.moveToFirst() }

    private fun schedulerVersion(db: SQLiteDatabase, confJson: String): Int {
        runCatching { if (confJson.isNotBlank()) return JSONObject(confJson).optInt("schedVer", 2) }
        if (hasTable(db, "config")) {
            runCatching {
                db.rawQuery("SELECT val FROM config WHERE KEY = 'schedVer'", null).use { c ->
                    if (c.moveToFirst()) return c.getBlob(0).toString(Charsets.UTF_8).trim().toIntOrNull() ?: 2
                }
            }
        }
        return 2
    }

    private fun legacyModels(json: String): Map<Long, AnkiModel> {
        if (json.isBlank()) return emptyMap()
        val o = JSONObject(json)
        return o.keys().asSequence().associate { key ->
            val m = o.getJSONObject(key)
            val flds = m.getJSONArray("flds")
            val tmpls = m.getJSONArray("tmpls")
            val id = key.toLong()
            id to AnkiModel(
                id,
                NoteType(
                    id = "anki-$id",
                    name = m.optString("name"),
                    fields = (0 until flds.length()).map { flds.getJSONObject(it) }.sortedBy { it.optInt("ord") }.map { it.getString("name") },
                    templates = (0 until tmpls.length()).map { tmpls.getJSONObject(it) }.sortedBy { it.optInt("ord") }
                        .map { CardTemplate(it.optString("name"), it.optString("qfmt"), it.optString("afmt")) },
                    kind = if (m.optInt("type") == 1) NoteKind.CLOZE else NoteKind.STANDARD,
                    css = m.optString("css"),
                ),
            )
        }
    }

    /** Schema 18+: notetypes (config: kind = 1, css = 3), fields, templates (config: q = 1, a = 2). */
    private fun newModels(db: SQLiteDatabase): Map<Long, AnkiModel> {
        val fields = HashMap<Long, MutableList<Pair<Int, String>>>()
        db.rawQuery("SELECT ntid, ord, name FROM fields", null).use { c ->
            while (c.moveToNext()) fields.getOrPut(c.getLong(0)) { ArrayList() } += c.getInt(1) to c.getString(2)
        }
        val templates = HashMap<Long, MutableList<Pair<Int, CardTemplate>>>()
        db.rawQuery("SELECT ntid, ord, name, config FROM templates", null).use { c ->
            while (c.moveToNext()) {
                val cfg = Protobuf(c.getBlob(3) ?: ByteArray(0))
                templates.getOrPut(c.getLong(0)) { ArrayList() } += c.getInt(1) to CardTemplate(c.getString(2), cfg.string(1), cfg.string(2))
            }
        }
        val out = HashMap<Long, AnkiModel>()
        db.rawQuery("SELECT id, name, config FROM notetypes", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val cfg = Protobuf(c.getBlob(2) ?: ByteArray(0))
                out[id] = AnkiModel(
                    id,
                    NoteType(
                        id = "anki-$id",
                        name = c.getString(1),
                        fields = fields[id].orEmpty().sortedBy { it.first }.map { it.second },
                        templates = templates[id].orEmpty().sortedBy { it.first }.map { it.second },
                        kind = if (cfg.long(1) == 1L) NoteKind.CLOZE else NoteKind.STANDARD,
                        css = cfg.string(3),
                    ),
                )
            }
        }
        return out
    }

    private fun legacyDecks(json: String): Map<Long, AnkiDeck> {
        if (json.isBlank()) return emptyMap()
        val o = JSONObject(json)
        return o.keys().asSequence().associate { key ->
            val d = o.getJSONObject(key)
            key.toLong() to AnkiDeck(key.toLong(), AnkiText.deckName(d.optString("name")), d.optLong("conf").takeIf { d.has("conf") })
        }
    }

    private fun newDecks(db: SQLiteDatabase): Map<Long, AnkiDeck> {
        val out = HashMap<Long, AnkiDeck>()
        db.rawQuery("SELECT id, name FROM decks", null).use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = AnkiDeck(c.getLong(0), AnkiText.deckName(c.getString(1)), null)
        }
        return out
    }

    private fun legacyDeckConfs(json: String): Map<Long, AnkiDeckConf> {
        if (json.isBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(json)
            o.keys().asSequence().associate { key ->
                val c = o.getJSONObject(key)
                key.toLong() to AnkiDeckConf(
                    c.optJSONObject("new")?.optInt("perDay", -1)?.takeIf { it >= 0 },
                    c.optJSONObject("rev")?.optInt("perDay", -1)?.takeIf { it >= 0 },
                )
            }
        }.getOrDefault(emptyMap())
    }
}
