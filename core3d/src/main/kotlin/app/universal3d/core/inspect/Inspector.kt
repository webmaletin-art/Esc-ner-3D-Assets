package app.universal3d.core.inspect

import app.universal3d.core.model.*

/** Valor que puede no estar disponible: se muestra como "No disponible". */
data class Maybe<T>(val value: T?) {
    fun text(fmt: (T) -> String = { it.toString() }, missing: String = "No disponible") = value?.let(fmt) ?: missing
}

class TextureInfo(
    val index: Int, val name: String, val mimeType: String, val sizeBytes: Long,
    val width: Int?, val height: Int?, val usedAs: List<String>,
) {
    val resolution: String get() = if (width != null && height != null) "${width}×$height" else "No disponible"
}

class MaterialInfo(val index: Int, val name: String, val baseColor: FloatArray, val metallic: Float, val roughness: Float, val alphaMode: AlphaMode, val doubleSided: Boolean, val textureSlots: List<String>)

class AnimationInfo(val name: String, val durationSeconds: Float, val channels: Int)

class InspectionReport(
    val name: String,
    val sourceFormat: ModelFormat,
    val sizeBytes: Maybe<Long>,
    val nodeCount: Int,
    val meshCount: Int,
    val primitiveCount: Int,
    val vertexCount: Long,
    val triangleCount: Long,
    val materialCount: Int,
    val textureCount: Int,
    val textures: List<TextureInfo>,
    val materials: List<MaterialInfo>,
    val hasUv: Boolean,
    val hasNormals: Boolean,
    val hasVertexColors: Boolean,
    val hasSkeleton: Boolean,
    val boneCount: Int,
    val animations: List<AnimationInfo>,
    val missingDependencies: List<String>,
    val boundsSize: Maybe<FloatArray>,
    val notices: List<Notice>,
    val exportOptions: List<ExportOption>,
)

object ModelInspector {
    fun inspect(scene: Scene3D): InspectionReport {
        var verts = 0L; var tris = 0L; var prims = 0
        var uv = false; var nrm = false; var col = false
        // Se cuentan las instancias reales (un mesh usado por 2 nodos cuenta dos veces para triángulos renderizados),
        // pero para vértices/triángulos del archivo se cuenta cada primitiva una vez.
        for (m in scene.meshes) for (p in m.primitives) {
            prims++; verts += p.vertexCount; tris += p.triangleCount
            if (p.uv0 != null) uv = true; if (p.normals != null) nrm = true; if (p.colors != null) col = true
        }
        val usage = HashMap<Int, MutableList<String>>()
        val mats = scene.materials.mapIndexed { i, m ->
            val slots = m.textureRefs().map { (slot, ref) ->
                val img = scene.textures.getOrNull(ref.textureIndex)?.imageIndex ?: -1
                if (img >= 0) usage.getOrPut(img) { ArrayList() }.add(slot)
                slot
            }
            MaterialInfo(i, m.name ?: "Material $i", m.baseColorFactor, m.metallicFactor, m.roughnessFactor, m.alphaMode, m.doubleSided, slots)
        }
        val texs = scene.images.mapIndexed { i, im ->
            TextureInfo(i, im.name ?: "Imagen $i", im.mimeType, im.bytes.size.toLong(), im.dimensions?.first, im.dimensions?.second, usage[i]?.distinct() ?: emptyList())
        }
        val bones = scene.skins.sumOf { it.joints.size }
        val b = scene.bounds()
        return InspectionReport(
            name = scene.name, sourceFormat = scene.sourceFormat, sizeBytes = Maybe(scene.sourceSizeBytes.takeIf { it >= 0 }),
            nodeCount = scene.nodes.size, meshCount = scene.meshes.size, primitiveCount = prims, vertexCount = verts, triangleCount = tris,
            materialCount = scene.materials.size, textureCount = scene.images.size, textures = texs, materials = mats,
            hasUv = uv, hasNormals = nrm, hasVertexColors = col, hasSkeleton = scene.skins.isNotEmpty(), boneCount = bones,
            animations = scene.animations.map { AnimationInfo(it.name ?: "Animación", it.duration, it.channels.size) },
            missingDependencies = scene.missingDependencies, boundsSize = Maybe(if (b.isEmpty) null else floatArrayOf(b.sizeX, b.sizeY, b.sizeZ)),
            notices = scene.importNotices, exportOptions = ExportPlanner.plan(scene),
        )
    }
}

enum class ExportFormat(val label: String, val extension: String, val mime: String) {
    GLB("GLB", "glb", "model/gltf-binary"),
    OBJ("OBJ (ZIP con MTL y texturas)", "zip", "application/zip"),
    FBX("FBX", "fbx", "application/octet-stream"),
    FULL_ZIP("Paquete ZIP completo", "zip", "application/zip"),
}

class ExportOption(val format: ExportFormat, val enabled: Boolean, val reason: String?, val expectedLosses: List<Notice>)

/** Decide qué exportaciones son reales para una escena y qué se perdería en cada una. */
object ExportPlanner {
    fun plan(scene: Scene3D): List<ExportOption> {
        if (scene.meshes.none { m -> m.primitives.any { it.triangleCount > 0 } }) {
            return ExportFormat.values().map { ExportOption(it, false, "El modelo no tiene geometría exportable.", emptyList()) }
        }
        val skinned = scene.skins.isNotEmpty()
        val anim = scene.animations.isNotEmpty()
        val packedMr = scene.materials.any { it.metallicRoughnessTexture != null }
        val ao = scene.materials.any { it.occlusionTexture != null }
        val multiUv = scene.meshes.any { m -> m.primitives.any { it.uv1 != null } }
        val glbLosses = ArrayList<Notice>()
        if (scene.missingDependencies.isNotEmpty()) glbLosses.add(Notice(Notice.Severity.WARNING, "missing", "Faltan ${scene.missingDependencies.size} dependencia(s): ${scene.missingDependencies.joinToString()}."))
        val objLosses = ArrayList<Notice>(glbLosses)
        objLosses.add(Notice(Notice.Severity.INFO, "hier", "La jerarquía de nodos se aplana (transformaciones aplicadas a vértices)."))
        if (skinned) objLosses.add(Notice(Notice.Severity.LOSS, "skin", "Se pierde el esqueleto y los pesos."))
        if (anim) objLosses.add(Notice(Notice.Severity.LOSS, "anim", "Se pierden ${scene.animations.size} animación(es)."))
        if (packedMr) objLosses.add(Notice(Notice.Severity.LOSS, "mr", "La textura metal/rugosidad se incluye pero no queda enlazada en el MTL."))
        if (ao) objLosses.add(Notice(Notice.Severity.LOSS, "ao", "La textura de oclusión se incluye pero no queda enlazada en el MTL."))
        if (multiUv) objLosses.add(Notice(Notice.Severity.LOSS, "uv1", "Se pierde el segundo juego de UV."))
        val fbxLosses = ArrayList<Notice>(glbLosses)
        if (skinned) fbxLosses.add(Notice(Notice.Severity.LOSS, "skin", "Se pierde el esqueleto y los pesos."))
        if (anim) fbxLosses.add(Notice(Notice.Severity.LOSS, "anim", "Se pierden ${scene.animations.size} animación(es)."))
        fbxLosses.add(Notice(Notice.Severity.LOSS, "pbr", "Materiales PBR aproximados con Phong (se conservan color base, normal y emisivo)."))
        if (packedMr) fbxLosses.add(Notice(Notice.Severity.LOSS, "mr", "No se exporta la textura metal/rugosidad."))
        if (ao) fbxLosses.add(Notice(Notice.Severity.LOSS, "ao", "No se exporta la textura de oclusión."))
        if (multiUv) fbxLosses.add(Notice(Notice.Severity.LOSS, "uv1", "Se pierde el segundo juego de UV."))
        val zipLosses = (glbLosses + objLosses + fbxLosses).distinctBy { it.code + it.message }
        return listOf(
            ExportOption(ExportFormat.GLB, true, null, glbLosses),
            ExportOption(ExportFormat.OBJ, true, null, objLosses),
            ExportOption(ExportFormat.FBX, true, null, fbxLosses),
            ExportOption(ExportFormat.FULL_ZIP, true, null, zipLosses),
        )
    }
}
