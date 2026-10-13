package app.universal3d.inspector

import android.app.Application
import android.os.Build
import app.universal3d.core.adapters.AdapterRegistry
import app.universal3d.core.detect.DetectionEngine
import app.universal3d.core.model.Scene3D
import app.universal3d.core.replicate.Fidelity
import app.universal3d.core.replicate.ReplicationResult
import app.universal3d.core.security.SafeFetcher
import app.universal3d.core.tasks.TaskManager
import app.universal3d.inspector.data.*
import app.universal3d.inspector.work.ExportRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Modelo abierto en el visor. */
class LoadedModel(
    val scene: Scene3D, val title: String, val libraryId: String?, val origin: String,
    val fidelity: Fidelity? = null, val replication: ReplicationResult? = null,
)

/** Contenedor manual de dependencias (sin frameworks de inyección). */
class AppContainer(val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = Settings(app)
    val tokens = TokenStore(app)
    val history = HistoryStore(app)
    val library = LibraryRepository(app)
    val tasks = TaskManager(appScope, maxConcurrent = 2)
    val adapters = AdapterRegistry.default()
    val exports = ExportRegistry(app)

    private val userAgent = "Universal3DAssetInspector/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})"
    val fetcher = SafeFetcher(userAgent = userAgent, allowHttpProvider = { settings.state.value.allowHttp })

    /** Cabeceras para recursos del mismo sitio que la página (cookies de la sesión del usuario); nunca se registran. */
    var headersProvider: (resourceUrl: String, pageUrl: String) -> Map<String, String> = { _, _ -> emptyMap() }

    val detection = DetectionEngine(appScope, adapters, fetcher, headersFor = { r, p -> headersProvider(r, p) })

    val current = MutableStateFlow<LoadedModel?>(null)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    fun toast(msg: String) { _messages.tryEmit(msg) }
}
