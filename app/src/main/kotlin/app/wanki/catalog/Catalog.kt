package app.wanki.catalog

import android.content.res.AssetManager
import app.wanki.data.AnswerMode
import org.json.JSONObject

/** A ready-made deck bundled in assets/decks (built by tools/decks/build.py). */
data class CatalogDeck(
    val id: String,
    /** Where it goes in the deck tree, e.g. Японский › Кандзи › 1 класс. */
    val path: List<String>,
    val language: String,
    val mode: AnswerMode,
    val count: Int,
    val file: String,
    val description: String,
    val source: String,
    val license: String,
    /** Changes whenever the cards change; an installed deck with another version can be updated. */
    val version: String,
)

data class CatalogRow(val key: String, val front: String, val back: String, val tags: String)

object CatalogFormat {
    fun parseCatalog(json: String): List<CatalogDeck> {
        val decks = JSONObject(json).getJSONArray("decks")
        return (0 until decks.length()).map { i ->
            val d = decks.getJSONObject(i)
            val path = d.getJSONArray("path")
            CatalogDeck(
                id = d.getString("id"),
                path = (0 until path.length()).map { path.getString(it) },
                language = d.getString("language"),
                mode = runCatching { AnswerMode.valueOf(d.getString("mode")) }.getOrDefault(AnswerMode.HANDWRITING),
                count = d.getInt("count"),
                file = d.getString("file"),
                description = d.optString("description"),
                source = d.optString("source"),
                license = d.optString("license"),
                version = d.getString("version"),
            )
        }
    }

    /** `key\tfront\tback\ttags` per line; \n, \t and \\ are escaped inside fields. */
    fun parseRows(tsv: String): List<CatalogRow> =
        tsv.lineSequence().filter { it.isNotEmpty() }.map { line ->
            val f = line.split('\t').map(::unescape)
            CatalogRow(f[0], f.getOrElse(1) { "" }, f.getOrElse(2) { "" }, f.getOrElse(3) { "" })
        }.toList()

    fun unescape(s: String): String {
        if ('\\' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    else -> out.append(n)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}

/** The bundled catalog, read from assets. */
class Catalog(private val assets: AssetManager) {
    val decks: List<CatalogDeck> by lazy { CatalogFormat.parseCatalog(read("decks/catalog.json")) }

    fun rows(deck: CatalogDeck): List<CatalogRow> = CatalogFormat.parseRows(read("decks/${deck.file}"))

    private fun read(path: String): String = assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
}
