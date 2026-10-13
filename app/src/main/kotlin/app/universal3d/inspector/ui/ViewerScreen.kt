package app.universal3d.inspector.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.universal3d.core.inspect.ExportPlanner
import app.universal3d.core.inspect.ModelInspector
import app.universal3d.core.model.Scene3D
import app.universal3d.inspector.ActivityActions
import app.universal3d.inspector.AppContainer
import app.universal3d.inspector.LoadedModel
import app.universal3d.inspector.render.*
import app.universal3d.inspector.work.Pipelines
import kotlin.math.roundToInt

private val BACKGROUNDS = listOf(
    "Gris oscuro" to Color(0xFF171A1F), "Negro" to Color(0xFF000000), "Gris claro" to Color(0xFFD5D9DE), "Blanco" to Color(0xFFFFFFFF), "Azul noche" to Color(0xFF0B1A2E),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(container: AppContainer, model: LoadedModel?, pipelines: Pipelines, actions: ActivityActions, onClose: () -> Unit) {
    if (model == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No hay un modelo abierto.", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp)); Button(onClick = onClose) { Text("Volver") }
        }
        return
    }
    val scene = model.scene
    val settings by container.settings.state.collectAsState()
    var stats by remember { mutableStateOf<RenderStats?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var labels by remember { mutableStateOf<List<NodeLabel>>(emptyList()) }
    var selected by remember { mutableStateOf(-1) }
    var wire by remember { mutableStateOf(WireMode.SOLID) }
    var bgIdx by remember { mutableStateOf(settings.backgroundIndex.coerceIn(0, BACKGROUNDS.lastIndex)) }
    var showNames by remember { mutableStateOf(false) }
    var animIdx by remember { mutableStateOf(if (scene.animations.isNotEmpty()) 0 else -1) }
    var playing by remember { mutableStateOf(scene.animations.isNotEmpty()) }
    var sheet by remember { mutableStateOf<String?>(null) }
    val hidden = remember { mutableStateMapOf<Int, Boolean>() }
    var bgMenu by remember { mutableStateOf(false) }
    val glView = remember {
        mutableStateOf<ModelGLView?>(null)
    }
    val context = LocalContext.current

    val callbacks = remember {
        object : RendererCallbacks {
            override fun onStats(s: RenderStats) { stats = s }
            override fun onError(message: String) { error = message }
            override fun onPicked(nodeIndex: Int) { selected = nodeIndex; glView.value?.renderer?.setSelected(nodeIndex) }
            override fun onLabels(l: List<NodeLabel>) { labels = l }
        }
    }

    Box(Modifier.fillMaxSize().background(BACKGROUNDS[bgIdx].second)) {
        AndroidView(
            factory = { ctx -> ModelGLView(ctx, callbacks).also { v -> glView.value = v; v.renderer.setScene(scene, if (settings.lowMemoryMode) 1024 else settings.maxTextureSize); val c = BACKGROUNDS[bgIdx].second; v.renderer.setBackground(c.red, c.green, c.blue) } },
            modifier = Modifier.fillMaxSize(),
        )
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle, glView.value) {
            val obs = LifecycleEventObserver { _, e -> when (e) { Lifecycle.Event.ON_PAUSE -> glView.value?.onPause(); Lifecycle.Event.ON_RESUME -> glView.value?.onResume(); else -> {} } }
            lifecycle.addObserver(obs)
            onDispose { lifecycle.removeObserver(obs); glView.value?.let { it.releaseModel(); it.onPause() } }
        }
        val r = glView.value?.renderer
        LaunchedEffect(wire) { r?.setWireMode(wire) }
        LaunchedEffect(bgIdx) { val c = BACKGROUNDS[bgIdx].second; r?.setBackground(c.red, c.green, c.blue); container.settings.update { it.copy(backgroundIndex = bgIdx) } }
        LaunchedEffect(showNames) { r?.setLabels(showNames) }
        LaunchedEffect(animIdx, playing) { r?.setAnimation(animIdx, playing) }

        // ---- etiquetas de nodos ----
        if (showNames) labels.forEach { l ->
            Text(l.text, Modifier.offset { IntOffset(l.x.roundToInt() - 40, l.y.roundToInt() - 16) }.background(Color(0xCC000000), MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 1.dp),
                color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }

        // ---- barra superior ----
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, "Volver", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(model.title, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                val s = stats
                Text(
                    if (s != null) "${s.meshes} mallas · ${s.triangles} triáng.${if (s.visibleTriangles != s.triangles) " (${s.visibleTriangles} visibles)" else ""}${s.fps?.let { " · ${it.roundToInt()} FPS medidos" } ?: ""}"
                    else "Cargando…", color = Color(0xFFCFD8DC), style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(onClick = { sheet = "inspector" }) { Text("Inspector", color = Color.White) }
            TextButton(onClick = { sheet = "export" }) { Text("Exportar", color = Color.White) }
        }

        // ---- mensajes ----
        Column(Modifier.align(Alignment.TopCenter).padding(top = 64.dp, start = 12.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            error?.let { WarnCard(it, true) { error = null } }
            stats?.warnings?.take(2)?.forEach { WarnCard(it, false) {} }
            if ((stats?.triangles ?: 0) > 800_000) WarnCard("Modelo muy grande (${stats?.triangles} triángulos): puede ir lento en este dispositivo.", false) {}
            model.replication?.fidelity?.let { f -> if (f.level.ordinal >= 2) WarnCard("${f.level.label}. No es el archivo original; revisá el Inspector para ver qué falta.", false) {} }
        }

        // ---- información de la selección ----
        if (selected >= 0 && selected < scene.nodes.size) {
            val n = scene.nodes[selected]
            val mesh = n.meshIndex.takeIf { it >= 0 }?.let { scene.meshes[it] }
            Card(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 140.dp).widthIn(max = 280.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Text(n.name ?: mesh?.name ?: "Nodo $selected", style = MaterialTheme.typography.titleSmall)
                    mesh?.let { m ->
                        Text("${m.primitives.size} primitiva(s) · ${m.primitives.sumOf { it.triangleCount }} triáng.", style = MaterialTheme.typography.bodySmall)
                        m.primitives.mapNotNull { scene.materials.getOrNull(it.materialIndex)?.name }.distinct().takeIf { it.isNotEmpty() }?.let { Text("Material: ${it.joinToString()}", style = MaterialTheme.typography.bodySmall) }
                    }
                    Row { TextButton(onClick = { hidden[selected] = true; r?.setNodeVisible(selected, false); selected = -1; r?.setSelected(-1) }) { Text("Ocultar") }; TextButton(onClick = { selected = -1; r?.setSelected(-1) }) { Text("Deseleccionar") } }
                }
            }
        }

        // ---- controles inferiores ----
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xB3000000)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (scene.animations.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledIconToggleButton(checked = playing, onCheckedChange = { playing = it }) { Text(if (playing) "⏸" else "▶") }
                    scene.animations.forEachIndexed { i, a ->
                        FilterChip(selected = animIdx == i, onClick = { animIdx = i; playing = true }, label = { Text(a.name ?: "Animación ${i + 1}", maxLines = 1) })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = { wire = WireMode.values()[(wire.ordinal + 1) % 3] }, label = { Text(when (wire) { WireMode.SOLID -> "Sólido"; WireMode.WIRE -> "Alámbrico"; WireMode.BOTH -> "Sólido + malla" }) })
                AssistChip(onClick = { glView.value?.renderer?.resetCamera() }, label = { Text("Reencuadrar") })
                Box {
                    AssistChip(onClick = { bgMenu = true }, label = { Text("Fondo") }, leadingIcon = { Box(Modifier.size(14.dp).clip(CircleShape).background(BACKGROUNDS[bgIdx].second)) })
                    DropdownMenu(expanded = bgMenu, onDismissRequest = { bgMenu = false }) {
                        BACKGROUNDS.forEachIndexed { i, (n, c) -> DropdownMenuItem(text = { Text(n) }, leadingIcon = { Box(Modifier.size(16.dp).clip(CircleShape).background(c)) }, onClick = { bgIdx = i; bgMenu = false }) }
                    }
                }
                AssistChip(onClick = { sheet = "parts" }, label = { Text("Partes (${scene.nodes.count { it.meshIndex >= 0 }})") })
                FilterChip(selected = showNames, onClick = { showNames = !showNames }, label = { Text("Nombres") })
            }
            Text("Un dedo: rotar · pellizco: zoom · dos dedos: mover · doble toque: reencuadrar · toque: seleccionar parte", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB0BEC5))
        }
    }

    // ---- hojas ----
    if (sheet != null) ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        when (sheet) {
            "inspector" -> InspectorContent(scene, model)
            "parts" -> PartsContent(scene, hidden, selected, onToggle = { i, v -> hidden[i] = !v; glView.value?.renderer?.setNodeVisible(i, v) },
                onSelect = { i -> selected = i; glView.value?.renderer?.setSelected(i) },
                onAll = { v -> scene.nodes.indices.forEach { hidden[it] = !v }; glView.value?.renderer?.setAllVisible(v) })
            "export" -> Column(Modifier.padding(16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Exportar", style = MaterialTheme.typography.titleLarge)
                Text("Cada archivo se genera, se reabre para verificarlo y recién entonces se ofrece para guardar.", style = MaterialTheme.typography.bodySmall)
                ExportButtons(ExportPlanner.plan(scene)) { fmt ->
                    sheet = null
                    pipelines.export(scene, model.title, fmt) { p -> Handler(Looper.getMainLooper()).post { actions.save(p) } }
                }
            }
        }
    }
}

@Composable
private fun WarnCard(text: String, dismissible: Boolean, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = if (dismissible) MaterialTheme.colorScheme.errorContainer else Color(0xE6332B00))) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = if (dismissible) MaterialTheme.colorScheme.onErrorContainer else Color(0xFFFFE082))
            if (dismissible) TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun PartsContent(scene: Scene3D, hidden: Map<Int, Boolean>, selected: Int, onToggle: (Int, Boolean) -> Unit, onSelect: (Int) -> Unit, onAll: (Boolean) -> Unit) {
    val rows = remember(scene) {
        val out = ArrayList<Pair<Int, Int>>()
        fun walk(i: Int, d: Int, seen: MutableSet<Int>) { if (!seen.add(i) || i !in scene.nodes.indices) return; out.add(i to d); scene.nodes[i].children.forEach { walk(it, d + 1, seen) } }
        val seen = HashSet<Int>(); scene.rootNodes.forEach { walk(it, 0, seen) }
        out
    }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text("Partes del modelo", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onAll(true) }) { Text("Mostrar todo") }
            TextButton(onClick = { onAll(false) }) { Text("Ocultar todo") }
        }
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(rows) { (i, depth) ->
                val n = scene.nodes[i]
                Row(Modifier.fillMaxWidth().clickable { if (n.meshIndex >= 0) onSelect(i) }.padding(start = (depth * 14).dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = hidden[i] != true, onCheckedChange = { onToggle(i, it) }, enabled = n.meshIndex >= 0)
                    Column(Modifier.weight(1f)) {
                        Text(n.name ?: "Nodo $i", style = MaterialTheme.typography.bodyMedium, color = if (i == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(if (n.meshIndex >= 0) "${scene.meshes[n.meshIndex].primitives.sumOf { it.triangleCount }} triáng." + (if (n.skinIndex >= 0) " · con esqueleto" else "") else "Grupo / hueso", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
