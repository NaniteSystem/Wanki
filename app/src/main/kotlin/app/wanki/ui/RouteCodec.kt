package app.wanki.ui

import app.wanki.ui.texts.StudyKind
import app.wanki.ui.texts.StudyRequest

/**
 * Text form of a [Route] for saved instance state: `Name|field|field`. A field is escaped with `\`
 * (`\|`, `\\`); a nullable field is empty for null and `=value` otherwise; a list field holds `=item`
 * entries separated by `,` (each item escaped for `,` before the field is escaped for `|`).
 */
object RouteCodec {
    private const val SEP = '|'
    private const val ITEM_SEP = ','

    fun encode(route: Route): String = when (route) {
        Route.Decks -> name("Decks")
        is Route.Deck -> name("Deck", str(route.id))
        is Route.DeckOptions -> name("DeckOptions", str(route.id))
        is Route.Review -> name("Review", str(route.deckId))
        Route.Browser -> name("Browser")
        Route.Catalog -> name("Catalog")
        is Route.CardEditor -> name("CardEditor", str(route.id))
        is Route.Practice -> name("Practice", opt(route.glyph), str(route.language))
        Route.Notebooks -> name("Notebooks")
        is Route.Notebook -> name("Notebook", str(route.id), opt(route.pageId), opt(route.highlight))
        is Route.PageGrid -> name("PageGrid", str(route.notebookId))
        Route.Notes -> name("Notes")
        is Route.Note -> name("Note", opt(route.id))
        Route.Diary -> name("Diary")
        is Route.DiaryDay -> name("DiaryDay", route.epochDay.toString())
        Route.Boards -> name("Boards")
        is Route.Board -> name("Board", str(route.id))
        Route.Texts -> name("Texts")
        is Route.TextEditor -> name("TextEditor", opt(route.id))
        is Route.TextDetail -> name("TextDetail", str(route.id))
        is Route.TextReadings -> name("TextReadings", str(route.id))
        is Route.TextStudy -> name(
            "TextStudy", route.request.kind.name, opt(route.request.textId), list(route.request.pieceIds),
        )
        Route.Settings -> name("Settings")
        Route.Trash -> name("Trash")
        Route.Stats -> name("Stats")
        Route.WritingLab -> name("WritingLab")
    }

    /** The route for [s], or null when it is unknown or malformed. */
    fun decode(s: String): Route? = try {
        val parts = split(s, SEP)
        val f = parts.drop(1)
        when (parts[0]) {
            "Decks" -> Route.Decks
            "Deck" -> Route.Deck(unStr(f[0]))
            "DeckOptions" -> Route.DeckOptions(unStr(f[0]))
            "Review" -> Route.Review(unStr(f[0]))
            "Browser" -> Route.Browser
            "Catalog" -> Route.Catalog
            "CardEditor" -> Route.CardEditor(unStr(f[0]))
            "Practice" -> Route.Practice(unOpt(f[0]), unStr(f[1]))
            "Notebooks" -> Route.Notebooks
            "Notebook" -> Route.Notebook(unStr(f[0]), unOpt(f[1]), f.getOrNull(2)?.let(::unOpt))
            "PageGrid" -> Route.PageGrid(unStr(f[0]))
            "Notes" -> Route.Notes
            "Note" -> Route.Note(unOpt(f[0]))
            "Diary" -> Route.Diary
            "DiaryDay" -> Route.DiaryDay(f[0].toLong())
            "Boards" -> Route.Boards
            "Board" -> Route.Board(unStr(f[0]))
            "Texts" -> Route.Texts
            "TextEditor" -> Route.TextEditor(unOpt(f[0]))
            "TextDetail" -> Route.TextDetail(unStr(f[0]))
            "TextReadings" -> Route.TextReadings(unStr(f[0]))
            "TextStudy" -> Route.TextStudy(StudyRequest(StudyKind.valueOf(f[0]), unOpt(f[1]), unList(f[2])))
            "Settings" -> Route.Settings
            "Trash" -> Route.Trash
            "Stats" -> Route.Stats
            "WritingLab" -> Route.WritingLab
            else -> null
        }
    } catch (e: RuntimeException) {
        null
    }

    private fun name(n: String, vararg fields: String) = (listOf(n) + fields.map { escape(it, SEP) }).joinToString(SEP.toString())

    // Plain strings are stored as is (escaped by [name]); optional ones get an "=" marker.
    private fun str(v: String) = v
    private fun unStr(v: String) = v
    private fun opt(v: String?) = if (v == null) "" else "=$v"
    private fun unOpt(v: String): String? = if (v.isEmpty()) null else if (v[0] == '=') v.substring(1) else throw IllegalArgumentException(v)
    private fun list(v: List<String>) = v.joinToString(ITEM_SEP.toString()) { "=" + escape(it, ITEM_SEP) }
    private fun unList(v: String): List<String> =
        if (v.isEmpty()) emptyList() else split(v, ITEM_SEP).map { require(it.startsWith("=")); it.substring(1) }

    private fun escape(s: String, sep: Char): String = buildString {
        for (c in s) { if (c == '\\' || c == sep) append('\\'); append(c) }
    }

    /** Splits on unescaped [sep] and removes the escapes. */
    private fun split(s: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' -> { require(i + 1 < s.length); cur.append(s[i + 1]); i++ }
                c == sep -> { out.add(cur.toString()); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out
    }
}
