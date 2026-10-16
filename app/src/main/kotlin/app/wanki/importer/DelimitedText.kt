package app.wanki.importer

/**
 * Plain-text card lists: one card per line, "question<sep>answer[<sep>tags]". The separator is
 * a tab, semicolon or comma (detected), quoted fields follow RFC 4180 (may contain separators
 * and line breaks). Anki's "Notes in plain text" export headers (#separator:, #html:,
 * #tags column:) are understood; other lines starting with # are skipped.
 */
object DelimitedText {
    data class Row(val front: String, val back: String, val tags: String)

    fun parse(text: String): List<Row> {
        val body = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val headers = body.lineSequence().takeWhile { it.startsWith("#") }.toList()
        var sep: Char? = null
        var html = false
        var tagsColumn: Int? = null
        for (h in headers) {
            val (key, value) = h.removePrefix("#").split(":", limit = 2).let { it[0].trim().lowercase() to it.getOrElse(1) { "" }.trim() }
            when (key) {
                "separator" -> sep = when (value.lowercase()) {
                    "tab" -> '\t'; "comma" -> ','; "semicolon" -> ';'; "pipe" -> '|'; "space" -> ' '
                    else -> value.firstOrNull()
                }
                "html" -> html = value.equals("true", ignoreCase = true)
                "tags column" -> tagsColumn = value.toIntOrNull()
            }
        }
        val content = body.lineSequence().dropWhile { it.startsWith("#") }.joinToString("\n")
        val separator = sep ?: detectSeparator(content.lineSequence().filter { it.isNotBlank() }.take(20).toList())
        return records(content, separator).mapNotNull { fields ->
            val clean = fields.map { if (html) AnkiText.clean(it) else it.trim() }
            val tagIndex = tagsColumn?.minus(1)
            val values = clean.filterIndexed { i, _ -> i != tagIndex }
            val front = values.getOrElse(0) { "" }
            val back = values.getOrElse(1) { "" }
            if (front.isBlank() && back.isBlank()) return@mapNotNull null
            val tags = tagIndex?.let { clean.getOrNull(it) } ?: values.getOrElse(2) { "" }
            Row(front, back, tags.trim())
        }
    }

    /** The candidate that splits most lines into the same number (≥ 2) of fields. */
    fun detectSeparator(lines: List<String>): Char {
        if (lines.isEmpty()) return '\t'
        return listOf('\t', ';', ',').maxBy { c ->
            val counts = lines.map { l -> records(l, c).firstOrNull()?.size ?: 1 }
            val common = counts.groupingBy { it }.eachCount().maxByOrNull { it.value }
            if (common == null || common.key < 2) 0 else common.value * 10 + if (c == '\t') 1 else 0
        }
    }

    /** RFC 4180 records: quotes wrap fields, "" is a quote inside one. */
    fun records(text: String, sep: Char): List<List<String>> {
        val out = ArrayList<List<String>>()
        var fields = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        fun endField() { fields += cur.toString(); cur.clear() }
        fun endRecord() {
            endField()
            if (!(fields.size == 1 && fields[0].isEmpty())) out += fields
            fields = ArrayList()
        }
        while (i < text.length) {
            val ch = text[i]
            when {
                quoted && ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cur.append('"'); i++ }
                quoted && ch == '"' -> quoted = false
                quoted -> cur.append(ch)
                ch == '"' && cur.isBlank() -> { cur.clear(); quoted = true }
                ch == sep -> endField()
                ch == '\n' -> endRecord()
                else -> cur.append(ch)
            }
            i++
        }
        if (cur.isNotEmpty() || fields.isNotEmpty()) endRecord()
        return out
    }
}
