package app.universal3d.core.adapters

import app.universal3d.core.detect.PageSignals
import app.universal3d.core.model.ModelFormat
import app.universal3d.core.resources.*
import app.universal3d.core.security.SafeFetcher
import kotlinx.serialization.json.*
import java.net.URI

enum class AdapterOperation { IDENTIFY, REWRITE_URL, OFFICIAL_EXPORT, METADATA }
enum class AuthRequirement { NONE, OPTIONAL_TOKEN, REQUIRED_TOKEN }

/** Contexto que se entrega a las operaciones de red de un adaptador. */
class AdapterContext(val fetcher: SafeFetcher, val tokenFor: (adapterId: String) -> String?)

class OfficialExportResult(val resource: DetectedResource, val license: String?, val title: String?, val note: String? = null)

/** Error legible de un adaptador (p. ej. falta token o el modelo no es descargable). */
class AdapterException(message: String, val needsToken: Boolean = false) : Exception(message)

/**
 * Cada plataforma se integra con este contrato y se registra en [AdapterRegistry]; el núcleo no cambia.
 */
interface PlatformAdapter {
    val id: String
    val displayName: String
    /** Sufijos de dominio soportados (vacío = cualquiera). */
    val domains: Set<String>
    val viewerTypes: Set<String>
    val recognizedResources: Set<ResourceKind>
    val operations: Set<AdapterOperation>
    val authRequirement: AuthRequirement
    val inputFormats: Set<ModelFormat>
    val outputFormats: Set<ModelFormat>
    val limitations: List<String>

    fun matches(pageUrl: String): Boolean
    fun identify(pageUrl: String, signals: PageSignals): List<DetectedResource>
    /** Exportación oficial documentada, si existe. Debe lanzar [AdapterException] con un motivo comprensible. */
    suspend fun officialExport(ctx: AdapterContext, pageUrl: String): OfficialExportResult? = null
}

fun hostMatches(url: String, domains: Set<String>): Boolean {
    val host = try { URI(url).host?.lowercase() } catch (_: Exception) { null } ?: return false
    return domains.any { host == it || host.endsWith(".$it") }
}

class AdapterRegistry(adapters: List<PlatformAdapter>) {
    val all: List<PlatformAdapter> = adapters
    fun forPage(url: String) = all.filter { it.matches(url) }
    fun byId(id: String) = all.firstOrNull { it.id == id }

    companion object {
        fun default() = AdapterRegistry(listOf(GenericAdapter, GitHubAdapter, SketchfabAdapter()))
    }
}

/** Detector genérico: siempre activo; se apoya en señales (DOM, red, model-viewer) y no en selectores frágiles. */
object GenericAdapter : PlatformAdapter {
    override val id = "generic"
    override val displayName = "Detector genérico"
    override val domains = emptySet<String>()
    override val viewerTypes = setOf("model-viewer", "three", "babylon", "aframe", "playcanvas")
    override val recognizedResources = setOf(ResourceKind.MODEL, ResourceKind.ARCHIVE, ResourceKind.TEXTURE, ResourceKind.BUFFER, ResourceKind.MATERIAL_LIBRARY)
    override val operations = setOf(AdapterOperation.IDENTIFY)
    override val authRequirement = AuthRequirement.NONE
    override val inputFormats = setOf(ModelFormat.GLB, ModelFormat.GLTF, ModelFormat.OBJ, ModelFormat.FBX, ModelFormat.ZIP)
    override val outputFormats = inputFormats
    override val limitations = listOf("Sólo ve recursos referenciados en el DOM, la red o <model-viewer>.", "No inspecciona marcos de otro origen ni contenido empaquetado/ofuscado.")
    override fun matches(pageUrl: String) = true
    override fun identify(pageUrl: String, signals: PageSignals): List<DetectedResource> = emptyList() // la clasificación genérica la hace DetectionClassifier
}

/** github.com/<o>/<r>/blob/<ref>/<ruta>.glb -> raw.githubusercontent.com (patrón documentado de GitHub). */
object GitHubAdapter : PlatformAdapter {
    override val id = "github"
    override val displayName = "GitHub (archivos)"
    override val domains = setOf("github.com", "raw.githubusercontent.com")
    override val viewerTypes = setOf("github-blob")
    override val recognizedResources = setOf(ResourceKind.MODEL, ResourceKind.ARCHIVE)
    override val operations = setOf(AdapterOperation.IDENTIFY, AdapterOperation.REWRITE_URL)
    override val authRequirement = AuthRequirement.NONE
    override val inputFormats = setOf(ModelFormat.GLB, ModelFormat.GLTF, ModelFormat.OBJ, ModelFormat.FBX, ModelFormat.ZIP)
    override val outputFormats = inputFormats
    override val limitations = listOf("Sólo repositorios públicos.", "Archivos grandes (Git LFS) pueden devolver un puntero en lugar del binario: se detecta por firma.")
    private val BLOB = Regex("^/([^/]+)/([^/]+)/(?:blob|raw)/([^/]+)/(.+)$")

    override fun matches(pageUrl: String) = hostMatches(pageUrl, domains)

    fun rawUrl(pageUrl: String): String? {
        val u = try { URI(pageUrl) } catch (_: Exception) { return null }
        if (u.host.equals("raw.githubusercontent.com", true)) return pageUrl
        if (!u.host.equals("github.com", true)) return null
        val m = BLOB.matchEntire(u.rawPath ?: return null) ?: return null
        val (owner, repo, ref, path) = m.destructured
        return "https://raw.githubusercontent.com/$owner/$repo/$ref/$path"
    }

    override fun identify(pageUrl: String, signals: PageSignals): List<DetectedResource> {
        val raw = rawUrl(pageUrl) ?: return emptyList()
        return listOfNotNull(ResourceClassifier.classify(raw, null, Provenance.ADAPTER, id)?.takeIf { it.kind == ResourceKind.MODEL || it.kind == ResourceKind.ARCHIVE })
    }
}

/**
 * Sketchfab: identifica el UID del modelo (páginas y embeds) y, **sólo con el token personal de API del usuario** y si el
 * modelo es descargable, utiliza la API oficial de descarga (https://sketchfab.com/developers/download-api).
 * Sin token sólo consulta metadatos públicos. No evade restricciones: si el autor no habilitó la descarga, se informa.
 */
class SketchfabAdapter(private val apiBase: String = "https://api.sketchfab.com") : PlatformAdapter {
    override val id = "sketchfab"
    override val displayName = "Sketchfab (API oficial)"
    override val domains = setOf("sketchfab.com")
    override val viewerTypes = setOf("sketchfab")
    override val recognizedResources = setOf(ResourceKind.VIEWER_PAGE, ResourceKind.ARCHIVE, ResourceKind.MODEL)
    override val operations = setOf(AdapterOperation.IDENTIFY, AdapterOperation.METADATA, AdapterOperation.OFFICIAL_EXPORT)
    override val authRequirement = AuthRequirement.REQUIRED_TOKEN
    override val inputFormats = emptySet<ModelFormat>()
    override val outputFormats = setOf(ModelFormat.GLB, ModelFormat.GLTF, ModelFormat.ZIP)
    override val limitations = listOf(
        "La descarga oficial requiere un token personal de API de Sketchfab y que el autor haya habilitado la descarga.",
        "Se respeta la licencia del modelo: se muestra al descargar.",
        "No verificado contra el servicio real en este entorno de desarrollo; el análisis de respuestas está cubierto por pruebas con muestras de la documentación.",
    )

    override fun matches(pageUrl: String) = hostMatches(pageUrl, domains)

    fun modelUid(url: String): String? {
        val path = try { URI(url).path ?: return null } catch (_: Exception) { return null }
        Regex("/models/([0-9a-f]{32})").find(path)?.let { return it.groupValues[1] }
        Regex("/3d-models/(?:[^/]*-)?([0-9a-f]{32})/?$").find(path)?.let { return it.groupValues[1] }
        return null
    }

    override fun identify(pageUrl: String, signals: PageSignals): List<DetectedResource> {
        val uids = LinkedHashSet<String>()
        modelUid(pageUrl)?.let { uids.add(it) }
        signals.iframes.forEach { f -> if (hostMatches(f, domains)) modelUid(f)?.let { uids.add(it) } }
        return uids.map { uid ->
            val url = "https://sketchfab.com/3d-models/$uid"
            DetectedResource(
                id = DetectedResource.idFor(url), url = url, origin = Provenance.ADAPTER, adapterId = id, kind = ResourceKind.VIEWER_PAGE,
                format = ModelFormat.UNKNOWN, role = uid, title = signals.title,
                notes = listOf("Modelo Sketchfab $uid. La descarga oficial necesita token de API y que el modelo sea descargable."),
            )
        }
    }

    class Metadata(val uid: String, val name: String?, val downloadable: Boolean, val licenseLabel: String?, val author: String?, val faceCount: Long?)

    fun parseMetadata(json: String): Metadata? {
        val o = try { Json.parseToJsonElement(json) as? JsonObject } catch (_: Exception) { null } ?: return null
        val uid = (o["uid"] as? JsonPrimitive)?.contentOrNull ?: return null
        return Metadata(
            uid, (o["name"] as? JsonPrimitive)?.contentOrNull, (o["isDownloadable"] as? JsonPrimitive)?.booleanOrNull ?: false,
            ((o["license"] as? JsonObject)?.get("label") as? JsonPrimitive)?.contentOrNull ?: (o["license"] as? JsonPrimitive)?.contentOrNull,
            ((o["user"] as? JsonObject)?.get("displayName") as? JsonPrimitive)?.contentOrNull,
            (o["faceCount"] as? JsonPrimitive)?.longOrNull,
        )
    }

    class DownloadLink(val key: String, val url: String, val size: Long?, val expires: Long?)

    /** Orden de preferencia: glb > gltf > source > cualquier otro con url. */
    fun parseDownloadResponse(json: String): DownloadLink? {
        val o = try { Json.parseToJsonElement(json) as? JsonObject } catch (_: Exception) { null } ?: return null
        val prefs = listOf("glb", "gltf", "source", "usdz")
        val keys = prefs + o.keys.filter { it !in prefs }
        for (k in keys) {
            val e = o[k] as? JsonObject ?: continue
            val url = (e["url"] as? JsonPrimitive)?.contentOrNull ?: continue
            if (k == "usdz") continue
            return DownloadLink(k, url, (e["size"] as? JsonPrimitive)?.longOrNull, (e["expires"] as? JsonPrimitive)?.longOrNull)
        }
        return null
    }

    override suspend fun officialExport(ctx: AdapterContext, pageUrl: String): OfficialExportResult? {
        val uid = modelUid(pageUrl) ?: return null
        val metaBytes = try { ctx.fetcher.download("$apiBase/v3/models/$uid", 1_000_000) } catch (e: Exception) {
            throw AdapterException("No se pudieron leer los metadatos de Sketchfab: ${e.message}")
        }
        val meta = parseMetadata(String(metaBytes, Charsets.UTF_8)) ?: throw AdapterException("Respuesta de Sketchfab no reconocida.")
        if (!meta.downloadable) throw AdapterException("El autor no habilitó la descarga de este modelo en Sketchfab (${meta.licenseLabel ?: "licencia no indicada"}). No hay exportación oficial disponible.")
        val token = ctx.tokenFor(id) ?: throw AdapterException("Este modelo es descargable pero necesitás un token personal de API de Sketchfab (Ajustes → Integraciones).", needsToken = true)
        val respBytes = try {
            ctx.fetcher.download("$apiBase/v3/models/$uid/download", 1_000_000, mapOf("Authorization" to "Token $token"))
        } catch (e: app.universal3d.core.security.FetchException) {
            if (e.kind == app.universal3d.core.security.FetchException.Kind.AUTH) throw AdapterException("Sketchfab rechazó el token (HTTP ${e.httpCode}). Revisalo en Ajustes.", needsToken = true)
            throw AdapterException("Falló la solicitud de descarga oficial: ${e.message}")
        }
        val link = parseDownloadResponse(String(respBytes, Charsets.UTF_8)) ?: throw AdapterException("Sketchfab no devolvió un enlace de descarga compatible.")
        val ext = if (link.key == "glb") "glb" else "zip"
        val url = link.url
        val res = DetectedResource(
            id = DetectedResource.idFor(url), url = url, origin = Provenance.ADAPTER, adapterId = id,
            kind = if (ext == "zip") ResourceKind.ARCHIVE else ResourceKind.MODEL,
            format = if (ext == "zip") ModelFormat.ZIP else ModelFormat.GLB, sizeBytes = link.size, access = AccessState.ACCESSIBLE,
            title = meta.name, notes = listOf("Licencia: ${meta.licenseLabel ?: "no indicada"}", "Autor: ${meta.author ?: "desconocido"}", "Enlace temporal (caduca en ${link.expires ?: "pocos"} s)."),
        )
        return OfficialExportResult(res, meta.licenseLabel, meta.name, "Descarga oficial de Sketchfab; respetá la licencia y atribución del autor.")
    }
}
