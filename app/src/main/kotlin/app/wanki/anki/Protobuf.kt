package app.wanki.anki

/**
 * Just enough protobuf to read Anki's media list and note type configs: fields by number, as
 * varints or length-delimited bytes. Unknown wire types end the message.
 */
class Protobuf(private val bytes: ByteArray) {
    data class Field(val number: Int, val varint: Long?, val bytes: ByteArray?) {
        val string: String get() = bytes?.toString(Charsets.UTF_8).orEmpty()
        val message: Protobuf get() = Protobuf(bytes ?: ByteArray(0))
    }

    val fields: List<Field> by lazy { parse() }

    fun all(number: Int): List<Field> = fields.filter { it.number == number }
    fun first(number: Int): Field? = fields.firstOrNull { it.number == number }
    fun string(number: Int): String = first(number)?.string.orEmpty()
    fun long(number: Int): Long = first(number)?.varint ?: 0

    private fun parse(): List<Field> {
        val out = ArrayList<Field>()
        var i = 0
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (i < bytes.size) {
                val b = bytes[i++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            return result
        }
        while (i < bytes.size) {
            val key = varint()
            val number = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> out += Field(number, varint(), null)
                1 -> i += 8
                2 -> {
                    val len = varint().toInt()
                    if (len < 0 || i + len > bytes.size) return out
                    out += Field(number, null, bytes.copyOfRange(i, i + len))
                    i += len
                }
                5 -> i += 4
                else -> return out
            }
        }
        return out
    }
}
