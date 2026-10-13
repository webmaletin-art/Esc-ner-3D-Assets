package app.universal3d.inspector.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.universal3d.core.inspect.ExportFormat
import app.universal3d.core.inspect.ExportPlanner
import app.universal3d.core.model.Notice
import app.universal3d.inspector.ActivityActions
import app.universal3d.inspector.AppContainer
import app.universal3d.inspector.work.Pipelines
import app.universal3d.inspector.work.ReplicationUi

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplicationSheet(
    ui: ReplicationUi, container: AppContainer, pipelines: Pipelines, actions: ActivityActions,
    onClose: () -> Unit, openViewer: () -> Unit, openReconstruct: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Copiar / Replicar modelo", style = MaterialTheme.typography.titleLarge)
            if (ui.running) {
                Text("Fase ${ui.phaseIndex} de ${ui.phaseTotal}: ${ui.phaseLabel}", style = MaterialTheme.typography.titleSmall)
                val total = ui.progressTotal
                if (total != null && total > 0) {
                    LinearProgressIndicator(progress = { (ui.progressDone.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("${ui.progressLabel} · ${humanSize(ui.progressDone)} de ${humanSize(total)}", style = MaterialTheme.typography.bodySmall)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (ui.progressLabel.isNotEmpty()) Text(ui.progressLabel, style = MaterialTheme.typography.bodySmall)
                    Text("Progreso indeterminado: el tamaño total no se conoce.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (ui.found.isNotEmpty()) { Text("Recursos encontrados", style = MaterialTheme.typography.labelLarge); ui.found.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) } }
            if (ui.errors.isNotEmpty()) { Text("Avisos recuperables", style = MaterialTheme.typography.labelLarge, color = U3DColors.warn); ui.errors.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

            val res = ui.result
            if (ui.running) {
                OutlinedButton(onClick = onClose) { Text("Cancelar") }
            } else if (res != null && res.success && res.scene != null) {
                val scene = res.scene!!
                val f = res.fidelity!!
                HorizontalDivider()
                Text("Resultado: ${f.level.label}", style = MaterialTheme.typography.titleMedium, color = U3DColors.ok)
                res.sourceDescription?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (f.preserved.isNotEmpty()) { Text("Conservado", style = MaterialTheme.typography.labelLarge); f.preserved.forEach { Text("✓ $it", style = MaterialTheme.typography.bodySmall) } }
                if (f.approximated.isNotEmpty()) { Text("Aproximado", style = MaterialTheme.typography.labelLarge, color = U3DColors.warn); f.approximated.forEach { Text("≈ $it", style = MaterialTheme.typography.bodySmall) } }
                if (f.missing.isNotEmpty()) { Text("No recuperado", style = MaterialTheme.typography.labelLarge, color = U3DColors.loss); f.missing.forEach { Text("✗ $it", style = MaterialTheme.typography.bodySmall) } }
                Button(onClick = { onClose(); openViewer() }) { Text("Abrir en el visor") }
                Text("Exportar (opciones disponibles para este resultado):", style = MaterialTheme.typography.labelLarge)
                ExportButtons(ExportPlanner.plan(scene), onPick = { fmt ->
                    pipelines.export(scene, scene.name, fmt) { p -> Handler(Looper.getMainLooper()).post { actions.save(p) } }
                })
            } else if (res != null) {
                HorizontalDivider()
                Text("No se pudo replicar el modelo", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text(res.failureReason ?: "", style = MaterialTheme.typography.bodyMedium)
                res.suggestions.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (res.offerVisualReconstruction) FilledTonalButton(onClick = { onClose(); openReconstruct() }) { Text("Reconstrucción visual aproximada") }
                    OutlinedButton(onClick = onClose) { Text("Cerrar") }
                }
                Text("La reconstrucción visual NO recupera el archivo original: genera una forma aproximada a partir de fotos con fondo uniforme.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Botones de exportación: sólo los que el planificador marca como habilitados, con las pérdidas previstas visibles. */
@Composable
fun ExportButtons(options: List<app.universal3d.core.inspect.ExportOption>, onPick: (ExportFormat) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (o in options) {
            if (!o.enabled) {
                Text("${o.format.label}: no disponible — ${o.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                continue
            }
            Column {
                OutlinedButton(onClick = { onPick(o.format) }, modifier = Modifier.fillMaxWidth()) { Text("Descargar ${o.format.label}") }
                val losses = o.expectedLosses.filter { it.severity != Notice.Severity.INFO }
                if (losses.isEmpty()) Text("Sin pérdidas previstas.", style = MaterialTheme.typography.labelSmall, color = U3DColors.ok)
                else losses.forEach { Text("${if (it.severity == Notice.Severity.LOSS) "Se pierde" else "Aviso"}: ${it.message}", style = MaterialTheme.typography.labelSmall, color = if (it.severity == Notice.Severity.LOSS) U3DColors.loss else U3DColors.warn) }
            }
        }
    }
}
