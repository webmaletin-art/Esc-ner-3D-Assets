package app.universal3d.inspector.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.universal3d.core.tasks.TaskState
import app.universal3d.inspector.ActivityActions
import app.universal3d.inspector.AppContainer
import app.universal3d.inspector.web.BrowserController
import app.universal3d.inspector.work.Pipelines
import kotlinx.coroutines.launch

enum class Tab(val label: String) { BROWSER("Navegador"), LIBRARY("Biblioteca"), TASKS("Tareas"), SETTINGS("Ajustes") }
enum class Overlay { NONE, VIEWER, HISTORY, RECONSTRUCT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(container: AppContainer, browser: BrowserController, pipelines: Pipelines, actions: ActivityActions, incoming: Uri?) {
    var tab by rememberSaveable { mutableStateOf(Tab.BROWSER) }
    var overlay by rememberSaveable { mutableStateOf(Overlay.NONE) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val tasks by container.tasks.tasks.collectAsState()
    val current by container.current.collectAsState()
    val openViewer: () -> Unit = { overlay = Overlay.VIEWER }

    LaunchedEffect(Unit) { container.library.refresh() }
    LaunchedEffect(incoming) { if (incoming != null) pipelines.importUris(listOf(incoming)) { overlay = Overlay.VIEWER } }
    LaunchedEffect(Unit) { container.messages.collect { snackbar.showSnackbar(it, withDismissAction = true) } }

    // Avisos de tareas: errores comprensibles y exportaciones listas para guardar
    val seen = remember { HashMap<Long, TaskState>() }
    LaunchedEffect(tasks) {
        for (t in tasks) {
            val prev = seen[t.id]; seen[t.id] = t.state
            if (prev == t.state) continue
            when (t.state) {
                TaskState.FAILED -> scope.launch { snackbar.showSnackbar("${t.title}: ${t.error ?: "error"}", withDismissAction = true) }
                TaskState.SUCCEEDED -> container.exports.forFile(t.location)?.let { p ->
                    scope.launch {
                        val r = snackbar.showSnackbar("Listo: ${p.fileName}", actionLabel = "Guardar como…", withDismissAction = true, duration = SnackbarDuration.Long)
                        if (r == SnackbarResult.ActionPerformed) actions.save(p)
                    }
                }
                else -> {}
            }
        }
    }

    BackHandler(enabled = overlay != Overlay.NONE) { overlay = Overlay.NONE }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (overlay == Overlay.NONE) NavigationBar {
                Tab.values().forEach { t ->
                    val running = if (t == Tab.TASKS) tasks.count { !it.isFinished } else 0
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t },
                        icon = {
                            BadgedBox(badge = { if (running > 0) Badge { Text("$running") } }) {
                                Icon(when (t) { Tab.BROWSER -> Icons.Default.Search; Tab.LIBRARY -> Icons.Default.List; Tab.TASKS -> Icons.Default.Refresh; Tab.SETTINGS -> Icons.Default.Settings }, contentDescription = t.label)
                            }
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (overlay) {
                Overlay.VIEWER -> ViewerScreen(container, current, pipelines, actions, onClose = { overlay = Overlay.NONE })
                Overlay.HISTORY -> HistoryScreen(container, onClose = { overlay = Overlay.NONE }, onOpen = { url -> overlay = Overlay.NONE; tab = Tab.BROWSER; browser.load(url) })
                Overlay.RECONSTRUCT -> ReconstructScreen(container, pipelines, actions, onClose = { overlay = Overlay.NONE }, onOpenViewer = openViewer)
                Overlay.NONE -> when (tab) {
                    Tab.BROWSER -> BrowserScreen(container, browser, pipelines, actions, openViewer = openViewer, openHistory = { overlay = Overlay.HISTORY }, openReconstruct = { overlay = Overlay.RECONSTRUCT })
                    Tab.LIBRARY -> LibraryScreen(container, pipelines, actions, openViewer = openViewer, openReconstruct = { overlay = Overlay.RECONSTRUCT })
                    Tab.TASKS -> TasksScreen(container, actions, openViewer = openViewer)
                    Tab.SETTINGS -> SettingsScreen(container)
                }
            }
        }
    }
}
