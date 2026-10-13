package app.universal3d.core.replicate

import app.universal3d.core.adapters.*
import app.universal3d.core.detect.DetectionReport
import app.universal3d.core.io.*
import app.universal3d.core.model.*
import app.universal3d.core.resources.*
import app.universal3d.core.security.FetchException
import app.universal3d.core.security.SafeFetcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

enum class FidelityLevel(val label: String) {
    EXACT_ORIGINAL("Exacta: archivo original"),
    OFFICIAL_EXPORT("Exportación oficial de la plataforma"),
    PARTIAL_CAPTURE("Parcial: geometría capturada de la GPU"),
    APPROXIMATE_VISUAL("Aproximada: reconstrucción visual"),
}

class Fidelity(val level: FidelityLevel, val preserved: List<String>, val missing: List<String>, val approximated: List<String>)

class ReplicationResult(
    val success: Boolean,
    val scene: Scene3D?,
    val fidelity: Fidelity?,
    val sourceDescription: String?,
    val failureReason: String?,
    /** Qué podría hacer el usuario a continuación (p. ej. reconstrucción visual aproximada). */
    val suggestions: List<String> = emptyList(),
    val offerVisualReconstruction: Boolean = false,
)

sealed class ReplicationEvent {
    data class Phase(val index: Int, val total: Int, val strategy: String, val label: String) : ReplicationEvent()
    /** Progreso real; [total] null = indeterminado. */
    data class Progress(val done: Long, val total: Long?, val label: String) : ReplicationEvent()
    data class Found(val count: Int, val what: String) : ReplicationEvent()
    data class Recoverable(val message: String) : ReplicationEvent()
    data class Finished(val result: ReplicationResult) : ReplicationEvent()
}

/** Canal hacia la página para capturar geometría WebGL; la implementación vive en la app (WebView). */
fun interface CaptureProvider { suspend fun captureWebGl(): String? }

class ReplicationInput(
    val report: DetectionReport,
    val fetcher: SafeFetcher,
    val registry: AdapterRegistry,
    val tokenFor: (String) -> String? = { null },
    val headersFor: (resourceUrl: String) -> Map<String, String> = { emptyMap() },
    val capture: CaptureProvider? = null,
    val maxBytes: Long = 256L * 1024 * 1024,
    val confirmLargeDownload: suspend (Long) -> Boolean = { true },
    /** Comprobación previa de memoria para un archivo de N bytes; debe lanzar una excepción con un mensaje comprensible. */
    val checkMemory: (Long) -> Unit = {},
)

/**
 * Ejecuta las estrategias en orden: archivo original -> exportación oficial -> geometría capturada -> (sugerencia) visual.
 * Nunca simula resultados: cada fase informa lo que realmente ocurrió.
 */
class ReplicationEngine {
    private val total = 3

    fun run(input: ReplicationInput): Flow<ReplicationEvent> = channelFlow {
        val failures = ArrayList<String>()
        // 1. Archivo original
        send(ReplicationEvent.Phase(1, total, "original", "Buscando un archivo original accesible"))
        val candidates = input.report.importableResources
            .filter { it.access != AccessState.REQUIRES_AUTH || true }
            .sortedWith(compareByDescending<DetectedResource> { it.contentVerified }.thenBy { DetectionReport.formatRank(it.format) })
            .take(3)
        send(ReplicationEvent.Found(candidates.size, "archivo(s) 3D candidato(s)"))
        for (c in candidates) {
            try {
                val scene = fetchAndImport(this, input, c, label = "Descargando ${c.format.displayName}")
                send(ReplicationEvent.Finished(success(scene, FidelityLevel.EXACT_ORIGINAL, "Archivo original ${c.format.displayName}: ${shortUrl(c.url)}")))
                return@channelFlow
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                val m = "No se pudo usar ${shortUrl(c.url)}: ${friendly(e)}"
                failures.add(m); send(ReplicationEvent.Recoverable(m))
            }
        }
        if (candidates.isEmpty()) failures.add("No hay un archivo 3D accesible referenciado por la página.")

        // 2. Exportación oficial
        send(ReplicationEvent.Phase(2, total, "official", "Buscando exportación oficial de la plataforma"))
        val ctx = AdapterContext(input.fetcher, input.tokenFor)
        val adapters = input.registry.forPage(input.report.pageUrl).filter { AdapterOperation.OFFICIAL_EXPORT in it.operations }
        if (adapters.isEmpty()) send(ReplicationEvent.Recoverable("Esta página no tiene un adaptador con exportación oficial."))
        for (a in adapters) {
            try {
                val r = a.officialExport(ctx, input.report.pageUrl) ?: continue
                send(ReplicationEvent.Found(1, "descarga oficial (${a.displayName})"))
                val scene = fetchAndImport(this, input, r.resource, label = "Descargando exportación oficial")
                val desc = "Exportación oficial de ${a.displayName}" + (r.license?.let { " · licencia: $it" } ?: "")
                send(ReplicationEvent.Finished(success(scene, FidelityLevel.OFFICIAL_EXPORT, desc)))
                return@channelFlow
            } catch (e: CancellationException) { throw e } catch (e: AdapterException) {
                failures.add(e.message ?: "Adaptador ${a.id} falló"); send(ReplicationEvent.Recoverable(e.message ?: "Error del adaptador"))
            } catch (e: Exception) {
                val m = "Exportación oficial falló: ${friendly(e)}"; failures.add(m); send(ReplicationEvent.Recoverable(m))
            }
        }

        // 3. Geometría accesible (captura WebGL)
        send(ReplicationEvent.Phase(3, total, "capture", "Capturando geometría accesible de la página"))
        val cap = input.capture
        if (cap == null || !input.report.webglActive && !input.report.captureAvailable) {
            failures.add("No hay geometría WebGL observable en esta página.")
            send(ReplicationEvent.Recoverable("No hay geometría WebGL observable en esta página."))
        } else {
            try {
                send(ReplicationEvent.Progress(0, null, "Solicitando buffers a la página"))
                val json = cap.captureWebGl()
                if (json == null) { failures.add("La página no entregó datos de captura.") ; send(ReplicationEvent.Recoverable("La página no entregó datos de captura.")) }
                else {
                    send(ReplicationEvent.Progress(json.length.toLong(), json.length.toLong(), "Procesando captura"))
                    val rec = WebGlCaptureReconstructor.reconstruct(json)
                    send(ReplicationEvent.Found(rec.drawsUsed, "malla(s) reconstruida(s) de ${rec.drawsRead} dibujos"))
                    send(ReplicationEvent.Finished(success(rec.scene, FidelityLevel.PARTIAL_CAPTURE, "Geometría capturada de WebGL (${rec.drawsUsed} malla(s))")))
                    return@channelFlow
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                val m = "Captura WebGL: ${friendly(e)}"; failures.add(m); send(ReplicationEvent.Recoverable(m))
            }
        }

        // 4. Nada recuperable: explicar qué falta y ofrecer la reconstrucción visual aproximada (otro módulo)
        val visualPossible = input.report.state != app.universal3d.core.detect.DetectionState.UNSUPPORTED_FORMAT
        send(ReplicationEvent.Finished(ReplicationResult(
            success = false, scene = null, fidelity = null, sourceDescription = null,
            failureReason = "No se pudo obtener geometría: " + failures.joinToString(" · "),
            suggestions = buildList {
                add("Si tenés el archivo original, importalo desde Biblioteca → Importar.")
                if (input.report.state == app.universal3d.core.detect.DetectionState.AUTH_REQUIRED) add("Iniciá sesión o concedé el permiso en la propia página y reintentá.")
                if (visualPossible) add("Podés intentar una reconstrucción visual aproximada con fotos o capturas del objeto (resultado aproximado, no el modelo original).")
            },
            offerVisualReconstruction = visualPossible,
        )))
    }

    private fun success(scene: Scene3D, level: FidelityLevel, desc: String): ReplicationResult {
        val preserved = ArrayList<String>(); val missing = ArrayList<String>(); val approx = ArrayList<String>()
        val ins = app.universal3d.core.inspect.ModelInspector.inspect(scene)
        preserved.add("Geometría: ${ins.triangleCount} triángulos en ${ins.meshCount} malla(s)")
        if (ins.hasUv) preserved.add("Coordenadas UV") else missing.add("Coordenadas UV")
        if (ins.hasNormals) preserved.add("Normales")
        if (ins.textureCount > 0) preserved.add("${ins.textureCount} textura(s)") else missing.add("Texturas")
        if (ins.materialCount > 0) preserved.add("${ins.materialCount} material(es)")
        if (ins.hasSkeleton) preserved.add("Esqueleto (${ins.boneCount} huesos)")
        if (ins.animations.isNotEmpty()) preserved.add("${ins.animations.size} animación(es)")
        scene.missingDependencies.forEach { missing.add("Dependencia no encontrada: $it") }
        scene.importNotices.filter { it.severity == Notice.Severity.LOSS }.forEach { missing.add(it.message) }
        scene.importNotices.filter { it.severity == Notice.Severity.WARNING }.forEach { approx.add(it.message) }
        if (level == FidelityLevel.PARTIAL_CAPTURE) { missing.add("Esqueleto y animaciones"); approx.add("Materiales aproximados; pose actual") }
        return ReplicationResult(true, scene, Fidelity(level, preserved, missing.distinct(), approx.distinct()), desc, null)
    }

    private fun shortUrl(u: String) = u.substringAfter("://").let { if (it.length > 60) it.take(57) + "…" else it }
    private fun friendly(e: Exception) = when (e) {
        is FetchException -> e.message ?: "error de red"
        is ModelImportException -> e.message ?: "formato inválido"
        else -> e.message ?: e::class.simpleName ?: "error"
    }

    private suspend fun fetchAndImport(scope: ProducerScope<ReplicationEvent>, input: ReplicationInput, r: DetectedResource, label: String): Scene3D {
        if (r.unsupportedExtension != null) throw UnsupportedFormatException("Formato .${r.unsupportedExtension} no compatible con el motor actual.")
        r.sizeBytes?.let { if (it > input.maxBytes) throw FetchException(FetchException.Kind.TOO_LARGE, "Archivo demasiado grande (${it / 1048576} MB).") ; if (it > 50L * 1024 * 1024 && !input.confirmLargeDownload(it)) throw FetchException(FetchException.Kind.POLICY, "Descarga cancelada por el usuario (archivo grande).") }
        r.sizeBytes?.let { input.checkMemory(it) }
        val files = ArrayList<BundleFile>()
        val main = input.fetcher.download(r.url, input.maxBytes, input.headersFor(r.url)) { done, total -> scope.trySend(ReplicationEvent.Progress(done, total, label)) }
        val name = ResourceClassifier.extensionOf(r.url).let { ext -> r.url.substringBefore('?').substringAfterLast('/').ifEmpty { "modelo.$ext" } }
        val fileName = SafeNames.fileName(name, name.substringAfterLast('.', "bin"))
        val baseDir = r.url.substringBefore('?').substringBeforeLast('/') + "/"
        when (r.format) {
            ModelFormat.ZIP -> return ModelImporter.importBundle(ModelImporter.unzip(main), fileName.substringBeforeLast('.'))
            ModelFormat.GLTF -> {
                files.add(BundleFile.ofBytes(fileName, main))
                val deps = DependencyResolver.forGltf(r.url, String(main, Charsets.UTF_8))
                fetchDeps(scope, input, deps, baseDir, files)
            }
            ModelFormat.OBJ -> {
                files.add(BundleFile.ofBytes(fileName, main))
                val mtls = DependencyResolver.forObj(r.url, String(main, Charsets.UTF_8))
                val got = fetchDeps(scope, input, mtls, baseDir, files)
                // texturas referenciadas por cada MTL descargado
                for ((ref, bytes) in got) if (ref.kind == ResourceKind.MATERIAL_LIBRARY) {
                    val texDeps = DependencyResolver.forMtl(ref.url, String(bytes, Charsets.UTF_8))
                    fetchDeps(scope, input, texDeps, baseDir, files)
                }
            }
            else -> files.add(BundleFile.ofBytes(fileName, main))
        }
        scope.trySend(ReplicationEvent.Progress(1, 1, "Importando"))
        return ModelImporter.importBundle(FileBundle(files), fileName.substringBeforeLast('.'))
    }

    /** Descarga dependencias únicas (tabla anti-duplicados). Las que fallen se reportan y la importación informará lo faltante. */
    private suspend fun fetchDeps(scope: ProducerScope<ReplicationEvent>, input: ReplicationInput, deps: List<ResourceRef>, baseDir: String, files: MutableList<BundleFile>): List<Pair<ResourceRef, ByteArray>> {
        val table = DependencyTable()
        deps.forEach { table.register(it, "main") }
        val out = ArrayList<Pair<ResourceRef, ByteArray>>()
        val unique = table.unique
        scope.trySend(ReplicationEvent.Found(unique.size, "dependencia(s) únicas"))
        for ((i, e) in unique.withIndex()) {
            try {
                val bytes = input.fetcher.download(e.ref.url, input.maxBytes, input.headersFor(e.ref.url)) { done, total -> scope.trySend(ReplicationEvent.Progress(done, total, "Dependencia ${i + 1}/${unique.size}")) }
                val rel = if (e.ref.url.startsWith(baseDir)) e.ref.url.removePrefix(baseDir).substringBefore('?') else e.ref.url.substringBefore('?').substringAfterLast('/')
                files.add(BundleFile.ofBytes(java.net.URLDecoder.decode(rel, "UTF-8"), bytes))
                out.add(e.ref to bytes)
            } catch (ex: CancellationException) { throw ex } catch (ex: Exception) {
                scope.trySend(ReplicationEvent.Recoverable("No se pudo descargar ${e.ref.url.substringAfterLast('/').substringBefore('?')}: ${ex.message}"))
            }
        }
        return out
    }
}
