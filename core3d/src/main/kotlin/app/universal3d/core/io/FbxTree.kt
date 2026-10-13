package app.universal3d.core.io

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/** Nodo genérico del árbol FBX (binario o ASCII). Las propiedades son tipos primitivos, String, ByteArray o arrays tipados. */
class FbxNode(val name: String, val props: MutableList<Any> = ArrayList(), val children: MutableList<FbxNode> = ArrayList()) {
    fun child(n: String): FbxNode? = children.firstOrNull { it.name == n }
    fun childrenNamed(n: String): List<FbxNode> = children.filter { it.name == n }

    fun long(i: Int): Long? = when (val v = props.getOrNull(i)) { is Long -> v; is Int -> v.toLong(); is Short -> v.toLong(); is Double -> v.toLong(); is Float -> v.toLong(); else -> null }
    fun double(i: Int): Double? = when (val v = props.getOrNull(i)) { is Double -> v; is Float -> v.toDouble(); is Long -> v.toDouble(); is Int -> v.toDouble(); is Short -> v.toDouble(); else -> null }
    fun string(i: Int): String? = props.getOrNull(i) as? String
    fun bytes(i: Int): ByteArray? = props.getOrNull(i) as? ByteArray

    /** Array numérico de la primera propiedad como DoubleArray. */
    fun doubles(): DoubleArray? = when (val v = props.firstOrNull()) {
        is DoubleArray -> v; is FloatArray -> DoubleArray(v.size) { v[it].toDouble() }
        is IntArray -> DoubleArray(v.size) { v[it].toDouble() }; is LongArray -> DoubleArray(v.size) { v[it].toDouble() }
        else -> null
    }

    fun ints(): IntArray? = when (val v = props.firstOrNull()) {
        is IntArray -> v; is LongArray -> IntArray(v.size) { v[it].toInt() }
        is DoubleArray -> IntArray(v.size) { v[it].toInt() }; is FloatArray -> IntArray(v.size) { v[it].toInt() }
        else -> null
    }

    fun longs(): LongArray? = when (val v = props.firstOrNull()) {
        is LongArray -> v; is IntArray -> LongArray(v.size) { v[it].toLong() }
        is DoubleArray -> LongArray(v.size) { v[it].toLong() }
        else -> null
    }

    /** Valor de una propiedad en "Properties70" (P: nombre, tipo, etiqueta, flags, valores...). */
    fun p70(name: String): List<Any>? {
        val props70 = child("Properties70") ?: child("Properties60") ?: return null
        val p = props70.children.firstOrNull { it.props.firstOrNull() == name } ?: return null
        return p.props.drop(4)
    }

    fun p70Doubles(name: String, n: Int): DoubleArray? {
        val v = p70(name) ?: return null
        if (v.size < n) return null
        return DoubleArray(n) { (v[it] as? Number)?.toDouble() ?: return null }
    }

    fun p70Double(name: String): Double? = (p70(name)?.firstOrNull() as? Number)?.toDouble()
    fun p70Int(name: String): Int? = (p70(name)?.firstOrNull() as? Number)?.toInt()
}

object FbxParser {
    private const val BIN_MAGIC = "Kaydara FBX Binary  "

    fun isBinary(b: ByteArray) = b.size > 27 && String(b, 0, 20, Charsets.ISO_8859_1) == BIN_MAGIC

    fun parse(bytes: ByteArray): Pair<Int, List<FbxNode>> =
        if (isBinary(bytes)) parseBinary(bytes) else parseAscii(String(bytes, Charsets.UTF_8))

    // ---------------- Binario ----------------
    fun parseBinary(bytes: ByteArray): Pair<Int, List<FbxNode>> {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val version = bb.getInt(23)
        if (version < 6000 || version > 9999) throw ModelImportException(ModelImportException.Code.MALFORMED, "Versión de FBX no reconocida ($version).")
        val wide = version >= 7500
        var pos = 27
        val roots = ArrayList<FbxNode>()
        try {
            while (pos + (if (wide) 25 else 13) <= bytes.size) {
                val (node, next) = readNode(bb, bytes, pos, wide, 0) ?: break
                roots.add(node); pos = next
            }
        } catch (e: ModelImportException) { throw e } catch (e: Exception) {
            throw ModelImportException(ModelImportException.Code.MALFORMED, "FBX binario dañado o truncado: ${e.message?.take(100)}", e)
        }
        if (roots.isEmpty()) throw ModelImportException(ModelImportException.Code.MALFORMED, "FBX sin nodos legibles.")
        return version to roots
    }

    private fun readNode(bb: ByteBuffer, bytes: ByteArray, start: Int, wide: Boolean, depth: Int): Pair<FbxNode, Int>? {
        if (depth > 64) throw ModelImportException(ModelImportException.Code.MALFORMED, "FBX con anidación excesiva.")
        var p = start
        val endOffset: Long; val numProps: Long; val propLen: Long
        if (wide) { endOffset = bb.getLong(p); numProps = bb.getLong(p + 8); propLen = bb.getLong(p + 16); p += 24 }
        else { endOffset = bb.getInt(p).toLong() and 0xFFFFFFFFL; numProps = bb.getInt(p + 4).toLong() and 0xFFFFFFFFL; propLen = bb.getInt(p + 8).toLong() and 0xFFFFFFFFL; p += 12 }
        val nameLen = bytes[p].toInt() and 0xFF; p += 1
        if (endOffset == 0L) return null // registro nulo (fin de lista)
        if (endOffset > bytes.size || endOffset < p) throw ModelImportException(ModelImportException.Code.MALFORMED, "FBX truncado (offset de nodo fuera de rango).")
        val name = String(bytes, p, nameLen, Charsets.UTF_8); p += nameLen
        val node = FbxNode(name)
        val propsEnd = p + propLen
        for (i in 0 until numProps) {
            val t = bytes[p].toInt().toChar(); p++
            when (t) {
                'Y' -> { node.props.add(bb.getShort(p)); p += 2 }
                'C' -> { node.props.add(bytes[p].toInt() != 0); p += 1 }
                'I' -> { node.props.add(bb.getInt(p)); p += 4 }
                'F' -> { node.props.add(bb.getFloat(p)); p += 4 }
                'D' -> { node.props.add(bb.getDouble(p)); p += 8 }
                'L' -> { node.props.add(bb.getLong(p)); p += 8 }
                'S' -> { val l = bb.getInt(p); p += 4; node.props.add(String(bytes, p, l, Charsets.UTF_8)); p += l }
                'R' -> { val l = bb.getInt(p); p += 4; node.props.add(bytes.copyOfRange(p, p + l)); p += l }
                'f', 'd', 'l', 'i', 'b' -> {
                    val len = bb.getInt(p); val enc = bb.getInt(p + 4); val clen = bb.getInt(p + 8); p += 12
                    val elem = when (t) { 'f', 'i' -> 4; 'd', 'l' -> 8; else -> 1 }
                    val rawLen = len.toLong() * elem
                    if (len < 0 || rawLen > 1_500_000_000L) throw ModelImportException(ModelImportException.Code.TOO_LARGE, "Array FBX demasiado grande.")
                    val raw: ByteArray = if (enc == 0) bytes.copyOfRange(p, p + rawLen.toInt()) else {
                        val inf = Inflater()
                        inf.setInput(bytes, p, clen)
                        val out = ByteArray(rawLen.toInt())
                        var off = 0
                        while (off < out.size) {
                            val n = inf.inflate(out, off, out.size - off)
                            if (n == 0 && (inf.finished() || inf.needsInput())) break
                            off += n
                        }
                        inf.end()
                        if (off < out.size) throw ModelImportException(ModelImportException.Code.MALFORMED, "Array FBX comprimido incompleto.")
                        out
                    }
                    p += if (enc == 0) rawLen.toInt() else clen
                    val ab = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                    node.props.add(when (t) {
                        'f' -> FloatArray(len).also { ab.asFloatBuffer().get(it) }
                        'd' -> DoubleArray(len).also { ab.asDoubleBuffer().get(it) }
                        'i' -> IntArray(len).also { ab.asIntBuffer().get(it) }
                        'l' -> LongArray(len).also { ab.asLongBuffer().get(it) }
                        else -> BooleanArray(len) { raw[it].toInt() != 0 }
                    })
                }
                else -> throw ModelImportException(ModelImportException.Code.MALFORMED, "Tipo de propiedad FBX desconocido '$t'.")
            }
        }
        p = propsEnd.toInt()
        val nullSize = if (wide) 25 else 13
        while (p < endOffset) {
            if (p + nullSize <= bytes.size && isNull(bytes, p, nullSize)) { p += nullSize; break }
            val r = readNode(bb, bytes, p, wide, depth + 1) ?: break
            node.children.add(r.first); p = r.second
        }
        return node to endOffset.toInt()
    }

    private fun isNull(b: ByteArray, p: Int, n: Int): Boolean { for (i in 0 until n) if (b[p + i].toInt() != 0) return false; return true }

    // ---------------- ASCII ----------------
    fun parseAscii(text: String): Pair<Int, List<FbxNode>> {
        val s = Ascii(text)
        val roots = ArrayList<FbxNode>()
        try {
            while (true) {
                s.skipWs()
                if (s.eof()) break
                roots.add(s.readNode() ?: break)
            }
        } catch (e: ModelImportException) { throw e } catch (e: Exception) {
            throw ModelImportException(ModelImportException.Code.MALFORMED, "FBX ASCII inválido: ${e.message?.take(100)}", e)
        }
        if (roots.isEmpty()) throw ModelImportException(ModelImportException.Code.MALFORMED, "FBX ASCII sin nodos.")
        val ver = roots.firstOrNull { it.name == "FBXHeaderExtension" }?.child("FBXVersion")?.long(0)?.toInt() ?: 7400
        return ver to roots
    }

    private class Ascii(val t: String) {
        var i = 0
        fun eof() = i >= t.length
        fun skipWs() {
            while (i < t.length) {
                val c = t[i]
                if (c == ';') { while (i < t.length && t[i] != '\n') i++ } else if (c.isWhitespace()) i++ else break
            }
        }

        fun readNode(): FbxNode? {
            skipWs()
            if (eof() || t[i] == '}') return null
            val start = i
            while (i < t.length && (t[i].isLetterOrDigit() || t[i] == '_' || t[i] == ' ' && false)) i++
            val name = t.substring(start, i)
            if (name.isEmpty() || i >= t.length || t[i] != ':') throw IllegalStateException("se esperaba 'Nombre:' en posición $start")
            i++ // ':'
            val node = FbxNode(name)
            // propiedades hasta '{' o fin de línea
            while (true) {
                skipInlineWs()
                if (eof()) break
                val c = t[i]
                if (c == '\n' || c == '\r') {
                    // ¿continúa en la siguiente línea con ','? (listas largas) o una llave
                    var j = i; while (j < t.length && t[j].isWhitespace()) j++
                    if (j < t.length && t[j] == '{') { i = j } else break
                    continue
                }
                if (c == '{') {
                    i++
                    while (true) {
                        skipWs()
                        if (eof()) throw IllegalStateException("falta '}'")
                        if (t[i] == '}') { i++; break }
                        node.children.add(readNode() ?: break)
                    }
                    break
                }
                if (c == ',') { i++; continue }
                if (c == '*') { // *N { a: v,v,v }
                    i++
                    while (i < t.length && t[i].isDigit()) i++
                    skipWs()
                    if (t[i] == '{') {
                        i++; skipWs()
                        // 'a:'
                        if (t.startsWith("a:", i)) i += 2
                        val sb = ArrayList<Double>()
                        while (true) {
                            skipWs()
                            if (t[i] == '}') { i++; break }
                            if (t[i] == ',') { i++; continue }
                            val st = i
                            while (i < t.length && (t[i].isDigit() || t[i] in "+-.eE")) i++
                            if (st == i) throw IllegalStateException("número esperado")
                            sb.add(t.substring(st, i).toDouble())
                        }
                        val isInt = sb.all { it == Math.floor(it) && Math.abs(it) < 2.1e9 } && node.name in INT_ARRAYS
                        node.props.add(if (isInt) IntArray(sb.size) { sb[it].toInt() } else sb.toDoubleArray())
                    }
                    continue
                }
                if (c == '"') {
                    val st = ++i
                    while (i < t.length && t[i] != '"') i++
                    node.props.add(t.substring(st, i)); i++
                    continue
                }
                val st = i
                while (i < t.length && t[i] != ',' && t[i] != '\n' && t[i] != '\r' && t[i] != '{') i++
                val tok = t.substring(st, i).trim()
                if (tok.isNotEmpty()) node.props.add(parseToken(tok))
            }
            return node
        }

        fun skipInlineWs() { while (i < t.length && (t[i] == ' ' || t[i] == '\t')) i++ }

        fun parseToken(tok: String): Any = tok.toLongOrNull() ?: tok.toDoubleOrNull() ?: tok

        companion object { val INT_ARRAYS = setOf("PolygonVertexIndex", "Edges", "Indexes", "UVIndex", "NormalsIndex", "Materials", "ColorIndex", "KeyAttrFlags") }
    }
}
