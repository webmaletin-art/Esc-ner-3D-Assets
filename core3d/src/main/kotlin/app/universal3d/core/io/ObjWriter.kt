package app.universal3d.core.io

import app.universal3d.core.math.Mat4
import app.universal3d.core.model.*
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exporta a un paquete ZIP con .obj, .mtl y texturas (rutas relativas, nombres seguros). */
object ObjWriter {
    class Result(val zip: ByteArray, val objName: String, val files: List<String>, val notices: List<Notice>)

    fun write(scene: Scene3D, baseName: String = scene.name): Result {
        val notices = ArrayList<Notice>()
        val safe = SafeNames.fileStem(baseName)
        val world = scene.worldMatrices()

        // --- texturas -> archivos con nombres únicos ---
        val imageFile = arrayOfNulls<String>(scene.images.size)
        val used = HashSet<String>()
        val dedupe = HashMap<String, String>()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        scene.images.forEachIndexed { i, im ->
            val h = digest.digest(im.bytes).joinToString("") { "%02x".format(it) }
            dedupe[h]?.let { imageFile[i] = it; return@forEachIndexed }
            val ext = when (im.mimeType) { "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/webp" -> "webp"; else -> im.name?.substringAfterLast('.', "png")?.lowercase() ?: "png" }
            val stem = SafeNames.fileStem(im.name?.substringBeforeLast('.') ?: "texture_$i")
            var candidate = "$stem.$ext"; var k = 1
            while (!used.add(candidate.lowercase())) candidate = "${stem}_${k++}.$ext"
            imageFile[i] = candidate
            dedupe[h] = candidate
        }
        fun texPath(ref: TextureRef?): String? {
            ref ?: return null
            val t = scene.textures.getOrNull(ref.textureIndex) ?: return null
            return imageFile.getOrNull(t.imageIndex)?.let { "textures/$it" }
        }

        // --- MTL ---
        val mtl = StringBuilder("# Generado por Universal 3D Asset Inspector\n")
        val matNames = ArrayList<String>()
        val used2 = HashSet<String>()
        scene.materials.forEachIndexed { i, m ->
            var n = SafeNames.token(m.name ?: "material_$i"); var k = 1; val base = n
            while (!used2.add(n)) n = "${base}_${k++}"
            matNames.add(n)
            mtl.append("newmtl ").append(n).append('\n')
            val c = m.baseColorFactor
            mtl.append("Kd ${f(c[0])} ${f(c[1])} ${f(c[2])}\n")
            mtl.append("Ka 1.0 1.0 1.0\n")
            val spec = 0.04f + 0.96f * m.metallicFactor
            mtl.append("Ks ${f(spec)} ${f(spec)} ${f(spec)}\n")
            val ns = ((1f - m.roughnessFactor).coerceIn(0f, 1f)).let { it * it * 1000f }
            mtl.append("Ns ${f(ns)}\n")
            mtl.append("d ${f(c[3])}\n")
            mtl.append("illum 2\n")
            if (m.emissiveFactor.any { it > 0f }) mtl.append("Ke ${f(m.emissiveFactor[0])} ${f(m.emissiveFactor[1])} ${f(m.emissiveFactor[2])}\n")
            mtl.append("Pr ${f(m.roughnessFactor)}\nPm ${f(m.metallicFactor)}\n")
            texPath(m.baseColorTexture)?.let { mtl.append("map_Kd $it\n") }
            texPath(m.normalTexture)?.let { mtl.append("map_Bump $it\n") }
            texPath(m.emissiveTexture)?.let { mtl.append("map_Ke $it\n") }
            texPath(m.metallicRoughnessTexture)?.let {
                mtl.append("# glTF metallicRoughness (canales empaquetados: G=rugosidad, B=metálico): $it\n")
                notices.add(Notice(Notice.Severity.LOSS, "mr", "Material '${m.name}': la textura metal/rugosidad empaquetada se incluye en el ZIP pero OBJ/MTL no puede enlazarla."))
            }
            texPath(m.occlusionTexture)?.let {
                mtl.append("# glTF occlusion (canal R): $it\n")
                notices.add(Notice(Notice.Severity.LOSS, "ao", "Material '${m.name}': la textura de oclusión se incluye en el ZIP pero OBJ/MTL no puede enlazarla."))
            }
            mtl.append('\n')
        }

        // --- OBJ ---
        val obj = StringBuilder("# Generado por Universal 3D Asset Inspector\nmtllib $safe.mtl\n")
        var vOff = 1; var tOff = 1; var nOff = 1
        var skinned = false
        scene.nodes.forEachIndexed { ni, node ->
            if (node.meshIndex < 0) return@forEachIndexed
            if (node.skinIndex >= 0) skinned = true
            val mesh = scene.meshes[node.meshIndex]
            val w: Mat4 = world[ni]
            val nm = w.inverse()?.transposed()
            val tmp = FloatArray(3)
            for ((pi, p) in mesh.primitives.withIndex()) {
                obj.append("o ").append(SafeNames.token(node.name ?: mesh.name ?: "mesh_$ni")).append(if (mesh.primitives.size > 1) "_$pi" else "").append('\n')
                for (v in 0 until p.vertexCount) {
                    w.transformPoint(p.positions[v * 3], p.positions[v * 3 + 1], p.positions[v * 3 + 2], tmp)
                    obj.append("v ${f(tmp[0])} ${f(tmp[1])} ${f(tmp[2])}\n")
                }
                p.uv0?.let { uv -> for (v in 0 until p.vertexCount) obj.append("vt ${f(uv[v * 2])} ${f(1f - uv[v * 2 + 1])}\n") }
                p.normals?.let { nr ->
                    for (v in 0 until p.vertexCount) {
                        val m = nm ?: Mat4.identity()
                        m.transformDirection(nr[v * 3], nr[v * 3 + 1], nr[v * 3 + 2], tmp)
                        val l = kotlin.math.sqrt(tmp[0] * tmp[0] + tmp[1] * tmp[1] + tmp[2] * tmp[2]).let { if (it == 0f) 1f else it }
                        obj.append("vn ${f(tmp[0] / l)} ${f(tmp[1] / l)} ${f(tmp[2] / l)}\n")
                    }
                }
                if (p.materialIndex in matNames.indices) obj.append("usemtl ").append(matNames[p.materialIndex]).append('\n')
                val flip = w.determinant3() < 0f
                val hasT = p.uv0 != null; val hasN = p.normals != null
                var i = 0
                while (i + 2 < p.indices.size) {
                    obj.append('f')
                    val order = if (flip) intArrayOf(0, 2, 1) else intArrayOf(0, 1, 2)
                    for (o in order) {
                        val vi = p.indices[i + o]
                        obj.append(' ').append(vOff + vi)
                        if (hasT || hasN) {
                            obj.append('/'); if (hasT) obj.append(tOff + vi)
                            if (hasN) obj.append('/').append(nOff + vi)
                        }
                    }
                    obj.append('\n'); i += 3
                }
                vOff += p.vertexCount; if (hasT) tOff += p.vertexCount; if (hasN) nOff += p.vertexCount
            }
        }
        if (skinned) notices.add(Notice(Notice.Severity.LOSS, "skin", "OBJ no soporta esqueletos: se exporta la pose de reposo."))
        if (scene.animations.isNotEmpty()) notices.add(Notice(Notice.Severity.LOSS, "anim", "OBJ no soporta animaciones: ${scene.animations.size} animación(es) no se exportan."))
        notices.add(Notice(Notice.Severity.INFO, "hier", "OBJ no tiene jerarquía: las transformaciones de nodos se aplicaron a los vértices."))

        val bos = ByteArrayOutputStream()
        val files = ArrayList<String>()
        ZipOutputStream(bos).use { z ->
            fun put(name: String, data: ByteArray) {
                z.putNextEntry(ZipEntry(name).apply { time = 0L }); z.write(data); z.closeEntry(); files.add(name)
            }
            put("$safe.obj", obj.toString().toByteArray(Charsets.UTF_8))
            put("$safe.mtl", mtl.toString().toByteArray(Charsets.UTF_8))
            val written = HashSet<String>()
            scene.images.forEachIndexed { i, im -> val fn = imageFile[i]!!; if (written.add(fn)) put("textures/$fn", im.bytes) }
        }
        return Result(bos.toByteArray(), "$safe.obj", files, notices)
    }

    private fun f(v: Float): String = if (v == v.toLong().toFloat() && kotlin.math.abs(v) < 1e9f) v.toLong().toString() else String.format(Locale.ROOT, "%.6f", v).trimEnd('0').trimEnd('.')
}

/** Nombres de archivo seguros. */
object SafeNames {
    private val RESERVED = setOf("con", "prn", "aux", "nul", "com1", "com2", "com3", "lpt1", "lpt2", "lpt3")

    /** Nombre de archivo (sin extensión) seguro para cualquier sistema de archivos. */
    fun fileStem(name: String, fallback: String = "modelo"): String {
        var s = java.text.Normalizer.normalize(name.substringAfterLast('/').substringAfterLast('\\'), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        s = s.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_', '.', ' ')
        s = s.replace(Regex("\\.{2,}"), ".")
        if (s.length > 80) s = s.substring(0, 80)
        if (s.isEmpty() || s.lowercase() in RESERVED) s = fallback
        return s
    }

    /** Nombre completo con extensión; la extensión se sanea y fuerza a minúsculas. */
    fun fileName(name: String, extension: String, fallback: String = "modelo"): String =
        fileStem(name.substringBeforeLast('.', name), fallback) + "." + extension.lowercase().replace(Regex("[^a-z0-9]"), "")

    /** Token sin espacios, apto para nombres de objeto/material en OBJ/MTL. */
    fun token(name: String): String = name.trim().replace(Regex("\\s+"), "_").replace(Regex("[^\\p{L}\\p{N}_.\\-]"), "_").ifEmpty { "unnamed" }

    /** Ruta segura dentro de un ZIP (evita zip-slip). */
    fun safeZipPath(entry: String): String? {
        val n = entry.replace('\\', '/')
        if (n.startsWith("/") || n.contains(':')) return null
        val parts = n.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }
}
