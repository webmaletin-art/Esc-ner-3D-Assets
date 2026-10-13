package app.universal3d.inspector.work

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import app.universal3d.core.detect.DetectionReport
import app.universal3d.core.export.ModelExporter
import app.universal3d.core.inspect.ExportFormat
import app.universal3d.core.io.*
import app.universal3d.core.model.ModelFormat
import app.universal3d.core.model.Scene3D
import app.universal3d.core.recon.*
import app.universal3d.core.replicate.*
import app.universal3d.core.resources.DetectedResource
import app.universal3d.core.tasks.TaskOutcome
import app.universal3d.inspector.AppContainer
import app.universal3d.inspector.LoadedModel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class ReplicationUi(
    val running: Boolean = true,
    val phaseIndex: Int = 0, val phaseTotal: Int = 3, val phaseLabel: String = "Preparando…",
    val progressDone: Long = 0, val progressTotal: Long? = null, val progressLabel: String = "",
    val found: List<String> = emptyList(), val errors: List<String> = emptyList(),
    val result: ReplicationResult? = null, val taskId: Long = -1,
)

/** Orquesta las operaciones de larga duración como tareas cancelables (todas fuera del hilo principal). */
class Pipelines(private val c: AppContainer, private val resolver: ContentResolver) {
    private val _replication = MutableStateFlow<ReplicationUi?>(null)
    val replication: StateFlow<ReplicationUi?> = _replication.asStateFlow()
    fun dismissReplication() { _replication.value?.takeIf { it.running }?.let { c.tasks.cancel(it.taskId) }; _replication.value = null }

    // ------------------------------------------------------------ importación local
    class PickedFile(val uri: Uri, val name: String, val size: Long)

    fun describe(uri: Uri): PickedFile {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "archivo"; var size = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) {
                    cur.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cur.getString(it) ?: name }
                    cur.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cur.isNull(it) }?.let { size = cur.getLong(it) }
                }
            }
        } catch (_: Exception) { }
        return PickedFile(uri, name, size)
    }

    fun importUris(uris: List<Uri>, openWhenDone: (LoadedModel) -> Unit): Long {
        val files = uris.map { describe(it) }
        val title = if (files.size == 1) "Importando ${files[0].name}" else "Importando ${files.size} archivos"
        return c.tasks.submit(title, FormatSniffer.byExtension(files.first().name), files.sumOf { it.size.coerceAtLeast(0) }.takeIf { it > 0 }, retryable = true) { rep ->
            rep.phase("Leyendo archivos")
            MemoryGuard.check(files.sumOf { it.size.coerceAtLeast(0) })
            val bundleFiles = files.map { f ->
                BundleFile(f.name, f.size.coerceAtLeast(0)) {
                    resolver.openInputStream(f.uri)?.use { it.readBytes() } ?: throw ModelImportException(ModelImportException.Code.IO, "No se pudo abrir ${f.name}.")
                }
            }
            val scene = ModelImporter.importBundle(FileBundle(bundleFiles))
            currentCoroutineContext().ensureActive()
            rep.phase("Guardando en la biblioteca")
            val item = c.library.save(scene, "Importado: ${files.joinToString { it.name }}")
            val lm = LoadedModel(scene, scene.name, item.id, "Importado de archivo local")
            c.current.value = lm
            openWhenDone(lm)
            TaskOutcome("library:${item.id}")
        }
    }

    fun openFromLibrary(id: String, name: String, openWhenDone: (LoadedModel) -> Unit): Long =
        c.tasks.submit("Abriendo $name", null, null, retryable = true) { rep ->
            rep.phase("Cargando modelo")
            val scene = c.library.load(id)
            val lm = LoadedModel(scene, scene.name, id, "Biblioteca")
            c.current.value = lm; openWhenDone(lm)
            TaskOutcome("library:$id")
        }

    fun openSample(assetName: String, display: String, assets: android.content.res.AssetManager, openWhenDone: (LoadedModel) -> Unit): Long =
        c.tasks.submit("Abriendo muestra: $display", null, null) { rep ->
            rep.phase("Leyendo muestra incluida")
            val bytes = assets.open("samples/$assetName").use { it.readBytes() }
            val scene = ModelImporter.importBytes(assetName, bytes).retag(name = display)
            val lm = LoadedModel(scene, display, null, "Muestra incluida con la app (CC0, generada por el proyecto)")
            c.current.value = lm; openWhenDone(lm)
            TaskOutcome()
        }

    // ------------------------------------------------------------ recursos web
    private fun headers(url: String, page: String) = c.headersProvider(url, page)

    /** Descarga un recurso detectado, lo importa y lo abre en el visor. */
    fun fetchAndOpen(r: DetectedResource, pageUrl: String, openWhenDone: (LoadedModel) -> Unit): Long =
        c.tasks.submit("Descargando ${r.url.substringBefore('?').substringAfterLast('/').ifEmpty { "modelo" }}", r.format, r.sizeBytes) { rep ->
            val scene = fetchScene(r, pageUrl, rep)
            val lm = LoadedModel(scene, scene.name, null, "Descargado de ${r.url.substringAfter("://").substringBefore('/')}")
            rep.phase("Guardando en la biblioteca")
            val item = c.library.save(scene, lm.origin)
            val withId = LoadedModel(scene, scene.name, item.id, lm.origin)
            c.current.value = withId; openWhenDone(withId)
            TaskOutcome("library:${item.id}")
        }

    private suspend fun fetchScene(r: DetectedResource, pageUrl: String, rep: app.universal3d.core.tasks.TaskReporter): Scene3D {
        // Reutiliza el motor de replicación en su modo de archivo original para obtener dependencias con la misma lógica
        val report = DetectionReport(pageUrl, app.universal3d.core.detect.DetectionState.FILE_DETECTED, listOf(r), emptyList(), false, false, emptyList(), null)
        var result: ReplicationResult? = null
        ReplicationEngine().run(ReplicationInput(report, c.fetcher, c.adapters, headersFor = { headers(it, pageUrl) }, maxBytes = 256L * 1024 * 1024, checkMemory = { MemoryGuard.check(it) })).collect { ev ->
            when (ev) {
                is ReplicationEvent.Progress -> rep.progress(ev.done, ev.total)
                is ReplicationEvent.Phase -> rep.phase(ev.label)
                is ReplicationEvent.Finished -> result = ev.result
                else -> {}
            }
        }
        val res = result ?: throw IllegalStateException("La operación terminó sin resultado.")
        if (!res.success) throw IllegalStateException(res.failureReason ?: "No se pudo importar el recurso.")
        return res.scene!!
    }

    // ------------------------------------------------------------ exportación
    /** Convierte y verifica en caché privada; el usuario elige después dónde guardar (selector del sistema). */
    fun export(scene: Scene3D, name: String, format: ExportFormat, onReady: (PreparedExport) -> Unit = {}): Long {
        return c.tasks.submit("Exportando ${format.label}", when (format) { ExportFormat.GLB -> ModelFormat.GLB; ExportFormat.OBJ -> ModelFormat.OBJ; ExportFormat.FBX -> ModelFormat.FBX; else -> ModelFormat.ZIP }) { rep ->
            rep.phase("Convirtiendo")
            val art = ModelExporter.export(scene, format, name)
            currentCoroutineContext().ensureActive()
            rep.phase("Verificando el archivo generado")
            val f = c.exports.newFile(art.fileName)
            f.writeBytes(art.bytes)
            if (!f.exists() || f.length() != art.bytes.size.toLong()) throw IllegalStateException("No se pudo escribir el archivo exportado.")
            val p = PreparedExport(f, art.fileName, format.mime, format.label, art.verification.ok, art.verification.details, art.notices)
            if (!art.verification.ok) { f.delete(); throw IllegalStateException("La verificación del archivo generado falló: ${art.verification.details.joinToString(" · ")}") }
            rep.progress(f.length(), f.length())
            c.exports.register(p)
            onReady(p)
            TaskOutcome(f.path)
        }
    }

    /** Descarga + importa + exporta en una sola tarea (botones "Descargar GLB/OBJ/FBX/ZIP" del panel). */
    fun downloadAs(r: DetectedResource, pageUrl: String, format: ExportFormat, onReady: (PreparedExport) -> Unit): Long =
        c.tasks.submit("${format.label}: ${r.url.substringBefore('?').substringAfterLast('/').ifEmpty { "modelo" }}", r.format, r.sizeBytes) { rep ->
            val scene = fetchScene(r, pageUrl, rep)
            rep.phase("Convirtiendo a ${format.label}")
            val art = ModelExporter.export(scene, format, scene.name)
            currentCoroutineContext().ensureActive()
            if (!art.verification.ok) throw IllegalStateException("La verificación del archivo generado falló: ${art.verification.details.joinToString(" · ")}")
            val f = c.exports.newFile(art.fileName); f.writeBytes(art.bytes)
            if (!f.exists() || f.length() == 0L) throw IllegalStateException("No se pudo escribir el archivo exportado.")
            val p = PreparedExport(f, art.fileName, format.mime, format.label, true, art.verification.details, art.notices)
            c.exports.register(p)
            onReady(p)
            TaskOutcome(f.path)
        }

    /** Descarga normal (archivo no 3D) hacia caché; el usuario elige después el destino. */
    fun downloadFile(url: String, fileName: String, mimeType: String?, headers: Map<String, String>, onReady: (PreparedExport) -> Unit): Long =
        c.tasks.submit("Descargando $fileName", null, null) { rep ->
            val target = c.exports.newFile(SafeNames.fileName(fileName, fileName.substringAfterLast('.', "bin")))
            val n = c.fetcher.downloadToFile(url, target, 1024L * 1024 * 1024, headers) { d, t -> rep.progress(d, t) }
            val p = PreparedExport(target, target.name, mimeType ?: "application/octet-stream", "Archivo", true, listOf("$n bytes descargados"), emptyList())
            c.exports.register(p)
            onReady(p)
            TaskOutcome(target.path)
        }

    // ------------------------------------------------------------ copiar / replicar
    fun replicate(report: DetectionReport, capture: CaptureProvider?, openWhenDone: (LoadedModel) -> Unit): Long {
        _replication.value = ReplicationUi()
        val id = c.tasks.submit("Copiar / Replicar modelo", null, null, retryable = false) { rep ->
            val input = ReplicationInput(
                report, c.fetcher, c.adapters, tokenFor = { c.tokens.get(it) }, headersFor = { headers(it, report.pageUrl) }, capture = capture,
                confirmLargeDownload = { true }, checkMemory = { MemoryGuard.check(it) },
            )
            var finished: ReplicationResult? = null
            ReplicationEngine().run(input).collect { ev ->
                when (ev) {
                    is ReplicationEvent.Phase -> { rep.phase(ev.label); _replication.value = _replication.value?.copy(phaseIndex = ev.index, phaseTotal = ev.total, phaseLabel = ev.label, progressDone = 0, progressTotal = null, progressLabel = "") }
                    is ReplicationEvent.Progress -> { rep.progress(ev.done, ev.total); _replication.value = _replication.value?.copy(progressDone = ev.done, progressTotal = ev.total, progressLabel = ev.label) }
                    is ReplicationEvent.Found -> _replication.value = _replication.value?.let { it.copy(found = it.found + "${ev.count} ${ev.what}") }
                    is ReplicationEvent.Recoverable -> _replication.value = _replication.value?.let { it.copy(errors = it.errors + ev.message) }
                    is ReplicationEvent.Finished -> finished = ev.result
                }
            }
            val res = finished ?: throw IllegalStateException("La replicación terminó sin resultado.")
            _replication.value = _replication.value?.copy(running = false, result = res)
            if (res.success) {
                val scene = res.scene!!
                val item = c.library.save(scene, res.sourceDescription ?: "Replicado")
                val lm = LoadedModel(scene, scene.name, item.id, res.sourceDescription ?: "", res.fidelity, res)
                c.current.value = lm; openWhenDone(lm)
            } else throw IllegalStateException(res.failureReason ?: "No se pudo replicar el modelo.")
            TaskOutcome()
        }
        _replication.value = _replication.value?.copy(taskId = id)
        return id
    }

    // ------------------------------------------------------------ reconstrucción visual aproximada
    class ReconSource(val uri: Uri, val name: String)

    fun reconstructVisual(sources: List<ReconSource>, totalAngleDeg: Float, resolution: Int, maxImageSide: Int, openWhenDone: (LoadedModel) -> Unit): Long =
        c.tasks.submit("Reconstrucción visual aproximada", null, null, retryable = true) { rep ->
            rep.phase("Preparando siluetas")
            val views = ArrayList<SilhouetteView>()
            for ((i, s) in sources.withIndex()) {
                currentCoroutineContext().ensureActive()
                views.add(ImageDecode.silhouette(resolver, s.uri, maxImageSide, (i * (totalAngleDeg / sources.size) * Math.PI / 180.0).toFloat()))
                rep.progress((i + 1).toLong(), sources.size.toLong())
            }
            rep.phase("Tallando voxels")
            val res = VisualHullReconstructor.reconstruct(views, HullConfig(resolution = resolution, minViews = 6)) { d, t, phase -> rep.phase(phase); rep.progress(d.toLong(), t.toLong()) }
            val scene = res.scene
            val item = c.library.save(scene, "Reconstrucción visual aproximada (${views.size} vistas)")
            val lm = LoadedModel(scene, scene.name, item.id, "Reconstrucción visual aproximada: NO es el modelo original")
            c.current.value = lm; openWhenDone(lm)
            TaskOutcome("library:${item.id}")
        }
}
