package app.universal3d.inspector.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.universal3d.inspector.AppContainer
import app.universal3d.inspector.BuildConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer) {
    val s by container.settings.state.collectAsState()
    var token by remember { mutableStateOf("") }
    var hasToken by remember { mutableStateOf(container.tokens.has("sketchfab")) }
    var home by remember(s.homeUrl) { mutableStateOf(s.homeUrl) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Ajustes", style = MaterialTheme.typography.headlineSmall)

        Text("Navegador", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        SwitchRow("JavaScript", "Necesario para casi todos los sitios y para la detección 3D. Los cambios se aplican al reiniciar la app.", s.javascript) { v -> container.settings.update { it.copy(javascript = v) } }
        SwitchRow("Permitir HTTP sin cifrar", "No recomendado. Por defecto sólo se abren páginas HTTPS.", s.allowHttp) { v -> container.settings.update { it.copy(allowHttp = v) } }
        OutlinedTextField(home, { home = it }, label = { Text("Página de inicio") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Se guarda al pulsar \"Guardar inicio\".") })
        OutlinedButton(onClick = {
            val d = app.universal3d.core.security.UrlPolicy.checkNavigation(app.universal3d.core.security.UrlPolicy.fromAddressBar(home), s.allowHttp)
            if (d.allowed) container.settings.update { it.copy(homeUrl = d.url) } else container.toast("Inicio inválido: ${d.reason}")
        }) { Text("Guardar inicio") }
        OutlinedButton(onClick = { android.webkit.CookieManager.getInstance().removeAllCookies(null); android.webkit.WebStorage.getInstance().deleteAllData(); container.toast("Cookies y datos de sitios borrados.") }) { Text("Borrar cookies y datos de sitios") }

        HorizontalDivider()
        Text("Detección y copia", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        SwitchRow("Observar buffers WebGL (captura de geometría)", "Permite \"Copiar / Replicar\" cuando no hay archivo original. Usa hasta 24 MB de memoria extra por página y se aplica a las páginas que abras después de activarlo.", s.captureWebGl) { v -> container.settings.update { it.copy(captureWebGl = v) } }
        Text("La app sólo trabaja con lo que tu navegador ya puede ver y descargar. No elude inicios de sesión, DRM ni restricciones. Usá modelos sobre los que tengas derechos.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        HorizontalDivider()
        Text("Rendimiento", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        SwitchRow("Modo poca memoria", "Limita las texturas a 1024 px.", s.lowMemoryMode) { v -> container.settings.update { it.copy(lowMemoryMode = v) } }
        Text("Tamaño máximo de textura: ${if (s.lowMemoryMode) 1024 else s.maxTextureSize} px", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1024, 2048, 4096).forEach { px -> FilterChip(selected = s.maxTextureSize == px, onClick = { container.settings.update { it.copy(maxTextureSize = px) } }, label = { Text("$px") }) }
        }

        HorizontalDivider()
        Text("Integraciones (opcionales)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text("Sketchfab: token personal de API para usar la descarga oficial en modelos que el autor haya habilitado. Se guarda cifrado con el Android Keystore y nunca se registra.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(token, { token = it }, label = { Text(if (hasToken) "Token guardado (escribí para reemplazar)" else "Token de API de Sketchfab") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { container.tokens.put("sketchfab", token); token = ""; hasToken = container.tokens.has("sketchfab"); container.toast("Token guardado.") }, enabled = token.isNotBlank()) { Text("Guardar token") }
            OutlinedButton(onClick = { container.tokens.clear("sketchfab"); hasToken = false; container.toast("Token borrado.") }, enabled = hasToken) { Text("Borrar token") }
        }

        HorizontalDivider()
        Text("Acerca de", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text("Universal 3D Asset Inspector ${BuildConfig.VERSION_NAME} (código ${BuildConfig.VERSION_CODE})\nID de aplicación: ${BuildConfig.APPLICATION_ID}\nBiblioteca local: ${humanSize(container.library.storageBytes())}", style = MaterialTheme.typography.bodySmall)
        Text("Para actualizar sobre la versión instalada sin desinstalar, el APK nuevo debe tener el mismo ID de aplicación, la misma firma y un código de versión mayor.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) { Text(title, style = MaterialTheme.typography.bodyLarge); Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked = value, onCheckedChange = onChange)
    }
}
