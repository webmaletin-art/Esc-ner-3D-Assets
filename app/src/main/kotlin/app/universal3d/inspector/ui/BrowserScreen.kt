package app.universal3d.inspector.ui

import android.webkit.URLUtil
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.universal3d.core.detect.DetectionReport
import app.universal3d.core.detect.DetectionState
import app.universal3d.core.inspect.ExportFormat
import app.universal3d.core.resources.AccessState
import app.universal3d.core.resources.DetectedResource
import app.universal3d.core.resources.ResourceKind
import app.universal3d.core.model.ModelFormat
import app.universal3d.inspector.ActivityActions
import app.universal3d.inspector.AppContainer
import app.universal3d.inspector.web.BrowserController
import app.universal3d.inspector.web.DownloadRequest
import app.universal3d.inspector.work.Pipelines

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    container: AppContainer, browser: BrowserController, pipelines: Pipelines, actions: ActivityActions,
    openViewer: () -> Unit, openHistory: () -> Unit, openReconstruct: () -> Unit,
) {
    val state by browser.state.collectAsState()
    val report by browser.detection.collectAsState()
    val replication by pipelines.replication.collectAsState()
    var address by remember { mutableStateOf(state.url) }
    var editing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var download by remember { mutableStateOf<DownloadRequest?>(null) }
    var panelExpanded by remember { mutableStateOf(false) }
    var panelHidden by remember(report.pageUrl) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val settings by container.settings.state.collectAsState()

    LaunchedEffect(state.url) { if (!editing) address = state.url }
    DisposableEffect(Unit) {
        browser.onDownload = { download = it }
        onDispose { browser.onDownload = null; browser.releaseView() }
    }
    BackHandler(enabled = state.canGoBack) { browser.back() }

    Column(Modifier.fillMaxSize()) {
        // ---- barra de direcciones ----
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { browser.back() }, enabled = state.canGoBack) { Icon(Icons.Default.ArrowBack, "Atrás") }
            IconButton(onClick = { browser.forward() }, enabled = state.canGoForward) { Icon(Icons.Default.ArrowForward, "Adelante") }
            OutlinedTextField(
                value = address, onValueChange = { address = it }, singleLine = true, modifier = Modifier.weight(1f).heightIn(min = 48.dp).onFocusChanged { editing = it.isFocused },
                textStyle = MaterialTheme.typography.bodyMedium, placeholder = { Text("Buscar o escribir URL") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { focus.clearFocus(); browser.load(address) }),
                leadingIcon = { if (state.url.startsWith("https://")) Icon(Icons.Default.Lock, "Conexión segura", Modifier.size(18.dp)) },
            )
            IconButton(onClick = { if (state.loading) browser.stop() else browser.reload() }) { Icon(if (state.loading) Icons.Default.Close else Icons.Default.Refresh, if (state.loading) "Detener" else "Recargar") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menú") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Historial") }, onClick = { menu = false; openHistory() })
                    DropdownMenuItem(text = { Text("Ir a la página de inicio") }, onClick = { menu = false; browser.load(settings.homeUrl) })
                    DropdownMenuItem(text = { Text("Reconstrucción visual aproximada…") }, onClick = { menu = false; openReconstruct() })
                    if (panelHidden) DropdownMenuItem(text = { Text("Mostrar panel de detección") }, onClick = { menu = false; panelHidden = false })
                }
            }
        }
        if (state.loading) LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth().height(3.dp))
        else Spacer(Modifier.height(3.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { ctx -> browser.obtainWebView(ctx) }, modifier = Modifier.fillMaxSize(), onRelease = { })
            state.error?.let { err ->
                Card(Modifier.align(Alignment.TopCenter).padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(err, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { browser.clearError() }) { Text("Cerrar") }
                    }
                }
            }
        }

        if (!panelHidden) DetectionPanel(
            report = report, expanded = panelExpanded, onToggle = { panelExpanded = !panelExpanded }, onHide = { panelHidden = true },
            webMessagingSupported = state.webMessagingSupported,
            onOpen = { r -> pipelines.fetchAndOpen(r, report.pageUrl) { openViewer() } },
            onDownloadAs = { r, f ->
                pipelines.downloadAs(r, report.pageUrl, f) { p -> android.os.Handler(android.os.Looper.getMainLooper()).post { actions.save(p) } }
            },
            onReplicate = { pipelines.replicate(report, browser.captureProvider) { } },
        )
    }

    // ---- diálogo de descarga normal ----
    download?.let { d ->
        val fmt = app.universal3d.core.io.FormatSniffer.byExtension(d.fileName).let { if (it == ModelFormat.UNKNOWN) app.universal3d.core.io.FormatSniffer.byMime(d.mimeType) else it }
        val is3d = fmt != ModelFormat.UNKNOWN && fmt != ModelFormat.ZIP || ((fmt == ModelFormat.ZIP) && d.fileName.endsWith(".zip"))
        AlertDialog(
            onDismissRequest = { download = null },
            title = { Text("Descargar archivo") },
            text = {
                Column {
                    Text(d.fileName, style = MaterialTheme.typography.titleSmall)
                    Text(if (d.contentLength > 0) "${d.contentLength / 1024} KB" else "Tamaño desconocido", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Sólo descargá contenido sobre el que tengas derechos. El archivo no se ejecuta ni se abre automáticamente.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Row {
                    if (is3d) TextButton(onClick = {
                        val r = app.universal3d.core.resources.ResourceClassifier.classify(d.url, d.mimeType)
                        download = null
                        if (r != null) pipelines.fetchAndOpen(r.copy(format = if (fmt == ModelFormat.UNKNOWN) r.format else fmt), report.pageUrl.ifEmpty { d.url }) { openViewer() }
                        else container.toast("No se reconoció el formato del archivo.")
                    }) { Text("Importar al visor") }
                    TextButton(onClick = {
                        val h = browser.cookieHeaderFor(d.url, state.url)
                        download = null
                        pipelines.downloadFile(d.url, d.fileName, d.mimeType, h) { p -> android.os.Handler(android.os.Looper.getMainLooper()).post { actions.save(p) } }
                    }) { Text("Guardar") }
                }
            },
            dismissButton = { TextButton(onClick = { download = null }) { Text("Cancelar") } },
        )
    }

    // ---- hoja de replicación ----
    replication?.let { ui -> ReplicationSheet(ui, container, pipelines, actions, onClose = { pipelines.dismissReplication() }, openViewer = openViewer, openReconstruct = openReconstruct) }
}

private fun fmtLabel(f: ModelFormat) = if (f == ModelFormat.UNKNOWN) "?" else f.displayName

@Composable
fun DetectionPanel(
    report: DetectionReport, expanded: Boolean, onToggle: () -> Unit, onHide: () -> Unit, webMessagingSupported: Boolean,
    onOpen: (DetectedResource) -> Unit, onDownloadAs: (DetectedResource, ExportFormat) -> Unit, onReplicate: () -> Unit,
) {
    val state = report.state
    val color = when (state) {
        DetectionState.MODEL_CONFIRMED -> U3DColors.ok
        DetectionState.FILE_DETECTED, DetectionState.GEOMETRY_CAPTURABLE -> U3DColors.info
        DetectionState.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
        DetectionState.AUTH_REQUIRED, DetectionState.UNSUPPORTED_FORMAT, DetectionState.INCOMPLETE -> U3DColors.warn
        else -> U3DColors.info
    }
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).animateContentSizeCompat()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = color, shape = MaterialTheme.shapes.small, modifier = Modifier.size(10.dp)) {}
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.title, style = MaterialTheme.typography.titleSmall)
                    if (state == DetectionState.NONE) {
                        if (!webMessagingSupported) Text("Esta versión de WebView no admite la detección avanzada; sólo se observa la red.", style = MaterialTheme.typography.bodySmall)
                    } else report.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (report.probing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                if (state != DetectionState.NONE) TextButton(onClick = onToggle) { Text(if (expanded) "Menos" else "Más") }
                IconButton(onClick = onHide) { Icon(Icons.Default.Close, "Ocultar panel") }
            }
            if (state != DetectionState.NONE) {
                val primary = report.primaryModel
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (primary != null && primary.access != AccessState.REQUIRES_AUTH) Button(onClick = { onOpen(primary) }) { Text("Abrir en visor") }
                    if (report.canReplicate) FilledTonalButton(onClick = onReplicate) { Text("COPIAR / REPLICAR MODELO") }
                }
                if (report.canExportFormats && primary != null) {
                    Text("Descargar como:", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth().horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(ExportFormat.GLB to "GLB", ExportFormat.FBX to "FBX", ExportFormat.OBJ to "OBJ", ExportFormat.FULL_ZIP to "ZIP completo").forEach { (f, label) ->
                            OutlinedButton(onClick = { onDownloadAs(primary, f) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Descargar $label") }
                        }
                    }
                    Text("Todos los formatos se generan y verifican en el dispositivo; antes de guardar se muestra qué datos se conservan.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (expanded) {
                    Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                        if (report.viewerHints.isNotEmpty()) Text("Visores/librerías: ${report.viewerHints.joinToString()}", style = MaterialTheme.typography.bodySmall)
                        if (report.adapterIds.isNotEmpty()) Text("Adaptadores activos: ${report.adapterIds.joinToString()}", style = MaterialTheme.typography.bodySmall)
                        report.resources.forEach { r -> ResourceRow(r) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResourceRow(r: DetectedResource) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.extraSmall) {
                Text(if (r.unsupportedExtension != null) ".${r.unsupportedExtension}" else if (r.kind == ResourceKind.VIEWER_PAGE) "Visor" else fmtLabel(r.format), Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.width(8.dp))
            Text(r.url.substringBefore('?').substringAfterLast('/').ifEmpty { r.url }.take(48), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
        }
        val access = when (r.access) {
            AccessState.UNVERIFIED -> "sin verificar"; AccessState.ACCESSIBLE -> if (r.contentVerified) "accesible, contenido verificado" else "accesible"
            AccessState.REQUIRES_AUTH -> "requiere autorización"; AccessState.NOT_FOUND -> "no encontrado"; AccessState.TOO_LARGE -> "demasiado grande"
            AccessState.BLOCKED_BY_POLICY -> "bloqueado por política"; AccessState.CONTENT_MISMATCH -> "el contenido no es un modelo"; AccessState.ERROR -> "error de acceso"
        }
        Text("${r.origin.name.lowercase().replace('_', '-')} · $access · ${r.sizeBytes?.let { "${it / 1024} KB" } ?: "tamaño desconocido"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        r.incompatibilityReason?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = U3DColors.warn) }
        r.notes.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
