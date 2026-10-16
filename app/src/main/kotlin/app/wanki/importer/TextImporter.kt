package app.wanki.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.wanki.data.AnswerMode
import app.wanki.data.Repository
import app.wanki.review.WritingLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Imports a CSV/TSV card list ([DelimitedText]) into a new deck named after the file. */
class TextImporter(private val context: Context, private val repository: Repository) {
    data class Result(val deck: String, val cards: Int)

    suspend fun import(uri: Uri): Result = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Не удалось открыть файл")
        val rows = DelimitedText.parse(text).filter { it.front.isNotBlank() && it.back.isNotBlank() }
        if (rows.isEmpty()) error("В файле не найдено строк вида «вопрос<Tab>ответ»")
        val name = displayName(uri)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Импорт из текста"
        val cells = { r: DelimitedText.Row -> WritingLayout.forAnswer(r.back) is WritingLayout.Cells }
        val handwriting = rows.count(cells)
        val deck = repository.createDeck(
            name,
            AnkiText.guessLanguage(rows.take(200).map { it.back }),
            if (handwriting * 2 >= rows.size) AnswerMode.HANDWRITING else AnswerMode.CLASSIC,
        )
        repository.addCards(deck.id, rows.map { r ->
            Repository.NewCard(r.front, r.back, if (cells(r)) AnswerMode.HANDWRITING else AnswerMode.CLASSIC, r.tags)
        })
        Result(deck.name, rows.size)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
