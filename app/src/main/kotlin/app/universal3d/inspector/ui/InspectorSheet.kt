package app.universal3d.inspector.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.universal3d.core.inspect.ModelInspector
import app.universal3d.core.model.Notice
import app.universal3d.core.model.Scene3D
import app.universal3d.inspector.LoadedModel

@Composable
fun InspectorContent(scene: Scene3D, model: LoadedModel) {
    val r = remember(scene) { ModelInspector.inspect(scene) }
    var preview by remember { mutableStateOf<Int?>(null) }
    LazyColumn(Modifier.heightIn(max = 620.dp).padding(horizontal = 16.dp)) {
        item {
            Text("Inspector", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Section("General")
            Row2("Nombre", r.name); Row2("Formato original", if (r.sourceFormat == app.universal3d.core.model.ModelFormat.UNKNOWN) "No disponible" else r.sourceFormat.displayName)
            Row2("Tamaño del archivo", r.sizeBytes.text({ humanSize(it) })); Row2("Origen", model.origin.ifEmpty { "No disponible" })
            r.boundsSize.value?.let { Row2("Dimensiones (unidades del modelo)", "%.3f × %.3f × %.3f".format(it[0], it[1], it[2])) } ?: Row2("Dimensiones", "No disponible")
            Section("Geometría")
            Row2("Nodos", "${r.nodeCount}"); Row2("Mallas", "${r.meshCount}"); Row2("Primitivas", "${r.primitiveCount}")
            Row2("Vértices", "${r.vertexCount}"); Row2("Triángulos", "${r.triangleCount}")
            Row2("Coordenadas UV", if (r.hasUv) "Sí" else "No"); Row2("Normales", if (r.hasNormals) "Sí" else "No (se calculan al mostrar)"); Row2("Colores de vértice", if (r.hasVertexColors) "Sí" else "No")
            Section("Esqueleto y animación")
            Row2("Esqueleto", if (r.hasSkeleton) "Sí" else "No"); Row2("Huesos", if (r.hasSkeleton) "${r.boneCount}" else "0")
            Row2("Animaciones", if (r.animations.isEmpty()) "Ninguna" else "${r.animations.size}")
            r.animations.forEach { Row2("  ${it.name}", "%.2f s · %d canales".format(it.durationSeconds, it.channels)) }
            Section("Materiales (${r.materialCount})")
        }
        items(r.materials.size) { i ->
            val m = r.materials[i]
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(Color(m.baseColor[0].coerceIn(0f, 1f), m.baseColor[1].coerceIn(0f, 1f), m.baseColor[2].coerceIn(0f, 1f))))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(m.name, style = MaterialTheme.typography.bodyMedium)
                    Text("metal %.2f · rugosidad %.2f · %s%s${if (m.textureSlots.isNotEmpty()) " · texturas: " + m.textureSlots.joinToString() else ""}".format(m.metallic, m.roughness, m.alphaMode.name.lowercase(), if (m.doubleSided) " · doble cara" else ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Section("Texturas (${r.textureCount})") }
        items(r.textures.size) { i ->
            val t = r.textures[i]
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextureThumb(scene, i, Modifier.size(52.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text("${t.resolution} · ${t.mimeType.removePrefix("image/")} · ${humanSize(t.sizeBytes)}${if (t.usedAs.isNotEmpty()) " · " + t.usedAs.joinToString() else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { preview = if (preview == i) null else i }) { Text(if (preview == i) "Cerrar" else "Ver") }
            }
            if (preview == i) TextureThumb(scene, i, Modifier.fillMaxWidth().heightIn(max = 280.dp), big = true)
        }
        item {
            if (r.missingDependencies.isNotEmpty()) {
                Section("Dependencias ausentes")
                r.missingDependencies.forEach { Text("✗ $it", color = U3DColors.loss, style = MaterialTheme.typography.bodySmall) }
            }
            if (r.notices.isNotEmpty()) {
                Section("Avisos de importación")
                r.notices.forEach { NoticeLine(it) }
            }
            model.fidelity?.let { f ->
                Section("Fidelidad de la copia: ${f.level.label}")
                f.preserved.forEach { Text("✓ $it", style = MaterialTheme.typography.bodySmall) }
                f.approximated.forEach { Text("≈ $it", style = MaterialTheme.typography.bodySmall, color = U3DColors.warn) }
                f.missing.forEach { Text("✗ $it", style = MaterialTheme.typography.bodySmall, color = U3DColors.loss) }
            }
            Section("Compatibilidad de exportación")
            r.exportOptions.forEach { o ->
                Text(o.format.label + if (o.enabled) "" else " — no disponible: ${o.reason}", style = MaterialTheme.typography.bodyMedium)
                o.expectedLosses.forEach { NoticeLine(it) }
                if (o.enabled && o.expectedLosses.none { it.severity != Notice.Severity.INFO }) Text("Sin pérdidas previstas", style = MaterialTheme.typography.labelSmall, color = U3DColors.ok)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable private fun Section(t: String) { Spacer(Modifier.height(10.dp)); Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary); HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
@Composable private fun Row2(k: String, v: String) { Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) { Text(k, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(v, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) } }
@Composable fun NoticeLine(n: Notice) {
    val c = when (n.severity) { Notice.Severity.LOSS -> U3DColors.loss; Notice.Severity.WARNING -> U3DColors.warn; else -> MaterialTheme.colorScheme.onSurfaceVariant }
    Text("${when (n.severity) { Notice.Severity.LOSS -> "✗ "; Notice.Severity.WARNING -> "⚠ "; else -> "ℹ " }}${n.message}", style = MaterialTheme.typography.labelSmall, color = c)
}

@Composable
private fun TextureThumb(scene: Scene3D, imageIndex: Int, modifier: Modifier, big: Boolean = false) {
    val bmp by produceState<Bitmap?>(null, scene, imageIndex) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            try {
                val b = scene.images[imageIndex].bytes
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(b, 0, b.size, o)
                val target = if (big) 1024 else 128
                var s = 1; while (o.outWidth / s > target * 2 || o.outHeight / s > target * 2) s *= 2
                BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
            } catch (_: Throwable) { null }
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) } ?: Text("sin vista previa", Modifier.padding(4.dp), style = MaterialTheme.typography.labelSmall)
    }
}
