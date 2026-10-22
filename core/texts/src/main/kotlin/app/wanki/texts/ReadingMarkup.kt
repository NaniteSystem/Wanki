package app.wanki.texts

/** A reading (and other spellings) given for a written form, e.g. from `{月|がつ}` markup. */
data class ReadingOverride(val reading: String, val forms: List<String> = emptyList())

/** [plain] is the text with the markup removed; [readings] maps each written form to its override. */
data class Markup(val plain: String, val readings: Map<String, ReadingOverride>)

/** The `{display|reading|alt1|alt2…}` markup; an empty reading means the display itself is the reading. */
object ReadingMarkup {
    private val any = Regex("\\{([^}]*)\\}")
    private val reading = Regex("\\{[^{}|]*\\|[^{}]*\\}")

    /** True when [s] has at least one `{x|y}` group; `{x}` and plain braces in prose do not count. */
    fun has(s: String): Boolean = reading.containsMatchIn(s)

    fun parse(s: String): Markup {
        val readings = LinkedHashMap<String, ReadingOverride>()
        val plain = any.replace(s) { m ->
            val parts = m.groupValues[1].split("|")
            val display = parts[0]
            if (display.isNotEmpty()) {
                val r = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: display
                val forms = parts.drop(2).filter { it.isNotEmpty() && it != display }.distinct()
                readings.putIfAbsent(display, ReadingOverride(r, forms))
            }
            display
        }
        return Markup(plain, readings)
    }
}
