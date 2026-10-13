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
import androidx.compose.ui.unit.dp
import app.universal3d.inspector.AppContainer
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(container: AppContainer, onClose: () -> Unit, onOpen: (String) -> Unit) {
    val entries by container.history.entries.collectAsState()
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, "Volver") }
            Text("Historial", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { container.history.clear() }, enabled = entries.isNotEmpty()) { Text("Borrar todo") }
        }
        if (entries.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Sin historial.") }
        else LazyColumn {
            items(entries, key = { it.url + it.time }) { e ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(e.url) }.padding(vertical = 8.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.title.ifEmpty { e.url }, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                        Text("${e.url.take(70)} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.time))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    IconButton(onClick = { container.history.remove(e) }) { Icon(Icons.Default.Close, "Quitar") }
                }
            }
        }
    }
}
