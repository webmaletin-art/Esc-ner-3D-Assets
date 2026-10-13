package app.universal3d.core.io

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Codificador PNG mínimo (RGBA 8 bits) sin dependencias de plataforma. */
object Png {
    fun encodeRgba(width: Int, height: Int, rgba: ByteArray): ByteArray {
        require(rgba.size == width * height * 4) { "tamaño RGBA inválido" }
        val raw = ByteArray((width * 4 + 1) * height)
        for (y in 0 until height) {
            raw[y * (width * 4 + 1)] = 0
            System.arraycopy(rgba, y * width * 4, raw, y * (width * 4 + 1) + 1, width * 4)
        }
        val def = Deflater(6)
        def.setInput(raw); def.finish()
        val comp = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!def.finished()) { val n = def.deflate(buf); comp.write(buf, 0, n) }
        def.end()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val ihdr = ByteArray(13)
        putInt(ihdr, 0, width); putInt(ihdr, 4, height)
        ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0
        chunk(out, "IHDR", ihdr)
        chunk(out, "IDAT", comp.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    fun solid(width: Int, height: Int, r: Int, g: Int, b: Int, a: Int = 255): ByteArray {
        val px = ByteArray(width * height * 4)
        for (i in 0 until width * height) {
            px[i * 4] = r.toByte(); px[i * 4 + 1] = g.toByte(); px[i * 4 + 2] = b.toByte(); px[i * 4 + 3] = a.toByte()
        }
        return encodeRgba(width, height, px)
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val len = ByteArray(4); putInt(len, 0, data.size)
        out.write(len)
        val t = type.toByteArray(Charsets.US_ASCII)
        out.write(t); out.write(data)
        val crc = CRC32(); crc.update(t); crc.update(data)
        val c = ByteArray(4); putInt(c, 0, crc.value.toInt())
        out.write(c)
    }

    private fun putInt(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }
}
