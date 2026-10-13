package app.universal3d.core.io

import app.universal3d.core.model.ModelFormat

/** Error de importación con mensaje comprensible para el usuario. */
open class ModelImportException(val code: Code, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Code { MALFORMED, UNSUPPORTED, INCOMPLETE, TOO_LARGE, EMPTY, IO }
}

class UnsupportedFormatException(message: String) : ModelImportException(Code.UNSUPPORTED, message)

/** Un archivo del paquete de importación (lectura diferida para no mantener todo en memoria). */
class BundleFile(val path: String, val size: Long, private val reader: () -> ByteArray) {
    fun read(): ByteArray = reader()
    val fileName: String get() = path.substringAfterLast('/')
    val extension: String get() = fileName.substringAfterLast('.', "").lowercase()

    companion object {
        fun ofBytes(path: String, bytes: ByteArray) = BundleFile(path, bytes.size.toLong()) { bytes }
    }
}

/**
 * Conjunto de archivos relacionados (p. ej. .gltf + .bin + texturas, o .obj + .mtl + imágenes).
 * Las búsquedas son tolerantes: ruta exacta, ruta normalizada o nombre base (sin distinguir mayúsculas).
 */
class FileBundle(files: List<BundleFile>) {
    val files: List<BundleFile> = files
    private val byPath = HashMap<String, BundleFile>()
    private val byName = HashMap<String, BundleFile>()

    init {
        for (f in files) {
            byPath.putIfAbsent(normalize(f.path), f)
            byName.putIfAbsent(f.fileName.lowercase(), f)
        }
    }

    fun find(reference: String, relativeTo: String? = null): BundleFile? {
        val ref = decodeUri(reference).replace('\\', '/')
        val candidates = ArrayList<String>()
        if (relativeTo != null && relativeTo.contains('/')) candidates.add(normalize(relativeTo.substringBeforeLast('/') + "/" + ref))
        candidates.add(normalize(ref))
        for (c in candidates) byPath[c]?.let { return it }
        return byName[ref.substringAfterLast('/').lowercase()]
    }

    /** Elige el archivo principal con prioridad GLB > glTF > FBX > OBJ. */
    fun pickMain(): BundleFile? {
        val order = listOf("glb", "gltf", "fbx", "obj")
        for (ext in order) files.filter { it.extension == ext }.maxByOrNull { it.size }?.let { return it }
        return null
    }

    companion object {
        fun normalize(path: String): String {
            val out = ArrayList<String>()
            for (seg in path.replace('\\', '/').split('/')) {
                when (seg) {
                    "", "." -> {}
                    ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                    else -> out.add(seg)
                }
            }
            return out.joinToString("/").lowercase()
        }

        fun decodeUri(s: String): String = try {
            java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) { s }
    }
}

/** Ayudas para detectar el formato por contenido y extensión. */
object FormatSniffer {
    fun byExtension(name: String): ModelFormat = when (name.substringAfterLast('.', "").lowercase()) {
        "glb" -> ModelFormat.GLB
        "gltf" -> ModelFormat.GLTF
        "obj" -> ModelFormat.OBJ
        "fbx" -> ModelFormat.FBX
        "zip" -> ModelFormat.ZIP
        else -> ModelFormat.UNKNOWN
    }

    fun byMime(mime: String?): ModelFormat {
        val m = mime?.substringBefore(';')?.trim()?.lowercase() ?: return ModelFormat.UNKNOWN
        return when (m) {
            "model/gltf-binary" -> ModelFormat.GLB
            "model/gltf+json" -> ModelFormat.GLTF
            "model/obj", "text/prs.wavefront-obj" -> ModelFormat.OBJ
            "application/zip", "application/x-zip-compressed" -> ModelFormat.ZIP
            "application/vnd.autodesk.fbx", "application/x-fbx" -> ModelFormat.FBX
            else -> ModelFormat.UNKNOWN
        }
    }

    /** Detecta por los primeros bytes (hasta ~4 KB). */
    fun byContent(head: ByteArray): ModelFormat {
        if (head.size >= 4 && head[0] == 'g'.code.toByte() && head[1] == 'l'.code.toByte() && head[2] == 'T'.code.toByte() && head[3] == 'F'.code.toByte()) return ModelFormat.GLB
        if (head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) return ModelFormat.ZIP
        val asText = String(head, 0, minOf(head.size, 4096), Charsets.ISO_8859_1)
        if (asText.startsWith("Kaydara FBX Binary") || asText.trimStart().startsWith("; FBX")) return ModelFormat.FBX
        val trimmed = asText.trimStart('﻿', ' ', '\n', '\r', '\t')
        if (trimmed.startsWith("{") && trimmed.contains("\"asset\"")) return ModelFormat.GLTF
        if (looksLikeObj(trimmed)) return ModelFormat.OBJ
        return ModelFormat.UNKNOWN
    }

    private fun looksLikeObj(text: String): Boolean {
        var vertexLines = 0
        var other = 0
        for (line in text.lineSequence().take(60)) {
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("#")) continue
            when {
                l.startsWith("v ") || l.startsWith("vn ") || l.startsWith("vt ") || l.startsWith("f ") -> vertexLines++
                l.startsWith("o ") || l.startsWith("g ") || l.startsWith("mtllib ") || l.startsWith("usemtl ") || l.startsWith("s ") -> {}
                else -> other++
            }
        }
        return vertexLines >= 2 && other == 0
    }
}
