package app.universal3d.core.detect

import app.universal3d.core.adapters.AdapterRegistry
import app.universal3d.core.io.FormatSniffer
import app.universal3d.core.model.ModelFormat
import app.universal3d.core.resources.*
import app.universal3d.core.security.FetchException
import app.universal3d.core.security.SafeFetcher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Mantiene el estado de detección de la página actual. Recibe eventos (URLs de red observadas, instantáneas del script
 * de la página) con límite de frecuencia, verifica candidatos con peticiones ligeras (Range) y publica un [DetectionReport].
 */
class DetectionEngine(
    private val scope: CoroutineScope,
    private val registry: AdapterRegistry,
    private val fetcher: SafeFetcher,
    /** Cabeceras (p. ej. Cookie del mismo origen) para sondear recursos de la página; nunca se registran. */
    private val headersFor: (resourceUrl: String, pageUrl: String) -> Map<String, String> = { _, _ -> emptyMap() },
    private val maxProbesPerPage: Int = 8,
    private val maxModelBytesForProbeWarning: Long = 200L * 1024 * 1024,
    private val minRecomputeIntervalMs: Long = 250,
) {
    private val _report = MutableStateFlow(DetectionReport.none(""))
    val report: StateFlow<DetectionReport> = _report.asStateFlow()

    private var pageUrl: String = ""
    private var signals: PageSignals? = null
    private val netUrls = LinkedHashSet<String>()
    private val probed = HashMap<String, DetectedResource>()
    private val probing = HashSet<String>()
    private var probeCount = 0
    private val probeSem = Semaphore(2)
    private var generation = 0
    private var recomputeJob: Job? = null
    private var lastRecompute = 0L
    private var signalCount = 0

    @Synchronized fun onPageStarted(url: String) {
        generation++
        recomputeJob?.cancel()
        pageUrl = url; signals = null; netUrls.clear(); probed.clear(); probing.clear(); probeCount = 0; signalCount = 0
        _report.value = DetectionReport.none(url)
    }

    /** URL observada en la red (shouldInterceptRequest). Sólo se guardan las que parecen 3D. */
    @Synchronized fun onNetworkUrl(url: String) {
        if (netUrls.size >= 100 || !ResourceClassifier.isModelCandidate(url)) return
        if (netUrls.add(url)) scheduleRecompute()
    }

    fun onSignals(json: String) { PageSignals.parse(json)?.let { onSignals(it) } }

    @Synchronized fun onSignals(s: PageSignals) {
        if (signalCount >= 60) return // límite por página
        signalCount++
        signals = s
        scheduleRecompute()
    }

    @Synchronized private fun scheduleRecompute() {
        val gen = generation
        if (recomputeJob?.isActive == true) return
        val wait = (minRecomputeIntervalMs - (System.currentTimeMillis() - lastRecompute)).coerceAtLeast(0)
        recomputeJob = scope.launch {
            delay(wait)
            recompute(gen)
        }
    }

    @Synchronized private fun recompute(gen: Int) {
        if (gen != generation) return
        lastRecompute = System.currentTimeMillis()
        val rep = DetectionClassifier.analyze(pageUrl, signals, netUrls.toList(), registry.all, probed.toMap(), probing = probing.isNotEmpty())
        _report.value = rep
        // lanzar sondeos de candidatos nuevos
        for (r in rep.resources) {
            if (!r.importable || r.access != AccessState.UNVERIFIED || r.id in probing || probed.containsKey(r.id)) continue
            if (probeCount >= maxProbesPerPage) break
            probeCount++
            probing.add(r.id)
            val genAtLaunch = gen
            scope.launch { probeSem.withPermit { probe(r, genAtLaunch) } }
        }
        if (probing.isNotEmpty() && !rep.probing) _report.value = rep.copy(probing = true)
    }

    private suspend fun probe(r: DetectedResource, gen: Int) {
        val updated = try {
            val d = fetcher.check(r.url)
            if (!d.allowed) r.copy(access = AccessState.BLOCKED_BY_POLICY, notes = r.notes + (d.reason ?: "bloqueado"))
            else {
                val p = fetcher.probe(r.url, 512, headersFor(r.url, pageUrl))
                val fmt = FormatSniffer.byContent(p.head)
                val ok = fmt == r.format || (r.format == ModelFormat.GLTF && fmt == ModelFormat.GLTF) || (r.format == ModelFormat.ZIP && fmt == ModelFormat.ZIP) ||
                    (r.format == ModelFormat.OBJ && fmt == ModelFormat.OBJ)
                // un OBJ con menos de 3 líneas de pista en 512 B no se reconoce: se acepta si es texto y no HTML
                val softOk = r.format == ModelFormat.OBJ && fmt == ModelFormat.UNKNOWN && looksLikePlainText(p.head)
                val tooBig = (p.totalBytes ?: 0) > 512L * 1024 * 1024
                when {
                    tooBig -> r.copy(access = AccessState.TOO_LARGE, sizeBytes = p.totalBytes)
                    ok || softOk -> r.copy(access = AccessState.ACCESSIBLE, contentVerified = ok, sizeBytes = p.totalBytes ?: r.sizeBytes,
                        notes = if ((p.totalBytes ?: 0) > maxModelBytesForProbeWarning) r.notes + "Archivo muy grande para un teléfono." else r.notes)
                    else -> r.copy(access = AccessState.CONTENT_MISMATCH, sizeBytes = p.totalBytes, notes = r.notes + "El servidor devolvió ${p.contentType ?: "otro contenido"} en lugar de ${r.format.displayName}.")
                }
            }
        } catch (e: CancellationException) { throw e } catch (e: FetchException) {
            when (e.kind) {
                FetchException.Kind.AUTH -> r.copy(access = AccessState.REQUIRES_AUTH)
                FetchException.Kind.NOT_FOUND -> r.copy(access = AccessState.NOT_FOUND)
                FetchException.Kind.POLICY -> r.copy(access = AccessState.BLOCKED_BY_POLICY, notes = r.notes + (e.message ?: ""))
                else -> r.copy(access = AccessState.ERROR, notes = r.notes + (e.message ?: "error de red"))
            }
        } catch (e: Exception) { r.copy(access = AccessState.ERROR, notes = r.notes + (e.message ?: "error")) }
        synchronized(this) {
            if (gen != generation) return
            probing.remove(r.id); probed[r.id] = updated
            recompute(gen)
        }
    }

    private fun looksLikePlainText(b: ByteArray): Boolean = b.isNotEmpty() && b.none { it.toInt() == 0 } && !String(b, Charsets.ISO_8859_1).trimStart().startsWith("<")
}
