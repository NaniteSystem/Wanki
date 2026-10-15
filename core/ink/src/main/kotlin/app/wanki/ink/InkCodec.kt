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
 * per stroke: tool:u8 channels:u8 [v2: brushKind:u8 color:u32le size:f32le] pointCount
 *   per point: Δx Δy (1/16 unit) Δt (ms)
 *              [Δpressure (0..1023)] [Δtilt (0..1023 over 0..π/2)] [Δorientation (0..1023 over 0..2π)]
 * ```
 * All deltas are signed. Position/time deltas run across strokes, channel deltas restart at 0 for
 * every stroke. Stylus handwriting with all channels costs ~7–8 bytes per point, finger ~4–5.
 * Quantization: position 1/16 unit, pressure/tilt/orientation 1/1023 of their range.
 */
object InkCodec {
    private val MAGIC = byteArrayOf('W'.code.toByte(), 'I'.code.toByte(), 'N'.code.toByte(), 'K'.code.toByte())
    private const val VERSION = 2
    private const val POS_SCALE = 16f
    private const val Q = 1023f
    private const val HAS_PRESSURE = 1
    private const val HAS_TILT = 2
    private const val HAS_ORIENTATION = 4
    private val TILT_RANGE = (PI / 2).toFloat()
    private val ORIENTATION_RANGE = (2 * PI).toFloat()

    fun encode(drawing: InkDrawing): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(VERSION)
        writeVarint(out, drawing.strokes.size.toLong())
        var px = 0L
        var py = 0L
        var pt = 0L
        for (s in drawing.strokes) {
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
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): InkDrawing {
        val r = Reader(bytes)
        require(bytes.size >= 5 && (0 until 4).all { bytes[it] == MAGIC[it] }) { "Not an ink blob" }
        r.pos = 4
        val version = r.u8()
        require(version in 1..VERSION) { "Unsupported ink format version $version" }
        val strokeCount = r.varint().toInt()
        var px = 0L
        var py = 0L
        var pt = 0L
        val strokes = ArrayList<InkStroke>(strokeCount)
        repeat(strokeCount) {
            val tool = InkTool.entries.getOrElse(r.u8()) { InkTool.UNKNOWN }
            val channels = r.u8()
            val brush = if (version >= 2) {
                InkBrush(BrushKind.entries.getOrElse(r.u8()) { BrushKind.PEN }, r.int(), java.lang.Float.intBitsToFloat(r.int()))
            } else InkBrush.DEFAULT
            val n = r.varint().toInt()
            val points = ArrayList<InkPoint>(n)
            var pp = 0L
            var ptilt = 0L
            var por = 0L
            repeat(n) {
                px += r.signed(); py += r.signed(); pt += r.signed()
                val pressure = if (channels and HAS_PRESSURE != 0) { pp += r.signed(); dequantize(pp, 1f) } else Float.NaN
                val tilt = if (channels and HAS_TILT != 0) { ptilt += r.signed(); dequantize(ptilt, TILT_RANGE) } else Float.NaN
                val orientation = if (channels and HAS_ORIENTATION != 0) {
                    por += r.signed(); dequantize(por, ORIENTATION_RANGE)
                } else Float.NaN
                points += InkPoint(px / POS_SCALE, py / POS_SCALE, pt, pressure, tilt, orientation)
            }
            strokes += InkStroke(points, tool, brush)
        }
        return InkDrawing(strokes)
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
        fun u8(): Int {
            require(pos < b.size) { "Truncated ink blob" }
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
