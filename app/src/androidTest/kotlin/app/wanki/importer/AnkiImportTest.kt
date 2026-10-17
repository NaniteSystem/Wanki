package app.wanki.importer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.wanki.anki.CardBlock
import app.wanki.data.AnswerMode
import app.wanki.data.Repository
import app.wanki.data.STATE_NEW
import app.wanki.data.WankiDatabase
import app.wanki.media.MediaStore
import com.github.luben.zstd.ZstdOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds real .apkg files (legacy and current schema) and imports them. */
@RunWith(AndroidJUnit4::class)
class AnkiImportTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: WankiDatabase
    private lateinit var repo: Repository
    private lateinit var media: MediaStore
    private val work = File(context.cacheDir, "anki-import-test")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, WankiDatabase::class.java).build()
        repo = Repository(db)
        media = MediaStore(context)
        work.deleteRecursively()
        work.mkdirs()
    }

    @After
    fun tearDown() {
        db.close()
        work.deleteRecursively()
        File(media.dir, "dog.jpg").delete()
        File(media.dir, "cat.png").delete()
    }

    private val t0 = 1_700_000_000_000L
    private val day = 86_400_000L
    private val sep = "\u001f"

    private fun createCommon(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE notes (id INTEGER, guid TEXT, mid INTEGER, mod INTEGER, tags TEXT, flds TEXT)")
        db.execSQL("CREATE TABLE cards (id INTEGER, nid INTEGER, did INTEGER, ord INTEGER, type INTEGER, queue INTEGER, due INTEGER, ivl INTEGER, factor INTEGER, reps INTEGER, lapses INTEGER, odid INTEGER)")
        db.execSQL("CREATE TABLE revlog (id INTEGER, cid INTEGER, ease INTEGER, type INTEGER, ivl INTEGER, time INTEGER)")
    }

    /** Legacy (schema 11): models, decks and limits as JSON in col. */
    private fun legacyApkg(): File {
        val col = File(work, "legacy.anki2")
        SQLiteDatabase.openOrCreateDatabase(col, null).use { db ->
            db.execSQL("CREATE TABLE col (crt INTEGER, models TEXT, decks TEXT, dconf TEXT, conf TEXT)")
            createCommon(db)
            val models = JSONObject(
                """{"100": {"name": "Basic (and reversed)", "type": 0,
                  "flds": [{"name": "Front", "ord": 0}, {"name": "Back", "ord": 1}],
                  "tmpls": [{"name": "Card 1", "ord": 0, "qfmt": "{{Front}}<img src=\"dog.jpg\">", "afmt": "{{FrontSide}}<hr id=answer>{{Back}}"},
                            {"name": "Card 2", "ord": 1, "qfmt": "{{Back}}", "afmt": "{{FrontSide}}<hr id=answer>{{Front}}"}]},
                 "200": {"name": "Cloze", "type": 1,
                  "flds": [{"name": "Text", "ord": 0}, {"name": "Extra", "ord": 1}],
                  "tmpls": [{"name": "Cloze", "ord": 0, "qfmt": "{{cloze:Text}}", "afmt": "{{cloze:Text}}<br>{{Extra}}"}]}}""",
            )
            val decks = JSONObject("""{"1": {"name": "Default", "conf": 1}, "10": {"name": "Japanese::Words", "conf": 2}}""")
            val dconf = JSONObject("""{"1": {"new": {"perDay": 20}, "rev": {"perDay": 200}}, "2": {"new": {"perDay": 15}, "rev": {"perDay": 150}}}""")
            db.execSQL("INSERT INTO col VALUES (?, ?, ?, ?, ?)", arrayOf<Any>(1_600_000_000L, models.toString(), decks.toString(), dconf.toString(), """{"schedVer": 2}"""))
            db.execSQL("INSERT INTO notes VALUES (1, 'g1', 100, 1700000000, ' jp ', ?)", arrayOf("dog${sep}犬"))
            db.execSQL("INSERT INTO notes VALUES (2, 'g2', 200, 1700000000, '', ?)", arrayOf("{{c1::東京}}は{{c2::日本}}の首都${sep}столица"))
            // reviewed with history; new; suspended new; review without history
            db.execSQL("INSERT INTO cards VALUES (11, 1, 10, 0, 2, 2, 400, 10, 2500, 3, 0, 0)")
            db.execSQL("INSERT INTO cards VALUES (12, 1, 10, 1, 0, 0, 5, 0, 0, 0, 0, 0)")
            db.execSQL("INSERT INTO cards VALUES (21, 2, 10, 0, 0, -1, 6, 0, 0, 0, 0, 0)")
            db.execSQL("INSERT INTO cards VALUES (22, 2, 10, 1, 2, 2, 450, 20, 2300, 4, 1, 0)")
            db.execSQL("INSERT INTO revlog VALUES ($t0, 11, 3, 0, -600, 5000)")
            db.execSQL("INSERT INTO revlog VALUES (${t0 + 600_000}, 11, 3, 0, 1, 4000)")
            db.execSQL("INSERT INTO revlog VALUES (${t0 + day}, 11, 3, 1, 10, 3000)")
        }
        return zip("legacy.apkg", "collection.anki2" to col.readBytes(), "media" to """{"0": "dog.jpg"}""".toByteArray(), "0" to DOG)
    }

    /** Current schema (anki21b): note types in tables with protobuf configs, zstd everywhere. */
    private fun currentApkg(): File {
        val col = File(work, "current.anki2")
        SQLiteDatabase.openOrCreateDatabase(col, null).use { db ->
            db.execSQL("CREATE TABLE col (crt INTEGER, models TEXT, decks TEXT, dconf TEXT, conf TEXT)")
            db.execSQL("CREATE TABLE notetypes (id INTEGER, name TEXT, config BLOB)")
            db.execSQL("CREATE TABLE fields (ntid INTEGER, ord INTEGER, name TEXT)")
            db.execSQL("CREATE TABLE templates (ntid INTEGER, ord INTEGER, name TEXT, config BLOB)")
            db.execSQL("CREATE TABLE decks (id INTEGER, name TEXT)")
            db.execSQL("CREATE TABLE config (KEY TEXT, val BLOB)")
            createCommon(db)
            db.execSQL("INSERT INTO col VALUES (1600000000, '', '', '', '')")
            db.execSQL("INSERT INTO notetypes VALUES (300, 'Kanji', ?)", arrayOf<Any>(proto(1 to 0L, 3 to ".card {}")))
            db.execSQL("INSERT INTO fields VALUES (300, 0, 'Meaning')")
            db.execSQL("INSERT INTO fields VALUES (300, 1, 'Kanji')")
            db.execSQL("INSERT INTO templates VALUES (300, 0, 'Write', ?)", arrayOf<Any>(proto(1 to "{{Meaning}}<img src=\"cat.png\">", 2 to "{{FrontSide}}<hr id=answer>{{Kanji}}")))
            db.execSQL("INSERT INTO decks VALUES (30, ?)", arrayOf("Kanji${sep}N5"))
            db.execSQL("INSERT INTO notes VALUES (3, 'g3', 300, 1700000000, '', ?)", arrayOf("кошка${sep}猫"))
            db.execSQL("INSERT INTO cards VALUES (31, 3, 30, 0, 0, 0, 1, 0, 0, 0, 0, 0)")
        }
        val mediaList = proto(1 to proto(1 to "cat.png", 2 to CAT.size.toLong()))
        return zip("current.apkg", "collection.anki2" to ByteArray(0), "collection.anki21b" to zstd(col.readBytes()), "media" to zstd(mediaList), "0" to zstd(CAT))
    }

    @Test
    fun legacyPackageKeepsTemplatesScheduleLimitsAndMedia() = runBlocking {
        val r = AnkiImporter(context, repo, media).import(Uri.fromFile(legacyApkg()))
        assertEquals(1, r.decks)
        assertEquals(2, r.notes)
        assertEquals(4, r.cards)
        assertEquals(3, r.reviews)
        assertEquals(1, r.media)

        val tree = repo.deckTree()
        val deck = tree.byId.values.single { tree.path(it.id) == "Japanese::Words" }
        assertEquals(15, deck.newPerDay)
        assertEquals(150, deck.reviewsPerDay)

        // Card 1: question "dog" with a picture, answer 犬 written by hand; history replayed
        val forward = repo.card("anki-g1:0")!!
        assertEquals("REVIEW", forward.card.state)
        assertEquals(3, forward.card.reps)
        assertEquals(listOf(STATE_NEW, "LEARNING", "REVIEW"), db.reviews().forCard(forward.card.id).map { it.cardState })
        assertEquals(AnswerMode.HANDWRITING.name, forward.card.answerMode)
        val fc = repo.content(forward)
        assertEquals(listOf(CardBlock.Text("dog"), CardBlock.Image("dog.jpg")), fc.question)
        assertEquals("犬", fc.writeAnswer)
        assertTrue(media.file("dog.jpg") != null)

        // Card 2 (reversed): asks 犬, the answer "dog" is typed classically
        val reverse = repo.card("anki-g1:1")!!
        assertTrue(reverse.card.isNew)
        assertEquals(0, reverse.card.writeFieldOrd)
        assertEquals("dog", repo.content(reverse).writeAnswer)
        assertEquals(AnswerMode.CLASSIC.name, reverse.card.answerMode)

        // Cloze: c1 suspended, c2 a review card without history
        val c1 = repo.card("anki-g2:0")!!
        assertTrue(c1.card.suspended)
        assertEquals("東京", repo.content(c1).writeAnswer)
        assertEquals(CardBlock.Text("[…]は日本の首都"), repo.content(c1).question.first())
        val c2 = repo.card("anki-g2:1")!!
        assertEquals("REVIEW", c2.card.state)
        assertEquals(20.0, c2.card.stability!!, 0.0)
        assertEquals("日本", repo.content(c2).writeAnswer)

        // importing again adds nothing and keeps the history single
        val again = AnkiImporter(context, repo, media).import(Uri.fromFile(legacyApkg()))
        assertEquals(0, again.cards)
        assertEquals(0, again.reviews)
        assertEquals(3, db.reviews().forCard(forward.card.id).size)
    }

    @Test
    fun currentPackageWithZstdAndProtobuf() = runBlocking {
        val r = AnkiImporter(context, repo, media).import(Uri.fromFile(currentApkg()))
        assertEquals(1, r.cards)
        assertEquals(1, r.media)
        val card = repo.card("anki-g3:0")!!
        val tree = repo.deckTree()
        assertEquals("Kanji::N5", tree.path(card.card.deckId))
        assertEquals("Kanji", repo.noteType(card.note.noteTypeId).name)
        val c = repo.content(card)
        assertEquals(listOf(CardBlock.Text("кошка"), CardBlock.Image("cat.png")), c.question)
        assertEquals("猫", c.writeAnswer)
        assertArrayEquals(CAT, media.file("cat.png")!!.readBytes())
    }

    private fun zip(name: String, vararg entries: Pair<String, ByteArray>): File {
        val f = File(work, name)
        ZipOutputStream(f.outputStream()).use { z ->
            for ((n, bytes) in entries) {
                z.putNextEntry(ZipEntry(n))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return f
    }

    private fun zstd(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> ZstdOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private fun proto(vararg fields: Pair<Int, Any>): ByteArray {
        val out = ByteArrayOutputStream()
        fun varint(v: Long) {
            var x = v
            while (x and 0x7F.inv().toLong() != 0L) { out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }
            out.write(x.toInt())
        }
        for ((n, v) in fields) when (v) {
            is Long -> { varint((n shl 3).toLong()); varint(v) }
            is String -> { val b = v.toByteArray(); varint(((n shl 3) or 2).toLong()); varint(b.size.toLong()); out.write(b) }
            is ByteArray -> { varint(((n shl 3) or 2).toLong()); varint(v.size.toLong()); out.write(v) }
        }
        return out.toByteArray()
    }

    private companion object {
        val DOG = byteArrayOf(1, 2, 3, 4)
        val CAT = ByteArray(300) { (it * 7).toByte() }
    }
}
