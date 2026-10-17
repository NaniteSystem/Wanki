package app.wanki.anki

import kotlin.test.Test
import kotlin.test.assertEquals

class ProtobufTest {
    /** Encodes fields the way protobuf does, for building test messages. */
    private fun msg(vararg fields: Pair<Int, Any>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun varint(v: Long) {
            var x = v
            while (x and 0x7F.inv().toLong() != 0L) { out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }
            out.write(x.toInt())
        }
        for ((n, v) in fields) when (v) {
            is Long -> { varint((n shl 3).toLong()); varint(v) }
            is Int -> { varint((n shl 3).toLong()); varint(v.toLong()) }
            is String -> { val b = v.toByteArray(); varint(((n shl 3) or 2).toLong()); varint(b.size.toLong()); out.write(b) }
            is ByteArray -> { varint(((n shl 3) or 2).toLong()); varint(v.size.toLong()); out.write(v) }
        }
        return out.toByteArray()
    }

    @Test
    fun mediaEntriesLikeAnki() {
        val entries = msg(1 to msg(1 to "dog.jpg", 2 to 1234, 3 to ByteArray(20)), 1 to msg(1 to "犬.mp3", 2 to 300_000))
        val p = Protobuf(entries)
        assertEquals(listOf("dog.jpg", "犬.mp3"), p.all(1).map { it.message.string(1) })
        assertEquals(300_000L, p.all(1)[1].message.long(2))
    }

    @Test
    fun templateConfig() {
        val config = Protobuf(msg(1 to "{{Front}}", 2 to "{{FrontSide}}<hr id=answer>{{Back}}", 5 to 0L))
        assertEquals("{{Front}}", config.string(1))
        assertEquals("{{FrontSide}}<hr id=answer>{{Back}}", config.string(2))
        assertEquals("", config.string(3))
    }
}
