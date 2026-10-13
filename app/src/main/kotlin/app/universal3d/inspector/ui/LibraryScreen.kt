package app.universal3d.inspector.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.universal3d.core.model.ModelFormat
import app.universal3d.inspector.ActivityActions
import app.universal3d.inspector.AppContainer
import app.universal3d.inspector.data.LibraryItem
import app.universal3d.inspector.work.Pipelines
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(container: AppContainer, pipelines: Pipelines, actions: ActivityActions, openViewer: () -> Unit, openReconstruct: () -> Unit) {
    val items by container.library.items.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var confirmDelete by remember { mutableStateOf<LibraryItem?>(null) }
    var renaming by remember { mutableStateOf<LibraryItem?>(null) }
    var bigWarn by remember { mutableStateOf<List<android.net.Uri>?>(null) }

    fun doImport(uris: List<android.net.Uri>) {
        val big = uris.map { pipelines.describe(it).size }.sum() > container.settings.state.value.confirmLargeBytes + 14L * 1024 * 1024
        if (big) bigWarn = uris else pipelines.importUris(uris) { openViewer() }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text("Biblioteca", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScrollCompat()) {
            Button(onClick = { actions.pickModels { uris -> if (uris.isNotEmpty()) doImport(uris) } }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Importar modelo") }
            OutlinedButton(onClick = { pipelines.openSample("cubo_texturado.glb", "Cubo texturizado (muestra)", ctx.assets) { openViewer() } }) { Text("Muestra: cubo") }
            OutlinedButton(onClick = { pipelines.openSample("brazo_animado.glb", "Brazo con esqueleto (muestra)", ctx.assets) { openViewer() } }) { Text("Muestra: esqueleto + animación") }
            OutlinedButton(onClick = { pipelines.openSample("cubo_obj.zip", "Cubo OBJ + MTL (muestra)", ctx.assets) { openViewer() } }) { Text("Muestra: OBJ") }
        }
        Text("Podés elegir varios archivos a la vez (.gltf + .bin + texturas, .obj + .mtl + imágenes) o un .zip. Formatos: GLB, glTF, OBJ, FBX.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("La biblioteca está vacía.\nImportá un modelo o abrí una muestra.", style = MaterialTheme.typography.bodyMedium) }
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items, key = { it.id }) { it0 ->
                Card(Modifier.fillMaxWidth().clickable { pipelines.openFromLibrary(it0.id, it0.name) { openViewer() } }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(it0.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text("${if (it0.sourceFormat == ModelFormat.UNKNOWN) "—" else it0.sourceFormat.displayName} · ${it0.triangles} triáng. · ${humanSize(it0.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
                            Text("${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it0.createdAt))} · ${it0.origin.take(60)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { renaming = it0 }) { Icon(Icons.Default.Edit, "Renombrar") }
                        IconButton(onClick = { confirmDelete = it0 }) { Icon(Icons.Default.Delete, "Eliminar") }
                    }
                }
            }
        }
    }
    confirmDelete?.let { d ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("Eliminar modelo") }, text = { Text("¿Eliminar \"${d.name}\" de la biblioteca? Esto no se puede deshacer.") },
            confirmButton = { TextButton(onClick = { scope.launch { container.library.delete(d.id) }; confirmDelete = null }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancelar") } })
    }
    renaming?.let { d ->
        var text by remember(d.id) { mutableStateOf(d.name) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Renombrar") }, text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { scope.launch { container.library.rename(d.id, text) }; renaming = null }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancelar") } })
    }
    bigWarn?.let { uris ->
        AlertDialog(onDismissRequest = { bigWarn = null }, title = { Text("Archivo grande") },
            text = { Text("Los archivos seleccionados son grandes. Importarlos puede consumir mucha memoria y tardar; la app podría cerrarse en un teléfono con poca RAM. ¿Continuar?") },
            confirmButton = { TextButton(onClick = { bigWarn = null; pipelines.importUris(uris) { openViewer() } }) { Text("Importar igualmente") } },
            dismissButton = { TextButton(onClick = { bigWarn = null }) { Text("Cancelar") } })
    }
}
