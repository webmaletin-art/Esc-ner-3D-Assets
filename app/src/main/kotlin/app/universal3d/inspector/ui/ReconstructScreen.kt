package app.universal3d.inspector.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.universal3d.core.recon.SilhouetteExtractor
import app.universal3d.inspector.ActivityActions
import app.universal3d.inspector.AppContainer
import app.universal3d.inspector.work.ImageDecode
import app.universal3d.inspector.work.Pipelines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ReconstructScreen(container: AppContainer, pipelines: Pipelines, actions: ActivityActions, onClose: () -> Unit, onOpenViewer: () -> Unit) {
    val picked = remember { mutableStateListOf<Pipelines.ReconSource>() }
    var resolution by remember { mutableStateOf(64) }
    var fullTurn by remember { mutableStateOf(true) }
    var consent by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, "Volver") }
            Text("Reconstrucción visual aproximada", style = MaterialTheme.typography.titleLarge)
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Qué hace (y qué no)", style = MaterialTheme.typography.titleSmall)
                Text("• Genera una forma APROXIMADA (casco visual por siluetas) a partir de fotos o capturas del objeto girando sobre su eje, con ángulos uniformes y fondo liso o transparente.", style = MaterialTheme.typography.bodySmall)
                Text("• NO recupera el modelo original: no reproduce concavidades ni detalles finos y no genera UV; el color se proyecta en los vértices.", style = MaterialTheme.typography.bodySmall)
                Text("• Todo se procesa en este dispositivo. No se sube ninguna imagen.", style = MaterialTheme.typography.bodySmall)
                Text("• Mínimo 6 vistas (recomendado 12–36). Ordenalas según el giro; las imágenes de abajo son las únicas que se usarán.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScrollCompat()) {
            Button(onClick = { actions.pickImages { uris -> uris.forEach { u -> picked.add(Pipelines.ReconSource(u, pipelines.describe(u).name)) } } }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Agregar fotos") }
            OutlinedButton(onClick = { picked.clear() }, enabled = picked.isNotEmpty()) { Text("Quitar todas") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            Text("Giro:", style = MaterialTheme.typography.bodyMedium)
            FilterChip(selected = fullTurn, onClick = { fullTurn = true }, label = { Text("360°") })
            FilterChip(selected = !fullTurn, onClick = { fullTurn = false }, label = { Text("180°") })
            Text("Voxels:", style = MaterialTheme.typography.bodyMedium)
            listOf(32, 64, 96).forEach { r -> FilterChip(selected = resolution == r, onClick = { resolution = r }, label = { Text("$r³") }) }
        }
        Text("${picked.size} imagen(es) seleccionada(s)${if (picked.size in 1..5) " — faltan ${6 - picked.size} para el mínimo" else ""}", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(picked, key = { i, s -> "${s.uri}#$i" }) { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MaskThumb(pipelines, s.uri, Modifier.size(72.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text("Vista ${i + 1} · ${"%.0f".format(i * (if (fullTurn) 360f else 180f) / picked.size.coerceAtLeast(1))}°", style = MaterialTheme.typography.bodyMedium)
                        Text(s.name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                    IconButton(onClick = { if (i > 0) { val t = picked[i]; picked[i] = picked[i - 1]; picked[i - 1] = t } }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, "Subir") }
                    IconButton(onClick = { if (i < picked.lastIndex) { val t = picked[i]; picked[i] = picked[i + 1]; picked[i + 1] = t } }, enabled = i < picked.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "Bajar") }
                    IconButton(onClick = { picked.removeAt(i) }) { Icon(Icons.Default.Close, "Quitar") }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = consent, onCheckedChange = { consent = it })
            Text("Entiendo que el resultado es aproximado y que las fotos son sólo mías o autorizadas.", style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = { pipelines.reconstructVisual(picked.toList(), if (fullTurn) 360f else 180f, resolution, 384) { onOpenViewer() }; container.toast("Reconstruyendo en segundo plano; seguí el progreso en Tareas.") },
            enabled = picked.size >= 6 && consent, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) { Text("Reconstruir (aproximado)") }
    }
}

/** Miniatura con la silueta detectada resaltada, para que el usuario vea qué se va a usar. */
@Composable
private fun MaskThumb(pipelines: Pipelines, uri: Uri, modifier: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.Default) {
            try {
                val b = ImageDecode.decode(ctx.contentResolver, uri, 144)
                val view = SilhouetteExtractor.fromRgba(b.width, b.height, ImageDecode.rgba(b), 0f)
                val out = b.copy(Bitmap.Config.ARGB_8888, true)
                val px = IntArray(b.width * b.height); out.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
                for (i in px.indices) if (view.mask[i]) px[i] = AColor.argb(255, (AColor.red(px[i]) * 0.6f + 255 * 0.4f).toInt(), (AColor.green(px[i]) * 0.6f).toInt(), (AColor.blue(px[i]) * 0.6f).toInt())
                out.setPixels(px, 0, b.width, 0, 0, b.width, b.height)
                b.recycle(); out
            } catch (_: Throwable) { null }
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        bmp?.let { Image(it.asImageBitmap(), "Silueta detectada", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) } ?: Text("…", Modifier.align(Alignment.Center))
    }
}
