package app.universal3d.core.model

import app.universal3d.core.math.Aabb
import app.universal3d.core.math.Mat4

/** Formato de origen/destino de un modelo. */
enum class ModelFormat(val extension: String, val displayName: String) {
    GLB("glb", "GLB"),
    GLTF("gltf", "glTF"),
    OBJ("obj", "OBJ"),
    FBX("fbx", "FBX"),
    ZIP("zip", "ZIP"),
    UNKNOWN("", "Desconocido"),
}

enum class TopologyMode { TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN, LINES, POINTS, OTHER }

/**
 * Primitiva: un conjunto de vértices con atributos y un único material.
 * Los índices siempre se almacenan como triángulos (lista) tras la importación.
 */
class Primitive3D(
    val positions: FloatArray,
    val indices: IntArray,
    val normals: FloatArray? = null,
    val uv0: FloatArray? = null,
    val uv1: FloatArray? = null,
    /** RGBA por vértice (4 floats). */
    val colors: FloatArray? = null,
    /** 4 índices de joint por vértice. */
    val joints: IntArray? = null,
    /** 4 pesos por vértice. */
    val weights: FloatArray? = null,
    val materialIndex: Int = -1,
    val name: String? = null,
) {
    val vertexCount get() = positions.size / 3
    val triangleCount get() = indices.size / 3

    fun validate(): String? {
        if (positions.size % 3 != 0) return "positions no es múltiplo de 3"
        if (indices.size % 3 != 0) return "indices no es múltiplo de 3"
        val vc = vertexCount
        for (i in indices) if (i < 0 || i >= vc) return "índice $i fuera de rango (vértices=$vc)"
        normals?.let { if (it.size != vc * 3) return "normals tamaño incorrecto" }
        uv0?.let { if (it.size != vc * 2) return "uv0 tamaño incorrecto" }
        uv1?.let { if (it.size != vc * 2) return "uv1 tamaño incorrecto" }
        colors?.let { if (it.size != vc * 4) return "colors tamaño incorrecto" }
        joints?.let { if (it.size != vc * 4) return "joints tamaño incorrecto" }
        weights?.let { if (it.size != vc * 4) return "weights tamaño incorrecto" }
        return null
    }

    fun bounds(): Aabb {
        val b = Aabb()
        var i = 0
        while (i < positions.size) { b.add(positions[i], positions[i + 1], positions[i + 2]); i += 3 }
        return b
    }
}

class Mesh3D(val name: String?, val primitives: List<Primitive3D>)

class TextureRef(val textureIndex: Int, val texCoord: Int = 0)

enum class AlphaMode { OPAQUE, MASK, BLEND }

class Material3D(
    val name: String? = null,
    val baseColorFactor: FloatArray = floatArrayOf(1f, 1f, 1f, 1f),
    val baseColorTexture: TextureRef? = null,
    val metallicFactor: Float = 1f,
    val roughnessFactor: Float = 1f,
    /** Canal G = rugosidad, B = metálico (convención glTF). */
    val metallicRoughnessTexture: TextureRef? = null,
    val normalTexture: TextureRef? = null,
    val normalScale: Float = 1f,
    /** Canal R = oclusión. */
    val occlusionTexture: TextureRef? = null,
    val occlusionStrength: Float = 1f,
    val emissiveFactor: FloatArray = floatArrayOf(0f, 0f, 0f),
    val emissiveTexture: TextureRef? = null,
    val alphaMode: AlphaMode = AlphaMode.OPAQUE,
    val alphaCutoff: Float = 0.5f,
    val doubleSided: Boolean = false,
) {
    fun textureRefs(): List<Pair<String, TextureRef>> = listOfNotNull(
        baseColorTexture?.let { "baseColor" to it },
        metallicRoughnessTexture?.let { "metallicRoughness" to it },
        normalTexture?.let { "normal" to it },
        occlusionTexture?.let { "occlusion" to it },
        emissiveTexture?.let { "emissive" to it },
    )
}

/** Imagen codificada (PNG/JPEG/WebP...). Se mantiene comprimida para ahorrar memoria. */
class Image3D(val name: String?, val mimeType: String, val bytes: ByteArray, val sourceUri: String? = null) {
    /** Dimensiones si se pudieron leer de la cabecera. */
    val dimensions: Pair<Int, Int>? by lazy { ImageHeader.dimensions(bytes) }
}

class Sampler3D(
    val magFilter: Int? = null,
    val minFilter: Int? = null,
    val wrapS: Int = 10497,
    val wrapT: Int = 10497,
)

class Texture3D(val name: String?, val imageIndex: Int, val sampler: Sampler3D = Sampler3D())

class Node3D(
    val name: String?,
    val children: List<Int> = emptyList(),
    val meshIndex: Int = -1,
    val skinIndex: Int = -1,
    val translation: FloatArray = floatArrayOf(0f, 0f, 0f),
    /** xyzw */
    val rotation: FloatArray = floatArrayOf(0f, 0f, 0f, 1f),
    val scale: FloatArray = floatArrayOf(1f, 1f, 1f),
) {
    fun localMatrix(): Mat4 = Mat4.fromTrs(translation, rotation, scale)
}

class Skin3D(
    val name: String?,
    val joints: List<Int>,
    /** 16 floats por joint; vacío = identidad. */
    val inverseBindMatrices: FloatArray?,
    val skeletonRoot: Int = -1,
)

enum class AnimPath { TRANSLATION, ROTATION, SCALE, WEIGHTS }
enum class Interpolation { LINEAR, STEP, CUBICSPLINE }

class AnimSampler(val times: FloatArray, val values: FloatArray, val interpolation: Interpolation)
class AnimChannel(val nodeIndex: Int, val path: AnimPath, val sampler: AnimSampler)

class Animation3D(val name: String?, val channels: List<AnimChannel>) {
    val duration: Float get() = channels.maxOfOrNull { c -> c.sampler.times.lastOrNull() ?: 0f } ?: 0f
}

/** Información sobre lo que se perdió/omitió o aproximó durante una importación o exportación. */
class Notice(val severity: Severity, val code: String, val message: String) {
    enum class Severity { INFO, WARNING, LOSS }
    override fun toString() = "[$severity] $message"
}

/**
 * Escena en memoria. Modelo interno neutro cuyo formato de intercambio principal es GLB.
 */
class Scene3D(
    val name: String,
    val sourceFormat: ModelFormat,
    val nodes: List<Node3D>,
    val rootNodes: List<Int>,
    val meshes: List<Mesh3D>,
    val materials: List<Material3D>,
    val textures: List<Texture3D>,
    val images: List<Image3D>,
    val skins: List<Skin3D> = emptyList(),
    val animations: List<Animation3D> = emptyList(),
    /** Avisos generados al importar (datos no soportados, aproximaciones, etc.). */
    val importNotices: List<Notice> = emptyList(),
    /** Nombres de archivos de dependencia no encontrados. */
    val missingDependencies: List<String> = emptyList(),
    val sourceSizeBytes: Long = -1,
) {
    /** Matrices de mundo de cada nodo en pose de reposo (sin animación). */
    fun worldMatrices(): Array<Mat4> = SceneEval.worldMatrices(this, null)

    fun bounds(): Aabb {
        val world = worldMatrices()
        val out = Aabb()
        nodes.forEachIndexed { i, n ->
            if (n.meshIndex >= 0) for (p in meshes[n.meshIndex].primitives) out.add(p.bounds().transformed(world[i]))
        }
        return out
    }

    fun withNotices(extra: List<Notice>, missing: List<String> = emptyList(), size: Long = sourceSizeBytes) = Scene3D(
        name, sourceFormat, nodes, rootNodes, meshes, materials, textures, images, skins, animations,
        importNotices + extra, missingDependencies + missing, size,
    )
}

object SceneEval {
    fun worldMatrices(scene: Scene3D, local: Array<Mat4>?): Array<Mat4> {
        val n = scene.nodes.size
        val world = Array(n) { Mat4.identity() }
        val visited = BooleanArray(n)
        fun visit(i: Int, parent: Mat4) {
            if (i !in 0 until n || visited[i]) return
            visited[i] = true
            val l = local?.get(i) ?: scene.nodes[i].localMatrix()
            world[i] = parent * l
            for (c in scene.nodes[i].children) visit(c, world[i])
        }
        for (r in scene.rootNodes) visit(r, Mat4.identity())
        return world
    }
}

/** Lectura mínima de dimensiones de PNG/JPEG/WebP/GIF sin decodificar. */
object ImageHeader {
    fun dimensions(b: ByteArray): Pair<Int, Int>? {
        try {
            if (b.size >= 24 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()) {
                return be32(b, 16) to be32(b, 20)
            }
            if (b.size > 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) {
                var i = 2
                while (i + 9 < b.size) {
                    if (b[i] != 0xFF.toByte()) { i++; continue }
                    val marker = b[i + 1].toInt() and 0xFF
                    if (marker == 0xD8 || marker == 0x01 || (marker in 0xD0..0xD7)) { i += 2; continue }
                    val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
                    if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                        val h = ((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF)
                        val w = ((b[i + 7].toInt() and 0xFF) shl 8) or (b[i + 8].toInt() and 0xFF)
                        return w to h
                    }
                    i += 2 + len
                }
                return null
            }
            if (b.size >= 30 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP") {
                when (String(b, 12, 4, Charsets.ISO_8859_1)) {
                    "VP8X" -> return (1 + le24(b, 24)) to (1 + le24(b, 27))
                    "VP8 " -> return ((le16(b, 26) and 0x3FFF)) to ((le16(b, 28) and 0x3FFF))
                    "VP8L" -> {
                        val bits = (b[21].toInt() and 0xFF) or ((b[22].toInt() and 0xFF) shl 8) or
                            ((b[23].toInt() and 0xFF) shl 16) or ((b[24].toInt() and 0xFF) shl 24)
                        return ((bits and 0x3FFF) + 1) to (((bits shr 14) and 0x3FFF) + 1)
                    }
                }
            }
            if (b.size >= 10 && String(b, 0, 3, Charsets.ISO_8859_1) == "GIF") return le16(b, 6) to le16(b, 8)
        } catch (_: Exception) { }
        return null
    }

    fun sniffMime(b: ByteArray): String? = when {
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "image/png"
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
        b.size >= 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
        b.size >= 3 && String(b, 0, 3, Charsets.ISO_8859_1) == "GIF" -> "image/gif"
        b.size >= 2 && b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte() -> "image/bmp"
        b.size >= 12 && b[0] == 0xAB.toByte() && b[1] == 'K'.code.toByte() && b[2] == 'T'.code.toByte() -> "image/ktx2"
        else -> null
    }

    fun mimeForName(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"
        "gif" -> "image/gif"; "bmp" -> "image/bmp"; "ktx2" -> "image/ktx2"
        else -> null
    }

    private fun be32(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
        ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le24(b: ByteArray, o: Int) = le16(b, o) or ((b[o + 2].toInt() and 0xFF) shl 16)
}
