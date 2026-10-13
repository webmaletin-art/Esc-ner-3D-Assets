package app.universal3d.core.resources

import app.universal3d.core.model.ModelFormat
import java.net.URI
import java.security.MessageDigest

enum class ResourceKind { MODEL, TEXTURE, BUFFER, MATERIAL_LIBRARY, ARCHIVE, VIEWER_PAGE, UNKNOWN }

/** De dónde se obtuvo la pista del recurso. */
enum class Provenance { DOM, NETWORK, MODEL_VIEWER, ADAPTER, WEBGL_CAPTURE, USER_IMPORT }

/** Separado de la detección y de la conversión: estado de acceso. */
enum class AccessState {
    /** Se ha visto la URL pero aún no se comprobó. */
    UNVERIFIED,
    /** Se comprobó con una petición ligera que el contenido es accesible y del tipo esperado. */
    ACCESSIBLE,
    REQUIRES_AUTH,
    NOT_FOUND,
    TOO_LARGE,
    BLOCKED_BY_POLICY,
    CONTENT_MISMATCH,
    ERROR,
}

class ResourceRef(val url: String, val kind: ResourceKind, val role: String? = null)

/**
 * Recurso detectado en una página. No implica autorización de descarga ni capacidad de conversión:
 * esas propiedades se expresan en [access] y [conversions] por separado.
 */
data class DetectedResource(
    val id: String,
    val url: String,
    val origin: Provenance,
    val adapterId: String,
    val kind: ResourceKind,
    val format: ModelFormat,
    /** Extensión 3D reconocida pero no importable (p. ej. "usdz"). */
    val unsupportedExtension: String? = null,
    val sizeBytes: Long? = null,
    val access: AccessState = AccessState.UNVERIFIED,
    /** true si el contenido real coincide con el formato esperado (firma/magic number). */
    val contentVerified: Boolean = false,
    val dependencies: List<ResourceRef> = emptyList(),
    val role: String? = null,
    val title: String? = null,
    val notes: List<String> = emptyList(),
) {
    val importable: Boolean get() = kind == ResourceKind.MODEL && format != ModelFormat.UNKNOWN && unsupportedExtension == null
    val conversions: Set<ModelFormat> get() = if (importable) setOf(ModelFormat.GLB, ModelFormat.OBJ, ModelFormat.FBX, ModelFormat.ZIP) else emptySet()
    val incompatibilityReason: String?
        get() = when {
            unsupportedExtension != null -> "Formato .$unsupportedExtension no compatible con el motor actual."
            kind == ResourceKind.MODEL && format == ModelFormat.UNKNOWN -> "Formato desconocido."
            access == AccessState.REQUIRES_AUTH -> "Requiere autorización."
            access == AccessState.CONTENT_MISMATCH -> "El contenido no coincide con el formato anunciado."
            else -> null
        }

    companion object {
        fun idFor(url: String): String {
            val n = ResourceClassifier.normalizeUrl(url)
            return MessageDigest.getInstance("SHA-1").digest(n.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        }
    }
}

object ResourceClassifier {
    private val TEXTURE_EXT = setOf("png", "jpg", "jpeg", "webp", "ktx2", "basis", "bmp", "tga", "hdr", "exr")
    /** Extensiones 3D conocidas que el motor actual NO importa. */
    val UNSUPPORTED_3D = setOf("usdz", "usd", "usda", "usdc", "dae", "stl", "ply", "3ds", "blend", "x3d", "x3dv", "wrl", "3mf", "step", "stp", "iges", "igs", "off", "splat", "ksplat", "spz", "max", "ma", "mb", "c4d", "lwo", "abc", "vox")

    fun extensionOf(url: String): String {
        val path = try { URI(url).path ?: url } catch (_: Exception) { url.substringBefore('?').substringBefore('#') }
        val name = path.substringAfterLast('/')
        return if (name.contains('.')) name.substringAfterLast('.').lowercase() else ""
    }

    fun normalizeUrl(url: String): String {
        val noFrag = url.substringBefore('#')
        return try {
            val u = URI(noFrag)
            val host = u.host?.lowercase() ?: return noFrag
            val port = if (u.port == -1 || (u.scheme == "https" && u.port == 443) || (u.scheme == "http" && u.port == 80)) "" else ":${u.port}"
            "${u.scheme?.lowercase()}://$host$port${u.rawPath ?: ""}${u.rawQuery?.let { "?$it" } ?: ""}"
        } catch (_: Exception) { noFrag }
    }

    /** Clasifica una URL por extensión y, si se conoce, por tipo MIME. */
    fun classify(url: String, mime: String? = null, origin: Provenance = Provenance.NETWORK, adapterId: String = "generic"): DetectedResource? {
        val ext = extensionOf(url)
        val byMime = app.universal3d.core.io.FormatSniffer.byMime(mime)
        val fmt = when (ext) {
            "glb", "vrm" -> ModelFormat.GLB
            "gltf" -> ModelFormat.GLTF
            "obj" -> ModelFormat.OBJ
            "fbx" -> ModelFormat.FBX
            "zip" -> ModelFormat.ZIP
            else -> byMime
        }
        val id = DetectedResource.idFor(url)
        return when {
            fmt == ModelFormat.ZIP -> DetectedResource(id, url, origin, adapterId, ResourceKind.ARCHIVE, ModelFormat.ZIP)
            fmt != ModelFormat.UNKNOWN -> DetectedResource(id, url, origin, adapterId, ResourceKind.MODEL, fmt)
            ext in UNSUPPORTED_3D -> DetectedResource(id, url, origin, adapterId, ResourceKind.MODEL, ModelFormat.UNKNOWN, unsupportedExtension = ext)
            ext == "bin" -> DetectedResource(id, url, origin, adapterId, ResourceKind.BUFFER, ModelFormat.UNKNOWN)
            ext == "mtl" -> DetectedResource(id, url, origin, adapterId, ResourceKind.MATERIAL_LIBRARY, ModelFormat.UNKNOWN)
            ext in TEXTURE_EXT -> DetectedResource(id, url, origin, adapterId, ResourceKind.TEXTURE, ModelFormat.UNKNOWN)
            else -> null
        }
    }

    fun isModelCandidate(url: String): Boolean = classify(url)?.let { it.kind == ResourceKind.MODEL || it.kind == ResourceKind.ARCHIVE } ?: false

    /** Resuelve una referencia relativa contra una URL base. */
    fun resolve(base: String, ref: String): String? = try {
        if (ref.startsWith("data:")) null else URI(base).resolve(ref.replace(" ", "%20")).toString()
    } catch (_: Exception) { null }
}

/**
 * Tabla de dependencias: evita descargar o empaquetar dos veces la misma textura/recurso
 * (clave = URL normalizada o nombre de archivo normalizado).
 */
class DependencyTable {
    private val entries = LinkedHashMap<String, Entry>()

    class Entry(val key: String, val ref: ResourceRef, val referencedBy: MutableSet<String> = linkedSetOf())

    /** Registra una dependencia; devuelve true si es nueva. */
    fun register(ref: ResourceRef, referencedBy: String): Boolean {
        val key = ResourceClassifier.normalizeUrl(ref.url).lowercase()
        val existing = entries[key]
        if (existing != null) { existing.referencedBy.add(referencedBy); return false }
        entries[key] = Entry(key, ref, linkedSetOf(referencedBy))
        return true
    }

    val unique: List<Entry> get() = entries.values.toList()
    val size: Int get() = entries.size
    fun referencesOf(url: String): Set<String> = entries[ResourceClassifier.normalizeUrl(url).lowercase()]?.referencedBy ?: emptySet()
}

/** Obtiene las dependencias externas de un glTF/OBJ/MTL ya descargado, resueltas contra su URL. */
object DependencyResolver {
    fun forGltf(baseUrl: String, json: String): List<ResourceRef> =
        app.universal3d.core.io.GltfReader.listExternalUris(json).mapNotNull { ref ->
            ResourceClassifier.resolve(baseUrl, ref)?.let { ResourceRef(it, if (ResourceClassifier.extensionOf(ref) == "bin") ResourceKind.BUFFER else ResourceKind.TEXTURE) }
        }

    fun forObj(baseUrl: String, objText: String): List<ResourceRef> {
        val out = ArrayList<ResourceRef>()
        for (raw in objText.lineSequence()) {
            val l = raw.substringBefore('#').trim()
            if (l.startsWith("mtllib ")) ResourceClassifier.resolve(baseUrl, l.substring(7).trim())?.let { out.add(ResourceRef(it, ResourceKind.MATERIAL_LIBRARY)) }
        }
        return out.distinctBy { it.url }
    }

    fun forMtl(baseUrl: String, mtlText: String): List<ResourceRef> {
        val out = ArrayList<ResourceRef>()
        for (raw in mtlText.lineSequence()) {
            val parts = raw.substringBefore('#').trim().split(Regex("\\s+"))
            val key = parts[0].lowercase()
            if ((key.startsWith("map_") || key == "bump" || key == "norm") && parts.size > 1) {
                ResourceClassifier.resolve(baseUrl, parts.last())?.let { out.add(ResourceRef(it, ResourceKind.TEXTURE, key)) }
            }
        }
        return out
    }
}
