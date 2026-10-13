package app.universal3d.core.export

import app.universal3d.core.inspect.ExportFormat
import app.universal3d.core.io.*
import app.universal3d.core.model.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExportArtifact(val format: ExportFormat, val fileName: String, val bytes: ByteArray, val notices: List<Notice>, val verification: Verification)

class Verification(val ok: Boolean, val details: List<String>)

object ModelExporter {
    fun export(scene: Scene3D, format: ExportFormat, baseName: String = scene.name): ExportArtifact {
        val stem = SafeNames.fileStem(baseName)
        return when (format) {
            ExportFormat.GLB -> {
                val r = GlbWriter.write(scene)
                finish(scene, format, "$stem.glb", r.bytes, r.notices + scene.importNotices.filter { it.severity == Notice.Severity.LOSS })
            }
            ExportFormat.OBJ -> {
                val r = ObjWriter.write(scene, stem)
                finish(scene, format, "${stem}_obj.zip", r.zip, r.notices)
            }
            ExportFormat.FBX -> {
                val r = FbxWriter.write(scene)
                finish(scene, format, "$stem.fbx", r.bytes, r.notices)
            }
            ExportFormat.FULL_ZIP -> fullZip(scene, stem)
        }
    }

    private fun finish(scene: Scene3D, format: ExportFormat, fileName: String, bytes: ByteArray, notices: List<Notice>) =
        ExportArtifact(format, fileName, bytes, notices, ExportVerifier.verify(scene, format, bytes))

    private fun fullZip(scene: Scene3D, stem: String): ExportArtifact {
        val glb = GlbWriter.write(scene)
        val obj = ObjWriter.write(scene, stem)
        val fbx = FbxWriter.write(scene)
        val notices = (glb.notices + obj.notices + fbx.notices).distinctBy { it.code + it.message }
        val report = buildReport(scene, notices)
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun put(n: String, b: ByteArray) { z.putNextEntry(ZipEntry(n).apply { time = 0L }); z.write(b); z.closeEntry() }
            put("glb/$stem.glb", glb.bytes)
            // OBJ: se re-empaquetan los archivos bajo obj/
            val inner = ModelImporter.unzip(obj.zip)
            for (f in inner.files) put("obj/${f.path}", f.read())
            put("fbx/$stem.fbx", fbx.bytes)
            put("INFORME.txt", report.toByteArray(Charsets.UTF_8))
        }
        val bytes = bos.toByteArray()
        val v = ExportVerifier.verifyFullZip(scene, bytes, stem)
        return ExportArtifact(ExportFormat.FULL_ZIP, "${stem}_completo.zip", bytes, notices, v)
    }

    fun buildReport(scene: Scene3D, notices: List<Notice>): String = buildString {
        appendLine("Universal 3D Asset Inspector — informe de exportación")
        appendLine("Modelo: ${scene.name} (origen: ${scene.sourceFormat.displayName})")
        val ins = app.universal3d.core.inspect.ModelInspector.inspect(scene)
        appendLine("Mallas: ${ins.meshCount} · Primitivas: ${ins.primitiveCount} · Vértices: ${ins.vertexCount} · Triángulos: ${ins.triangleCount}")
        appendLine("Materiales: ${ins.materialCount} · Texturas: ${ins.textureCount} · Huesos: ${ins.boneCount} · Animaciones: ${ins.animations.size}")
        if (scene.missingDependencies.isNotEmpty()) appendLine("Dependencias ausentes: ${scene.missingDependencies.joinToString()}")
        appendLine()
        appendLine("Avisos de importación:")
        if (scene.importNotices.isEmpty()) appendLine("  (ninguno)") else scene.importNotices.forEach { appendLine("  $it") }
        appendLine()
        appendLine("Datos conservados o perdidos por formato:")
        if (notices.isEmpty()) appendLine("  (sin pérdidas registradas)") else notices.forEach { appendLine("  $it") }
    }
}

/** Reabre el archivo generado y comprueba que conserva lo esencial antes de declarar éxito. */
object ExportVerifier {
    fun verify(src: Scene3D, format: ExportFormat, bytes: ByteArray): Verification {
        val details = ArrayList<String>()
        if (bytes.isEmpty()) return Verification(false, listOf("El archivo generado está vacío."))
        return try {
            val back = when (format) {
                ExportFormat.GLB -> ModelImporter.importBytes("check.glb", bytes)
                ExportFormat.OBJ -> ModelImporter.importBytes("check.zip", bytes)
                ExportFormat.FBX -> ModelImporter.importBytes("check.fbx", bytes)
                ExportFormat.FULL_ZIP -> return verifyFullZip(src, bytes, "")
            }
            compare(src, back, format, details)
        } catch (e: Exception) {
            Verification(false, listOf("No se pudo reabrir el archivo generado: ${e.message}"))
        }
    }

    fun verifyFullZip(src: Scene3D, bytes: ByteArray, stem: String): Verification {
        val details = ArrayList<String>()
        return try {
            val b = ModelImporter.unzip(bytes)
            val glb = b.files.firstOrNull { it.path.startsWith("glb/") && it.extension == "glb" } ?: return Verification(false, listOf("Falta el GLB en el paquete."))
            val fbx = b.files.firstOrNull { it.path.startsWith("fbx/") } ?: return Verification(false, listOf("Falta el FBX en el paquete."))
            val obj = b.files.firstOrNull { it.path.startsWith("obj/") && it.extension == "obj" } ?: return Verification(false, listOf("Falta el OBJ en el paquete."))
            val r1 = compare(src, ModelImporter.importBytes("a.glb", glb.read()), ExportFormat.GLB, details)
            val r2 = compare(src, ModelImporter.importBytes("a.fbx", fbx.read()), ExportFormat.FBX, details)
            val objFiles = FileBundle(b.files.filter { it.path.startsWith("obj/") }.map { BundleFile(it.path.removePrefix("obj/"), it.size) { it.read() } })
            val back = ModelImporter.importBundle(objFiles)
            val r3 = compare(src, back, ExportFormat.OBJ, details)
            Verification(r1.ok && r2.ok && r3.ok && back.missingDependencies.isEmpty(), details)
        } catch (e: Exception) { Verification(false, listOf("No se pudo verificar el paquete: ${e.message}")) }
    }

    private fun tris(s: Scene3D) = s.meshes.sumOf { m -> m.primitives.sumOf { it.triangleCount } }

    private fun compare(src: Scene3D, back: Scene3D, format: ExportFormat, details: MutableList<String>): Verification {
        var ok = true
        // Comparamos triángulos por instancia de nodo (los nodos pueden compartir mallas).
        fun instTris(s: Scene3D) = s.nodes.sumOf { n -> if (n.meshIndex >= 0) s.meshes[n.meshIndex].primitives.sumOf { it.triangleCount } else 0 }
        val a = instTris(src); val b = if (format == ExportFormat.GLB) instTris(back) else instTris(back)
        if (a != b) { ok = false; details.add("${format.name}: triángulos distintos ($a → $b).") } else details.add("${format.name}: $b triángulos verificados.")
        val tex = src.images.map { it.bytes.size }.toSet().size
        if (src.images.isNotEmpty() && back.images.isEmpty() && format != ExportFormat.OBJ) { ok = false; details.add("${format.name}: se perdieron todas las texturas.") }
        if (format == ExportFormat.OBJ && back.missingDependencies.isNotEmpty()) { ok = false; details.add("OBJ: referencias rotas ${back.missingDependencies}.") }
        if (format == ExportFormat.GLB) {
            if (src.animations.size != back.animations.size) { ok = false; details.add("GLB: animaciones ${src.animations.size} → ${back.animations.size}.") }
            else if (src.animations.isNotEmpty()) details.add("GLB: ${back.animations.size} animación(es) verificada(s).")
            if (src.skins.size != back.skins.size) { ok = false; details.add("GLB: esqueletos ${src.skins.size} → ${back.skins.size}.") }
            if (src.images.isNotEmpty()) details.add("GLB: ${back.images.size} imagen(es) embebida(s).")
        }
        val sb = src.bounds(); val bb = back.bounds()
        if (!sb.isEmpty && !bb.isEmpty) {
            val tol = 1e-3f * (1f + sb.radius)
            // FBX puede aplicar conversión de unidades en el nodo raíz; se compara en el mismo espacio de mundo
            if (kotlin.math.abs(sb.sizeX - bb.sizeX) > tol * 10 && kotlin.math.abs(sb.sizeY - bb.sizeY) > tol * 10) { ok = false; details.add("${format.name}: las dimensiones difieren (${sb.sizeX}×${sb.sizeY} → ${bb.sizeX}×${bb.sizeY}).") }
        }
        return Verification(ok, details)
    }
}
