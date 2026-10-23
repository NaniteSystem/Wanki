package app.wanki.ink

import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Compact binary format for [InkDrawing] (the `strokesBlob` of `InkAttempt`, layers of `PageInk`).
 *
 * Layout (all integers are LEB128 varints, signed ones zig-zag encoded):
 * ```
 * "WINK" version:u8 strokeCount
 * v1/v2 per stroke: tool:u8 channels:u8 [v2: brushKind:u8 color:u32le size:f32le] pointCount
 * v3 per stroke: chunkLength chunk, where chunk = tool channels brushKind color size pointCount
 *   per point: Δx Δy (1/16 unit) Δt (ms)
 *              [Δpressure (0..1023)] [Δtilt (0..1023 over 0..π/2)] [Δorientation (0..1023 over 0..2π)]
 * ```
 * All deltas are signed. In v1/v2 position/time deltas run across strokes and channel deltas restart at 0
 * for every stroke; in v3 every delta restarts at 0 in each chunk, so a chunk is independent of its
 * neighbours (cacheable, hashable via [strokeKey]). Stylus handwriting with all channels costs ~7–8 bytes per point, finger ~4–5.
 * Quantization: position 1/16 unit, pressure/tilt/orientation 1/1023 of their range.
 */
object InkCodec {
    private val MAGIC = byteArrayOf('W'.code.toByte(), 'I'.code.toByte(), 'N'.code.toByte(), 'K'.code.toByte())
    private const val VERSION = 3
    private const val POS_SCALE = 16f
    private const val Q = 1023f
    private const val HAS_PRESSURE = 1
    private const val HAS_TILT = 2
    private const val HAS_ORIENTATION = 4
    private val TILT_RANGE = (PI / 2).toFloat()
    private val ORIENTATION_RANGE = (2 * PI).toFloat()

    /** Writes v3: every stroke is a self-contained chunk, so a chunk's bytes depend on that stroke alone. */
    fun encode(drawing: InkDrawing): ByteArray = assemble(drawing.strokes.map(::encodeStroke))

    /** One v3 chunk: `tool channels brushKind color size pointCount points`, deltas starting from 0. */
    fun encodeStroke(stroke: InkStroke): ByteArray {
        val out = ByteArrayOutputStream()
        val s = stroke
        var channels = 0
        if (s.points.isNotEmpty() && s.points.all { !it.pressure.isNaN() }) channels = channels or HAS_PRESSURE
        if (s.points.isNotEmpty() && s.points.all { !it.tiltRadians.isNaN() }) channels = channels or HAS_TILT
        if (s.points.isNotEmpty() && s.points.all { !it.orientationRadians.isNaN() }) channels = channels or HAS_ORIENTATION
        out.write(s.tool.ordinal)
        out.write(channels)
        out.write(s.brush.kind.ordinal)
        writeInt(out, s.brush.color)
        writeInt(out, java.lang.Float.floatToIntBits(s.brush.size))
        writeVarint(out, s.points.size.toLong())
        var px = 0L
        var py = 0L
        var pt = 0L
        var pp = 0L
        var ptilt = 0L
        var por = 0L
        for (p in s.points) {
            val x = (p.x * POS_SCALE).roundToLong()
            val y = (p.y * POS_SCALE).roundToLong()
            writeSigned(out, x - px); writeSigned(out, y - py); writeSigned(out, p.timeMs - pt)
            px = x; py = y; pt = p.timeMs
            if (channels and HAS_PRESSURE != 0) quantize(p.pressure, 1f).let { writeSigned(out, it - pp); pp = it }
            if (channels and HAS_TILT != 0) quantize(p.tiltRadians, TILT_RANGE).let { writeSigned(out, it - ptilt); ptilt = it }
            if (channels and HAS_ORIENTATION != 0) {
                quantize(p.orientationRadians, ORIENTATION_RANGE).let { writeSigned(out, it - por); por = it }
            }
        }
        return out.toByteArray()
    }

    /** `"WINK" 3 strokeCount`, then per stroke `chunkLength chunk`. */
    fun assemble(chunks: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream(chunks.sumOf { it.size + 2 } + 8)
        out.write(MAGIC)
        out.write(VERSION)
        writeVarint(out, chunks.size.toLong())
        for (c in chunks) {
            writeVarint(out, c.size.toLong())
            out.write(c)
        }
        return out.toByteArray()
    }

    /** 64-bit FNV-1a of a chunk: identifies a stroke's content for caches. */
    fun strokeKey(chunk: ByteArray): Long {
        var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        for (b in chunk) {
            h = h xor (b.toLong() and 0xFF)
            h *= 0x100000001b3L
        }
        return h
    }

    fun decode(bytes: ByteArray): InkDrawing {
        val r = Reader(bytes)
        require(bytes.size >= 5 && (0 until 4).all { bytes[it] == MAGIC[it] }) { "Not an ink blob" }
        r.pos = 4
        val version = r.u8()
        require(version in 1..VERSION) { "Unsupported ink format version $version" }
        val strokeCount = r.varint().toInt()
        require(strokeCount >= 0 && strokeCount <= bytes.size) { "Malformed ink blob" }
        val strokes = ArrayList<InkStroke>(strokeCount)
        if (version >= 3) {
            repeat(strokeCount) {
                val len = r.varint()
                require(len >= 0 && len <= bytes.size - r.pos) { "Truncated ink blob" }
                val end = r.pos + len.toInt()
                strokes += decodeStroke(r, version, PointState(), end)
                r.pos = end
                r.limit = bytes.size
            }
        } else {
            val state = PointState()
            repeat(strokeCount) { strokes += decodeStroke(r, version, state, bytes.size) }
        }
        return InkDrawing(strokes)
    }

    /** Position/time deltas run across strokes in v1/v2 (shared state); in v3 each chunk starts fresh. */
    private class PointState { var x = 0L; var y = 0L; var t = 0L }

    private fun decodeStroke(r: Reader, version: Int, st: PointState, limit: Int): InkStroke {
        r.limit = limit
        val tool = InkTool.entries.getOrElse(r.u8()) { InkTool.UNKNOWN }
        val channels = r.u8()
        val brush = if (version >= 2) {
            InkBrush(BrushKind.entries.getOrElse(r.u8()) { BrushKind.PEN }, r.int(), java.lang.Float.intBitsToFloat(r.int()))
        } else InkBrush.DEFAULT
        val n = r.varint().toInt()
        require(n >= 0 && n <= limit) { "Malformed ink blob" }
        val points = ArrayList<InkPoint>(n)
        var pp = 0L
        var ptilt = 0L
        var por = 0L
        repeat(n) {
            st.x += r.signed(); st.y += r.signed(); st.t += r.signed()
            val pressure = if (channels and HAS_PRESSURE != 0) { pp += r.signed(); dequantize(pp, 1f) } else Float.NaN
            val tilt = if (channels and HAS_TILT != 0) { ptilt += r.signed(); dequantize(ptilt, TILT_RANGE) } else Float.NaN
            val orientation = if (channels and HAS_ORIENTATION != 0) {
                por += r.signed(); dequantize(por, ORIENTATION_RANGE)
            } else Float.NaN
            points += InkPoint(st.x / POS_SCALE, st.y / POS_SCALE, st.t, pressure, tilt, orientation)
        }
        return InkStroke(points, tool, brush)
    }

    private fun quantize(v: Float, range: Float): Long = (v.coerceIn(0f, range) / range * Q).roundToInt().toLong()
    private fun dequantize(q: Long, range: Float): Float = q / Q * range

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Int) {
        for (k in 0 until 4) out.write((v ushr (8 * k)) and 0xFF)
    }

    private fun writeSigned(out: ByteArrayOutputStream, v: Long) = writeVarint(out, (v shl 1) xor (v shr 63))

    private class Reader(val b: ByteArray) {
        var pos = 0
        var limit = b.size
        fun u8(): Int {
            require(pos < limit) { "Truncated ink blob" }
            return b[pos++].toInt() and 0xFF
        }
        fun int(): Int {
            var v = 0
            for (k in 0 until 4) v = v or (u8() shl (8 * k))
            return v
        }
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val byte = u8()
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
                require(shift < 64) { "Malformed varint" }
            }
        }
        fun signed(): Long {
            val v = varint()
            return (v ushr 1) xor -(v and 1)
        }
    }
}
