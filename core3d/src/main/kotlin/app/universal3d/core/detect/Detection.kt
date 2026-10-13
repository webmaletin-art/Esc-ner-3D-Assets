package app.universal3d.core.detect

import app.universal3d.core.model.ModelFormat
import app.universal3d.core.resources.*
import kotlinx.serialization.json.*

/** Estados de detección mostrados al usuario. */
enum class DetectionState(val title: String) {
    NONE("No se detectaron modelos 3D en esta página"),
    FILE_DETECTED("Archivo 3D detectado"),
    MODEL_CONFIRMED("Modelo 3D detectado"),
    VIEWER_PROBABLE("Visor 3D probable"),
    VISIBLE_NO_GEOMETRY("Modelo visible, archivo original no disponible"),
    GEOMETRY_CAPTURABLE("Geometría accesible; se puede capturar"),
    AUTH_REQUIRED("La página requiere autorización"),
    UNSUPPORTED_FORMAT("Formato no compatible con el motor actual"),
    INCOMPLETE("Detección incompleta por limitaciones del sitio"),
}

class ViewerSignal(val type: String, val src: String?, val iosSrc: String? = null, val poster: String? = null)
class CanvasSignal(val width: Int, val height: Int, val glVersion: String?, val drawCalls: Long, val triangles: Long, val visible: Boolean)
class CaptureSignal(val available: Boolean, val draws: Long, val bufferBytes: Long, val truncated: Boolean)

/** Datos observados en la página (no confiables): se validan y limitan al parsear. */
class PageSignals(
    val url: String,
    val title: String? = null,
    val viewers: List<ViewerSignal> = emptyList(),
    val links: List<String> = emptyList(),
    val iframes: List<String> = emptyList(),
    val libs: List<String> = emptyList(),
    val canvases: List<CanvasSignal> = emptyList(),
    val net: List<String> = emptyList(),
    val capture: CaptureSignal? = null,
    val crossOriginIframeBlocked: Boolean = false,
) {
    companion object {
        const val MAX_JSON = 512 * 1024
        private const val MAX_URLS = 200
        private val KNOWN_LIBS = setOf("three", "babylon", "aframe", "playcanvas", "cesium", "sketchfab", "model-viewer", "pixi", "regl", "unity", "godot", "x3dom", "vtk", "deck")

        fun parse(json: String): PageSignals? {
            if (json.length > MAX_JSON) return null
            val o = try { Json.parseToJsonElement(json) as? JsonObject } catch (_: Exception) { null } ?: return null
            fun strs(k: String): List<String> = (o[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.filter { it.length <= 2048 && it.isNotBlank() }?.distinct()?.take(MAX_URLS) ?: emptyList()
            fun s(e: JsonObject, k: String) = (e[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.length <= 2048 }
            fun num(e: JsonObject, k: String) = (e[k] as? JsonPrimitive)?.doubleOrNull?.toLong()?.coerceAtLeast(0) ?: 0L
            val viewers = (o["viewers"] as? JsonArray)?.take(20)?.mapNotNull { e ->
                val eo = e as? JsonObject ?: return@mapNotNull null
                ViewerSignal(s(eo, "type") ?: "unknown", s(eo, "src"), s(eo, "iosSrc"), s(eo, "poster"))
            } ?: emptyList()
            val canv = (o["canvases"] as? JsonArray)?.take(20)?.mapNotNull { e ->
                val eo = e as? JsonObject ?: return@mapNotNull null
                CanvasSignal(num(eo, "w").toInt(), num(eo, "h").toInt(), s(eo, "gl"), num(eo, "draws"), num(eo, "tris"), (eo["visible"] as? JsonPrimitive)?.booleanOrNull ?: true)
            } ?: emptyList()
            val cap = (o["capture"] as? JsonObject)?.let { CaptureSignal((it["available"] as? JsonPrimitive)?.booleanOrNull ?: false, num(it, "draws"), num(it, "bufferBytes"), (it["truncated"] as? JsonPrimitive)?.booleanOrNull ?: false) }
            return PageSignals(
                url = (o["url"] as? JsonPrimitive)?.contentOrNull?.take(2048) ?: return null,
                title = (o["title"] as? JsonPrimitive)?.contentOrNull?.take(200),
                viewers = viewers, links = strs("links"), iframes = strs("iframes"),
                libs = strs("libs").map { it.lowercase() }.filter { it in KNOWN_LIBS }.distinct(),
                canvases = canv, net = strs("net"), capture = cap,
                crossOriginIframeBlocked = (o["xoFrames"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }
    }
}

data class DetectionReport(
    val pageUrl: String,
    val state: DetectionState,
    val resources: List<DetectedResource>,
    val viewerHints: List<String>,
    val webglActive: Boolean,
    val captureAvailable: Boolean,
    val adapterIds: List<String>,
    val detail: String?,
    val probing: Boolean = false,
) {
    val title: String get() = state.title
    val primaryModel: DetectedResource? get() = resources.filter { it.importable && it.access != AccessState.NOT_FOUND && it.access != AccessState.CONTENT_MISMATCH }
        .sortedWith(compareByDescending<DetectedResource> { it.contentVerified }.thenBy { formatRank(it.format) }.thenByDescending { it.sizeBytes ?: 0 }).firstOrNull()
    val importableResources get() = resources.filter { it.importable && it.access != AccessState.CONTENT_MISMATCH && it.access != AccessState.NOT_FOUND }
    /** El botón "Copiar / Replicar modelo" se ofrece cuando hay algo que intentar. */
    val canReplicate: Boolean get() = state != DetectionState.NONE && state != DetectionState.UNSUPPORTED_FORMAT
    /** Opciones de formato: sólo si hay un recurso importable y accesible. */
    val canExportFormats: Boolean get() = primaryModel?.let { it.access != AccessState.REQUIRES_AUTH } == true

    companion object {
        fun formatRank(f: ModelFormat) = when (f) { ModelFormat.GLB -> 0; ModelFormat.GLTF -> 1; ModelFormat.FBX -> 2; ModelFormat.OBJ -> 3; ModelFormat.ZIP -> 4; else -> 9 }
        fun none(url: String) = DetectionReport(url, DetectionState.NONE, emptyList(), emptyList(), false, false, emptyList(), null)
    }
}

/** Reglas puras de clasificación: no hace red ni accede a la página. */
object DetectionClassifier {
    fun analyze(
        pageUrl: String, signals: PageSignals?, networkUrls: Collection<String>,
        adapters: List<app.universal3d.core.adapters.PlatformAdapter>, probed: Map<String, DetectedResource> = emptyMap(), probing: Boolean = false,
    ): DetectionReport {
        val byId = LinkedHashMap<String, DetectedResource>()
        fun add(r: DetectedResource?) {
            r ?: return
            val prev = byId[r.id]
            byId[r.id] = if (prev == null) r else prev.copy(
                origin = if (prev.origin == Provenance.NETWORK) r.origin else prev.origin,
                title = prev.title ?: r.title, notes = (prev.notes + r.notes).distinct(), role = prev.role ?: r.role,
            )
        }
        val hints = ArrayList<String>()
        val matched = adapters.filter { it.matches(pageUrl) }
        val urls = LinkedHashSet<Pair<String, Provenance>>()
        signals?.viewers?.forEach { v ->
            v.src?.let { urls.add(absolute(pageUrl, it) to Provenance.MODEL_VIEWER) }
            v.iosSrc?.let { urls.add(absolute(pageUrl, it) to Provenance.MODEL_VIEWER) }
            hints.add(v.type)
        }
        signals?.links?.forEach { urls.add(absolute(pageUrl, it) to Provenance.DOM) }
        signals?.net?.forEach { urls.add(absolute(pageUrl, it) to Provenance.NETWORK) }
        networkUrls.forEach { urls.add(it to Provenance.NETWORK) }
        for ((u, prov) in urls) ResourceClassifier.classify(u, null, prov)?.let { if (it.kind == ResourceKind.MODEL || it.kind == ResourceKind.ARCHIVE) add(it) }
        // Adaptadores específicos
        if (signals != null) for (a in matched) a.identify(pageUrl, signals).forEach { add(it) }
        else for (a in matched) a.identify(pageUrl, PageSignals(pageUrl)).forEach { add(it) }
        // Sustituir con resultados de sondeo
        for ((id, r) in probed) if (byId.containsKey(id)) byId[id] = r
        val resources = byId.values.toList()

        signals?.libs?.forEach { hints.add(it) }
        signals?.iframes?.forEach { f -> if (f.contains("sketchfab.com")) hints.add("sketchfab") }
        val distinctHints = hints.distinct()
        val activeGl = signals?.canvases?.any { it.glVersion != null && it.drawCalls > 0 && it.triangles > 0 && it.visible } == true
        val anyGl = signals?.canvases?.any { it.glVersion != null } == true
        val captureGeo = signals?.capture?.let { it.available && it.draws > 0 && it.bufferBytes > 0 } == true
        val importable = resources.filter { it.importable }
        val verified = importable.filter { it.contentVerified && it.access == AccessState.ACCESSIBLE }
        val unsupported = resources.filter { it.unsupportedExtension != null }
        val authOnly = importable.isNotEmpty() && importable.all { it.access == AccessState.REQUIRES_AUTH }
        val adapterIds = matched.map { it.id }.filter { it != "generic" }

        val (state, detail) = when {
            verified.isNotEmpty() -> DetectionState.MODEL_CONFIRMED to "Archivo ${verified.first().format.displayName} verificado y accesible."
            authOnly -> DetectionState.AUTH_REQUIRED to "El recurso 3D existe pero requiere iniciar sesión o un permiso. Podés autorizarlo en la propia página o con un token en Ajustes (si el adaptador lo admite)."
            importable.any { it.access != AccessState.NOT_FOUND && it.access != AccessState.CONTENT_MISMATCH } ->
                DetectionState.FILE_DETECTED to (if (probing) "Verificando acceso al archivo…" else "Se encontró una referencia a un archivo 3D; aún no se verificó su contenido.")
            unsupported.isNotEmpty() && !captureGeo -> DetectionState.UNSUPPORTED_FORMAT to "Formatos detectados: ${unsupported.mapNotNull { it.unsupportedExtension }.distinct().joinToString { "." + it }}. El motor actual importa GLB, glTF, OBJ y FBX."
            captureGeo -> DetectionState.GEOMETRY_CAPTURABLE to "Se observó geometría en la GPU de la página (${(signals!!.capture!!.bufferBytes / 1024)} KB capturables). La reconstrucción será parcial."
            distinctHints.isNotEmpty() && activeGl -> DetectionState.VISIBLE_NO_GEOMETRY to "Hay un visor 3D activo (${distinctHints.joinToString()}), pero no se encontró un archivo accesible."
            activeGl || (distinctHints.isNotEmpty() && anyGl) -> DetectionState.VIEWER_PROBABLE to "Hay un canvas WebGL con actividad de dibujo; no se puede confirmar que contenga un modelo (podría ser un juego o un gráfico)."
            signals?.crossOriginIframeBlocked == true && (distinctHints.isNotEmpty() || anyGl) -> DetectionState.INCOMPLETE to "Parte del contenido está en marcos de otro origen que el navegador no permite inspeccionar."
            distinctHints.isNotEmpty() || (signals?.iframes?.any { isKnownViewerFrame(it) } == true) -> DetectionState.VIEWER_PROBABLE to "Se reconoció un visor 3D (${distinctHints.joinToString().ifEmpty { "incrustado" }}) sin geometría accesible todavía."
            else -> DetectionState.NONE to null
        }
        return DetectionReport(pageUrl, state, resources, distinctHints, activeGl, captureGeo, adapterIds, detail, probing)
    }

    private fun isKnownViewerFrame(u: String): Boolean = listOf("sketchfab.com/models", "p3d.in", "myminifactory.com/3d-model", "3dviewer.", "viewer.", "/embed/model").any { u.contains(it) }

    private fun absolute(base: String, ref: String): String = ResourceClassifier.resolve(base, ref) ?: ref
}
