package app.wanki.importer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import app.wanki.anki.AnkiCard
import app.wanki.anki.AnkiCollection
import app.wanki.anki.AnkiCollectionReader
import app.wanki.anki.AnkiNote
import app.wanki.anki.AnkiTemplate
import app.wanki.anki.CardHtml
import app.wanki.anki.NoteKind
import app.wanki.anki.NoteType
import app.wanki.anki.Protobuf
import app.wanki.anki.ScheduleReplay
import app.wanki.data.AnswerMode
import app.wanki.data.CardEntity
import app.wanki.data.DeckEntity
import app.wanki.data.NoteEntity
import app.wanki.data.Repository
import app.wanki.data.ReviewLogEntity
import app.wanki.media.MediaStore
import app.wanki.review.WritingLayout
import com.github.luben.zstd.ZstdInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Imports Anki packages (.apkg / .colpkg), legacy (anki2/anki21) and current (anki21b, zstd).
 * Keeps everything: note types with their templates, all fields, nested decks with their daily
 * limits, media, and the schedule — each card's review history is replayed through FSRS, so it
 * continues where Anki left off. Importing the same package again adds only what is new.
 */
class AnkiImporter(private val context: Context, private val repository: Repository, private val media: MediaStore) {
    data class Result(val decks: Int, val notes: Int, val cards: Int, val reviews: Int, val media: Int, val skipped: Int)

    suspend fun import(uri: Uri, onProgress: (String) -> Unit = {}): Result = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "apkg-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            onProgress("Распаковка…")
            val pkg = unpack(uri, dir)
            onProgress("Чтение коллекции…")
            val collection = SQLiteDatabase.openDatabase(pkg.collection.path, null, SQLiteDatabase.OPEN_READONLY).use { AnkiCollectionReader.read(it) }
            onProgress("Медиафайлы…")
            val mediaCount = copyMedia(pkg, dir)
            write(collection, mediaCount, onProgress)
        } finally {
            dir.deleteRecursively()
        }
    }

    private class Package(val collection: File, val zstd: Boolean, val mediaList: File?)

    /** Extracts the newest collection format present (newer exports also contain a dummy anki2) and the media. */
    private fun unpack(uri: Uri, dir: File): Package {
        val input = context.contentResolver.openInputStream(uri) ?: error("Не удалось открыть файл")
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = File(e.name).name
                if (e.isDirectory || name.isEmpty()) continue
                File(dir, "pkg-$name").outputStream().use { zip.copyTo(it) }
            }
        }
        File(dir, "pkg-collection.anki21b").takeIf { it.exists() }?.let { packed ->
            val out = File(dir, "collection.sqlite")
            ZstdInputStream(packed.inputStream().buffered()).use { z -> out.outputStream().use { z.copyTo(it) } }
            return Package(out, zstd = true, mediaList = File(dir, "pkg-media").takeIf { it.exists() })
        }
        val legacy = listOf("pkg-collection.anki21", "pkg-collection.anki2").map { File(dir, it) }.firstOrNull { it.exists() }
            ?: error("В файле нет коллекции Anki")
        return Package(legacy, zstd = false, mediaList = File(dir, "pkg-media").takeIf { it.exists() })
    }

    /** Media list: legacy JSON {"0": "name"}; current zstd + protobuf MediaEntries (index = zip entry name). */
    private fun copyMedia(pkg: Package, dir: File): Int {
        val list = pkg.mediaList ?: return 0
        val names: Map<String, String> = if (pkg.zstd) {
            val bytes = ZstdInputStream(list.inputStream().buffered()).use { it.readBytes() }
            Protobuf(bytes).all(1).mapIndexed { i, e ->
                val m = e.message
                (m.first(255)?.varint?.toString() ?: i.toString()) to m.string(1)
            }.toMap()
        } else {
            runCatching { JSONObject(list.readText()) }.getOrNull()?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } }.orEmpty()
        }
        var count = 0
        for ((index, name) in names) {
            val f = File(dir, "pkg-$index").takeIf { it.exists() } ?: continue
            runCatching {
                if (pkg.zstd) ZstdInputStream(f.inputStream().buffered()).use { media.save(name, it) } else f.inputStream().use { media.save(name, it) }
                count++
            }
        }
        return count
    }

    private suspend fun write(c: AnkiCollection, mediaCount: Int, onProgress: (String) -> Unit): Result {
        val types = c.models.values.associate { it.id to it.type }
        repository.saveNoteTypes(types.values.toList())
        val notesById = c.notes.associateBy { it.id }
        val scheduler = repository.scheduler(0.9)
        val now = System.currentTimeMillis()
        val tomorrow = Repository.startOfTomorrow()
        val positionBase = repository.maxCardPosition() + 1

        // deck per Anki deck (filtered decks: the card's home deck), with language and default mode
        // guessed from what is written in it
        val usable = c.cards.filter { it.nid in notesById && types.containsKey(notesById.getValue(it.nid).mid) }
        val homeDeck = { card: AnkiCard -> if (card.odid != 0L) card.odid else card.did }
        val answers = usable.associate { card ->
            val note = notesById.getValue(card.nid)
            card.id to writeAnswer(types.getValue(note.mid), note, card.ord)
        }
        val decks = HashMap<Long, DeckEntity>()
        for ((did, cards) in usable.groupBy(homeDeck)) {
            val deck = c.decks[did]
            val sample = cards.take(200).mapNotNull { answers[it.id] }
            val cells = sample.count { WritingLayout.forAnswer(it) is WritingLayout.Cells }
            var d = repository.deckByPath(
                (deck?.name ?: "Импорт из Anki").split("::"),
                AnkiText.guessLanguage(sample),
                if (sample.isNotEmpty() && cells * 2 >= sample.size) AnswerMode.HANDWRITING else AnswerMode.CLASSIC,
            )
            deck?.confId?.let { c.deckConfs[it] }?.let { conf ->
                d = d.copy(newPerDay = conf.newPerDay ?: d.newPerDay, reviewsPerDay = conf.reviewsPerDay ?: d.reviewsPerDay)
                repository.updateDeck(d)
            }
            decks[did] = d
        }

        var cardsAdded = 0
        var reviews = 0
        var notesUpdated = 0
        val writtenNotes = HashSet<Long>()
        usable.chunked(500).forEachIndexed { batch, chunk ->
            onProgress("Карточки: ${batch * 500 + chunk.size} из ${usable.size}")
            val notes = ArrayList<NoteEntity>()
            val cards = ArrayList<CardEntity>()
            val logs = ArrayList<ReviewLogEntity>()
            for (card in chunk) {
                val note = notesById.getValue(card.nid)
                val type = types.getValue(note.mid)
                val noteId = "anki-${note.guid}"
                if (writtenNotes.add(note.id)) {
                    notes += NoteEntity(id = noteId, noteTypeId = type.id, fields = note.fields, tags = note.tags, updatedAt = note.modSec * 1000)
                }
                val cardId = "$noteId:${card.ord}"
                val replay = ScheduleReplay.replay(c.revlog[card.id].orEmpty(), scheduler, card.id, c.schedulerV1)
                val s = when {
                    replay.logs.isNotEmpty() -> replay.state
                    card.type == 2 -> ScheduleReplay.fromInterval(card.ivl, card.factor, card.due, c.createdSec, card.reps, card.lapses)
                    else -> null
                }
                val answer = answers[card.id].orEmpty()
                val mode = if (WritingLayout.forAnswer(answer) is WritingLayout.Cells) AnswerMode.HANDWRITING else AnswerMode.CLASSIC
                var entity = CardEntity(
                    id = cardId, noteId = noteId, deckId = decks.getValue(homeDeck(card)).id, templateOrd = card.ord,
                    answerMode = mode.name, writeFieldOrd = type.writeFieldOrd(card.ord), seed = card.id,
                    updatedAt = now, suspended = card.queue == -1, buriedUntil = if (card.queue == -2 || card.queue == -3) tomorrow else 0,
                    // new cards keep Anki's order
                    position = positionBase + if (card.type == 0) card.due else 0,
                )
                if (s != null) {
                    entity = entity.copy(
                        state = s.state.name, step = s.step, stability = s.stability, difficulty = s.difficulty,
                        due = s.due, lastReview = s.lastReview, reps = s.reps, lapses = s.lapses,
                    )
                }
                cards += entity
                replay.logs.forEach { l ->
                    logs += ReviewLogEntity(
                        id = "$cardId@${l.time}", cardId = cardId, reviewedAt = l.time, rating = l.rating.value,
                        mode = AnswerMode.CLASSIC.name, durationMs = l.durationMs, cardState = l.cardState,
                    )
                }
            }
            val w = repository.writeImported(notes, cards, logs)
            cardsAdded += w.cardsAdded
            reviews += w.reviews
            notesUpdated += w.notesUpdated
        }
        return Result(decks.size, writtenNotes.size, cardsAdded, reviews, mediaCount, c.cards.size - usable.size)
    }

    /** The text written for a card: the answer field, or the cloze deletions of this card. */
    private fun writeAnswer(type: NoteType, note: AnkiNote, ord: Int): String {
        val values = note.fields.split(NoteEntity.FIELD_SEPARATOR)
        val source = values.getOrElse(type.writeFieldOrd(ord)) { "" }
        return if (type.kind == NoteKind.CLOZE) AnkiTemplate.clozeAnswers(source, ord + 1).joinToString(" ") { CardHtml.plain(it) }
        else CardHtml.plain(source)
    }
}
