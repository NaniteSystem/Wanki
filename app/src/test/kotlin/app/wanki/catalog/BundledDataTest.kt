package app.wanki.catalog

import app.wanki.review.WritingLayout
import app.wanki.scoring.kanjivg.KanjiVg
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Checks the data in assets (built by tools/decks/build.py) as the app will read it. */
class BundledDataTest {
    private val assets = File("src/main/assets")

    @Test
    fun everyKanjiVgReferenceParsesAsItsCharacter() {
        val files = File(assets, "kanjivg").listFiles { f -> f.name.endsWith(".svg") }.orEmpty()
        assertTrue(files.size > 6000, "expected the full KanjiVG set, found ${files.size}")
        val bad = files.mapNotNull { f ->
            val expected = String(Character.toChars(f.name.removeSuffix(".svg").toInt(16)))
            val ref = runCatching { KanjiVg.parse(f.readText()) }.getOrElse { return@mapNotNull "${f.name}: ${it.message}" }
            when {
                ref.char != expected -> "${f.name}: parsed as ${ref.char}"
                ref.strokes.isEmpty() || ref.strokes.any { it.points.size < 2 } -> "${f.name}: empty stroke"
                else -> null
            }
        }
        if (bad.isNotEmpty()) fail(bad.take(20).joinToString("\n"))
    }

    @Test
    fun everyDeckMatchesItsCatalogEntry() {
        val decks = CatalogFormat.parseCatalog(File(assets, "decks/catalog.json").readText())
        assertTrue(decks.size >= 20)
        assertEquals(decks.size, decks.map { it.id }.toSet().size, "deck ids must be unique")
        for (d in decks) {
            val rows = CatalogFormat.parseRows(File(assets, "decks/${d.file}").readText())
            assertEquals(d.count, rows.size, d.id)
            assertEquals(rows.size, rows.map { it.key }.toSet().size, "${d.id}: keys must be unique")
            for (r in rows) {
                assertTrue(r.front.isNotBlank() && r.back.isNotBlank(), "${d.id}/${r.key}: empty side")
                assertTrue('\n' !in r.back && '\t' !in r.back, "${d.id}/${r.key}: answer must be one line")
            }
        }
    }

    @Test
    fun japaneseAnswersAreWrittenInCellsWithReferences() {
        val refs = File(assets, "kanjivg").list().orEmpty().map { it.removeSuffix(".svg") }.toSet()
        val decks = CatalogFormat.parseCatalog(File(assets, "decks/catalog.json").readText())
        for (d in decks.filter { it.id.startsWith("ja-kana") || it.id.startsWith("ja-kanji") }) {
            for (r in CatalogFormat.parseRows(File(assets, "decks/${d.file}").readText())) {
                val layout = WritingLayout.forAnswer(r.back)
                assertTrue(layout is WritingLayout.Cells, "${d.id}/${r.key}: not written in cells")
                for (c in layout.chars) assertTrue("%05x".format(c.codePointAt(0)) in refs, "${d.id}: no reference for $c")
            }
        }
    }
}
