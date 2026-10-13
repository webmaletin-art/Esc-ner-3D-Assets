package app.universal3d.inspector.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.universal3d.core.tasks.TaskInfo
import app.universal3d.core.tasks.TaskState
import app.universal3d.inspector.ActivityActions
import app.universal3d.inspector.AppContainer

@Composable
fun TasksScreen(container: AppContainer, actions: ActivityActions, openViewer: () -> Unit) {
    val tasks by container.tasks.tasks.collectAsState()
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text("Descargas y exportaciones", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (tasks.any { it.isFinished }) TextButton(onClick = { container.tasks.clearFinished() }) { Text("Limpiar") }
        }
        if (tasks.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No hay tareas.", style = MaterialTheme.typography.bodyMedium) }
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tasks.reversed(), key = { it.id }) { t -> TaskCard(t, container, actions) }
        }
    }
}

@Composable
private fun TaskCard(t: TaskInfo, container: AppContainer, actions: ActivityActions) {
    val prepared = container.exports.forFile(t.location)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    Text(buildString {
                        append(when (t.state) { TaskState.QUEUED -> "En cola"; TaskState.RUNNING -> "En curso"; TaskState.SUCCEEDED -> "Completada"; TaskState.FAILED -> "Falló"; TaskState.CANCELLED -> "Cancelada" })
                        t.format?.let { if (it != app.universal3d.core.model.ModelFormat.UNKNOWN) append(" · ${it.displayName}") }
                        t.totalBytes?.let { append(" · ${humanSize(it)}") }
                    }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (t.state == TaskState.RUNNING || t.state == TaskState.QUEUED) {
                val p = t.progress
                if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) else if (t.state == TaskState.RUNNING) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                t.phase?.let { Text(it + (p?.let { v -> " · ${(v * 100).toInt()} %" } ?: ""), style = MaterialTheme.typography.bodySmall) }
            }
            t.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (prepared != null) {
                Text("Archivo listo: ${prepared.fileName} (${humanSize(prepared.file.length())}) · verificado", style = MaterialTheme.typography.bodySmall, color = U3DColors.ok)
                prepared.verification.forEach { Text("• $it", style = MaterialTheme.typography.labelSmall) }
                prepared.notices.filter { it.severity != app.universal3d.core.model.Notice.Severity.INFO }.forEach { NoticeLine(it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!t.isFinished) OutlinedButton(onClick = { container.tasks.cancel(t.id) }) { Text("Cancelar") }
                if ((t.state == TaskState.FAILED || t.state == TaskState.CANCELLED) && t.retryable) Button(onClick = { container.tasks.retry(t.id) }) { Text("Reintentar") }
                if (prepared != null) Button(onClick = { actions.save(prepared) }) { Text("Guardar como…") }
                if (t.location?.startsWith("library:") == true && t.state == TaskState.SUCCEEDED) Text("Guardado en la biblioteca", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterVertically))
                if (t.isFinished) TextButton(onClick = { container.tasks.remove(t.id) }) { Text("Quitar") }
            }
        }
    }
}
