package app.universal3d.core.io

import app.universal3d.core.model.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** Punto de entrada único de importación: detecta formato y delega en el lector adecuado. */
object ModelImporter {
    const val MAX_ZIP_ENTRIES = 2000
    const val MAX_ZIP_TOTAL = 768L * 1024 * 1024
    const val MAX_ENTRY = 512L * 1024 * 1024

    class Probe(val format: ModelFormat, val main: BundleFile?)

    /** Desempaqueta un ZIP en memoria con límites anti zip-bomb / zip-slip. */
    fun unzip(bytes: ByteArray): FileBundle {
        val files = ArrayList<BundleFile>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.isDirectory) continue
                    val safe = SafeNames.safeZipPath(e.name) ?: continue // se ignoran entradas peligrosas
                    if (safe.startsWith("__MACOSX/") || safe.substringAfterLast('/').startsWith("._")) continue
                    if (files.size >= MAX_ZIP_ENTRIES) throw ModelImportException(ModelImportException.Code.TOO_LARGE, "El ZIP contiene demasiados archivos (> $MAX_ZIP_ENTRIES).")
                    val bos = ByteArrayOutputStream()
                    val buf = ByteArray(16384)
                    var n: Int
                    var entryTotal = 0L
                    while (zin.read(buf).also { n = it } > 0) {
                        entryTotal += n; total += n
                        if (entryTotal > MAX_ENTRY || total > MAX_ZIP_TOTAL) throw ModelImportException(ModelImportException.Code.TOO_LARGE, "El contenido descomprimido del ZIP excede el límite permitido.")
                        bos.write(buf, 0, n)
                    }
                    files.add(BundleFile.ofBytes(safe, bos.toByteArray()))
                }
            }
        } catch (e: ModelImportException) { throw e } catch (e: Exception) {
            throw ModelImportException(ModelImportException.Code.MALFORMED, "El ZIP está dañado o no se puede leer: ${e.message?.take(100)}", e)
        }
        if (files.isEmpty()) throw ModelImportException(ModelImportException.Code.EMPTY, "El ZIP está vacío.")
        return FileBundle(files)
    }

    /** Importa un único archivo (ZIP se desempaqueta). */
    fun importBytes(name: String, bytes: ByteArray): Scene3D {
        val fmt = FormatSniffer.byContent(bytes.copyOf(minOf(bytes.size, 4096))).let { if (it == ModelFormat.UNKNOWN) FormatSniffer.byExtension(name) else it }
        return if (fmt == ModelFormat.ZIP) importBundle(unzip(bytes), name.substringBeforeLast('.'))
        else importBundle(FileBundle(listOf(BundleFile.ofBytes(name, bytes))), name.substringBeforeLast('.'))
    }

    fun importBundle(bundle: FileBundle, displayName: String? = null): Scene3D {
        if (bundle.pickMain() == null) {
            // ¿hay un ZIP con el modelo dentro? (p. ej. el usuario eligió "modelo.zip")
            val zip = bundle.files.firstOrNull { it.extension == "zip" }
            if (zip != null) return importBundle(unzip(zip.read()), displayName ?: zip.fileName.substringBeforeLast('.'))
        }
        val main = bundle.pickMain() ?: run {
            val ext = bundle.files.joinToString { it.extension }.ifBlank { "ninguno" }
            throw UnsupportedFormatException("No se encontró un modelo compatible (.glb, .gltf, .obj, .fbx). Archivos recibidos: $ext.")
        }
        if (main.size > GltfReader.MAX_BYTES) throw ModelImportException(ModelImportException.Code.TOO_LARGE, "El archivo es demasiado grande para este dispositivo (${main.size / 1048576} MB).")
        val name = displayName ?: main.fileName.substringBeforeLast('.')
        val bytes = main.read()
        // el contenido manda sobre la extensión
        val byContent = FormatSniffer.byContent(bytes.copyOf(minOf(bytes.size, 4096)))
        val fmt = if (byContent != ModelFormat.UNKNOWN && byContent != ModelFormat.ZIP) byContent else FormatSniffer.byExtension(main.fileName)
        return when (fmt) {
            ModelFormat.GLB -> GltfReader.readGlb(bytes, name, bundle, main.path)
            ModelFormat.GLTF -> GltfReader.readGltf(String(bytes, Charsets.UTF_8), name, bundle, main.path, bytes.size.toLong())
            ModelFormat.OBJ -> ObjReader.read(bytes, name, bundle, main.path)
            ModelFormat.FBX -> FbxReader.read(bytes, name, bundle, main.path)
            else -> throw UnsupportedFormatException("Formato no reconocido para '${main.fileName}'.")
        }
    }
}
